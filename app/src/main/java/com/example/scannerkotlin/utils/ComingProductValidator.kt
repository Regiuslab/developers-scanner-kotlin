package com.example.scannerkotlin.utils

import com.example.scannerkotlin.model.Product

object ComingProductValidator {
    fun validate(products: List<Product>): List<String> {
        val errors = mutableListOf<String>()
        if (products.isEmpty()) {
            errors.add("Добавьте хотя бы один товар в документ")
            return errors
        }

        products.forEach { product ->
            val title = product.name ?: "Без названия"
            if (product.measureName.isNullOrBlank()) {
                errors.add("• Товар: $title (ID: ${product.id}) — не выбрана единица измерения")
            }
            if (product.barcode.isNullOrBlank()) {
                errors.add("• Товар: $title (ID: ${product.id}) — не заполнен штрихкод")
            }
            if (product.isOldProduct) {
                if (product.batchNumber.isNullOrBlank()) {
                    errors.add("• Товар: $title (ID: ${product.id}) — для старой продукции укажите партию")
                }
                if (product.weight == null || product.weight == 0.0) {
                    errors.add("• Товар: $title (ID: ${product.id}) — для старой продукции укажите вес")
                }
            }
        }
        return errors
    }
}
