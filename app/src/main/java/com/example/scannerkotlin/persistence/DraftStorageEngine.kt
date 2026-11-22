package com.example.scannerkotlin.persistence

import com.example.scannerkotlin.model.ComingDocumentDraftRecord
import com.example.scannerkotlin.model.ComingItemStatus
import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.ComingSyncState
import com.example.scannerkotlin.model.DocumentElement
import com.example.scannerkotlin.model.OperationStepJournalEntry
import com.example.scannerkotlin.model.Product

data class DraftWriteSummary(
    val operationId: String,
    val updatedAtMs: Long,
    val totalItems: Int
)

/**
 * Persistent storage contract for unfinished warehouse drafts and operation step journals.
 * Must NEVER be cleared when reference caches are invalidated.
 */
interface DraftStorageEngine {

    fun loadComingDraft(documentId: Int): ComingDocumentDraftRecord?

    fun getDraftSummary(documentId: Int): DraftWriteSummary? {
        val draft = loadComingDraft(documentId) ?: return null
        return DraftWriteSummary(draft.operationId, draft.updatedAtMs, draft.items.size)
    }

    fun listActiveComingDrafts(): List<ComingDocumentDraftRecord>

    fun saveFullComingDraft(record: ComingDocumentDraftRecord)

    fun upsertComingItem(
        documentId: Int,
        documentTitle: String?,
        destinationStoreId: Int,
        allowedNomenclatureIds: List<Double>?,
        item: Product,
        insertAtTop: Boolean
    ): ComingDocumentDraftRecord

    fun upsertComingItemFast(
        documentId: Int,
        documentTitle: String?,
        destinationStoreId: Int,
        allowedNomenclatureIds: List<Double>?,
        item: Product,
        insertAtTop: Boolean
    ): DraftWriteSummary {
        val updated = upsertComingItem(
            documentId = documentId,
            documentTitle = documentTitle,
            destinationStoreId = destinationStoreId,
            allowedNomenclatureIds = allowedNomenclatureIds,
            item = item,
            insertAtTop = insertAtTop
        )
        return DraftWriteSummary(updated.operationId, updated.updatedAtMs, updated.items.size)
    }

    fun updateItemQuantityAtomically(
        documentId: Int,
        localItemId: String,
        newQuantity: Double?,
        delta: Double? = null
    ): Product?

    fun updateItemBarcodeAtomically(
        documentId: Int,
        localItemId: String,
        barcode: String?
    ): Product?

    fun updateItemRemoteIdentifiers(
        documentId: Int,
        localItemId: String,
        offerId: Int?,
        idInDocument: Int?,
        itemStatus: ComingItemStatus,
        syncState: ComingSyncState,
        validationError: String? = null
    )

    /**
     * Persists item remote identifiers and the matching journal row in a single atomic write.
     * A crash cannot leave offerId/idInDocument saved without the journal entry, or vice versa.
     */
    fun recordItemAndJournalAtomically(
        documentId: Int,
        localItemId: String,
        offerId: Int?,
        idInDocument: Int?,
        itemStatus: ComingItemStatus,
        syncState: ComingSyncState,
        validationError: String? = null,
        journalEntry: OperationStepJournalEntry
    ) {
        updateItemRemoteIdentifiers(
            documentId = documentId,
            localItemId = localItemId,
            offerId = offerId,
            idInDocument = idInDocument,
            itemStatus = itemStatus,
            syncState = syncState,
            validationError = validationError
        )
        appendStepJournal(journalEntry)
    }

    fun removeComingItem(documentId: Int, localItemId: String, remoteElementIdToDelete: Int?): ComingDocumentDraftRecord?

    fun updateDraftMetadata(
        documentId: Int,
        comment: String? = null,
        state: ComingOperationState? = null,
        syncState: ComingSyncState? = null,
        lastErrorMessage: String? = null,
        incrementAttempt: Boolean = false
    ): ComingDocumentDraftRecord?

    fun markComingDraftCommitted(documentId: Int, operationId: String)

    fun deleteComingDraft(documentId: Int)

    fun transitionInterruptedOperationsOnStartup(): List<ComingDocumentDraftRecord>

    fun appendStepJournal(entry: OperationStepJournalEntry)

    fun getStepJournal(operationId: String): List<OperationStepJournalEntry>

    fun getStepJournalForDocument(documentId: Int): List<OperationStepJournalEntry>

    fun saveMovingDraft(documentId: Int, elements: List<DocumentElement>)

    fun loadMovingDraft(documentId: Int): List<DocumentElement>?

    fun deleteMovingDraft(documentId: Int)
}
