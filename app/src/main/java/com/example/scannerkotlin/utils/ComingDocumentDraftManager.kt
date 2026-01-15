package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.Product
import java.util.concurrent.ConcurrentHashMap


object ComingDocumentDraftManager {


    private val drafts = ConcurrentHashMap<Int, List<Product>>()
    private val comments = ConcurrentHashMap<Int, String?>()


    fun saveDraft(documentId: Int, products: List<Product>, comment: String?) {
        drafts[documentId] = ArrayList(products)
        comments[documentId] = comment ?: ""
    }


    fun getDraftProducts(documentId: Int): List<Product>? {

        return drafts[documentId]?.let { ArrayList(it) }
    }


    fun getDraftComment(documentId: Int): String? {
        return comments[documentId]
    }

    fun clearDraft(documentId: Int) {
        drafts.remove(documentId)
        comments.remove(documentId)
    }
}