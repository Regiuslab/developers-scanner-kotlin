package com.example.scannerkotlin.mappers

import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.utils.BitrixNumberParser
import com.google.gson.internal.LinkedTreeMap

class ProductMapper {
    fun mapToProduct(map: LinkedTreeMap<*, *>): Product {
        val parentIdRaw = map["parentId"]
        val parsedParentId: Int? = when (parentIdRaw) {
            is Map<*, *> -> BitrixNumberParser.toIntOrNull(parentIdRaw["value"])
            else -> BitrixNumberParser.toIntOrNull(parentIdRaw)
        }

        val product = Product(
            idInDocument = null,
            id = BitrixNumberParser.toIntOrDefault(map["id"], 0),
            iblockId = BitrixNumberParser.toIntOrDefault(map["iblockId"], 0),
            name = map["name"]?.toString(),
            measureId = BitrixNumberParser.toIntOrNull(map["measure"]),
            measureSymbol = null,
            quantity = BitrixNumberParser.toDoubleOrNull(map["quantity"]),
            barcode = map["barcode"] as? String,
            diameter = BitrixNumberParser.toDoubleOrNull(map["width"]),
            weight = BitrixNumberParser.toDoubleOrNull(map["weight"]),
            height = BitrixNumberParser.toDoubleOrNull(map["height"]),
            parentId = parsedParentId,
            storeAmount = null
        )
        product.catalogProductType = BitrixNumberParser.toIntOrNull(map["type"])
        return product
    }
}
