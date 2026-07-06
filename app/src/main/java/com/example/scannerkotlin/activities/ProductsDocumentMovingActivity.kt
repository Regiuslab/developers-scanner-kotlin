package com.example.scannerkotlin.activities


import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.scannerkotlin.R
import com.example.scannerkotlin.adapter.ProductMovingAdapter
import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.mappers.ProductMapper
import com.example.scannerkotlin.mappers.ProductToDocumentElementMapper
import com.example.scannerkotlin.model.DocumentElement
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.Store
import com.example.scannerkotlin.request.ProductIdRequest
import com.example.scannerkotlin.response.StoreAmountResponse
import com.example.scannerkotlin.service.CatalogDocumentMovingService
import com.example.scannerkotlin.utils.BaseScanActivity
import com.example.scannerkotlin.utils.BarcodeScanGate
import com.example.scannerkotlin.utils.MovingDocumentDraftManager
import com.google.gson.internal.LinkedTreeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

@RequiresApi(Build.VERSION_CODES.O)
class ProductsDocumentMovingActivity : BaseScanActivity() {


    private lateinit var btnSave: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: ProductMovingAdapter
    private lateinit var service: CatalogDocumentMovingService
    private lateinit var apiBitrix: ApiBitrix
    private lateinit var emptyProductMovingTextView: TextView

    
    private val elements: MutableList<DocumentElement> = mutableListOf()
    private val baseList: MutableList<DocumentElement> = mutableListOf()
    private val deletedProduct: MutableList<DocumentElement> = mutableListOf()
    private val newProductInDocument: MutableList<DocumentElement> = mutableListOf()
    private var storeList: List<Store> = listOf() 

    
    private val mapper = ProductToDocumentElementMapper()
    private val productMapper = ProductMapper() 

    
    private var idDocument: Int? = null
    private var idDocumentLong: Long? = null


    private var isSaved = false
    private val scanGate = BarcodeScanGate(duplicateWindowMs = 500L)

    
    override fun onBarcodeScanned(barcode: String) {
        if (!scanGate.tryAcquire(barcode)) {
            Log.d("ProductsActivity", "Scan skipped by gate: $barcode")
            return
        }
        processBarcodeScan(barcode)
    }


    @SuppressLint("UnspecifiedRegisterReceiverFlag", "MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_products_document_moving)

        
        idDocument = intent.getStringExtra("idDocument")?.toIntOrNull()
        idDocumentLong = intent.getStringExtra("idDocument")?.toLongOrNull()

        if (idDocument == null || idDocumentLong == null) {
            Log.e("ProductsActivity", "Invalid or missing 'idDocument' extra.")
            Toast.makeText(this, "Ошибка: Неверный ID документа", Toast.LENGTH_LONG).show()
            finish() 
            return
        }

        
        initializeDependencies()
        initializeViews()
        setupRecyclerView()
        setupButtonClickListeners()

        
        loadInitialDataManager()
        updateSaveButtonState() 
    }

    override fun onDestroy() {
        super.onDestroy()

        idDocument?.let { docId ->
            if (!isSaved) {
                if (elements.isNotEmpty()) {
                    Log.d("ProductsActivity", "Saving draft for document ID: $docId")
                    MovingDocumentDraftManager.saveDraft(docId, elements)
                } else {
                    MovingDocumentDraftManager.clearDraft(docId)
                }
            }
        }
    }



