package com.example.scannerkotlin.response

import com.google.gson.annotations.SerializedName

data class TsdDocumentSyncResponse(
    val result: Result? = null,
    val error: String? = null,
    @SerializedName("error_description")
    val errorDescription: String? = null
) {
    data class Result(
        val documentId: Int = 0,
        val status: String? = null,
        val alreadyConducted: Boolean = false,
        val elementIds: List<Int> = emptyList(),
        val items: List<SyncedItem> = emptyList(),
        val operationId: String? = null
    )

    data class SyncedItem(
        val localItemId: String? = null,
        val offerId: Int = 0,
        val elementId: Int = 0,
        val quantity: Double = 0.0
    )
}
