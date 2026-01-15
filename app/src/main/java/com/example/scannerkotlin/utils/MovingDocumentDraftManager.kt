package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.DocumentElement
import java.util.concurrent.ConcurrentHashMap


object MovingDocumentDraftManager {


    private val drafts = ConcurrentHashMap<Int, List<DocumentElement>>()

    /**
     * Сохраняет черновик для указанного документа перемещения.
     * @param documentId ID документа.
     * @param elements Список элементов документа для сохранения.
     */
    fun saveDraft(documentId: Int, elements: List<DocumentElement>) {

        drafts[documentId] = ArrayList(elements)
    }

    /**
     * Возвращает сохраненный черновик элементов для документа.
     * @param documentId ID документа.
     * @return Список элементов или null, если черновика нет.
     */
    fun getDraft(documentId: Int): List<DocumentElement>? {
        return drafts[documentId]?.let { ArrayList(it) }
    }

    /**
     * Удаляет черновик для указанного документа.
     * Вызывается после успешного сохранения документа на сервере.
     * @param documentId ID документа.
     */
    fun clearDraft(documentId: Int) {
        drafts.remove(documentId)
    }
}