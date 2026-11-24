package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComingNewLineQuantityTest {

    @Test
    fun `manual new line keeps meter and piece units but leaves quantity empty`() {
        val pipe = template(name = "Труба ПЭ 110", measureId = null, quantity = 1.0)
        val meterLine = ComingProductCopyFactory.newManualPosition(pipe, destinationStoreId = 3)
        assertEquals("Метр", meterLine.measureName)
        assertNull(meterLine.quantity)
        assertEquals("", ComingQuantityDisplay.format(meterLine.quantity, meterLine.measureName))

        val fitting = template(name = "Муфта соединительная", measureId = null, quantity = 1.0)
        val pieceLine = ComingProductCopyFactory.newManualPosition(fitting, destinationStoreId = 3)
        assertEquals("Штука", pieceLine.measureName)
        assertNull(pieceLine.quantity)
        assertEquals("", ComingQuantityDisplay.format(pieceLine.quantity, pieceLine.measureName))
    }

    @Test
    fun `manual new line does not inherit catalog stock quantity`() {
        val stocked = template(name = "Труба", measureId = 1, quantity = 12.0)
        val line = ComingProductCopyFactory.newManualPosition(stocked, destinationStoreId = 1)
        assertNull(line.quantity)
        assertEquals("Метр", line.measureName)
    }

    @Test
    fun `copied and scanned lines are new positions with empty quantity`() {
        val source = template(name = "Труба", measureId = 1, quantity = 12.5).apply {
            barcode = "4600000000001"
        }

        val copied = ComingProductCopyFactory.newCopiedPosition(source, destinationStoreId = 2)
        assertNull(copied.quantity)
        assertNull(copied.barcode)
        assertEquals(source.id, copied.id)
        assertEquals(source.measureId, copied.measureId)
        assertEquals(2, copied.destinationStoreId)

        val scanned = ComingProductCopyFactory.newScannedPosition(source, "4600000000099", destinationStoreId = 2)
        assertNull(scanned.quantity)
        assertEquals("4600000000099", scanned.barcode)
        assertEquals("Метр", scanned.measureName)
    }

    @Test
    fun `typed quantity is shown and unit change does not clear it`() {
        assertEquals("1", ComingQuantityDisplay.format(1.0, "Штука"))
        assertEquals("1.5", ComingQuantityDisplay.format(1.5, "Метр"))
        assertEquals("", ComingQuantityDisplay.format(null, "Метр"))
        assertEquals("", ComingQuantityDisplay.format(null, "Штука"))

        assertNull(ComingQuantityDisplay.afterMeasureChange(null, "Метр"))
        assertNull(ComingQuantityDisplay.afterMeasureChange(null, "Штука"))
        assertEquals(1.0, ComingQuantityDisplay.afterMeasureChange(1.0, "Штука")!!, 0.0)
        assertEquals(1.5, ComingQuantityDisplay.afterMeasureChange(1.5, "Килограмм")!!, 0.0)
        assertTrue(ComingQuantityDisplay.allowsFraction("Метр"))
        assertTrue(!ComingQuantityDisplay.allowsFraction("Штука"))
    }

    @Test
    fun `empty quantity blocks conduct with Укажите количество`() {
        val empty = template(name = "Труба", measureId = 1, quantity = null).apply {
            barcode = "4600000000001"
        }
        val issues = ComingProductValidator.validateDetailed(
            products = listOf(empty),
            documentId = 10,
            destinationStoreId = 1,
            expectedProductsIblockId = 14
        )
        assertTrue(issues.any { it.field == "quantity" && it.message.contains("Укажите количество") })
        assertTrue(issues.none { it.field == "quantity" && it.message.contains("больше 0") })

        val zero = empty.detachedCopy().apply { quantity = 0.0 }
        val zeroIssues = ComingProductValidator.validateDetailed(
            products = listOf(zero),
            documentId = 10,
            destinationStoreId = 1,
            expectedProductsIblockId = 14
        )
        assertTrue(zeroIssues.any { it.field == "quantity" && it.message.contains("больше 0") })

        val meter = empty.detachedCopy().apply { quantity = 1.5 }
        val piece = empty.detachedCopy().apply {
            measureName = "Штука"
            quantity = 1.0
            barcode = "4600000000002"
        }
        val accepted = ComingProductValidator.validateDetailed(
            products = listOf(meter, piece),
            documentId = 10,
            destinationStoreId = 1,
            expectedProductsIblockId = 14
        )
        assertTrue(accepted.none { it.field == "quantity" })
    }

    private fun template(name: String, measureId: Int?, quantity: Double?): Product {
        return Product(
            idInDocument = 4,
            id = 77,
            iblockId = 14,
            name = name,
            measureId = measureId,
            measureSymbol = null,
            quantity = quantity,
            barcode = "SHOULD_NOT_COPY",
            diameter = 10.0,
            weight = 2.0,
            height = 1.0,
            parentId = null,
            storeAmount = null
        )
    }
}
