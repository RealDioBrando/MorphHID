package dev.morphhid.ui.renderer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.morphhid.core.profile.ThemeSpec

private fun parseColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: IllegalArgumentException) {
        fallback
    }
}

@Composable
fun RendererTheme(theme: ThemeSpec?, content: @Composable () -> Unit) {
    val dark = theme?.dark ?: true
    val primary = parseColor(theme?.primaryColor, Color(0xFF7C4DFF))
    val accent = parseColor(theme?.accentColor, Color(0xFF00E5FF))
    val background = parseColor(theme?.backgroundColor, if (dark) Color(0xFF101018) else Color(0xFFF4F4F8))
    val scheme = if (dark) {
        darkColorScheme(primary = primary, secondary = accent, background = background, surface = background)
    } else {
        lightColorScheme(primary = primary, secondary = accent, background = background, surface = background)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}