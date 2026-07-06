package com.example.scannerkotlin.utils

class BarcodeScanGate(
    private val duplicateWindowMs: Long = 500L
) {
    private var isProcessing = false
    private var lastBarcode: String? = null
    private var lastScanTimeMs: Long = 0L

    @Synchronized
    fun tryAcquire(barcode: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (isProcessing) return false
        if (barcode == lastBarcode && nowMs - lastScanTimeMs < duplicateWindowMs) return false
        isProcessing = true
        lastBarcode = barcode
        lastScanTimeMs = nowMs
        return true
    }

    @Synchronized
    fun release() {
        isProcessing = false
    }
}
