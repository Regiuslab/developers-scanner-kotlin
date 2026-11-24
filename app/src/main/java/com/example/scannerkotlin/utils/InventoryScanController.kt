package com.example.scannerkotlin.utils

/**
 * Guards the inventory software trigger.
 *
 * The controller never produces a barcode. A press only asks [HardwareScanTrigger]
 * to start the hardware module; a release, a real decode, a watchdog, or leaving
 * the screen asks it to stop. A second press while a session is active is ignored.
 */
class InventoryScanController(
    private val engine: HardwareScanTrigger
) {
    var phase: InventoryScanPhase = InventoryScanPhase.IDLE
        private set

    private var open: Boolean = false

    fun attach() {
        open = true
        engine.open()
    }

    fun detach() {
        if (phase == InventoryScanPhase.SCANNING) {
            engine.stop()
        }
        phase = InventoryScanPhase.IDLE
        open = false
        engine.close()
    }

    fun press(): InventoryScanPress {
        if (!open) return InventoryScanPress.CLOSED
        if (phase == InventoryScanPhase.SCANNING) return InventoryScanPress.ALREADY_ACTIVE
        if (!engine.isReady()) {
            phase = InventoryScanPhase.UNAVAILABLE
            return InventoryScanPress.NOT_READY
        }
        val started = try {
            engine.start()
        } catch (_: Throwable) {
            false
        }
        if (!started) {
            phase = InventoryScanPhase.UNAVAILABLE
            return InventoryScanPress.START_FAILED
        }
        phase = InventoryScanPhase.SCANNING
        return InventoryScanPress.STARTED
    }

    /**
     * @return true when a hold-to-release device was actually stopped.
     * Timeout mode keeps scanning after the finger lifts; decode or the watchdog ends it.
     */
    fun release(): Boolean {
        if (phase != InventoryScanPhase.SCANNING) return false
        if (!engine.stopsOnRelease()) return false
        engine.stop()
        phase = InventoryScanPhase.IDLE
        return true
    }

    fun onDecodeObserved(): Boolean = endSession()

    fun onWatchdog(): Boolean = endSession()

    private fun endSession(): Boolean {
        if (phase != InventoryScanPhase.SCANNING) return false
        engine.stop()
        phase = InventoryScanPhase.IDLE
        return true
    }
}

enum class InventoryScanPhase {
    IDLE,
    SCANNING,
    UNAVAILABLE
}

enum class InventoryScanPress {
    STARTED,
    ALREADY_ACTIVE,
    NOT_READY,
    START_FAILED,
    CLOSED
}

/**
 * Hardware decode trigger. Implementations must not invent barcode text.
 */
interface HardwareScanTrigger {
    fun open()
    fun close()
    fun isReady(): Boolean
    fun stopsOnRelease(): Boolean
    fun start(): Boolean
    fun stop()
}
