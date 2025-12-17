package com.enki.netrix.native

import java.net.InetAddress

/**
 * Data class representing a packet from NFQUEUE
 */
data class NfqueuePacket(
    /** Unique packet ID for verdict */
    val packetId: Int,
    
    /** IP protocol (6=TCP, 17=UDP) */
    val protocol: Int,
    
    /** Source IP in network byte order */
    val srcIp: Int,
    
    /** Destination IP in network byte order */
    val dstIp: Int,
    
    /** Source port in host byte order */
    val srcPort: Int,
    
    /** Destination port in host byte order */
    val dstPort: Int,
    
    /** Raw packet payload (IP header + data) */
    val payload: ByteArray?
) {
    /** Check if TCP packet */
    val isTcp: Boolean get() = protocol == NfqueueBridge.PROTOCOL_TCP
    
    /** Check if UDP packet */
    val isUdp: Boolean get() = protocol == NfqueueBridge.PROTOCOL_UDP
    
    /** Check if HTTPS (port 443) */
    val isHttps: Boolean get() = dstPort == 443
    
    /** Check if HTTP (port 80) */
    val isHttp: Boolean get() = dstPort == 80
    
    /** Get source IP as string */
    val srcIpString: String get() = intToIpString(srcIp)
    
    /** Get destination IP as string */
    val dstIpString: String get() = intToIpString(dstIp)
    
    /** Get IP header length */
    val ipHeaderLength: Int
        get() = if (payload != null && payload.isNotEmpty()) {
            (payload[0].toInt() and 0x0F) * 4
        } else 0
    
    /** Get TCP/UDP payload (after IP header) */
    val transportPayload: ByteArray?
        get() {
            if (payload == null || payload.size <= ipHeaderLength) return null
            return payload.copyOfRange(ipHeaderLength, payload.size)
        }
    
    /** Get TCP data offset (header length) */
    val tcpHeaderLength: Int
        get() {
            if (!isTcp) return 0
            val transport = transportPayload ?: return 0
            if (transport.size < 13) return 0
            return ((transport[12].toInt() and 0xF0) shr 4) * 4
        }
    
    /** Get TCP flags */
    val tcpFlags: Int
        get() {
            if (!isTcp) return 0
            val transport = transportPayload ?: return 0
            if (transport.size < 14) return 0
            return transport[13].toInt() and 0xFF
        }
    
    /** Check if SYN flag set */
    val isSyn: Boolean get() = (tcpFlags and 0x02) != 0
    
    /** Check if ACK flag set */
    val isAck: Boolean get() = (tcpFlags and 0x10) != 0
    
    /** Check if FIN flag set */
    val isFin: Boolean get() = (tcpFlags and 0x01) != 0
    
    /** Check if RST flag set */
    val isRst: Boolean get() = (tcpFlags and 0x04) != 0
    
    /** Check if PSH flag set */
    val isPsh: Boolean get() = (tcpFlags and 0x08) != 0
    
    /** Get TCP application data (after TCP header) */
    val tcpData: ByteArray?
        get() {
            if (!isTcp) return null
            val transport = transportPayload ?: return null
            val headerLen = tcpHeaderLength
            if (transport.size <= headerLen) return null
            return transport.copyOfRange(headerLen, transport.size)
        }
    
    /** Check if TLS ClientHello */
    val isTlsClientHello: Boolean
        get() {
            val data = tcpData ?: return false
            return data.size >= 6 && 
                   data[0] == 0x16.toByte() && 
                   data[5] == 0x01.toByte()
        }
    
    /** Extract SNI from TLS ClientHello */
    fun extractSni(): String? {
        val data = tcpData ?: return null
        if (!isTlsClientHello) return null
        
        try {
            if (data.size < 43) return null
            var offset = 43
            
            // Skip session ID
            if (offset >= data.size) return null
            val sessionIdLen = data[offset].toInt() and 0xFF
            offset += 1 + sessionIdLen
            
            // Skip cipher suites
            if (offset + 2 > data.size) return null
            val cipherLen = ((data[offset].toInt() and 0xFF) shl 8) or 
                           (data[offset + 1].toInt() and 0xFF)
            offset += 2 + cipherLen
            
            // Skip compression
            if (offset >= data.size) return null
            val compLen = data[offset].toInt() and 0xFF
            offset += 1 + compLen
            
            // Extensions length
            if (offset + 2 > data.size) return null
            val extLen = ((data[offset].toInt() and 0xFF) shl 8) or 
                        (data[offset + 1].toInt() and 0xFF)
            offset += 2
            val extEnd = offset + extLen
            
            // Find SNI extension (type 0x0000)
            while (offset + 4 < extEnd && offset + 4 < data.size) {
                val extType = ((data[offset].toInt() and 0xFF) shl 8) or 
                             (data[offset + 1].toInt() and 0xFF)
                val extDataLen = ((data[offset + 2].toInt() and 0xFF) shl 8) or 
                                (data[offset + 3].toInt() and 0xFF)
                
                if (extType == 0x0000 && extDataLen > 5) {
                    val sniStart = offset + 4
                    if (sniStart + 5 < data.size) {
                        val nameType = data[sniStart + 2].toInt() and 0xFF
                        val nameLen = ((data[sniStart + 3].toInt() and 0xFF) shl 8) or 
                                     (data[sniStart + 4].toInt() and 0xFF)
                        
                        if (nameType == 0 && nameLen > 0) {
                            val hostnameOffset = sniStart + 5
                            if (hostnameOffset + nameLen <= data.size) {
                                return String(data, hostnameOffset, nameLen, Charsets.US_ASCII)
                            }
                        }
                    }
                }
                
                offset += 4 + extDataLen
            }
        } catch (e: Exception) {
            // Parse error
        }
        
        return null
    }
    
    /** Extract HTTP Host header */
    fun extractHttpHost(): String? {
        if (isHttps) return null
        val data = tcpData ?: return null
        
        try {
            val httpStr = String(data, Charsets.ISO_8859_1)
            val lines = httpStr.split("\r\n", "\n")
            for (line in lines) {
                if (line.startsWith("Host:", ignoreCase = true)) {
                    return line.substring(5).trim()
                }
            }
        } catch (e: Exception) {
            // Parse error
        }
        
        return null
    }
    
    /** Get hostname (SNI for HTTPS, Host header for HTTP) */
    fun getHostname(): String? {
        return if (isHttps) extractSni() else extractHttpHost()
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NfqueuePacket
        return packetId == other.packetId
    }
    
    override fun hashCode(): Int = packetId
    
    override fun toString(): String {
        return "NfqueuePacket(id=$packetId, proto=$protocol, " +
               "$srcIpString:$srcPort -> $dstIpString:$dstPort, " +
               "len=${payload?.size ?: 0})"
    }
    
    companion object {
        fun intToIpString(ip: Int): String {
            return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
        }
    }
}

/**
 * Callback interface for receiving packets from NFQUEUE
 */
interface PacketCallback {
    /**
     * Called when a packet is received from NFQUEUE
     * 
     * @param packet The received packet
     * @return Verdict: NfqueueBridge.VERDICT_ACCEPT, VERDICT_DROP, etc.
     * 
     * Note: This is called from a native thread. Be careful with
     * thread safety and avoid long-running operations.
     */
    fun onPacketReceived(packet: NfqueuePacket): Int
}

/**
 * Simple callback that accepts all packets
 */
class AcceptAllCallback : PacketCallback {
    override fun onPacketReceived(packet: NfqueuePacket): Int {
        return NfqueueBridge.VERDICT_ACCEPT
    }
}

/**
 * Logging callback for debugging
 */
class LoggingCallback(
    private val tag: String = "NfqueuePacket",
    private val delegate: PacketCallback = AcceptAllCallback()
) : PacketCallback {
    override fun onPacketReceived(packet: NfqueuePacket): Int {
        android.util.Log.d(tag, packet.toString())
        return delegate.onPacketReceived(packet)
    }
}

