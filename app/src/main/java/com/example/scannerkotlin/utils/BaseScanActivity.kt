package com.example.scannerkotlin.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Базовый класс для активити, которые используют сканирование штрихкодов
 * Решает проблемы с множественной регистрацией BroadcastReceiver
 */
abstract class BaseScanActivity : AppCompatActivity() {
    
    private var scanDataReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false
    
    protected abstract fun onBarcodeScanned(barcode: String)
    
    protected open fun getLogTag(): String = this::class.java.simpleName
    
    override fun onResume() {
        super.onResume()
        registerScanReceiver()
    }
    
    override fun onPause() {
        super.onPause()
        unregisterScanReceiver()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterScanReceiver()
        scanDataReceiver = null
    }
    
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScanReceiver() {
        if (isReceiverRegistered) {
            Log.w(getLogTag(), "Scan receiver already registered, skipping")
            return
        }
        
        if (scanDataReceiver == null) {
            scanDataReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == "Scan_data_received") {
                        val barcodeData = intent.getStringExtra("scanData")
                        if (!barcodeData.isNullOrBlank()) {
                            Log.d(getLogTag(), "Scanned barcode: $barcodeData")
                            onBarcodeScanned(barcodeData)
                        } else {
                            Log.w(getLogTag(), "Received null or blank scanData")
                        }
                    }
                }
            }
        }
        
        scanDataReceiver?.let { receiver ->
            try {
                val filter = IntentFilter("Scan_data_received")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }
                isReceiverRegistered = true
                Log.d(getLogTag(), "Scan data receiver registered successfully")
            } catch (e: Exception) {
                Log.e(getLogTag(), "Failed to register scan receiver", e)
            }
        }
    }
    
    private fun unregisterScanReceiver() {
        if (!isReceiverRegistered) {
            return
        }
        
        scanDataReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
                isReceiverRegistered = false
                Log.d(getLogTag(), "Scan data receiver unregistered successfully")
            } catch (e: IllegalArgumentException) {
                Log.w(getLogTag(), "Receiver not registered or already unregistered", e)
                isReceiverRegistered = false
            } catch (e: Exception) {
                Log.e(getLogTag(), "Error unregistering scan receiver", e)
                isReceiverRegistered = false
            }
        }
    }
} 