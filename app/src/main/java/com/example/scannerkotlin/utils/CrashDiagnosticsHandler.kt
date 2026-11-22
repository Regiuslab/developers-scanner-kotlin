package com.example.scannerkotlin.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Captures uncaught exceptions synchronously to persistent diagnostic storage
 * before delegating to the system handler so crashes are observable on restart.
 */
object CrashDiagnosticsHandler {

    private const val LAST_CRASH_FILE = "last_crash_marker.txt"

    @Volatile
    private var installed = false

    @Volatile
    private var markerDir: File? = null

    data class CrashRecord(
        val timestampMs: Long,
        val threadName: String,
        val exceptionType: String,
        val sanitizedMessage: String,
        val topStackFrame: String,
        val activeDocumentId: Int?,
        val activeOperationId: String?
    )

    fun install(context: Context) {
        val dir = File(context.applicationContext.filesDir, "diagnostics")
        installWithDirectory(dir)
    }

    fun installWithDirectory(directory: File) {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        markerDir = directory
        if (installed) return
        synchronized(this) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    recordCrashSync(thread.name, throwable)
                } catch (_: Throwable) {
                    // Never block default crash termination.
                } finally {
                    previous?.uncaughtException(thread, throwable)
                }
            }
            installed = true
        }
    }

    fun recordCrashSync(threadName: String, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val topFrame = throwable.stackTrace.firstOrNull()?.let {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        } ?: "unknown"
        val docId = TsdLogger.activeDocumentId
        val opId = TsdLogger.activeOperationId
        val safeMsg = TsdLogger.redact(throwable.message).replace('\n', ' ').replace('\r', ' ').take(200)

        TsdLogger.error(
            category = "CRASH",
            event = "uncaught_exception",
            operationId = opId,
            documentId = docId,
            fields = mapOf(
                "thread" to threadName,
                "topFrame" to topFrame
            ),
            throwable = throwable
        )

        val dir = markerDir ?: return
        if (!dir.exists()) dir.mkdirs()
        val payload = listOf(
            "timestampMs=$now",
            "thread=${threadName.replace('\n', ' ')}",
            "exceptionType=${throwable::class.java.name}",
            "message=$safeMsg",
            "topFrame=$topFrame",
            "documentId=${docId ?: ""}",
            "operationId=${opId ?: ""}"
        ).joinToString("\n")

        val tmp = File(dir, "$LAST_CRASH_FILE.tmp")
        val target = File(dir, LAST_CRASH_FILE)
        FileOutputStream(tmp, false).use { fos ->
            fos.write(payload.toByteArray(StandardCharsets.UTF_8))
            fos.fd.sync()
        }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    fun consumeLastCrashRecord(): CrashRecord? {
        val dir = markerDir ?: return null
        val file = File(dir, LAST_CRASH_FILE)
        if (!file.exists()) return null
        return try {
            val map = file.readLines(StandardCharsets.UTF_8)
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
                }
                .toMap()
            file.delete()
            CrashRecord(
                timestampMs = map["timestampMs"]?.toLongOrNull() ?: 0L,
                threadName = map["thread"].orEmpty(),
                exceptionType = map["exceptionType"].orEmpty(),
                sanitizedMessage = map["message"].orEmpty(),
                topStackFrame = map["topFrame"].orEmpty(),
                activeDocumentId = map["documentId"]?.toIntOrNull(),
                activeOperationId = map["operationId"]?.takeIf { it.isNotBlank() }
            )
        } catch (_: Throwable) {
            null
        }
    }
}
