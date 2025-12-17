package com.enki.netrix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enki.netrix.data.*
import com.enki.netrix.native.NfqueueService
import com.enki.netrix.native.RootHelper
import com.enki.netrix.vpn.BypassVpnService
import com.enki.netrix.vpn.LogManager
import com.enki.netrix.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToWhitelist: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    
    val appVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${pInfo.versionName}"
        } catch (_: Exception) { "v1.0" }
    }
    
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var settings by remember { mutableStateOf(DpiSettings()) }
    
    LaunchedEffect(Unit) {
        SettingsRepository.settings.collect { loadedSettings ->
            settings = loadedSettings
            LogManager.enabled = loadedSettings.enableLogs
        }
    }
    
    fun updateSettings(newSettings: DpiSettings) {
        settings = newSettings
        LogManager.enabled = newSettings.enableLogs
        scope.launch { 
            SettingsRepository.updateSettings(newSettings)
            // Apply new settings if VPN is running
            BypassVpnService.reloadSettings(context)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            contentPadding = paddingValues,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // === 1. BYPASS METHOD ===
            item {
                SettingsSection(title = stringResource(R.string.settings_bypass_method)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        // First row: Normal methods
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = settings.desyncMethod == DesyncMethod.SPLIT,
                                onClick = { updateSettings(settings.copy(desyncMethod = DesyncMethod.SPLIT)) },
                                label = { Text(stringResource(R.string.bypass_split)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = settings.desyncMethod == DesyncMethod.DISORDER,
                                onClick = { updateSettings(settings.copy(desyncMethod = DesyncMethod.DISORDER)) },
                                label = { Text(stringResource(R.string.bypass_disorder)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = settings.desyncMethod == DesyncMethod.FAKE,
                                onClick = { updateSettings(settings.copy(desyncMethod = DesyncMethod.FAKE)) },
                                label = { Text(stringResource(R.string.bypass_fake)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // Second row: Reverse methods (GoodbyeDPI compatible)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = settings.desyncMethod == DesyncMethod.SPLIT_REVERSE,
                                onClick = { updateSettings(settings.copy(desyncMethod = DesyncMethod.SPLIT_REVERSE)) },
                                label = { Text(stringResource(R.string.bypass_split_reverse)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = settings.desyncMethod == DesyncMethod.DISORDER_REVERSE,
                                onClick = { updateSettings(settings.copy(desyncMethod = DesyncMethod.DISORDER_REVERSE)) },
                                label = { Text(stringResource(R.string.bypass_disorder_reverse)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    SettingsSwitchRow(Icons.Default.Https, stringResource(R.string.bypass_https_desync), stringResource(R.string.bypass_https_desync_desc), settings.desyncHttps) { updateSettings(settings.copy(desyncHttps = it)) }
                    SettingsSwitchRow(Icons.Default.Http, stringResource(R.string.bypass_http_desync), stringResource(R.string.bypass_http_desync_desc), settings.desyncHttp) { updateSettings(settings.copy(desyncHttp = it)) }
                }
            }
            
            // === 2. ADVANCED SETTINGS ===
            item {
                SettingsSection(title = stringResource(R.string.settings_advanced)) {
                    // Split position: For SPLIT, SPLIT_REVERSE and FAKE
                    if (settings.desyncMethod in listOf(DesyncMethod.SPLIT, DesyncMethod.SPLIT_REVERSE, DesyncMethod.FAKE)) {
                        SettingsSliderRow(stringResource(R.string.advanced_split_position), settings.firstPacketSize.toFloat(), 1f..10f, { updateSettings(settings.copy(firstPacketSize = it.toInt())) }, "${settings.firstPacketSize}. byte")
                    }
                    // Fragment count: For DISORDER and DISORDER_REVERSE
                    if (settings.desyncMethod in listOf(DesyncMethod.DISORDER, DesyncMethod.DISORDER_REVERSE)) {
                        SettingsSliderRow(stringResource(R.string.advanced_fragment_count), settings.splitCount.toFloat(), 2f..20f, { updateSettings(settings.copy(splitCount = it.toInt())) }, "${settings.splitCount}")
                    }
                    // Fake hex: Only for FAKE method
                    if (settings.desyncMethod == DesyncMethod.FAKE) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.advanced_fake_hex), style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = settings.fakeHex,
                                onValueChange = { updateSettings(settings.copy(fakeHex = it)) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                singleLine = true
                            )
                        }
                    }
                    // Delay: 0-100ms (default 50ms)
                    SettingsSliderRow(stringResource(R.string.advanced_delay), settings.splitDelay.toFloat(), 0f..100f, { updateSettings(settings.copy(splitDelay = it.toLong())) }, "${settings.splitDelay}")
                    SettingsSwitchRow(Icons.Default.TextFields, stringResource(R.string.advanced_host_mixing), stringResource(R.string.advanced_host_mixing_desc), settings.mixHostCase) { updateSettings(settings.copy(mixHostCase = it)) }
                }
            }

            // === 3. DNS AND CONNECTION ===
            item {
                SettingsSection(title = stringResource(R.string.settings_dns_connection)) {
                    SettingsSwitchRow(Icons.Default.Dns, stringResource(R.string.dns_custom), stringResource(R.string.dns_custom_desc), settings.customDnsEnabled) { updateSettings(settings.copy(customDnsEnabled = it)) }
                    if (settings.customDnsEnabled) {
                        SingleChoiceRow(settings.customDns, listOf("94.140.14.14" to stringResource(R.string.dns_adguard), "76.76.2.2" to stringResource(R.string.dns_controld), "1.1.1.1" to stringResource(R.string.dns_cloudflare))) { 
                            val dns2 = when(it) { "94.140.14.14"->"94.140.15.15" "76.76.2.2"->"76.76.10.2" else->"1.0.0.1" }
                            updateSettings(settings.copy(customDns = it, customDns2 = dns2)) 
                        }
                    }
                    SettingsSwitchRow(Icons.Outlined.Speed, stringResource(R.string.quic_block), stringResource(R.string.quic_block_desc), settings.blockQuic) { updateSettings(settings.copy(blockQuic = it)) }
                    SettingsSwitchRow(Icons.Outlined.Bolt, stringResource(R.string.tcp_nodelay), stringResource(R.string.tcp_nodelay_desc), settings.enableTcpNodelay) { updateSettings(settings.copy(enableTcpNodelay = it)) }
                }
            }

            // === 4. APPEARANCE ===
            item {
                SettingsSection(title = stringResource(R.string.settings_appearance)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        // First row: System, Amoled, Ocean
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = settings.appTheme == AppTheme.SYSTEM,
                                onClick = { updateSettings(settings.copy(appTheme = AppTheme.SYSTEM)) },
                                label = { Text(stringResource(R.string.theme_system)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = settings.appTheme == AppTheme.AMOLED,
                                onClick = { updateSettings(settings.copy(appTheme = AppTheme.AMOLED)) },
                                label = { Text(stringResource(R.string.theme_amoled)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = settings.appTheme == AppTheme.OCEAN,
                                onClick = { updateSettings(settings.copy(appTheme = AppTheme.OCEAN)) },
                                label = { Text(stringResource(R.string.theme_ocean)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // Second row: Forest, Sunset, Lavender
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = settings.appTheme == AppTheme.FOREST,
                                onClick = { updateSettings(settings.copy(appTheme = AppTheme.FOREST)) },
                                label = { Text(stringResource(R.string.theme_forest)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = settings.appTheme == AppTheme.SUNSET,
                                onClick = { updateSettings(settings.copy(appTheme = AppTheme.SUNSET)) },
                                label = { Text(stringResource(R.string.theme_sunset)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = settings.appTheme == AppTheme.LAVENDER,
                                onClick = { updateSettings(settings.copy(appTheme = AppTheme.LAVENDER)) },
                                label = { Text(stringResource(R.string.theme_lavender)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // === 5. WHITELIST ===
            item {
                SettingsSection(title = stringResource(R.string.settings_exceptions)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToWhitelist() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.VerifiedUser, null, tint = MaterialTheme.colorScheme.tertiary)
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.whitelist_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.whitelist_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // === 6. SYSTEM ===
            item {
                // Root check
                val isRooted = remember { RootHelper.isRooted() }
                val isNfqueueAvailable = remember { NfqueueService.isAvailable() }
                
                SettingsSection(title = stringResource(R.string.settings_system)) {
                    // Root Mode toggle (always show, disabled if no root)
                    SettingsSwitchRow(
                        icon = Icons.Default.AdminPanelSettings,
                        title = stringResource(R.string.root_mode),
                        subtitle = when {
                            !isRooted -> stringResource(R.string.root_required)
                            !isNfqueueAvailable -> stringResource(R.string.daemon_not_found)
                            else -> stringResource(R.string.root_mode_desc)
                        },
                        checked = settings.useRootMode && isRooted && isNfqueueAvailable,
                        onCheckedChange = { 
                            if (isRooted && isNfqueueAvailable) {
                                updateSettings(settings.copy(useRootMode = it))
                            }
                        }
                    )
                    SettingsSwitchRow(Icons.Default.BugReport, stringResource(R.string.logging), stringResource(R.string.logging_desc), settings.enableLogs) { updateSettings(settings.copy(enableLogs = it)) }
                }
            }
            
            // === 7. SIFIRLA ===
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { updateSettings(DpiSettings()) }) { Text(stringResource(R.string.settings_reset_default), color = MaterialTheme.colorScheme.error) }
                }
            }
            
            // === 8. ABOUT (FIXED: enki on top) ===
            item {
                SettingsSection(title = stringResource(R.string.settings_about)) {
                    Row(modifier = Modifier.fillMaxWidth().clickable { uriHandler.openUri("https://github.com/enki013") }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(painter = painterResource(id = R.drawable.ic_netrix), null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            // NAME ON TOP AND LARGE
                            Text("enki", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            // TITLE BELOW AND SMALL
                            Text(stringResource(R.string.developer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(modifier = Modifier.fillMaxWidth().clickable { uriHandler.openUri("https://github.com/enki013/netrix") }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(painter = painterResource(id = R.drawable.ic_github), null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(stringResource(R.string.github), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.github_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            
            // === 9. VERSION INFO ===
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp), contentAlignment = Alignment.Center) {
                    Text("${stringResource(R.string.app_name)} • $appVersion", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║                              HELPER COMPOSABLES                               ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

/**
 * Creates a section card for the settings screen.
 * @param title Section title
 * @param content Section content
 */
@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                content = content
            )
        }
    }
}

/**
 * Settings row with toggle switch.
 * @param icon Left side icon
 * @param title Title
 * @param subtitle Subtitle (optional)
 * @param checked Switch state
 * @param onCheckedChange Change callback
 */
@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val containerColor = if (checked) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    
    val iconTint = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon container
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(containerColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // Text content
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // Switch (visual only - click handled by Row)
        Switch(
            checked = checked,
            onCheckedChange = null
        )
    }
}

/**
 * Settings row with slider.
 * @param title Title
 * @param value Current value
 * @param range Value range
 * @param onChange Change callback
 * @param text Value text to display
 */
@Composable
fun SettingsSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    text: String
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = text,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range
        )
    }
}

/**
 * Single choice chip row.
 * @param selected Selected value
 * @param options (value, label) pairs
 * @param onSelect Selection callback
 */
@Composable
fun SingleChoiceRow(
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (optionValue, label) ->
            FilterChip(
                selected = selected == optionValue,
                onClick = { onSelect(optionValue) },
                label = { Text(label) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
