package com.example.scannerkotlin.utils

import com.example.scannerkotlin.response.ErrorResponse
import com.google.gson.Gson

object BitrixApiErrorParser {
    private val gson = Gson()

    fun fromHttp(httpCode: Int, errorBody: String?): String {
        val parsed = parse(errorBody)
        return format(httpCode, parsed.error, parsed.errorDescription, errorBody)
    }

    fun fromSuccessBody(body: Map<*, *>?): String? {
        if (body == null) return null
        val error = body["error"] ?: return null
        val description = body["error_description"]?.toString()
        return format(200, error.toString(), description, null)
    }

    private fun parse(errorBody: String?): ErrorResponse {
        if (errorBody.isNullOrBlank()) return ErrorResponse()
        return try {
            gson.fromJson(errorBody, ErrorResponse::class.java) ?: ErrorResponse()
        } catch (_: Exception) {
            ErrorResponse()
        }
    }

    private fun format(
        httpCode: Int,
        errorCode: String?,
        description: String?,
        rawBody: String?
    ): String {
        val desc = description?.trim().orEmpty()
        val generic = desc.isEmpty() ||
            desc.equals("Unknown API error", ignoreCase = true) ||
            desc.equals("Unknown API error conducting document", ignoreCase = true)

        return buildString {
            append("Bitrix не провёл документ")
            if (httpCode !in 200..299) {
                append(" (HTTP $httpCode)")
            }
            if (!errorCode.isNullOrBlank()) {
                append("\nКод: $errorCode")
            }
            if (desc.isNotEmpty()) {
                append("\n$desc")
            }
            if (generic) {
                append("\nПроверьте склады, количество и что документ ещё черновик.")
            }
            if (generic && !rawBody.isNullOrBlank() && !rawBody.contains(desc) && rawBody.length < 300) {
                append("\n$rawBody")
            }
        }
    }
}
