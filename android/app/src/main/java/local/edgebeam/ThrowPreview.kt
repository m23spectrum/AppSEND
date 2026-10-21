package local.edgebeam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.sin

internal data class ThrowPreview(
    val id: Long,
    val name: String,
    val bounds: Rect,
    val detail: String,
    val rotation: Float,
    val compact: Boolean = false,
    val initialScale: Float = 1f,
)

@Composable
internal fun LocalThrowPreview(flight: ThrowPreview, finished: () -> Unit) {
    val progress = remember(flight.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(flight.id) {
        var start = 0L
        while (true) {
            withFrameNanos { now ->
                if (start == 0L) start = now
                progress.floatValue =
                    ((now - start) / (if (flight.compact) 280_000_000f else 360_000_000f)).coerceIn(
                        0f,
                        1f,
                    )
            }
            if (progress.floatValue >= 1f) break
        }
        finished()
    }
    val density = LocalDensity.current
    val width = with(density) { (flight.bounds.width / flight.initialScale).toDp() }
    val height = with(density) { (flight.bounds.height / flight.initialScale).toDp() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val distance = with(density) { maxHeight.toPx() } + flight.bounds.bottom
        val trajectory =
            Modifier.width(width).height(height).graphicsLayer {
                val t = progress.floatValue
                val travel = t * t * (.35f + .65f * t)
                translationX =
                    flight.bounds.left + sin(t * Math.PI).toFloat() * 55 * density.density
                translationY = flight.bounds.top - distance * travel
                rotationZ = flight.rotation * (1 - t) - 28 * t
                if (flight.compact) transformOrigin = TransformOrigin(0f, 0f)
                scaleX = flight.initialScale * (1 - .55f * t)
                scaleY = scaleX
                alpha = 1 - t * t
            }
        if (flight.compact) TransferFileCard(flight.name, flight.detail, trajectory)
        else
            Column(
                trajectory.background(Color.White, RoundedCornerShape(25.dp)).padding(25.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FileMark(false, Modifier.size(45.dp), Color.Black)
                Text(
                    flight.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Black,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(flight.detail, fontSize = 12.sp, color = Color.Black.copy(alpha = .5f))
            }
    }
}
