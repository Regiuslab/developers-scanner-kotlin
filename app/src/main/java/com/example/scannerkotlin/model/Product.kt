package com.example.scannerkotlin.model

import com.example.scannerkotlin.response.StoreAmountResponse
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.example.scannerkotlin.utils.ParentIdValueDeserializer

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

    var storeAmount: List<StoreAmountResponse.StoreProduct>?
){

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
        storeAmount = null
    )

    fun checkInList(products: List<Product>): Boolean {

        return products.contains(this)
    }

    override fun toString(): String {
        return "Product(idInDocument=$idInDocument, id=$id, iblockId=$iblockId, name=$name, measureId=$measureId, measureSymbol=$measureSymbol, measureName=$measureName, quantity=$quantity, barcode=$barcode, diameter=$diameter, weight=$weight, height=$height, parentId=$parentId)"
    }
}