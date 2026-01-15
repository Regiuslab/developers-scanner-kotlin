package com.example.scannerkotlin.utils

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.lang.reflect.Type

class ParentIdValueDeserializer : JsonDeserializer<Int?> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): Int? {
        if (json == null || json.isJsonNull) {
            return null
        }


        if (json.isJsonObject) {
            val jsonObject = json.asJsonObject

            if (jsonObject.has("value")) {
                val valueElement = jsonObject.get("value")
                if (valueElement != null && !valueElement.isJsonNull && valueElement.isJsonPrimitive) {
                    val primitiveValue = valueElement.asJsonPrimitive

                    if (primitiveValue.isString) {
                        return primitiveValue.asString.toIntOrNull()
                    }

                    else if (primitiveValue.isNumber) {
                        return primitiveValue.asInt
                    }
                }
            }
        }
        return null
    }
}