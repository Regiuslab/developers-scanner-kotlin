package com.example.scannerkotlin.request

import com.example.scannerkotlin.config.BitrixConfig

data class VariationsRequest(
    val select: List<String> = listOf("id", "iblockId", "name", "quantity", "parentId", "measure"),
    val filter: Map<String, Any?> = mapOf(
        "iblockId" to BitrixConfig.iblockOffers
    )
)
