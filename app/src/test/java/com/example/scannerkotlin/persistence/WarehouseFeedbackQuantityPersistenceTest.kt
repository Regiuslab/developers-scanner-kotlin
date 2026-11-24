package com.example.scannerkotlin.persistence

import com.example.scannerkotlin.model.DocumentElement
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.SaveOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Draft round-trips for the warehouse feedback: an unentered quantity stays empty,
 * a typed quantity survives leaving the screen, and an older draft is not rewritten.
 */
class WarehouseFeedbackQuantityPersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `new line with empty quantity reloads as null and typed values survive restart`() = runBlocking {
        val dir = tempFolder.newFolder("coming_qty")
        val repo = ComingDraftRepository(FileBackedDraftStorageEngine(dir))
        val docId = 1111

        val emptyLine = line(name = "Труба", measureId = 1, quantity = null, barcode = null)
        val onePiece = line(name = "Муфта", measureId = 5, quantity = 1.0, barcode = "4600000000001")
        val meters = line(name = "Труба 1.5", measureId = 1, quantity = 1.5, barcode = "4600000000002")

        assertTrueSaved(repo.saveAddedOrUpdatedItem(docId, "Приход", 1, null, emptyLine, false))
        assertTrueSaved(repo.saveAddedOrUpdatedItem(docId, "Приход", 1, null, onePiece, false))
        assertTrueSaved(repo.saveAddedOrUpdatedItem(docId, "Приход", 1, null, meters, false))

        val restarted = ComingDraftRepository(FileBackedDraftStorageEngine(dir)).getDraft(docId)
        assertNotNull(restarted)
        val byName = restarted!!.items.associateBy { it.name }
        assertNull(byName["Труба"]?.quantity)
        assertEquals(1, byName["Труба"]?.measureId)
        assertEquals(1.0, byName["Муфта"]?.quantity!!, 0.0)
        assertEquals(5, byName["Муфта"]?.measureId)
        assertEquals(1.5, byName["Труба 1.5"]?.quantity!!, 0.0)
        assertEquals(1, byName["Труба 1.5"]?.measureId)
    }

    @Test
    fun `existing draft quantity is kept when the document is opened again`() = runBlocking {
        val dir = tempFolder.newFolder("old_draft")
        val docId = 462
        val original = line(name = "Старый черновик", measureId = 1, quantity = 7.5, barcode = "4600000000462")
        ComingDraftRepository(FileBackedDraftStorageEngine(dir))
            .saveAddedOrUpdatedItem(docId, "Приход 462", 1, null, original, true)

        val reopened = ComingDraftRepository(FileBackedDraftStorageEngine(dir)).getDraft(docId)
        assertNotNull(reopened)
        assertEquals(1, reopened!!.items.size)
        assertEquals(7.5, reopened.items.first().quantity!!, 0.0)
        assertEquals("4600000000462", reopened.items.first().barcode)
    }

    @Test
    fun `moving line added by barcode is restored with the same document`() {
        val dir = tempFolder.newFolder("moving_draft")
        val engine = FileBackedDraftStorageEngine(dir)
        val element = DocumentElement(
            id = null,
            amount = 2.0,
            docId = 90L,
            elementId = 318857,
            purchasingPrice = null,
            storeFrom = 1,
            storeTo = 2,
            name = "Труба по штрихкоду",
            mainAmount = null
        )
        engine.saveMovingDraft(90, listOf(element))

        val restored = FileBackedDraftStorageEngine(dir).loadMovingDraft(90)
        assertNotNull(restored)
        assertEquals(1, restored!!.size)
        assertEquals(318857, restored.first().elementId)
        assertEquals(90L, restored.first().docId)
        assertEquals(1, restored.first().storeFrom)
        assertEquals(2, restored.first().storeTo)
        assertEquals(2.0, restored.first().amount!!, 0.0)
    }

    private fun assertTrueSaved(outcome: SaveOutcome) {
        org.junit.Assert.assertTrue(outcome is SaveOutcome.Saved)
    }

    private fun line(name: String, measureId: Int, quantity: Double?, barcode: String?): Product {
        return Product(
            idInDocument = null,
            id = 77,
            iblockId = 14,
            name = name,
            measureId = measureId,
            measureSymbol = null,
            quantity = quantity,
            barcode = barcode,
            diameter = null,
            weight = null,
            height = null,
            parentId = null,
            storeAmount = null
        )
    }
}
