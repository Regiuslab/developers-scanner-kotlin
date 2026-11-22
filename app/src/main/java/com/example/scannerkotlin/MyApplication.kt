package com.example.scannerkotlin

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import com.example.scannerkotlin.cache.CatalogReferenceCache
import com.example.scannerkotlin.persistence.ComingDraftRepository
import com.example.scannerkotlin.service.ScanService
import com.example.scannerkotlin.utils.CrashDiagnosticsHandler
import com.example.scannerkotlin.utils.SessionManager
import com.example.scannerkotlin.utils.TsdLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        TsdLogger.init(applicationContext)
        CrashDiagnosticsHandler.install(applicationContext)
        SessionManager.init(applicationContext)
        ComingDraftRepository.init(applicationContext)

        appScope.launch {
            val previousCrash = CrashDiagnosticsHandler.consumeLastCrashRecord()
            if (previousCrash != null) {
                TsdLogger.warn(
                    category = "RECOVERY",
                    event = "previous_crash_detected",
                    operationId = previousCrash.activeOperationId,
                    documentId = previousCrash.activeDocumentId,
                    fields = mapOf(
                        "crashAtMs" to previousCrash.timestampMs,
                        "exceptionType" to previousCrash.exceptionType,
                        "topFrame" to previousCrash.topStackFrame
                    )
                )
            }
            ComingDraftRepository.getInstance().runStartupRecovery()
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {
                if (startedActivityCount == 0 && !ScanService.isRunning) {
                    startScanServiceSafely()
                }
                startedActivityCount++
            }

            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        TsdLogger.info(category = "APP", event = "application_initialized")
    }

    private fun startScanServiceSafely() {
        try {
            val serviceIntent = Intent(this, ScanService::class.java)
            startService(serviceIntent)
        } catch (e: IllegalStateException) {
            // Android 8+ BackgroundServiceStartNotAllowedException when process starts in background;
            // BaseScanActivity has direct hardware broadcast fallback so scanning still works.
            TsdLogger.warn(
                category = "SCANNER",
                event = "scan_service_background_start_deferred",
                throwable = e
            )
        } catch (t: Throwable) {
            TsdLogger.error(
                category = "SCANNER",
                event = "scan_service_start_error",
                throwable = t
            )
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            // Trim ONLY disposable reference cache; SQLite operational drafts remain untouched.
            CatalogReferenceCache.trimForMemoryPressure()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        CatalogReferenceCache.clearAll()
    }
}
