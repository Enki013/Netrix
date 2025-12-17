package com.enki.netrix.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.enki.netrix.MainActivity

class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        
        try {
            // Check if VPN permission is granted
            val prepare = VpnService.prepare(this)
            if (prepare != null) {
                // If permission not granted, send user to app
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(intent)
                Toast.makeText(this, "Please open the app first and grant VPN permission.", Toast.LENGTH_LONG).show()
                return
            }

            if (BypassVpnService.isRunning.value) {
                // If running, STOP
                val intent = Intent(this, BypassVpnService::class.java).apply { action = "STOP" }
                startService(intent)
            } else {
                // If stopped, START
                val intent = Intent(this, BypassVpnService::class.java)
                // Foreground start required for Android 8+ (Oreo)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            
            // Temporarily update state (let UI respond until service starts)
            val tile = qsTile
            if (tile != null) {
                tile.state = if (BypassVpnService.isRunning.value) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
                tile.updateTile()
            }
            
            // Short delay to check actual state
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                updateTileState()
            }, 500)
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = BypassVpnService.isRunning.value
        
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        
        
        // The following is not needed as it's already defined in manifest.
        
        //tile.label = "Netrix"
        
        // Android Quick Settings icons must be monochrome (single color).
        // System automatically colors them with theme color when active, gray when inactive.
        // Even if you put a colored image, Android paints it white.
        //tile.icon = Icon.createWithResource(this, R.drawable.ic_netrix)
        
        tile.updateTile()
    }
}