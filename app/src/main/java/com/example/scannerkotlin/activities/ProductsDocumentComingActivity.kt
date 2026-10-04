package com.example.scannerkotlin.activities

import android.annotation.SuppressLint
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.scannerkotlin.R
import com.example.scannerkotlin.adapter.CustomProductDialogAdapter
import com.example.scannerkotlin.adapter.ProductComingAdapter
import com.example.scannerkotlin.listener.OnItemClickListener
import com.example.scannerkotlin.mappers.ProductMapper
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.ProductOffer
import com.example.scannerkotlin.response.ConductResult
import com.example.scannerkotlin.service.CatalogDocumentComingService
import com.example.scannerkotlin.utils.ComingMeasureAutoResolver
import com.example.scannerkotlin.utils.ComingProductCopyFactory
import com.example.scannerkotlin.utils.BaseScanActivity
import com.example.scannerkotlin.utils.ComingProductValidator
import com.example.scannerkotlin.utils.ComingDocumentDraftManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.O)
class ProductsDocumentComingActivity : BaseScanActivity(), OnItemClickListener {

    private lateinit var btnSave: Button
    private lateinit var btnAddProduct: Button
    private lateinit var adapter: ProductComingAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var service: CatalogDocumentComingService
    private lateinit var emptyProductListTextView: TextView

    private lateinit var productMapper: ProductMapper


    private val baseList = mutableListOf<Product>()
    private val productList = mutableListOf<Product>()
    private val productOffersList = mutableListOf<ProductOffer>()
    private val deletedProductsList = mutableListOf<Product>()


    private var idDocument: Int? = null

    private var currentDocumentComment: String? = null

    private var productsForSelectionId: ArrayList<Double?>? = null

    private var isSaved = false
    private var isSaving = false

    override fun onBarcodeScanned(barcode: String) {
        handleBarcodeScanResult(barcode)
    }


    @SuppressLint("UnspecifiedRegisterReceiverFlag", "NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_document_products)
        setupWindowInsets()

        idDocument = intent.getStringExtra("idDocument")?.toIntOrNull()

        @Suppress("DEPRECATION")
        productsForSelectionId = intent.getSerializableExtra("field620Values") as? ArrayList<Double?>


        if (idDocument == null) {
            Log.e("ProductsComingActivity", "Invalid or missing 'idDocument' extra for a new document.")
        }


        initializeDependencies()
        initializeViews()
        setupRecyclerView()
        setupUI()
        setupButtonClickListeners()

        loadInitialState()

        updateSaveButtonState()
        updateEmptyStateVisibility()
    }

    private fun setupWindowInsets() {
        val root = findViewById<View>(R.id.comingProductsRoot)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialLeft + insets.left,
                initialTop + insets.top,
                initialRight + insets.right,
                initialBottom + insets.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }
    @SuppressLint("NotifyDataSetChanged")
    private fun loadInitialState() {

        val draftProducts = idDocument?.let { ComingDocumentDraftManager.getDraftProducts(it) }
        val draftComment = idDocument?.let { ComingDocumentDraftManager.getDraftComment(it) }

        if (draftProducts != null) {
            Log.d("ProductsComingActivity", "Loading draft for document ID: $idDocument")
            productList.clear()
            productList.addAll(draftProducts)


            productOffersList.clear()
            draftProducts.forEach { product ->
                productOffersList.add(createProductOffer(product))
            }

            this.currentDocumentComment = draftComment

            adapter.setCurrentDocumentComment(draftComment)
            adapter.notifyDataSetChanged()
        } else {
            Log.d("ProductsComingActivity", "No draft found. Starting with an empty list.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        idDocument?.let { docId ->
            if (!isSaved) {
                if (productList.isNotEmpty()) {
                    Log.d("ProductsComingActivity", "Saving draft for document ID: $docId")
                    ComingDocumentDraftManager.saveDraft(docId, productList, currentDocumentComment)
                } else {
                    ComingDocumentDraftManager.clearDraft(docId)
                }
            }
        }
    }


    private fun initializeDependencies() {
        service = CatalogDocumentComingService()
        productMapper = ProductMapper()
    }

    private fun initializeViews() {
        btnSave = findViewById(R.id.btnSave)
        btnAddProduct = findViewById(R.id.btnAddProduct)
        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.rvProducts)
        emptyProductListTextView = findViewById(R.id.emptyProductListTextView)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupRecyclerView() {
        // Keep original vertical paging. With fillViewport=false and a compact card,
        // NestedScrollView only intercepts when content actually overflows.
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = ProductComingAdapter(
            productList,
            onDelete = { position ->
                handleItemDeletion(position)
            },
            onCopy = { position ->
                handleItemCopy(position)
            },
            currentDocumentComment = this.currentDocumentComment,
            onCommentChanged = { newComment ->
                this.currentDocumentComment = newComment
                Log.d("ProductsComingActivity", "Document comment changed to: $newComment")
            }
        )

        adapter.setOnDataChangedListener {
            updateSaveButtonState()
        }
        recyclerView.adapter = adapter

        adapter.setRecyclerView(recyclerView)

        val snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)
    }

