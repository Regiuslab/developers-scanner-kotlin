package com.example.scannerkotlin.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder

class ScanService : android.app.Service() {

    private var scanReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                if (action == "com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST") {
                    val scanData = intent.getStringExtra("EXTRA_BARCODE_DECODING_DATA")
                    val symbology = intent.getStringExtra("EXTRA_BARCODE_DECODING_SYMBOLE")

                    // Отправляем данные локально внутри приложения
                    val localIntent = Intent("Scan_data_received")
                    localIntent.putExtra("scanData", scanData)
                    localIntent.putExtra("symbology", symbology)
                    // Для локальных нужд лучше ограничить область видимости интента пакетом
                    localIntent.setPackage(packageName)

                    sendBroadcast(localIntent)
                }
            }
        }

        val filter = IntentFilter("com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST")

        // Исправление падения под Android 14+ (API 34+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(scanReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scanReceiver?.let {
            unregisterReceiver(it)
        }
        scanReceiver = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
