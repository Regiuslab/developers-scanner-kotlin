package com.example.scannerkotlin.response

import com.example.scannerkotlin.model.Provider
import com.google.gson.annotations.SerializedName

data class GettingProvidersResponse(
    @SerializedName("result")
    val result: ResultData? = null,

    @SerializedName("total")
    val total: Int? = null,

) {

    data class ResultData(
        @SerializedName("items")
        val items: List<Provider>?
    )



    fun getAllProviders(): List<Provider> {
        return result?.items ?: emptyList()
    }
}