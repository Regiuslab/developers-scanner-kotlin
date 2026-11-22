package com.example.scannerkotlin.response

import com.google.gson.annotations.SerializedName

data class TsdVariationResolveResponse(
    val result: Result? = null,
    val error: String? = null,
    @SerializedName("error_description")
    val errorDescription: String? = null
) {
    data class Result(
        val offerId: Int = 0,
        val barcode: String? = null,
        val created: Boolean = false,
        val operationId: String? = null
    )
}
