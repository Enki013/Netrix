package com.enki.netrix.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.enki.netrix.data.AppTheme

// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║                              DEFAULT THEME                                    ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

private val DefaultLightScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight
)

private val DefaultDarkScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark
)

// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║                         🌊 OCEAN THEME                                        ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

private val OceanLightScheme = lightColorScheme(
    primary = OceanPrimaryLight,
    onPrimary = OceanOnPrimaryLight,
    primaryContainer = OceanPrimaryContainerLight,
    onPrimaryContainer = OceanOnPrimaryContainerLight,
    secondary = OceanSecondaryLight,
    onSecondary = OceanOnSecondaryLight,
    secondaryContainer = OceanSecondaryContainerLight,
    onSecondaryContainer = OceanOnSecondaryContainerLight,
    tertiary = OceanTertiaryLight,
    onTertiary = OceanOnTertiaryLight,
    tertiaryContainer = OceanTertiaryContainerLight,
    onTertiaryContainer = OceanOnTertiaryContainerLight,
    error = OceanErrorLight,
    onError = OceanOnErrorLight,
    errorContainer = OceanErrorContainerLight,
    onErrorContainer = OceanOnErrorContainerLight,
    background = OceanBackgroundLight,
    onBackground = OceanOnBackgroundLight,
    surface = OceanSurfaceLight,
    onSurface = OceanOnSurfaceLight,
    surfaceVariant = OceanSurfaceVariantLight,
    onSurfaceVariant = OceanOnSurfaceVariantLight,
    outline = OceanOutlineLight,
    outlineVariant = OceanOutlineVariantLight
)

private val OceanDarkScheme = darkColorScheme(
    primary = OceanPrimaryDark,
    onPrimary = OceanOnPrimaryDark,
    primaryContainer = OceanPrimaryContainerDark,
    onPrimaryContainer = OceanOnPrimaryContainerDark,
    secondary = OceanSecondaryDark,
    onSecondary = OceanOnSecondaryDark,
    secondaryContainer = OceanSecondaryContainerDark,
    onSecondaryContainer = OceanOnSecondaryContainerDark,
    tertiary = OceanTertiaryDark,
    onTertiary = OceanOnTertiaryDark,
    tertiaryContainer = OceanTertiaryContainerDark,
    onTertiaryContainer = OceanOnTertiaryContainerDark,
    error = OceanErrorDark,
    onError = OceanOnErrorDark,
    errorContainer = OceanErrorContainerDark,
    onErrorContainer = OceanOnErrorContainerDark,
    background = OceanBackgroundDark,
    onBackground = OceanOnBackgroundDark,
    surface = OceanSurfaceDark,
    onSurface = OceanOnSurfaceDark,
    surfaceVariant = OceanSurfaceVariantDark,
    onSurfaceVariant = OceanOnSurfaceVariantDark,
    outline = OceanOutlineDark,
    outlineVariant = OceanOutlineVariantDark
)

// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║                         🌲 FOREST THEME                                       ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

private val ForestLightScheme = lightColorScheme(
    primary = ForestPrimaryLight,
    onPrimary = ForestOnPrimaryLight,
    primaryContainer = ForestPrimaryContainerLight,
    onPrimaryContainer = ForestOnPrimaryContainerLight,
    secondary = ForestSecondaryLight,
    onSecondary = ForestOnSecondaryLight,
    secondaryContainer = ForestSecondaryContainerLight,
    onSecondaryContainer = ForestOnSecondaryContainerLight,
    tertiary = ForestTertiaryLight,
    onTertiary = ForestOnTertiaryLight,
    tertiaryContainer = ForestTertiaryContainerLight,
    onTertiaryContainer = ForestOnTertiaryContainerLight,
    error = ForestErrorLight,
    onError = ForestOnErrorLight,
    errorContainer = ForestErrorContainerLight,
    onErrorContainer = ForestOnErrorContainerLight,
    background = ForestBackgroundLight,
    onBackground = ForestOnBackgroundLight,
    surface = ForestSurfaceLight,
    onSurface = ForestOnSurfaceLight,
    surfaceVariant = ForestSurfaceVariantLight,
    onSurfaceVariant = ForestOnSurfaceVariantLight,
    outline = ForestOutlineLight,
    outlineVariant = ForestOutlineVariantLight
)

private val ForestDarkScheme = darkColorScheme(
    primary = ForestPrimaryDark,
    onPrimary = ForestOnPrimaryDark,
    primaryContainer = ForestPrimaryContainerDark,
    onPrimaryContainer = ForestOnPrimaryContainerDark,
    secondary = ForestSecondaryDark,
    onSecondary = ForestOnSecondaryDark,
    secondaryContainer = ForestSecondaryContainerDark,
    onSecondaryContainer = ForestOnSecondaryContainerDark,
    tertiary = ForestTertiaryDark,
    onTertiary = ForestOnTertiaryDark,
    tertiaryContainer = ForestTertiaryContainerDark,
    onTertiaryContainer = ForestOnTertiaryContainerDark,
    error = ForestErrorDark,
    onError = ForestOnErrorDark,
    errorContainer = ForestErrorContainerDark,
    onErrorContainer = ForestOnErrorContainerDark,
    background = ForestBackgroundDark,
    onBackground = ForestOnBackgroundDark,
    surface = ForestSurfaceDark,
    onSurface = ForestOnSurfaceDark,
    surfaceVariant = ForestSurfaceVariantDark,
    onSurfaceVariant = ForestOnSurfaceVariantDark,
    outline = ForestOutlineDark,
    outlineVariant = ForestOutlineVariantDark
)

// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║                         🌅 SUNSET THEME                                       ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

private val SunsetLightScheme = lightColorScheme(
    primary = SunsetPrimaryLight,
    onPrimary = SunsetOnPrimaryLight,
    primaryContainer = SunsetPrimaryContainerLight,
    onPrimaryContainer = SunsetOnPrimaryContainerLight,
    secondary = SunsetSecondaryLight,
    onSecondary = SunsetOnSecondaryLight,
    secondaryContainer = SunsetSecondaryContainerLight,
    onSecondaryContainer = SunsetOnSecondaryContainerLight,
    tertiary = SunsetTertiaryLight,
    onTertiary = SunsetOnTertiaryLight,
    tertiaryContainer = SunsetTertiaryContainerLight,
    onTertiaryContainer = SunsetOnTertiaryContainerLight,
    error = SunsetErrorLight,
    onError = SunsetOnErrorLight,
    errorContainer = SunsetErrorContainerLight,
    onErrorContainer = SunsetOnErrorContainerLight,
    background = SunsetBackgroundLight,
    onBackground = SunsetOnBackgroundLight,
    surface = SunsetSurfaceLight,
    onSurface = SunsetOnSurfaceLight,
    surfaceVariant = SunsetSurfaceVariantLight,
    onSurfaceVariant = SunsetOnSurfaceVariantLight,
    outline = SunsetOutlineLight,
    outlineVariant = SunsetOutlineVariantLight
)

private val SunsetDarkScheme = darkColorScheme(
    primary = SunsetPrimaryDark,
    onPrimary = SunsetOnPrimaryDark,
    primaryContainer = SunsetPrimaryContainerDark,
    onPrimaryContainer = SunsetOnPrimaryContainerDark,
    secondary = SunsetSecondaryDark,
    onSecondary = SunsetOnSecondaryDark,
    secondaryContainer = SunsetSecondaryContainerDark,
    onSecondaryContainer = SunsetOnSecondaryContainerDark,
    tertiary = SunsetTertiaryDark,
    onTertiary = SunsetOnTertiaryDark,
    tertiaryContainer = SunsetTertiaryContainerDark,
    onTertiaryContainer = SunsetOnTertiaryContainerDark,
    error = SunsetErrorDark,
    onError = SunsetOnErrorDark,
    errorContainer = SunsetErrorContainerDark,
    onErrorContainer = SunsetOnErrorContainerDark,
    background = SunsetBackgroundDark,
    onBackground = SunsetOnBackgroundDark,
    surface = SunsetSurfaceDark,
    onSurface = SunsetOnSurfaceDark,
    surfaceVariant = SunsetSurfaceVariantDark,
    onSurfaceVariant = SunsetOnSurfaceVariantDark,
    outline = SunsetOutlineDark,
    outlineVariant = SunsetOutlineVariantDark
)

// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║                         💜 LAVENDER THEME                                     ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

private val LavenderLightScheme = lightColorScheme(
    primary = LavenderPrimaryLight,
    onPrimary = LavenderOnPrimaryLight,
    primaryContainer = LavenderPrimaryContainerLight,
    onPrimaryContainer = LavenderOnPrimaryContainerLight,
    secondary = LavenderSecondaryLight,
    onSecondary = LavenderOnSecondaryLight,
    secondaryContainer = LavenderSecondaryContainerLight,
    onSecondaryContainer = LavenderOnSecondaryContainerLight,
    tertiary = LavenderTertiaryLight,
    onTertiary = LavenderOnTertiaryLight,
    tertiaryContainer = LavenderTertiaryContainerLight,
    onTertiaryContainer = LavenderOnTertiaryContainerLight,
    error = LavenderErrorLight,
    onError = LavenderOnErrorLight,
    errorContainer = LavenderErrorContainerLight,
    onErrorContainer = LavenderOnErrorContainerLight,
    background = LavenderBackgroundLight,
    onBackground = LavenderOnBackgroundLight,
    surface = LavenderSurfaceLight,
    onSurface = LavenderOnSurfaceLight,
    surfaceVariant = LavenderSurfaceVariantLight,
    onSurfaceVariant = LavenderOnSurfaceVariantLight,
    outline = LavenderOutlineLight,
    outlineVariant = LavenderOutlineVariantLight
)

