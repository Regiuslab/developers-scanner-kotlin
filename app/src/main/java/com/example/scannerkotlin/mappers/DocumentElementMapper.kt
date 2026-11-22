package com.example.scannerkotlin.mappers

import com.example.scannerkotlin.model.DocumentElement
import com.example.scannerkotlin.utils.BitrixNumberParser
import com.google.gson.internal.LinkedTreeMap

class DocumentElementMapper {

    fun mapToDocumentElement(map: LinkedTreeMap<*, *>): DocumentElement {
        return DocumentElement(
            amount = BitrixNumberParser.toDoubleOrNull(map["amount"]),
            docId = BitrixNumberParser.toLongOrNull(map["docId"]),
            elementId = BitrixNumberParser.toIntOrNull(map["elementId"]),
            purchasingPrice = BitrixNumberParser.toDoubleOrNull(map["purchasingPrice"]),
            storeFrom = BitrixNumberParser.toIntOrNull(map["storeFrom"]),
            storeTo = BitrixNumberParser.toIntOrNull(map["storeTo"]),
            id = BitrixNumberParser.toIntOrNull(map["id"]),
            name = null,
            mainAmount = null
        )
    }
}
