package com.enki.netrix.native

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controller for the NFQUEUE daemon.
 * 
 * The daemon runs as root in a separate process to bypass SELinux restrictions.
 * Communication is done via Unix domain socket.
 */
object DaemonController {
    
    private const val TAG = "DaemonController"
    
    // Paths
    private const val DAEMON_NAME = "nfqueue_daemon"
    private const val DAEMON_PATH = "/data/local/tmp/$DAEMON_NAME"
    private const val SOCKET_PATH = "/data/local/tmp/netrix.sock"
    private const val PID_FILE = "/data/local/tmp/netrix.pid"
    
    // Connection settings
    private const val CONNECT_TIMEOUT = 5000
    private const val READ_TIMEOUT = 10000
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 500L
    
    // State
    private val initialized = AtomicBoolean(false)
    private val daemonRunning = AtomicBoolean(false)
    
    /**
     * Check if daemon is available (binary exists and root access)
     */
    fun isAvailable(): Boolean {
        return RootHelper.isRooted()
    }
    
    /**
     * Check if daemon process is running
     */
    fun isDaemonRunning(): Boolean {
        if (!RootHelper.isRooted()) return false
        
        // Check PID file
        val result = RootHelper.executeAsRoot("cat $PID_FILE 2>/dev/null")
        if (!result.success || result.output.isEmpty()) {
            return false
        }
        
        val pid = result.output.trim()
        val checkResult = RootHelper.executeAsRoot("kill -0 $pid 2>/dev/null && echo 'alive'")
        return checkResult.output.contains("alive")
    }
    
    /**
     * Check if NFQUEUE is active (daemon is processing packets)
     */
    suspend fun isNfqueueActive(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = sendCommand("""{"cmd":"status"}""")
            val json = JSONObject(response)
            json.optBoolean("running", false)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Initialize and start the daemon
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "[DEBUG] ========== DAEMON INITIALIZATION ==========")
        
        // Step 1: Check root
        Log.i(TAG, "[DEBUG] Step 1: Checking root access...")
        if (!RootHelper.isRooted()) {
            Log.e(TAG, "[DEBUG] FAILED: Device is not rooted")
            return@withContext false
        }
        Log.i(TAG, "[DEBUG] Root access confirmed")
        
        // Step 2: Extract daemon binary
        Log.i(TAG, "[DEBUG] Step 2: Extracting daemon binary...")
        if (!extractDaemon(context)) {
            Log.e(TAG, "[DEBUG] FAILED: Could not extract daemon binary")
            return@withContext false
        }
        Log.i(TAG, "[DEBUG] Daemon binary extracted")
        
        // Step 3: Check if daemon already running
        Log.i(TAG, "[DEBUG] Step 3: Checking if daemon already running...")
        val alreadyRunning = isDaemonRunning()
        Log.i(TAG, "[DEBUG] Daemon already running: $alreadyRunning")
        
        // Step 4: Start daemon if not running
        if (!alreadyRunning) {
            Log.i(TAG, "[DEBUG] Step 4: Starting daemon process...")
            if (!startDaemon()) {
                Log.e(TAG, "[DEBUG] FAILED: Could not start daemon process")
                return@withContext false
            }
            Log.i(TAG, "[DEBUG] Daemon process started, waiting 1 second...")
            delay(1000) // Wait longer for daemon to be ready
        } else {
            Log.i(TAG, "[DEBUG] Step 4: Skipped (daemon already running)")
        }
        
        // Step 5: Test connection with ping
        Log.i(TAG, "[DEBUG] Step 5: Testing connection with ping...")
        try {
            val response = sendCommand("""{"cmd":"ping"}""")
            Log.i(TAG, "[DEBUG] Ping response: $response")
            
            if (response.contains("pong")) {
                initialized.set(true)
                daemonRunning.set(true)
                Log.i(TAG, "[DEBUG] ========== DAEMON INITIALIZED SUCCESSFULLY ==========")
                return@withContext true
            } else {
                Log.e(TAG, "[DEBUG] FAILED: Unexpected ping response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] FAILED: Connection error: ${e.message}")
            e.printStackTrace()
        }
        
        Log.e(TAG, "[DEBUG] ========== DAEMON INITIALIZATION FAILED ==========")
        return@withContext false
    }
    
