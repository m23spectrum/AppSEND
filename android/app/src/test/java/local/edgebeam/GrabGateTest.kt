package local.edgebeam

import org.junit.Assert.*
import org.junit.Test

class GrabGateTest {
    private val palm = GrabGate.Hand("Open_Palm", .95f, .5f, .5f)
    private val fist = GrabGate.Hand("Closed_Fist", .95f, .5f, .5f)

    private fun capture(g: GrabGate, start: Long = 1000) {
        for (t in start..start + 200 step 50) assertFalse(g.sample(t, palm))
        for (t in start + 250..start + 550 step 50) assertFalse(g.sample(t, fist))
        assertEquals(GrabGate.Phase.HELD, g.phase)
    }

    @Test
    fun stableGripThenEdgeExitSendsExactlyOnce() {
        for ((x, y) in listOf(.1f to .5f, .9f to .5f, .5f to .1f, .5f to .9f)) {
            val g = GrabGate()
            capture(g)
            assertFalse(g.sample(1600, fist.copy(x = x, y = y)))
            for (t in 1650L..1700L step 50) assertFalse(g.sample(t, null))
            assertTrue(g.sample(1750, null))
            assertFalse(g.sample(2000, null))
        }
    }

    @Test
    fun noHandAndUnprimedFistNeverSend() {
        val g = GrabGate()
        for (t in 1000L..2000L step 50) assertFalse(g.sample(t, fist))
        for (t in 2050L..2600L step 50) assertFalse(g.sample(t, null))
        assertEquals(GrabGate.Phase.FIST, g.phase)
    }

    @Test
    fun BriefFistDoesNotCapture() {
        val g = GrabGate()
        for (t in 1000L..1200L step 50) g.sample(t, palm)
        g.sample(1250, fist)
        g.sample(1350, palm)
        assertEquals(GrabGate.Phase.FIST, g.phase)
        for (t in 1400L..1900L step 50) assertFalse(g.sample(t, null))
    }

    @Test
    fun DisappearanceInCenterCancels() {
        val g = GrabGate()
        capture(g)
        for (t in 1600L..2100L step 50) assertFalse(g.sample(t, null))
        assertEquals(GrabGate.Phase.FIST, g.phase)
    }

    @Test
    fun BriefTrackingLossAtEdgeDoesNotSend() {
        val g = GrabGate()
        capture(g)
        g.sample(1600, fist.copy(x = .9f))
        g.sample(1650, null)
        g.sample(1700, null)
        assertFalse(g.sample(1725, fist))
        for (t in 1850L..2200L step 50) assertFalse(g.sample(t, null))
    }

    @Test
    fun OpeningHandSendsImmediatelyInsideFrame() {
        val g = GrabGate()
        capture(g)
        g.sample(1600, palm)
        assertEquals(GrabGate.Phase.RELEASE, g.phase)
        assertTrue(g.sample(1645, palm))
        assertFalse(g.sample(1700, palm))
    }

    @Test
    fun CameraStallAndPauseCancelGrip() {
        val g = GrabGate()
        capture(g)
        g.sample(1600, fist.copy(x = .9f))
        assertFalse(g.sample(2600, null))
        capture(g, 3000)
        g.reset()
        for (t in 3600L..4100L step 50) assertFalse(g.sample(t, null))
    }

    @Test
    fun GripExpiresWithoutExit() {
        val g = GrabGate()
        capture(g)
        for (t in 1600L..7800L step 100) assertFalse(g.sample(t, fist))
        assertEquals(GrabGate.Phase.FIST, g.phase)
    }

    @Test
    fun LowConfidenceDoesNotCapture() {
        val g = GrabGate()
        for (t in 1000L..1500L step 50) g.sample(t, palm)
        for (t in 1550L..2100L step 50) assertFalse(g.sample(t, fist.copy(score = .4f)))
        assertEquals(GrabGate.Phase.FIST, g.phase)
    }

    @Test
    fun ReentryResetsExitDebounce() {
        val g = GrabGate()
        capture(g)
        g.sample(1600, fist.copy(x = .9f))
        g.sample(1650, null)
        g.sample(1700, null)
        g.sample(1725, palm.copy(x = .9f))
        g.sample(1750, null)
        assertFalse(g.sample(1800, null))
        assertTrue(g.sample(1850, null))
    }

    @Test
    fun DirectFistThenPalmNeedsNoInitialOpenHand() {
        val g = GrabGate()
        g.sample(1000, fist)
        g.sample(1035, fist)
        assertEquals(GrabGate.Phase.FIST, g.phase)
        g.sample(1070, fist)
        assertEquals(GrabGate.Phase.HELD, g.phase)
        assertFalse(g.sample(1100, palm))
        assertTrue(g.sample(1145, palm))
    }

    @Test
    fun OpenHandStillCannotSendByDisappearingInCenter() {
        val g = GrabGate()
        capture(g)
        g.sample(1600, palm)
        for (t in 1650L..2100L step 50) assertFalse(g.sample(t, null))
    }

    @Test
    fun ExplicitResetCancelsCapturedOpenHand() {
        val g = GrabGate()
        capture(g)
        g.sample(1600, palm)
        g.reset()
        g.sample(1650, palm.copy(x = .8f))
        for (t in 1700L..2000L step 50) assertFalse(g.sample(t, null))
    }

    @Test
    fun OpenHandWithoutFistNeverSends() {
        val g = GrabGate()
        for (t in 1000L..2000L step 30) assertFalse(g.sample(t, palm))
    }

    @Test
    fun ABriefOpenClassificationDoesNotSend() {
        val g = GrabGate()
        capture(g)
        assertFalse(g.sample(1600, palm))
        assertFalse(g.sample(1630, fist))
        assertFalse(g.sample(1660, palm))
        assertFalse(g.sample(1690, palm))
        assertTrue(g.sample(1705, palm))
    }

    @Test
    fun MissingFrameRestartsOpenDebounce() {
        val g = GrabGate()
        capture(g)
        g.sample(1600, palm)
        g.sample(1630, null)
        assertFalse(g.sample(1660, palm))
        assertFalse(g.sample(1690, palm))
        assertTrue(g.sample(1705, palm))
    }

    @Test
    fun CancelDoesNotRecaptureAStillClosedFist() {
        val g = GrabGate()
        capture(g)
        g.reset()
        for (t in 1600L..1900L step 30) assertFalse(g.sample(t, fist))
        for (t in 1930L..2050L step 30) assertFalse(g.sample(t, palm))
        g.sample(2100, fist)
        g.sample(2170, fist)
        g.sample(2200, palm)
        assertTrue(g.sample(2245, palm))
    }

    @Test
    fun EmptyCameraThenFistWorksWithoutAnInitialPalm() {
        val g = GrabGate()
        for (t in 1000L..1500L step 30) assertFalse(g.sample(t, null))
        g.sample(1530, fist)
        g.sample(1600, fist)
        assertEquals(GrabGate.Phase.HELD, g.phase)
        assertFalse(g.sample(1630, palm))
        assertTrue(g.sample(1675, palm))
    }
}
