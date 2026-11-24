package com.example.scannerkotlin.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xcheng.scanner.ScannerResult
import com.xcheng.scanner.XcBarcodeScanner

/**
 * Starts and stops the XCheng scan service that is already installed on the TSD
 * (`com.xcheng.scannere3`, action `com.xcheng.scannere3.scanservice`).
 *
 * Decoded text is delivered by the service callback and, on the warehouse devices
 * already in use, by `com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST`.
 * This class only forwards that text. It does not change trigger mode, output
 * method, or hardware key settings.
 *
 * `XcBarcodeScanner.getScanTriggerMode()` in SDK 1.3.56 is a stub that returns
 * an empty string. The live mode is read from the bound `IScanner`.
 */
class XchengHardwareScanEngine(
    private val context: Context,
    private val onHardwareBarcode: (String) -> Unit
) : HardwareScanTrigger {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var opened = false

    override fun open() {
        if (opened) return
        try {
            XcBarcodeScanner.init(context, ScannerResult { result ->
                val raw = result?.takeIf { it.isNotBlank() } ?: return@ScannerResult
                mainHandler.post {
                    if (!opened) return@post
                    onHardwareBarcode(raw)
                }
            })
            opened = true
            TsdLogger.info(
                category = "SCANNER",
                event = "xcheng_bind_requested",
                fields = mapOf("package" to SERVICE_PACKAGE)
            )
        } catch (t: Throwable) {
            opened = false
            TsdLogger.error(
                category = "SCANNER",
                event = "xcheng_bind_failed",
                throwable = t
            )
        }
    }

    override fun close() {
        mainHandler.removeCallbacksAndMessages(null)
        if (!opened) return
        opened = false
        stop()
        releaseBinding()
    }

    /**
     * SDK 1.3.56 `deInit` nulls its connection and then calls `unbindService(null)`,
     * which throws and leaves the scan service bound. Unbind the real connection first.
     */
    private fun releaseBinding() {
        val connection = XcBarcodeScanner.f
        XcBarcodeScanner.a = null
        XcBarcodeScanner.b = null
        XcBarcodeScanner.c = null
        XcBarcodeScanner.d = null
        XcBarcodeScanner.e = null
        XcBarcodeScanner.f = null
        XcBarcodeScanner.g = false
        if (connection == null) {
            TsdLogger.info(category = "SCANNER", event = "xcheng_unbound", fields = mapOf("bound" to false))
            return
        }
        try {
            context.unbindService(connection)
            TsdLogger.info(category = "SCANNER", event = "xcheng_unbound", fields = mapOf("bound" to true))
        } catch (t: IllegalArgumentException) {
            TsdLogger.info(
                category = "SCANNER",
                event = "xcheng_unbind_not_registered",
                fields = mapOf("reason" to (t.message ?: "not_registered"))
            )
        } catch (t: Throwable) {
            TsdLogger.warn(
                category = "SCANNER",
                event = "xcheng_unbind_failed",
                throwable = t
            )
        }
    }

    override fun isReady(): Boolean = opened && XcBarcodeScanner.a != null

    override fun stopsOnRelease(): Boolean {
        return HardwareTriggerMode.stopsOnRelease(readTriggerMode())
    }

    override fun start(): Boolean {
        val binder = XcBarcodeScanner.a ?: return false
        return try {
            binder.startScan()
            val mode = readTriggerMode()
            TsdLogger.info(
                category = "SCANNER",
                event = "software_scan_started",
                fields = mapOf(
                    "triggerMode" to (mode?.trim()?.ifEmpty { null } ?: "unknown"),
                    "stopOnRelease" to HardwareTriggerMode.stopsOnRelease(mode)
                )
            )
            true
        } catch (t: Throwable) {
            TsdLogger.error(
                category = "SCANNER",
                event = "software_scan_start_failed",
                throwable = t
            )
            false
        }
    }

    override fun stop() {
        val binder = XcBarcodeScanner.a ?: return
        try {
            binder.stopScan()
        } catch (t: Throwable) {
            TsdLogger.warn(
                category = "SCANNER",
                event = "software_scan_stop_failed",
                throwable = t
            )
        }
    }

    private fun readTriggerMode(): String? {
        val binder = XcBarcodeScanner.a ?: return null
        return try {
            binder.scanTriggerMode
        } catch (t: Throwable) {
            TsdLogger.warn(
                category = "SCANNER",
                event = "trigger_mode_unreadable",
                throwable = t
            )
            null
        }
    }

    private companion object {
        const val SERVICE_PACKAGE = "com.xcheng.scannere3"
    }
}
