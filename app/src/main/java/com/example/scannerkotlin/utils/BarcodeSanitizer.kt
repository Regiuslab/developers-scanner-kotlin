package com.example.scannerkotlin.utils

/**
 * Sanitizes raw hardware scanner barcode payloads and suppresses duplicate hardware
 * broadcast bursts (e.g., simultaneously delivered relay + hardware broadcast or
 * scanner trigger bounce) without dropping intentional repeat scans.
 */
object BarcodeSanitizer {

    const val MAX_BARCODE_LENGTH = 64
    const val COMING_VARIATION_MAX_BARCODE_LENGTH = 13

    /**
     * Strips CR/LF suffixes, ASCII control characters, and leading/trailing spaces
     * commonly appended by Xcheng / Urovo / Zebra TSD hardware scanner wedges.
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                if (ch >= ' ' && ch.code != 127) {
                    append(ch)
                }
            }
        }.trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > MAX_BARCODE_LENGTH) {
            cleaned.substring(0, MAX_BARCODE_LENGTH)
        } else {
            cleaned
        }
    }

    fun normalizeForComingVariation(raw: String?): String? {
        val sanitized = sanitize(raw) ?: return null
        return if (sanitized.length > COMING_VARIATION_MAX_BARCODE_LENGTH) {
            sanitized.substring(0, COMING_VARIATION_MAX_BARCODE_LENGTH)
        } else {
            sanitized
        }
    }
}

class HardwareScanBurstDeduplicator(
    private val hardwareBurstWindowMs: Long = 180L
) {
    private val recentEventIds = LinkedHashSet<String>()
    private var lastBarcode: String? = null
    private var lastDeliveryAtMs: Long = 0L

    @Synchronized
    fun shouldAccept(
        barcode: String,
        eventId: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!eventId.isNullOrBlank()) {
            if (!recentEventIds.add(eventId)) {
                return false
            }
            if (recentEventIds.size > 128) {
                val iter = recentEventIds.iterator()
                if (iter.hasNext()) {
                    iter.next()
                    iter.remove()
                }
            }
        }
        if (barcode == lastBarcode && nowMs - lastDeliveryAtMs in 0 until hardwareBurstWindowMs) {
            return false
        }
        lastBarcode = barcode
        lastDeliveryAtMs = nowMs
        return true
    }
}
