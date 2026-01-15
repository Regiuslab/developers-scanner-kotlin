package com.example.scannerkotlin.request

data class UpdateCommentRequest(
    var documentId: Int? = null,
    var fields: MutableMap<String, Any?>? = null
) {
}