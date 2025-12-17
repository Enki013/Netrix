package com.enki.netrix.vpn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.enki.netrix.MainActivity
import com.enki.netrix.R
import com.enki.netrix.data.DesyncMethod
import com.enki.netrix.data.DpiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

@SuppressLint("VpnServicePolicy")
class BypassVpnService : VpnService() {
    
    companion object {
        private const val TAG = "Netrix"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "netrix_channel"
        private const val MTU = 1500
        
        // Intent Actions
        const val ACTION_STOP = "STOP"
        const val ACTION_RELOAD_SETTINGS = "RELOAD_SETTINGS"
        
        // PendingIntent Request Codes
        private const val REQUEST_CODE_STOP = 1001
        private const val REQUEST_CODE_OPEN = 1002
        private const val REQUEST_CODE_SETTINGS = 1003
        
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning
        
        private val _stats = MutableStateFlow(Stats())
        val stats: StateFlow<Stats> = _stats
        
        @Volatile
        private var _settings: DpiSettings = DpiSettings()
        val settings: DpiSettings get() = _settings
        
        /**
         * Use this function to update settings while VPN is running.
         * Notifies the Service by sending an Intent with Context.
         */
        fun reloadSettings(context: android.content.Context) {
            if (_isRunning.value) {
                val intent = android.content.Intent(context, BypassVpnService::class.java).apply {
                    action = ACTION_RELOAD_SETTINGS
                }
                context.startService(intent)
            }
        }
    }
    
    data class Stats(
        val packetsIn: Long = 0,
        val packetsOut: Long = 0,
        val bytesIn: Long = 0,
        val bytesOut: Long = 0
    )
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnInput: FileInputStream? = null
    private var vpnOutput: FileOutputStream? = null
    
    private var tcpHandler: TcpConnection? = null
    private var udpHandler: UdpConnection? = null
    
    @Volatile
    private var running = false
    
    private var packetsIn = 0L
    private var packetsOut = 0L

    override fun onCreate() {
        super.onCreate()
        // Settings will be loaded in onStartCommand - prevent duplicates
    }
    
