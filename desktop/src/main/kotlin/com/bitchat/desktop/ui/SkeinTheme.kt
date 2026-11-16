package com.bitchat.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

/** Bitchat's dark terminal look, same colors as the phone's DarkBitchatColorScheme. */
object SkeinColors {
    val green = Color(0xFF32D74B)
    val blue = Color(0xFF0A84FF)
    val orange = Color(0xFFFF9F0A)
    val purple = Color(0xFFBF5AF2)
    val red = Color(0xFFFF453A)
    val muted = Color(0xFF9AA69A)
    val tertiary = Color(0xFF6B776B)
    val mentionBackground = Color(0x33FF9F0A)
    val selected = Color(0xFF163D1D)
    val sidebar = Color(0xFF070B07)
}

private val SkeinDarkScheme = darkColorScheme(
    primary = SkeinColors.green,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF163D1D),
    onPrimaryContainer = Color(0xFFB8F5C1),
    secondary = SkeinColors.blue,
    onSecondary = Color.Black,
    tertiary = SkeinColors.orange,
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF0E150E),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF182118),
    onSurfaceVariant = SkeinColors.muted,
    outline = Color(0xFF2A3A2A),
    outlineVariant = Color(0xFF1C271C),
    error = SkeinColors.red,
    onError = Color.Black,
)

private fun typography(family: FontFamily) = Typography().let { t ->
    fun f(size: Int, base: TextStyle) = base.copy(fontFamily = family, fontSize = size.sp)
    t.copy(
        headlineSmall = f(20, t.headlineSmall),
        titleMedium = f(15, t.titleMedium),
        titleSmall = f(13, t.titleSmall),
        bodyLarge = f(14, t.bodyLarge),
        bodyMedium = f(13, t.bodyMedium),
        bodySmall = f(11, t.bodySmall),
        labelLarge = f(13, t.labelLarge),
        labelMedium = f(12, t.labelMedium),
        labelSmall = f(10, t.labelSmall),
    )
}

// Bitchat's terminal monospace for Latin UI; Arabic needs a proportional font to join letters.
private val MonoTypography = typography(FontFamily.Monospace)
private val ProportionalTypography = typography(FontFamily.Default)

/** For fingerprints, peer IDs and #channel names: monospace, always left to right. */
fun TextStyle.technical(): TextStyle = copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)

@Composable
fun SkeinTheme(language: AppLanguage = AppLanguage.ENGLISH, content: @Composable () -> Unit) {
    val typography = if (language == AppLanguage.ARABIC) ProportionalTypography else MonoTypography
    MaterialTheme(colorScheme = SkeinDarkScheme, typography = typography, content = content)
}
