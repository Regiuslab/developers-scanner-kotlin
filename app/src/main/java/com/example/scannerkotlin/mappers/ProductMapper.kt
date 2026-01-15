package com.example.scannerkotlin.mappers

import com.example.scannerkotlin.model.Product
import com.google.gson.internal.LinkedTreeMap // Используется если map приходит от Gson без строгого типа
import android.util.Log // Для логирования, если необходимо

class ProductMapper {
    fun mapToProduct(map: LinkedTreeMap<*, *>): Product {
        val parentIdRaw = map["parentId"]
        var parsedParentId: Int? = null

        if (parentIdRaw != null) {
            if (parentIdRaw is LinkedTreeMap<*, *>) {

                val parentIdValueRaw = parentIdRaw["value"]
                if (parentIdValueRaw is String) {
                    parsedParentId = parentIdValueRaw.toIntOrNull()
                } else if (parentIdValueRaw is Double) {
                    parsedParentId = parentIdValueRaw.toInt()
                } else if (parentIdValueRaw is Number) {
                    parsedParentId = parentIdValueRaw.toInt()
                }

            } else if (parentIdRaw is String) {

                parsedParentId = parentIdRaw.toIntOrNull()
            } else if (parentIdRaw is Double) {
                parsedParentId = parentIdRaw.toInt()
            } else if (parentIdRaw is Number) {

                parsedParentId = parentIdRaw.toInt()
            }

        }

        return Product(
            idInDocument = null,
            id = (map["id"] as? Double)?.toInt() ?: (map["id"] as? Number)?.toInt() ?: 0,
            iblockId = (map["iblockId"] as? Double)?.toInt() ?: (map["iblockId"] as? Number)?.toInt() ?: 0,
            name = map["name"] as? String,
            measureId = (map["measure"] as? Double)?.toInt() ?: (map["measure"] as? Number)?.toInt(),
            measureSymbol = null,
            quantity = (map["quantity"] as? Double)?.toDouble() ?: (map["quantity"] as? Number)?.toDouble(),
            barcode = map["barcode"] as? String,
            diameter = (map["width"] as? Double)?.toDouble() ?: (map["width"] as? Number)?.toDouble(),
            weight = (map["weight"] as? Double)?.toDouble() ?: (map["weight"] as? Number)?.toDouble(),
            height = (map["height"] as? Double)?.toDouble() ?: (map["height"] as? Number)?.toDouble(),
            parentId = parsedParentId,
            storeAmount = null
        )
    }
}