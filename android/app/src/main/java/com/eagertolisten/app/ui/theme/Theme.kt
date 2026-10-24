package com.eagertolisten.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = BurntOrange,
    onPrimary = TextPrimary,
    primaryContainer = ForestGreenElevated,
    onPrimaryContainer = BurntOrangeLight,
    secondary = ForestGreenAccent,
    onSecondary = TextPrimary,
    background = ForestGreenDarkest,
    onBackground = TextPrimary,
    surface = ForestGreenSurface,
    onSurface = TextPrimary,
    surfaceVariant = ForestGreenElevated,
    onSurfaceVariant = TextSecondary,
    outline = ForestGreenBorder
)

@Composable
fun EagerToListenTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
