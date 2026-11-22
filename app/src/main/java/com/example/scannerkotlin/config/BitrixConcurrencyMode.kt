package com.example.scannerkotlin.config

/**
 * How incoming-document mutations talk to Bitrix.
 *
 * [LEGACY] — standard REST (`offer.add`, `barcode.setbyproductid`, `element.add`, `conduct`).
 * Safe for one Android process. Does **not** claim multi-TSD atomicity.
 *
 * [SERVER_GUARDED] — portal methods `tsd.variation.resolveOrCreate` and
 * `tsd.document.syncAndConduct`. If those methods are missing, 5xx, or time out,
 * mutating operations stop. There is **no** automatic fallback to [LEGACY].
 */
enum class BitrixConcurrencyMode {
    LEGACY,
    SERVER_GUARDED;

    fun toDiagnosticLabel(): String = when (this) {
        LEGACY -> "LEGACY_MODE"
        SERVER_GUARDED -> "SERVER_GUARDED_MODE"
    }

    companion object {
        fun fromConfig(raw: String?): BitrixConcurrencyMode {
            val normalized = raw?.trim()?.uppercase()?.replace('-', '_') ?: return LEGACY
            return when (normalized) {
                "SERVER_GUARDED", "SERVER_GUARDED_MODE", "GUARDED" -> SERVER_GUARDED
                else -> LEGACY
            }
        }
    }
}
