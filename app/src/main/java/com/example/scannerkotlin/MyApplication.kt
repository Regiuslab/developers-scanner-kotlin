package com.example.scannerkotlin

import android.app.Application
import android.content.Intent
import android.util.Log
import com.example.scannerkotlin.service.ScanService
import com.example.scannerkotlin.utils.SessionManager

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        SessionManager.init(applicationContext)
        Log.i("MyApplication", "SessionManager initialized.")
        val serviceIntent = Intent(this, ScanService::class.java)


        startService(serviceIntent)
    }
}