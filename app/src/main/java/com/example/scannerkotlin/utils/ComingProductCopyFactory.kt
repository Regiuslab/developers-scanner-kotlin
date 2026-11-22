package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.ComingItemStatus
import com.example.scannerkotlin.model.ComingSyncState
import com.example.scannerkotlin.model.Product
import java.util.UUID

object ComingProductCopyFactory {
    fun cloneWithoutValues(source: Product): Product {
        val copy = source.copy(
            idInDocument = null,
            quantity = null,
            barcode = null,
            diameter = null,
            weight = null,
            height = null
        )
        copy.localItemId = UUID.randomUUID().toString()
        copy.offerId = null
        copy.catalogProductType = source.catalogProductType
        copy.destinationStoreId = source.destinationStoreId
        copy.itemStatus = ComingItemStatus.PENDING
        copy.syncState = ComingSyncState.LOCAL_DIRTY
        copy.validationError = null
        copy.updatedAtMs = System.currentTimeMillis()
        copy.batchNumber = null
        copy.isOldProduct = false
        return copy
    }
}
