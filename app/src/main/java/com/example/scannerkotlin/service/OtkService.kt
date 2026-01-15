package com.example.scannerkotlin.service

import android.util.Log
import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.request.ProcessUpdateRequest
import com.example.scannerkotlin.request.ProcessesRequest
import com.example.scannerkotlin.response.ProcessesResponse
import com.example.scannerkotlin.utils.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

class OtkService {

    private val baseUrl = "https://bitrix.izocom.by/rest/1/o2deu7wx7zfl3ib4/"
    private var apiBitrix: ApiBitrix


    init {
        val retrofit: Retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        apiBitrix = retrofit.create(ApiBitrix::class.java)
    }


    suspend fun getProcessByBarcode(barcode: String): ProcessesResponse? {
        val request = ProcessesRequest(
            entityTypeId = 1070,
            filter = mutableMapOf(
                "ufCrm10_1747122173398" to barcode,
                "stageId" to "DT1070_14:NEW"
            )
        )

        return try {
            Log.d(
                "ProcessRepository",
                "Requesting processes for barcode: $barcode with request: $request"
            )
            val response = withContext(Dispatchers.IO) {
                apiBitrix.getProcesses(request)
            }
            Log.d("ProcessRepository", "Received response: ${response.total} items found.")

            if (response.result?.items != null) {
                Log.d("ProcessRepository", "First item: ${response.result.items.firstOrNull()}")
            }
            response
        } catch (e: HttpException) {

            val errorBody = e.response()?.errorBody()?.string()
            Log.e(
                "ProcessRepository",
                "HTTP error fetching processes: ${e.code()} - ${e.message()}. Body: $errorBody",
                e
            )
            null
        } catch (e: IOException) {

            Log.e("ProcessRepository", "Network error fetching processes: ${e.message}", e)
            null
        } catch (e: Exception) {

            Log.e("ProcessRepository", "Generic error fetching processes: ${e.message}", e)
            null
        }
    }

    suspend fun updateItem(id: Int?, marriage: String? = null) {

        var request: ProcessUpdateRequest? = null
        if (marriage == null) {
            request = ProcessUpdateRequest(
                id = id,
                fields = mutableMapOf(
                    "stageId" to "DT1070_14:SUCCESS",
                    "assignedById" to SessionManager.getUserId()

                )
            )
        } else {
            request = ProcessUpdateRequest(
                id = id,
                fields = mutableMapOf(
                    "ufCrm10_1737917903883" to marriage,
                    "stageId" to "DT1070_14:FAIL",
                    "assignedById" to SessionManager.getUserId()
                )
            )
        }

        apiBitrix.updateProcesses(request)
    }


}