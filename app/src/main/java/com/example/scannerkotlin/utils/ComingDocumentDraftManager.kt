package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.ComingDocumentDraftRecord
import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.ComingSyncState
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.persistence.ComingDraftRepository
import java.util.UUID

/**
 * Persistent facade for incoming document drafts backed by [ComingDraftRepository] (SQLite).
 * Preserves legacy helper methods while surviving process death, `force-stop`, and device reboot.
 */
object ComingDocumentDraftManager {

    fun saveDraft(
        documentId: Int,
        products: List<Product>,
        comment: String?,
        documentTitle: String? = null,
        allowedNomenclatureIds: List<Double>? = null,
        destinationStoreId: Int = 1
    ) {
        val engine = ComingDraftRepository.engine()
        val existing = engine.loadComingDraft(documentId)
        val now = System.currentTimeMillis()
        val record = ComingDocumentDraftRecord(
            documentId = documentId,
            operationId = existing?.operationId ?: UUID.randomUUID().toString(),
            documentTitle = documentTitle ?: existing?.documentTitle,
            destinationStoreId = destinationStoreId,
            destinationStoreTitle = existing?.destinationStoreTitle,
            comment = comment ?: "",
            allowedNomenclatureIds = allowedNomenclatureIds ?: existing?.allowedNomenclatureIds,
            state = existing?.state?.takeIf { it != ComingOperationState.COMMITTED } ?: ComingOperationState.DRAFT,
            syncState = ComingSyncState.LOCAL_SAVED,
            lastErrorMessage = existing?.lastErrorMessage,
            conductAttemptCount = existing?.conductAttemptCount ?: 0,
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now,
            lastActiveAtMs = now,
            items = products.map { it.detachedCopy(preserveLocalItemId = true) },
            deletedRemoteElementIds = existing?.deletedRemoteElementIds ?: emptyList()
        )
        engine.saveFullComingDraft(record)
    }

    fun getDraftRecord(documentId: Int): ComingDocumentDraftRecord? {
        return ComingDraftRepository.engine()
            .loadComingDraft(documentId)
            ?.takeIf { it.state != ComingOperationState.COMMITTED }
    }

    fun getDraftProducts(documentId: Int): List<Product>? {
        val record = getDraftRecord(documentId) ?: return null
        if (record.items.isEmpty() && record.comment.isNullOrBlank()) return null
        return record.items.map { it.detachedCopy(preserveLocalItemId = true) }
    }

    fun getDraftComment(documentId: Int): String? {
        return getDraftRecord(documentId)?.comment
    }

    fun listActiveDrafts(): List<ComingDocumentDraftRecord> {
        return ComingDraftRepository.engine().listActiveComingDrafts()
    }

    /**
     * Deletes an empty/abandoned local draft explicitly requested by user or when empty.
     * For committed documents, prefer [ComingDraftRepository.markCommittedAndFinalize].
     */
    fun clearDraft(documentId: Int) {
        ComingDraftRepository.engine().deleteComingDraft(documentId)
    }
}
