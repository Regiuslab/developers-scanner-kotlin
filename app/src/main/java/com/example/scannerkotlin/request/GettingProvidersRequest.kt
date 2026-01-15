package com.example.scannerkotlin.request

data class GettingProvidersRequest(
    val entityTypeId: Int = 4,
    val filter: Map<String, Any> = mapOf(
        "categoryId" to 5
    )
)