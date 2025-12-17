package com.enki.netrix.vpn

import com.enki.netrix.data.DesyncMethod
import com.enki.netrix.data.DpiSettings
import java.io.OutputStream
import java.net.Socket
import kotlin.math.min

class DpiBypass(private val settings: DpiSettings) {
    
    fun sendWithBypass(socket: Socket, data: ByteArray, isHttps: Boolean): Boolean {
        if (data.isEmpty()) return false
        val output = socket.getOutputStream()
        
        return try {
            val hostname = if (isHttps) extractSni(data) else extractHostHeader(data)
            
            if (hostname != null && isWhitelisted(hostname)) {
                LogManager.i("Whitelist Match: $hostname (Bypass Skipped)")
                return sendDirect(output, data)
            }
            
            val shouldBypass = (isHttps && settings.desyncHttps && isTlsClientHello(data)) ||
                               (!isHttps && settings.desyncHttp)
            
            if (shouldBypass) {
                val protocol = if(isHttps) "HTTPS" else "HTTP"
                when (settings.desyncMethod) {
                    DesyncMethod.SPLIT -> {
                        LogManager.bypass("BYPASS: $protocol Split Applied -> $hostname")
                        sendSplit(output, data)
                    }
                    DesyncMethod.SPLIT_REVERSE -> {
                        LogManager.bypass("BYPASS: $protocol Split Reverse Applied -> $hostname")
                        sendSplitReverse(output, data)
                    }
                    DesyncMethod.DISORDER -> {
                        LogManager.bypass("BYPASS: $protocol Disorder Applied -> $hostname")
                        sendShredded(output, data)
                    }
                    DesyncMethod.DISORDER_REVERSE -> {
                        LogManager.bypass("BYPASS: $protocol Disorder Reverse Applied -> $hostname")
                        sendShreddedReverse(output, data)
                    }
                    DesyncMethod.FAKE -> {
                        LogManager.bypass("BYPASS: $protocol Fake Packet Sent -> $hostname")
                        sendFake(output, data)
                    }
                }
            } else {
                sendDirect(output, data)
            }
        } catch (e: Exception) {
            LogManager.e("Bypass Error: ${e.message}")
            try { 
                sendDirect(output, data) 
            } catch (e2: Exception) { 
                LogManager.e("Fallback send also failed: ${e2.message}")
                false 
            }
        }
    }

    private fun isWhitelisted(host: String): Boolean {
        return settings.whitelist.any { domain -> host.contains(domain, ignoreCase = true) }
    }
    
    private fun sendDirect(output: OutputStream, data: ByteArray): Boolean {
        output.write(data)
        output.flush()
        return true
    }
    
    private fun sendSplit(output: OutputStream, data: ByteArray): Boolean {
        val splitPos = settings.firstPacketSize.coerceIn(1, data.size - 1)
        output.write(data, 0, splitPos)
        output.flush()
        delay()
        output.write(data, splitPos, data.size - splitPos)
        output.flush()
        return true
    }
    
    /**
     * Reverse Split: Sends packet in REVERSE ORDER.
     * First the second fragment, then the first fragment.
     * Same logic as GoodbyeDPI's --reverse-frag feature.
     */
    private fun sendSplitReverse(output: OutputStream, data: ByteArray): Boolean {
        val splitPos = settings.firstPacketSize.coerceIn(1, data.size - 1)
        // FIRST send the second fragment (contains SNI)
        output.write(data, splitPos, data.size - splitPos)
        output.flush()
        delay()
        // THEN send the first fragment
        output.write(data, 0, splitPos)
        output.flush()
        return true
    }
    
    private fun sendShredded(output: OutputStream, data: ByteArray): Boolean {
        val count = settings.splitCount.coerceIn(2, 20)
        val chunkSize = (data.size / count).coerceAtLeast(1)
        var offset = 0
        while (offset < data.size) {
            val len = min(chunkSize, data.size - offset)
            output.write(data, offset, len)
            output.flush()
            delay()
            offset += len
        }
        return true
    }
    
