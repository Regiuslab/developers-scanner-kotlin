package com.example.scannerkotlin.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightGuardTest {

    @Test
    fun `second acquire is rejected until release`() {
        val guard = InFlightGuard(minDurationMs = 800L) { 0L }

        assertTrue(guard.tryAcquire())
        assertFalse(guard.tryAcquire())

        guard.release()
        assertTrue(guard.tryAcquire())
    }

    @Test
    fun `remaining lock covers fast completion inside min duration`() {
        var now = 1_000L
        val guard = InFlightGuard(minDurationMs = 800L) { now }

        assertTrue(guard.tryAcquire())
        now = 1_120L
        assertEquals(680L, guard.remainingLockMs())
    }
}
