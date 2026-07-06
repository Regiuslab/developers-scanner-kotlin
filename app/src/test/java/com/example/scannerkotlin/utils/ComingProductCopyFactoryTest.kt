package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ComingProductCopyFactoryTest {

    @Test
    fun `cloneWithoutValues clears mutable operational fields`() {
        val source = Product(
            idInDocument = 10,
            id = 77,
            iblockId = 14,
            name = "Труба ПЭ100",
            measureId = 1,
            measureSymbol = "м",
            quantity = 12.5,
            barcode = "1234567890123",
            diameter = 63.0,
            weight = 21.0,
            height = 5.8,
            parentId = null,
            storeAmount = null,
            batchNumber = "B-01",
            isOldProduct = true
        )

        val copy = ComingProductCopyFactory.cloneWithoutValues(source)

        assertEquals(source.id, copy.id)
        assertEquals(source.name, copy.name)
        assertEquals(source.measureId, copy.measureId)
        assertNull(copy.idInDocument)
        assertNull(copy.quantity)
        assertNull(copy.barcode)
        assertNull(copy.diameter)
        assertNull(copy.weight)
        assertNull(copy.height)
        assertNull(copy.batchNumber)
        assertFalse(copy.isOldProduct)
    }
}
