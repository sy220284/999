package cn.codex.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

val LocalMotionEnabled = staticCompositionLocalOf { true }

private val Dark = darkColorScheme(
    primary = Color(0xFF9CDBC0), onPrimary = Color(0xFF103C2D), primaryContainer = Color(0xFF234A3C), onPrimaryContainer = Color(0xFFD2F5E4),
    background = Color(0xFF111615), onBackground = Color(0xFFE8EEEA), surface = Color(0xFF111615), onSurface = Color(0xFFE8EEEA),
    surfaceContainer = Color(0xFF1B2220), surfaceContainerLow = Color(0xFF171D1B), surfaceContainerHigh = Color(0xFF242D29),
    surfaceVariant = Color(0xFF29332E), onSurfaceVariant = Color(0xFFB0BEB5), outline = Color(0xFF536059), outlineVariant = Color(0xFF303C35), error = Color(0xFFFFB4AB)
)
private val Light = lightColorScheme(
    primary = Color(0xFF28634D), onPrimary = Color.White, primaryContainer = Color(0xFFD9EDE1), onPrimaryContainer = Color(0xFF173E2B),
    background = Color(0xFFF7F8F3), onBackground = Color(0xFF1A231E), surface = Color(0xFFF7F8F3), onSurface = Color(0xFF1A231E),
    surfaceContainer = Color(0xFFEDF0E9), surfaceContainerLow = Color(0xFFF1F3ED), surfaceContainerHigh = Color(0xFFE3E9DF),
    surfaceVariant = Color(0xFFE1E8DF), onSurfaceVariant = Color(0xFF536157), outline = Color(0xFF738176), outlineVariant = Color(0xFFD4DDD2)
)
@Composable fun CodexTheme(theme: String, content: @Composable () -> Unit) {
    val dark = if (theme == "system") isSystemInDarkTheme() else theme == "dark"
    val typography = Typography(
        headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, lineHeight = 42.sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 26.sp, lineHeight = 36.sp),
        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 21.sp, lineHeight = 29.sp),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 27.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 23.sp),
        labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp)
    )
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = typography, content = content)
}
