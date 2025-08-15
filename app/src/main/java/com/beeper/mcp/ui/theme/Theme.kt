package com.beeper.mcp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0D4FFB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFF0D4FFB),
    secondary = Color(0xFF0D4FFB),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF0D4FFB),
    tertiary = Color(0xFF0D4FFB),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF4F4F5),
    onTertiaryContainer = Color(0xFF0D4FFB),
    surfaceDim = Color(0xFFEBEBEB),
    surface = Color(0xFFFFFFFF),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFEBEBEB),
    surfaceContainerHighest = Color(0xFFE5E5E5),
    onSurface = Color(0xFF202124),
    onSurfaceVariant = Color(0xFF808080),
    outline = Color(0xFFB3B3B3),
    outlineVariant = Color(0xFFF4F4F6),
    error = Color(0xFFDC362E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFDC362E),
    onErrorContainer = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF000000),
    inverseOnSurface = Color(0xFFF6F6F6),
    inversePrimary = Color(0xFF2561FB),
    scrim = Color(0xFF000000),
    // These colors are the same as "surface" and "onSurface" colors.
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF202124),
    // This color is the same as "surfaceContainer" color.
    surfaceVariant = Color(0xFFF0F0F0)
)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF2561FB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1C1C1D),
    onPrimaryContainer = Color(0xFF2561FB),
    secondary = Color(0xFF2561FB),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF1C1C1D),
    onSecondaryContainer = Color(0xFF2561FB),
    tertiary = Color(0xFF2561FB),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF1C1C1D),
    onTertiaryContainer = Color(0xFF2561FB),
    surfaceDim = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceBright = Color(0xFF262626),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF2B2B2B),
    surfaceContainerHighest = Color(0xFF3D3D3D),
    onSurface = Color(0xFFF6F6F6),
    onSurfaceVariant = Color(0xFF808080),
    outline = Color(0xFF4C4C4C),
    outlineVariant = Color(0xFF1C1C1D),
    error = Color(0xFFDC362E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFDC362E),
    onErrorContainer = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF202124),
    inversePrimary = Color(0xFF0D4FFB),
    scrim = Color(0xFF000000),
    // These colors are the same as "surface" and "onSurface" colors.
    background = Color(0xFF000000),
    onBackground = Color(0xFFF6F6F6),
    // This color is the same as "surfaceContainer" color.
    surfaceVariant = Color(0xFF1F1F1F)
)
@Composable
fun BeeperMcpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}