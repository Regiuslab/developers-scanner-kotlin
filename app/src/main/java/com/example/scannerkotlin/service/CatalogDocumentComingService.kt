package com.example.scannerkotlin.service

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.mappers.DocumentElementMapper
import com.example.scannerkotlin.mappers.DocumentMapper
import com.example.scannerkotlin.mappers.ProductMapper
import com.example.scannerkotlin.model.Document
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.ProductOffer
import com.example.scannerkotlin.request.* 
import com.example.scannerkotlin.response.* 
import com.google.gson.Gson
import com.google.gson.internal.LinkedTreeMap
import kotlinx.coroutines.* 
import retrofit2.HttpException
import retrofit2.Retrofit

import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException 

@RequiresApi(Build.VERSION_CODES.O) 
class CatalogDocumentComingService {
    private val baseUrl = "https://bitrix.izocom.by/rest/1/o2deu7wx7zfl3ib4/"
    private val barcodeBaseUrl = "https://bitrix.izocom.by/rest/1/sh1lchx64vrzcor6/"

    private var apiBitrix: ApiBitrix
    private var apiBarcode: ApiBitrix
    private val gson = Gson()

    
    private val documentMapper = DocumentMapper()
    private val documentElementsMapper = DocumentElementMapper()
    private val productMapper = ProductMapper()

