package com.example.scannerkotlin.adapter

import android.annotation.SuppressLint
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.example.scannerkotlin.R
import com.example.scannerkotlin.model.Product
import java.util.Locale

class ProductComingAdapter(
    private val productList: MutableList<Product>,
    private val onDelete: (position: Int) -> Unit,
    private var currentDocumentComment: String?,
    private val onCommentChanged: (String?) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var focusedPosition: Int = RecyclerView.NO_POSITION
    private val measures = listOf("Штука", "Килограмм", "Литр", "Метр", "Грамм")

    private var onDataChangedListener: (() -> Unit)? = null


    companion object {
        const val PAYLOAD_BARCODE_UPDATE = "barcode_update"
        private const val VIEW_TYPE_PRODUCT = 0
        private const val VIEW_TYPE_COMMENT = 1

    }

    fun setOnDataChangedListener(listener: () -> Unit) {
        this.onDataChangedListener = listener
    }

    fun setCurrentDocumentComment(comment: String?) {

        this.currentDocumentComment = comment

        if (itemCount > 0) {
            notifyItemChanged(0)
        }
    }


    fun areAllBarcodesFilledInUI(): Boolean {
        if (productList.isEmpty()) return false
        for (i in 0 until productList.size) {
            val viewHolder = recyclerView?.findViewHolderForAdapterPosition(i) as? ProductViewHolder
            if (viewHolder != null) {

                val barcodeText = viewHolder.tvBarcode.text.toString().trim()
                if (barcodeText.isBlank()) {
                    return false
                }
            } else {
                val product = productList[i]
                if (product.barcode.isNullOrBlank()) {
                    return false
                }
            }
        }
        return true
    }


    fun findProductsMissingBarcodes(): List<Product> {
        return productList.filter { it.barcode.isNullOrBlank() }
    }

    fun findFirstMissingBarcodePosition(): Int {
        for (i in 0 until productList.size) {
            if (productList[i].barcode.isNullOrBlank()) return i
        }
        return -1
    }

    private var recyclerView: RecyclerView? = null


    fun setRecyclerView(rv: RecyclerView) {
        this.recyclerView = rv
    }


    override fun getItemViewType(position: Int): Int {

        return if (position < productList.size) {
            VIEW_TYPE_PRODUCT
        } else {
            VIEW_TYPE_COMMENT
        }
    }


    override fun getItemCount(): Int {

        return if (productList.isNotEmpty()) {
            productList.size + 1
        } else {
            0
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PRODUCT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_coming_product, parent, false)
                ProductViewHolder(view)
            }
            VIEW_TYPE_COMMENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_document_comment, parent, false)
                CommentViewHolder(view, onCommentChanged)
            }
            else -> throw IllegalArgumentException("Invalid view type: $viewType")
        }

    }


    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (holder.itemViewType == VIEW_TYPE_PRODUCT && payloads.contains(PAYLOAD_BARCODE_UPDATE)) {

            if (position >= 0 && position < productList.size) {
                val product = productList[position]
                val barcodeField = (holder as ProductViewHolder).tvBarcode
                Log.d("Adapter", "PAYLOAD: Setting barcode '${product.barcode}' at position $position")
                barcodeField.setText(product.barcode ?: "")
                Log.d("Adapter", "PAYLOAD: Barcode set successfully")
            } else {
                Log.w("Adapter", "Payload update received for invalid position $position")
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }


    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder.itemViewType) {
            VIEW_TYPE_PRODUCT -> {
                if (position >= 0 && position < productList.size) {
                    val product = productList[position]
                    (holder as ProductViewHolder).bind(product)
                } else {
                    Log.e("Adapter", "onBindViewHolder (Product) called with invalid position: $position, list size: ${productList.size}")
                }
            }
            VIEW_TYPE_COMMENT -> {
                (holder as CommentViewHolder).bind(currentDocumentComment)
            }
        }
    }


    fun updateFocusedProductBarcode(newBarcode: String): Boolean {
        val currentFocusedPosition = focusedPosition
        if (currentFocusedPosition != RecyclerView.NO_POSITION && currentFocusedPosition < productList.size) {
            try {
                val product = productList[currentFocusedPosition]
                val truncatedBarcode = if (newBarcode.length > 13) {
                    newBarcode.substring(0, 13)
                } else {
                    newBarcode
                }
                product.barcode = truncatedBarcode
                notifyItemChanged(currentFocusedPosition, PAYLOAD_BARCODE_UPDATE)
                Log.d("Adapter", "Barcode updated for product ${product.id} at position $currentFocusedPosition. Original: '$newBarcode', Saved: '$truncatedBarcode'")
                onDataChangedListener?.invoke()
                return true
            } catch (e: IndexOutOfBoundsException) {
                Log.e("Adapter", "IndexOutOfBoundsException in updateFocusedProductBarcode for position $currentFocusedPosition", e)
                focusedPosition = RecyclerView.NO_POSITION
                return false
            }
        } else {
            Log.w("Adapter", "Cannot update barcode, focusedPosition is invalid or out of bounds: $currentFocusedPosition")
            return false
        }
    }



    fun updateDocumentComment(newComment: String?) {
        val needsUpdate = this.currentDocumentComment != newComment
        this.currentDocumentComment = newComment
        if (needsUpdate && productList.isNotEmpty()) {
            val commentPosition = productList.size

            if (commentPosition < itemCount) {
                notifyItemChanged(commentPosition)
            }
        }
    }


    inner class ProductViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvProductName: TextView = itemView.findViewById(R.id.tvProductName)
        val etQuantity: EditText = itemView.findViewById(R.id.etQuantity)
        val spinnerMeasure: Spinner = itemView.findViewById(R.id.spinnerMeasure)
        val tvBarcode: EditText = itemView.findViewById(R.id.tvBarcode)
        val tvBarcodeError: TextView = itemView.findViewById(R.id.tvBarcodeError)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
        val etDiameter: EditText = itemView.findViewById(R.id.etDiameter)
        val etWeight: EditText = itemView.findViewById(R.id.etWeight)
        val etWallThickness: EditText = itemView.findViewById(R.id.etWallThickness)

        var quantityWatcher: TextWatcher? = null
        var diameterWatcher: TextWatcher? = null
        var weightWatcher: TextWatcher? = null
        var wallThicknessWatcher: TextWatcher? = null

        init {
            val measureAdapter = ArrayAdapter(
                itemView.context,
                R.layout.custom_spinner_item,
                measures
            )

            measureAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item)
            spinnerMeasure.adapter = measureAdapter


            tvBarcode.isFocusable = true
            tvBarcode.isFocusableInTouchMode = true
            tvBarcode.isCursorVisible = false
            tvBarcode.showSoftInputOnFocus = false
            tvBarcode.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(13))



            etQuantity.isFocusable = true
            etQuantity.isFocusableInTouchMode = true
            etQuantity.showSoftInputOnFocus = false
            etQuantity.isCursorVisible = true


            etDiameter.isFocusable = true
            etDiameter.isFocusableInTouchMode = true
            etDiameter.showSoftInputOnFocus = false
            etDiameter.isCursorVisible = true


            etWeight.isFocusable = true
            etWeight.isFocusableInTouchMode = true
            etWeight.showSoftInputOnFocus = false
            etWeight.isCursorVisible = true


            etWallThickness.isFocusable = true
            etWallThickness.isFocusableInTouchMode = true
            etWallThickness.showSoftInputOnFocus = false
            etWallThickness.isCursorVisible = true
        }


        private fun setupQuantityEditText(product: Product) {
            etQuantity.removeTextChangedListener(quantityWatcher)

            val quantityText: String

            val currentQuantity = product.quantity ?: 0.0

            if (product.measureName == "Штука") {
                etQuantity.inputType = InputType.TYPE_CLASS_NUMBER
                quantityText = currentQuantity.toInt().toString()
            } else {
                etQuantity.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                quantityText = if (currentQuantity % 1.0 == 0.0) {
                    currentQuantity.toInt().toString()
                } else {

                    String.format(Locale.US, "%.3f", currentQuantity).trimEnd('0').trimEnd('.')
                }
            }
            etQuantity.setText(quantityText)
            etQuantity.addTextChangedListener(quantityWatcher)
        }

        @SuppressLint("SetTextI18n")
        fun bind(product: Product) {
            tvProductName.text = product.name ?: "Без названия"
            Log.d("Adapter", "BIND: Setting barcode '${product.barcode}' for product ${product.id}")
            tvBarcode.setText(product.barcode ?: "")
            Log.d("Adapter", "BIND: Barcode set successfully")

            // Visual validation for barcode
            val hasBarcode = !product.barcode.isNullOrBlank()
            // По умолчанию без фокуса — стандартный фон, ошибка скрыта
            tvBarcode.setBackgroundResource(R.drawable.input_field_yellow_border_blue_bg)
            tvBarcodeError.visibility = View.GONE



            etDiameter.setText(product.diameter?.toString() ?: "")
            etWeight.setText(product.weight?.toString() ?: "")
            etWallThickness.setText(product.height?.toString() ?: "")


            if (quantityWatcher == null) {
                quantityWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val currentPosition = adapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION && currentPosition < productList.size) {
                            val currentProduct = productList.getOrNull(currentPosition) ?: return
                            val inputString = s?.toString()?.replace(',', '.') ?: ""
                            var newQuantityModel: Double? = null

                            if (inputString.isBlank()) {
                                newQuantityModel = null
                            } else {
                                val parsedDouble = inputString.toDoubleOrNull()
                                if (parsedDouble != null) {
                                    newQuantityModel = if (currentProduct.measureName == "Штука") {
                                        parsedDouble.toInt().toDouble()
                                    } else {
                                        parsedDouble
                                    }
                                } else {
                                    if (inputString.isNotEmpty() && inputString != "." && inputString != "-") {
                                        return
                                    }
                                }
                            }

                            if (currentProduct.quantity != newQuantityModel) {
                                currentProduct.quantity = newQuantityModel
                                onDataChangedListener?.invoke()
                            }
                        }
                    }
                    override fun afterTextChanged(s: Editable?) {}
                }
            }

            setupQuantityEditText(product)


            spinnerMeasure.onItemSelectedListener = null
            val currentMeasureIndex = measures.indexOf(product.measureName).takeIf { it != -1 } ?: 0
            spinnerMeasure.setSelection(currentMeasureIndex, false)
            spinnerMeasure.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val selectedMeasure = measures.getOrNull(pos) ?: return
                    val currentPosition = adapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION && currentPosition < productList.size) {
                        val currentProduct = productList.getOrNull(currentPosition) ?: return
                        if (currentProduct.measureName != selectedMeasure) {
                            currentProduct.measureName = selectedMeasure
                            Log.d("ViewHolder", "Measure changed to '$selectedMeasure' at pos $currentPosition for product ID ${currentProduct.id}")
                            if (selectedMeasure == "Штука") {
                                currentProduct.quantity?.let { qty ->
                                    if (qty % 1.0 != 0.0) {
                                        currentProduct.quantity = qty.toInt().toDouble()
                                    }
                                }
                            }

                            setupQuantityEditText(currentProduct)
                            onDataChangedListener?.invoke()
                        }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }


            etDiameter.removeTextChangedListener(diameterWatcher)
            if (diameterWatcher == null) {
                diameterWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val currentPosition = adapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION && currentPosition < productList.size) {
                            val currentProduct = productList.getOrNull(currentPosition) ?: return
                            val newDiameter = s?.toString()?.replace(',', '.')?.toDoubleOrNull()
                            if (currentProduct.diameter != newDiameter) {
                                currentProduct.diameter = newDiameter
                                onDataChangedListener?.invoke()
                            }
                        }
                    }
                    override fun afterTextChanged(s: Editable?) {}
                }
            }
            etDiameter.addTextChangedListener(diameterWatcher)


            etWeight.removeTextChangedListener(weightWatcher)
            if (weightWatcher == null) {
                weightWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val currentPosition = adapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION && currentPosition < productList.size) {
                            val currentProduct = productList.getOrNull(currentPosition) ?: return
                            val newWeight = s?.toString()?.replace(',', '.')?.toDoubleOrNull()
                            if (currentProduct.weight != newWeight) {
                                currentProduct.weight = newWeight
                                onDataChangedListener?.invoke()
                            }
                        }
                    }
                    override fun afterTextChanged(s: Editable?) {}
                }
            }
            etWeight.addTextChangedListener(weightWatcher)


            etWallThickness.removeTextChangedListener(wallThicknessWatcher)
            if (wallThicknessWatcher == null) {
                wallThicknessWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val currentPosition = adapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION && currentPosition < productList.size) {
                            val currentProduct = productList.getOrNull(currentPosition) ?: return

                            val newWallThickness = s?.toString()?.replace(',', '.')?.toDoubleOrNull()

                            if (currentProduct.height != newWallThickness) {
                                currentProduct.height = newWallThickness
                                onDataChangedListener?.invoke()
                            }
                        }
                    }
                    override fun afterTextChanged(s: Editable?) {}
                }
            }
            etWallThickness.addTextChangedListener(wallThicknessWatcher)






            tvBarcode.setOnFocusChangeListener { _, hasFocus ->
                val currentPosition = adapterPosition
                if (currentPosition != RecyclerView.NO_POSITION && currentPosition < productList.size) {
                    if (hasFocus) {
                        focusedPosition = currentPosition
                        Log.d("Adapter", "Barcode field focused at position $currentPosition")
                    } else {
                        if (focusedPosition == currentPosition) {
                            focusedPosition = RecyclerView.NO_POSITION
                        }
                        // sync model from UI on focus loss
                        val text = tvBarcode.text?.toString()?.trim()
                        val currentProduct = productList.getOrNull(currentPosition)
                        if (currentProduct != null && currentProduct.barcode != text) {
                            currentProduct.barcode = text
                        }
                        // update visual error state
                        // Потеря фокуса — возвращаем нормальный фон и скрываем ошибку
                        tvBarcode.setBackgroundResource(R.drawable.input_field_yellow_border_blue_bg)
                        tvBarcodeError.visibility = View.GONE
                        onDataChangedListener?.invoke()
                        Log.d("Adapter", "Barcode field focus lost, updating button state")
                    }
                }
            }

            // react to barcode typing
            tvBarcode.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val currentPosition = adapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION && currentPosition < productList.size) {
                        val currentProduct = productList.getOrNull(currentPosition) ?: return
                        val text = s?.toString()?.trim()
                        currentProduct.barcode = text
                        val has = !text.isNullOrBlank()
                        // Во время ввода при фокусе — подсвечиваем ошибкой только если пусто
                        if (tvBarcode.hasFocus()) {
                            val res = if (has) R.drawable.input_field_yellow_border_blue_bg else R.drawable.item_card_yellow_border_dark_blue_bg
                            tvBarcode.setBackgroundResource(res)
                            tvBarcodeError.visibility = if (has) View.GONE else View.VISIBLE
                        }
                        onDataChangedListener?.invoke()
                    }
                }
            })


            btnDelete.setOnClickListener {
                val currentPosition = adapterPosition

                if (currentPosition != RecyclerView.NO_POSITION && currentPosition < productList.size) {
                    onDelete(currentPosition)
                }
            }
        }
    }


    inner class CommentViewHolder(
        itemView: View,
        private val onCommentChangedCallback: (String?) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val etDocumentComment: EditText = itemView.findViewById(R.id.etDocumentComment)
        private var commentTextWatcher: TextWatcher? = null

        @SuppressLint("SetTextI18n")
        fun bind(comment: String?) {
            etDocumentComment.removeTextChangedListener(commentTextWatcher)
            etDocumentComment.setText(comment ?: "")


            if (commentTextWatcher == null) {
                commentTextWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val newText = s?.toString()
                        onCommentChangedCallback(newText)
                    }

                    override fun afterTextChanged(s: Editable?) {}
                }
            }
            etDocumentComment.addTextChangedListener(commentTextWatcher)
        }
    }
}