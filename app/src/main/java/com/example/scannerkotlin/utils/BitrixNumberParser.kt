package com.example.scannerkotlin.utils

object BitrixNumberParser {
    fun toIntOrNull(value: Any?): Int? {
        return when (value) {
            null -> null
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toInt()
            is String -> value.trim().replace(',', '.').toDoubleOrNull()?.toInt()
            else -> value.toString().trim().replace(',', '.').toDoubleOrNull()?.toInt()
        }
    }

    fun toIntOrDefault(value: Any?, default: Int): Int {
        return toIntOrNull(value) ?: default
    }

    fun toLongOrNull(value: Any?): Long? {
        return when (value) {
            null -> null
            is Long -> value
            is Number -> value.toLong()
            is String -> value.trim().replace(',', '.').toDoubleOrNull()?.toLong()
            else -> value.toString().trim().replace(',', '.').toDoubleOrNull()?.toLong()
        }
    }

    fun toDoubleOrNull(value: Any?): Double? {
        return when (value) {
            null -> null
            is Double -> value.takeIf { !it.isNaN() && !it.isInfinite() }
            is Number -> value.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
            is String -> value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
            else -> value.toString().trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
        }
    }
}
