package com.example.scannerkotlin.utils

import java.util.Locale

/**
 * Trigger modes reported by the XCheng scan service (`IScanner.getScanTriggerMode`).
 *
 * `SYNC` keeps the imager on until the trigger is released. `TIMEOUT` keeps it on
 * until a successful read or the device timeout. Any other or unreadable value is
 * treated as hold-to-release so a software trigger cannot leave the module running.
 */
object HardwareTriggerMode {
    const val STOP_ON_RELEASE = "SYNC"
    const val STOP_ON_TIMEOUT = "TIMEOUT"

    fun stopsOnRelease(mode: String?): Boolean {
        val normalized = mode?.trim()?.uppercase(Locale.US)
        return normalized != STOP_ON_TIMEOUT
    }
}
