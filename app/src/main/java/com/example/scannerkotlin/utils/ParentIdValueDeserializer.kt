package com.example.scannerkotlin.utils

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

class ParentIdValueDeserializer : JsonDeserializer<Int?>, JsonSerializer<Int?> {
    override fun serialize(
        src: Int?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement {
        return if (src == null) JsonNull.INSTANCE else JsonPrimitive(src)
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): Int? {
        if (json == null || json.isJsonNull) {
            return null
        }

        if (json.isJsonPrimitive) {
            val primitive = json.asJsonPrimitive
            return when {
                primitive.isNumber -> primitive.asNumber.toInt()
                primitive.isString -> BitrixNumberParser.toIntOrNull(primitive.asString)
                else -> null
            }
        }

        if (json.isJsonObject) {
            val jsonObject = json.asJsonObject
            if (jsonObject.has("value")) {
                val valueElement = jsonObject.get("value")
                if (valueElement != null && !valueElement.isJsonNull && valueElement.isJsonPrimitive) {
                    val primitiveValue = valueElement.asJsonPrimitive
                    if (primitiveValue.isString) {
                        return BitrixNumberParser.toIntOrNull(primitiveValue.asString)
                    } else if (primitiveValue.isNumber) {
                        return primitiveValue.asNumber.toInt()
                    }
                }
            }
        }
        return null
    }
}
