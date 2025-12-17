package com.enki.netrix

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.enki.netrix.data.DpiSettings
import com.enki.netrix.data.SettingsRepository
import com.enki.netrix.ui.screens.HomeScreen
import com.enki.netrix.ui.screens.LogScreen
import com.enki.netrix.ui.screens.SettingsScreen
import com.enki.netrix.ui.screens.SplashScreen
import com.enki.netrix.ui.screens.WhitelistScreen
import com.enki.netrix.ui.theme.NetrixTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }
    
    private val _navigationEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val navigationEvent = _navigationEvent.asSharedFlow()
    
    private var startDestination = "home"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Native splash - immediately pass (fallback only for older phones)
        installSplashScreen()
        
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        requestNotificationPermissionIfNeeded()
        
        startDestination = getDestinationFromIntent(intent)
        Log.d(TAG, "onCreate - startDestination: $startDestination, action: ${intent?.action}")
        
        setContent {
            val settings by SettingsRepository.settings.collectAsState(initial = DpiSettings())
            
            // Compose Splash Screen state
            var showSplash by remember { mutableStateOf(true) }
            
            NetrixTheme(themeMode = settings.appTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showSplash) {
                        // Compose Splash Screen - this is the actual splash!
                        SplashScreen(
                            onSplashFinished = {
                                showSplash = false
                            }
                        )
                    } else {
                        // Main application
                        netrixApp(
                            startDestination = startDestination,
                            navigationEventFlow = navigationEvent
                        )
                    }
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        val destination = getDestinationFromIntent(intent)
        Log.d(TAG, "onNewIntent - destination: $destination, action: ${intent.action}")
        
        if (destination == "settings") {
            lifecycleScope.launch {
                _navigationEvent.emit("settings")
            }
        }
    }
    
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    private fun getDestinationFromIntent(intent: Intent?): String {
        if (intent == null) return "home"
        
        return when (intent.action) {
            "android.service.quicksettings.action.QS_TILE_PREFERENCES" -> "settings"
            "OPEN_SETTINGS" -> "settings"
            "android.intent.action.APPLICATION_PREFERENCES" -> "settings"
            else -> "home"
        }
    }
}

@Composable
fun netrixApp(
    startDestination: String,
    navigationEventFlow: kotlinx.coroutines.flow.Flow<String>
) {
    val navController = rememberNavController()
    
    LaunchedEffect(Unit) {
        navigationEventFlow.collect { destination ->
            Log.d("netrixApp", "Navigation event: $destination")
            navController.navigate(destination) {
                launchSingleTop = true
            }
        }
    }
    
    AppNavHost(
        navController = navController,
        startDestination = startDestination
    )
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("home") {
            HomeScreen(
                onNavigateToSettings = { 
                    navController.navigate("settings") { launchSingleTop = true }
                },
                onNavigateToLogs = { 
                    navController.navigate("logs") { launchSingleTop = true }
                }
            )
        }
        
        composable(
            "settings",
            enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
            exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
            popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) }
        ) {
            SettingsScreen(
                onNavigateBack = { 
                    if (!navController.popBackStack()) {
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    }
                },
                onNavigateToWhitelist = { 
                    navController.navigate("whitelist") { launchSingleTop = true }
                }
            )
        }
        
        composable("whitelist") {
            WhitelistScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable("logs") {
            LogScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}