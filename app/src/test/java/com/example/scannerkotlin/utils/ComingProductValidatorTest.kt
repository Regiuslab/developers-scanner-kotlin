package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComingProductValidatorTest {

    @Test
    fun `returns empty errors for valid product`() {
        val product = baseProduct().apply {
            measureName = "Метр"
            barcode = "1234567890123"
            isOldProduct = true
            batchNumber = "LOT-1"
            weight = 11.4
        }

        val errors = ComingProductValidator.validate(listOf(product))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `returns errors for missing measure barcode and old product fields`() {
        val product = baseProduct().apply {
            measureName = null
            barcode = null
            isOldProduct = true
            batchNumber = null
            weight = 0.0
        }

        val errors = ComingProductValidator.validate(listOf(product))
        assertEquals(4, errors.size)
    }

    private fun baseProduct(): Product = Product(
        idInDocument = null,
        id = 12,
        iblockId = 14,
        name = "Труба 110",
        measureId = null,
        measureSymbol = null,
        quantity = 1.0,
        barcode = null,
        diameter = null,
        weight = null,
        height = null,
        parentId = null,
        storeAmount = null
    )
}
