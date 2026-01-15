package com.example.scannerkotlin.request

data class StoreAmountRequest(
    val select: MutableList<String>? = null,
    val filter: MutableMap<String, Any?>? = null
) {
}