    @SuppressLint("NotifyDataSetChanged")
    private fun loadInitialDataManager() {
        val currentIdDocument = idDocument ?: return
        progressBar.visibility = View.VISIBLE
        emptyProductMovingTextView.visibility = View.GONE
        Log.d("ProductsActivity", "Starting loadInitialData for document $currentIdDocument")

        lifecycleScope.launch {

            val draftElements = MovingDocumentDraftManager.getDraft(currentIdDocument)

            var loadedStores: List<Store> = emptyList()
            var serverElements: List<DocumentElement> = emptyList()
            var error: Throwable? = null

            try {
                coroutineScope {
                    val storesDeferred = async(Dispatchers.IO) { service.getStoreList() }
                    val elementsDeferred = async(Dispatchers.IO) { service.getDocumentElementsWithDetails(currentIdDocument) }
                    loadedStores = storesDeferred.await()
                    serverElements = elementsDeferred.await()
                }
                Log.d("ProductsActivity", "Loaded ${loadedStores.size} stores and ${serverElements.size} elements from server.")
            } catch (e: Exception) {
                Log.d("ProductsActivity", "Error")
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (isActive) {
                        if (error == null) {
                            storeList = loadedStores

                            baseList.clear()
                            baseList.addAll(serverElements)

                            elements.clear()
                            if (draftElements != null) {

                                Log.d("ProductsActivity", "Applying draft with ${draftElements.size} elements.")
                                elements.addAll(draftElements)

                                val baseIds = baseList.map { it.id }.toSet()
                                newProductInDocument.clear()
                                newProductInDocument.addAll(elements.filter { it.id == null })
                                deletedProduct.clear()
                                deletedProduct.addAll(baseList.filter { baseEl -> elements.none { it.id == baseEl.id } })
                            } else {

                                Log.d("ProductsActivity", "No draft found. Applying server data.")
                                elements.addAll(serverElements)
                                newProductInDocument.clear()
                                deletedProduct.clear()
                            }

                            adapter.updateStores(storeList)
                            adapter.updateData(ArrayList(elements))
                            updateEmptyStateVisibility()
                            updateSaveButtonState()
                        } else {
                            Log.d("ProductsActivity", "Error")
                        }
                    }
                }
            }
        }
    }

    

    private fun initializeDependencies() {
        service = CatalogDocumentMovingService() 

        
        val retrofitInstance: Retrofit = Retrofit.Builder()
            .baseUrl("https://bitrix.izocom.by/rest/1/c953o6imkob2gpwd/") 
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        apiBitrix = retrofitInstance.create(ApiBitrix::class.java)
    }

    private fun initializeViews() {

        btnSave = findViewById(R.id.btnMovingSave)
        recyclerView = findViewById(R.id.rvMovingProducts)
        progressBar = findViewById(R.id.progressBar)
        emptyProductMovingTextView = findViewById(R.id.emptyProductMovingTextView)
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ProductMovingAdapter(
            this, 
            elements, 
            storeList 
        ) { position -> 
            handleItemDeletion(position)
        }
        recyclerView.adapter = adapter
        
        adapter.setOnDataChangedListener {
            updateSaveButtonState()
        }

        val snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)
    }

    private fun setupButtonClickListeners() {
        btnSave.setOnClickListener { handleSaveButtonClick() }
    }

    

    @SuppressLint("NotifyDataSetChanged") 
    private fun loadInitialData() {
        val currentIdDocument = idDocument ?: return 
        progressBar.visibility = View.VISIBLE
        emptyProductMovingTextView.visibility = View.GONE
        Log.d("ProductsActivity", "Starting loadInitialData for document $currentIdDocument")

        lifecycleScope.launch {
            var loadedStores: List<Store> = emptyList()
            var loadedElements: List<DocumentElement> = emptyList()
            var error: Throwable? = null

            try {
                
                coroutineScope { 
                    val storesDeferred = async(Dispatchers.IO) { service.getStoreList() }
                    val elementsDeferred = async(Dispatchers.IO) { service.getDocumentElementsWithDetails(currentIdDocument) }

                    loadedStores = storesDeferred.await()
                    loadedElements = elementsDeferred.await()
                }
                Log.d("ProductsActivity", "Loaded ${loadedStores.size} stores and ${loadedElements.size} elements.")

            } catch (e: Exception) {
                Log.e("ProductsActivity", "Error during initial data loading", e)
                error = e
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (isActive) { 
                        if (error == null) {
                            storeList = loadedStores
                            elements.clear()
                            elements.addAll(loadedElements)
                            baseList.clear()
                            baseList.addAll(loadedElements) 

                            
                            adapter.updateStores(storeList) 
                            adapter.updateData(ArrayList(elements)) 

                            updateEmptyStateVisibility()
                            updateSaveButtonState() 
                        } else {
                            Toast.makeText(this@ProductsDocumentMovingActivity, "Ошибка загрузки данных: ${error.message}", Toast.LENGTH_LONG).show()
                            
                            elements.clear()
                            baseList.clear()
                            adapter.updateData(ArrayList(elements))
                            updateEmptyStateVisibility()
                            updateSaveButtonState()
                        }
                    }
                }
            }
        }
    }

    

    private fun handleItemDeletion(position: Int) {
        if (position < 0 || position >= elements.size) return
        val removedItem = elements.removeAt(position)
        
        if (baseList.any { it.id == removedItem.id && removedItem.id != null }) {
            deletedProduct.add(removedItem)
        }
        
        newProductInDocument.remove(removedItem)

        adapter.notifyItemRemoved(position)

        updateEmptyStateVisibility()
        updateSaveButtonState()
        Log.d("ProductsActivity", "Item deleted: ${removedItem.name}. Deleted list size: ${deletedProduct.size}")

    }

    private fun handleAddButtonClick() {
        Log.d("ProductsActivity", "Add button clicked")
        progressBar.visibility = View.VISIBLE 


        lifecycleScope.launch {
            var variations: List<Product> = emptyList()
            var error: Throwable? = null
            try {
                variations = service.getVariations() 
            } catch (e: Exception) {
                Log.e("ProductsActivity", "Error getting variations", e)
                error = e
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (isActive) {
                        if (error == null) {
                            Log.d("ProductsActivity", "Loaded ${variations.size} variations.")
                            if (variations.isEmpty()) {
                                showAlert("Нет доступных продуктов", emptyList()) {} 
                            } else {
                                showAlert("Выберите продукт", variations) { selectedProduct ->
                                    handleProductSelection(selectedProduct)
                                }
                            }
                        } else {
                            Toast.makeText(this@ProductsDocumentMovingActivity, "Ошибка загрузки вариантов: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun handleProductSelection(selectedProduct: Product?) {
        
        if (selectedProduct != null) {
            val currentIdDocument = idDocumentLong ?: return 
            val newElement = mapper.map(selectedProduct, currentIdDocument)
            Toast.makeText(this, "Вы выбрали: ${selectedProduct.name}", Toast.LENGTH_SHORT).show()

            
            if (elements.none { it.elementId == newElement.elementId }) {
                elements.add(0, newElement)
                newProductInDocument.add(newElement) 
                adapter.notifyItemInserted(0)
                recyclerView.scrollToPosition(0) 
                updateSaveButtonState()
                updateEmptyStateVisibility()
                Log.d("ProductsActivity", "Added new element: ${newElement.name}. New list size: ${newProductInDocument.size}")
            } else {
                Toast.makeText(this, "Товар '${selectedProduct.name}' уже есть в списке", Toast.LENGTH_SHORT).show()
            }

        } else {
            Toast.makeText(this, "Выбор отменён", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("NotifyDataSetChanged") 
    private fun addScannedProductToList(element: DocumentElement) {
        
        if (elements.none { it.elementId == element.elementId }) {
            elements.add(0, element)
            newProductInDocument.add(element) 
            
            
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(0)
            updateEmptyStateVisibility()
            updateSaveButtonState()
            Log.d("ProductsActivity", "Added scanned element: ${element.name}. New list size: ${newProductInDocument.size}")
        } else {
            showAlertInfo("Этот товар уже отсканирован")
        }
    }


    private fun handleSaveButtonClick() {
        val currentIdDocument = idDocument ?: return
        if (!areAllFieldsFilled(elements)) {
            Toast.makeText(this, "Заполните все поля (Кол-во > 0, Склад Откуда/Куда, Склады не равны)", Toast.LENGTH_LONG).show()
            return
        }

        Log.d("ProductsActivity", "Save button clicked for document $currentIdDocument")
        progressBar.visibility = View.VISIBLE
        btnSave.isEnabled = false 

        
        val productsActuallyUpdated = elements.filter { current ->
            val base = baseList.find { it.id == current.id }
            base != null && ( 
                    current.amount != base.amount || 
                            current.storeFrom != base.storeFrom || 
                            current.storeTo != base.storeTo 
                    ) && !deletedProduct.contains(current) 
        }

        lifecycleScope.launch {
            var success = false
            var error: Throwable? = null

            try {
                Log.d("ProductsActivity", "Calling conductDocumentSuspend: base=${baseList.size}, deleted=${deletedProduct.size}, updated=${productsActuallyUpdated.size}, new=${newProductInDocument.size}")
                success = service.conductDocumentSuspend(
                    baseList = baseList,
                    idDocument = currentIdDocument,
                    context = this@ProductsDocumentMovingActivity,
                    deletedProducts = deletedProduct,
                    updatedProducts = productsActuallyUpdated, 
                    newElements = newProductInDocument
                )

                if (success) {
                    isSaved = true
                    MovingDocumentDraftManager.clearDraft(currentIdDocument)

                }
            } catch (e: Exception) {
                Log.e("ProductsActivity", "Error calling conductDocumentSuspend", e)
                error = e
            } finally {
                if (success) {

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ProductsDocumentMovingActivity, "Документ успешно проведен", Toast.LENGTH_SHORT).show()
                        val intent = Intent(this@ProductsDocumentMovingActivity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        btnSave.isEnabled = true
                        if (isActive) {
                            if (error != null) {
                                Toast.makeText(this@ProductsDocumentMovingActivity, "Ошибка проведения: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    

    
    private fun processBarcodeScan(barcode: String) {
        val currentIdDocument = idDocumentLong ?: return 

        progressBar.visibility = View.VISIBLE
        Log.d("ProductsActivity", "Processing barcode: $barcode for document $currentIdDocument")

        lifecycleScope.launch {
            var fetchedProduct: Product? = null
            var fetchedStoreAmounts: List<StoreAmountResponse.StoreProduct>? = null
            var failureMessage: String? = null

            try {
                
                val productId = withContext(Dispatchers.IO) {
                    Log.d("ProductsActivity", "[IO] Getting product ID for barcode $barcode")
                    val response = apiBitrix.getProductIdByBarcode(barcode) 

                    if (response.isSuccessful) {
                        
                        val resultId = response.body()?.result?.trim() 

                        if (!resultId.isNullOrEmpty()) {
                            
                            Log.d("ProductsActivity", "[IO] Product ID found: $resultId")
                            resultId 
                        } else {
                            
                            Log.w("ProductsActivity", "[IO] getProductIdByBarcode successful but result is null/empty for barcode $barcode.")
                            failureMessage = "Товар по штрихкоду '$barcode' не найден."
                            null 
                        }
                    } else {
                        
                        Log.w("ProductsActivity", "[IO] getProductIdByBarcode failed with code: ${response.code()}")
                        
                        failureMessage = "Ошибка при поиске штрихкода (${response.code()})."
                        null 
                    }
                } 

                
                if (productId != null) {
                    
                    fetchedProduct = withContext(Dispatchers.IO) {
                        Log.d("ProductsActivity", "[IO] Getting product details for ID $productId")
                        val request = ProductIdRequest(id = productId)
                        val response = apiBitrix.getProductById(request)
                        if (response.isSuccessful) {
                            val details = response.body()?.result?.get("product") as? LinkedTreeMap<*, *>
                            details?.let { productMapper.mapToProduct(it) }?.also {
                                Log.d("ProductsActivity", "[IO] Product details fetched: ${it.name}")
                            }
                        } else {
                            Log.w("ProductsActivity", "[IO] getProductById failed for $productId: ${response.code()}")
                            failureMessage = "Не удалось получить детали товара (ID: $productId)."
                            null 
                        }
                    }
                }

                
                if (fetchedProduct != null && productId != null) { 
                    
                    val productIdInt = productId.toIntOrNull()
                    if (productIdInt != null) {
                        Log.d("ProductsActivity", "Getting store amounts for product ID $productIdInt")
                        fetchedStoreAmounts = service.getStoreForScan(productIdInt)
                        Log.d("ProductsActivity", "Fetched ${fetchedStoreAmounts.size} store amount records.")
                    } else {
                        Log.w("ProductsActivity", "Invalid product ID format for store scan: $productId")
                    }
                }

                
            } catch (ioe: IOException) {
                Log.e("ProductsActivity", "Network error processing barcode $barcode", ioe)
                failureMessage = "Ошибка сети при обработке штрихкода."
            } catch (e: Exception) {
                Log.e("ProductsActivity", "Error processing barcode $barcode", e)
                failureMessage = "Непредвиденная ошибка при обработке штрихкода."
            }

            
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE 

                if (!isActive) {
                    Log.w("ProductsActivity", "Coroutine cancelled, skipping UI update for barcode $barcode")
                    return@withContext
                }

                if (fetchedProduct != null) {
                    
                    val element = mapper.map(fetchedProduct, currentIdDocument)

                    if (elements.none { it.elementId == element.elementId }) {
                        Log.d("ProductsActivity", "Product not in list. Showing confirmation dialog for ${element.name}")
                        showAlertScanInfo(element, fetchedStoreAmounts) {
                            Log.d("ProductsActivity", "Add confirmed for ${element.name}")
                            addScannedProductToList(element)
                        }
                    } else {
                        Log.d("ProductsActivity", "Product ${element.name} already in list.")
                        showAlertInfo("Этот товар уже добавлен")
                    }
                } else if (failureMessage != null) {
                    
                    Log.w("ProductsActivity", "Scan processing failed or product not found: $failureMessage")
                    showAlertInfo(failureMessage!!) 
                }
                
            } 
            scanGate.release()
        } 
    }



    private fun showAlertInfo(text: String) {
        if (!isFinishing && !isDestroyed) {
            val builder = AlertDialog.Builder(this, R.style.MyCustomAlertDialog).apply {
                setTitle("Информация")
                setMessage(text)
                setCancelable(true)
                setPositiveButton("ОК") { dialog, _ ->
                    dialog.dismiss()
                }
            }
            val dialog = builder.create()
            dialog.show()


            val yellowColor = ContextCompat.getColor(this, R.color.yellow)


            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(yellowColor)
        } else {
            Log.w("ScanActivity", "Attempted to show alert dialog on finishing/destroyed activity")
        }
    }

    private fun showAlertScanInfo(
        element: DocumentElement,
        storeAmounts: List<StoreAmountResponse.StoreProduct>?,
        onAddProduct: () -> Unit
    ) {
        if (!isFinishing) {
            val message = buildString {
                append("${element.name}\n\n")
                if (storeAmounts.isNullOrEmpty()) {
                    append("Нет данных по остаткам.")
                } else {
                    append("Остатки на складах:\n")
                    storeAmounts.forEach { storeProduct ->
                        val storeName = storeList.find { it.id == storeProduct.storeId.toInt() }?.title ?: "Склад ID ${storeProduct.storeId}"
                        append("$storeName: ${storeProduct.amount}\n")
                    }
                }
            }

            val builder = AlertDialog.Builder(this, R.style.MyCustomAlertDialog)
            builder.apply {
                setTitle("Подтверждение товара")
                setMessage(message)
                setCancelable(false)
                setPositiveButton("Добавить товар") { dialog, _ ->
                    onAddProduct()
                    dialog.dismiss()
                }
                setNegativeButton("Отмена") { dialog, _ ->
                    dialog.dismiss()
                }
            }

            val dialog = builder.create()
            dialog.show()


            val yellowColor = ContextCompat.getColor(this, R.color.yellow)


            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(yellowColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(yellowColor)
        }
    }


    private fun showAlert(
        title: String,
        products: List<Product>,
        onProductSelected: (Product?) -> Unit
    ) {
        if (!isFinishing) {
            val builder = AlertDialog.Builder(this) 
            builder.setTitle(title)

            if (products.isEmpty()) {
                builder.setMessage("Нет доступных вариантов.")
                builder.setPositiveButton("OK") { dialog, _ ->
                    onProductSelected(null)
                    dialog.dismiss()
                }
            } else {
                val productNames = products.map { it.name ?: "Без имени" } 
                builder.setItems(productNames.toTypedArray()) { dialog, which ->
                    val selectedProduct = products[which]
                    onProductSelected(selectedProduct)
                    dialog.dismiss()
                }
                builder.setNegativeButton("Отмена") { dialog, _ ->
                    onProductSelected(null)
                    dialog.dismiss()
                }
            }
            builder.create().show()
        }
    }



    private fun areAllFieldsFilled(elements: List<DocumentElement>): Boolean {
        if (elements.isEmpty()) {
            Log.d("ARE_ALL_FILLED", "Список элементов пуст, кнопка неактивна.")
            return false
        }
        Log.d("ARE_ALL_FILLED", "Проверка ${elements.size} элементов для кнопки 'Провести'.")
        return elements.all { product ->
            Log.d("ARE_ALL_FILLED", "Проверка товара: ${product.name}, ID: ${product.elementId}")

            val amountCondition = (product.amount ?: 0.0) > 0
            Log.d("ARE_ALL_FILLED", " - Количество (${product.amount}) > 0: $amountCondition")

            val storeFromCondition = product.storeFrom != null
            Log.d("ARE_ALL_FILLED", " - Склад-ОТКУДА (${product.storeFrom}) != null: $storeFromCondition")

            val storeToCondition = product.storeTo != null
            Log.d("ARE_ALL_FILLED", " - Склад-КУДА (${product.storeTo}) != null: $storeToCondition")

            val storesDifferentCondition = product.storeFrom != product.storeTo
            Log.d("ARE_ALL_FILLED", " - Склад-ОТКУДА (${product.storeFrom}) != Склад-КУДА (${product.storeTo}): $storesDifferentCondition")

            val resultForProduct = amountCondition && storeFromCondition && storeToCondition && storesDifferentCondition
            if (!resultForProduct) {
                Log.w("ARE_ALL_FILLED", " - ТОВАР НЕ ПРОШЕЛ ПРОВЕРКУ! Кнопка будет неактивна.")
            }
            resultForProduct
        }
    }

    
    private fun updateSaveButtonState() {
        
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updateSaveButtonStateInternal() }
        } else {
            updateSaveButtonStateInternal()
        }
    }

    private fun updateSaveButtonStateInternal() {
        val canSave = areAllFieldsFilled(elements)
        Log.d("ProductsActivity", "Updating save button state. CanSave: $canSave")
        btnSave.isEnabled = canSave
        btnSave.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (canSave) R.color.field_blue else R.color.gray
            )
        )
    }
    private fun updateEmptyStateVisibility() {
        if (elements.isEmpty()) {
            emptyProductMovingTextView.text = "Список товаров пуст"
            emptyProductMovingTextView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyProductMovingTextView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }
}