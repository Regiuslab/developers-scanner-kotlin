package com.example.scannerkotlin.response

data class NomenclatureResponse(
    val result: NomenclatureResult?,

) {

    data class NomenclatureResult(
        val documents: List<NomenclatureDocumentItem>?
    )

    data class NomenclatureDocumentItem(
        val documentId: Int?,
        val documentType: String?,
        val field620: List<Double>?
    )
}