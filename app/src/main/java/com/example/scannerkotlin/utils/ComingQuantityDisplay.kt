package com.example.scannerkotlin.utils

import java.util.Locale

/**
 * How an incoming-document quantity is shown and how it survives a unit change.
 * An unentered quantity is null and must be rendered as an empty field.
 */
object ComingQuantityDisplay {

    fun format(quantity: Double?, measureName: String?): String {
        val currentQuantity = quantity ?: return ""
        return if (measureName == "Штука") {
            currentQuantity.toInt().toString()
        } else if (currentQuantity % 1.0 == 0.0) {
            currentQuantity.toInt().toString()
        } else {
            String.format(Locale.US, "%.3f", currentQuantity).trimEnd('0').trimEnd('.')
        }
    }

    fun allowsFraction(measureName: String?): Boolean = measureName != "Штука"

    /**
     * Changing the unit keeps the typed quantity. Pieces stay whole numbers;
     * an empty field stays empty.
     */
    fun afterMeasureChange(quantity: Double?, newMeasureName: String?): Double? {
        if (quantity == null) return null
        if (newMeasureName == "Штука" && quantity % 1.0 != 0.0) {
            return quantity.toInt().toDouble()
        }
        return quantity
    }
}
