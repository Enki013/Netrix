package com.enki.netrix.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton Settings Repository
 * Must be initialized at Application startup.
 */
object SettingsRepository {
    
    private lateinit var prefs: SharedPreferences
    private val _settings = MutableStateFlow(DpiSettings())
    
    val settings: Flow<DpiSettings> = _settings.asStateFlow()
    val currentSettings: DpiSettings get() = _settings.value
    
    /**
     * Initializes the repository. Should be called in Application.onCreate.
     * @param context Application context (to prevent leaks)
     */
    fun init(context: Context) {
        if (::prefs.isInitialized) return // Already initialized
        
        prefs = context.applicationContext.getSharedPreferences("dpi_prefs", Context.MODE_PRIVATE)
        _settings.value = loadSettings()
    }
    
    private fun loadSettings(): DpiSettings {
        if (!::prefs.isInitialized) return DpiSettings()
        
        return DpiSettings(
            appTheme = try {
                AppTheme.valueOf(prefs.getString("app_theme", "SYSTEM") ?: "SYSTEM")
            } catch (e: Exception) { AppTheme.SYSTEM },

            whitelist = prefs.getStringSet("whitelist", DpiSettings.DEFAULT_WHITELIST) 
                ?: DpiSettings.DEFAULT_WHITELIST,

            bufferSize = prefs.getInt("buffer_size", 32768),
            tcpFastOpen = prefs.getBoolean("tcp_fast_open", false),
            enableTcpNodelay = prefs.getBoolean("tcp_nodelay", true),
            useRootMode = prefs.getBoolean("use_root_mode", false),
            desyncMethod = try {
                DesyncMethod.valueOf(prefs.getString("desync_method", "SPLIT") ?: "SPLIT")
            } catch (e: Exception) { DesyncMethod.SPLIT },
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
            bypassMode = try {
                BypassMode.valueOf(prefs.getString("bypass_mode", "FULL") ?: "FULL")
            } catch (e: Exception) { BypassMode.FULL }
        )
    }
    
    suspend fun updateSettings(settings: DpiSettings) {
        if (!::prefs.isInitialized) return
        
        prefs.edit().apply {
            putString("app_theme", settings.appTheme.name)
            putStringSet("whitelist", settings.whitelist)
            putInt("buffer_size", settings.bufferSize)
            putBoolean("tcp_fast_open", settings.tcpFastOpen)
            putBoolean("tcp_nodelay", settings.enableTcpNodelay)
            putBoolean("use_root_mode", settings.useRootMode)
            putString("desync_method", settings.desyncMethod.name)
            putBoolean("desync_http", settings.desyncHttp)
            putBoolean("desync_https", settings.desyncHttps)
            putInt("first_packet_size", settings.firstPacketSize)
            putLong("split_delay", settings.splitDelay)
            putBoolean("mix_host_case", settings.mixHostCase)
            putInt("split_count", settings.splitCount)
            putString("fake_hex", settings.fakeHex)
            putInt("fake_count", settings.fakeCount)
            putBoolean("dns_enabled", settings.customDnsEnabled)
            putString("dns1", settings.customDns)
            putString("dns2", settings.customDns2)
            putBoolean("block_quic", settings.blockQuic)
            putBoolean("logs", settings.enableLogs)
            putString("bypass_mode", settings.bypassMode.name)
            apply()
        }
        _settings.value = settings
    }
}
