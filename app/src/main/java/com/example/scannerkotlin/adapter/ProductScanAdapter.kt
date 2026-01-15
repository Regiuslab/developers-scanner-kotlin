package com.example.scannerkotlin.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
// <<< ИЗМЕНЕНИЕ 1: Добавлены импорты для AlertDialog и ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.scannerkotlin.R
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.Store

class ProductScanAdapter(
    private val products: MutableList<Product>,
    allStores: List<Store>?,
    private val onDelete: (position: Int) -> Unit
) : RecyclerView.Adapter<ProductScanAdapter.ProductViewHolder>() {

    // Примечание: в вашем коде ID магазина уже Int, это правильно.
    private val storeIdToNameMap: Map<Int, String>? = allStores?.associate { store ->
        store.id to store.title
    }

    class ProductViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvScanName)
        private val tvMeasure: TextView = view.findViewById(R.id.tvScanMeasure)
        private val tvStoreAmountsTitle: TextView = view.findViewById(R.id.tvStoreAmountsTitle)
        private val llStoreAmountsContainer: LinearLayout = view.findViewById(R.id.llStoreAmountsContainer)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteSgp)

        @SuppressLint("SetTextI18n")
        fun bind(product: Product, storeIdToNameMap: Map<Int, String>?) {
            // <<< ИЗМЕНЕНИЕ 2: Сохраняем полное имя в переменную для использования в обработчике
            val productName = product.name ?: "Нет названия"
            tvName.text = productName

            // <<< ИЗМЕНЕНИЕ 3: Устанавливаем обработчик клика на TextView с названием
            tvName.setOnClickListener {
                // Если текст был обрезан (есть троеточие), показываем диалог
                // Также можно показывать всегда, убрав эту проверку
                if (tvName.layout != null && tvName.layout.getEllipsisCount(tvName.lineCount - 1) > 0) {
                    showFullNameDialog(productName)
                }
            }

            if (!product.measureName.isNullOrBlank()) {
                tvMeasure.text = "(${product.measureName})"
                tvMeasure.visibility = View.VISIBLE
            } else {
                tvMeasure.visibility = View.GONE
            }

            llStoreAmountsContainer.removeAllViews()

            if (product.storeAmount.isNullOrEmpty()) {
                tvStoreAmountsTitle.visibility = View.GONE
                llStoreAmountsContainer.visibility = View.GONE
            } else {
                tvStoreAmountsTitle.visibility = View.VISIBLE
                llStoreAmountsContainer.visibility = View.VISIBLE

                product.storeAmount?.forEach { storeProduct ->
                    // Ваш код для отображения остатков остался без изменений, он корректен
                    val storeName = storeIdToNameMap?.get(storeProduct.storeId.toInt()) ?: "Склад  ${storeProduct.storeId.toInt()}"
                    val amountFormatted = storeProduct.amount.toString()

                    val storeInfoTextView = TextView(itemView.context).apply {
                        text = "$storeName: $amountFormatted"
                        textSize = 18f
                        gravity = android.view.Gravity.CENTER
                        setTextColor(ContextCompat.getColor(itemView.context, R.color.white))
                        setTypeface(null, android.graphics.Typeface.NORMAL)

                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        val marginVertical = (6 * itemView.context.resources.displayMetrics.density).toInt()
                        params.setMargins(0, marginVertical, 0, marginVertical)
                        layoutParams = params
                    }
                    llStoreAmountsContainer.addView(storeInfoTextView)
                }
            }
        }


        private fun showFullNameDialog(fullName: String) {
            val context = itemView.context
            AlertDialog.Builder(context, R.style.MyCustomAlertDialog)
                .setTitle("Полное наименование")
                .setMessage(fullName)
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .create()
                .show()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_product, parent, false)
        return ProductViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        holder.bind(products[position], storeIdToNameMap)
        
        holder.btnDelete.setOnClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition != RecyclerView.NO_POSITION && currentPosition < products.size) {
                onDelete(currentPosition)
            }
        }
    }



    override fun getItemCount(): Int = products.size
}