    private fun loadSettingsDirectly() {
        try {
            val prefs = getSharedPreferences("dpi_prefs", MODE_PRIVATE)
            _settings = DpiSettings(
                bufferSize = prefs.getInt("buffer_size", 32768),
                tcpFastOpen = prefs.getBoolean("tcp_fast_open", false),
                enableTcpNodelay = prefs.getBoolean("tcp_nodelay", true),
                desyncMethod = try {
                    DesyncMethod.valueOf(prefs.getString("desync_method", "SPLIT") ?: "SPLIT")
                } catch (_: Exception) { DesyncMethod.SPLIT },
                desyncHttp = prefs.getBoolean("desync_http", true),
                desyncHttps = prefs.getBoolean("desync_https", true),
                firstPacketSize = prefs.getInt("first_packet_size", 2),
                splitDelay = prefs.getLong("split_delay", 50L),
                mixHostCase = prefs.getBoolean("mix_host_case", true),
                splitCount = prefs.getInt("split_count", 4),
                fakeHex = prefs.getString("fake_hex", "474554202f20485454502f312e300d0a0d0a") ?: "474554202f20485454502f312e300d0a0d0a",
                fakeCount = prefs.getInt("fake_count", 1),
                customDnsEnabled = prefs.getBoolean("dns_enabled", false),
                customDns = prefs.getString("dns1", "94.140.14.14") ?: "94.140.14.14",
                customDns2 = prefs.getString("dns2", "94.140.15.15") ?: "94.140.15.15",
                blockQuic = prefs.getBoolean("block_quic", true),
                enableLogs = prefs.getBoolean("logs", true),
                whitelist = prefs.getStringSet("whitelist", emptySet()) ?: emptySet()
            )
            Log.i(TAG, "Settings loaded manually")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading settings manually: ${e.message}")
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }
            ACTION_RELOAD_SETTINGS -> {
                reloadSettingsInternal()
                START_STICKY
            }
            else -> {
                loadSettingsDirectly()
                startVpn()
                START_STICKY
            }
        }
    }
    
    /**
     * Reloads settings and updates handlers while VPN is running.
     * This updates settings without restarting the VPN when settings change.
     */
    private fun reloadSettingsInternal() {
        if (!running) return
        
        Log.i(TAG, "Reloading settings while VPN is running...")
        loadSettingsDirectly()
        
        // Pass new settings to TCP and UDP handlers
        tcpHandler?.updateSettings(_settings)
        udpHandler?.updateSettings(_settings)
        
        LogManager.enabled = _settings.enableLogs
        Log.i(TAG, "Settings reloaded successfully")
    }
    
    private fun startVpn() {
        if (running) return
        
        Log.i(TAG, "Starting Netrix...")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        try {
            val builder = Builder()
                .setSession("Netrix")
                .setMtu(MTU)
                .addAddress("10.8.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .setBlocking(true)
            
            builder.setConfigureIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            
            // DNS Settings
            if (settings.customDnsEnabled) {
                builder.addDnsServer(settings.customDns)
                if (settings.customDns2.isNotEmpty()) {
                    builder.addDnsServer(settings.customDns2)
                }
            } else {
                builder.addDnsServer("8.8.8.8")
            }
            
            // Mark as non-metered connection (Android Q+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            
            // Exclude our own app from VPN
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
                Log.w(TAG, "Could not exclude self from VPN")
            }
            
            vpnInterface = builder.establish()
            
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN")
                stopSelf()
                return
            }
            
            vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
            vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
            
            tcpHandler = TcpConnection(this, vpnOutput!!, settings)
            udpHandler = UdpConnection(this, vpnOutput!!, settings)
            
            running = true
            _isRunning.value = true
            
            Thread({ runVpnLoop() }, "Netrix-Loop").start()
            Log.i(TAG, "Netrix started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}")
            stopVpn()
        }
    }
    
    private fun runVpnLoop() {
        val buffer = ByteBuffer.allocate(MTU)
        val input = vpnInput ?: return
        
        while (running) {
            try {
                buffer.clear()
                val length = input.read(buffer.array())
                if (length > 0) {
                    buffer.limit(length)
                    processPacket(buffer)
                    packetsIn++
                    if (packetsIn % 100 == 0L) updateStats()
                } else if (length < 0) {
                    break
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Loop error: ${e.message}")
                break
            }
        }
    }
    
    private fun processPacket(buffer: ByteBuffer) {
        try {
            val packet = Packet(buffer)
            if (packet.version != 4) return
            when (packet.protocol) {
                Packet.PROTOCOL_TCP -> {
                    tcpHandler?.processPacket(packet)
                    packetsOut++
                }
                Packet.PROTOCOL_UDP -> {
                    udpHandler?.processPacket(packet)
                    packetsOut++
                }
            }
        } catch (e: Exception) {
            // Packet parse/processing error - malformed packet or buffer issue
            if (settings.enableLogs) {
                LogManager.e("Packet processing error: ${e.message}")
            }
        }
    }
    
    private fun updateStats() {
        val tcpStats = tcpHandler?.getStats() ?: (0L to 0L)
        val udpStats = udpHandler?.getStats() ?: (0L to 0L)
        _stats.value = Stats(
            packetsIn = packetsIn,
            packetsOut = packetsOut,
            bytesIn = tcpStats.first + udpStats.first,
            bytesOut = tcpStats.second + udpStats.second
        )
    }
    
    private fun stopVpn() {
        // Don't run again if already stopped (prevent duplicate logs)
        if (!running && vpnInterface == null) return
        
        Log.i(TAG, "Stopping Netrix...")
        running = false
        _isRunning.value = false
        tcpHandler?.stop()
        udpHandler?.stop()
        // Close resources - nothing to do if error occurs
        runCatching {
            vpnInput?.close()
            vpnOutput?.close()
            vpnInterface?.close()
        }
        vpnInput = null
        vpnOutput = null
        vpnInterface = null
        tcpHandler = null
        udpHandler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Netrix stopped")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, 
                getString(R.string.vpn_notification_channel), 
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        // 1. DURDUR BUTONU
        val stopIntent = Intent(this, BypassVpnService::class.java).apply { 
            action = ACTION_STOP 
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 
            REQUEST_CODE_STOP,
            stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 2. WHEN NOTIFICATION IS CLICKED - Opens main screen
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 
            REQUEST_CODE_OPEN,
            openIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 3. SETTINGS BUTTON - Opens settings screen
        val settingsIntent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_SETTINGS"
        }
        val settingsPendingIntent = PendingIntent.getActivity(
            this, 
            REQUEST_CODE_SETTINGS,
            settingsIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setSmallIcon(R.drawable.ic_netrix)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.vpn_notification_stop), stopPendingIntent)
            .addAction(R.drawable.ic_settings, getString(R.string.vpn_notification_settings), settingsPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    
    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
    
    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}