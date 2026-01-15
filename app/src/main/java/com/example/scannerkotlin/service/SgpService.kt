package com.example.scannerkotlin.service

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.mappers.DocumentMapper
import com.example.scannerkotlin.model.Document
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.Provider
import com.example.scannerkotlin.request.AddDocumentElementRequest
import com.example.scannerkotlin.request.BlankRequest
import com.example.scannerkotlin.request.CatalogDocumentListRequest
import com.example.scannerkotlin.request.GettingProvidersRequest
import com.example.scannerkotlin.request.NewDocumentRequest
import com.example.scannerkotlin.request.ProcessUpdateRequest
import com.example.scannerkotlin.request.ProviderRequest
import com.example.scannerkotlin.request.SgpRequest
import com.example.scannerkotlin.request.VariationsRequest
import com.example.scannerkotlin.response.FirstBlankResponse

import com.example.scannerkotlin.response.ErrorResponse
import com.example.scannerkotlin.response.GettingProvidersResponse
import com.example.scannerkotlin.response.SecondBlankResponse
import com.google.gson.Gson
import com.google.gson.internal.LinkedTreeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter


@Suppress("NAME_SHADOWING")
class SgpService(
    private val apiBitrix: ApiBitrix,
    private val userId: String?
) {
    data class ProcessResult(
        val additionalIds: List<Int>, // для прихода
        val writeOffItems: List<WriteOffItem> // для списания
    )

    data class WriteOffItem(
        val elementId: Int,
        val quantity: Double
    )

    private val documentMapper: DocumentMapper = DocumentMapper()

    private val gson = Gson()


    private suspend fun getProviders(): List<Provider> {
        val request = GettingProvidersRequest()

        return try {
            val response: GettingProvidersResponse = apiBitrix.getProviders(request)

            response.getAllProviders()

        } catch (e: Exception) {
            println("Ошибка при получении провайдеров: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    private suspend fun addProvider(documentId: Int?, entityId: Int?, context: Context): Boolean {
        if (entityId == null) {
            Log.i("SgpService", "Provider ID is null for document ID: $documentId. Skipping addProvider call.")
            return true
        }

        val request = ProviderRequest(
            fields = mutableMapOf(
                "documentId" to documentId,
                "entityTypeId" to 4,
                "entityId" to entityId
            )
        )

        Log.d("SgpService", "Attempting to add/link provider. Request: $request")

        return try {

            val response = withContext(Dispatchers.IO) {
                apiBitrix.addProvider(request)
            }

            if (response.isSuccessful) {
                val responseBody = response.body()
                if (responseBody != null && responseBody["error"] == null) {
                    Log.i("SgpService", "Successfully linked provider (Entity ID: $entityId) to document (Doc ID: $documentId). Response body: $responseBody")
                    true
                } else {
                    val errorMsg = responseBody?.get("error_description")?.toString()
                        ?: responseBody?.get("error")?.toString()
                        ?: "Unknown API error after successful HTTP (addProvider)"
                    Log.e("SgpService", "API error while linking provider (Entity ID: $entityId, Doc ID: $documentId): $errorMsg. Full response: $responseBody")
                    withContext(Dispatchers.Main) {
                        showAlertInfo("Ошибка API при привязке поставщика: $errorMsg", context)
                    }
                    false
                }
            } else {

                val errorBody = response.errorBody()?.string()
                val parsedError = try {

                    gson.fromJson(errorBody, ErrorResponse::class.java)?.errorDescription
                        ?: "HTTP error ${response.code()}"
                } catch (e: Exception) {
                    "HTTP error ${response.code()}: Failed to parse error body - $errorBody"
                }
                Log.e("SgpService", "Failed to link provider (Entity ID: $entityId, Doc ID: $documentId). Code: ${response.code()}, Parsed Error: $parsedError. Raw Error Body: $errorBody")
                withContext(Dispatchers.Main) {
                    showAlertInfo("Ошибка сети при привязке поставщика: $parsedError", context)
                }
                false
            }
        } catch (e: HttpException) {
            Log.e("SgpService", "HTTP exception while linking provider (Entity ID: $entityId, Doc ID: $documentId): ${e.code()} - ${e.message()}", e)
            withContext(Dispatchers.Main) {
                showAlertInfo("Сетевая ошибка (HTTP) при привязке поставщика.", context)
            }
            false
        } catch (e: IOException) {
            Log.e("SgpService", "Network I/O error while linking provider (Entity ID: $entityId, Doc ID: $documentId): ${e.message}", e)
            withContext(Dispatchers.Main) {
                showAlertInfo("Ошибка сети (I/O) при привязке поставщика.", context)
            }
            false
        } catch (e: Exception) {
            Log.e("SgpService", "Generic error while linking provider (Entity ID: $entityId, Doc ID: $documentId): ${e.message}", e)
            withContext(Dispatchers.Main) {
                showAlertInfo("Непредвиденная ошибка при привязке поставщика.", context)
            }
            false
        }
    }

    private suspend fun updateQuantityInFirstBlanks(blankId: Int, newQuantity: Double?) {


        val request = ProcessUpdateRequest(
            entityTypeId = 1130,
            id = blankId,
            fields = mutableMapOf(
                "ufCrm24_1748358413" to newQuantity
            )
        )
        try {
            apiBitrix.updateProcesses(request)
            Log.i("UpdateQuantity", "FirstBlank ID: $blankId успешно обновлен.")
        } catch (e: Exception) {
            Log.e("UpdateQuantity", "Ошибка при обновлении FirstBlank ID: $blankId: ${e.message}", e)
        }
    }


    private suspend fun updateQuantityInSecondBlanks(blankId: Int, newQuantity: Double?) {


        val request = ProcessUpdateRequest(
            entityTypeId = 1134,
            id = blankId,
            fields = mutableMapOf(
                "ufCrm25_1748356055518" to newQuantity
            )
        )
        try {

            apiBitrix.updateProcesses(request)
            Log.i("UpdateQuantity", "SecondBlank ID: $blankId успешно обновлен.")
        } catch (e: Exception) {
            Log.e("UpdateQuantity", "Ошибка при обновлении SecondBlank ID: $blankId: ${e.message}", e)
        }
    }


    suspend fun updateBlanks(products: MutableList<Product>) {
        coroutineScope {
            val firstBlanksDeferred = async { getFirstBlanks() }
            val secondBlanksDeferred = async { getSecondBlanks() }

            val firstBlanksList = firstBlanksDeferred.await()
            val secondBlanksList = secondBlanksDeferred.await()


            val firstBlanksMap = firstBlanksList.associateBy { it.ufSomeNumericId }
            val secondBlanksMap = secondBlanksList.associateBy { it.ufSomeNumericId }

            Log.d("UpdateBlanks", "Получено ${firstBlanksMap.size} элементов для firstBlanks. Map ключи: ${firstBlanksMap.keys}")
            Log.d("UpdateBlanks", "Получено ${secondBlanksMap.size} элементов для secondBlanks. Map ключи: ${secondBlanksMap.keys}")

            for (product in products) {
                var matchFoundAndProcessed = false


                firstBlanksMap[product.id]?.let { matchedBlank ->
                    matchFoundAndProcessed = true
                    Log.i("UpdateBlanks", "MATCH: Product ID ${product.id} (${product.name}) найден в firstBlanks. Blank ID: ${matchedBlank.id}.")

                    if (matchedBlank.ufSomeStringId.toInt() != 0) {
                        updateQuantityInFirstBlanks(
                            matchedBlank.id,
                            (matchedBlank.ufSomeStringId.toInt() - 1).toDouble()
                        )
                    }
                }

                product.parentId?.let { pId ->
                    firstBlanksMap[pId]?.let { matchedBlankByParent ->
                        matchFoundAndProcessed = true
                        Log.i("UpdateBlanks", "MATCH: Product ParentID $pId (для продукта ${product.name} ID ${product.id}) найден в firstBlanks. Blank ID: ${matchedBlankByParent.id}")

                        if (matchedBlankByParent.ufSomeStringId.toInt() != 0) {
                            updateQuantityInFirstBlanks(
                                matchedBlankByParent.id,
                                (matchedBlankByParent.ufSomeStringId.toInt() - 1).toDouble()
                            )
                        }
                    }
                }


                secondBlanksMap[product.id]?.let { matchedBlank ->
                    matchFoundAndProcessed = true
                    Log.i("UpdateBlanks", "MATCH: Product ID ${product.id} (${product.name}) найден в secondBlanks. Blank ID: ${matchedBlank.id}")

                    if (matchedBlank.ufSomeStringId.toInt() != 0) {
                        updateQuantityInSecondBlanks(
                            matchedBlank.id,
                            (matchedBlank.ufSomeStringId.toInt() - 1).toDouble()
                        )
                    }
                }


                product.parentId?.let { pId ->
                    secondBlanksMap[pId]?.let { matchedBlankByParent ->
                        matchFoundAndProcessed = true
                        Log.i("UpdateBlanks", "MATCH: Product ParentID $pId (для продукта ${product.name} ID ${product.id}) найден в secondBlanks. Blank ID: ${matchedBlankByParent.id}")

                        if (matchedBlankByParent.ufSomeStringId.toInt() != 0) {
                            updateQuantityInSecondBlanks(
                                matchedBlankByParent.id,
                                (matchedBlankByParent.ufSomeStringId.toInt() - 1).toDouble()
                            )
                        }
                    }
                }

                if (matchFoundAndProcessed) {
                    Log.d("UpdateBlanks", "Для продукта ${product.name} (ID: ${product.id}) было найдено и обработано хотя бы одно совпадение.")
                } else {
                    Log.d("UpdateBlanks", "Для продукта ${product.name} (ID: ${product.id}, ParentID: ${product.parentId}) совпадений не найдено или они не были обработаны.")
                }
            }
        }
    }



    private suspend fun getFirstBlanks(): MutableList<FirstBlankResponse.BlankItem> {

        val combinedBlankItems = mutableListOf<FirstBlankResponse.BlankItem>()

        coroutineScope {
            val request = BlankRequest(entityTypeId = 1130)



            val responseDeferred = async { apiBitrix.getFirstBlanks(request) }

            try {

                val apiResponse = responseDeferred.await()

                apiResponse.result.items.let { items ->
                    combinedBlankItems.addAll(items)
                }
                Log.d("GetBlanks", "Получено ${apiResponse.result.items.size ?: 0} элементов из первого запроса (entityTypeId: 1130)")

            } catch (e: Exception) {

                Log.e("GetBlanks", "Ошибка при получении заготовок для entityTypeId 1130: ${e.message}", e)

            }
        }

        Log.d("GetBlanks", "Всего получено ${combinedBlankItems.size} элементов.")
        return combinedBlankItems
    }

    private suspend fun getSecondBlanks(): MutableList<SecondBlankResponse.BlankItem> {

        val combinedBlankItems = mutableListOf<SecondBlankResponse.BlankItem>()

        coroutineScope {
            val request = BlankRequest(entityTypeId = 1134)



            val responseDeferred = async { apiBitrix.getSecondBlanks(request) }

            try {

                val apiResponse = responseDeferred.await()

                apiResponse.result.items.let { items ->
                    combinedBlankItems.addAll(items)
                }
                Log.d("GetBlanks", "Получено ${apiResponse.result.items.size ?: 0} элементов из второго запроса (entityTypeId: 1134)")

            } catch (e: Exception) {

                Log.e("GetBlanks", "Ошибка при получении сырья для entityTypeId 1130: ${e.message}", e)

            }
        }

        Log.d("GetBlanks", "Всего получено ${combinedBlankItems.size} элементов.")
        return combinedBlankItems
    }




    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun getDocumentsSuspend(id: List<Int?>?): List<Document> {
        val documentMapper = DocumentMapper()
        val requestWithParams = CatalogDocumentListRequest(
            filter = mutableMapOf(
                "id" to id,
                "status" to "N",
                "docType" to "A"
            )
        )
        return withContext(Dispatchers.IO) {
            try {
                Log.d("ComingService", "Requesting documents list...")
                val response = apiBitrix.getDocumentListSuspend(requestWithParams)

                if (response.isSuccessful) {
                    val rawDocuments = response.body()?.result?.get("documents") as? List<*>
                        ?: run {
                            Log.w("ComingService", "Documents list is null or not a list in response")
                            return@withContext emptyList<Document>()
                        }

                    rawDocuments.mapNotNull { documentMap ->
                        try {
                            documentMapper.mapToDocument(documentMap as LinkedTreeMap<*, *>)
                        } catch (e: Exception) {
                            Log.e("ComingService", "Error mapping document", e)
                            null
                        }
                    }.also { Log.d("ComingService", "Loaded ${it.size} documents") }
                } else {
                    Log.e("ComingService", "getDocumentsSuspend failed: ${response.code()} - ${response.message()}")
                    emptyList()
                }
            } catch (e: HttpException) {
                Log.e("ComingService", "HTTP error in getDocumentsSuspend: ${e.code()} - ${e.message()}", e)
                emptyList()
            } catch (e: IOException) {
                Log.e("ComingService", "Network error in getDocumentsSuspend", e)
                emptyList()
            } catch (e: Exception) {
                Log.e("ComingService", "Failed to load documents", e)
                emptyList()
            }
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun  spendDocument(products: List<Product>, context: Context): Boolean {
        if (products.isEmpty()) {
            Log.w("SgpService", "Product list is empty.")
            withContext(Dispatchers.Main) { showAlertInfo("Список товаров пуст. Документ не будет создан.", context) }
            return false
        }

//        val newDocument = addNewDocument()
//        if (newDocument?.id == null) {
//            Log.e("SgpService", "Failed to create a new document.")
//            if (newDocument == null) {
//                withContext(Dispatchers.Main) { showAlertInfo("Не удалось создать новый документ. Операция прервана.", context) }
//            }
//            return false
//        }
        val currentDate = LocalDate.now()


        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")


        val formattedDate = currentDate.format(formatter)



        val request = SgpRequest(
            filter = mutableMapOf(
                "field702" to "1",
                "documentType" to "A",
                "field703" to formattedDate
            )
        )

        val result = apiBitrix.getDocumentSgp(request).body()?.result
        val id = result?.documents?.map { x -> x.documentId }


        val documentsSuspend = getDocumentsSuspend(id);
        val docId = documentsSuspend[0].id


        val processResult = processProductOffers(products, context)
        if (processResult.additionalIds.isNotEmpty()) {
            Log.i("SgpService", "Received ${processResult.additionalIds.size} additional IDs from product offers")
        }
        if (processResult.writeOffItems.isNotEmpty()) {
            Log.i("SgpService", "Received ${processResult.writeOffItems.size} write-off items")
        }


        var allElementsAddedSuccessfully = true
        

        for (product in products) {
            val request = AddDocumentElementRequest(
                fields = mutableMapOf(
                    "docId" to docId, "storeFrom" to 0, "storeTo" to 1,
                    "elementId" to product.id, "amount" to 1,
                    "purchasingPrice" to 0.0
                )
            )
            try {
                val response = withContext(Dispatchers.IO) { apiBitrix.addDocumentElement(request) }
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body is Map<*, *> && body["error"] != null) {
                        val errorDesc = body["error_description"]?.toString() ?: body["error"].toString()
                        Log.e("SgpService", "API Error adding product '${product.name}': $errorDesc")
                        allElementsAddedSuccessfully = false
                        withContext(Dispatchers.Main) { showAlertInfo("Ошибка API при добавлении товара '${product.name}': $errorDesc", context) }
                        break
                    }
                    Log.i("SgpService", "Added product '${product.name}' (ID: ${product.id}) to doc $docId.")
                } else {
                    val errorBody = response.errorBody()?.string() ?: "No error body"
                    val parsedError = try { gson.fromJson(errorBody, ErrorResponse::class.java)?.errorDescription ?: "Код: ${response.code()}" } catch (e:Exception) { "Код: ${response.code()}"}
                    Log.e("SgpService", "Failed to add product '${product.name}'. Error: $parsedError")
                    allElementsAddedSuccessfully = false
                    withContext(Dispatchers.Main) { showAlertInfo("Ошибка при добавлении товара '${product.name}'. $parsedError", context) }
                    break
                }
            } catch (e: HttpException) {  allElementsAddedSuccessfully = false; withContext(Dispatchers.Main) { showAlertInfo("Сетевая ошибка (HTTP) при добавлении товара '${product.name}'.", context) }; break }
            catch (e: IOException) {  allElementsAddedSuccessfully = false; withContext(Dispatchers.Main) { showAlertInfo("Ошибка сети (I/O) при добавлении товара '${product.name}'.", context) }; break }
            catch (e: Exception) {allElementsAddedSuccessfully = false; withContext(Dispatchers.Main) { showAlertInfo("Непредвиденная ошибка при добавлении товара '${product.name}'.", context) }; break }
        }

        if (!allElementsAddedSuccessfully) {
            Log.w("SgpService", "Not all products added to doc $docId. Aborting.")
            return false
        }
        Log.i("SgpService", "All products added to doc $docId.")

        
        if (processResult.additionalIds.isNotEmpty()) {
            for (additionalId in processResult.additionalIds) {
                val request = AddDocumentElementRequest(
                    fields = mutableMapOf(
                        "docId" to docId, "storeFrom" to 0, "storeTo" to 1,
                        "elementId" to additionalId, "amount" to 1,
                        "purchasingPrice" to 0.0
                    )
                )
                try {
                    val response = withContext(Dispatchers.IO) { apiBitrix.addDocumentElement(request) }
                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body is Map<*, *> && body["error"] != null) {
                            val errorDesc = body["error_description"]?.toString() ?: body["error"].toString()
                            Log.e("SgpService", "API Error adding additional element ID $additionalId: $errorDesc")
                        } else {
                            Log.i("SgpService", "Added additional element ID $additionalId to doc $docId.")
                        }
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "No error body"
                        Log.e("SgpService", "Failed to add additional element ID $additionalId. Error: $errorBody")
                    }
                } catch (e: Exception) {
                    Log.e("SgpService", "Error adding additional element ID $additionalId", e)
                }
            }
        }


        if (processResult.writeOffItems.isNotEmpty()) {
            addToWriteOffDocument(processResult.writeOffItems, context)
        }

//        val providerId = getProviders().first().id
//
//        if (providerId != null || docId != null) {
//            Log.i("SgpService", "Attempting to link provider ID: $providerId to doc ID: $docId")
//            val providerLinkedSuccessfully = addProvider(documentId = docId, entityId = providerId, context = context)
//            if (!providerLinkedSuccessfully) {
//                Log.e("SgpService", "Failed to link provider ID: $providerId to doc $docId. Aborting conduct.")
//
//                return false
//            } else {
//                Log.i("SgpService", "Provider ID: $providerId linked to doc ID: $docId.")
//            }
//        } else {
//            Log.i("SgpService", "No providerId for doc ID: $docId. Skipping provider linking.")
//        }
        return true
    }

    /**
     * Получает дополнительные ID через catalog.product.offer.list с рекурсивными запросами
     */
    private suspend fun processProductOffers(products: List<Product>, context: Context): ProcessResult {
        val allAdditionalIds = mutableListOf<Int>()
        val allWriteOffItems = mutableListOf<WriteOffItem>()
        
        for (product in products) {
            try {
                Log.i("SgpService", "Processing product: ${product.name} (ID: ${product.id})")
                
                // Первый запрос - получаем property81
                val firstResult = getProductOffers(product.id)
                if (firstResult.isEmpty()) {
                    Log.w("SgpService", "No offers found for product ${product.id}")
                    continue
                }
                

                val isKit = firstResult.size > 1
                Log.i("SgpService", "Product ${product.id} is ${if (isKit) "KIT" else "PRODUCT"} (${firstResult.size} items)")
                
                val processedResult = if (isKit) {
                    processKit(product.id, firstResult)
                } else {
                    processProduct(product.id, firstResult)
                }
                
                allAdditionalIds.addAll(processedResult.additionalIds)
                allWriteOffItems.addAll(processedResult.writeOffItems)
                
            } catch (e: Exception) {
                Log.e("SgpService", "Error processing product ${product.id}", e)
            }
        }
        
        Log.i("SgpService", "Total additional IDs: ${allAdditionalIds.size}, write-off items: ${allWriteOffItems.size}")
        return ProcessResult(allAdditionalIds, allWriteOffItems)
    }
    


    /**
     * Получает предложения продукта (1 запрос)
     */
    private suspend fun getProductOffers(productId: Int): List<Int> {
        return try {
            val request = VariationsRequest(
                select = listOf("id", "property81", "iblockId"),
                filter = mapOf(
                    "iblockId" to 15,
                    "id" to productId
                )
            )
            
            val response = withContext(Dispatchers.IO) { apiBitrix.getVariations(request) }
            
            if (response.isSuccessful) {
                val offers = response.body()?.result?.offers ?: emptyList()
                if (offers.isNotEmpty()) {
                    val offer = offers[0]
                    val property81 = offer["property81"] as? List<Map<String, Any>>
                    
                    if (!property81.isNullOrEmpty()) {
                        val values = property81.mapNotNull { item ->
                            (item["value"] as? String)?.toIntOrNull()
                        }
                        Log.i("SgpService", "Retrieved ${values.size} values from property81 for product $productId")
                        values
                    } else {
                        Log.w("SgpService", "property81 is null or empty for product $productId")
                        emptyList()
                    }
                } else {
                    Log.w("SgpService", "No offers found for product $productId")
                    emptyList()
                }
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error body"
                Log.e("SgpService", "Failed to get product offers: ${response.code()} - $errorBody")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("SgpService", "Error getting product offers for $productId", e)
            emptyList()
        }
    }

    /**
     * Получает количество для списания из property82
     */
    private suspend fun getQuantityForWriteOff(elementId: Int): Double {
        return try {
            val request = VariationsRequest(
                select = listOf("id", "property82", "iblockId"),
                filter = mapOf(
                    "iblockId" to 15,
                    "id" to elementId
                )
            )
            
            val response = withContext(Dispatchers.IO) { apiBitrix.getVariations(request) }
            
            if (response.isSuccessful) {
                val offers = response.body()?.result?.offers ?: emptyList()
                if (offers.isNotEmpty()) {
                    val offer = offers[0]
                    val property82 = offer["property82"] as? Map<String, Any>
                    
                    if (property82 != null) {
                        val value = property82["value"] as? String
                        val quantity = value?.toDouble() ?: 1.0
                        Log.i("SgpService", "Retrieved quantity $quantity from property82 for element $elementId")
                        quantity
                    } else {
                        Log.w("SgpService", "property82 is null for element $elementId, using default quantity 1.0")
                        1.0
                    }
                } else {
                    Log.w("SgpService", "No offers found for element $elementId, using default quantity 1.0")
                    1.0
                }
            } else {
                val errorBody = response.errorBody()?.string() ?: "No error body"
                Log.e("SgpService", "Failed to get quantity: ${response.code()} - $errorBody")
                1.0
            }
        } catch (e: Exception) {
            Log.e("SgpService", "Error getting quantity for element $elementId", e)
            1.0
        }
    }
    
    /**
     * Обрабатывает продукт (2 запроса)
     */
    private suspend fun processProduct(productId: Int, firstLevelIds: List<Int>): ProcessResult {
        val additionalIds = mutableListOf<Int>()
        val writeOffItems = mutableListOf<WriteOffItem>()
        
        // Добавляем заготовки в приход (основной продукт уже добавлен в spendDocument)
        additionalIds.addAll(firstLevelIds)
        
        // Второй запрос - получаем сырье для каждой заготовки
        for (firstLevelId in firstLevelIds) {
            try {
                val secondLevelIds = getProductOffers(firstLevelId)
                if (secondLevelIds.isNotEmpty()) {


                    

                    writeOffItems.add(WriteOffItem(firstLevelId, 1.0))
                    var rawQuantity: Double
                    // Добавляем сырье в списание (с количеством из property82)
                    for (secondLevelId in secondLevelIds) {
                        rawQuantity = getQuantityForWriteOff(secondLevelId)
                        writeOffItems.add(WriteOffItem(secondLevelId, rawQuantity))
                    }
                    
                    Log.i("SgpService", "Added 1 first level item (qty=1) and ${secondLevelIds.size} second level items (qty=$)")
                } else {
                    // Если property81 пустой, значит это сырье - добавляем его в списание с количеством из property82
                    val rawQuantity = getQuantityForWriteOff(firstLevelId)
                    writeOffItems.add(WriteOffItem(firstLevelId, rawQuantity))
                    Log.i("SgpService", "Added raw material item (qty=$rawQuantity) for $firstLevelId")
                }
            } catch (e: Exception) {
                Log.e("SgpService", "Error processing second level for $firstLevelId", e)
            }
        }
        
        return ProcessResult(additionalIds, writeOffItems)
    }
    
    /**
     * Обрабатывает комплект (3 запроса)
     */
    private suspend fun processKit(kitId: Int, firstLevelIds: List<Int>): ProcessResult {
        val additionalIds = mutableListOf<Int>()
        val writeOffItems = mutableListOf<WriteOffItem>()
        

        additionalIds.addAll(firstLevelIds)
        
        // Второй запрос - получаем заготовки
        for (firstLevelId in firstLevelIds) {
            try {
                val secondLevelIds = getProductOffers(firstLevelId)
                if (secondLevelIds.isNotEmpty()) {
                    additionalIds.addAll(secondLevelIds)
                    
                    // Добавляем продукты в списание (по 1)
                    writeOffItems.add(WriteOffItem(firstLevelId, 1.0))
                    
                    // Третий запрос - получаем сырье
                    for (secondLevelId in secondLevelIds) {
                        try {
                            val thirdLevelIds = getProductOffers(secondLevelId)
                            if (thirdLevelIds.isNotEmpty()) {
                                // Получаем количество для списания сырья
                                var rawQuantity: Double
                                
                                // Добавляем заготовки в списание (по 1)
                                writeOffItems.add(WriteOffItem(secondLevelId, 1.0))
                                

                                for (thirdLevelId in thirdLevelIds) {
                                    rawQuantity = getQuantityForWriteOff(secondLevelId)
                                    writeOffItems.add(WriteOffItem(thirdLevelId, rawQuantity))
                                }
                                
//                                Log.i("SgpService", "Added 1 second level item (qty=1) and ${thirdLevelIds.size} third level items (qty=$rawQuantity)")
                            } else {
                                // Если property81 пустой, значит это сырье - добавляем его в списание с количеством из property82
                                val rawQuantity = getQuantityForWriteOff(secondLevelId)
                                writeOffItems.add(WriteOffItem(secondLevelId, rawQuantity))
                                Log.i("SgpService", "Added raw material item (qty=$rawQuantity) for $secondLevelId")
                            }
                        } catch (e: Exception) {
                            Log.e("SgpService", "Error processing third level for $secondLevelId", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SgpService", "Error processing second level for $firstLevelId", e)
            }
        }
        
        return ProcessResult(additionalIds, writeOffItems)
    }
    


    /**
     * Добавляет элементы в документ списания с количеством
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun addToWriteOffDocument(writeOffItems: List<WriteOffItem>, context: Context) {
        try {
            val currentDate = LocalDate.now()


            val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")


            val formattedDate = currentDate.format(formatter)


            val request = SgpRequest(
                filter = mutableMapOf(
                    "field756" to "2",
                    "documentType" to "D",
                    "field755" to formattedDate
                )
            )

            val result = apiBitrix.getDocumentSgp(request).body()?.result
            val id = result?.documents?.map { x -> x.documentId }

            if (id.isNullOrEmpty()) {
                Log.w("SgpService", "No write-off document found")
                return
            }

//            val documentsSuspend = getDocumentsSuspend(id)
//            val writeOffDocId = documentsSuspend[0].id

//            Log.i("SgpService", "Adding ${writeOffItems.size} items to write-off document $writeOffDocId")

            for (writeOffItem in writeOffItems) {
                val request = AddDocumentElementRequest(
                    fields = mutableMapOf(
                        "docId" to id[0], "storeFrom" to 1, "storeTo" to 1,
                        "elementId" to writeOffItem.elementId, "amount" to writeOffItem.quantity,
                        "purchasingPrice" to 0.0
                    )
                )
                
                try {
                    val response = withContext(Dispatchers.IO) { apiBitrix.addDocumentElement(request) }
                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body is Map<*, *> && body["error"] != null) {
                            val errorDesc = body["error_description"]?.toString() ?: body["error"].toString()
                            Log.e("SgpService", "API Error adding to write-off document ID ${writeOffItem.elementId}: $errorDesc")
                        } else {
//                            Log.i("SgpService", "Added ID ${writeOffItem.elementId} with quantity ${writeOffItem.quantity} to write-off document $writeOffDocId.")
                        }
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "No error body"
                        Log.e("SgpService", "Failed to add ID ${writeOffItem.elementId} to write-off document. Error: $errorBody")
                    }
                } catch (e: Exception) {
                    Log.e("SgpService", "Error adding ID ${writeOffItem.elementId} to write-off document", e)
                }
            }
        } catch (e: Exception) {
            Log.e("SgpService", "Error in addToWriteOffDocument", e)
        }
    }




    private suspend fun conductDocSuspend(idDocument: Int, context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("ComingService", "Attempting to conduct document $idDocument")
                val response = apiBitrix.conductDocument(idDocument)
                if (response.isSuccessful) {
                    Log.d("ComingService", "Document $idDocument conducted successfully.")
                    true
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e("ComingService", "Failed to conduct document $idDocument: ${response.code()} - $errorBody")
                    val errorDescription = try {
                        gson.fromJson(errorBody, ErrorResponse::class.java)?.errorDescription ?: "Unknown API error conducting document"
                    } catch (e: Exception) { "Failed to parse conduct error response" }

                    withContext(Dispatchers.Main) {
                        showAlertInfo(errorDescription, context)
                    }
                    false
                }
            } catch (e: Exception) {
                Log.e("ComingService", "Error conducting document $idDocument", e)
                withContext(Dispatchers.Main) {
                    showAlertInfo("Network or unexpected error conducting document.", context)
                }
                false
            }
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun addNewDocument(): Document? {
        if (userId?.isBlank() == true) {
            Log.w("ApiService", "Cannot add new document: userId is null or blank.")
            return null
        }

        val request = NewDocumentRequest(
            fields = mutableMapOf(
                "docType" to "A",
                "currency" to "BYN",
                "responsibleId" to userId
            )
        )

        return withContext(Dispatchers.IO) {
            try {
                Log.d("SgpService", "Adding new document with request: $request")
                val response: Response<HashMap<String, Any?>> = apiBitrix.addNewDocument(request)

                if (response.isSuccessful) {
                    val responseBodyMap: HashMap<String, Any?>? = response.body()

                    if (responseBodyMap != null) {
                        val resultMap = responseBodyMap["result"] as? Map<*, *>
                        if (resultMap != null) {

                            val documentDataMap = resultMap["document"] as? LinkedTreeMap<*, *>


                            if (documentDataMap != null) {

                                val mappedDocument = documentMapper.mapToDocument(documentDataMap)

                                if (mappedDocument != null) {
                                    if (mappedDocument.id != null) {
                                        Log.i(
                                            "SgpService",
                                            "New document created and mapped successfully: ${mappedDocument.title} (ID: ${mappedDocument.id})"
                                        )
                                        return@withContext mappedDocument
                                    } else {
                                        Log.e(
                                            "SgpService",
                                            "Document mapped, but ID is missing. Mapped document: $mappedDocument"
                                        )
                                        return@withContext null
                                    }
                                } else {
                                    Log.e(
                                        "SgpService",
                                        "Failed to map document data. DocumentDataMap was: $documentDataMap"
                                    )
                                    return@withContext null
                                }
                            } else {
                                Log.e(
                                    "SgpService",
                                    "API_RESPONSE_ERROR: 'document' field is missing or not a map in 'result'. Result map: $resultMap"
                                )
                                return@withContext null
                            }
                        } else {
                            Log.e(
                                "SgpService",
                                "API_RESPONSE_ERROR: 'result' field is missing or not a map in the response body. Body: $responseBodyMap"
                            )
                            return@withContext null
                        }
                    } else {
                        Log.e(
                            "SgpService",
                            "API_RESPONSE_ERROR: Response body is null despite successful HTTP response."
                        )
                        return@withContext null
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(
                        "SgpService",
                        "addNewDocument API call failed: ${response.code()} - ${response.message()}. Error body: $errorBody"
                    )
                    return@withContext null
                }
            } catch (e: HttpException) {
                Log.e("SgpService", "HTTP error in addNewDocument: ${e.code()} - ${e.message()}", e)
                return@withContext null
            } catch (e: IOException) {
                Log.e("SgpService", "Network error in addNewDocument: ${e.message}", e)
                return@withContext null
            } catch (e: ClassCastException) {
                Log.e(
                    "SgpService",
                    "Data parsing error (ClassCastException) in addNewDocument: ${e.message}",
                    e
                )
                return@withContext null
            } catch (e: Exception) {

                Log.e("SgpService", "Generic error in addNewDocument: ${e.message}", e)
                return@withContext null
            }
        }
    }


    private fun showAlertInfo(message: String, context: Context) {
        AlertDialog.Builder(context).apply {
            setTitle("Предупреждение")
            setMessage(message)
            setCancelable(false)
            setPositiveButton("Закрыть") { dialog, _ ->
                dialog.cancel()
            }
        }.create().show()
    }

}