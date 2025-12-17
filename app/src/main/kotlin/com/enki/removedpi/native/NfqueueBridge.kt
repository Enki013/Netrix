package com.enki.netrix.native

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI Bridge for native NFQUEUE handler.
 * Provides kernel-level packet interception on rooted devices.
 * 
 * Usage:
 * 1. Check root: RootHelper.isRooted()
 * 2. Setup iptables: RootHelper.setupIptables()
 * 3. Initialize: NfqueueBridge.init(0)
 * 4. Set callback: NfqueueBridge.setCallback(myCallback)
 * 5. Start: NfqueueBridge.start()
 * 6. Stop: NfqueueBridge.stop()
 * 7. Cleanup: NfqueueBridge.cleanup()
 */
object NfqueueBridge {
    
    private const val TAG = "NfqueueBridge"
    
    // Library loading state
    private val libraryLoaded = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    
    // Current callback
    private var currentCallback: PacketCallback? = null
    
    // Verdict constants (match native enum)
    const val VERDICT_DROP = 0
    const val VERDICT_ACCEPT = 1
    const val VERDICT_REPEAT = 4
    const val VERDICT_STOLEN = 3
    
    // Protocol constants
    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17
    
    init {
        try {
            System.loadLibrary("nfqueue_handler")
            libraryLoaded.set(true)
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
    }
    
    /**
     * Check if native library is loaded
     */
    fun isLibraryLoaded(): Boolean = libraryLoaded.get()
    
    /**
     * Check if NFQUEUE is initialized
     */
    fun isInitialized(): Boolean = initialized.get()
    
    /**
     * Check if NFQUEUE is running
     */
    fun isRunning(): Boolean {
        if (!libraryLoaded.get()) return false
        return nativeIsRunning()
    }
    
    /**
     * Initialize NFQUEUE with given queue number
     * @param queueNum Queue number (0-65535), default 0
     * @return true on success
     */
    fun init(queueNum: Int = 0): Boolean {
        if (!libraryLoaded.get()) {
            Log.e(TAG, "Library not loaded")
            return false
        }
        
        if (initialized.get()) {
            Log.w(TAG, "Already initialized")
            return true
        }
        
        val result = nativeInit(queueNum)
        if (result) {
            initialized.set(true)
            Log.i(TAG, "NFQUEUE initialized with queue=$queueNum")
        } else {
            Log.e(TAG, "Failed to initialize: ${getError()}")
        }
        
        return result
    }
    
    /**
     * Set packet callback
     * @param callback Callback to receive packets, or null to clear
     */
    fun setCallback(callback: PacketCallback?) {
        if (!libraryLoaded.get()) return
        
        currentCallback = callback
        
        // Create wrapper that implements the JNI interface
        val wrapper = if (callback != null) {
            object : NativePacketCallback {
                override fun onPacket(
                    packetId: Int,
                    protocol: Int,
                    srcIp: Int,
                    dstIp: Int,
                    srcPort: Int,
                    dstPort: Int,
                    payload: ByteArray?
                ): Int {
                    val packet = NfqueuePacket(
                        packetId = packetId,
                        protocol = protocol,
                        srcIp = srcIp,
                        dstIp = dstIp,
                        srcPort = srcPort,
                        dstPort = dstPort,
                        payload = payload
                    )
                    return callback.onPacketReceived(packet)
                }
            }
        } else null
        
        nativeSetCallback(wrapper)
    }
    
    /**
     * Start NFQUEUE processing (blocking)
     * Call from background thread!
     */
    fun start(): Boolean {
        if (!libraryLoaded.get() || !initialized.get()) {
            Log.e(TAG, "Not initialized")
            return false
        }
        
        Log.i(TAG, "Starting NFQUEUE processing")
        return nativeStart()
    }
    
    /**
     * Start NFQUEUE processing in coroutine
     */
    suspend fun startAsync(): Boolean = withContext(Dispatchers.IO) {
        start()
    }
    
    /**
     * Stop NFQUEUE processing
     */
    fun stop() {
        if (!libraryLoaded.get()) return
        Log.i(TAG, "Stopping NFQUEUE")
        nativeStop()
    }
    
    /**
     * Cleanup and release resources
     */
    fun cleanup() {
        if (!libraryLoaded.get()) return
        
        Log.i(TAG, "Cleaning up NFQUEUE")
        nativeCleanup()
        initialized.set(false)
        currentCallback = null
    }
    
    /**
     * Manually set verdict for a packet
     * Used when callback returns VERDICT_STOLEN
     */
    fun setVerdict(packetId: Int, verdict: Int, modifiedPayload: ByteArray? = null): Boolean {
        if (!libraryLoaded.get()) return false
        return nativeSetVerdict(packetId, verdict, modifiedPayload)
    }
    
    /**
     * Get last error message
     */
    fun getError(): String {
        if (!libraryLoaded.get()) return "Library not loaded"
        return nativeGetError()
    }
    
    // ========================================================================
    // Native methods
    // ========================================================================
    
    private external fun nativeInit(queueNum: Int): Boolean
    private external fun nativeSetCallback(callback: NativePacketCallback?): Boolean
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeCleanup()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetVerdict(packetId: Int, verdict: Int, modifiedPayload: ByteArray?): Boolean
    private external fun nativeGetError(): String
}

/**
 * Internal interface for JNI callback
 */
private interface NativePacketCallback {
    fun onPacket(
        packetId: Int,
        protocol: Int,
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray?
    ): Int
}

