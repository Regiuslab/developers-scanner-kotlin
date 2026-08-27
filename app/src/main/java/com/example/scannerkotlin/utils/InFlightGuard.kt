package com.example.scannerkotlin.utils

/**
 * Allows only one in-flight action and keeps the lock at least [minDurationMs]
 * so a fast server response cannot let a double-tap through.
 */
class InFlightGuard(
    private val minDurationMs: Long = 800L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    private var inFlight = false
    private var startedAt = 0L

    @Synchronized
    fun tryAcquire(): Boolean {
        if (inFlight) return false
        inFlight = true
        startedAt = clock()
        return true
    }

    @Synchronized
    fun remainingLockMs(): Long {
        if (!inFlight) return 0L
        val elapsed = clock() - startedAt
        return (minDurationMs - elapsed).coerceAtLeast(0L)
    }

    @Synchronized
    fun release() {
        inFlight = false
    }
}
