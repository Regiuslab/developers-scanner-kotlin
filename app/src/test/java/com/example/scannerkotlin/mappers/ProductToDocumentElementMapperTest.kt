package com.example.scannerkotlin.mappers

import com.example.scannerkotlin.model.Product
import org.junit.Assert.assertNull
import org.junit.Test

class ProductToDocumentElementMapperTest {

    @Test
    fun `does not copy catalog quantity into warehouse remainder`() {
        val product = Product(
            idInDocument = null,
            id = 318857,
            iblockId = 14,
            name = "Труба ст20 76х12",
            measureId = 1,
            measureSymbol = null,
            quantity = 7.87,
            barcode = "0000000021968",
            diameter = null,
            weight = null,
            height = null,
            parentId = null,
            storeAmount = null
        )
        val element = ProductToDocumentElementMapper().map(product, docId = 462L)
        assertNull(element.mainAmount)
    }
}