    /**
     * Extract daemon binary from assets to /data/local/tmp/
     */
    private fun extractDaemon(context: Context): Boolean {
        try {
            // Determine ABI
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            Log.i(TAG, "[DEBUG] Device ABI: $abi")
            Log.i(TAG, "[DEBUG] All supported ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            
            // List available assets
            try {
                val assetList = context.assets.list("") ?: emptyArray()
                Log.i(TAG, "[DEBUG] Root assets: ${assetList.joinToString()}")
                
                val daemonDir = context.assets.list("daemon") ?: emptyArray()
                Log.i(TAG, "[DEBUG] daemon/ assets: ${daemonDir.joinToString()}")
            } catch (e: Exception) {
                Log.w(TAG, "[DEBUG] Could not list assets: ${e.message}")
            }
            
            val assetPath = "daemon/$abi/$DAEMON_NAME"
            Log.i(TAG, "[DEBUG] Primary asset path: $assetPath")
            
            // Try to extract from assets first (preferred method)
            if (extractFromAssets(context, assetPath)) {
                Log.i(TAG, "[DEBUG] Extracted from primary path")
                return true
            }
            
            // Fallback: try alternative paths
            val altPaths = listOf(
                "daemon/$DAEMON_NAME",
                "$abi/$DAEMON_NAME",
                DAEMON_NAME
            )
            
            for (path in altPaths) {
                Log.i(TAG, "[DEBUG] Trying alternative path: $path")
                if (extractFromAssets(context, path)) {
                    Log.i(TAG, "[DEBUG] Extracted from: $path")
                    return true
                }
            }
            
            // Last resort: check if daemon already exists at destination
            Log.i(TAG, "[DEBUG] Checking if daemon already exists at $DAEMON_PATH...")
            val checkResult = RootHelper.executeAsRoot("test -f $DAEMON_PATH && echo 'exists'")
            if (checkResult.output.contains("exists")) {
                Log.i(TAG, "[DEBUG] Daemon already exists at destination, using existing")
                return true
            }
            
            Log.e(TAG, "[DEBUG] FAILED: Daemon binary not found anywhere")
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] FAILED: Exception during extraction: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    private fun copyDaemonFromFile(source: File): Boolean {
        return try {
            // Copy to temp location first
            val tempPath = "/data/local/tmp/${DAEMON_NAME}.tmp"
            
            val result = RootHelper.executeAsRoot(
                "cp ${source.absolutePath} $tempPath && " +
                "mv $tempPath $DAEMON_PATH && " +
                "chmod 755 $DAEMON_PATH && " +
                "chown root:root $DAEMON_PATH"
            )
            
            if (result.success) {
                Log.i(TAG, "Daemon copied successfully")
                true
            } else {
                Log.e(TAG, "Failed to copy daemon: ${result.error}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying daemon: ${e.message}")
            false
        }
    }
    
    private fun extractFromAssets(context: Context, assetPath: String): Boolean {
        return try {
            Log.d(TAG, "Trying to extract from assets: $assetPath")
            
            val inputStream = context.assets.open(assetPath)
            val tempFile = File(context.cacheDir, DAEMON_NAME)
            
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            
            Log.i(TAG, "Extracted daemon to cache: ${tempFile.absolutePath}")
            copyDaemonFromFile(tempFile)
        } catch (e: java.io.FileNotFoundException) {
            Log.d(TAG, "Asset not found: $assetPath")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract from assets ($assetPath): ${e.message}")
            false
        }
    }
    
    /**
     * Start the daemon process
     */
    private fun startDaemon(): Boolean {
        Log.i(TAG, "[DEBUG] ========== STARTING DAEMON PROCESS ==========")
        
        // Step 1: Kill any existing daemon
        Log.i(TAG, "[DEBUG] Killing any existing daemon...")
        val killResult = RootHelper.executeAsRoot("pkill -f $DAEMON_NAME 2>/dev/null; echo done")
        Log.i(TAG, "[DEBUG] Kill result: ${killResult.output}")
        Thread.sleep(200)
        
        // Step 2: Check if daemon binary exists
        Log.i(TAG, "[DEBUG] Checking daemon binary at $DAEMON_PATH...")
        val checkResult = RootHelper.executeAsRoot("ls -la $DAEMON_PATH 2>&1")
        Log.i(TAG, "[DEBUG] Binary check: ${checkResult.output}")
        
        // Step 3: Start daemon in background
        Log.i(TAG, "[DEBUG] Starting daemon with: $DAEMON_PATH -d")
        val result = RootHelper.executeAsRoot("$DAEMON_PATH -d 2>&1")
        Log.i(TAG, "[DEBUG] Start result - success: ${result.success}, output: ${result.output}, error: ${result.error}")
        
        // Step 4: Wait and check
        Log.i(TAG, "[DEBUG] Waiting 500ms for daemon to start...")
        Thread.sleep(500)
        
        // Step 5: Check if running
        val running = isDaemonRunning()
        Log.i(TAG, "[DEBUG] Daemon running after start: $running")
        
        // Step 6: Check socket file
        val socketCheck = RootHelper.executeAsRoot("ls -la $SOCKET_PATH 2>&1")
        Log.i(TAG, "[DEBUG] Socket file check: ${socketCheck.output}")
        
        // Step 7: Check daemon log
        val logCheck = RootHelper.executeAsRoot("tail -20 /data/local/tmp/netrix.log 2>&1")
        Log.i(TAG, "[DEBUG] Daemon log:\n${logCheck.output}")
        
        if (running) {
            Log.i(TAG, "[DEBUG] ========== DAEMON STARTED SUCCESSFULLY ==========")
        } else {
            Log.e(TAG, "[DEBUG] ========== DAEMON START FAILED ==========")
        }
        
        return running
    }
    
    /**
     * Stop the daemon process
     */
    fun stopDaemon(): Boolean {
        Log.i(TAG, "Stopping daemon...")
        
        try {
            // Send exit command
            sendCommandSync("""{"cmd":"exit"}""")
        } catch (e: Exception) {
            // Ignore, daemon might already be stopped
        }
        
        Thread.sleep(200)
        
        // Force kill if still running
        if (isDaemonRunning()) {
            RootHelper.executeAsRoot("pkill -9 -f $DAEMON_NAME")
        }
        
        // Cleanup
        RootHelper.executeAsRoot("rm -f $SOCKET_PATH $PID_FILE")
        
        daemonRunning.set(false)
        initialized.set(false)
        
        return true
    }
    
    /**
     * Start NFQUEUE processing
     */
    suspend fun startNfqueue(settings: NfqueueSettings): Boolean = withContext(Dispatchers.IO) {
        if (!initialized.get()) {
            Log.e(TAG, "Daemon not initialized")
            return@withContext false
        }
        
        try {
            val settingsJson = settings.toJson()
            val response = sendCommand("""{"cmd":"start","settings":$settingsJson}""")
            
            val json = JSONObject(response)
            if (json.optString("status") == "ok") {
                Log.i(TAG, "NFQUEUE started")
                return@withContext true
            } else {
                Log.e(TAG, "Failed to start NFQUEUE: ${json.optString("message")}")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting NFQUEUE: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Stop NFQUEUE processing
     */
    suspend fun stopNfqueue(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = sendCommand("""{"cmd":"stop"}""")
            val json = JSONObject(response)
            json.optString("status") == "ok"
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping NFQUEUE: ${e.message}")
            false
        }
    }
    
    /**
     * Update settings
     */
    suspend fun updateSettings(settings: NfqueueSettings): Boolean = withContext(Dispatchers.IO) {
        try {
            val settingsJson = settings.toJson()
            val response = sendCommand("""{"cmd":"settings",$settingsJson}""")
            val json = JSONObject(response)
            json.optString("status") == "ok"
        } catch (e: Exception) {
            Log.e(TAG, "Error updating settings: ${e.message}")
            false
        }
    }
    
    /**
     * Get daemon status
     */
    suspend fun getStatus(): DaemonStatus = withContext(Dispatchers.IO) {
        try {
            val response = sendCommand("""{"cmd":"status"}""")
            val json = JSONObject(response)
            DaemonStatus(
                running = json.optBoolean("running", false),
                packetsTotal = json.optLong("packets", 0),
                packetsBypassed = json.optLong("bypassed", 0)
            )
        } catch (e: Exception) {
            DaemonStatus(running = false)
        }
    }
    
    /**
     * Send command to daemon via Unix socket
     */
    private fun sendCommand(command: String): String {
        Log.d(TAG, "[DEBUG] Sending command: $command")
        var lastException: Exception? = null
        
        repeat(MAX_RETRIES) { attempt ->
            try {
                val response = sendCommandOnce(command)
                Log.d(TAG, "[DEBUG] Command response: $response")
                return response
            } catch (e: Exception) {
                Log.w(TAG, "[DEBUG] Command attempt ${attempt + 1} failed: ${e.message}")
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
        }
        
        Log.e(TAG, "[DEBUG] All command attempts failed")
        throw lastException ?: Exception("Failed to send command")
    }
    
    private fun sendCommandOnce(command: String): String {
        Log.d(TAG, "[DEBUG] sendCommandOnce: $command")
        
        // Check if nc (netcat) is available
        val ncCheck = RootHelper.executeAsRoot("which nc 2>/dev/null || which netcat 2>/dev/null || echo 'not found'")
        Log.d(TAG, "[DEBUG] nc location: ${ncCheck.output.trim()}")
        
        // Try different methods to communicate with daemon
        
        // Method 1: Use nc with Unix socket
        Log.d(TAG, "[DEBUG] Trying nc -U $SOCKET_PATH...")
        var result = RootHelper.executeAsRoot(
            "echo '$command' | nc -U $SOCKET_PATH 2>&1"
        )
        Log.d(TAG, "[DEBUG] nc result: success=${result.success}, output=${result.output}, error=${result.error}")
        
        if (result.success && result.output.isNotEmpty() && !result.output.contains("error") && !result.output.contains("not found")) {
            return result.output.trim()
        }
        
        // Method 2: Use socat
        Log.d(TAG, "[DEBUG] Trying socat...")
        result = RootHelper.executeAsRoot(
            "echo '$command' | socat - UNIX-CONNECT:$SOCKET_PATH 2>&1"
        )
        Log.d(TAG, "[DEBUG] socat result: success=${result.success}, output=${result.output}")
        
        if (result.success && result.output.isNotEmpty() && !result.output.contains("error")) {
            return result.output.trim()
        }
        
        // Method 3: Direct file-based fallback (for testing)
        Log.d(TAG, "[DEBUG] Falling back to error response...")
        
        throw Exception("Could not connect to daemon socket. nc output: ${result.output}, error: ${result.error}")
    }
    
    private fun sendCommandSync(command: String): String {
        return try {
            sendCommandOnce(command)
        } catch (e: Exception) {
            "{\"status\":\"error\"}"
        }
    }
}

/**
 * Settings for NFQUEUE daemon
 */
data class NfqueueSettings(
    val method: String = "SPLIT",
    val firstPacketSize: Int = 2,
    val splitDelay: Int = 50,
    val splitCount: Int = 4,
    val desyncHttps: Boolean = true,
    val desyncHttp: Boolean = true,
    val mixHostCase: Boolean = true,
    val blockQuic: Boolean = true
) {
    fun toJson(): String {
        return """{"method":"$method","first_packet_size":$firstPacketSize,"split_delay":$splitDelay,"split_count":$splitCount,"desync_https":$desyncHttps,"desync_http":$desyncHttp,"block_quic":$blockQuic}"""
    }
}

/**
 * Daemon status
 */
data class DaemonStatus(
    val running: Boolean = false,
    val packetsTotal: Long = 0,
    val packetsBypassed: Long = 0
)

