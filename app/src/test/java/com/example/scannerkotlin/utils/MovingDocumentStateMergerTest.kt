package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.DocumentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovingDocumentStateMergerTest {

    @Test
    fun `uses server elements when there is no draft and no local scan`() {
        val server = listOf(element(id = 10, elementId = 318857, name = "server"))
        val result = MovingDocumentStateMerger.merge(server, null, emptyList())
        assertEquals(1, result.elements.size)
        assertEquals(10, result.elements.first().id)
        assertTrue(result.newProducts.isEmpty())
    }

    @Test
    fun `keeps scanned product when initial load finishes later`() {
        val server = emptyList<DocumentElement>()
        val scanned = listOf(element(id = null, elementId = 318857, name = "scanned"))
        val result = MovingDocumentStateMerger.merge(server, null, scanned)
        assertEquals(1, result.elements.size)
        assertEquals(318857, result.elements.first().elementId)
        assertEquals(1, result.newProducts.size)
    }

    @Test
    fun `applies draft when user has not scanned during load`() {
        val server = listOf(element(id = 10, elementId = 1, name = "server"))
        val draft = listOf(element(id = null, elementId = 2, name = "draft"))
        val result = MovingDocumentStateMerger.merge(server, draft, emptyList())
        assertEquals("draft", result.elements.first().name)
        assertEquals(1, result.newProducts.size)
    }

    private fun element(id: Int?, elementId: Int, name: String): DocumentElement {
        return DocumentElement(
            id = id,
            amount = 1.0,
            docId = 462L,
            elementId = elementId,
            purchasingPrice = 0.0,
            storeFrom = null,
            storeTo = null,
            name = name,
            mainAmount = null
        )
    }
}
