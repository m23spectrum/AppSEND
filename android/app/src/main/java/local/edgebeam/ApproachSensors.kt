package local.edgebeam

import android.content.Context
import android.hardware.*
import kotlin.math.exp

/** Uses proximity and orientation only. Motion, speed and stopping are not classified. */
class ApproachSensors(
    context: Context,
    private val enabled: () -> Boolean,
    private val trigger: () -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximity = manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val gravity = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val acceleration = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val available
        get() = proximity != null && (gravity != null || acceleration != null)

    private val gate = ApproachGate()
    private val g = FloatArray(3)
    private var ready = false
    private var lastSample = 0L

    fun start() {
        proximity?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        (gravity ?: acceleration)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        manager.unregisterListener(this)
        reset()
    }

    private fun reset() {
        gate.reset()
        ready = false
        lastSample = 0L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (!enabled()) {
            reset()
            return
        }
        val time = e.timestamp / 1_000_000
        if (e.sensor.type == Sensor.TYPE_PROXIMITY)
            gate.proximity(time, e.values[0] < minOf(e.sensor.maximumRange, 5f))
        else {
            if (gravity != null || !ready) for (i in 0..2) g[i] = e.values[i]
            else {
                val dt = ((time - lastSample) / 1000f).coerceIn(.001f, .1f)
                val alpha = exp(-dt / .32f)
                for (i in 0..2) g[i] = alpha * g[i] + (1 - alpha) * e.values[i]
            }
            ready = true
            lastSample = time
        }
        if (available && ready && gate.sample(time, g[0], g[1], g[2])) trigger()
    }
}
