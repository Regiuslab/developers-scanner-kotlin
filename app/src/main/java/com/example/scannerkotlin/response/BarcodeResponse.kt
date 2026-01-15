package com.example.scannerkotlin.response

import com.google.gson.annotations.SerializedName

data class BarcodeResponse(
    @SerializedName("result")
    val result: Boolean
) {
}