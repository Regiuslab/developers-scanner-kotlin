package com.example.scannerkotlin.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import com.example.scannerkotlin.utils.BarcodeSanitizer
import com.example.scannerkotlin.utils.TsdLogger
import java.util.UUID

class ScanService : android.app.Service() {

    private var scanReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    companion object {
        const val ACTION_HARDWARE_SCAN = "com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST"
        const val ACTION_LOCAL_SCAN = "Scan_data_received"
        const val EXTRA_HARDWARE_DATA = "EXTRA_BARCODE_DECODING_DATA"
        const val EXTRA_HARDWARE_SYMBOLOGY = "EXTRA_BARCODE_DECODING_SYMBOLE"
        const val EXTRA_LOCAL_DATA = "scanData"
        const val EXTRA_LOCAL_SYMBOLOGY = "symbology"
        const val EXTRA_SCAN_EVENT_ID = "scanEventId"
        const val EXTRA_SCAN_TIMESTAMP_MS = "scanTimestampMs"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_HARDWARE_SCAN) return
                val rawScanData = intent.getStringExtra(EXTRA_HARDWARE_DATA)
                val sanitized = BarcodeSanitizer.sanitize(rawScanData)
                if (sanitized.isNullOrEmpty()) {
                    TsdLogger.warn(category = "SCANNER", event = "ignored_blank_hardware_scan")
                    return
                }
                val symbology = intent.getStringExtra(EXTRA_HARDWARE_SYMBOLOGY)
                val eventId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()

                val localIntent = Intent(ACTION_LOCAL_SCAN).apply {
                    putExtra(EXTRA_LOCAL_DATA, sanitized)
                    putExtra(EXTRA_LOCAL_SYMBOLOGY, symbology)
                    putExtra(EXTRA_SCAN_EVENT_ID, eventId)
                    putExtra(EXTRA_SCAN_TIMESTAMP_MS, now)
                    setPackage(packageName)
                }
                sendBroadcast(localIntent)
            }
        }

        val filter = IntentFilter(ACTION_HARDWARE_SCAN)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(scanReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (t: Throwable) {
            TsdLogger.error(category = "SCANNER", event = "service_receiver_register_failed", throwable = t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        if (isReceiverRegistered) {
            scanReceiver?.let {
                try {
                    unregisterReceiver(it)
                } catch (_: IllegalArgumentException) {
                }
            }
            isReceiverRegistered = false
        }
        scanReceiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
