package com.babycam.app.reconnect

/**
 * Decides from periodic player samples whether a live stream is healthy, frozen (no new video
 * frame for [STALL_THRESHOLD_MILLIS], RES-01) or lagging behind real time (estimated drift above
 * [DRIFT_THRESHOLD_MILLIS] for [DRIFT_GRACE_MILLIS], RES-09). Pure logic: the caller supplies time.
 *
 * Drift = wall-clock elapsed since [reset] minus media position advanced since [reset]. It grows
 * exactly when playback pauses (rebuffer) and later resumes at 1x, which is how latency piles up.
 */
class StreamHealthMonitor {

    enum class Health { OK, STALL, DRIFT }

    private var lastFrames = 0L
    private var lastFrameAt = 0L
    private var startAt = 0L
    private var startPosition = 0L
    private var driftAboveSince: Long? = null

    fun reset(nowMs: Long, frames: Long, positionMs: Long) {
        lastFrames = frames
        lastFrameAt = nowMs
        startAt = nowMs
        startPosition = positionMs
        driftAboveSince = null
    }

    fun check(nowMs: Long, frames: Long, positionMs: Long): Health {
        // `!=` rather than `>`: the decoder counter restarts when the renderer is re-created.
        if (frames != lastFrames) {
            lastFrames = frames
            lastFrameAt = nowMs
        }
        if (nowMs - lastFrameAt >= STALL_THRESHOLD_MILLIS) return Health.STALL

        val drift = (nowMs - startAt) - (positionMs - startPosition)
        if (drift <= DRIFT_THRESHOLD_MILLIS) {
            driftAboveSince = null
            return Health.OK
        }
        val since = driftAboveSince ?: nowMs.also { driftAboveSince = it }
        return if (nowMs - since >= DRIFT_GRACE_MILLIS) Health.DRIFT else Health.OK
    }

    fun lastFrameAtMs(): Long = lastFrameAt

    companion object {
        const val STALL_THRESHOLD_MILLIS = 5_000L
        const val DRIFT_THRESHOLD_MILLIS = 3_000L
        const val DRIFT_GRACE_MILLIS = 5_000L
    }
}
