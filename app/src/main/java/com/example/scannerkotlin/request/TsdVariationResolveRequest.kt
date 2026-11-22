package com.example.scannerkotlin.request

data class TsdVariationResolveRequest(
    val operationId: String,
    val parentProductId: Int,
    val barcode: String?,
    val localItemId: String,
    val offerFields: Map<String, Any?>
)
