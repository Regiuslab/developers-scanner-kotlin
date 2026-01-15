package com.example.scannerkotlin.request

data class ProcessUpdateRequest(
    val entityTypeId: Int = 1070,
    var id: Int?,
    var fields: MutableMap<String, Any?>? = null
    ) {
}