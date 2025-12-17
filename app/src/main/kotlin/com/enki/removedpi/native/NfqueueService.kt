package com.enki.netrix.native

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.enki.netrix.MainActivity
import com.enki.netrix.R
import com.enki.netrix.data.DpiSettings
import com.enki.netrix.data.SettingsRepository
import com.enki.netrix.vpn.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service for NFQUEUE-based DPI bypass.
 * Requires ROOT access to function.
 * 
 * Uses a standalone daemon running as root to bypass SELinux restrictions.
 * The daemon handles NFQUEUE operations, while this service manages the daemon
 * and provides the UI/notification.
 */
class NfqueueService : Service() {
    
    companion object {
        private const val TAG = "NfqueueService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "nfqueue_channel"
        
        const val ACTION_START = "com.enki.netrix.NFQUEUE_START"
        const val ACTION_STOP = "com.enki.netrix.NFQUEUE_STOP"
        const val ACTION_RELOAD_SETTINGS = "com.enki.netrix.NFQUEUE_RELOAD"
        
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        
        private var _settings: DpiSettings = DpiSettings()
        val settings: DpiSettings get() = _settings
        
        /**
         * Check if device is rooted and daemon is available
         */
        fun isAvailable(): Boolean {
            return RootHelper.isRooted() && DaemonController.isAvailable()
        }
        
        /**
         * Start the service
         */
        fun start(context: Context) {
            if (!isAvailable()) {
                Log.e(TAG, "NFQUEUE not available (root required)")
                return
            }
            
            val intent = Intent(context, NfqueueService::class.java).apply {
                action = ACTION_START
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stop the service
         */
        fun stop(context: Context) {
            val intent = Intent(context, NfqueueService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        /**
         * Reload settings
         */
        fun reloadSettings(context: Context) {
            if (_isRunning.value) {
                val intent = Intent(context, NfqueueService::class.java).apply {
                    action = ACTION_RELOAD_SETTINGS
                }
                context.startService(intent)
            }
        }
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statusJob: Job? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                serviceScope.launch {
                    startNfqueue()
                }
                START_STICKY
            }
            ACTION_STOP -> {
                serviceScope.launch {
                    stopNfqueue()
                }
                START_NOT_STICKY
            }
            ACTION_RELOAD_SETTINGS -> {
                serviceScope.launch {
                    reloadSettingsInternal()
                }
                START_STICKY
            }
            else -> START_NOT_STICKY
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        serviceScope.launch {
            stopNfqueue()
        }
        serviceScope.cancel()
        super.onDestroy()
    }
    
    private suspend fun startNfqueue() {
        if (_isRunning.value) {
            Log.w(TAG, "[DEBUG] NFQUEUE already running, skipping")
            return
        }
        
        Log.i(TAG, "[DEBUG] ========== STARTING NFQUEUE SERVICE ==========")
        Log.i(TAG, "[DEBUG] Step 1: Starting foreground service...")
        
        // Start foreground immediately
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.root_nfqueue_starting)))
        
        // Load settings
        Log.i(TAG, "[DEBUG] Step 2: Loading settings...")
        loadSettings()
        Log.i(TAG, "[DEBUG] Settings loaded: method=${_settings.desyncMethod}, rootMode=${_settings.useRootMode}")
        
        // Check root
        Log.i(TAG, "[DEBUG] Step 3: Checking root access...")
        val isRooted = RootHelper.isRooted()
        Log.i(TAG, "[DEBUG] Root status: $isRooted")
        
        if (!isRooted) {
            Log.e(TAG, "[DEBUG] FAILED: Device is not rooted!")
            updateNotification(getString(R.string.root_no_access))
            delay(2000)
            _isRunning.value = false
            stopSelf()
            return
        }
        
        // Initialize daemon
        Log.i(TAG, "[DEBUG] Step 4: Initializing daemon...")
        updateNotification(getString(R.string.root_nfqueue_starting))
        
        val initResult = try {
            DaemonController.initialize(this@NfqueueService)
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] Daemon init exception: ${e.message}")
            e.printStackTrace()
            false
        }
        Log.i(TAG, "[DEBUG] Daemon initialize result: $initResult")
        
