package com.example.scannerkotlin.utils

/**
 * Shared barcode normalization and id parsing for the moving document.
 * Hardware scans and the manual field both go through [normalize] before lookup.
 */
object MovingBarcodeLookup {

    fun normalize(raw: String?): String? = BarcodeSanitizer.sanitize(raw)

    fun parseProductIds(rawResult: String?): Match {
        val trimmed = rawResult?.trim().orEmpty()
        if (trimmed.isEmpty() || isAbsent(trimmed)) return Match.NotFound

        val tokens = trimmed.split(',', ';', '|', '\n', '\t', ' ')
            .map { token -> token.trim().trim('[', ']', '"', '\'') }
            .filter { it.isNotEmpty() }
        val ids = tokens.mapNotNull { token ->
            val numeric = token.toLongOrNull()
            if (numeric != null && numeric > 0L) numeric.toString() else null
        }.distinct()

        return when (ids.size) {
            0 -> Match.NotFound
            1 -> Match.Single(ids.first())
            else -> Match.Ambiguous(ids)
        }
    }

    private fun isAbsent(value: String): Boolean {
        return value == "0" ||
            value.equals("null", ignoreCase = true) ||
            value.equals("false", ignoreCase = true)
    }

    sealed class Match {
        data class Single(val productId: String) : Match()
        data class Ambiguous(val productIds: List<String>) : Match()
        data object NotFound : Match()
    }
}
