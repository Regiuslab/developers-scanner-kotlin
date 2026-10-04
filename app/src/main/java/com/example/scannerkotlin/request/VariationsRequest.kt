package com.example.scannerkotlin.request

import com.example.scannerkotlin.config.BitrixConfig

data class VariationsRequest(
    val select: List<String> = listOf("id","iblockId", "name", "quantity"),
    val filter: Map<String, Int> = mapOf(
        "iblockId" to BitrixConfig.iblockOffers
    )
)