package com.example.scannerkotlin.response

import com.google.gson.annotations.SerializedName

data class ErrorResponse(
    val error: String? = null,
    @SerializedName("error_description")
    val errorDescription: String? = null
)
