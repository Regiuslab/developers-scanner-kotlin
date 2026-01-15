package com.example.scannerkotlin.response

import com.example.scannerkotlin.model.ProductOffer


data class ConductResult(val isSuccess: Boolean, val message: String? = null) {


    sealed class SaveResult {
        data class Success(val savedVariations: List<Pair<Int, ProductOffer>>) : SaveResult()
        data class Failure(val errorMessage: String) : SaveResult()
    }
}