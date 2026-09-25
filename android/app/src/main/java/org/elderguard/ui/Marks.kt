package org.elderguard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ShieldState { Safe, Caution, Brand }

/** The guard's single, consistent mark: a shield. Hand-drawn so we need no icon library. */
@Composable
fun ShieldMark(state: ShieldState, size: Dp, description: String? = null) {
    val fill = when (state) { ShieldState.Safe -> Palette.Safe; ShieldState.Caution -> Palette.Caution; ShieldState.Brand -> Palette.Trust }
    val m = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    Canvas(m.size(size)) {
        val w = this.size.width; val h = this.size.height
        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.04f)
            lineTo(w * 0.9f, h * 0.18f)
            lineTo(w * 0.9f, h * 0.46f)
            cubicTo(w * 0.9f, h * 0.72f, w * 0.72f, h * 0.88f, w * 0.5f, h * 0.97f)
            cubicTo(w * 0.28f, h * 0.88f, w * 0.1f, h * 0.72f, w * 0.1f, h * 0.46f)
            lineTo(w * 0.1f, h * 0.18f)
            close()
        }
        drawPath(shield, fill)
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (state) {
            ShieldState.Caution -> {
                drawLine(Color.White, Offset(w * 0.5f, h * 0.3f), Offset(w * 0.5f, h * 0.56f), w * 0.09f, StrokeCap.Round)
                drawCircle(Color.White, w * 0.055f, Offset(w * 0.5f, h * 0.72f))
            }
            else -> drawPath(Path().apply {
                moveTo(w * 0.32f, h * 0.5f); lineTo(w * 0.45f, h * 0.63f); lineTo(w * 0.7f, h * 0.37f)
            }, Color.White, style = stroke)
        }
    }
}

/** Small round bullet used in "it may..." lists. */
@Composable
fun Dot(color: Color, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) { drawCircle(color) }
}
