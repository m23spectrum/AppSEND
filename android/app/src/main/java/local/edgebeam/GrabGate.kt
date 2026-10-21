package local.edgebeam

/** Consumes successful camera observations only; a stopped camera is never a missing hand. */
internal class GrabGate {
    enum class Phase {
        FIST,
        HELD,
        RELEASE,
        EXIT,
        SENT,
    }

    data class Hand(val gesture: String, val score: Float, val x: Float, val y: Float)

    var phase = Phase.FIST
        private set

    private var releaseAt = -1L
    private var fistAt = -1L
    private var armedAt = -1L
    private var edgeAt = -1L
    private var missingAt = -1L
    private var lastAt = -1L
    private var requireRelease = false

    fun reset(requireRelease: Boolean = true) {
        phase = Phase.FIST
        releaseAt = -1
        fistAt = -1
        armedAt = -1
        edgeAt = -1
        missingAt = -1
        lastAt = -1
        this.requireRelease = requireRelease
    }

    fun sample(now: Long, hand: Hand?): Boolean {
        if (phase == Phase.SENT) return false
        // A processing stall or lifecycle interruption invalidates the grip.
        if (lastAt >= 0 && (now <= lastAt || now - lastAt > 500)) reset()
        lastAt = now
        val palm = hand?.gesture == "Open_Palm" && hand.score >= .60f
        val fist = hand?.gesture == "Closed_Fist" && hand.score >= .60f
        when (phase) {
            Phase.FIST -> {
                if (requireRelease) {
                    if (palm) requireRelease = false
                    return false
                }
                if (fist) {
                    if (fistAt < 0) fistAt = now
                    if (now - fistAt >= 70) {
                        phase = Phase.HELD
                        armedAt = now
                    }
                } else fistAt = -1
            }
            Phase.HELD,
            Phase.RELEASE,
            Phase.EXIT -> {
                // A deliberate fist followed by an open palm sends without leaving the frame.
                if (now - armedAt > 6000) {
                    reset()
                    return false
                }
                if (hand != null) {
                    missingAt = -1
                    if (palm) {
                        if (releaseAt < 0) releaseAt = now
                        phase = Phase.RELEASE
                        if (now - releaseAt >= 45) {
                            phase = Phase.SENT
                            return true
                        }
                    } else {
                        releaseAt = -1
                        phase = Phase.HELD
                    }
                    // Coordinates are normalized to the full uncropped camera frame.
                    if (hand.x < .27f || hand.x > .73f || hand.y < .27f || hand.y > .73f)
                        edgeAt = now
                    else edgeAt = -1
                } else {
                    releaseAt = -1
                    if (missingAt < 0) {
                        missingAt = now
                    }
                    if (edgeAt >= 0 && missingAt - edgeAt <= 400) {
                        phase = Phase.EXIT
                        if (now - missingAt >= 100) {
                            phase = Phase.SENT
                            return true
                        }
                    } else if (now - missingAt >= 260) reset()
                }
            }
            Phase.SENT -> Unit
        }
        return false
    }
}