    /**
     * Reverse Disorder: Sends fragments in REVERSE ORDER.
     * Starts from the last fragment, goes towards the first.
     */
    private fun sendShreddedReverse(output: OutputStream, data: ByteArray): Boolean {
        val count = settings.splitCount.coerceIn(2, 20)
        val chunkSize = (data.size / count).coerceAtLeast(1)
        
        // First calculate fragments
        val chunks = mutableListOf<Pair<Int, Int>>() // (offset, length)
        var offset = 0
        while (offset < data.size) {
            val len = min(chunkSize, data.size - offset)
            chunks.add(offset to len)
            offset += len
        }
        
        // Send in reverse order
        for (i in chunks.indices.reversed()) {
            val (chunkOffset, len) = chunks[i]
            output.write(data, chunkOffset, len)
            output.flush()
            if (i > 0) delay() // No delay after last fragment
        }
        return true
    }
    
    private fun sendFake(output: OutputStream, data: ByteArray): Boolean {
        try {
            val fakeData = hexStringToByteArray(settings.fakeHex)
            if (fakeData.isNotEmpty()) {
                output.write(fakeData)
                output.flush()
                delay()
            }
        } catch (e: Exception) {
            LogManager.e("Fake packet send error: ${e.message}")
        }
        return sendSplit(output, data)
    }
    
    private fun delay() {
        if (settings.splitDelay > 0) {
            try { 
                Thread.sleep(settings.splitDelay) 
            } catch (e: InterruptedException) {
                // Thread interrupted - normal condition, logging unnecessary
                Thread.currentThread().interrupt()
            }
        }
    }
    
    private fun isTlsClientHello(data: ByteArray): Boolean {
        if (data.size < 6) return false
        return data[0] == 0x16.toByte() && data[5] == 0x01.toByte()
    }
    
    private fun extractSni(data: ByteArray): String? {
        try {
            if (data.size < 43) return null
            var offset = 43
            if (offset >= data.size) return null
            val sessionIdLen = data[offset].toInt() and 0xFF
            offset += 1 + sessionIdLen
            if (offset + 2 > data.size) return null
            val cipherSuitesLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2 + cipherSuitesLen
            if (offset >= data.size) return null
            val compressionLen = data[offset].toInt() and 0xFF
            offset += 1 + compressionLen
            if (offset + 2 > data.size) return null
            val extensionsLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2
            val extensionsEnd = offset + extensionsLen
            while (offset + 4 < extensionsEnd && offset + 4 < data.size) {
                val extType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                val extLen = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
                if (extType == 0x0000 && extLen > 5) {
                    val sniListStart = offset + 4
                    if (sniListStart + 5 < data.size) {
                        val nameType = data[sniListStart + 2].toInt() and 0xFF
                        val nameLen = ((data[sniListStart + 3].toInt() and 0xFF) shl 8) or (data[sniListStart + 4].toInt() and 0xFF)
                        if (nameType == 0 && nameLen > 0) {
                             val hostnameOffset = sniListStart + 5
                             if (hostnameOffset + nameLen <= data.size) {
                                 return String(data, hostnameOffset, nameLen, Charsets.US_ASCII)
                             }
                        }
                    }
                }
                offset += 4 + extLen
            }
        } catch (e: Exception) {
            // SNI could not be parsed - malformed TLS or unsupported format
            // In debug mode: LogManager.e("SNI extraction error: ${e.message}")
        }
        return null
    }
    
    private fun extractHostHeader(data: ByteArray): String? {
        try {
            val httpStr = String(data, Charsets.ISO_8859_1)
            val lines = httpStr.split("\r\n", "\n")
            for (line in lines) {
                if (line.startsWith("Host:", ignoreCase = true)) {
                    return line.substring(5).trim()
                }
            }
        } catch (e: Exception) {
            // HTTP header could not be parsed - binary data or malformed request
            // In debug mode: LogManager.e("Host header extraction error: ${e.message}")
        }
        return null
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}