package com.example.scannerkotlin.utils

import com.example.scannerkotlin.config.BitrixConfig
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.ValidationIssue

object ComingProductValidator {

    // Bitrix catalog product types that cannot have SKU variations in incoming warehouse flow:
    // 2 = TYPE_SET (комплект), 4 = TYPE_OFFER (уже вариация), 7 = TYPE_SERVICE (услуга).
    val UNSUPPORTED_VARIATION_PRODUCT_TYPES = setOf(2, 4, 7)

    fun isProductSupportingVariations(
        iblockId: Int,
        catalogProductType: Int?,
        expectedProductsIblockId: Int = BitrixConfig.iblockProducts,
        offersIblockId: Int = BitrixConfig.iblockOffers
    ): Boolean {
        if (iblockId <= 0) return false
        if (iblockId == offersIblockId) return false
        if (expectedProductsIblockId > 0 && iblockId != expectedProductsIblockId) return false
        if (catalogProductType != null && catalogProductType in UNSUPPORTED_VARIATION_PRODUCT_TYPES) {
            return false
        }
        return true
    }

    /**
     * Backward-compatible string validation list used by existing UI and unit tests.
     */
    fun validate(products: List<Product>): List<String> {
        return validateDetailed(
            products = products,
            documentId = 1,
            destinationStoreId = 1,
            expectedProductsIblockId = 0
        ).map { it.message }
    }

