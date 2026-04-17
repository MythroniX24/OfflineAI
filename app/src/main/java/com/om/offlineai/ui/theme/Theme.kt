package com.om.offlineai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Dark palette (ChatGPT-like) ───────────────────────────────────
val DarkBackground   = Color(0xFF0D1117)
val DarkSurface      = Color(0xFF161B22)
val DarkSurfaceVar   = Color(0xFF21262D)
val DarkPrimary      = Color(0xFF10A37F)   // GPT green
val DarkOnPrimary    = Color(0xFFFFFFFF)
val DarkOnBackground = Color(0xFFE6EDF3)
val UserBubble       = Color(0xFF2D333B)
val AssistantBubble  = Color(0xFF161B22)

// ── Light palette ────────────────────────────────────────────────
val LightBackground  = Color(0xFFF9F9F9)
val LightSurface     = Color(0xFFFFFFFF)
val LightPrimary     = Color(0xFF10A37F)

private val DarkColorScheme = darkColorScheme(
    primary          = DarkPrimary,
    onPrimary        = DarkOnPrimary,
    background       = DarkBackground,
    onBackground     = DarkOnBackground,
    surface          = DarkSurface,
    surfaceVariant   = DarkSurfaceVar,
    onSurface        = DarkOnBackground,
    onSurfaceVariant = Color(0xFF8B949E),
    outline          = Color(0xFF30363D),
    error            = Color(0xFFF85149)
)

private val LightColorScheme = lightColorScheme(
    primary      = LightPrimary,
    background   = LightBackground,
    surface      = LightSurface,
    onBackground = Color(0xFF1A1A1A),
    onSurface    = Color(0xFF1A1A1A)
)

@Composable
fun OfflineAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography(),
        content     = content
    )
}