    init {
        val retrofit: Retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        apiBitrix = retrofit.create(ApiBitrix::class.java)

        val barcodeRetrofit: Retrofit = Retrofit.Builder()
            .baseUrl(barcodeBaseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        apiBarcode = barcodeRetrofit.create(ApiBitrix::class.java)
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

    
    private suspend fun deleteProductsSuspend( 
        deletedProducts: List<Product>, 
        idDocument: Int,
        baseList: List<Product> 
    ): Boolean = coroutineScope {
        val productsToDelete = deletedProducts.filter { baseList.contains(it) && it.idInDocument != null }
        if (productsToDelete.isEmpty()) return@coroutineScope true

        Log.d("ComingService", "Deleting ${productsToDelete.size} products for document $idDocument")

        val deferredResults = productsToDelete.map { product ->
            async(Dispatchers.IO) { 
                try {
                    val request = DeletedDocumentElementRequest(
                        id = product.idInDocument!!, 
                        fields = mutableMapOf("docId" to idDocument)
                    )
                    val response = apiBitrix.deleteDocumentElement(request)
                    if (!response.isSuccessful) {
                        Log.e("ComingService", "Failed to delete product element ${product.idInDocument}: ${response.code()} - ${response.errorBody()?.string()}")
                        false
                    } else {
                        Log.d("ComingService", "Deleted product element ${product.idInDocument} successfully")
                        true
                    }
                } catch (e: Exception) {
                    Log.e("ComingService", "Error deleting product element ${product.idInDocument}", e)
                    false
                }
            }
        }
        deferredResults.awaitAll().all { it } 
    }

    
    private suspend fun updateProductsInDocSuspend(
        updatedProducts: List<ProductOffer>,
        idDocument: Int
    ): Boolean = coroutineScope {
        val productsToUpdate = updatedProducts.filter { it.idInDocument != null }
        if (productsToUpdate.isEmpty()) {
            Log.d("ComingService", "No products with idInDocument to update for document $idDocument")
            return@coroutineScope true
        }

        Log.d("ComingService", "Updating ${productsToUpdate.size} products for document $idDocument")

        val deferredResults = productsToUpdate.map { product ->
            async(Dispatchers.IO) { 
                var overallSuccess = false 
                try {
                    
                    val updateRequest = UpdatedDocumentElementsRequest(
                        id = product.idInDocument!!,
                        fields = mutableMapOf(
                            "docId" to idDocument,
                            "amount" to product.product.quantity
//                            "weight" to product.weight,
//                            "width" to product.diameter
                        )
                    )
                    Log.d("ComingService", "Updating doc element ${product.idInDocument} for product ${product.id}, amount ${product.quantity}")
                    val updateResponse = apiBitrix.updateDocumentElement(updateRequest)

                    if (updateResponse.isSuccessful) {
                        Log.d("ComingService", "Successfully updated doc element ${product.idInDocument}")

                        


                        
                        
                        val measureUpdateSuccess = if (product.product.measureId != null) {
                            updateProductMeasureSuspend(product.product.id, product.product.measureId!!)
                        } else {
                            Log.w("ComingService", "Skipping measure update for product ${product.id} - measureId is null")
                            true 
                        }


                        overallSuccess =  measureUpdateSuccess
                        if (!overallSuccess) {
                            Log.e("ComingService", "Failed during barcode/measure update for product ${product.id} after successful element update.")
                        }

                    } else {
                        Log.e("ComingService", "Failed to update doc element ${product.idInDocument}: ${updateResponse.code()} - ${updateResponse.errorBody()?.string()}")
                        overallSuccess = false
                    }
                } catch (e: Exception) {
                    Log.e("ComingService", "Error updating product chain for ${product.id} (docElementId=${product.idInDocument})", e)
                    overallSuccess = false
                }
                overallSuccess 
            }
        }
        deferredResults.awaitAll().all { it } 
    }


    private suspend fun setBarcodeForProductSuspend(productId: Int, barcode: String): Boolean {

        val barcodeRequest = BarcodeRequest(productId, barcode)

        return withContext(Dispatchers.IO) {
            try {
                Log.d("ComingService", "Setting barcode '$barcode' for product ID: $productId")
                val response = apiBarcode.setBarcodeByProductId(barcodeRequest)


                if (response.isSuccessful) {
                    val responseBody = response.body()

                    if (responseBody != null) {
                        if (responseBody.result) {

                            Log.i("ComingService", "Barcode for product $productId set successfully.")
                            true
                        } else {

                            Log.w("ComingService", "Failed to set barcode for product $productId. API returned 'result: false'. Likely a duplicate barcode: '$barcode'")
                            false
                        }
                    } else {

                        Log.e("ComingService", "Error setting barcode for $productId: Response body is null.")
                        false
                    }
                } else {

                    val errorBody = response.errorBody()?.string()
                    Log.e("ComingService", "Error setting barcode for $productId: HTTP ${response.code()} - $errorBody")
                    false
                }
            } catch (e: Exception) {

                Log.e("ComingService", "Network error setting barcode for $productId", e)
                false
            }
        }
    }

    
    private suspend fun updateProductMeasureSuspend(productId: Int, measureId: Int): Boolean {
        val request = UpdateProductRequest(
            id = productId,
            fields = mutableMapOf("measure" to measureId)
        )
        return withContext(Dispatchers.IO) {
            try {
                Log.d("ComingService", "Updating measure to $measureId for product ID: $productId")
                val response = apiBitrix.updateProduct(request)
                if (!response.isSuccessful) {
                    Log.e("ComingService", "Error updating measure for $productId: ${response.code()} - ${response.errorBody()?.string()}")
                }
                response.isSuccessful
            } catch (e: Exception) {
                Log.e("ComingService", "Network error updating measure for $productId", e)
                false
            }
        }
    }


    suspend fun conductDocumentSuspend(
        baseList: List<Product>,
        idDocument: Int?,
        context: Context,
        documentComment: String?,
        deletedProducts: List<Product>,
        updatedProducts: List<Product>,
        currentProductListState: List<Product>,
        productOffersList: List<ProductOffer>
    ): ConductResult {
        if (idDocument == null) {
            Log.e("ComingService", "Error: Document ID is null for conduct.")
            return ConductResult(false, "ID документа не найден.")
        }

        try {
            Log.d("ComingService", "Starting conduct process for document $idDocument")


            when (val saveResult = saveProductVariationsSuspend(productOffersList)) {
                is ConductResult.SaveResult.Success -> {
                    Log.d("ComingService", "Save variations step successful.")
                    val savedVariations = saveResult.savedVariations

                    val addSuccess = if (savedVariations.isNotEmpty()) {
                        addProductsToDocSuspend(savedVariations, idDocument, currentProductListState)
                    } else { true }

                    if (!addSuccess) {
                        Log.e("ComingService", "Conduct failed at add step for document $idDocument")
                        withContext(Dispatchers.Main) { showAlertInfo("Ошибка при добавлении новых товаров.", context) }
                        return ConductResult(false, "Ошибка при добавлении товаров в документ.")
                    }
                    Log.d("ComingService", "Add step successful.")

                    updateDiameterAndWeightAndHeight(productOffersList)
                    updateComment(documentComment, documentId = idDocument)

                    val conductSuccess = conductDocSuspend(idDocument, context)

                    return if (conductSuccess) {
                        Log.d("ComingService", "Conduct final step successful for document $idDocument.")
                        ConductResult(true)
                    } else {
                        Log.e("ComingService", "Conduct final step failed for document $idDocument")
                        ConductResult(false, "Не удалось провести документ на стороне сервера.")
                    }
                }
                is ConductResult.SaveResult.Failure -> {

                    Log.e("ComingService", "Conduct failed at save variations step: ${saveResult.errorMessage}")
                    return ConductResult(false, saveResult.errorMessage)
                }
            }

        } catch (e: Exception) {
            Log.e("ComingService", "Unexpected error during conduct process for doc $idDocument", e)
            withContext(Dispatchers.Main) { showAlertInfo("Непредвиденная ошибка при проведении: ${e.localizedMessage}", context) }
            return ConductResult(false, "Непредвиденная ошибка: ${e.localizedMessage}")
        }

    }

    private suspend fun updateComment(comment: String?, documentId: Int?) {
        Log.d("CommentUpdate", "Function called. Document ID: $documentId, Received comment: '$comment'")
        if (!comment.isNullOrBlank()) {
            Log.d("CommentUpdate", "Attempting to update comment for document $documentId with: '$comment'")
            val request = UpdateCommentRequest(
                documentId = documentId,
                fields = mutableMapOf(
                    "field621" to comment,
                    "documentType" to "A"
                )
            )
            try {
                apiBitrix.updateComment(request)
                Log.i("CommentUpdate", "Comment update request sent successfully for document $documentId.")
            } catch (e: Exception) {
                Log.e("CommentUpdate", "Failed to send comment update request for document $documentId", e)

            }
        } else {
            Log.d("CommentUpdate", "Comment is null or blank for document $documentId. No update request sent.")
        }
    }



    private suspend fun updateDiameterAndWeightAndHeight(products: List<ProductOffer>) {
        Log.d("MyFunctionCheck", "updateDiameterAndWeightAndHeight called. Products count: ${products.size}")
        if (products.isEmpty()) {
            Log.d("MyFunctionCheck", "Products list is empty, skipping update.")
            return
        }

        for(productOffer in products) {
            Log.d("DiameterAndWeight", "Product id ${productOffer.id}" )

            if (productOffer.product.weight != null || productOffer.product.diameter != null || productOffer.product.height != null) {
                val request = UpdateProductRequest()
                request.id = productOffer.id
                request.fields = mutableMapOf(
                    "weight" to productOffer.product.weight,
                    "width" to productOffer.product.diameter,
                    "height" to productOffer.product.height
                )

                try {
                    Log.d(
                        "ComingService",
                        "Updating weight and width for product ID: ${productOffer.id}"
                    )
                    val response = apiBitrix.updateProductOffer(request)
                    if (!response.isSuccessful) {
                        Log.e(
                            "ComingService",
                            "Error updating weight and width for ${productOffer.id}: ${response.code()} - ${
                                response.errorBody()?.string()
                            }"
                        )
                    } else {
                        Log.d(
                            "ComingService",
                            "Successfully updated product ID: ${productOffer.id}"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(
                        "ComingService",
                        "Network error updating measure for ${productOffer.id}",
                        e
                    )
                }
            }
        }
    }





    private suspend fun addProductsToDocSuspend(
        savedVariations: List<Pair<Int, ProductOffer>>,
        idDocument: Int,
        // Этот параметр больше не используется для поиска, но пусть остается,
        // чтобы не сломать вызов функции в других местах.
        currentProductListState: List<Product>
    ): Boolean = coroutineScope {
        if (savedVariations.isEmpty()) return@coroutineScope true

        Log.d("ComingService", "Adding ${savedVariations.size} doc elements for document $idDocument using current list state")

        val deferredResults = savedVariations.map { (elementId, productOffer) ->
            async(Dispatchers.IO) {
                var success = false
                try {

                    val currentProduct = productOffer.product

                    val currentQuantity = currentProduct.quantity ?: 0.0

                    Log.d("ComingService", "Using quantity $currentQuantity for product ${currentProduct.id} (Offer elementId: $elementId) from current list state.")

                    val request = AddDocumentElementRequest(
                        fields = mutableMapOf(
                            "docId" to idDocument,
                            "storeFrom" to 0,
                            "storeTo" to 1,
                            "elementId" to elementId,
                            "amount" to currentQuantity.toString(),
                            "purchasingPrice" to 0
                        )
                    )
                    val response = apiBitrix.addDocumentElement(request)
                    if (response.isSuccessful) {
                        val newDocElementId = try { (((response.body()
                            ?.get("result") as? Map<*, *>)
                            ?.get("documentElement") as? LinkedTreeMap<*, *>)
                            ?.get("id") as? Double)?.toInt() } catch (e: Exception) { null }

                        if (newDocElementId != null) {
                            currentProduct.idInDocument = newDocElementId
                            Log.d("ComingService", "Added doc element for product ID $elementId, new docElementId: $newDocElementId. Updated idInDocument in current list state for product ${currentProduct.id}.")
                            success = true
                        } else {
                            Log.w("ComingService", "Added doc element for product ID $elementId, but failed to parse new docElementId")
                            success = true
                        }
                    } else {
                        Log.e("ComingService", "Failed to add doc element for product ID $elementId: ${response.code()} - ${response.errorBody()?.string()}")
                        success = false
                    }
                } catch (e: Exception) {
                    Log.e("ComingService", "Error adding doc element for product ID ${productOffer.product.id}", e)
                    success = false
                }
                success
            }
        }
        deferredResults.awaitAll().all { it }
    }



    // В CatalogDocumentComingService.kt

    private suspend fun saveProductVariationsSuspend(
        productOffersList: List<ProductOffer>
    ): ConductResult.SaveResult = withContext(Dispatchers.IO) {
        if (productOffersList.isEmpty()) {
            return@withContext ConductResult.SaveResult.Success(emptyList())
        }

        Log.d("ComingService", "Saving ${productOffersList.size} product variations (offers)")
        val successfullySaved = mutableListOf<Pair<Int, ProductOffer>>()

        for (productOffer in productOffersList) {
            try {
                // 1. Создаем вариацию (торговое предложение)
                val productId = addVariationOfProductSuspend(productOffer)
                if (productId == null) {
                    // Если не удалось создать товар, прерываемся с ошибкой
                    return@withContext ConductResult.SaveResult.Failure("Не удалось создать товар: ${productOffer.name}")
                }
                productOffer.id = productId

                // 2. Устанавливаем штрихкод, если он есть
                if (!productOffer.barcode.isNullOrBlank()) {
                    Log.i("Barcode", "Setting barcode for $productId, name: ${productOffer.name}")
                    val barcodeSuccess = setBarcodeForProductSuspend(productId, productOffer.barcode!!)

                    if (!barcodeSuccess) {
                        // !!! ОБНАРУЖЕНА ОШИБКА !!!
                        // Прерываем весь процесс и возвращаем конкретную ошибку
                        val errorMessage = "Штрихкод '${productOffer.barcode}' для товара '${productOffer.name}' уже используется или не может быть назначен."
                        Log.e("ComingService", errorMessage)
                        return@withContext ConductResult.SaveResult.Failure(errorMessage)
                    }
                } else {
                    Log.i("Barcode", "Barcode for $productId is null or empty, skipping assignment.")
                }

                // Если все успешно, добавляем в список
                successfullySaved.add(Pair(productId, productOffer))
                Log.d("ComingService", "Successfully saved variation for ${productOffer.name} with ID $productId")

            } catch (e: Exception) {
                Log.e("ComingService", "Error saving variation for ${productOffer.name}", e)
                return@withContext ConductResult.SaveResult.Failure("Критическая ошибка при сохранении товара: ${productOffer.name}")
            }
        }


        ConductResult.SaveResult.Success(successfullySaved)
    }

    
    
    private suspend fun addVariationOfProductSuspend(productOffer: ProductOffer): Int? {
        
        val fieldsMap: MutableMap<String, Any?> = mutableMapOf(
            "iblockId" to 15, 
            "name" to productOffer.name,
            "measure" to productOffer.measure,
            "parentId" to productOffer.parentId,
            "dateCreate" to productOffer.dateCreate.toString(),
            "purchasingPrice" to productOffer.purchasingPrice,
            "purchasingCurrency" to productOffer.purchasingCurrency
        )

        
        val nonNullFields: MutableMap<String, Any> = fieldsMap
            .filterValues { it != null } 
            .mapValues { it.value!! } 
            .toMutableMap()           

        
        val productOfferRequest = ProductOfferRequest(fields = nonNullFields)

        return withContext(Dispatchers.IO) {
            try {
                Log.d("ComingService", "Adding variation for ${productOffer.name}")
                val response = apiBitrix.addVariationsOfProduct(productOfferRequest)
                if (response.isSuccessful) {
                    val productDetails = response.body()?.result?.get("offer") as? LinkedTreeMap<*, *>
                    val productId = productDetails?.get("id")?.let { (it as? Double)?.toInt() }
                    if (productId == null) {
                        Log.e("ComingService", "addVariationOfProduct successful but failed to parse offer ID from response")
                    }
                    productId 
                } else {
                    Log.e("ComingService", "Error adding variation ${productOffer.name}: ${response.code()} - ${response.errorBody()?.string()}")
                    null 
                }
            } catch (e: Exception) {
                Log.e("ComingService", "Network error adding variation ${productOffer.name}", e)
                null 
            }
        }
    }

    

    
    suspend fun getDocumentsSuspend(): List<Document> {
        val requestWithParams = CatalogDocumentListRequest(
            filter = mutableMapOf(
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

    suspend fun fetchAndAssignNomenclature(documents: List<Document>): List<Document> {

        if (documents.isEmpty()) {
            Log.d("ComingService", "Initial document list is empty, skipping nomenclature fetch.")
            return documents
        }

        val documentIds = documents.mapNotNull { it.id }
        if (documentIds.isEmpty()) {
            Log.d("ComingService", "No valid document IDs found, skipping nomenclature fetch.")
            return documents
        }

        val nomenclatureRequest = NomenclatureRequest(
            filter = mutableMapOf(
                "documentId" to documentIds,
                "documentType" to "A"
            )

        )

        Log.d("ComingService", "Requesting nomenclature for document IDs: $documentIds")

        try {
            val response = apiBitrix.getDocumentsNomenclature(nomenclatureRequest)

            if (response.isSuccessful) {
                val nomenclatureItems = response.body()?.result?.documents
                if (nomenclatureItems.isNullOrEmpty()) {
                    Log.w("ComingService", "Nomenclature data is null or empty in response.")
                    return documents
                }


                val field620Map = nomenclatureItems.associate { item ->
                    item.documentId to item.field620
                }.filterKeys { it != null }
                        as Map<Int, List<Double>?>

                Log.d("ComingService", "Received ${nomenclatureItems.size} nomenclature items. Mapped ${field620Map.size} items.")


                return documents.map { doc ->
                    doc.id?.let { currentDocId ->
                        if (field620Map.containsKey(currentDocId)) {
                            val newField620Values = field620Map[currentDocId]
                            doc.copy(field620Values = newField620Values)
                        } else {
                            doc
                        }
                    } ?: doc
                }.also {
                    Log.d("ComingService", "Updated documents with nomenclature data.")
                }

            } else {
                Log.e("ComingService", "getDocumentsNomenclature failed: ${response.code()} - ${response.message()}")
                return documents
            }
        } catch (e: HttpException) {
            Log.e("ComingService", "HTTP error in getDocumentsNomenclature: ${e.code()} - ${e.message()}", e)
        } catch (e: IOException) {
            Log.e("ComingService", "Network error in getDocumentsNomenclature", e)
        } catch (e: Exception) {
            Log.e("ComingService", "Failed to load or process nomenclature", e)
        }

        return documents
    }


    suspend fun getProductsForSelection(iblockId: Int, productId: ArrayList<Double?>?): List<Product> {

        val allProducts = mutableListOf<Product>()

        var startFrom: Int? = 0

        if (productId == null) {
            return emptyList();
        }

        val filterMap = mapOf(
            "iblockId" to iblockId,
            "id" to productId
        )

        return withContext(Dispatchers.IO) {
            try {
                do {

                    val productRequest = ProductRequest(
                        filter = filterMap,
                        start = startFrom?.toString()
                    )

                    Log.d("ComingService", "Getting products... Request: $productRequest")


                    val response = apiBitrix.getProducts(productRequest)

                    if (response.isSuccessful) {
                        val responseBody = response.body()
                        if (responseBody != null) {

                            val rawProducts = responseBody.result["products"] as? List<*>

                            val newProducts = rawProducts?.mapNotNull { unit ->
                                (unit as? LinkedTreeMap<*, *>)?.let { productMap ->
                                    try {
                                        productMapper.mapToProduct(productMap)
                                    } catch (e: Exception) {
                                        Log.e("ComingService", "Error mapping product", e)
                                        null
                                    }
                                }
                            } ?: emptyList()

                            allProducts.addAll(newProducts)
                            Log.d("ComingService", "Fetched ${newProducts.size}. Total products: ${allProducts.size}")


                            startFrom = responseBody.next

                        } else {
                            Log.w("ComingService", "Response body is null. Stopping pagination.")
                            startFrom = null
                        }
                    } else {
                        Log.e("ComingService", "Request failed: ${response.code()} - ${response.message()}")
                        startFrom = null
                    }

                } while (startFrom != null)

            } catch (e: Exception) {
                Log.e("ComingService", "Critical error in getProductsForSelection loop", e)
                allProducts.clear()
            }

            allProducts
        }
    }




//    suspend fun getDocumentProductsWithDetails(idDocument: Int): List<Product> {
//        return withContext(Dispatchers.IO) {
//
//            val documentElementsData = mutableMapOf<Int, Pair<Int, Int?>>()
//
//            try {
//                Log.d("ComingService", "Getting doc elements for document $idDocument")
//                val requestElements = CatalogDocumentElementListRequest(filter = mutableMapOf("docId" to idDocument))
//                val elementsResponse = apiBitrix.getDocumentProducts(requestElements)
//
//                if (elementsResponse.isSuccessful) {
//                    val rawElements = elementsResponse.body()?.result?.get("documentElements") as? List<*>
//                    rawElements?.forEach { element ->
//                        (element as? LinkedTreeMap<*, *>)?.let { elementMap ->
//                            try {
//                                val docElement = documentElementsMapper.mapToDocumentElement(elementMap)
//                                if (docElement.elementId != null && docElement.id != null) {
//                                    documentElementsData[docElement.elementId] = Pair(docElement.id!!, docElement.amount)
//                                    Log.d("ComingService", "Mapped doc element: id=${docElement.id}, elementId=${docElement.elementId}, amount=${docElement.amount}")
//                                } else {
//                                    Log.w("ComingService", "Skipping doc element due to null elementId or id: ${elementMap}")
//                                }
//                            } catch (mapE: Exception) {
//                                Log.e("ComingService", "Error mapping document element", mapE)
//                            }
//                        }
//                    }
//                    Log.d("ComingService", "Found ${documentElementsData.size} elements data in document $idDocument")
//                } else {
//                    Log.w("ComingService", "getDocumentProducts failed: ${elementsResponse.code()} - ${elementsResponse.message()}")
//                    return@withContext emptyList<Product>()
//                }
//
//
//                val elementIds = documentElementsData.keys.toList()
//                if (elementIds.isEmpty()) {
//                    Log.d("ComingService", "No valid element IDs found in document $idDocument, returning empty list.")
//                    return@withContext emptyList<Product>()
//                }
//
//                Log.d("ComingService", "Getting product details for ${elementIds.size} elements")
//                val productRequest = ProductRequest(filter = mapOf("id" to elementIds, "iblockId" to listOf(14, 15)))
//                val productsResponse = apiBitrix.getProducts(productRequest)
//                val resultProducts = mutableListOf<Product>()
//
//                if (productsResponse.isSuccessful) {
//                    val rawProducts = productsResponse.body()?.result?.get("products") as? List<*>
//                    rawProducts?.forEach { unit ->
//                        (unit as? LinkedTreeMap<*, *>)?.let { productMap ->
//                            try {
//                                val product = productMapper.mapToProduct(productMap)
//
//                                val elementData = documentElementsData[product.id]
//
//                                if (elementData != null) {
//                                    product.idInDocument = elementData.first
//                                    product.quantity = elementData.second?.toDouble()
//                                    Log.d("ComingService", "Assigned idInDocument=${product.idInDocument} and quantity=${product.quantity} to product ${product.id}")
//                                } else {
//                                    Log.w("ComingService", "Could not find element data for product ${product.id} in documentElementsData map.")
//                                    product.idInDocument = null
//                                    product.quantity = 0.0
//                                }
//                                resultProducts.add(product)
//                            } catch (mapE: Exception) {
//                                Log.e("ComingService", "Error mapping product or assigning doc data", mapE)
//                            }
//                        }
//                    }
//                    Log.d("ComingService", "Mapped ${resultProducts.size} products with details and doc data.")
//                } else {
//                    Log.w("ComingService", "getProducts failed: ${productsResponse.code()} - ${productsResponse.message()}")
//                    return@withContext emptyList<Product>()
//                }
//                resultProducts
//
//            } catch (e: Exception) {
//                Log.e("ComingService", "Error in getDocumentProductsWithDetails for doc $idDocument", e)
//                emptyList()
//            }
//        }
//    }

    

    
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