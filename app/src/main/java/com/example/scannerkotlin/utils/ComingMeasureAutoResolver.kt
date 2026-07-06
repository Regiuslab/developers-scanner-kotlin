package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.Product
import java.util.Locale

object ComingMeasureAutoResolver {
    private val pipeKeywords = listOf("труба", "pipe")
    private val pieceKeywords = listOf("фасон", "отвод", "тройник", "переход", "муфта", "угол", "штуч")

    fun resolve(product: Product): String? {
        product.measureName?.let { return it }
        val name = product.name?.lowercase(Locale.getDefault()) ?: return null

        if (pipeKeywords.any { name.contains(it) }) return "Метр"
        if (pieceKeywords.any { name.contains(it) }) return "Штука"
        return null
    }
}
