package com.example.scannerkotlin.request

data class TsdDocumentSyncRequest(
    val operationId: String,
    val documentId: Int,
    val items: List<TsdDocumentSyncItem>,
    val deletedRemoteElementIds: List<Int> = emptyList()
)

data class TsdDocumentSyncItem(
    val localItemId: String,
    val offerId: Int,
    val quantity: Double,
    val storeTo: Int,
    val batchNumber: String? = null
)
