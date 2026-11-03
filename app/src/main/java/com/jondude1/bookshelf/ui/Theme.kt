package com.jondude1.bookshelf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jondude1.bookshelf.R

@OptIn(ExperimentalTextApi::class)
private fun lit(w: Int) = Font(R.font.literata, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(ExperimentalTextApi::class)
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Literata = FontFamily(lit(300), lit(400), lit(500), lit(600), lit(700))
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

object Brand {
    val Gold = Color(0xFFD9B36C)
    val GoldDeep = Color(0xFF8A6A2F)
    val Ink = Color(0xFF0E0F11)
}

private val Dark = darkColorScheme(
    primary = Brand.Gold,
    onPrimary = Color(0xFF1C1508),
    primaryContainer = Color(0xFF3A2F1B),
    onPrimaryContainer = Color(0xFFF3DEB3),
    secondary = Color(0xFFB9B2A4),
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFF2A2C31),
    onSecondaryContainer = Color(0xFFE6E1D6),
    background = Brand.Ink,
    onBackground = Color(0xFFECE7DD),
    surface = Color(0xFF131518),
    onSurface = Color(0xFFECE7DD),
    surfaceVariant = Color(0xFF1D2025),
    onSurfaceVariant = Color(0xFF9F9A90),
    surfaceContainerLowest = Color(0xFF0B0C0E),
    surfaceContainerLow = Color(0xFF15171A),
    surfaceContainer = Color(0xFF191B1F),
    surfaceContainerHigh = Color(0xFF202328),
    surfaceContainerHighest = Color(0xFF272A30),
    outline = Color(0xFF3A3D44),
    outlineVariant = Color(0xFF2A2D33),
    error = Color(0xFFE8857A),
)

private val Light = lightColorScheme(
    primary = Brand.GoldDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1E2C2),
    onPrimaryContainer = Color(0xFF2E2310),
    secondary = Color(0xFF5E5A52),
    secondaryContainer = Color(0xFFEAE5DA),
    onSecondaryContainer = Color(0xFF26231E),
    background = Color(0xFFF7F4EE),
    onBackground = Color(0xFF1D1B18),
    surface = Color(0xFFFBF9F5),
    onSurface = Color(0xFF1D1B18),
    surfaceVariant = Color(0xFFECE7DE),
    onSurfaceVariant = Color(0xFF6A665E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F0E9),
    surfaceContainer = Color(0xFFEFEAE2),
    surfaceContainerHigh = Color(0xFFE9E4DB),
    surfaceContainerHighest = Color(0xFFE3DDD3),
    outline = Color(0xFFC9C2B5),
    outlineVariant = Color(0xFFDDD6CA),
)

private val Type = Typography(
    displayLarge = TextStyle(fontFamily = Literata, fontWeight = FontWeight(500), fontSize = 44.sp, lineHeight = 50.sp),
    displayMedium = TextStyle(fontFamily = Literata, fontWeight = FontWeight(500), fontSize = 36.sp, lineHeight = 42.sp),
    displaySmall = TextStyle(fontFamily = Literata, fontWeight = FontWeight(500), fontSize = 30.sp, lineHeight = 36.sp),
    headlineLarge = TextStyle(fontFamily = Literata, fontWeight = FontWeight(600), fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Literata, fontWeight = FontWeight(600), fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Literata, fontWeight = FontWeight(600), fontSize = 21.sp, lineHeight = 27.sp),
    titleLarge = TextStyle(fontFamily = Literata, fontWeight = FontWeight(600), fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight(400), fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight(400), fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight(500), fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp),
)

@Composable
fun BookShelfTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}
