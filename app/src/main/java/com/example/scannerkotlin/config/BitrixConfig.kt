package com.example.scannerkotlin.config

import com.example.scannerkotlin.BuildConfig

/**
 * Tenant Bitrix24 settings from BuildConfig (Gradle → bitrix.properties / local.properties).
 * Rebuild the app after changing properties — no Kotlin edits required.
 */
object BitrixConfig {
    val host: String get() = BuildConfig.BITRIX_HOST
    val restUserId: String get() = BuildConfig.BITRIX_REST_USER_ID
    val tenantName: String get() = BuildConfig.BITRIX_TENANT_NAME

    val webhookCatalog: String get() = BuildConfig.BITRIX_WEBHOOK_CATALOG
    val webhookBarcodeScan: String get() = BuildConfig.BITRIX_WEBHOOK_BARCODE_SCAN
    val webhookBarcodeComing: String get() = BuildConfig.BITRIX_WEBHOOK_BARCODE_COMING

    val iblockPin: Int get() = BuildConfig.BITRIX_IBLOCK_PIN
    val iblockProducts: Int get() = BuildConfig.BITRIX_IBLOCK_PRODUCTS
    val iblockOffers: Int get() = BuildConfig.BITRIX_IBLOCK_OFFERS

    val roleStorekeeper: String get() = BuildConfig.BITRIX_ROLE_STOREKEEPER
    val roleOtk: String get() = BuildConfig.BITRIX_ROLE_OTK

    val catalogBaseUrl: String
        get() = webhookBaseUrl(webhookCatalog)

    val barcodeScanBaseUrl: String
        get() = webhookBaseUrl(webhookBarcodeScan)

    val barcodeComingBaseUrl: String
        get() = webhookBaseUrl(webhookBarcodeComing)

    private fun webhookBaseUrl(token: String): String =
        "https://$host/rest/$restUserId/$token/"
}