private val LavenderDarkScheme = darkColorScheme(
    primary = LavenderPrimaryDark,
    onPrimary = LavenderOnPrimaryDark,
    primaryContainer = LavenderPrimaryContainerDark,
    onPrimaryContainer = LavenderOnPrimaryContainerDark,
    secondary = LavenderSecondaryDark,
    onSecondary = LavenderOnSecondaryDark,
    secondaryContainer = LavenderSecondaryContainerDark,
    onSecondaryContainer = LavenderOnSecondaryContainerDark,
    tertiary = LavenderTertiaryDark,
    onTertiary = LavenderOnTertiaryDark,
    tertiaryContainer = LavenderTertiaryContainerDark,
    onTertiaryContainer = LavenderOnTertiaryContainerDark,
    error = LavenderErrorDark,
    onError = LavenderOnErrorDark,
    errorContainer = LavenderErrorContainerDark,
    onErrorContainer = LavenderOnErrorContainerDark,
    background = LavenderBackgroundDark,
    onBackground = LavenderOnBackgroundDark,
    surface = LavenderSurfaceDark,
    onSurface = LavenderOnSurfaceDark,
    surfaceVariant = LavenderSurfaceVariantDark,
    onSurfaceVariant = LavenderOnSurfaceVariantDark,
    outline = LavenderOutlineDark,
    outlineVariant = LavenderOutlineVariantDark
)

// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║                               AMOLED THEME                                    ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

private val AmoledLightScheme = lightColorScheme(
    primary = AmoledPrimaryLight,
    onPrimary = AmoledOnPrimaryLight,
    primaryContainer = AmoledPrimaryContainerLight,
    onPrimaryContainer = AmoledOnPrimaryContainerLight,
    secondary = AmoledSecondaryLight,
    onSecondary = AmoledOnSecondaryLight,
    secondaryContainer = AmoledSecondaryContainerLight,
    onSecondaryContainer = AmoledOnSecondaryContainerLight,
    tertiary = AmoledTertiaryLight,
    onTertiary = AmoledOnTertiaryLight,
    tertiaryContainer = AmoledTertiaryContainerLight,
    onTertiaryContainer = AmoledOnTertiaryContainerLight,
    error = AmoledErrorLight,
    onError = AmoledOnErrorLight,
    errorContainer = AmoledErrorContainerLight,
    onErrorContainer = AmoledOnErrorContainerLight,
    background = AmoledBackgroundLight,
    onBackground = AmoledOnBackgroundLight,
    surface = AmoledSurfaceLight,
    onSurface = AmoledOnSurfaceLight,
    surfaceVariant = AmoledSurfaceVariantLight,
    onSurfaceVariant = AmoledOnSurfaceVariantLight,
    outline = AmoledOutlineLight,
    outlineVariant = AmoledOutlineVariantLight
)

private val AmoledDarkScheme = darkColorScheme(
    primary = AmoledPrimaryDark,
    onPrimary = AmoledOnPrimaryDark,
    primaryContainer = AmoledPrimaryContainerDark,
    onPrimaryContainer = AmoledOnPrimaryContainerDark,
    secondary = AmoledSecondaryDark,
    onSecondary = AmoledOnSecondaryDark,
    secondaryContainer = AmoledSecondaryContainerDark,
    onSecondaryContainer = AmoledOnSecondaryContainerDark,
    tertiary = AmoledTertiaryDark,
    onTertiary = AmoledOnTertiaryDark,
    tertiaryContainer = AmoledTertiaryContainerDark,
    onTertiaryContainer = AmoledOnTertiaryContainerDark,
    error = AmoledErrorDark,
    onError = AmoledOnErrorDark,
    errorContainer = AmoledErrorContainerDark,
    onErrorContainer = AmoledOnErrorContainerDark,
    background = AmoledBackgroundDark,
    onBackground = AmoledOnBackgroundDark,
    surface = AmoledSurfaceDark,
    onSurface = AmoledOnSurfaceDark,
    surfaceVariant = AmoledSurfaceVariantDark,
    onSurfaceVariant = AmoledOnSurfaceVariantDark,
    outline = AmoledOutlineDark,
    outlineVariant = AmoledOutlineVariantDark
)

// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║                              THEME COMPOSABLE                                 ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

@Composable
fun NetrixTheme(
    themeMode: AppTheme = AppTheme.SYSTEM,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    val colorScheme = when (themeMode) {
        AppTheme.SYSTEM -> {
            // Android 12+ ise Dynamic Colors kullan
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) 
                else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) DefaultDarkScheme 
                else DefaultLightScheme
            }
        }
        AppTheme.AMOLED -> {
            if (darkTheme) AmoledDarkScheme 
            else AmoledLightScheme
        }
        AppTheme.OCEAN -> {
            if (darkTheme) OceanDarkScheme 
            else OceanLightScheme
        }
        AppTheme.FOREST -> {
            if (darkTheme) ForestDarkScheme 
            else ForestLightScheme
        }
        AppTheme.SUNSET -> {
            if (darkTheme) SunsetDarkScheme 
            else SunsetLightScheme
        }
        AppTheme.LAVENDER -> {
            if (darkTheme) LavenderDarkScheme 
            else LavenderLightScheme
        }
    }

    // Set status bar color
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
