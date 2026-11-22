package com.example.scannerkotlin.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
    private val service: CatalogDocumentMovingService = CatalogDocumentMovingService()
    private val scanGate = BarcodeScanGate(duplicateWindowMs = 450L)

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
        setContentView(R.layout.scan_activity)

        progressBar = findViewById(R.id.progressBarScan)
        totalCountTextView = findViewById(R.id.totalCountText)
        recyclerView = findViewById(R.id.recycleViewScan)
        setupRecyclerView(emptyList())

        lifecycleScope.launch {
            val stores = service.getStoreList()
            if (!isFinishing && !isDestroyed && stores.isNotEmpty()) {
                setupRecyclerView(stores)
            }
        }
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
}
