package com.example.scannerkotlin.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.scannerkotlin.R
import com.example.scannerkotlin.adapter.ProductScanAdapter
import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.config.BitrixClientFactory
import com.example.scannerkotlin.mappers.ProductMapper
import com.example.scannerkotlin.mappers.ProductMeasureMapper
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.Store
import com.example.scannerkotlin.request.ProductIdRequest
import com.example.scannerkotlin.response.ProductResponse
import com.example.scannerkotlin.response.StoreAmountResponse
import com.example.scannerkotlin.service.CatalogDocumentMovingService
import com.example.scannerkotlin.utils.BarcodeScanGate
import com.example.scannerkotlin.utils.BaseScanActivity
import com.example.scannerkotlin.utils.InventoryScanController
import com.example.scannerkotlin.utils.InventoryScanPhase
import com.example.scannerkotlin.utils.InventoryScanPress
import com.example.scannerkotlin.utils.TsdLogger
import com.example.scannerkotlin.utils.XchengHardwareScanEngine
import com.google.android.material.button.MaterialButton
import com.google.gson.internal.LinkedTreeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.IOException

class ScanActivity : BaseScanActivity() {

    private val products: MutableList<Product> = mutableListOf()
    private lateinit var adapter: ProductScanAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var totalCountTextView: TextView
    private lateinit var softwareScanButton: MaterialButton
    private lateinit var scanSessionStatus: TextView
    private lateinit var scanEngine: XchengHardwareScanEngine
    private lateinit var scanController: InventoryScanController
    private val service: CatalogDocumentMovingService = CatalogDocumentMovingService()
    private val scanGate = BarcodeScanGate(duplicateWindowMs = 450L)
    private var scanAttemptGeneration = 0
    private var softwareScanFingerDown = false
    private var softwareScanTouchConsumed = false
    private val softwareScanWatchdog = Runnable {
        if (!::scanController.isInitialized) return@Runnable
        if (scanController.onWatchdog()) {
            logSoftwareScanStop("watchdog")
        }
        showSoftwareScanIdle()
    }

    private val apiBitrix: ApiBitrix by lazy {
        BitrixClientFactory.barcodeScanApi()
    }

    override fun onBarcodeScanned(barcode: String) {
        lifecycleScope.launch {
            processBarcodeSequential(barcode)
        }
    }

    override suspend fun onBarcodeScannedSequential(barcode: String) {
        processBarcodeSequential(barcode)
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.scan_activity)
        applySystemBarInsets()

        progressBar = findViewById(R.id.progressBarScan)
        totalCountTextView = findViewById(R.id.totalCountText)
        recyclerView = findViewById(R.id.recycleViewScan)
        softwareScanButton = findViewById(R.id.btnSoftwareScan)
        scanSessionStatus = findViewById(R.id.scanSessionStatus)
        scanEngine = XchengHardwareScanEngine(this) { raw ->
            if (!isFinishing && !isDestroyed) {
                enqueueBarcodeForProcessing(raw)
            }
        }
        scanController = InventoryScanController(scanEngine)
        bindSoftwareScanButton()
        setupRecyclerView(emptyList())

