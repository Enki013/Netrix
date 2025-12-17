package com.enki.netrix.native

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Helper for root operations and iptables management.
 * 
 * This class provides utilities for:
 * - Checking root access
 * - Executing commands as root
 * - Setting up iptables rules for NFQUEUE
 */
object RootHelper {
    
    private const val TAG = "RootHelper"
    
    // Common su binary locations
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/su",
        "/system/bin/.ext/.su",
        "/system/usr/we-need-root/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su"
    )
    
    // NFQUEUE number to use
    const val QUEUE_NUM = 0
    
    // Cache root status
    private var rootChecked = false
    private var isRootAvailable = false
    
    /**
     * Check if device has root access
     * @param forceCheck Force re-check even if cached
     */
    fun isRooted(forceCheck: Boolean = false): Boolean {
        Log.i(TAG, "[DEBUG] isRooted() called, forceCheck=$forceCheck, cached=$rootChecked")
        
        if (rootChecked && !forceCheck) {
            Log.i(TAG, "[DEBUG] Returning cached result: $isRootAvailable")
            return isRootAvailable
        }
        
        // Method 1: Check for su binary
        Log.i(TAG, "[DEBUG] Checking for su binary in known paths...")
        val foundSuPaths = SU_PATHS.filter { File(it).exists() }
        val suExists = foundSuPaths.isNotEmpty()
        
        if (suExists) {
            Log.i(TAG, "[DEBUG] Found su at: ${foundSuPaths.joinToString()}")
        } else {
            Log.w(TAG, "[DEBUG] No su binary found in any path")
            rootChecked = true
            isRootAvailable = false
            return false
        }
        
        // Method 2: Try to execute a simple root command
        Log.i(TAG, "[DEBUG] Trying to execute 'id' as root...")
        isRootAvailable = try {
            val result = executeAsRoot("id")
            Log.i(TAG, "[DEBUG] 'id' result: success=${result.success}, output=${result.output}")
            result.success && result.output.contains("uid=0")
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] Root check failed with exception: ${e.message}")
            e.printStackTrace()
            false
        }
        
        rootChecked = true
        Log.i(TAG, "[DEBUG] Root status determined: $isRootAvailable")
        return isRootAvailable
    }
    
    /**
     * Execute a command as root
     * @param command Command to execute
     * @return CommandResult with success status and output
     */
    fun executeAsRoot(command: String): CommandResult {
        Log.d(TAG, "[DEBUG] executeAsRoot: $command")
        return try {
            val process = Runtime.getRuntime().exec("su")
            
            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("$command\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            
            val output = StringBuilder()
            val error = StringBuilder()
            
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                }
            }
            
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    error.appendLine(line)
                }
            }
            
            val exitCode = process.waitFor()
            
            CommandResult(
                success = exitCode == 0,
                exitCode = exitCode,
                output = output.toString().trim(),
                error = error.toString().trim()
            )
        } catch (e: Exception) {
            Log.e(TAG, "executeAsRoot failed: ${e.message}")
            CommandResult(
                success = false,
                exitCode = -1,
                output = "",
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * Execute command as root (suspend version)
     */
    suspend fun executeAsRootAsync(command: String): CommandResult = 
        withContext(Dispatchers.IO) {
            executeAsRoot(command)
        }
    
    /**
     * Setup iptables rules for NFQUEUE
     * Routes TCP traffic on ports 80 and 443 to NFQUEUE
     */
    fun setupIptables(): Boolean {
        if (!isRooted()) {
            Log.e(TAG, "Device is not rooted")
            return false
        }
        
        Log.i(TAG, "Setting up iptables rules for NFQUEUE")
        
        // Clear any existing rules first
        clearIptables()
        
        // Add rules for HTTPS (port 443)
        val https = executeAsRoot(
            "iptables -A OUTPUT -p tcp --dport 443 -j NFQUEUE --queue-num $QUEUE_NUM"
        )
        
        // Add rules for HTTP (port 80)
        val http = executeAsRoot(
            "iptables -A OUTPUT -p tcp --dport 80 -j NFQUEUE --queue-num $QUEUE_NUM"
        )
        
        if (!https.success) {
            Log.e(TAG, "Failed to add HTTPS rule: ${https.error}")
            return false
        }
        
        if (!http.success) {
            Log.e(TAG, "Failed to add HTTP rule: ${http.error}")
            return false
        }
        
        Log.i(TAG, "iptables rules set up successfully")
        return true
    }
    
    /**
     * Setup iptables with custom ports
     * @param ports List of destination ports to intercept
     */
    fun setupIptables(vararg ports: Int): Boolean {
        if (!isRooted()) {
            Log.e(TAG, "Device is not rooted")
            return false
        }
        
        clearIptables()
        
        for (port in ports) {
            val result = executeAsRoot(
                "iptables -A OUTPUT -p tcp --dport $port -j NFQUEUE --queue-num $QUEUE_NUM"
            )
            
            if (!result.success) {
                Log.e(TAG, "Failed to add rule for port $port: ${result.error}")
                return false
            }
        }
        
        Log.i(TAG, "iptables rules set up for ports: ${ports.joinToString()}")
        return true
    }
    
    /**
     * Clear NFQUEUE iptables rules
     */
    fun clearIptables(): Boolean {
        if (!isRooted()) {
            return false
        }
        
        Log.i(TAG, "Clearing iptables NFQUEUE rules")
        
        // Delete all NFQUEUE rules from OUTPUT chain
        // Run multiple times to clear all matching rules
        repeat(10) {
            executeAsRoot(
                "iptables -D OUTPUT -p tcp --dport 443 -j NFQUEUE --queue-num $QUEUE_NUM 2>/dev/null"
            )
            executeAsRoot(
                "iptables -D OUTPUT -p tcp --dport 80 -j NFQUEUE --queue-num $QUEUE_NUM 2>/dev/null"
            )
        }
        
        return true
    }
    
    /**
     * List current iptables rules
     */
    fun listIptables(): String {
        if (!isRooted()) {
            return "Not rooted"
        }
        
        val result = executeAsRoot("iptables -L OUTPUT -n -v")
        return if (result.success) result.output else result.error
    }
    
    /**
     * Check if NFQUEUE module is loaded
     */
    fun isNfqueueAvailable(): Boolean {
        // Check if nfnetlink_queue module is loaded
        val result = executeAsRoot("lsmod | grep nfnetlink_queue")
        if (result.success && result.output.isNotEmpty()) {
            return true
        }
        
        // Try to load the module
        val loadResult = executeAsRoot("modprobe nfnetlink_queue 2>/dev/null || insmod nfnetlink_queue 2>/dev/null")
        
        // Check again
        val checkResult = executeAsRoot("lsmod | grep nfnetlink_queue")
        return checkResult.success && checkResult.output.isNotEmpty()
    }
    
    /**
     * Get kernel version
     */
    fun getKernelVersion(): String {
        return try {
            val result = executeAsRoot("uname -r")
            if (result.success) result.output else "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * Check if iptables is available
     */
    fun isIptablesAvailable(): Boolean {
        val result = executeAsRoot("which iptables")
        return result.success && result.output.isNotEmpty()
    }
}

/**
 * Result of a root command execution
 */
data class CommandResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String,
    val error: String
)

