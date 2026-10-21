package local.edgebeam

import kotlin.math.abs
import kotlin.math.sqrt

/** The phone is above the monitor, with its top edge or screen facing down. */
internal class ApproachGate {
    private var near = false
    private var nearAt = 0L
    private var downAt = -1L
    private var fired = false
    private var lastTrigger = -4000L

    fun proximity(time: Long, close: Boolean) {
        if (close && !near) nearAt = time
        if (!close) fired = false
        near = close
    }

    fun sample(time: Long, x: Float, y: Float, z: Float): Boolean {
        val magnitude = sqrt(x * x + y * y + z * z)
        val down = magnitude > 1f && (y / magnitude < -.55f || z / magnitude < -.75f)
        if (!down) downAt = -1L else if (downAt < 0) downAt = time
        if (
            near &&
                !fired &&
                downAt >= 0 &&
                time - downAt >= 200 &&
                time - nearAt >= 200 &&
                time - lastTrigger >= 4000
        ) {
            fired = true
            lastTrigger = time
            return true
        }
        return false
    }

    fun reset() {
        near = false
        downAt = -1L
        fired = false
    }
}

internal fun isFileThrow(dx: Float, dy: Float, velocityY: Float, density: Float): Boolean =
    density > 0 &&
        dy < 0 &&
        abs(dy) > abs(dx) * .8f &&
        (dy < -80 * density || (dy < -24 * density && velocityY < -750 * density))
