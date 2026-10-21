package local.edgebeam

import org.junit.Assert.*
import org.junit.Test

class ApproachGateTest {
    private fun run(gate: ApproachGate, y: Float, z: Float) =
        (1000L..1800L step 20).count { gate.sample(it, 0f, y, z) }

    @Test
    fun invertedPhoneTriggersWithoutAnyLiftOrMovement() {
        val gate = ApproachGate()
        gate.proximity(1000, true)
        assertEquals(1, run(gate, -9.81f, 0f))
    }

    @Test
    fun screenFacingDownAlsoWorksAtTheBezel() {
        val gate = ApproachGate()
        gate.proximity(1000, true)
        assertEquals(1, run(gate, 0f, -9.81f))
    }

    @Test
    fun uprightAndScreenFacingUpDoNotTrigger() {
        for ((y, z) in listOf(9.81f to 0f, 0f to 9.81f)) {
            val gate = ApproachGate()
            gate.proximity(1000, true)
            assertEquals(0, run(gate, y, z))
        }
    }

    @Test
    fun proximityIsRequired() {
        assertEquals(0, run(ApproachGate(), -9.81f, 0f))
    }

    @Test
    fun changingAccelerationMagnitudeDoesNotBlockOrientation() {
        val gate = ApproachGate()
        gate.proximity(1000, true)
        val count =
            (1000L..1800L step 20).count { time ->
                gate.sample(time, 0f, if (time % 40 == 0L) -16f else -5f, 0f)
            }
        assertEquals(1, count)
    }

    @Test
    fun oneContactTriggersOnceAndHonorsCooldown() {
        val gate = ApproachGate()
        gate.proximity(1000, true)
        assertEquals(1, run(gate, -9.81f, 0f))
        assertFalse(gate.sample(6000, 0f, -9.81f, 0f))
        gate.proximity(6100, false)
        gate.proximity(6200, true)
        assertTrue(gate.sample(6500, 0f, -9.81f, 0f))
        gate.reset()
        assertFalse(gate.sample(11000, 0f, -9.81f, 0f))
    }

    @Test
    fun onlyAnUpwardFileSwipeOrFlickLaunchesTheCard() {
        assertTrue(isFileThrow(8f, -100f, -20f, 1f))
        assertTrue(isFileThrow(5f, -30f, -900f, 1f))
        assertTrue(isFileThrow(10f, -60f, -1800f, 2f))
    }

    @Test
    fun scrollingDownOrSidewaysAndShortTouchesDoNotSend() {
        assertFalse(isFileThrow(0f, 120f, 950f, 1f))
        assertFalse(isFileThrow(150f, -90f, -900f, 1f))
        assertFalse(isFileThrow(0f, -20f, -900f, 1f))
        assertFalse(isFileThrow(0f, -40f, -900f, 2f))
    }
}
