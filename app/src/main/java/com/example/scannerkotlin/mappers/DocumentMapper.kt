package com.example.scannerkotlin.mappers

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.scannerkotlin.model.Document
import com.example.scannerkotlin.utils.BitrixNumberParser
import com.example.scannerkotlin.utils.TsdLogger
import com.google.gson.internal.LinkedTreeMap
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class DocumentMapper {
    @RequiresApi(Build.VERSION_CODES.O)
    fun mapToDocument(documentMap: LinkedTreeMap<*, *>): Document? {
        return try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            Document(
                commentary = documentMap["commentary"] as? String,
                createdBy = BitrixNumberParser.toIntOrDefault(documentMap["createdBy"], 0),
                currency = documentMap["currency"] as? String ?: "",
                dateCreate = parseDate(documentMap["dateCreate"] as? String, formatter),
                dateDocument = parseDate(documentMap["dateDocument"] as? String, formatter),
                dateModify = parseDate(documentMap["dateModify"] as? String, formatter),
                dateStatus = parseDate(documentMap["dateStatus"] as? String, formatter),
                docNumber = documentMap["docNumber"]?.toString(),
                docType = documentMap["docType"]?.toString(),
                id = BitrixNumberParser.toIntOrNull(documentMap["id"]),
                modifiedBy = BitrixNumberParser.toIntOrNull(documentMap["modifiedBy"]),
                responsibleId = BitrixNumberParser.toIntOrNull(documentMap["responsibleId"]),
                siteId = documentMap["siteId"] as? String,
                status = documentMap["status"]?.toString(),
                statusBy = BitrixNumberParser.toIntOrNull(documentMap["statusBy"]),
                title = documentMap["title"]?.toString(),
                total = BitrixNumberParser.toDoubleOrNull(documentMap["total"])
            )
        } catch (e: Exception) {
            TsdLogger.warn(category = "MAPPER", event = "document_map_failed", throwable = e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun parseDate(dateString: String?, formatter: DateTimeFormatter): OffsetDateTime? {
        if (dateString.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(dateString, formatter)
        } catch (_: Exception) {
            null
        }
    }
}
