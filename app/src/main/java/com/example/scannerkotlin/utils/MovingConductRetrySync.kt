package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.DocumentElement

object MovingConductRetrySync {
    fun pendingAdds(newProducts: List<DocumentElement>): List<DocumentElement> {
        return newProducts.filter { it.id == null }
    }

    fun promotePersistedAdds(
        newProducts: MutableList<DocumentElement>,
        baseList: MutableList<DocumentElement>
    ) {
        val persisted = newProducts.filter { it.id != null }
        for (element in persisted) {
            if (baseList.none { it.id == element.id }) {
                baseList.add(element)
            }
        }
        newProducts.removeAll { it.id != null }
    }
}
