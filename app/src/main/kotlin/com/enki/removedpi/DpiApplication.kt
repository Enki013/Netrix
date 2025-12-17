package com.enki.netrix

import android.app.Application
import com.enki.netrix.data.SettingsRepository
import com.enki.netrix.vpn.LogManager

class DpiApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Settings Repository'yi initialize et
        SettingsRepository.init(this)
        
        // Configure log manager
        LogManager.enabled = SettingsRepository.currentSettings.enableLogs
    }
}