        lifecycleScope.launch {
            val stores = service.getStoreList()
            if (!isFinishing && !isDestroyed && stores.isNotEmpty()) {
                setupRecyclerView(stores)
            }
        }
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.main)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        if (::scanController.isInitialized) {
            scanController.attach()
            showSoftwareScanIdle()
        }
    }

    override fun onPause() {
        releaseSoftwareScanHardware()
        super.onPause()
    }

    override fun onDestroy() {
        if (::softwareScanButton.isInitialized) {
            softwareScanButton.removeCallbacks(softwareScanWatchdog)
        }
        if (::scanController.isInitialized) {
            scanController.detach()
        }
        super.onDestroy()
    }

    private fun showLoading(isLoading: Boolean) {
        if (!isFinishing && !isDestroyed) {
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupRecyclerView(stores: List<Store>?) {
        if (recyclerView.layoutManager == null) {
            recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            val snapHelper = PagerSnapHelper()
            snapHelper.attachToRecyclerView(recyclerView)
        }
        adapter = ProductScanAdapter(products, stores) { position ->
            handleItemDeletion(position)
        }
        recyclerView.adapter = adapter
        totalCountTextView.text = "Количество отсканированных товаров: ${products.size}"
    }

    override fun onBarcodeEnqueued(barcode: String) {
        if (barcode.isEmpty()) return
        finishSoftwareScanSession("decode")
    }

    private suspend fun processBarcodeSequential(barcode: String) {
        if (!scanGate.tryAcquire(barcode)) {
            return
        }
        withContext(Dispatchers.Main) { showLoading(true) }
        try {
            val productId = getProductIdByBarcodeSuspend(barcode)
            if (!productId.isNullOrEmpty()) {
                val product = getProductByIdSuspend(productId)
                val productIdInt = productId.toIntOrNull()
                val fetchedStoreAmounts: List<StoreAmountResponse.StoreProduct> = if (productIdInt != null) {
                    service.getStoreForScan(productIdInt)
                } else {
                    emptyList()
                }
                product?.storeAmount = fetchedStoreAmounts
                withContext(Dispatchers.Main) {
                    if (product != null) {
                        addProductToList(product)
                    } else {
                        showAlertInfo("Не удалось получить детали товара ID: $productId")
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    showAlertInfo("Товар по штрихкоду '$barcode' не найден")
                }
            }
        } catch (e: IOException) {
            Log.e("ScanActivity", "Network or IO Error processing barcode $barcode", e)
            withContext(Dispatchers.Main) {
                showAlertInfo("Ошибка сети при обработке штрихкода")
            }
        } catch (e: Exception) {
            Log.e("ScanActivity", "Unexpected error processing barcode $barcode", e)
            withContext(Dispatchers.Main) {
                showAlertInfo("Произошла ошибка при обработке штрихкода: ${e.message}")
            }
        } finally {
            scanGate.release()
            withContext(Dispatchers.Main) { showLoading(false) }
        }
    }

    private suspend fun getProductIdByBarcodeSuspend(barcodeData: String): String? {
        return withContext(Dispatchers.IO) {
            val response = apiBitrix.getProductIdByBarcode(barcodeData)
            if (response.isSuccessful) {
                response.body()?.result?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }
    }

    private suspend fun getProductByIdSuspend(productId: String): Product? {
        return withContext(Dispatchers.IO) {
            val productRequest = ProductIdRequest(id = productId)
            val response = apiBitrix.getProductById(productRequest)
            if (response.isSuccessful) {
                mapProductResponse(response.body())
            } else {
                null
            }
        }
    }

    private fun mapProductResponse(responseBody: ProductResponse?): Product? {
        return try {
            val productDetails = responseBody?.result?.get("product") as? LinkedTreeMap<*, *> ?: return null
            val productMapper = ProductMapper()
            val measureMapper = ProductMeasureMapper()
            val product = productMapper.mapToProduct(productDetails)
            measureMapper.setMeasureNameProduct(product)
            product
        } catch (e: Exception) {
            Log.e("ScanActivity", "Error mapping product response", e)
            null
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addProductToList(product: Product) {
        val existingProduct = products.find { it.id == product.id }
        if (existingProduct == null) {
            products.add(0, product)
            adapter.notifyItemInserted(0)
            totalCountTextView.text = "Количество отсканированных товаров: ${products.size}"
            recyclerView.scrollToPosition(0)
        } else {
            showAlertInfo("Этот товар уже отсканирован")
        }
    }

    private fun showAlertInfo(text: String) {
        if (!isFinishing && !isDestroyed) {
            AlertDialog.Builder(this, R.style.MyCustomAlertDialog).apply {
                setTitle("Информация")
                setMessage(text)
                setCancelable(true)
                setPositiveButton("ОК") { dialog, _ ->
                    dialog.dismiss()
                }
            }.create().show()
        }
    }

    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun handleItemDeletion(position: Int) {
        if (position < 0 || position >= products.size) return
        products.removeAt(position)
        adapter.notifyItemRemoved(position)
        val remainingFromPos = products.size - position
        if (remainingFromPos > 0) {
            adapter.notifyItemRangeChanged(position, remainingFromPos)
        }
        totalCountTextView.text = "Количество отсканированных товаров: ${products.size}"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindSoftwareScanButton() {
        softwareScanButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    softwareScanTouchConsumed = true
                    view.isPressed = true
                    onSoftwareScanPressed()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    onSoftwareScanReleased()
                    view.performClick()
                    true
                }
                else -> false
            }
        }
        softwareScanButton.setOnClickListener {
            if (softwareScanTouchConsumed) {
                softwareScanTouchConsumed = false
                return@setOnClickListener
            }
            onSoftwareScanPressed()
        }
    }

    private fun onSoftwareScanPressed() {
        softwareScanFingerDown = true
        val generation = ++scanAttemptGeneration
        attemptSoftwareScan(generation, SCAN_BIND_RETRIES)
    }

    private fun onSoftwareScanReleased() {
        softwareScanFingerDown = false
        scanAttemptGeneration++
        if (!::scanController.isInitialized) return
        if (scanController.release()) {
            logSoftwareScanStop("release")
            showSoftwareScanIdle()
        }
    }

    private fun attemptSoftwareScan(generation: Int, remainingRetries: Int) {
        if (generation != scanAttemptGeneration || isFinishing || isDestroyed) return
        if (!softwareScanFingerDown && scanController.phase != InventoryScanPhase.SCANNING) return
        when (scanController.press()) {
            InventoryScanPress.STARTED -> showSoftwareScanActive()
            InventoryScanPress.ALREADY_ACTIVE -> {
                TsdLogger.info(
                    category = "SCANNER",
                    event = "software_scan_parallel_ignored",
                    fields = mapOf("screen" to "ScanActivity")
                )
            }
            InventoryScanPress.NOT_READY -> {
                if (remainingRetries > 0 && softwareScanFingerDown) {
                    softwareScanButton.postDelayed({
                        attemptSoftwareScan(generation, remainingRetries - 1)
                    }, SCAN_BIND_RETRY_MS)
                } else if (generation == scanAttemptGeneration) {
                    showSoftwareScanUnavailable()
                }
            }
            InventoryScanPress.START_FAILED,
            InventoryScanPress.CLOSED -> showSoftwareScanUnavailable()
        }
    }

    private fun showSoftwareScanActive() {
        if (isFinishing || isDestroyed) return
        softwareScanButton.text = getString(R.string.inventory_scan_active_button)
        softwareScanButton.isActivated = true
        scanSessionStatus.text = getString(R.string.inventory_scan_active_hint)
        scanSessionStatus.visibility = View.VISIBLE
        softwareScanButton.removeCallbacks(softwareScanWatchdog)
        softwareScanButton.postDelayed(softwareScanWatchdog, SCAN_WATCHDOG_MS)
    }

    private fun showSoftwareScanIdle() {
        if (!::softwareScanButton.isInitialized || isFinishing || isDestroyed) return
        softwareScanButton.removeCallbacks(softwareScanWatchdog)
        softwareScanButton.text = getString(R.string.inventory_scan_action)
        softwareScanButton.isActivated = false
        softwareScanButton.isPressed = false
        scanSessionStatus.visibility = View.GONE
    }

    private fun showSoftwareScanUnavailable() {
        showSoftwareScanIdle()
        if (isFinishing || isDestroyed) return
        TsdLogger.warn(
            category = "SCANNER",
            event = "software_scan_unavailable",
            fields = mapOf("screen" to "ScanActivity")
        )
        Toast.makeText(this, R.string.inventory_scan_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun finishSoftwareScanSession(reason: String) {
        if (!::scanController.isInitialized) return
        if (scanController.onDecodeObserved()) {
            logSoftwareScanStop(reason)
        }
        showSoftwareScanIdle()
    }

    private fun releaseSoftwareScanHardware() {
        scanAttemptGeneration++
        softwareScanFingerDown = false
        if (::softwareScanButton.isInitialized) {
            softwareScanButton.removeCallbacks(softwareScanWatchdog)
        }
        if (::scanController.isInitialized && scanController.phase == InventoryScanPhase.SCANNING) {
            logSoftwareScanStop("leave")
        }
        if (::scanController.isInitialized) {
            scanController.detach()
        }
        showSoftwareScanIdle()
    }

    private fun logSoftwareScanStop(reason: String) {
        TsdLogger.info(
            category = "SCANNER",
            event = "software_scan_stop",
            fields = mapOf("screen" to "ScanActivity", "reason" to reason)
        )
    }

    private companion object {
        const val SCAN_BIND_RETRY_MS = 200L
        const val SCAN_BIND_RETRIES = 8
        const val SCAN_WATCHDOG_MS = 12_000L
    }
}
