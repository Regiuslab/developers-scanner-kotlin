package com.example.scannerkotlin.response

import com.google.gson.annotations.SerializedName

data class SgpResponse(
    val result: SgpResult?,

    ) {

    data class SgpResult(
        val documents: List<SgpDocumentItem>?
    )

    data class SgpDocumentItem(
        val documentId: Int?,
    )
}