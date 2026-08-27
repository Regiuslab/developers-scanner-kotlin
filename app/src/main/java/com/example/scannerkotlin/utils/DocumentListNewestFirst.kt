package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.Document

object DocumentListNewestFirst {
    fun sort(documents: List<Document>): List<Document> {
        return documents.sortedWith(
            compareByDescending<Document> { it.dateCreate ?: it.dateDocument }
                .thenByDescending { it.id }
        )
    }
}
