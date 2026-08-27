package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.Document
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class DocumentListNewestFirstTest {

    @Test
    fun `puts newest date first then larger id`() {
        val older = document(id = 320, date = "2026-05-05T11:34:05+03:00")
        val newer = document(id = 395, date = "2026-07-08T11:29:43+03:00")
        val sorted = DocumentListNewestFirst.sort(listOf(older, newer))
        assertEquals(395, sorted.first().id)
        assertEquals(320, sorted.last().id)
    }

    private fun document(id: Int, date: String): Document {
        val created = OffsetDateTime.parse(date)
        return Document(
            commentary = null,
            createdBy = null,
            currency = null,
            dateCreate = created,
            dateDocument = created,
            dateModify = null,
            dateStatus = null,
            docNumber = id.toString(),
            docType = "M",
            id = id,
            modifiedBy = null,
            responsibleId = null,
            siteId = null,
            status = "N",
            statusBy = null,
            title = "Перемещение #$id",
            total = null
        )
    }
}
