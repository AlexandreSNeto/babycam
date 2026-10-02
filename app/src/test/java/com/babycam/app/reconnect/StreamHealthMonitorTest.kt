package com.babycam.app.reconnect

import com.babycam.app.reconnect.StreamHealthMonitor.Health
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamHealthMonitorTest {

    private val monitor = StreamHealthMonitor().apply { reset(nowMs = 0, frames = 10, positionMs = 0) }

    @Test
    fun `no new frame for 4999ms is still OK (RES-02)`() {
        assertEquals(Health.OK, monitor.check(nowMs = 4_999, frames = 10, positionMs = 4_999))
    }

    @Test
    fun `no new frame for 5000ms is a STALL (RES-01)`() {
        assertEquals(Health.STALL, monitor.check(nowMs = 5_000, frames = 10, positionMs = 5_000))
    }

    @Test
    fun `frames arriving less than 5s apart never stall over 20s (RES-02)`() {
        var frames = 10L
        for (t in 4_900L..20_000L step 4_900L) {
            frames++
            assertEquals(Health.OK, monitor.check(nowMs = t, frames = frames, positionMs = t))
        }
    }

    @Test
    fun `stall is measured from the last frame change, not from reset`() {
        monitor.check(nowMs = 3_000, frames = 11, positionMs = 3_000)
        assertEquals(Health.OK, monitor.check(nowMs = 7_999, frames = 11, positionMs = 7_999))
        assertEquals(Health.STALL, monitor.check(nowMs = 8_000, frames = 11, positionMs = 8_000))
        assertEquals(3_000L, monitor.lastFrameAtMs())
    }

    @Test
    fun `a decreasing frame counter (renderer re-created) counts as a new frame`() {
        monitor.check(nowMs = 4_000, frames = 2, positionMs = 4_000)
        assertEquals(Health.OK, monitor.check(nowMs = 8_999, frames = 2, positionMs = 8_999))
    }

    /** Frames keep flowing but media position lags wall clock by [lag] ms. */
    private fun sample(t: Long, lag: Long) = monitor.check(nowMs = t, frames = 10 + t, positionMs = t - lag)

    @Test
    fun `drift above 3000ms for 4999ms is OK, for 5000ms is DRIFT (RES-09)`() {
        assertEquals(Health.OK, sample(1_000, lag = 3_001))
        assertEquals(Health.OK, sample(5_999, lag = 3_001))
        assertEquals(Health.DRIFT, sample(6_000, lag = 3_001))
    }

    @Test
    fun `drift of exactly 3000ms never triggers DRIFT`() {
        for (t in 1_000L..30_000L step 1_000L) assertEquals(Health.OK, sample(t, lag = 3_000))
    }

    @Test
    fun `drift falling back to 3000ms restarts the drift timer`() {
        sample(1_000, lag = 3_500)
        sample(4_000, lag = 3_000)
        assertEquals(Health.OK, sample(5_000, lag = 3_500))
        assertEquals(Health.OK, sample(9_999, lag = 3_500))
        assertEquals(Health.DRIFT, sample(10_000, lag = 3_500))
    }

    @Test
    fun `STALL takes precedence over DRIFT`() {
        monitor.check(nowMs = 1_000, frames = 10, positionMs = 0)
        assertEquals(Health.STALL, monitor.check(nowMs = 10_000, frames = 10, positionMs = 0))
    }

    @Test
    fun `reset clears drift and frame baselines (RES-15)`() {
        sample(1_000, lag = 4_000)
        monitor.reset(nowMs = 5_000, frames = 0, positionMs = 50_000)
        assertEquals(Health.OK, monitor.check(nowMs = 9_999, frames = 0, positionMs = 54_999))
        assertEquals(Health.OK, monitor.check(nowMs = 14_000, frames = 1, positionMs = 59_000))
    }
}
