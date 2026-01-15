package com.example.scannerkotlin.response

import com.google.gson.annotations.SerializedName


data class SecondBlankResponse(
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


        @SerializedName("ufCrm25_1748528857")
        val ufSomeNumericId: Int,


        @SerializedName("ufCrm25_1748356055518")
        val ufSomeStringId: String


    )
}