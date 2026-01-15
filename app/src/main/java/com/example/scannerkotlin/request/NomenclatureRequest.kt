package com.example.scannerkotlin.request

data class NomenclatureRequest(
    val select: List<String> = listOf(
        "documentId", "field620", "documentType"
    ),

    var filter: MutableMap<String, Any?>? = null
){
}