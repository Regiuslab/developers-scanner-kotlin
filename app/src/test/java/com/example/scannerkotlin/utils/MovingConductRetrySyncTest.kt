package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.DocumentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovingConductRetrySyncTest {

    @Test
    fun `skips products that already got a Bitrix row id`() {
        val newProducts = listOf(
            element(id = 88, elementId = 318857),
            element(id = null, elementId = 1)
        )
        val pending = MovingConductRetrySync.pendingAdds(newProducts)
        assertEquals(1, pending.size)
        assertEquals(1, pending.first().elementId)
    }

    @Test
    fun `moves persisted rows to base list so retry updates instead of adding`() {
        val newProducts = mutableListOf(element(id = 88, elementId = 318857))
        val baseList = mutableListOf<DocumentElement>()

        MovingConductRetrySync.promotePersistedAdds(newProducts, baseList)

        assertTrue(newProducts.isEmpty())
        assertEquals(1, baseList.size)
        assertEquals(88, baseList.first().id)
    }

    private fun element(id: Int?, elementId: Int): DocumentElement {
        return DocumentElement(
            id = id,
            amount = 6.36,
            docId = 462L,
            elementId = elementId,
            purchasingPrice = 0.0,
            storeFrom = 1,
            storeTo = 2,
            name = "Труба",
            mainAmount = null
        )
    }
}
