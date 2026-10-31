package io.veko.browser.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Biểu trưng hình học V-Prism đặc trưng của Veko, vẽ thuần vector qua Canvas (sắc nét ở mọi mật độ điểm ảnh).
 */
@Composable
fun VekoPrismEmblem(
    isPrivate: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bgBrush = if (isPrivate) {
        Brush.linearGradient(
            colors = listOf(Color(0xFF0D9488), Color(0xFF0284C7)),
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color(0xFF1D4ED8), Color(0xFF06B6D4)),
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(bgBrush),
    ) {
        Canvas(modifier = Modifier.size(size * 0.58f)) {
            val w = this.size.width
            val h = this.size.height
            val vPath = Path().apply {
                moveTo(w * 0.14f, h * 0.20f)
                lineTo(w * 0.50f, h * 0.84f)
                lineTo(w * 0.86f, h * 0.20f)
            }
            drawPath(
                path = vPath,
                color = Color.White,
                style = Stroke(
                    width = w * 0.19f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            drawCircle(
                color = Color(0xFFA5F3FC),
                radius = w * 0.10f,
                center = Offset(w * 0.50f, h * 0.24f),
            )
        }
    }
}

/**
 * Biểu tượng ký tự đầu tên miền khi trang web chưa có favicon trong bộ nhớ.
 */
@Composable
fun DomainMonogramBadge(
    monogram: String,
    accentIndex: Int,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = listOf(
        Color(0xFF38BDF8) to Color(0xFF0F2D4A),
        Color(0xFF60A5FA) to Color(0xFF132A4E),
        Color(0xFF2DD4BF) to Color(0xFF0E3535),
        Color(0xFF818CF8) to Color(0xFF1D224C),
        Color(0xFF22D3EE) to Color(0xFF0D3244),
        Color(0xFF94A3B8) to Color(0xFF1B273B),
    )
    val (fg, bg) = palette[accentIndex.mod(palette.size)]
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(11.dp))
            .background(bg),
    ) {
        Text(
            text = monogram,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = fg,
        )
    }
}