        if (!initResult) {
            Log.e(TAG, "[DEBUG] FAILED: Daemon initialization failed!")
            // Error message based on condition
            val errorMsg = when {
                !RootHelper.isRooted() -> getString(R.string.root_no_access)
                else -> getString(R.string.root_daemon_error)
            }
            updateNotification(getString(R.string.root_error_prefix, errorMsg))
            _isRunning.value = false
            delay(3000)
            stopSelf()
            return
        }
        
        // Convert settings to daemon format
        Log.i(TAG, "[DEBUG] Step 5: Preparing daemon settings...")
        val daemonSettings = NfqueueSettings(
            method = _settings.desyncMethod.name,
            firstPacketSize = _settings.firstPacketSize,
            splitDelay = _settings.splitDelay.toInt(),
            splitCount = _settings.splitCount,
            desyncHttps = _settings.desyncHttps,
            desyncHttp = _settings.desyncHttp,
            mixHostCase = _settings.mixHostCase,
            blockQuic = _settings.blockQuic
        )
        Log.i(TAG, "[DEBUG] Daemon settings: $daemonSettings")
        
        // Start NFQUEUE via daemon
        Log.i(TAG, "[DEBUG] Step 6: Starting NFQUEUE via daemon...")
        updateNotification(getString(R.string.root_nfqueue_starting))
        
        val startResult = DaemonController.startNfqueue(daemonSettings)
        Log.i(TAG, "[DEBUG] NFQUEUE start result: $startResult")
        
        if (!startResult) {
            Log.e(TAG, "[DEBUG] FAILED: NFQUEUE start failed!")
            updateNotification(getString(R.string.root_nfqueue_failed))
            delay(2000)
            DaemonController.stopDaemon()
            _isRunning.value = false
            stopSelf()
            return
        }
        
        _isRunning.value = true
        Log.i(TAG, "[DEBUG] Step 7: SUCCESS! NFQUEUE is now running")
        LogManager.i("NFQUEUE started (daemon mode)")
        updateNotification(getString(R.string.root_bypass_active))
        
        // Start status monitoring
        startStatusMonitoring()
        
        Log.i(TAG, "[DEBUG] ========== NFQUEUE SERVICE STARTED ==========")
    }
    
    private suspend fun stopNfqueue() {
        if (!_isRunning.value) {
            return
        }
        
        Log.i(TAG, "Stopping NFQUEUE service...")
        
        // Stop status monitoring
        statusJob?.cancel()
        statusJob = null
        
        // Stop NFQUEUE via daemon
        DaemonController.stopNfqueue()
        
        // Stop daemon
        DaemonController.stopDaemon()
        
        _isRunning.value = false
        LogManager.i("NFQUEUE stopped")
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun loadSettings() {
        _settings = SettingsRepository.currentSettings
        Log.i(TAG, "Settings loaded: method=${_settings.desyncMethod}")
    }
    
    private suspend fun reloadSettingsInternal() {
        loadSettings()
        
        val daemonSettings = NfqueueSettings(
            method = _settings.desyncMethod.name,
            firstPacketSize = _settings.firstPacketSize,
            splitDelay = _settings.splitDelay.toInt(),
            splitCount = _settings.splitCount,
            desyncHttps = _settings.desyncHttps,
            desyncHttp = _settings.desyncHttp,
            mixHostCase = _settings.mixHostCase,
            blockQuic = _settings.blockQuic
        )
        
        DaemonController.updateSettings(daemonSettings)
        Log.i(TAG, "Settings reloaded")
    }
    
    private fun startStatusMonitoring() {
        statusJob = serviceScope.launch {
            while (_isRunning.value) {
                try {
                    val status = DaemonController.getStatus()
                    if (!status.running && _isRunning.value) {
                        // Daemon stopped unexpectedly
                        Log.w(TAG, "Daemon stopped unexpectedly")
                        _isRunning.value = false
                        updateNotification(getString(R.string.root_connection_lost))
                        delay(2000)
                        stopSelf()
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Status check error: ${e.message}")
                }
                delay(5000) // Check every 5 seconds
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NFQUEUE Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "DPI bypass running in root mode"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(text: String? = null): Notification {
        val notificationText = text ?: getString(R.string.root_bypass_active)
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, NfqueueService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.root_notification_title))
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.ic_netrix)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.vpn_notification_stop), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }
}
