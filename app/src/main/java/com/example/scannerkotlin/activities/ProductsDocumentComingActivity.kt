package com.example.scannerkotlin.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
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
import com.example.scannerkotlin.config.BitrixConfig
import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.ProductOffer
import com.example.scannerkotlin.model.SaveOutcome
import com.example.scannerkotlin.persistence.ComingDraftRepository
import com.example.scannerkotlin.response.ConductResult
import com.example.scannerkotlin.service.CatalogDocumentComingService
import com.example.scannerkotlin.utils.BarcodeScanGate
import com.example.scannerkotlin.utils.BarcodeSanitizer
import com.example.scannerkotlin.utils.BaseScanActivity
import com.example.scannerkotlin.utils.ComingProductCopyFactory
import com.example.scannerkotlin.utils.ComingProductValidator
import com.example.scannerkotlin.utils.InFlightGuard
import com.example.scannerkotlin.utils.TsdLogger
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.O)
class ProductsDocumentComingActivity : BaseScanActivity() {

    private lateinit var btnSave: Button
    private lateinit var btnAddProduct: Button
    private lateinit var adapter: ProductComingAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var service: CatalogDocumentComingService
    private lateinit var draftRepository: ComingDraftRepository
    private lateinit var emptyProductListTextView: TextView
    private lateinit var tvStatusHeader: TextView
    private lateinit var tvSaveIndicator: TextView
    private lateinit var layoutManager: LinearLayoutManager

    private val baseList = mutableListOf<Product>()
    private val productList = mutableListOf<Product>()
    private val deletedProductsList = mutableListOf<Product>()

    private var idDocument: Int? = null
    private var documentTitle: String? = null
    private var destinationStoreId: Int = 1
    private var currentDocumentComment: String? = null
    private var productsForSelectionId: ArrayList<Double?>? = null
    private var currentOperationState: ComingOperationState = ComingOperationState.DRAFT

    private var isCommitted = false
    private var isSaving = false
    private var isManualTargetDialogShowing = false
    private var debouncedSaveJob: Job? = null

    private val conductGuard = InFlightGuard(minDurationMs = 1_000L)
    private val scanGate = BarcodeScanGate(duplicateWindowMs = 420L)

    // Non-cancelling background scope so onPause/onStop disk flushes always complete even if Activity finishes
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBarcodeScanned(barcode: String) {
        lifecycleScope.launch {
            handleBarcodeScanSequential(barcode)
        }
    }

    override suspend fun onBarcodeScannedSequential(barcode: String) {
        handleBarcodeScanSequential(barcode)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag", "NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_document_products)
        setupWindowInsets()

        idDocument = intent.getStringExtra("idDocument")?.toIntOrNull()
        documentTitle = intent.getStringExtra("title")

        @Suppress("DEPRECATION")
        productsForSelectionId = intent.getSerializableExtra("field620Values") as? ArrayList<Double?>

        initializeDependencies()
        initializeViews()
        setupRecyclerView()
        setupUI()
        setupButtonClickListeners()

        loadInitialState()
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

    private fun initializeDependencies() {
        draftRepository = ComingDraftRepository.getInstance()
        service = CatalogDocumentComingService(draftRepository = draftRepository)
    }

    private fun initializeViews() {
        btnSave = findViewById(R.id.btnSave)
        btnAddProduct = findViewById(R.id.btnAddProduct)
        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.rvProducts)
        emptyProductListTextView = findViewById(R.id.emptyProductListTextView)
        tvStatusHeader = findViewById(R.id.tvArrivalNumber)
        tvSaveIndicator = findViewById(R.id.tvArrivalDate)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupRecyclerView() {
        layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager
        recyclerView.setHasFixedSize(true)
        recyclerView.itemAnimator = null
        recyclerView.clipToPadding = true
        recyclerView.clipChildren = true

        adapter = ProductComingAdapter(
            productList = productList,
            onDelete = { position -> handleItemDeletion(position) },
            onCopy = { position -> handleItemCopy(position) },
            currentDocumentComment = this.currentDocumentComment,
            onCommentChanged = { newComment ->
                this.currentDocumentComment = newComment
                scheduleDebouncedSnapshotSave()
            }
        )

        adapter.setOnDataChangedListener {
            updateHeaderAndSaveButtonState()
            scheduleDebouncedSnapshotSave()
        }
        recyclerView.adapter = adapter
        adapter.setRecyclerView(recyclerView)

        // Safe deferred page-height refresh: NEVER call notifyDataSetChanged synchronously inside onLayoutChange!
        var lastRvHeight = 0
        recyclerView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = v.height
            if (h > 0 && h != lastRvHeight && adapter.itemCount > 0) {
                lastRvHeight = h
                v.post {
                    if (!isFinishing && !isDestroyed && !recyclerView.isComputingLayout) {
                        adapter.notifyItemRangeChanged(0, adapter.itemCount)
                    }
                }
            }
        }

