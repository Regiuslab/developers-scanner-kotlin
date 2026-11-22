package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.DocumentElement
import com.example.scannerkotlin.persistence.ComingDraftRepository

/**
 * Persistent facade for moving document drafts backed by SQLite ([ComingDraftRepository]).
 */
object MovingDocumentDraftManager {

    fun saveDraft(documentId: Int, elements: List<DocumentElement>) {
        ComingDraftRepository.engine().saveMovingDraft(documentId, elements.map { it.copy() })
    }

    fun getDraft(documentId: Int): List<DocumentElement>? {
        return ComingDraftRepository.engine().loadMovingDraft(documentId)?.map { it.copy() }
    }

    fun clearDraft(documentId: Int) {
        ComingDraftRepository.engine().deleteMovingDraft(documentId)
    }
}
