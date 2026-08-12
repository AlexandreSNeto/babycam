package com.babycam.app.reconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffPolicyTest {

    private val policy = BackoffPolicy()

    @Test
    fun `nextDelayMillis for attempt 1 returns the initial fast interval`() {
        assertEquals(1000L, policy.nextDelayMillis(1))
    }

    @Test
    fun `delay strictly increases across consecutive attempts up to the cap`() {
        val delays = (1..6).map { policy.nextDelayMillis(it) }

        for (i in 0 until delays.size - 1) {
            assertTrue(
                "expected delays[$i]=${delays[i]} < delays[${i + 1}]=${delays[i + 1]}",
                delays[i] < delays[i + 1] || delays[i + 1] == BackoffPolicy.MAX_DELAY_MILLIS,
            )
        }
    }

    @Test
    fun `delay never exceeds the cap even for very large attempt values`() {
        assertEquals(BackoffPolicy.MAX_DELAY_MILLIS, policy.nextDelayMillis(1000))
    }

    @Test
    fun `delay reaches and stays at the cap once growth surpasses it`() {
        assertEquals(BackoffPolicy.MAX_DELAY_MILLIS, policy.nextDelayMillis(6))
        assertEquals(BackoffPolicy.MAX_DELAY_MILLIS, policy.nextDelayMillis(7))
    }
}
