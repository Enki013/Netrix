package com.enki.netrix.vpn

import android.net.VpnService
import com.enki.netrix.data.DpiSettings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class UdpConnection(
    private val vpnService: VpnService,
    private val vpnOutput: java.io.FileOutputStream,
    initialSettings: DpiSettings
) {
    companion object {
        private const val TIMEOUT = 30000
        private const val MAX_PACKET_SIZE = 65535
    }
    
    @Volatile
    private var settings: DpiSettings = initialSettings
    
    private val sessions = ConcurrentHashMap<String, UdpSession>()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val bytesIn = AtomicLong(0)
    private val bytesOut = AtomicLong(0)
    
    @Volatile
    private var isRunning = true
    
    /**
     * Updates settings while VPN is running.
     * New packets will use the updated settings.
     */
    fun updateSettings(newSettings: DpiSettings) {
        settings = newSettings
        LogManager.i("UDP settings updated")
    }
    
    data class UdpSession(
        val key: String,
        val socket: DatagramSocket,
        val srcIp: InetAddress,
        val srcPort: Int,
        val dstIp: InetAddress,
        val dstPort: Int,
        var lastActivity: Long = System.currentTimeMillis()
    )
    
    fun processPacket(packet: Packet) {
        val payload = packet.getPayload()
        if (payload.isEmpty()) return
        
        // QUIC Engelleme Logu
        if (settings.blockQuic && packet.destinationPort == 443) {
            // Don't log every packet to avoid spam, but
            // this log is useful if user wants to verify QUIC blocking works.
            LogManager.w("QUIC Packet Blocked (UDP 443) -> ${packet.destinationAddress.hostAddress}")
            return
        }
        
        try {
            val key = packet.connectionKey
            val session = sessions.getOrPut(key) { createSession(packet) }
            
            session.lastActivity = System.currentTimeMillis()
            
            val destIp = if (packet.isDns && settings.customDnsEnabled) {
                InetAddress.getByName(settings.customDns)
            } else {
                packet.destinationAddress
            }
            
            // DNS Logu
            if (packet.isDns) {
                LogManager.i("DNS Query → $destIp")
            }
            
            val destPacket = DatagramPacket(payload, payload.size, destIp, packet.destinationPort)
            session.socket.send(destPacket)
            bytesOut.addAndGet(payload.size.toLong())
            
        } catch (e: Exception) {
            LogManager.e("UDP Error: ${e.message}")
        }
    }
    
    private fun createSession(packet: Packet): UdpSession {
        val socket = DatagramSocket()
        vpnService.protect(socket)
        socket.soTimeout = TIMEOUT
        
        val session = UdpSession(
            key = packet.connectionKey,
            socket = socket,
            srcIp = packet.sourceAddress,
            srcPort = packet.sourcePort,
            dstIp = packet.destinationAddress,
            dstPort = packet.destinationPort
        )
        startResponseListener(session)
        return session
    }
    
    private fun startResponseListener(session: UdpSession) {
        executor.submit {
            val buffer = ByteArray(MAX_PACKET_SIZE)
            val receivePacket = DatagramPacket(buffer, buffer.size)
            
            while (isRunning && sessions.containsKey(session.key)) {
                try {
                    session.socket.receive(receivePacket)
                    val data = receivePacket.data.copyOf(receivePacket.length)
                    bytesIn.addAndGet(data.size.toLong())
                    sendToClient(session, data)
                    session.lastActivity = System.currentTimeMillis()
                } catch (e: Exception) {
                    if (isRunning && sessions.containsKey(session.key)) {
                        val timeDiff = System.currentTimeMillis() - session.lastActivity
                        if (timeDiff > TIMEOUT) {
                            closeSession(session.key)
                            break
                        }
                    } else { break }
                }
            }
        }
    }
    
    private fun sendToClient(session: UdpSession, data: ByteArray) {
        val packet = PacketBuilder.buildUdpPacket(
            srcIp = session.dstIp,
            dstIp = session.srcIp,
            srcPort = session.dstPort,
            dstPort = session.srcPort,
            payload = data
        )
        synchronized(vpnOutput) {
            try {
                val packetData = ByteArray(packet.remaining())
                packet.get(packetData)
                vpnOutput.write(packetData)
                vpnOutput.flush()
            } catch (e: Exception) {
                LogManager.e("VPN Write Error (UDP): ${e.message}")
            }
        }
    }
    
    private fun closeSession(key: String) {
        // Close socket - may already be closed
        sessions.remove(key)?.let { runCatching { it.socket.close() } }
    }
        
    fun getStats(): Pair<Long, Long> = bytesIn.get() to bytesOut.get()
    
    fun stop() {
        isRunning = false
        executor.shutdownNow()
        sessions.keys.toList().forEach { closeSession(it) }
    }
}