package com.example.scannerkotlin.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryScanControllerTest {

    @Test
    fun `press starts the hardware module once and a second press does not`() {
        val engine = FakeEngine()
        val controller = attached(engine)

        assertEquals(InventoryScanPress.STARTED, controller.press())
        assertEquals(InventoryScanPress.ALREADY_ACTIVE, controller.press())
        assertEquals(InventoryScanPress.ALREADY_ACTIVE, controller.press())

        assertEquals(listOf("open", "start"), engine.calls)
        assertEquals(InventoryScanPhase.SCANNING, controller.phase)
        assertTrue(engine.deliveredBarcodes.isEmpty())
    }

    @Test
    fun `release stops a hold trigger and the next press can scan again`() {
        val engine = FakeEngine(hold = true)
        val controller = attached(engine)

        controller.press()
        assertTrue(controller.release())
        assertEquals(InventoryScanPhase.IDLE, controller.phase)

        assertEquals(InventoryScanPress.STARTED, controller.press())
        assertEquals(listOf("open", "start", "stop", "start"), engine.calls)
    }

    @Test
    fun `release keeps a timeout trigger running until decode`() {
        val engine = FakeEngine(hold = false)
        val controller = attached(engine)

        controller.press()
        assertFalse(controller.release())
        assertEquals(InventoryScanPhase.SCANNING, controller.phase)
        assertEquals(listOf("open", "start"), engine.calls)

        assertTrue(controller.onDecodeObserved())
        assertEquals(InventoryScanPhase.IDLE, controller.phase)
        assertEquals(InventoryScanPress.STARTED, controller.press())
    }

    @Test
    fun `watchdog and leaving the screen stop the module`() {
        val engine = FakeEngine()
        val controller = attached(engine)

        controller.press()
        assertTrue(controller.onWatchdog())
        assertEquals(InventoryScanPhase.IDLE, controller.phase)
        assertFalse(controller.onWatchdog())

        controller.press()
        controller.detach()
        assertEquals(InventoryScanPhase.IDLE, controller.phase)
        assertEquals(InventoryScanPress.CLOSED, controller.press())
        assertEquals(
            listOf("open", "start", "stop", "start", "stop", "close"),
            engine.calls
        )
    }

    @Test
    fun `unready or failed start does not pretend a barcode was read`() {
        val unready = FakeEngine(ready = false)
        val unreadyController = attached(unready)
        assertEquals(InventoryScanPress.NOT_READY, unreadyController.press())
        assertEquals(listOf("open"), unready.calls)
        assertTrue(unready.deliveredBarcodes.isEmpty())

        val failing = FakeEngine(startOk = false)
        val failingController = attached(failing)
        assertEquals(InventoryScanPress.START_FAILED, failingController.press())
        assertEquals(InventoryScanPhase.UNAVAILABLE, failingController.phase)
        assertFalse(failingController.onDecodeObserved())
        assertTrue(failing.deliveredBarcodes.isEmpty())
    }

    @Test
    fun `decode ends the session without a second start`() {
        val engine = FakeEngine()
        val controller = attached(engine)
        controller.press()
        assertTrue(controller.onDecodeObserved())
        assertEquals(listOf("open", "start", "stop"), engine.calls)
        assertEquals(InventoryScanPress.STARTED, controller.press())
    }

    @Test
    fun `trigger mode stops on release unless the service reports timeout`() {
        assertTrue(HardwareTriggerMode.stopsOnRelease(null))
        assertTrue(HardwareTriggerMode.stopsOnRelease(""))
        assertTrue(HardwareTriggerMode.stopsOnRelease("  "))
        assertTrue(HardwareTriggerMode.stopsOnRelease(HardwareTriggerMode.STOP_ON_RELEASE))
        assertTrue(HardwareTriggerMode.stopsOnRelease("sync"))
        assertTrue(HardwareTriggerMode.stopsOnRelease("SINGLE_SCAN"))
        assertFalse(HardwareTriggerMode.stopsOnRelease(HardwareTriggerMode.STOP_ON_TIMEOUT))
        assertFalse(HardwareTriggerMode.stopsOnRelease(" timeout "))
    }

    private fun attached(engine: FakeEngine): InventoryScanController {
        return InventoryScanController(engine).also { it.attach() }
    }

    private class FakeEngine(
        private val ready: Boolean = true,
        private val hold: Boolean = true,
        private val startOk: Boolean = true
    ) : HardwareScanTrigger {
        val calls = mutableListOf<String>()
        val deliveredBarcodes = mutableListOf<String>()

        override fun open() {
            calls += "open"
        }

        override fun close() {
            calls += "close"
        }

        override fun isReady(): Boolean = ready

        override fun stopsOnRelease(): Boolean = hold

        override fun start(): Boolean {
            calls += "start"
            return startOk
        }

        override fun stop() {
            calls += "stop"
        }
    }
}
