package com.example.scannerkotlin.response

import com.google.gson.annotations.SerializedName


data class FirstBlankResponse(
    @SerializedName("result")
    val result: BlanksResult,

    @SerializedName("total")
    val total: Int,


) {


    data class BlanksResult(
        @SerializedName("items")
        val items: List<BlankItem>
    )


    data class BlankItem(
        @SerializedName("id")
        val id: Int,


        @SerializedName("ufCrm24_1748443839")
        val ufSomeNumericId: Int,


        @SerializedName("ufCrm24_1748358413")
        val ufSomeStringId: String


    )
}