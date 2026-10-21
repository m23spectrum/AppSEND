package local.edgebeam

import android.os.SystemClock
import androidx.compose.runtime.*
import kotlin.math.*

/** Frame state is consumed by a graphics layer, not by the main screen layout. */
@Composable
internal fun rememberEdgePulse(animation: Animation?): MutableFloatState {
    val pulse = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animation) {
        pulse.floatValue = 0f
        if (animation == null || animation.motion != "edge") return@LaunchedEffect
        val base = SystemClock.elapsedRealtime() + animation.at - System.currentTimeMillis()
        if (SystemClock.elapsedRealtime() - base > 1600) return@LaunchedEffect
        while (true) {
            var elapsed = 0L
            withFrameNanos {
                elapsed = SystemClock.elapsedRealtime() - base
                pulse.floatValue = max(0f, 1 - abs(elapsed - 350) / 500f).pow(2)
            }
            if (elapsed > 1600) break
        }
        pulse.floatValue = 0f
    }
    return pulse
}
