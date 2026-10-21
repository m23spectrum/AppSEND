package local.edgebeam

import org.junit.Assert.*
import org.junit.Test

class MotionTest {
    @Test
    fun departurePrecedesArrival() {
        for (plane in listOf(false, true)) {
            for (ms in listOf(-200f, 0f, 150f, 349f, 350f)) assertEquals(
                0f,
                motionFrame(ms, true, plane).alpha,
                0f,
            )
            assertEquals(0f, motionFrame(350f, false, plane).alpha, 0f)
            assertTrue(motionFrame(450f, true, plane).alpha > 0)
            assertEquals(1f, motionFrame(350f, true, plane).glow, 0f)
            assertEquals(0f, motionFrame(1600f, true, plane).alpha, 0f)
        }
    }

    @Test
    fun arrivalSettlesAfterOvershoot() {
        assertTrue(motionFrame(850f, true, true).progress > 1)
        assertEquals(1f, motionFrame(1000f, true, true).progress, 0f)
        for (ms in -100..1700 step 10) for (incoming in listOf(false, true)) for (plane in
            listOf(false, true)) {
            val f = motionFrame(ms.toFloat(), incoming, plane)
            assertTrue(f.progress.isFinite())
            assertTrue(f.alpha in 0f..1f)
        }
    }
}
