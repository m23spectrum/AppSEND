package local.edgebeam

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import kotlin.math.*

internal data class MotionFrame(
    val t: Float,
    val progress: Float,
    val alpha: Float,
    val glow: Float,
)

internal fun motionFrame(ms: Float, incoming: Boolean, plane: Boolean): MotionFrame {
    val t = ((ms - if (incoming) 350f else 0f) / (if (incoming) 650f else 350f)).coerceIn(0f, 1f)
    val progress =
        if (!incoming) t * t * t
        else if (plane) 1 + 2.2f * (t - 1).pow(3) + 1.2f * (t - 1).pow(2) else 1 - (1 - t).pow(3)
    val alpha =
        if (ms < 0) 0f
        else if (incoming) (t * 7).coerceIn(0f, 1f) * (1 - ((ms - 1200) / 350).coerceIn(0f, 1f))
        else 1 - t * t
    return MotionFrame(t, progress, alpha, max(0f, 1 - abs(ms - 350) / 450).pow(2))
}

@Composable
internal fun HandoffOverlay(animation: Animation) {
    val elapsed = remember(animation) { mutableFloatStateOf(-1f) }
    var visible by remember(animation) { mutableStateOf(true) }
    val context = LocalContext.current
    LaunchedEffect(animation) {
        val base = SystemClock.elapsedRealtime() + animation.at - System.currentTimeMillis()
        if (SystemClock.elapsedRealtime() - base > 1600) {
            visible = false
            return@LaunchedEffect
        }
        var haptic = false
        while (true) {
            withFrameNanos { elapsed.floatValue = (SystemClock.elapsedRealtime() - base).toFloat() }
            if (!haptic && elapsed.floatValue >= 350) {
                haptic = true
                val vibrator =
                    if (Build.VERSION.SDK_INT >= 31)
                        context.getSystemService(VibratorManager::class.java).defaultVibrator
                    else
                        @Suppress("DEPRECATION")
                        (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                vibrator.vibrate(VibrationEffect.createOneShot(28, 150))
            }
            if (elapsed.floatValue > 1600) {
                visible = false
                break
            }
        }
    }
    if (!visible) return
    val plane = animation.motion == "plane"
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = with(density) { maxHeight.toPx() }
        val width = with(density) { maxWidth.toPx() }
        if (!plane)
            Canvas(Modifier.fillMaxSize()) {
                val f = motionFrame(elapsed.floatValue, animation.incoming, false)
                drawRect(
                    Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = f.glow * .65f),
                            Accent.copy(alpha = f.glow * .4f),
                            Color.Transparent,
                        ),
                        Offset(size.width / 2, 0f),
                        size.height * .8f,
                    )
                )
                val depth = size.height * .085f * f.glow
                val sheet =
                    Path().apply {
                        moveTo(0f, 0f)
                        cubicTo(
                            size.width * .22f,
                            0f,
                            size.width * .32f,
                            depth,
                            size.width * .5f,
                            depth,
                        )
                        cubicTo(size.width * .68f, depth, size.width * .78f, 0f, size.width, 0f)
                        close()
                    }
                drawPath(
                    sheet,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = f.glow),
                            Accent.copy(alpha = f.glow * .8f),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = depth.coerceAtLeast(1f),
                    ),
                )
                drawRoundRect(
                    Accent.copy(alpha = f.glow * .45f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(30.dp.toPx()),
                    style = Stroke(2.dp.toPx()),
                )
                drawLine(
                    Color.White.copy(alpha = f.glow),
                    Offset(0f, 1f),
                    Offset(size.width, 1f),
                    3.dp.toPx(),
                )
            }
        if (!plane)
            Row(
                Modifier.align(Alignment.TopCenter)
                    .padding(top = 52.dp)
                    .width(230.dp)
                    .graphicsLayer {
                        val ms = elapsed.floatValue
                        alpha = if (ms < 0) 0f else max(0f, 1 - abs(ms - 250) / 330f)
                        translationY = -12.dp.toPx() * (1 - alpha)
                    }
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.Black)
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                LogoMark(Modifier.size(24.dp))
                Text(
                    animation.name,
                    fontSize = 12.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        if (!(plane && !animation.incoming && animation.departureShown))
            Column(
                Modifier.align(if (plane) Alignment.Center else Alignment.TopCenter)
                    .padding(top = if (plane) 0.dp else 62.dp)
                    .graphicsLayer {
                        val f = motionFrame(elapsed.floatValue, animation.incoming, plane)
                        val position = if (animation.incoming) 1 - f.progress else f.progress
                        translationY =
                            if (plane) -height * .85f * position
                            else with(density) { (-200 * position).dp.toPx() }
                        translationX =
                            if (plane) width * .22f * sin(position * Math.PI).toFloat() else 0f
                        rotationZ = if (plane) -35 * position else 0f
                        scaleX = 1 - .55f * position
                        scaleY =
                            if (plane) scaleX
                            else if (animation.incoming) .08f + .92f * f.t else 1 - .92f * f.t
                        if (!plane)
                            scaleX =
                                if (animation.incoming) .8f + .2f * f.t
                                else 1 + .12f * sin(f.t * Math.PI).toFloat()
                        alpha = f.alpha
                    }
                    .then(
                        if (plane && !animation.incoming) Modifier
                        else
                            Modifier.width(224.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.White)
                                .padding(20.dp)
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FileMark(
                    plane && !animation.incoming,
                    Modifier.size(if (plane && !animation.incoming) 70.dp else 30.dp),
                    if (plane && !animation.incoming) Color.White else Color.Black,
                )
                Text(
                    animation.name,
                    fontSize = 12.sp,
                    color = if (plane && !animation.incoming) Color.White else Color.Black,
                    maxLines = 1,
                )
            }
    }
}
