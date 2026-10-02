package com.babycam.app.player

import com.babycam.app.player.Media3RtspPlayerController.Companion.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
import com.babycam.app.player.Media3RtspPlayerController.Companion.BUFFER_FOR_PLAYBACK_MS
import com.babycam.app.player.Media3RtspPlayerController.Companion.FORCE_RTP_TCP
import com.babycam.app.player.Media3RtspPlayerController.Companion.MAX_BUFFER_MS
import com.babycam.app.player.Media3RtspPlayerController.Companion.MIN_BUFFER_MS
import com.babycam.app.player.Media3RtspPlayerController.Companion.RTSP_TIMEOUT_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3RtspPlayerControllerTest {

    @Test
    fun `low-latency buffer profile (RES-08)`() {
        assertTrue(BUFFER_FOR_PLAYBACK_MS <= 500)
        assertTrue(BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS <= 500)
        assertTrue(MAX_BUFFER_MS <= 2_000)
        // DefaultLoadControl rejects minBuffer < bufferForPlayback(AfterRebuffer).
        assertTrue(MIN_BUFFER_MS >= BUFFER_FOR_PLAYBACK_MS)
        assertTrue(MIN_BUFFER_MS >= BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
        assertTrue(MAX_BUFFER_MS >= MIN_BUFFER_MS)
    }

    @Test
    fun `RTSP forced over TCP with an 8s timeout (RES-10)`() {
        assertEquals(true, FORCE_RTP_TCP)
        assertEquals(8_000L, RTSP_TIMEOUT_MS)
    }
}
