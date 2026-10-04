package com.example.scannerkotlin.config

import com.example.scannerkotlin.api.ApiBitrix
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Single place to build Bitrix Retrofit clients from [BitrixConfig].
 */
object BitrixClientFactory {

    fun catalogApi(): ApiBitrix = create(BitrixConfig.catalogBaseUrl)

    fun barcodeScanApi(): ApiBitrix = create(BitrixConfig.barcodeScanBaseUrl)

    fun barcodeComingApi(): ApiBitrix = create(BitrixConfig.barcodeComingBaseUrl)

    fun create(baseUrl: String): ApiBitrix {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiBitrix::class.java)
    }
}
