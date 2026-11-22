package com.example.scannerkotlin.config

import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.utils.TsdLogger
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Singleton factory for Bitrix REST clients sharing a single [OkHttpClient] connection pool
 * and dispatcher thread pool to prevent socket/thread/memory leaks across screen navigations.
 */
object BitrixClientFactory {

    private val apiCache = ConcurrentHashMap<String, ApiBitrix>()

    private val diagnosticInterceptor = Interceptor { chain ->
        val request = chain.request()
        val endpoint = request.url().pathSegments().lastOrNull().orEmpty()
        val startNs = System.nanoTime()
        try {
            val response = chain.proceed(request)
            val durationMs = (System.nanoTime() - startNs) / 1_000_000L
            TsdLogger.info(
                category = "NET",
                event = "bitrix_http",
                fields = mapOf(
                    "method" to request.method(),
                    "endpoint" to endpoint,
                    "code" to response.code(),
                    "ms" to durationMs
                )
            )
            response
        } catch (t: Throwable) {
            val durationMs = (System.nanoTime() - startNs) / 1_000_000L
            TsdLogger.warn(
                category = "NET",
                event = "bitrix_http_failed",
                fields = mapOf(
                    "method" to request.method(),
                    "endpoint" to endpoint,
                    "ms" to durationMs
                ),
                throwable = t
            )
            throw t
        }
    }

    private val sharedOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 2, TimeUnit.MINUTES))
            .addInterceptor(diagnosticInterceptor)
            .build()
    }

    fun catalogApi(): ApiBitrix = create(BitrixConfig.catalogBaseUrl)

    fun barcodeScanApi(): ApiBitrix = create(BitrixConfig.barcodeScanBaseUrl)

    fun barcodeComingApi(): ApiBitrix = create(BitrixConfig.barcodeComingBaseUrl)

    fun create(baseUrl: String): ApiBitrix {
        return apiCache.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(sharedOkHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiBitrix::class.java)
        }
    }
}
