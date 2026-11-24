package com.example.scannerkotlin.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.scannerkotlin.service.ScanService
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Base Activity for barcode-scanning TSD screens.
 *
 * Improvements over original implementation:
 * - Prevents duplicate BroadcastReceiver registration and leaks across lifecycle transitions.
 * - Falls back directly to hardware broadcast (`BARCODE_DECODING_BROADCAST`) if `ScanService`
 *   was stopped by the OS, while deduplicating burst deliveries via [HardwareScanBurstDeduplicator].
 * - Sanitizes CR/LF/control suffixes from hardware scanner output.
 * - Serializes barcode dispatch through a bounded FIFO channel on [lifecycleScope] so rapid
 *   scans execute strictly in order without out-of-order UI mutations.
 */
abstract class BaseScanActivity : AppCompatActivity() {

    private var localScanReceiver: BroadcastReceiver? = null
    private var hardwareFallbackReceiver: BroadcastReceiver? = null
    private var isLocalRegistered = false
    private var isHardwareFallbackRegistered = false

    private val burstDeduplicator = HardwareScanBurstDeduplicator(hardwareBurstWindowMs = 150L)
    private val scanChannel = Channel<String>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var scanConsumerJob: Job? = null

    protected abstract fun onBarcodeScanned(barcode: String)

    /**
     * Optional suspend hook for screens that need sequential async persistence per scan
     * before processing the next queued barcode. Defaults to calling [onBarcodeScanned].
     */
    protected open suspend fun onBarcodeScannedSequential(barcode: String) {
        onBarcodeScanned(barcode)
    }

    protected open fun getLogTag(): String = this::class.java.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startSequentialScanConsumer()
    }

    override fun onResume() {
        super.onResume()
        if (scanConsumerJob?.isActive != true) {
            startSequentialScanConsumer()
        }
        registerScanReceivers()
    }

    override fun onPause() {
        unregisterScanReceivers()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterScanReceivers()
        scanConsumerJob?.cancel()
        scanConsumerJob = null
        localScanReceiver = null
        hardwareFallbackReceiver = null
        super.onDestroy()
    }

    private fun startSequentialScanConsumer() {
        scanConsumerJob?.cancel()
        scanConsumerJob = lifecycleScope.launch {
            for (barcode in scanChannel) {
                if (isFinishing || isDestroyed) break
                try {
                    onBarcodeScannedSequential(barcode)
                } catch (t: Throwable) {
                    TsdLogger.error(
                        category = "SCANNER",
                        event = "scan_handler_exception",
                        fields = mapOf("screen" to getLogTag(), "barcode" to barcode),
                        throwable = t
                    )
                }
            }
        }
    }

    protected fun enqueueBarcodeForProcessing(
        rawBarcode: String?,
        eventId: String? = null,
        bypassBurstDedup: Boolean = false
    ): Boolean {
        val sanitized = BarcodeSanitizer.sanitize(rawBarcode)
        if (sanitized.isNullOrEmpty()) {
            TsdLogger.warn(
                category = "SCANNER",
                event = "empty_barcode_ignored",
                fields = mapOf("screen" to getLogTag())
            )
            return false
        }
        if (!bypassBurstDedup && !burstDeduplicator.shouldAccept(sanitized, eventId)) {
            TsdLogger.info(
                category = "SCANNER",
                event = "duplicate_burst_suppressed",
                fields = mapOf("screen" to getLogTag(), "barcode" to sanitized)
            )
            return false
        }
        val sent = scanChannel.trySend(sanitized).isSuccess
        TsdLogger.info(
            category = "SCANNER",
            event = "scan_enqueued",
            fields = mapOf(
                "screen" to getLogTag(),
                "barcode" to sanitized,
                "accepted" to sent
            )
        )
        if (sent) {
            onBarcodeEnqueued(sanitized)
        }
        return sent
    }

    /**
     * Called after a sanitized barcode is accepted into the sequential queue.
     * Screens can use it for session UI. Business handling stays in [onBarcodeScannedSequential].
     */
    protected open fun onBarcodeEnqueued(barcode: String) = Unit

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScanReceivers() {
        if (!isLocalRegistered) {
            if (localScanReceiver == null) {
                localScanReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == ScanService.ACTION_LOCAL_SCAN) {
                            val barcodeData = intent.getStringExtra(ScanService.EXTRA_LOCAL_DATA)
                            val eventId = intent.getStringExtra(ScanService.EXTRA_SCAN_EVENT_ID)
                            enqueueBarcodeForProcessing(barcodeData, eventId)
                        }
                    }
                }
            }
            localScanReceiver?.let { receiver ->
                try {
                    val filter = IntentFilter(ScanService.ACTION_LOCAL_SCAN)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
                    } else {
                        registerReceiver(receiver, filter)
                    }
                    isLocalRegistered = true
                } catch (e: Exception) {
                    TsdLogger.error(
                        category = "SCANNER",
                        event = "local_receiver_register_failed",
                        fields = mapOf("screen" to getLogTag()),
                        throwable = e
                    )
                }
            }
        }

        if (!isHardwareFallbackRegistered) {
            if (hardwareFallbackReceiver == null) {
                hardwareFallbackReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        // Only handle directly when ScanService relay is not running;
                        // burstDeduplicator also protects against simultaneous delivery.
                        if (ScanService.isRunning) return
                        if (intent?.action == ScanService.ACTION_HARDWARE_SCAN) {
                            val rawData = intent.getStringExtra(ScanService.EXTRA_HARDWARE_DATA)
                            enqueueBarcodeForProcessing(rawData, eventId = null)
                        }
                    }
                }
            }
            hardwareFallbackReceiver?.let { receiver ->
                try {
                    val filter = IntentFilter(ScanService.ACTION_HARDWARE_SCAN)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(receiver, filter, RECEIVER_EXPORTED)
                    } else {
                        registerReceiver(receiver, filter)
                    }
                    isHardwareFallbackRegistered = true
                } catch (e: Exception) {
                    TsdLogger.error(
                        category = "SCANNER",
                        event = "hardware_fallback_register_failed",
                        fields = mapOf("screen" to getLogTag()),
                        throwable = e
                    )
                }
            }
        }
    }

    private fun unregisterScanReceivers() {
        if (isLocalRegistered) {
            localScanReceiver?.let { receiver ->
                try {
                    unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                } catch (e: Exception) {
                    TsdLogger.warn(category = "SCANNER", event = "local_receiver_unregister_err", throwable = e)
                }
            }
            isLocalRegistered = false
        }
        if (isHardwareFallbackRegistered) {
            hardwareFallbackReceiver?.let { receiver ->
                try {
                    unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                } catch (e: Exception) {
                    TsdLogger.warn(category = "SCANNER", event = "hw_receiver_unregister_err", throwable = e)
                }
            }
            isHardwareFallbackRegistered = false
        }
    }
}
