package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.DocumentElement

object MovingDocumentStateMerger {
    data class Result(
        val elements: List<DocumentElement>,
        val newProducts: List<DocumentElement>,
        val deletedProducts: List<DocumentElement>
    )

    fun merge(
        serverElements: List<DocumentElement>,
        draftElements: List<DocumentElement>?,
        localDuringLoad: List<DocumentElement>
    ): Result {
        val elements = mutableListOf<DocumentElement>()
        if (localDuringLoad.isEmpty()) {
            if (draftElements != null) {
                elements.addAll(draftElements)
            } else {
                elements.addAll(serverElements)
            }
        } else {
            val baseline = draftElements ?: serverElements
            elements.addAll(baseline)
            localDuringLoad.asReversed().forEach { local ->
                if (elements.none { sameProduct(it, local) }) {
                    elements.add(0, local)
                }
            }
        }

        val newProducts = elements.filter { it.id == null }
        val deletedProducts = serverElements.filter { baseEl ->
            baseEl.id != null && elements.none { it.id == baseEl.id }
        }
        return Result(elements, newProducts, deletedProducts)
    }

    private fun sameProduct(left: DocumentElement, right: DocumentElement): Boolean {
        if (left.id != null && right.id != null && left.id == right.id) {
            return true
        }
        return left.elementId != null && left.elementId == right.elementId
    }
}
