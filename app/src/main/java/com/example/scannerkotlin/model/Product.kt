package com.example.scannerkotlin.model

import com.example.scannerkotlin.response.StoreAmountResponse
import com.example.scannerkotlin.utils.ParentIdValueDeserializer
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.util.UUID

data class Product(
    var idInDocument: Int?,

    @SerializedName("id")
    val id: Int,

    @SerializedName("iblockId")
    val iblockId: Int,

    @SerializedName("name")
    var name: String?,

    @SerializedName("measureId")
    var measureId: Int?,

    @SerializedName("measureSymbol")
    var measureSymbol: String?,

    @SerializedName("quantity")
    var quantity: Double?,

    @SerializedName("barcode")
    var barcode: String?,

    @SerializedName("diameter")
    var diameter: Double?,

    @SerializedName("weight")
    var weight: Double?,

    @SerializedName("height")
    var height: Double?,

    @SerializedName("parentId")
    @JsonAdapter(ParentIdValueDeserializer::class)
    var parentId: Int?,

    var storeAmount: List<StoreAmountResponse.StoreProduct>?,

    var batchNumber: String? = null,

    var isOldProduct: Boolean = false
) {
    /**
     * Stable unique identifier of this line item inside a local warehouse document draft.
     * Prevents collisions when multiple positions share the same parent nomenclature [id].
     */
    var localItemId: String = UUID.randomUUID().toString()

    /**
     * Resolved or newly created Bitrix variation (offer) ID in iblockOffers.
     */
    var offerId: Int? = null

    /**
     * Optional Bitrix catalog product type (1=product, 2=set, 3=sku, 4=offer, 6=empty_sku, 7=service).
     */
    var catalogProductType: Int? = null

    /**
     * Destination warehouse ID for incoming operation (default 1 in Bitrix portal).
     */
    var destinationStoreId: Int = 1

    var itemStatus: ComingItemStatus = ComingItemStatus.PENDING

    var syncState: ComingSyncState = ComingSyncState.LOCAL_SAVED

    var validationError: String? = null

    var updatedAtMs: Long = System.currentTimeMillis()

    var measureName: String?
        get() = when (measureId) {
            1 -> "Метр"
            2 -> "Литр"
            3 -> "Грамм"
            4 -> "Килограмм"
            5 -> "Штука"
            else -> null
        }
        set(value) {
            measureId = when (value) {
                "Метр" -> 1
                "Литр" -> 2
                "Грамм" -> 3
                "Килограмм" -> 4
                "Штука" -> 5
                else -> null
            }
        }

    constructor() : this(
        idInDocument = null,
        id = 0,
        iblockId = 0,
        name = null,
        measureId = null,
        measureSymbol = null,
        quantity = null,
        barcode = null,
        diameter = 0.0,
        weight = 0.0,
        height = 0.0,
        parentId = null,
        storeAmount = null,
        batchNumber = null,
        isOldProduct = false
    )

    fun checkInList(products: List<Product>): Boolean {
        return products.any { it.id == this.id }
    }

    /**
     * Creates an isolated copy of this product so cached catalog items or other lines
     * in the same document never share mutable references.
     */
    fun detachedCopy(preserveLocalItemId: Boolean = true): Product {
        val cloned = this.copy(
            storeAmount = storeAmount?.toList()
        )
        cloned.localItemId = if (preserveLocalItemId) this.localItemId else UUID.randomUUID().toString()
        cloned.offerId = this.offerId
        cloned.catalogProductType = this.catalogProductType
        cloned.destinationStoreId = this.destinationStoreId
        cloned.itemStatus = this.itemStatus
        cloned.syncState = this.syncState
        cloned.validationError = this.validationError
        cloned.updatedAtMs = this.updatedAtMs
        return cloned
    }

    override fun toString(): String {
        return "Product(localItemId=$localItemId, idInDocument=$idInDocument, id=$id, offerId=$offerId, iblockId=$iblockId, type=$catalogProductType, name=$name, measureId=$measureId, measureName=$measureName, quantity=$quantity, barcode=$barcode, diameter=$diameter, weight=$weight, height=$height, parentId=$parentId, batchNumber=$batchNumber, isOldProduct=$isOldProduct, status=$itemStatus, sync=$syncState)"
    }
}
