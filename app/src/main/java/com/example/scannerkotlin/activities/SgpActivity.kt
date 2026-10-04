package com.example.scannerkotlin.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.scannerkotlin.R
import com.example.scannerkotlin.adapter.ProductScanAdapter
import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.mappers.ProductMapper
import com.example.scannerkotlin.mappers.ProductMeasureMapper
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.Provider
import com.example.scannerkotlin.model.Store
import com.example.scannerkotlin.request.ProductIdRequest
import com.example.scannerkotlin.response.ProductResponse
import com.example.scannerkotlin.service.CatalogDocumentMovingService
import com.example.scannerkotlin.service.SgpService
import com.example.scannerkotlin.utils.BaseScanActivity
import com.example.scannerkotlin.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.gson.internal.LinkedTreeMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

class SgpActivity : BaseScanActivity() {

    private val products: MutableList<Product> = mutableListOf()
    private lateinit var adapter: ProductScanAdapter
    private  var spendButton: MaterialButton? = null
    private lateinit var progressBar: ProgressBar

    override fun onBarcodeScanned(barcode: String) {
        processBarcode(barcode)
    }

    private val apiBitrix: ApiBitrix by lazy {
        com.example.scannerkotlin.config.BitrixClientFactory.barcodeScanApi()
    }
    private var sgpService: SgpService? = null

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("SgpActivityCoroutine", "Coroutine exception", throwable)
        lifecycleScope.launch(Dispatchers.Main) {
            showLoading(false)
            showAlertInfo("Непредвиденная ошибка: ${throwable.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingInflatedId", "NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sgp)


        sgpService = SgpService(apiBitrix, SessionManager.getUserId())
        progressBar = findViewById(R.id.progressBarSgp)
        spendButton = findViewById(R.id.conductButton)

        var stores: List<Store>? = null
        lifecycleScope.launch(Dispatchers.Main) {
            val service = CatalogDocumentMovingService()
            stores = service.getStoreList()
            setupRecyclerViewAndButton(stores)
        }

        spendButton?.setOnClickListener {
            if (products.isEmpty()) {
                Toast.makeText(this@SgpActivity, "Список товаров для проведения пуст.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showLoading(true)
            lifecycleScope.launch(coroutineExceptionHandler) {
                var mainDocumentSpentSuccessfully = false
                try {

                    val success: Boolean = sgpService!!.spendDocument(
                        products = ArrayList(products),
                        context = this@SgpActivity
                    )
                    mainDocumentSpentSuccessfully = success

                    if (success) {
                        Log.d("SgpActivity", "spendDocument successful")


                        try {
                            Log.i("SgpActivity", "Attempting to update blanks after successful spendDocument.")

                            sgpService!!.updateBlanks(ArrayList(products))
                            Log.i("SgpActivity", "updateBlanks call completed.")
                        } catch (eUpdateBlanks: Exception) {
                            Log.e("SgpActivity", "Error during updateBlanks", eUpdateBlanks)

                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@SgpActivity, "Документ проведен, но ошибка при обновлении заготовок: ${eUpdateBlanks.message}", Toast.LENGTH_LONG).show()
                            }
                        }


                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SgpActivity, "В документ добавлено!", Toast.LENGTH_SHORT).show()
                            products.clear()
                            adapter.notifyDataSetChanged()
                        }
                    } else {
                        Log.w("SgpActivity", "spendDocument failed")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SgpActivity, "Не удалось провести документ.", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {

                    Log.e("SgpActivity", "Exception during operation (spendDocument or updateBlanks)", e)
                    withContext(Dispatchers.Main) {
                        val actionMessage = if (mainDocumentSpentSuccessfully) {
                            "Документ проведен, но затем произошла ошибка: ${e.message}"
                        } else {
                            "Произошла непредвиденная ошибка при проведении: ${e.message}"
                        }
                        Toast.makeText(this@SgpActivity, actionMessage, Toast.LENGTH_LONG).show()
                    }
                } finally {
                    showLoading(false)
                }
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            spendButton?.isEnabled = !isLoading
        }
    }

    private fun setupRecyclerViewAndButton(stores: List<Store>?) {
        val recyclerView: RecyclerView = findViewById(R.id.recycleViewScan)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = ProductScanAdapter(products, stores) { position ->
            handleItemDeletion(position)
        }

        recyclerView.adapter = adapter

    }

    private fun processBarcode(barcode: String) {
        showLoading(true)
        lifecycleScope.launch(coroutineExceptionHandler) {
            try {
                val productId = getProductIdByBarcodeSuspend(barcode)
                if (!productId.isNullOrEmpty()) {
                    Log.d("SgpActivity", "Product ID received: $productId")
                    val product = getProductByIdSuspend(productId)
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
                Log.e("SgpActivity", "Network or IO Error processing barcode $barcode", e)
                withContext(Dispatchers.Main) { showAlertInfo("Ошибка сети при обработке штрихкода") }
            } catch (e: Exception) {
                Log.e("SgpActivity", "Unexpected error processing barcode $barcode", e)
                withContext(Dispatchers.Main) { showAlertInfo("Произошла ошибка при обработке штрихкода: ${e.message}") }
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
                        Log.w("SgpActivity", "API returned success but Product ID is null or empty for barcode $barcodeData")
                        null
                    }
                } else {
                    Log.e("SgpActivity", "Failed to get product ID: ${response.code()} - ${response.errorBody()?.string()}")
                    null
                }
            } catch (e: Exception) {
                Log.e("SgpActivity", "Error in getProductIdByBarcodeSuspend for $barcodeData", e)
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
                        Log.d("SgpActivity", "Product details fetched: $product")
                        Log.d("SgpActivity", "Product details fetched: ${product.parentId}")
                        product
                    } else {
                        Log.e("SgpActivity", "Product details mapping failed or result is null for ID $productId")
                        null
                    }
                } else {
                    Log.e("SgpActivity", "Failed to get product details for ID $productId: ${response.code()} - ${response.errorBody()?.string()}")
                    null
                }
            } catch (e: Exception) {
                Log.e("SgpActivity", "Error in getProductByIdSuspend for ID $productId", e)
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
                    Log.w("SgpActivity", "Product key not found or not a map in response result.")
                    null
                }
            } else {
                Log.w("SgpActivity", "Result in product response is null.")
                null
            }
        } catch (e: ClassCastException) {
            Log.e("SgpActivity", "Error casting product details in mapProductResponse", e)
            null
        } catch (e: Exception) {
            Log.e("SgpActivity", "Error mapping product response", e)
            null
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun addProductToList(product: Product) {
        if (!product.checkInList(products)) {
            products.add(0, product)
            adapter.notifyItemInserted(0)
            findViewById<RecyclerView>(R.id.recycleViewScan).scrollToPosition(0)
        } else {
            showAlertInfo("Этот товар уже отсканирован")
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

    private fun showAlert(
        title: String,
        providers: List<Provider>,
        onProviderSelected: (Provider?) -> Unit
    ) {
        if (!isFinishing && !isDestroyed) {
            val builder = AlertDialog.Builder(this)
            builder.setTitle(title)

            if (providers.isEmpty()) {
                builder.setMessage("Нет доступных вариантов.")
                builder.setPositiveButton("OK") { dialog, _ ->
                    onProviderSelected(null)
                    dialog.dismiss()
                }
            } else {
                val providersNames = providers.map { it.title ?: "Без имени" }
                builder.setItems(providersNames.toTypedArray()) { dialog, which ->
                    val selectedProvider = providers[which]
                    onProviderSelected(selectedProvider)
                    dialog.dismiss()
                }
                builder.setNegativeButton("Отмена") { dialog, _ ->
                    onProviderSelected(null)
                    dialog.dismiss()
                }
            }
            builder.create().show()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun handleItemDeletion(position: Int) {
        if (position < 0 || position >= products.size) return
        
        val removedProduct = products.removeAt(position)
        Log.d("SgpActivity", "Removed product: ${removedProduct.name} at position $position")
        
        adapter.notifyItemRemoved(position)
        adapter.notifyItemRangeChanged(position, products.size)
        
        if (products.isEmpty()) {
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}