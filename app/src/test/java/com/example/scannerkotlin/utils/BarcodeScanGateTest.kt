package com.example.scannerkotlin.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeScanGateTest {

    @Test
    fun `allows first scan and blocks duplicate while processing`() {
        val gate = BarcodeScanGate(duplicateWindowMs = 500L)
        assertTrue(gate.tryAcquire("123", nowMs = 1_000))
        assertFalse(gate.tryAcquire("123", nowMs = 1_100))
    }

    @Test
    fun `allows same barcode after release and timeout`() {
        val gate = BarcodeScanGate(duplicateWindowMs = 500L)
        assertTrue(gate.tryAcquire("123", nowMs = 1_000))
        gate.release()
        assertFalse(gate.tryAcquire("123", nowMs = 1_200))
        gate.release()
        assertTrue(gate.tryAcquire("123", nowMs = 1_600))
    }
}
