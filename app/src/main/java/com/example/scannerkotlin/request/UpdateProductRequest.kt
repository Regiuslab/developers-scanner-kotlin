package com.example.scannerkotlin.request

data class UpdateProductRequest(
    var id: Int? = null,
    var fields: MutableMap<String, Any?>? = null
) {
}