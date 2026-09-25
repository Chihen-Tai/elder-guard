package org.elderguard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

/**
 * Fixed brand palette (no dynamic color) so an older user always recognizes the real guard.
 * Warm and calm on purpose: warnings use amber, never flashing red, so the guard never looks like a scam pop-up.
 * Body text on Paper is well above WCAG AAA (7:1).
 */
object Palette {
    val Paper = Color(0xFFFBF8F2)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1C2733)
    val InkSoft = Color(0xFF4A5663)
    val Trust = Color(0xFF1F5A8A)
    val TrustSoft = Color(0xFFE6EEF6)
    val Safe = Color(0xFF2B7A57)
    val SafeSoft = Color(0xFFE2F1E9)
    val Caution = Color(0xFF9A5B00)
    val CautionSoft = Color(0xFFFCEFD8)
    val Line = Color(0xFFE4DDD0)
}

/**
 * Copy is written as short phrases joined by '\n' (one phrase per line, <= 14 CJK chars at 22sp);
 * balanced breaking then avoids a lone CJK character when a phrase still wraps at large font scales.
 */
private val Balanced = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Strict, LineBreak.WordBreak.Default)

private val ElderTypography = Typography(
    headlineLarge = TextStyle(lineBreak = Balanced, fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(lineBreak = Balanced, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(lineBreak = Balanced, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(lineBreak = Balanced, fontSize = 22.sp, lineHeight = 33.sp),
    bodyMedium = TextStyle(lineBreak = Balanced, fontSize = 20.sp, lineHeight = 30.sp),
    labelLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun GuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Trust, onPrimary = Color.White,
            background = Palette.Paper, onBackground = Palette.Ink,
            surface = Palette.Card, onSurface = Palette.Ink, onSurfaceVariant = Palette.InkSoft,
            outline = Palette.Line,
        ),
        typography = ElderTypography,
        content = content,
    )
}
