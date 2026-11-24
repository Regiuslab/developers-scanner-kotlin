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

    /**
     * Manual nomenclature pick. Catalog stock is not the document quantity:
     * the operator has not typed a quantity yet, so it stays null.
     */
    fun newManualPosition(template: Product, destinationStoreId: Int): Product {
        return template.detachedCopy(preserveLocalItemId = false).apply {
            idInDocument = null
            offerId = null
            this.destinationStoreId = destinationStoreId
            measureName = ComingMeasureAutoResolver.resolve(this)
            quantity = null
            barcode = null
            validationError = null
        }
    }

    /**
     * Copy starts a new line without operational values. It does not carry the
     * source quantity and does not substitute 1.
     */
    fun newCopiedPosition(source: Product, destinationStoreId: Int): Product {
        return cloneWithoutValues(source).apply {
            this.destinationStoreId = destinationStoreId
        }
    }

    /**
     * Extra line created when every existing card already has a barcode.
     * The scanned barcode is applied; quantity stays empty until it is typed.
     */
    fun newScannedPosition(template: Product, barcode: String, destinationStoreId: Int): Product {
        return cloneWithoutValues(template).apply {
            this.barcode = barcode
            this.destinationStoreId = destinationStoreId
        }
    }
}
