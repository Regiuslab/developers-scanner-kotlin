package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComingMeasureAutoResolverTest {

    @Test
    fun `returns existing measure from product`() {
        val product = product(name = "Труба ПЭ")
        product.measureName = "Килограмм"
        assertEquals("Килограмм", ComingMeasureAutoResolver.resolve(product))
    }

    @Test
    fun `resolves meter for pipe keyword`() {
        val product = product(name = "Труба стальная 57")
        assertEquals("Метр", ComingMeasureAutoResolver.resolve(product))
    }

    @Test
    fun `resolves piece for fitting keyword`() {
        val product = product(name = "Отвод 90")
        assertEquals("Штука", ComingMeasureAutoResolver.resolve(product))
    }

    @Test
    fun `returns null for unknown product`() {
        val product = product(name = "Краска")
        assertNull(ComingMeasureAutoResolver.resolve(product))
    }

    private fun product(name: String): Product = Product(
        idInDocument = null,
        id = 1,
        iblockId = 14,
        name = name,
        measureId = null,
        measureSymbol = null,
        quantity = null,
        barcode = null,
        diameter = null,
        weight = null,
        height = null,
        parentId = null,
        storeAmount = null
    )
}
