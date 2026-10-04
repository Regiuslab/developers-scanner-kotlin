package com.example.scannerkotlin.service

import android.util.Log
import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.config.BitrixClientFactory
import com.example.scannerkotlin.config.BitrixConfig
import com.example.scannerkotlin.dto.UserCredentials
import com.example.scannerkotlin.request.PasswordRequest
import com.example.scannerkotlin.response.PasswordResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import retrofit2.Response



class UserService {

    private val apiBitrix: ApiBitrix = BitrixClientFactory.catalogApi()


    suspend fun getPasswords(passwordToSearch: String): List<UserCredentials>? {
        return try {
            withContext(Dispatchers.IO) {

                val request = PasswordRequest(
                    iblockId = BitrixConfig.iblockPin,
                    iblockTypeId = "lists",
                    filter = mapOf(
                        "PROPERTY_71" to mapOf(
                            "1451" to passwordToSearch
                        )
                    )
                )


                Log.d("User", "check")

                val response = apiBitrix.getPasswords(request).execute()

                Log.d("User", "${response.body()?.result?.get(0)}")


                processResponse(response)
            }
        }catch (e: HttpException) {

            val errorBody = e.response()?.errorBody()?.string() ?: "No error body available"
            Log.e("UserService", "HttpException for '$passwordToSearch': ${e.code()} - ${e.message()}. Error Body: $errorBody", e)
            null
        } catch (e: java.io.IOException) {

            Log.e("UserService", "IOException for '$passwordToSearch': ${e.message}", e)
            null
        } catch (e: com.google.gson.JsonSyntaxException) {

            Log.e("UserService", "JsonSyntaxException (Gson parsing error) for '$passwordToSearch': ${e.message}", e)
            null
        }
        catch (e: Exception) {
            Log.e("UserService", "Generic Exception for '$passwordToSearch'. Type: ${e::class.java.simpleName}, Message: ${e.message}", e)

            null
        }
    }


    private fun processResponse(response: Response<PasswordResponse>): List<UserCredentials>? {
        return if (response.isSuccessful) {
            val responseBody = response.body()
            if (responseBody?.result == null) {
                Log.d("Password Request", "Успешный ответ, но тело ответа или список result пуст (null).")
                null
            } else {
                Log.d("Password Request", "Успешно получено ${responseBody.result.size} элементов.")
                responseBody.result.mapNotNull { item ->
                    val id = item.getId()
                    val password = item.getPassword()
                    val role = item.getRole()


                    if (id != null && password != null && role != null) {
                        UserCredentials(id, password, role)
                    } else {
                        Log.w("Password Request", "Найден элемент с null ID ($id), паролем ($password) или ролью ($role): $item")
                        null
                    }
                }
            }
        } else {
            Log.d("Password Request", "Неуспешный ответ: ${response.code()} - ${response.message()}. Тело ошибки: ${response.errorBody()?.string()}")
            null
        }
    }
}