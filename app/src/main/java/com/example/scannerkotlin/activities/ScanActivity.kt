package com.example.scannerkotlin.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View // Для View.VISIBLE/GONE
import android.widget.ProgressBar // Импорт ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.scannerkotlin.R
import com.example.scannerkotlin.adapter.ProductScanAdapter
import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.mappers.ProductMapper
import com.example.scannerkotlin.mappers.ProductMeasureMapper
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.Store
import com.example.scannerkotlin.request.ProductIdRequest
import com.example.scannerkotlin.response.ProductResponse
import com.example.scannerkotlin.response.StoreAmountResponse
import com.example.scannerkotlin.service.CatalogDocumentMovingService
import com.example.scannerkotlin.utils.BaseScanActivity
import com.google.gson.internal.LinkedTreeMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

class ScanActivity : BaseScanActivity() {

    private val products: MutableList<Product> = mutableListOf()
    private lateinit var adapter: ProductScanAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var totalCountTextView: TextView
    private var service: CatalogDocumentMovingService = CatalogDocumentMovingService()


    private val apiBitrix: ApiBitrix by lazy {
        Retrofit.Builder()
            .baseUrl("https://bitrix.izocom.by/rest/1/c953o6imkob2gpwd/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiBitrix::class.java)
    }


    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("ScanActivityCoroutine", "Coroutine exception", throwable)
        lifecycleScope.launch(Dispatchers.Main) {
            showLoading(false)
            showAlertInfo("Непредвиденная ошибка: ${throwable.message}")
        }
    }

    override fun onBarcodeScanned(barcode: String) {
        processBarcode(barcode)
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scan_activity)

        progressBar = findViewById(R.id.progressBarScan)
        totalCountTextView = findViewById(R.id.totalCountText)

        var stores: List<Store>? = null
        lifecycleScope.launch(Dispatchers.Main) {
            stores = service.getStoreList()
            setupRecyclerView(stores)
        }
    }

    private fun showLoading(isLoading: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isLoading) {
                progressBar.visibility = View.VISIBLE
            } else {
                progressBar.visibility = View.GONE

            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupRecyclerView(stores: List<Store>?) {

        recyclerView = findViewById(R.id.recycleViewScan)
        recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        adapter = ProductScanAdapter(products, stores) { position ->
            handleItemDeletion(position)
        }
        recyclerView.adapter = adapter

        totalCountTextView.text = "Количество отсканированных товаров: ${products.size}"

        val snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)
    }

    private fun processBarcode(barcode: String) {
        showLoading(true)
        lifecycleScope.launch(coroutineExceptionHandler) {
            try {
                val productId = getProductIdByBarcodeSuspend(barcode)

                if (!productId.isNullOrEmpty()) {
                    Log.d("ScanActivity", "Product ID received: $productId")

                    val product = getProductByIdSuspend(productId)
                    val fetchedStoreAmounts: List<StoreAmountResponse.StoreProduct> = service.getStoreForScan(productId.toInt())
                    product?.storeAmount = fetchedStoreAmounts
                    if (product != null) {
                        withContext(Dispatchers.Main) {
                            addProductToList(product)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
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
                showLoading(false)
            }
        }
    }


    private suspend fun getProductIdByBarcodeSuspend(barcodeData: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiBitrix.getProductIdByBarcode(barcodeData)
                if (response.isSuccessful) {
                    val productId = response.body()?.result?.trim()
                    if (!productId.isNullOrEmpty()) {
                        productId
                    } else {
                        Log.w("ScanActivity", "API returned success but Product ID is null or empty for barcode $barcodeData")
                        null
                    }
                } else {
                    Log.e("ScanActivity", "Failed to get product ID: ${response.code()} - ${response.errorBody()?.string()}")
                    null
                }
            } catch (e: Exception) {
                Log.e("ScanActivity", "Error in getProductIdByBarcodeSuspend for $barcodeData", e)
                throw e
            }
        }
    }


    private suspend fun getProductByIdSuspend(productId: String): Product? {
        return withContext(Dispatchers.IO) {
            try {
                val productRequest = ProductIdRequest(id = productId)
                val response = apiBitrix.getProductById(productRequest)

                if (response.isSuccessful) {
                    val product = mapProductResponse(response.body())
                    if (product != null) {
                        Log.d("ScanActivity", "Product details fetched: $product")
                        product
                    } else {
                        Log.e("ScanActivity", "Product details mapping failed or result is null for ID $productId")
                        null
                    }
                } else {
                    Log.e("ScanActivity", "Failed to get product details for ID $productId: ${response.code()} - ${response.errorBody()?.string()}")
                    null
                }
            } catch (e: Exception) {
                Log.e("ScanActivity", "Error in getProductByIdSuspend for ID $productId", e)
                throw e
            }
        }
    }


    private fun mapProductResponse(responseBody: ProductResponse?): Product? {
        return try {

            val productDetailsMap = responseBody?.result
            if (productDetailsMap != null) {
                val productDetails = productDetailsMap["product"] as? LinkedTreeMap<*, *>
                if (productDetails != null) {
                    val productMapper = ProductMapper()
                    val measureMapper = ProductMeasureMapper()
                    val product = productMapper.mapToProduct(productDetails)
                    measureMapper.setMeasureNameProduct(product)
                    product
                } else {
                    Log.w("ScanActivity", "Product key not found or not a map in response result.")
                    null
                }
            } else {
                Log.w("ScanActivity", "Result in product response is null.")
                null
            }
        } catch (e: ClassCastException) {
            Log.e("ScanActivity", "Error casting product details in mapProductResponse", e)
            null
        } catch (e: Exception) {
            Log.e("ScanActivity", "Error mapping product response", e)
            null
        }
    }


    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun addProductToList(product: Product) {

        val existingProduct = products.find { it.id == product.id }
        if (existingProduct == null) {
            products.add(0, product)
            adapter.notifyItemInserted(0)
            totalCountTextView.text = "Количество отсканированных товаров: ${products.size}"
            findViewById<RecyclerView>(R.id.recycleViewScan).scrollToPosition(0)
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
        } else {
            Log.w("ScanActivity", "Attempted to show alert dialog on finishing/destroyed activity")
        }
    }

    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun handleItemDeletion(position: Int) {
        if (position < 0 || position >= products.size) return
        
        val removedProduct = products.removeAt(position)
        Log.d("ScanActivity", "Removed product: ${removedProduct.name} at position $position")
        
        adapter.notifyItemRemoved(position)
        adapter.notifyItemRangeChanged(position, products.size)
        
        // Обновляем счетчик товаров
        totalCountTextView.text = "Количество отсканированных товаров: ${products.size}"
        
        if (products.isEmpty()) {
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}