    /**
     * Comprehensive pre-validation of all positions before any mutating Bitrix API calls.
     */
    fun validateDetailed(
        products: List<Product>,
        documentId: Int?,
        destinationStoreId: Int = 1,
        expectedProductsIblockId: Int = BitrixConfig.iblockProducts
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        if (documentId == null || documentId <= 0) {
            issues.add(
                ValidationIssue(
                    positionIndex = -1,
                    localItemId = null,
                    productId = null,
                    field = "documentId",
                    message = "Не указан корректный ID документа прихода."
                )
            )
        }

        if (destinationStoreId <= 0) {
            issues.add(
                ValidationIssue(
                    positionIndex = -1,
                    localItemId = null,
                    productId = null,
                    field = "destinationStoreId",
                    message = "Не указан корректный склад назначения."
                )
            )
        }

        if (products.isEmpty()) {
            issues.add(
                ValidationIssue(
                    positionIndex = -1,
                    localItemId = null,
                    productId = null,
                    field = "products",
                    message = "Добавьте хотя бы один товар в документ"
                )
            )
            return issues
        }

        val firstSeenBarcodePos = HashMap<String, Int>()

        products.forEachIndexed { index, product ->
            val posNum = index + 1
            val title = product.name?.takeIf { it.isNotBlank() } ?: "Без названия"

            if (product.id <= 0) {
                issues.add(
                    ValidationIssue(
                        positionIndex = index,
                        localItemId = product.localItemId,
                        productId = product.id,
                        field = "productId",
                        message = "Позиция №$posNum ($title): не выбрана корректная номенклатура."
                    )
                )
            } else if (!isProductSupportingVariations(
                    iblockId = product.iblockId,
                    catalogProductType = product.catalogProductType,
                    expectedProductsIblockId = expectedProductsIblockId
                )
            ) {
                issues.add(
                    ValidationIssue(
                        positionIndex = index,
                        localItemId = product.localItemId,
                        productId = product.id,
                        field = "iblockId",
                        message = "Позиция №$posNum ($title): выбранный тип товара не поддерживает создание вариаций. Проверьте номенклатуру."
                    )
                )
            }

            val qty = product.quantity
            if (qty == null) {
                issues.add(
                    ValidationIssue(
                        positionIndex = index,
                        localItemId = product.localItemId,
                        productId = product.id,
                        field = "quantity",
                        message = "Позиция №$posNum ($title): Укажите количество"
                    )
                )
            } else if (qty.isNaN() || qty.isInfinite() || qty <= 0.0) {
                issues.add(
                    ValidationIssue(
                        positionIndex = index,
                        localItemId = product.localItemId,
                        productId = product.id,
                        field = "quantity",
                        message = "Позиция №$posNum ($title): укажите количество больше 0."
                    )
                )
            } else if (product.measureName == "Штука" && qty % 1.0 != 0.0) {
                issues.add(
                    ValidationIssue(
                        positionIndex = index,
                        localItemId = product.localItemId,
                        productId = product.id,
                        field = "quantity",
                        message = "Позиция №$posNum ($title): для единицы «Штука» количество должно быть целым числом."
                    )
                )
            }

            if (product.measureId == null || product.measureName.isNullOrBlank()) {
                issues.add(
                    ValidationIssue(
                        positionIndex = index,
                        localItemId = product.localItemId,
                        productId = product.id,
                        field = "measure",
                        message = "• Позиция №$posNum — Товар: $title (ID: ${product.id}) — не выбрана единица измерения"
                    )
                )
            }

            val sanitizedBarcode = BarcodeSanitizer.sanitize(product.barcode)
            if (sanitizedBarcode.isNullOrBlank()) {
                issues.add(
                    ValidationIssue(
                        positionIndex = index,
                        localItemId = product.localItemId,
                        productId = product.id,
                        field = "barcode",
                        message = "• Позиция №$posNum — Товар: $title (ID: ${product.id}) — не заполнен штрихкод"
                    )
                )
            } else if (sanitizedBarcode.length > BarcodeSanitizer.COMING_VARIATION_MAX_BARCODE_LENGTH) {
                issues.add(
                    ValidationIssue(
                        positionIndex = index,
                        localItemId = product.localItemId,
                        productId = product.id,
                        field = "barcode",
                        message = "Позиция №$posNum ($title): длина штрихкода превышает ${BarcodeSanitizer.COMING_VARIATION_MAX_BARCODE_LENGTH} символов."
                    )
                )
            } else {
                val earlierIndex = firstSeenBarcodePos.putIfAbsent(sanitizedBarcode, index)
                if (earlierIndex != null) {
                    issues.add(
                        ValidationIssue(
                            positionIndex = index,
                            localItemId = product.localItemId,
                            productId = product.id,
                            field = "barcode",
                            message = "Позиция №$posNum ($title): штрихкод '$sanitizedBarcode' уже указан в позиции №${earlierIndex + 1}. Устраните дублирование."
                        )
                    )
                }
            }

            if (product.isOldProduct) {
                if (product.batchNumber.isNullOrBlank()) {
                    issues.add(
                        ValidationIssue(
                            positionIndex = index,
                            localItemId = product.localItemId,
                            productId = product.id,
                            field = "batchNumber",
                            message = "• Позиция №$posNum — Товар: $title (ID: ${product.id}) — для старой продукции укажите партию"
                        )
                    )
                }
                val w = product.weight
                if (w == null || w <= 0.0) {
                    issues.add(
                        ValidationIssue(
                            positionIndex = index,
                            localItemId = product.localItemId,
                            productId = product.id,
                            field = "weight",
                            message = "• Позиция №$posNum — Товар: $title (ID: ${product.id}) — для старой продукции укажите вес"
                        )
                    )
                }
            }

            if ((product.diameter ?: 0.0) < 0.0 || (product.weight ?: 0.0) < 0.0 || (product.height ?: 0.0) < 0.0) {
                issues.add(
                    ValidationIssue(
                        positionIndex = index,
                        localItemId = product.localItemId,
                        productId = product.id,
                        field = "dimensions",
                        message = "Позиция №$posNum ($title): габариты и вес не могут быть отрицательными."
                    )
                )
            }

            if (product.destinationStoreId <= 0) {
                issues.add(
                    ValidationIssue(
                        positionIndex = index,
                        localItemId = product.localItemId,
                        productId = product.id,
                        field = "destinationStoreId",
                        message = "Позиция №$posNum ($title): не выбран склад назначения."
                    )
                )
            }
        }

        return issues
    }
}
