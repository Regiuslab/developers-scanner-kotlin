package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.Product

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
        copy.batchNumber = null
        copy.isOldProduct = false
        return copy
    }
}
