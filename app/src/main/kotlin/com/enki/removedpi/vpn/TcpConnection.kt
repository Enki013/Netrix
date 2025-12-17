package com.enki.netrix.vpn

import android.net.VpnService
import com.enki.netrix.data.DpiSettings
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TcpConnection(
    private val vpnService: VpnService,
    private val vpnOutput: java.io.FileOutputStream,
    initialSettings: DpiSettings
) {
    companion object {
        private const val MAX_CONNECTIONS = 512
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
        private const val WINDOW_SIZE = 65535
        private const val KEEP_ALIVE = true
        private const val BUFFER_POOL_SIZE = 64
        private const val BUFFER_SIZE = 32768
    }

    @Volatile
    private var settings: DpiSettings = initialSettings

    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val executor: ExecutorService = Executors.newFixedThreadPool(MAX_CONNECTIONS)
    
    @Volatile private var isRunning = true
    private val bytesIn = AtomicLong(0)
    private val bytesOut = AtomicLong(0)
    
    // Buffer Pool - reusable direct buffers to reduce GC pressure
    private val bufferPool = ConcurrentLinkedQueue<ByteBuffer>().apply {
        repeat(BUFFER_POOL_SIZE) {
            add(ByteBuffer.allocateDirect(BUFFER_SIZE))
        }
    }
    
    private fun acquireBuffer(): ByteBuffer {
        return bufferPool.poll()?.also { it.clear() } 
            ?: ByteBuffer.allocateDirect(BUFFER_SIZE)
    }
    
    private fun releaseBuffer(buffer: ByteBuffer) {
        if (bufferPool.size < BUFFER_POOL_SIZE) {
            buffer.clear()
            bufferPool.offer(buffer)
        }
    }
    
    /**
     * Updates settings while VPN is running.
     * New connections will use the updated settings.
     */
    fun updateSettings(newSettings: DpiSettings) {
        settings = newSettings
        LogManager.i("TCP settings updated")
    }
    
    enum class SessionState { SYN_RECEIVED, CONNECTING, ESTABLISHED, FIN_WAIT, CLOSED }
    
    /**
     * TCP Session - works with NIO SocketChannel.
     * Contains lock for thread-safe state management.
     */
    class TcpSession(
        val key: String,
        val srcIp: java.net.InetAddress,
        val srcPort: Int,
        val dstIp: java.net.InetAddress,
        val dstPort: Int
    ) {
        // Lock for thread-safety
        val lock = ReentrantLock()
        
        // NIO SocketChannel - used in blocking mode (simpler)
        @Volatile var channel: SocketChannel? = null
        // Legacy Socket reference - for DpiBypass (temporary)
        @Volatile var socket: Socket? = null
        @Volatile var state: SessionState = SessionState.SYN_RECEIVED
        
        // Sequence numbers - should be accessed under lock
        var mySeqNum: Long = (System.nanoTime() and 0x7FFFFFFFL)
        var myAckNum: Long = 0
        var theirSeqNum: Long = 0
        var theirAckNum: Long = 0
        
        @Volatile var firstDataSent: Boolean = false
        val isHttps: Boolean = dstPort == 443
        val pendingData: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue(100)
        
        @Volatile var lastActivity: Long = System.currentTimeMillis()
    }
    
    fun processPacket(packet: Packet) {
        if (!isRunning) return
        val key = packet.connectionKey
        try {
            when {
                packet.isSyn && !packet.isAck -> handleSyn(packet, key)
                packet.isAck && packet.hasPayload -> handleData(packet, key)
                packet.isAck && !packet.hasPayload -> handleAck(packet, key)
                packet.isFin -> handleFin(packet, key)
                packet.isRst -> handleRst(key)
            }
        } catch (e: Exception) {
            LogManager.e("TCP Error [$key]: ${e.message}")
            closeSession(key)
        }
    }
    
    private fun handleSyn(packet: Packet, key: String) {
        val dest = "${packet.destinationAddress.hostAddress}:${packet.destinationPort}"
        LogManager.i("TCP Request Started → $dest")

        sessions[key]?.let { if (it.state != SessionState.CLOSED) closeSession(key) }
        
        val session = TcpSession(
            key = key,
            srcIp = packet.sourceAddress,
            srcPort = packet.sourcePort,
            dstIp = packet.destinationAddress,
            dstPort = packet.destinationPort
        )
        
        session.lock.withLock {
            session.theirSeqNum = packet.sequenceNumber
            session.myAckNum = packet.sequenceNumber + 1
            sessions[key] = session
            sendTcpPacketLocked(session, Packet.TCP_SYN or Packet.TCP_ACK)
            session.mySeqNum++
            session.state = SessionState.CONNECTING
        }
        
        executor.submit { connectToServer(session) }
    }
    
    private fun connectToServer(session: TcpSession) {
        val dest = "${session.dstIp.hostAddress}:${session.dstPort}"
        try {
            // NIO SocketChannel kullan
            val channel = SocketChannel.open()
            
            // Protect socket to bypass VPN
            vpnService.protect(channel.socket())
            
            // Socket settings
            val socket = channel.socket()
            socket.tcpNoDelay = settings.enableTcpNodelay
            socket.soTimeout = READ_TIMEOUT
            socket.keepAlive = KEEP_ALIVE
            socket.setSoLinger(true, 0)
            socket.sendBufferSize = settings.bufferSize
            socket.receiveBufferSize = settings.bufferSize
            
            // Connect in blocking mode (simpler, more reliable)
            channel.configureBlocking(true)
            socket.connect(InetSocketAddress(session.dstIp, session.dstPort), CONNECT_TIMEOUT)
            
            // State check and update under lock
            val shouldContinue = session.lock.withLock {
                if (!isRunning || session.state == SessionState.CLOSED) {
                    false
                } else {
                    session.channel = channel
                    session.socket = socket // Legacy reference for DpiBypass
                    session.state = SessionState.ESTABLISHED
                    true
                }
            }
            
            if (!shouldContinue) {
                channel.close()
                return
            }
            
            LogManager.i("Connection Established ✓ $dest")
            
            processPendingData(session)
            readFromServerNio(session)
        } catch (e: Exception) {
            LogManager.e("Connection Failed ($dest): ${e.message}")
            sendRst(session)
            closeSession(session.key)
        }
    }
    
    private fun processPendingData(session: TcpSession) {
        while (!session.pendingData.isEmpty() && session.state == SessionState.ESTABLISHED) {
            val data = session.pendingData.poll(100, TimeUnit.MILLISECONDS) ?: break
            sendToServer(session, data)
        }
    }
    
    /**
     * Reads data from server using NIO.
     * Reduces GC pressure by using Direct ByteBuffer.
     */
    private fun readFromServerNio(session: TcpSession) {
        val channel = session.channel ?: return
        val buffer = acquireBuffer()
        
        try {
            while (isRunning && session.state == SessionState.ESTABLISHED && channel.isConnected) {
                buffer.clear()
                val bytesRead = channel.read(buffer)
                
                when {
                    bytesRead > 0 -> {
                        bytesIn.addAndGet(bytesRead.toLong())
                        session.lastActivity = System.currentTimeMillis()
                        
                        // Buffer'dan veriyi kopyala
                        buffer.flip()
                        val data = ByteArray(bytesRead)
                        buffer.get(data)
                        
                        sendToClient(session, data)
                    }
                    bytesRead == -1 -> {
                        LogManager.i("Server Closed Connection: ${session.dstIp.hostAddress}")
                        sendFin(session)
                        break
                    }
                    // bytesRead == 0 ise devam et (non-blocking durumda)
                }
            }
        } catch (e: IOException) {
            if (session.state != SessionState.CLOSED && isRunning) {
                LogManager.e("Data Read Error (${session.dstIp.hostAddress}): ${e.message}")
            }
        } finally {
            releaseBuffer(buffer)
            closeSession(session.key)
        }
    }
    
    private fun handleData(packet: Packet, key: String) {
        val session = sessions[key] ?: return
        val payload = packet.getPayload()
        if (payload.isEmpty()) return
        
        val currentState: SessionState
        session.lock.withLock {
            session.lastActivity = System.currentTimeMillis()
            session.theirSeqNum = packet.sequenceNumber
            session.myAckNum = packet.sequenceNumber + payload.size
            sendTcpPacketLocked(session, Packet.TCP_ACK)
            currentState = session.state
        }
        
        when (currentState) {
            SessionState.ESTABLISHED -> executor.submit { sendToServer(session, payload) }
            SessionState.CONNECTING, SessionState.SYN_RECEIVED -> session.pendingData.offer(payload.copyOf())
            else -> { /* Ignore */ }
        }
    }
    
    private fun sendToServer(session: TcpSession, data: ByteArray) {
        val socket = session.socket ?: return
        if (socket.isClosed) return
        
        try {
            if (!session.firstDataSent) {
                session.firstDataSent = true
                val bypass = DpiBypass(settings)
                val dest = "${session.dstIp.hostAddress}:${session.dstPort}"
                
                // Bypass log is handled in DpiBypass class
                bypass.sendWithBypass(socket, data, session.isHttps)
            } else {
                socket.getOutputStream().apply {
                    write(data)
                    flush()
                }
            }
            bytesOut.addAndGet(data.size.toLong())
            session.lastActivity = System.currentTimeMillis()
        } catch (e: IOException) { 
            LogManager.e("Data Send Error: ${e.message}")
            closeSession(session.key) 
        }
    }
    
    private fun handleAck(packet: Packet, key: String) {
        val session = sessions[key] ?: return
        session.lock.withLock {
            session.theirAckNum = packet.acknowledgmentNumber
            session.lastActivity = System.currentTimeMillis()
        }
    }
    
    private fun handleFin(packet: Packet, key: String) {
        val session = sessions[key] ?: return
        LogManager.i("Client Terminated Connection (FIN): ${session.dstIp.hostAddress}")
        
        session.lock.withLock {
            session.myAckNum = packet.sequenceNumber + 1
            session.state = SessionState.FIN_WAIT
            sendTcpPacketLocked(session, Packet.TCP_FIN or Packet.TCP_ACK)
            session.mySeqNum++
        }
        
        closeSession(key)
    }
    
    private fun handleRst(key: String) {
        // LogManager.w("RST Packet Received (Connection Reset) [$key]")
        closeSession(key)
    }
    
    private fun sendToClient(session: TcpSession, data: ByteArray) {
        if (session.state == SessionState.CLOSED) return
        var offset = 0
        val mss = 1400
        
        session.lock.withLock {
            synchronized(vpnOutput) {
                while (offset < data.size) {
                    val chunkSize = minOf(mss, data.size - offset)
                    val packet = PacketBuilder.buildTcpPacket(
                        srcIp = session.dstIp, dstIp = session.srcIp,
                        srcPort = session.dstPort, dstPort = session.srcPort,
                        seqNum = session.mySeqNum, ackNum = session.myAckNum,
                        flags = Packet.TCP_PSH or Packet.TCP_ACK,
                        windowSize = WINDOW_SIZE,
                        payload = data.copyOfRange(offset, offset + chunkSize)
                    )
                    writeToVpn(packet)
                    session.mySeqNum += chunkSize
                    offset += chunkSize
                }
            }
        }
    }
    
    /**
     * Sends packet WITHOUT lock - caller must hold the lock.
     */
    private fun sendTcpPacketLocked(session: TcpSession, flags: Int) {
        val packet = PacketBuilder.buildTcpPacket(
            srcIp = session.dstIp, dstIp = session.srcIp,
            srcPort = session.dstPort, dstPort = session.srcPort,
            seqNum = session.mySeqNum, ackNum = session.myAckNum,
            flags = flags, 
            windowSize = WINDOW_SIZE,
            payload = ByteArray(0)
        )
        writeToVpn(packet)
    }
    
    /**
     * Sends packet WITH lock.
     */
    private fun sendTcpPacket(session: TcpSession, flags: Int) {
        session.lock.withLock {
            sendTcpPacketLocked(session, flags)
        }
    }
    
    private fun sendFin(session: TcpSession) {
        session.lock.withLock {
            if (session.state == SessionState.CLOSED) return
            session.state = SessionState.FIN_WAIT
            sendTcpPacketLocked(session, Packet.TCP_FIN or Packet.TCP_ACK)
            session.mySeqNum++
        }
    }
    
    private fun sendRst(session: TcpSession) = sendTcpPacket(session, Packet.TCP_RST or Packet.TCP_ACK)
    
    private fun writeToVpn(packet: ByteBuffer) {
        try {
            val data = ByteArray(packet.remaining())
            packet.get(data)
            synchronized(vpnOutput) { vpnOutput.write(data); vpnOutput.flush() }
        } catch (e: Exception) {
            LogManager.e("VPN Write Error: ${e.message}")
        }
    }
    
    private fun closeSession(key: String) {
        sessions.remove(key)?.let { session ->
            session.lock.withLock {
                session.state = SessionState.CLOSED
                session.pendingData.clear()
                // Close Channel and Socket - may already be closed
                runCatching { session.channel?.close() }
                session.channel = null
                session.socket = null
            }
        }
    }
    
    fun getStats(): Pair<Long, Long> = bytesIn.get() to bytesOut.get()
    
    fun stop() {
        isRunning = false
        sessions.keys.toList().forEach { closeSession(it) }
        executor.shutdownNow()
    }
}