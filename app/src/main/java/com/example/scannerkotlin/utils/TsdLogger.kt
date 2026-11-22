package com.example.scannerkotlin.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Structured diagnostic logger for TSD warehouse operations.
 *
 * Guarantees:
 * - Redacts Bitrix REST webhook tokens, Authorization headers, PINs, and password fields.
 * - Writes structured key=value events to Android Logcat (when available) and a bounded
 *   2-file rotating diagnostic log in internal app storage (never cleared by cache trim).
 */
object TsdLogger {

    private const val MAX_LOG_FILE_BYTES = 256 * 1024L
    private const val MAX_RING_ENTRIES = 500
    private const val LOG_FILE_NAME = "tsd_diagnostics.log"
    private const val LOG_FILE_PREV_NAME = "tsd_diagnostics.prev.log"

    private val webhookPathRegex = Regex("""(/rest/\d+/)([^/\s?"']+)""")
    private val secretKeyValueRegex = Regex(
        """(?i)\b(password|pin|token|webhook|secret|authorization)\s*[:=]\s*([^\s,;}"']+)"""
    )

    private val lock = Any()
    private val ringBuffer = ArrayDeque<String>(MAX_RING_ENTRIES)

    @Volatile
    private var logDir: File? = null

    @Volatile
    var activeDocumentId: Int? = null

    @Volatile
    var activeOperationId: String? = null

    fun init(context: Context) {
        initDirectory(File(context.applicationContext.filesDir, "diagnostics"))
    }

    fun initDirectory(directory: File) {
        synchronized(lock) {
            if (!directory.exists()) {
                directory.mkdirs()
            }
            logDir = directory
        }
    }

    fun redact(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var sanitized = webhookPathRegex.replace(raw) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        sanitized = secretKeyValueRegex.replace(sanitized) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        return sanitized
    }

    fun info(
        category: String,
        event: String,
        operationId: String? = activeOperationId,
        documentId: Int? = activeDocumentId,
        fields: Map<String, Any?> = emptyMap()
    ) {
        record("INFO", category, event, operationId, documentId, fields, null)
    }

    fun warn(
        category: String,
        event: String,
        operationId: String? = activeOperationId,
        documentId: Int? = activeDocumentId,
        fields: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null
    ) {
        record("WARN", category, event, operationId, documentId, fields, throwable)
    }

    fun error(
        category: String,
        event: String,
        operationId: String? = activeOperationId,
        documentId: Int? = activeDocumentId,
        fields: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null
    ) {
        record("ERROR", category, event, operationId, documentId, fields, throwable)
    }

    fun recentEntries(limit: Int = 100): List<String> {
        synchronized(lock) {
            val list = ringBuffer.toList()
            return if (list.size <= limit) list else list.takeLast(limit)
        }
    }

    fun clearRingBufferForTest() {
        synchronized(lock) {
            ringBuffer.clear()
        }
    }

    private fun record(
        level: String,
        category: String,
        event: String,
        operationId: String?,
        documentId: Int?,
        fields: Map<String, Any?>,
        throwable: Throwable?
    ) {
        val timestamp = formatIsoTimestamp(System.currentTimeMillis())
        val line = buildString {
            append(timestamp)
            append(" [").append(level).append("]")
            append(" cat=").append(sanitizeToken(category))
            append(" event=").append(sanitizeToken(event))
            if (!operationId.isNullOrBlank()) {
                append(" opId=").append(sanitizeToken(operationId))
            }
            if (documentId != null) {
                append(" docId=").append(documentId)
            }
            for ((k, v) in fields) {
                if (v != null) {
                    val safeKey = sanitizeToken(k)
                    val safeVal = if (isSensitiveKey(safeKey)) {
                        "[REDACTED]"
                    } else {
                        redact(v.toString()).replace('\n', ' ').replace('\r', ' ')
                    }
                    append(' ').append(safeKey).append('=').append(safeVal)
                }
            }
            if (throwable != null) {
                append(" errType=").append(throwable::class.java.simpleName)
                val errMsg = redact(throwable.message).replace('\n', ' ').replace('\r', ' ')
                if (errMsg.isNotBlank()) {
                    append(" errMsg=\"").append(errMsg.take(240)).append('"')
                }
            }
        }

        try {
            val tag = "TSD_$category".take(23)
            when (level) {
                "ERROR" -> Log.e(tag, line, throwable)
                "WARN" -> Log.w(tag, line, throwable)
                else -> Log.i(tag, line)
            }
        } catch (_: Throwable) {
            // Safe fallback in plain JVM tests where android.util.Log may be stubbed.
        }

        synchronized(lock) {
            if (ringBuffer.size >= MAX_RING_ENTRIES) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(line)
            appendToDiskLocked(line)
        }
    }

    private fun appendToDiskLocked(line: String) {
        val dir = logDir ?: return
        try {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, LOG_FILE_NAME)
            if (file.exists() && file.length() >= MAX_LOG_FILE_BYTES) {
                val prev = File(dir, LOG_FILE_PREV_NAME)
                if (prev.exists()) prev.delete()
                file.renameTo(prev)
            }
            FileOutputStream(File(dir, LOG_FILE_NAME), true).use { fos ->
                fos.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
                fos.fd.sync()
            }
        } catch (_: Throwable) {
            // Never crash application due to diagnostic logging failure.
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase(Locale.ROOT)
        return lower.contains("password") ||
            lower.contains("pin") ||
            lower.contains("token") ||
            lower.contains("webhook") ||
            lower.contains("secret") ||
            lower.contains("auth")
    }

    private fun sanitizeToken(value: String): String {
        return value.trim().replace(' ', '_')
    }

    private fun formatIsoTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }
}
