package com.example.scannerkotlin.request

import com.google.gson.annotations.SerializedName

data class ProcessesRequest(
    @SerializedName("entityTypeId")
    val entityTypeId: Int = 1070,

    @SerializedName("filter")
    var filter: MutableMap<String, Any?>? = null
) {
}