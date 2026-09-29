package com.patipan.tripmap.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = lightColorScheme(
    primary = Color(0xFF2783DE), onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F2FC), onPrimaryContainer = Color(0xFF185D9D),
    background = Color.White, surface = Color.White,
    onSurface = Color(0xFF2C2C2B), outline = Color(0xFFE6E5E3)
)

@Composable fun TripMapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
