package com.example.scannerkotlin.utils

object MovingAvailabilityDisplay {
    const val STORES_NOT_LOADED = "Склады не загрузились"
    const val STORES_NOT_LOADED_ALERT =
        "Не удалось загрузить список складов. Проведение невозможно, откройте документ ещё раз."

    fun availableLabel(
        storesLoaded: Boolean,
        mainAmount: Double?,
        storeFrom: Int?
    ): String {
        if (!storesLoaded) {
            return STORES_NOT_LOADED
        }
        if (mainAmount != null) {
            return "Доступно: $mainAmount"
        }
        if (storeFrom != null) {
            return "Доступно: Загрузка..."
        }
        return "Доступно: Выберите склад"
    }
}