    private fun setupUI() {

        val title = intent.getStringExtra("title") ?: "Новый приход товара"
        findViewById<TextView>(R.id.mainTitle).text = title
        supportActionBar?.title = title
        Log.d("ProductsComingActivity", "UI setup for new document: Title='$title'")
    }

    private fun setupButtonClickListeners() {
        btnSave.setOnClickListener {
            currentFocus?.clearFocus()
            handleSaveButtonClick() }
        btnAddProduct.setOnClickListener { handleAddProductClick() }
    }

    private fun handleAddProductClick() {
        Log.d("ProductsComingActivity", "Add product button clicked")
        progressBar.visibility = View.VISIBLE
        btnAddProduct.isEnabled = false

        lifecycleScope.launch {
            var productsForSelection: List<Product> = emptyList()
            var error: Throwable? = null
            val iblockId = com.example.scannerkotlin.config.BitrixConfig.iblockProducts
            try {
                productsForSelection = service.getProductsForSelection(iblockId, productsForSelectionId)
                Log.d("ProductsComingActivity", "Loaded ${productsForSelection.size} products for selection via service")
            } catch (e: Exception) {
                Log.e("ProductsComingActivity", "Error getting products for selection via service", e)
                error = e
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnAddProduct.isEnabled = true
                    if (isActive) {
                        if (error == null) {
                            if (productsForSelection.isEmpty()) {
                                showAlert("Нет доступных продуктов", emptyList()) {}
                            } else {
                                showAlert("Выберите продукт", productsForSelection) { selectedProduct ->
                                    handleProductSelection(selectedProduct)
                                }
                            }
                        } else {
                            Toast.makeText(this@ProductsDocumentComingActivity, "Ошибка загрузки списка продуктов: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }



    @SuppressLint("NotifyDataSetChanged")
    private fun handleProductSelection(selectedProduct: Product?) {
        if (selectedProduct != null) {
            Toast.makeText(this, "Вы выбрали: ${selectedProduct.name}", Toast.LENGTH_SHORT).show()
            val listWasEmpty = productList.isEmpty()

            selectedProduct.measureName = ComingMeasureAutoResolver.resolve(selectedProduct)

            if (selectedProduct.quantity == null || selectedProduct.quantity == 0.0) {
                selectedProduct.quantity = 1.0
            }

            productList.add(0, selectedProduct)
            val offer = createProductOffer(selectedProduct)

            productOffersList.add(offer)

            if (listWasEmpty) {
                adapter.notifyDataSetChanged()
            } else {
                adapter.notifyItemInserted(0)
            }


            recyclerView.scrollToPosition(0)


            recyclerView.post {
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(0)
                if (viewHolder is ProductComingAdapter.ProductViewHolder) {
                    viewHolder.tvBarcode.requestFocus()
                }
            }

            updateEmptyStateVisibility()
            updateSaveButtonState()

        } else {
            Toast.makeText(this, "Выбор отменён", Toast.LENGTH_SHORT).show()
        }
    }


    private fun createProductOffer(product: Product): ProductOffer {
        return ProductOffer(product)
    }


    @SuppressLint("NotifyDataSetChanged")
    private fun handleItemDeletion(position: Int) {
        if (position < 0 || position >= productList.size) return
        val removedProduct = productList.removeAt(position)
        Log.d("ProductsComingActivity", "Removed product: ${removedProduct.name} at position $position")


        if (baseList.any { it.idInDocument == removedProduct.idInDocument && removedProduct.idInDocument != null }) {
            deletedProductsList.add(removedProduct)
            Log.d("ProductsComingActivity", "Added to deletedProductsList. Size: ${deletedProductsList.size}")
        }
        val removedOffer = productOffersList.removeAll { it.product.id == removedProduct.id }
        if (removedOffer) {
            Log.d("ProductsComingActivity", "Removed from productOffersList.")
        }

        adapter.notifyDataSetChanged()
        updateSaveButtonState()
        updateEmptyStateVisibility()
    }

    private fun handleItemCopy(position: Int) {
        if (position < 0 || position >= productList.size) return
        val source = productList[position]
        val clone = ComingProductCopyFactory.cloneWithoutValues(source)
        val insertPosition = (position + 1).coerceAtMost(productList.size)
        productList.add(insertPosition, clone)
        productOffersList.add(insertPosition, createProductOffer(clone))
        adapter.notifyItemInserted(insertPosition)
        recyclerView.scrollToPosition(insertPosition)
        updateSaveButtonState()
        updateEmptyStateVisibility()
    }


    private fun handleSaveButtonClick() {

        val currentIdDocumentToSave = idDocument


        val validationErrors = validateProductsAndCollectErrors()
        if (validationErrors.isNotEmpty()) {
            showValidationAlert(validationErrors)
            return
        }

        Log.d("ProductsComingActivity", "Save button clicked for document ID: $currentIdDocumentToSave")
        isSaving = true
        progressBar.visibility = View.VISIBLE
        updateSaveButtonState()


        lifecycleScope.launch {
            var result: ConductResult? = null

            try {
                Log.d("ProductsComingActivity", "Calling conductDocumentSuspend: productList=${productList.size}, productOffersList=${productOffersList.size}")
                result = service.conductDocumentSuspend(
                    baseList = baseList,
                    idDocument = currentIdDocumentToSave,
                    context = this@ProductsDocumentComingActivity,
                    deletedProducts = deletedProductsList,
                    updatedProducts = productList,
                    currentProductListState = productList,
                    productOffersList = productOffersList,
                    documentComment = currentDocumentComment
                )
                Log.d("ProductsComingActivity", "conductDocumentSuspend returned: success=${result.isSuccess}, message=${result.message}")
            } catch (e: Exception) {
                Log.e("ProductsComingActivity", "Critical error calling conductDocumentSuspend", e)

                result = ConductResult(false, "Критическая ошибка: ${e.message}")
            } finally {

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    isSaving = false

                    if (isActive) {
                        if (result?.isSuccess == true) {
                            // УСПЕХ
                            Log.d("ProductsComingActivity", "Conduct successful.")
                            Toast.makeText(
                                this@ProductsDocumentComingActivity,
                                "Документ успешно сохранен",
                                Toast.LENGTH_SHORT
                            ).show()

                            isSaved = true
                            if (currentIdDocumentToSave != null) {
                                ComingDocumentDraftManager.clearDraft(currentIdDocumentToSave)
                            }


                            val intent = Intent(
                                this@ProductsDocumentComingActivity,
                                MainActivity::class.java
                            )
                            intent.flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                            finish()
                        } else {


                            val errorMessage = result?.message ?: "Произошла неизвестная ошибка."
                            showErrorAlert(errorMessage)
                            updateSaveButtonState()
                        }
                    }
                }
            }
        }
    }
    private fun showErrorAlert(message: String) {
        if (!isFinishing && !isDestroyed) {
            MaterialAlertDialogBuilder(this, R.style.MyCustomMaterialAlertDialogStyle)
                .setTitle("Ошибка сохранения")
                .setMessage(message)
                .setPositiveButton("Понятно") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }


    private fun handleBarcodeScanResult(barcode: String) {
        Log.d("ProductsComingActivity", "Handling barcode scan result: $barcode")
        val success = adapter.updateFocusedProductBarcode(barcode)
        if (success) {
            Log.d("ProductsComingActivity", "Barcode updated in adapter.")
            Toast.makeText(this, "Штрихкод '$barcode' установлен", Toast.LENGTH_SHORT).show()
            updateSaveButtonState()
        } else {
            Log.w("ProductsComingActivity", "Failed to update barcode in adapter (no focused product?).")
            if (productList.isNotEmpty()) {
                showManualBarcodeTargetDialog(barcode)
            } else {
                Toast.makeText(this, "Сначала добавьте товар в документ", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showManualBarcodeTargetDialog(barcode: String) {
        val productTitles = productList.mapIndexed { index, product ->
            "${index + 1}. ${product.name ?: "Без названия"}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this, R.style.MyCustomMaterialAlertDialogStyle)
            .setTitle("Выберите товар для штрихкода")
            .setItems(productTitles) { dialog, which ->
                val set = adapter.updateProductBarcodeAt(which, barcode)
                if (set) {
                    updateSaveButtonState()
                    Toast.makeText(this, "Штрихкод '$barcode' установлен вручную", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .show()
    }


    private fun showAlert(
        title: String,
        products: List<Product>,
        onProductSelected: (Product?) -> Unit
    ) {
        if (!isFinishing && !isDestroyed) {

            val builder = MaterialAlertDialogBuilder(this, R.style.MyCustomMaterialAlertDialogStyle)
            builder.setTitle(title)

            if (products.isEmpty()) {
                builder.setMessage("Нет доступных вариантов.")
                builder.setPositiveButton("OK") { dialog, _ ->
                    onProductSelected(null)
                    dialog.dismiss()
                }
            } else {
                val listAdapter = CustomProductDialogAdapter(this, products)
                builder.setAdapter(listAdapter) { dialog, which ->
                    val selectedProduct = listAdapter.getItem(which)
                    onProductSelected(selectedProduct)
                    dialog.dismiss()
                }
                builder.setNegativeButton("Отмена") { dialog, _ ->
                    onProductSelected(null)
                    dialog.dismiss()
                }
            }

            val dialog = builder.create()
            dialog.setOnShowListener {

                val listView = dialog.listView
                if (listView != null) {

                    listView.divider = null
                    listView.dividerHeight = 0


                    listView.setPadding(0, 8, 0, 8)
                }
            }
            dialog.show()
        } else {
            Log.w("YourActivityName", "Attempted to show alert dialog on finishing/destroyed activity")
        }
    }

    private fun areAllFieldsFilled(): Boolean {
        if (productList.isEmpty()) {
            return false
        }
        return adapter.areAllBarcodesFilledInUI()
    }

    private fun updateSaveButtonState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updateSaveButtonStateInternal() }
        } else {
            updateSaveButtonStateInternal()
        }
    }

    private fun updateSaveButtonStateInternal() {
        Log.d("ProductsComingActivity", "Updating save button state. isSaving=$isSaving")
        val missingCount = adapter.findProductsMissingBarcodes().size
        val canInteract = !isSaving
        btnSave.isEnabled = canInteract
        btnSave.setBackgroundColor(ContextCompat.getColor(this, R.color.field_blue))
        btnSave.text = if (missingCount > 0) "Заполните штрихкоды: $missingCount" else getString(R.string.btnSave)
    }

    private fun validateProductsAndCollectErrors(): List<String> {
        val errors = ComingProductValidator.validate(productList)
        if (errors.isNotEmpty()) {
            val pos = adapter.findFirstMissingBarcodePosition()
            if (pos >= 0) {
                recyclerView.scrollToPosition(pos)
            }
        }
        return errors
    }

    private fun showValidationAlert(messages: List<String>) {
        if (messages.isEmpty()) return
        val builder = MaterialAlertDialogBuilder(this, R.style.MyCustomMaterialAlertDialogStyle)
        builder.setTitle("Проверьте заполнение полей")
        builder.setMessage(messages.joinToString("\n"))
        builder.setPositiveButton("Понял") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    private fun updateEmptyStateVisibility() {
        if (productList.isEmpty()) {
            emptyProductListTextView.text = "Список товаров пуст. Добавьте товары."
            emptyProductListTextView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyProductListTextView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }

    }

    override fun onItemClick(title: String, idDocument: String, field620Values: List<Double>?) {
        TODO("Not yet implemented")
    }
}