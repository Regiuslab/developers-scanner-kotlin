package com.example.scannerkotlin.service

import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.config.BitrixClientFactory
import com.example.scannerkotlin.config.BitrixConfig
import com.example.scannerkotlin.dto.UserCredentials
import com.example.scannerkotlin.request.PasswordRequest
import com.example.scannerkotlin.response.PasswordResponse
import com.example.scannerkotlin.utils.TsdLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import retrofit2.Response

class UserService(
    private val apiBitrix: ApiBitrix = BitrixClientFactory.catalogApi()
) {

    suspend fun getPasswords(passwordToSearch: String): List<UserCredentials>? {
        return withContext(Dispatchers.IO) {
            val request = PasswordRequest(
                iblockId = BitrixConfig.iblockPin,
                iblockTypeId = "lists",
                filter = mapOf(
                    "PROPERTY_71" to mapOf(
                        "1451" to passwordToSearch
                    )
                )
            )
            TsdLogger.info(category = "AUTH", event = "pin_verify_requested")
            val response = apiBitrix.getPasswords(request).execute()
            processResponse(response)
        }
    }

    private fun processResponse(response: Response<PasswordResponse>): List<UserCredentials>? {
        if (!response.isSuccessful) {
            TsdLogger.warn(
                category = "AUTH",
                event = "pin_verify_http_error",
                fields = mapOf("code" to response.code())
            )
            throw HttpException(response)
        }
        val responseBody = response.body()
        val result = responseBody?.result
        if (result.isNullOrEmpty()) {
            TsdLogger.info(category = "AUTH", event = "pin_verify_no_match")
            return emptyList()
        }
        val credentials = result.mapNotNull { item ->
            val id = item.getId()
            val password = item.getPassword()
            val role = item.getRole()
            if (id != null && password != null && role != null) {
                UserCredentials(id, password, role)
            } else {
                TsdLogger.warn(
                    category = "AUTH",
                    event = "incomplete_credential_record",
                    fields = mapOf("hasId" to (id != null), "hasRole" to (role != null))
                )
                null
            }
        }
        TsdLogger.info(
            category = "AUTH",
            event = "pin_verify_matched",
            fields = mapOf("count" to credentials.size)
        )
        return credentials
    }
}
