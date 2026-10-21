package local.edgebeam

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/** Draw-only animations keep the camera subtree out of per-frame recomposition. */
@Composable
internal fun GrabViewfinder(held: Boolean, focus: HandFocus?, modifier: Modifier = Modifier) {
    val grip =
        animateFloatAsState(if (held) 1f else 0f, spring(stiffness = 450f), label = "grip-frame")
    val x = animateFloatAsState(focus?.x ?: .5f, tween(80), label = "hand-x")
    val y = animateFloatAsState(focus?.y ?: .5f, tween(80), label = "hand-y")
    val visible =
        animateFloatAsState(if (focus != null) 1f else 0f, tween(110), label = "hand-visible")
    val aspect = focus?.aspect ?: .75f
    Canvas(modifier) {
        val w = min(size.width, size.height * aspect)
        val h = w / aspect
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        val inset = 14.dp.toPx()
        val length = 23.dp.toPx() + 7.dp.toPx() * grip.value
        val color = Color.White.copy(alpha = (.45f + .5f * grip.value).coerceIn(0f, 1f))
        for ((cx, cy) in
            listOf(
                left + inset to top + inset,
                left + w - inset to top + inset,
                left + inset to top + h - inset,
                left + w - inset to top + h - inset,
            )) {
            val sx = if (cx < size.width / 2) 1 else -1
            val sy = if (cy < size.height / 2) 1 else -1
            drawLine(
                color,
                Offset(cx, cy),
                Offset(cx + sx * length, cy),
                2.dp.toPx(),
                StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(cx, cy),
                Offset(cx, cy + sy * length),
                2.dp.toPx(),
                StrokeCap.Round,
            )
        }
        val center = Offset(left + x.value * w, top + y.value * h)
        val radius = (34 - 9 * grip.value).dp.toPx()
        drawCircle(Accent.copy(alpha = .09f * visible.value), radius, center)
        drawCircle(
            Accent.copy(alpha = .85f * visible.value),
            radius,
            center,
            style = Stroke(1.5.dp.toPx()),
        )
        drawCircle(Color.White.copy(alpha = visible.value), 2.dp.toPx(), center)
    }
}

@Composable
internal fun TransferFileCard(name: String, detail: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color.White, RoundedCornerShape(22.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        FileMark(false, Modifier.size(36.dp), Color.Black)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                name,
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, fontSize = 11.sp, color = Color.Black.copy(alpha = .5f))
        }
    }
}

@Composable
internal fun DeliveryCheck(modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val t = progress.value
        val radius = min(size.width, size.height) * .43f
        drawCircle(Accent.copy(alpha = .08f), radius * (.8f + .2f * t))
        drawArc(
            Accent,
            -90f,
            360f * t,
            false,
            topLeft = Offset(size.width / 2 - radius, size.height / 2 - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
        )
        val check =
            Path().apply {
                val start = Offset(size.width * .32f, size.height * .5f)
                val mid = Offset(size.width * .45f, size.height * .62f)
                val end = Offset(size.width * .7f, size.height * .37f)
                val p = ((t - .3f) / .7f).coerceIn(0f, 1f)
                moveTo(start.x, start.y)
                if (p < .35f) {
                    val q = p / .35f
                    lineTo(start.x + (mid.x - start.x) * q, start.y + (mid.y - start.y) * q)
                } else {
                    lineTo(mid.x, mid.y)
                    val q = (p - .35f) / .65f
                    lineTo(mid.x + (end.x - mid.x) * q, mid.y + (end.y - mid.y) * q)
                }
            }
        drawPath(check, Color.White, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
internal fun DeliveryCross(modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val t = progress.value
        val radius = min(size.width, size.height) * .43f
        drawCircle(Accent.copy(alpha = .08f), radius * (.8f + .2f * t))
        drawArc(
            Accent.copy(alpha = .45f),
            -90f,
            360f * t,
            false,
            topLeft = Offset(size.width / 2 - radius, size.height / 2 - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
        )
        val d = radius * .42f * t
        val cx = size.width / 2
        val cy = size.height / 2
        drawLine(Color.White, Offset(cx - d, cy - d), Offset(cx + d, cy + d), 2.5.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White, Offset(cx + d, cy - d), Offset(cx - d, cy + d), 2.5.dp.toPx(), StrokeCap.Round)
    }
}
