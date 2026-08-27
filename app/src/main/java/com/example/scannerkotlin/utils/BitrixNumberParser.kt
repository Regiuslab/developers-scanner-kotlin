package com.example.scannerkotlin.utils

object BitrixNumberParser {
    fun toIntOrNull(value: Any?): Int? {
        return when (value) {
            null -> null
            is Number -> value.toInt()
            is String -> value.trim().toDoubleOrNull()?.toInt()
            else -> value.toString().toDoubleOrNull()?.toInt()
        }
    }

    fun toIntOrDefault(value: Any?, default: Int): Int {
        return toIntOrNull(value) ?: default
    }
}
