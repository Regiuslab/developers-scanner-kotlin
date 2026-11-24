package com.example.scannerkotlin.activities


import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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
import com.example.scannerkotlin.utils.MovingAvailabilityDisplay
import com.example.scannerkotlin.utils.MovingBarcodeLookup
import com.example.scannerkotlin.utils.MovingConductRetrySync
import com.example.scannerkotlin.utils.MovingDocumentDraftManager
import com.example.scannerkotlin.utils.MovingDocumentStateMerger
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
    private lateinit var btnFindBarcode: Button
    private lateinit var etManualBarcode: EditText
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
    private var isConducting = false
    private val scanGate = BarcodeScanGate(duplicateWindowMs = 500L)

    
    override fun onBarcodeScanned(barcode: String) {
        submitBarcode(barcode, fromManualField = false)
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
                Log.e("ProductsActivity", "Error during initial data loading", e)
                error = e
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (isActive) {
                        if (loadedStores.isNotEmpty()) {
                            storeList = loadedStores
                            adapter.updateStores(storeList)
                        }

                        if (error == null) {
                            baseList.clear()
                            baseList.addAll(serverElements)

                            val merged = MovingDocumentStateMerger.merge(
                                serverElements = serverElements,
                                draftElements = draftElements,
                                localDuringLoad = ArrayList(elements)
                            )
                            elements.clear()
                            elements.addAll(merged.elements)
                            newProductInDocument.clear()
                            newProductInDocument.addAll(merged.newProducts)
                            deletedProduct.clear()
                            deletedProduct.addAll(merged.deletedProducts)

                            adapter.updateData(ArrayList(elements))
                            updateEmptyStateVisibility()
                            updateSaveButtonState()
                            if (storeList.isEmpty()) {
                                showAlertInfo(MovingAvailabilityDisplay.STORES_NOT_LOADED_ALERT)
                            }
                        } else {
                            Toast.makeText(
                                this@ProductsDocumentMovingActivity,
                                "Ошибка загрузки данных: ${error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            updateEmptyStateVisibility()
                            updateSaveButtonState()
                        }
                    }
                }
            }
        }
    }

    

    private fun initializeDependencies() {
        service = CatalogDocumentMovingService() 

        
        apiBitrix = com.example.scannerkotlin.config.BitrixClientFactory.barcodeScanApi()
    }

    private fun initializeViews() {

        btnSave = findViewById(R.id.btnMovingSave)
        btnFindBarcode = findViewById(R.id.btnFindBarcode)
        etManualBarcode = findViewById(R.id.etManualBarcode)
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
        btnFindBarcode.setOnClickListener {
            submitBarcode(etManualBarcode.text?.toString(), fromManualField = true)
        }
        etManualBarcode.setOnEditorActionListener { _, actionId, event ->
            val isSubmit = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSubmit) {
                submitBarcode(etManualBarcode.text?.toString(), fromManualField = true)
                true
            } else {
                false
            }
        }
        etManualBarcode.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                submitBarcode(etManualBarcode.text?.toString(), fromManualField = true)
                true
            } else {
                false
            }
        }
    }

    /**
     * Hardware scans and the manual field share this entry point:
     * the same normalization, the same in-flight gate, and [processBarcodeScan].
     */
    private fun submitBarcode(raw: String?, fromManualField: Boolean) {
        val normalized = MovingBarcodeLookup.normalize(raw)
        if (normalized.isNullOrBlank()) {
            if (fromManualField) {
                Toast.makeText(this, getString(R.string.moving_barcode_empty), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!scanGate.tryAcquire(normalized)) {
            Log.d("ProductsActivity", "Barcode skipped by gate: $normalized")
            return
        }
        if (fromManualField) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(etManualBarcode.windowToken, 0)
        }
        processBarcodeScan(normalized, fromManualField)
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
    private fun addScannedProductToList(element: DocumentElement): Boolean {
        if (elements.none { it.elementId == element.elementId }) {
            elements.add(0, element)
            newProductInDocument.add(element)
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(0)
            updateEmptyStateVisibility()
            updateSaveButtonState()
            Log.d("ProductsActivity", "Added scanned element: ${element.name}. New list size: ${newProductInDocument.size}")
            return true
        }
        showAlertInfo("Этот товар уже отсканирован")
        return false
    }

    private fun presentFoundProduct(
        product: Product,
        storeAmounts: List<StoreAmountResponse.StoreProduct>?,
        currentIdDocument: Long,
        fromManualField: Boolean
    ) {
        val element = mapper.map(product, currentIdDocument)
        if (elements.none { it.elementId == element.elementId }) {
            Log.d("ProductsActivity", "Product not in list. Showing confirmation dialog for ${element.name}")
            showAlertScanInfo(element, storeAmounts) {
                Log.d("ProductsActivity", "Add confirmed for ${element.name}")
                if (addScannedProductToList(element) && fromManualField) {
                    etManualBarcode.setText("")
                }
            }
        } else {
            Log.d("ProductsActivity", "Product ${element.name} already in list.")
            showAlertInfo("Этот товар уже добавлен")
        }
    }


    private fun handleSaveButtonClick() {
        val currentIdDocument = idDocument ?: return
        if (isConducting) {
            Log.d("ProductsActivity", "Conduct ignored: already in progress")
            return
        }
        if (storeList.isEmpty()) {
            lifecycleScope.launch {
                progressBar.visibility = View.VISIBLE
                val stores = withContext(Dispatchers.IO) { service.getStoreList() }
                progressBar.visibility = View.GONE
                if (stores.isNotEmpty()) {
                    storeList = stores
                    adapter.updateStores(storeList)
                    updateSaveButtonState()
                    handleSaveButtonClick()
                } else {
                    showAlertInfo(MovingAvailabilityDisplay.STORES_NOT_LOADED_ALERT)
                }
            }
            return
        }
        if (!areAllFieldsFilled(elements)) {
            Toast.makeText(this, "Заполните все поля (Кол-во > 0, Склад Откуда/Куда, Склады не равны)", Toast.LENGTH_LONG).show()
            return
        }

        startConduct(currentIdDocument)
    }

    private fun startConduct(currentIdDocument: Int) {
        Log.d("ProductsActivity", "Save button clicked for document $currentIdDocument")
        isConducting = true
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
                        MovingConductRetrySync.promotePersistedAdds(newProductInDocument, baseList)
                        isConducting = false
                        progressBar.visibility = View.GONE
                        btnSave.isEnabled = true
                        updateSaveButtonState()
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

    

    
    private fun processBarcodeScan(barcode: String, fromManualField: Boolean) {
        val currentIdDocument = idDocumentLong
        if (currentIdDocument == null) {
            scanGate.release()
            return
        }

        progressBar.visibility = View.VISIBLE
        Log.d("ProductsActivity", "Processing barcode: $barcode for document $currentIdDocument")

        lifecycleScope.launch {
            var fetchedProduct: Product? = null
            var fetchedStoreAmounts: List<StoreAmountResponse.StoreProduct>? = null
            var ambiguousChoices: List<Pair<Product, List<StoreAmountResponse.StoreProduct>>>? = null
            var failureMessage: String? = null

            try {
                if (storeList.isEmpty()) {
                    val stores = withContext(Dispatchers.IO) { service.getStoreList() }
                    if (stores.isNotEmpty()) {
                        storeList = stores
                        adapter.updateStores(storeList)
                    }
                }
                if (storeList.isEmpty()) {
                    failureMessage = MovingAvailabilityDisplay.STORES_NOT_LOADED_ALERT
                }

                val lookupRaw = if (failureMessage == null) withContext(Dispatchers.IO) {
                    Log.d("ProductsActivity", "[IO] Getting product ID for barcode $barcode")
                    val response = apiBitrix.getProductIdByBarcode(barcode)
                    if (response.isSuccessful) {
                        response.body()?.result
                    } else {
                        Log.w("ProductsActivity", "[IO] getProductIdByBarcode failed with code: ${response.code()}")
                        failureMessage = "Ошибка при поиске штрихкода (${response.code()})."
                        null
                    }
                } else {
                    null
                }

                if (failureMessage == null) {
                    when (val match = MovingBarcodeLookup.parseProductIds(lookupRaw)) {
                        MovingBarcodeLookup.Match.NotFound -> {
                            Log.w("ProductsActivity", "[IO] barcode '$barcode' was not found. raw=$lookupRaw")
                            failureMessage = getString(R.string.moving_barcode_not_found, barcode)
                        }
                        is MovingBarcodeLookup.Match.Single -> {
                            val productIdInt = match.productId.toIntOrNull()
                            val loaded = if (productIdInt == null) null else loadProductForBarcode(match.productId)
                            fetchedProduct = loaded
                            if (loaded == null || productIdInt == null) {
                                failureMessage = "Не удалось получить детали товара (ID: ${match.productId})."
                            } else {
                                fetchedStoreAmounts = service.getStoreForScan(productIdInt)
                            }
                        }
                        is MovingBarcodeLookup.Match.Ambiguous -> {
                            val choices = mutableListOf<Pair<Product, List<StoreAmountResponse.StoreProduct>>>()
                            for (productId in match.productIds) {
                                val loaded = loadProductForBarcode(productId) ?: continue
                                val amounts = productId.toIntOrNull()?.let { service.getStoreForScan(it) }.orEmpty()
                                choices.add(loaded to amounts)
                            }
                            if (choices.isEmpty()) {
                                failureMessage = getString(R.string.moving_barcode_ambiguous, barcode)
                            } else if (choices.size == 1) {
                                fetchedProduct = choices.first().first
                                fetchedStoreAmounts = choices.first().second
                            } else {
                                ambiguousChoices = choices
                            }
                        }
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

                val singleProduct = fetchedProduct
                val choices = ambiguousChoices
                val messageToShow = failureMessage
                when {
                    singleProduct != null -> {
                        presentFoundProduct(singleProduct, fetchedStoreAmounts, currentIdDocument, fromManualField)
                    }
                    choices != null -> {
                        showAlert(
                            getString(R.string.moving_barcode_ambiguous, barcode),
                            choices.map { it.first }
                        ) { selected ->
                            if (selected == null) return@showAlert
                            val amounts = choices.firstOrNull { it.first.id == selected.id }?.second
                            presentFoundProduct(selected, amounts, currentIdDocument, fromManualField)
                        }
                    }
                    messageToShow != null -> {
                        Log.w("ProductsActivity", "Scan processing failed or product not found: $messageToShow")
                        showAlertInfo(messageToShow)
                    }
                }
            }
            scanGate.release()
        }
    }

    private suspend fun loadProductForBarcode(productId: String): Product? = withContext(Dispatchers.IO) {
        Log.d("ProductsActivity", "[IO] Getting product details for ID $productId")
        val request = ProductIdRequest(id = productId)
        val response = apiBitrix.getProductById(request)
        if (!response.isSuccessful) {
            Log.w("ProductsActivity", "[IO] getProductById failed for $productId: ${response.code()}")
            return@withContext null
        }
        val details = response.body()?.result?.get("product") as? LinkedTreeMap<*, *>
        details?.let { productMapper.mapToProduct(it) }?.also {
            Log.d("ProductsActivity", "[IO] Product details fetched: ${it.name}")
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
                        val storeId = storeProduct.storeId.toInt()
                        val storeName = storeList.find { it.id == storeId }?.title ?: "Склад ID $storeId"
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
        val storesMissing = storeList.isEmpty() && elements.isNotEmpty()
        val canSave = !storesMissing && areAllFieldsFilled(elements)
        Log.d("ProductsActivity", "Updating save button state. CanSave: $canSave storesMissing: $storesMissing")
        btnSave.isEnabled = canSave || storesMissing
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