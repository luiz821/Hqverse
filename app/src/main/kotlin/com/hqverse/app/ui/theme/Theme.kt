package com.hqverse.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.hqverse.app.data.settings.Accent
import com.hqverse.app.data.settings.ThemeMode

/** Preferência do sistema de redução de animações + opção do app. */
val LocalReduceMotion = staticCompositionLocalOf { false }

private val Ink = Color(0xFF0D0F1A)
private val Surface1 = Color(0xFF151828)
private val Surface2 = Color(0xFF1C2033)

private fun dark(accent: Color) = darkColorScheme(
    primary = accent, onPrimary = Color(0xFF0D0B22), primaryContainer = accent.copy(alpha = 0.22f), onPrimaryContainer = Color(0xFFEDEAFF),
    secondary = Color(0xFF7FD6FF), onSecondary = Color(0xFF00212F),
    background = Ink, onBackground = Color(0xFFECEDF5),
    surface = Surface1, onSurface = Color(0xFFECEDF5), surfaceVariant = Surface2, onSurfaceVariant = Color(0xFFAEB2C8),
    surfaceContainer = Surface1, surfaceContainerHigh = Surface2, surfaceContainerHighest = Color(0xFF262B42),
    outline = Color(0xFF5B6180), outlineVariant = Color(0xFF2B3050), error = Color(0xFFFF6B7A),
)

private fun light(accent: Color) = lightColorScheme(
    primary = accent.copy(red = accent.red * 0.7f, green = accent.green * 0.7f, blue = accent.blue * 0.85f), onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.18f), onPrimaryContainer = Color(0xFF1B1346),
    background = Color(0xFFF6F6FB), onBackground = Color(0xFF14152B), surface = Color.White, onSurface = Color(0xFF14152B),
    surfaceVariant = Color(0xFFECEDF6), onSurfaceVariant = Color(0xFF51556E), outline = Color(0xFF8A8FAA), outlineVariant = Color(0xFFD6D8E8),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun HQVerseTheme(mode: ThemeMode, accent: Accent, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val useDark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val color = Color(accent.argb)
    MaterialTheme(colorScheme = if (useDark) dark(color) else light(color), typography = AppTypography, shapes = AppShapes) {
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion) { content() }
    }
}