        val snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateStatusHeaderText()
                }
            }
        })
    }

    private fun setupUI() {
        val title = documentTitle ?: "Новый приход товара"
        findViewById<TextView>(R.id.mainTitle).text = title
        supportActionBar?.title = title
        updateHeaderAndSaveButtonState()
    }

    private fun setupButtonClickListeners() {
        btnSave.setOnClickListener {
            currentFocus?.clearFocus()
            handleSaveButtonClick()
        }
        btnAddProduct.setOnClickListener {
            handleAddProductClick()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadInitialState() {
        val docId = idDocument
        if (docId == null) {
            updateEmptyStateVisibility()
            updateHeaderAndSaveButtonState()
            return
        }

        lifecycleScope.launch {
            val draft = draftRepository.loadOrInitializeDraft(
                documentId = docId,
                documentTitle = documentTitle,
                allowedNomenclatureIds = productsForSelectionId?.filterNotNull(),
                destinationStoreId = destinationStoreId
            )

            TsdLogger.activeDocumentId = docId
            TsdLogger.activeOperationId = draft.operationId

            if (productsForSelectionId.isNullOrEmpty() && !draft.allowedNomenclatureIds.isNullOrEmpty()) {
                productsForSelectionId = ArrayList(draft.allowedNomenclatureIds)
            }
            if (documentTitle.isNullOrBlank() && !draft.documentTitle.isNullOrBlank()) {
                documentTitle = draft.documentTitle
                findViewById<TextView>(R.id.mainTitle).text = documentTitle
            }
            destinationStoreId = draft.destinationStoreId
            currentOperationState = draft.state
            currentDocumentComment = draft.comment

            productList.clear()
            productList.addAll(draft.items.map { it.detachedCopy(preserveLocalItemId = true) })

            adapter.setCurrentDocumentComment(currentDocumentComment)
            adapter.notifyDataSetChanged()
            updateEmptyStateVisibility()
            updateHeaderAndSaveButtonState()

            if (draft.items.isNotEmpty()) {
                setSaveIndicatorSaved(draft.updatedAtMs, restored = true)
            }

            // Automatic server reconciliation if previous attempt was interrupted during PROCESSING
            if (draft.state == ComingOperationState.RECONCILIATION_REQUIRED) {
                runAutoReconciliation(docId)
            }
        }
    }

    private suspend fun runAutoReconciliation(docId: Int) {
        tvSaveIndicator.text = "Сверка с Битрикс..."
        progressBar.visibility = View.VISIBLE
        isSaving = true
        updateHeaderAndSaveButtonState()

        val outcome = service.reconcileDocumentWithServerSuspend(docId, productList)

        progressBar.visibility = View.GONE
        isSaving = false

        when (outcome) {
            is CatalogDocumentComingService.ReconciliationResult.AlreadyConducted -> {
                isCommitted = true
                currentOperationState = ComingOperationState.COMMITTED
                updateHeaderAndSaveButtonState()
                if (!isFinishing && !isDestroyed) {
                    MaterialAlertDialogBuilder(this, R.style.MyCustomMaterialAlertDialogStyle)
                        .setTitle("Сверка завершена")
                        .setMessage("Документ №$docId уже был успешно проведён на сервере Битрикс. Повторное проведение не требуется.")
                        .setCancelable(false)
                        .setPositiveButton("В главное меню") { dialog, _ ->
                            dialog.dismiss()
                            navigateToMain()
                        }
                        .show()
                }
            }
            is CatalogDocumentComingService.ReconciliationResult.ReadyToContinue -> {
                currentOperationState = ComingOperationState.READY
                adapter.notifyDataSetChanged()
                setSaveIndicatorSaved(System.currentTimeMillis())
                updateHeaderAndSaveButtonState()
                Toast.makeText(
                    this,
                    "Сверка выполнена: документ не проведён, можно продолжить работу.",
                    Toast.LENGTH_LONG
                ).show()
            }
            is CatalogDocumentComingService.ReconciliationResult.Blocked -> {
                currentOperationState = if (outcome.keepReconciliationRequired) {
                    ComingOperationState.RECONCILIATION_REQUIRED
                } else {
                    ComingOperationState.FAILED
                }
                updateHeaderAndSaveButtonState()
                showErrorAlert(outcome.reason)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        flushSnapshotImmediately()
    }

    override fun onDestroy() {
        debouncedSaveJob?.cancel()
        if (TsdLogger.activeDocumentId == idDocument) {
            TsdLogger.activeDocumentId = null
            TsdLogger.activeOperationId = null
        }
        super.onDestroy()
    }

    private fun scheduleDebouncedSnapshotSave() {
        val docId = idDocument ?: return
        if (isCommitted) return
        debouncedSaveJob?.cancel()
        tvSaveIndicator.text = "Сохранение..."
        debouncedSaveJob = lifecycleScope.launch {
            delay(180L)
            val snapshot = productList.map { it.detachedCopy(preserveLocalItemId = true) }
            val commentSnapshot = currentDocumentComment
            val allowedSnapshot = productsForSelectionId?.filterNotNull()
            val outcome = draftRepository.saveSnapshot(
                documentId = docId,
                documentTitle = documentTitle,
                destinationStoreId = destinationStoreId,
                allowedNomenclatureIds = allowedSnapshot,
                products = snapshot,
                comment = commentSnapshot,
                deletedRemoteIds = deletedProductsList.mapNotNull { it.idInDocument }
            )
            if (outcome is SaveOutcome.Saved && isActive) {
                setSaveIndicatorSaved(outcome.updatedAtMs)
            } else if (outcome is SaveOutcome.Failed && isActive) {
                tvSaveIndicator.text = "Ошибка записи!"
            }
        }
    }

    private fun flushSnapshotImmediately() {
        val docId = idDocument ?: return
        if (isCommitted) return
        debouncedSaveJob?.cancel()
        val snapshot = productList.map { it.detachedCopy(preserveLocalItemId = true) }
        val commentSnapshot = currentDocumentComment
        val titleSnapshot = documentTitle
        val storeSnapshot = destinationStoreId
        val allowedSnapshot = productsForSelectionId?.filterNotNull()
        val deletedIds = deletedProductsList.mapNotNull { it.idInDocument }

        flushScope.launch {
            if (snapshot.isEmpty() && commentSnapshot.isNullOrBlank() &&
                currentOperationState == ComingOperationState.DRAFT
            ) {
                return@launch
            }
            draftRepository.saveSnapshot(
                documentId = docId,
                documentTitle = titleSnapshot,
                destinationStoreId = storeSnapshot,
                allowedNomenclatureIds = allowedSnapshot,
                products = snapshot,
                comment = commentSnapshot,
                deletedRemoteIds = deletedIds
            )
        }
    }

    private fun setSaveIndicatorSaved(timestampMs: Long, restored: Boolean = false) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
        tvSaveIndicator.setTextColor(ContextCompat.getColor(this, R.color.green))
        tvSaveIndicator.text = if (restored) "Восстановлено • $timeStr" else "Сохранено • $timeStr"
    }

    private fun handleAddProductClick() {
        progressBar.visibility = View.VISIBLE
        btnAddProduct.isEnabled = false

        lifecycleScope.launch {
            var productsForSelection: List<Product> = emptyList()
            var error: Throwable? = null
            val iblockId = BitrixConfig.iblockProducts
            try {
                productsForSelection = service.getProductsForSelection(iblockId, productsForSelectionId)
            } catch (e: Exception) {
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
                                    if (selectedProduct != null) {
                                        lifecycleScope.launch {
                                            addNewProductAndPersist(selectedProduct)
                                        }
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(
                                this@ProductsDocumentComingActivity,
                                "Ошибка загрузки списка продуктов: ${error.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * Persists the newly selected nomenclature position to SQLite BEFORE confirming in UI.
     */
    @SuppressLint("NotifyDataSetChanged")
    private suspend fun addNewProductAndPersist(selectedTemplate: Product) {
        val detached = ComingProductCopyFactory.newManualPosition(
            template = selectedTemplate,
            destinationStoreId = destinationStoreId
        )

        val docId = idDocument
        if (docId != null) {
            tvSaveIndicator.text = "Сохранение..."
            val outcome = draftRepository.saveAddedOrUpdatedItem(
                documentId = docId,
                documentTitle = documentTitle,
                destinationStoreId = destinationStoreId,
                allowedNomenclatureIds = productsForSelectionId?.filterNotNull(),
                item = detached,
                insertAtTop = true
            )
            if (outcome is SaveOutcome.Failed) {
                showErrorAlert(outcome.errorMessage)
                return
            }
            if (outcome is SaveOutcome.Saved) {
                setSaveIndicatorSaved(outcome.updatedAtMs)
            }
        }

        val listWasEmpty = productList.isEmpty()
        productList.add(0, detached)
        if (listWasEmpty) {
            adapter.notifyDataSetChanged()
        } else {
            adapter.notifyItemInserted(0)
        }
        recyclerView.scrollToPosition(0)
        recyclerView.post {
            val vh = recyclerView.findViewHolderForAdapterPosition(0)
            if (vh is ProductComingAdapter.ProductViewHolder) {
                vh.tvBarcode.requestFocus()
            }
        }
        updateEmptyStateVisibility()
        updateHeaderAndSaveButtonState()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun handleItemDeletion(position: Int) {
        if (position !in 0 until productList.size || isSaving) return
        val removedProduct = productList.removeAt(position)

        val remoteIdToDelete = removedProduct.idInDocument?.takeIf { docElId ->
            baseList.any { it.idInDocument == docElId }
        }
        if (remoteIdToDelete != null) {
            deletedProductsList.add(removedProduct)
        }

        if (productList.isEmpty()) {
            adapter.notifyDataSetChanged()
        } else {
            adapter.notifyItemRemoved(position)
            adapter.notifyItemRangeChanged(position, adapter.itemCount - position)
        }
        updateEmptyStateVisibility()
        updateHeaderAndSaveButtonState()

        val docId = idDocument ?: return
        lifecycleScope.launch {
            val outcome = draftRepository.removeItem(
                documentId = docId,
                localItemId = removedProduct.localItemId,
                remoteElementIdToDelete = remoteIdToDelete
            )
            if (outcome is SaveOutcome.Saved) {
                setSaveIndicatorSaved(outcome.updatedAtMs)
            }
        }
    }

    private fun handleItemCopy(position: Int) {
        if (position !in 0 until productList.size || isSaving) return
        val source = productList[position]
        val clone = ComingProductCopyFactory.newCopiedPosition(
            source = source,
            destinationStoreId = destinationStoreId
        )
        val insertPosition = (position + 1).coerceAtMost(productList.size)
        productList.add(insertPosition, clone)
        adapter.notifyItemInserted(insertPosition)
        recyclerView.scrollToPosition(insertPosition)
        recyclerView.post {
            val vh = recyclerView.findViewHolderForAdapterPosition(insertPosition)
            if (vh is ProductComingAdapter.ProductViewHolder) {
                vh.tvBarcode.requestFocus()
            }
        }
        updateEmptyStateVisibility()
        updateHeaderAndSaveButtonState()
        scheduleDebouncedSnapshotSave()
    }

    /**
     * Sequential, persistence-first handler for hardware barcode scans:
     * 1. Suppresses accidental duplicate hardware trigger bounce (< 420ms).
     * 2. If barcode already exists in the document (intentional repeat scan): atomically increments
     *    that item's quantity in SQLite BEFORE confirming in UI.
     * 3. If focused or visible/next item has an empty barcode: persists barcode to SQLite BEFORE
     *    confirming in UI.
     * 4. If all existing items already have barcodes and an active nomenclature template is present:
     *    clones a new position of that nomenclature with the scanned barcode, persists to SQLite FIRST,
     *    and inserts at position 0 — enabling continuous 100/500/1000-item TSD scanning without dialogs
     *    and without overwriting already-scanned barcodes!
     */
    @SuppressLint("NotifyDataSetChanged")
    private suspend fun handleBarcodeScanSequential(rawBarcode: String) {
        if (isSaving) return
        val normalizedBarcode = BarcodeSanitizer.normalizeForComingVariation(rawBarcode)
        if (normalizedBarcode.isNullOrBlank()) {
            return
        }
        if (!scanGate.tryAcquire(normalizedBarcode)) {
            TsdLogger.info(
                category = "SCANNER",
                event = "coming_duplicate_window_skipped",
                documentId = idDocument,
                fields = mapOf("barcode" to normalizedBarcode)
            )
            return
        }

        try {
            val docId = idDocument

            // Case 1: Intentional repeat scan of an already-added barcode -> atomic quantity +1
            val existingPos = adapter.findPositionByBarcode(normalizedBarcode)
            if (existingPos >= 0) {
                val targetItem = productList[existingPos]
                if (docId != null) {
                    val (saveOutcome, persisted) = draftRepository.updateQuantityAtomically(
                        documentId = docId,
                        localItemId = targetItem.localItemId,
                        delta = 1.0
                    )
                    if (saveOutcome is SaveOutcome.Failed || persisted == null) {
                        showErrorAlert((saveOutcome as? SaveOutcome.Failed)?.errorMessage ?: "Ошибка сохранения количества")
                        return
                    }
                    adapter.applyPersistedQuantityAt(existingPos, persisted.quantity)
                    setSaveIndicatorSaved(saveOutcome.let { (it as SaveOutcome.Saved).updatedAtMs })
                } else {
                    val newQty = (targetItem.quantity ?: 0.0) + 1.0
                    adapter.applyPersistedQuantityAt(existingPos, newQty)
                }
                recyclerView.scrollToPosition(existingPos)
                updateHeaderAndSaveButtonState()
                Toast.makeText(
                    this,
                    "Штрихкод '$normalizedBarcode': кол-во ${productList[existingPos].quantity}",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            if (productList.isEmpty()) {
                Toast.makeText(
                    this,
                    "Сначала выберите номенклатуру («Добавить товар»)",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Case 2: Focused card has blank barcode, OR current visible snapped card has blank barcode,
            // OR another card in productList is waiting for a barcode
            val targetBlankPos = resolveTargetBlankBarcodePosition()
            if (targetBlankPos >= 0) {
                val targetItem = productList[targetBlankPos]
                if (docId != null) {
                    val (saveOutcome, _) = draftRepository.saveBarcodeScanAtomically(
                        documentId = docId,
                        localItemId = targetItem.localItemId,
                        barcode = normalizedBarcode
                    )
                    if (saveOutcome is SaveOutcome.Failed) {
                        showErrorAlert(saveOutcome.errorMessage)
                        return
                    }
                    if (saveOutcome is SaveOutcome.Saved) {
                        setSaveIndicatorSaved(saveOutcome.updatedAtMs)
                    }
                }
                adapter.applyPersistedBarcodeAt(targetBlankPos, normalizedBarcode)
                recyclerView.scrollToPosition(targetBlankPos)

                // Advance focus to next blank-barcode card if any exists
                val nextBlank = adapter.findFirstMissingBarcodePosition()
                if (nextBlank >= 0) {
                    recyclerView.scrollToPosition(nextBlank)
                    recyclerView.post {
                        val nextVh = recyclerView.findViewHolderForAdapterPosition(nextBlank)
                        if (nextVh is ProductComingAdapter.ProductViewHolder) {
                            nextVh.tvBarcode.requestFocus()
                        }
                    }
                }
                updateHeaderAndSaveButtonState()
                return
            }

            // Case 3: All existing cards already have barcodes -> create a new position from current
            // active nomenclature template, persist to SQLite FIRST, then insert at top!
            val activeTemplatePos = layoutManager.findFirstCompletelyVisibleItemPosition()
                .takeIf { it in 0 until productList.size }
                ?: layoutManager.findFirstVisibleItemPosition().takeIf { it in 0 until productList.size }
                ?: 0
            val template = productList[activeTemplatePos]
            val newItem = ComingProductCopyFactory.newScannedPosition(
                template = template,
                barcode = normalizedBarcode,
                destinationStoreId = destinationStoreId
            )

            if (docId != null) {
                val outcome = draftRepository.saveAddedOrUpdatedItem(
                    documentId = docId,
                    documentTitle = documentTitle,
                    destinationStoreId = destinationStoreId,
                    allowedNomenclatureIds = productsForSelectionId?.filterNotNull(),
                    item = newItem,
                    insertAtTop = true
                )
                if (outcome is SaveOutcome.Failed) {
                    showErrorAlert(outcome.errorMessage)
                    return
                }
                if (outcome is SaveOutcome.Saved) {
                    setSaveIndicatorSaved(outcome.updatedAtMs)
                }
            }

            productList.add(0, newItem)
            adapter.notifyItemInserted(0)
            recyclerView.scrollToPosition(0)
            updateEmptyStateVisibility()
            updateHeaderAndSaveButtonState()
        } finally {
            scanGate.release()
        }
    }

    private fun resolveTargetBlankBarcodePosition(): Int {
        val focused = adapter.getFocusedPosition()
        if (focused in 0 until productList.size && productList[focused].barcode.isNullOrBlank()) {
            return focused
        }
        val visiblePos = layoutManager.findFirstCompletelyVisibleItemPosition()
            .takeIf { it in 0 until productList.size }
            ?: layoutManager.findFirstVisibleItemPosition().takeIf { it in 0 until productList.size }
        if (visiblePos != null && productList[visiblePos].barcode.isNullOrBlank()) {
            return visiblePos
        }
        return adapter.findFirstMissingBarcodePosition()
    }

    private fun handleSaveButtonClick() {
        if (isSaving || !conductGuard.tryAcquire()) {
            return
        }

        val currentIdDocumentToSave = idDocument
        val detailedIssues = ComingProductValidator.validateDetailed(
            products = productList,
            documentId = currentIdDocumentToSave,
            destinationStoreId = destinationStoreId,
            expectedProductsIblockId = BitrixConfig.iblockProducts
        )

        if (detailedIssues.isNotEmpty()) {
            conductGuard.release()
            highlightValidationIssues(detailedIssues)
            showValidationAlert(detailedIssues.map { it.message })
            return
        }

        isSaving = true
        currentOperationState = ComingOperationState.PROCESSING
        progressBar.visibility = View.VISIBLE
        tvSaveIndicator.text = "Отправка в Битрикс..."
        updateHeaderAndSaveButtonState()

        lifecycleScope.launch {
            var result: ConductResult? = null
            try {
                val offersSnapshot = productList.map { ProductOffer(it) }
                result = service.conductDocumentSuspend(
                    baseList = baseList,
                    idDocument = currentIdDocumentToSave,
                    context = this@ProductsDocumentComingActivity,
                    deletedProducts = deletedProductsList,
                    updatedProducts = productList,
                    currentProductListState = productList,
                    productOffersList = offersSnapshot,
                    documentComment = currentDocumentComment,
                    destinationStoreId = destinationStoreId
                )
            } catch (e: Exception) {
                TsdLogger.error(
                    category = "CONDUCT",
                    event = "activity_conduct_exception",
                    documentId = currentIdDocumentToSave,
                    throwable = e
                )
                result = ConductResult(false, "Критическая ошибка: ${e.message}")
            } finally {
                val remainingLock = conductGuard.remainingLockMs()
                if (remainingLock > 0) {
                    delay(remainingLock)
                }
                conductGuard.release()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    isSaving = false

                    if (isActive && !isFinishing && !isDestroyed) {
                        if (result?.isSuccess == true) {
                            isCommitted = true
                            currentOperationState = ComingOperationState.COMMITTED
                            val toastMsg = result.message ?: "Документ успешно проведён"
                            Toast.makeText(
                                this@ProductsDocumentComingActivity,
                                toastMsg,
                                Toast.LENGTH_LONG
                            ).show()
                            navigateToMain()
                        } else {
                            currentOperationState = result?.state ?: ComingOperationState.FAILED
                            val targetPos = result?.failedPositionIndex
                                ?: result?.validationIssues?.firstOrNull { it.positionIndex >= 0 }?.positionIndex
                            if (result != null && result.validationIssues.isNotEmpty()) {
                                highlightValidationIssues(result.validationIssues)
                            } else if (targetPos != null && targetPos in 0 until productList.size) {
                                productList[targetPos].validationError = result?.message
                                adapter.notifyItemChanged(targetPos, ProductComingAdapter.PAYLOAD_ERROR_UPDATE)
                                recyclerView.scrollToPosition(targetPos)
                            }
                            setSaveIndicatorSaved(System.currentTimeMillis())
                            updateHeaderAndSaveButtonState()
                            showErrorAlert(result?.message ?: "Произошла неизвестная ошибка.")
                        }
                    }
                }
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun highlightValidationIssues(issues: List<com.example.scannerkotlin.model.ValidationIssue>) {
        val byItemId = issues.filter { it.localItemId != null }.groupBy { it.localItemId!! }
        productList.forEachIndexed { idx, prod ->
            val err = byItemId[prod.localItemId]?.joinToString("\n") { it.message }
            if (prod.validationError != err) {
                prod.validationError = err
                adapter.notifyItemChanged(idx, ProductComingAdapter.PAYLOAD_ERROR_UPDATE)
            }
        }
        val firstBadIdx = issues.firstOrNull { it.positionIndex in 0 until productList.size }?.positionIndex
        if (firstBadIdx != null) {
            recyclerView.scrollToPosition(firstBadIdx)
        }
    }

    private fun showErrorAlert(message: String) {
        if (!isFinishing && !isDestroyed) {
            MaterialAlertDialogBuilder(this, R.style.MyCustomMaterialAlertDialogStyle)
                .setTitle(
                    if (currentOperationState == ComingOperationState.RECONCILIATION_REQUIRED) {
                        "Требуется сверка с Битрикс"
                    } else {
                        "Ошибка проведения"
                    }
                )
                .setMessage(message)
                .setPositiveButton("Понятно") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    private fun showValidationAlert(messages: List<String>) {
        if (messages.isEmpty() || isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this, R.style.MyCustomMaterialAlertDialogStyle)
            .setTitle("Проверьте заполнение позиций")
            .setMessage(messages.take(8).joinToString("\n") + if (messages.size > 8) "\n…и ещё ${messages.size - 8}" else "")
            .setPositiveButton("Исправить") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showAlert(
        title: String,
        products: List<Product>,
        onProductSelected: (Product?) -> Unit
    ) {
        if (isFinishing || isDestroyed || isManualTargetDialogShowing) return
        isManualTargetDialogShowing = true

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
        dialog.setOnDismissListener {
            isManualTargetDialogShowing = false
        }
        dialog.setOnShowListener {
            dialog.listView?.apply {
                divider = null
                dividerHeight = 0
                setPadding(0, 8, 0, 8)
            }
        }
        dialog.show()
    }

    private fun updateHeaderAndSaveButtonState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updateHeaderAndSaveButtonStateInternal() }
        } else {
            updateHeaderAndSaveButtonStateInternal()
        }
    }

    private fun updateStatusHeaderText() {
        val total = productList.size
        if (total == 0) {
            tvStatusHeader.text = currentOperationState.toRussianLabel()
            return
        }
        val visiblePos = layoutManager.findFirstCompletelyVisibleItemPosition()
            .takeIf { it >= 0 }
            ?: layoutManager.findFirstVisibleItemPosition().takeIf { it >= 0 }
            ?: 0
        val posLabel = if (visiblePos < total) "Поз. ${visiblePos + 1}/$total" else "Комментарий ($total поз.)"
        tvStatusHeader.text = "${currentOperationState.toRussianLabel()} • $posLabel"
    }

    private fun updateHeaderAndSaveButtonStateInternal() {
        updateStatusHeaderText()
        val missingCount = adapter.countMissingBarcodes()
        val canInteract = !isSaving && !isCommitted
        btnSave.isEnabled = canInteract
        btnAddProduct.isEnabled = canInteract
        btnSave.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (canInteract && missingCount == 0 && productList.isNotEmpty()) R.color.field_blue else R.color.surface_elevated
            )
        )
        btnSave.text = when {
            isSaving && currentOperationState == ComingOperationState.RECONCILIATION_REQUIRED -> "Сверка с сервером..."
            isSaving -> "Проведение..."
            currentOperationState == ComingOperationState.RECONCILIATION_REQUIRED -> "Сверить и провести"
            missingCount > 0 -> "Заполните штрихкоды: $missingCount"
            else -> getString(R.string.btnSave)
        }
    }

    private fun updateEmptyStateVisibility() {
        if (productList.isEmpty()) {
            emptyProductListTextView.text = "Список товаров пуст.\nНажмите «Добавить товар» и отсканируйте штрихкод."
            emptyProductListTextView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyProductListTextView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }
}
