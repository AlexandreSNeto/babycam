package com.babycam.app.reconnect

/**
 * Exponential backoff with a cap: retries start fast and never flood the target,
 * but also never stop (no terminal "give up" state) — the caller keeps calling
 * [nextDelayMillis] with an ever-increasing attempt count.
 */
class BackoffPolicy {

    fun nextDelayMillis(attempt: Int): Long {
        // Exponent capped at 30 (2^30 * 1s already dwarfs MAX_DELAY_MILLIS) so huge attempt
        // values can't overflow the shift.
        val exponent = (attempt - 1).coerceIn(0, 30)
        val delay = INITIAL_DELAY_MILLIS * (1L shl exponent)
        return delay.coerceAtMost(MAX_DELAY_MILLIS)
    }

    companion object {
        const val INITIAL_DELAY_MILLIS: Long = 1000
        const val MAX_DELAY_MILLIS: Long = 30_000
    }
}
