package com.example.scannerkotlin.adapter

import android.annotation.SuppressLint
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.scannerkotlin.R
import com.example.scannerkotlin.model.ComingSyncState
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.utils.BarcodeSanitizer
import com.example.scannerkotlin.utils.ComingQuantityDisplay

class ProductComingAdapter(
    private val productList: MutableList<Product>,
    private val onDelete: (position: Int) -> Unit,
    private val onCopy: (position: Int) -> Unit,
    private var currentDocumentComment: String?,
    private val onCommentChanged: (String?) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var focusedPosition: Int = RecyclerView.NO_POSITION
    private val noMeasureValue = "Выберите ед."
    private val measures = listOf(noMeasureValue, "Штука", "Килограмм", "Литр", "Метр", "Грамм")

    private var onDataChangedListener: (() -> Unit)? = null
    private var recyclerView: RecyclerView? = null

    init {
        setHasStableIds(true)
    }

    companion object {
        const val PAYLOAD_BARCODE_UPDATE = "barcode_update"
        const val PAYLOAD_QUANTITY_UPDATE = "quantity_update"
        const val PAYLOAD_ERROR_UPDATE = "error_update"
        private const val VIEW_TYPE_PRODUCT = 0
        private const val VIEW_TYPE_COMMENT = 1
        private const val COMMENT_STABLE_ID = -999_999L
    }

    override fun getItemId(position: Int): Long {
        return if (position in 0 until productList.size) {
            productList[position].localItemId.hashCode().toLong()
        } else {
            COMMENT_STABLE_ID
        }
    }

    fun setOnDataChangedListener(listener: () -> Unit) {
        this.onDataChangedListener = listener
    }

    fun setRecyclerView(rv: RecyclerView) {
        this.recyclerView = rv
    }

    fun getFocusedPosition(): Int = focusedPosition

    fun setCurrentDocumentComment(comment: String?) {
        this.currentDocumentComment = comment
        if (productList.isNotEmpty()) {
            val commentPos = productList.size
            if (commentPos < itemCount) {
                notifyItemChanged(commentPos)
            }
        }
    }

    fun countMissingBarcodes(): Int {
        var missing = 0
        for (i in 0 until productList.size) {
            if (productList[i].barcode.isNullOrBlank()) {
                missing++
            }
        }
        return missing
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

    fun findPositionByBarcode(barcode: String): Int {
        val normalized = BarcodeSanitizer.normalizeForComingVariation(barcode) ?: return -1
        for (i in 0 until productList.size) {
            if (productList[i].barcode == normalized) return i
        }
        return -1
    }

    fun areAllBarcodesFilledInUI(): Boolean {
        if (productList.isEmpty()) return false
        return countMissingBarcodes() == 0
    }

    override fun getItemViewType(position: Int): Int {
        return if (position < productList.size) VIEW_TYPE_PRODUCT else VIEW_TYPE_COMMENT
    }

    override fun getItemCount(): Int {
        return if (productList.isNotEmpty()) productList.size + 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PRODUCT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_coming_product, parent, false)
                applyFullPageLayoutParams(view, parent)
                ProductViewHolder(view)
            }
            VIEW_TYPE_COMMENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_document_comment, parent, false)
                applyFullPageLayoutParams(view, parent)
                CommentViewHolder(view, onCommentChanged)
            }
            else -> throw IllegalArgumentException("Invalid view type: $viewType")
        }
    }

    private fun applyFullPageLayoutParams(itemView: View, parent: ViewGroup) {
        val pageHeight = when {
            parent.height > 0 -> parent.height
            parent.measuredHeight > 0 -> parent.measuredHeight
            else -> ViewGroup.LayoutParams.MATCH_PARENT
        }
        itemView.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            pageHeight
        )
    }

    private fun ensureFullPageHeight(holder: RecyclerView.ViewHolder) {
        val rv = recyclerView ?: return
        if (rv.height <= 0) return
        val lp = holder.itemView.layoutParams as? RecyclerView.LayoutParams ?: return
        if (lp.height != rv.height) {
            lp.height = rv.height
            holder.itemView.layoutParams = lp
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (holder.itemViewType == VIEW_TYPE_PRODUCT && payloads.isNotEmpty() && position in 0 until productList.size) {
            val product = productList[position]
            val productHolder = holder as ProductViewHolder
            var handled = false
            if (payloads.contains(PAYLOAD_BARCODE_UPDATE)) {
                productHolder.updateBarcodeOnly(product)
                handled = true
            }
            if (payloads.contains(PAYLOAD_QUANTITY_UPDATE)) {
                productHolder.updateQuantityOnly(product)
                handled = true
            }
            if (payloads.contains(PAYLOAD_ERROR_UPDATE)) {
                productHolder.updateErrorVisuals(product)
                handled = true
            }
            if (handled) return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        ensureFullPageHeight(holder)
        when (holder.itemViewType) {
            VIEW_TYPE_PRODUCT -> {
                if (position in 0 until productList.size) {
                    (holder as ProductViewHolder).bind(productList[position], position)
                }
            }
            VIEW_TYPE_COMMENT -> {
                (holder as CommentViewHolder).bind(currentDocumentComment)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ProductViewHolder) {
            holder.detachAllListeners()
        } else if (holder is CommentViewHolder) {
            holder.detachListeners()
        }
    }

    fun applyPersistedBarcodeAt(position: Int, savedBarcode: String): Boolean {
        if (position !in 0 until productList.size) return false
        productList[position].barcode = savedBarcode
        productList[position].validationError = null
        productList[position].syncState = ComingSyncState.LOCAL_SAVED
        notifyItemChanged(position, PAYLOAD_BARCODE_UPDATE)
        onDataChangedListener?.invoke()
        return true
    }

    fun applyPersistedQuantityAt(position: Int, newQuantity: Double?): Boolean {
        if (position !in 0 until productList.size) return false
        productList[position].quantity = newQuantity
        productList[position].validationError = null
        productList[position].syncState = ComingSyncState.LOCAL_SAVED
        notifyItemChanged(position, PAYLOAD_QUANTITY_UPDATE)
        onDataChangedListener?.invoke()
        return true
    }

    fun updateFocusedProductBarcode(newBarcode: String): Boolean {
        val currentFocusedPosition = focusedPosition
        if (currentFocusedPosition != RecyclerView.NO_POSITION && currentFocusedPosition < productList.size) {
            val truncatedBarcode = BarcodeSanitizer.normalizeForComingVariation(newBarcode) ?: return false
            productList[currentFocusedPosition].barcode = truncatedBarcode
            productList[currentFocusedPosition].validationError = null
            notifyItemChanged(currentFocusedPosition, PAYLOAD_BARCODE_UPDATE)
            onDataChangedListener?.invoke()
            return true
        }
        return false
    }

    fun updateProductBarcodeAt(position: Int, newBarcode: String): Boolean {
        if (position !in 0 until productList.size) return false
        val truncatedBarcode = BarcodeSanitizer.normalizeForComingVariation(newBarcode) ?: return false
        productList[position].barcode = truncatedBarcode
        productList[position].validationError = null
        notifyItemChanged(position, PAYLOAD_BARCODE_UPDATE)
        onDataChangedListener?.invoke()
        return true
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
        val btnCopy: ImageButton = itemView.findViewById(R.id.btnCopy)
        val etDiameter: EditText = itemView.findViewById(R.id.etDiameter)
        val etWeight: EditText = itemView.findViewById(R.id.etWeight)
        val etWallThickness: EditText = itemView.findViewById(R.id.etWallThickness)
        val etBatchNumber: EditText = itemView.findViewById(R.id.etBatchNumber)
        val cbOldProduct: CheckBox = itemView.findViewById(R.id.cbOldProduct)

        private var quantityWatcher: TextWatcher? = null
        private var diameterWatcher: TextWatcher? = null
        private var weightWatcher: TextWatcher? = null
        private var wallThicknessWatcher: TextWatcher? = null
        private var batchWatcher: TextWatcher? = null
        private var barcodeWatcher: TextWatcher? = null

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
            tvBarcode.filters = arrayOf<InputFilter>(
                InputFilter.LengthFilter(BarcodeSanitizer.COMING_VARIATION_MAX_BARCODE_LENGTH)
            )

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

            etBatchNumber.isFocusable = true
            etBatchNumber.isFocusableInTouchMode = true
            etBatchNumber.showSoftInputOnFocus = false
            etBatchNumber.isCursorVisible = true

            ensureWatchersInitialized()
        }

        fun detachAllListeners() {
            quantityWatcher?.let { etQuantity.removeTextChangedListener(it) }
            diameterWatcher?.let { etDiameter.removeTextChangedListener(it) }
            weightWatcher?.let { etWeight.removeTextChangedListener(it) }
            wallThicknessWatcher?.let { etWallThickness.removeTextChangedListener(it) }
            batchWatcher?.let { etBatchNumber.removeTextChangedListener(it) }
            barcodeWatcher?.let { tvBarcode.removeTextChangedListener(it) }
            tvBarcode.onFocusChangeListener = null
            cbOldProduct.setOnCheckedChangeListener(null)
            spinnerMeasure.onItemSelectedListener = null
        }

        private fun ensureWatchersInitialized() {
            if (quantityWatcher == null) {
                quantityWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val pos = bindingOrAdapterPos() ?: return
                        val currentProduct = productList.getOrNull(pos) ?: return
                        val inputString = s?.toString()?.replace(',', '.') ?: ""
                        val newQuantityModel: Double? = if (inputString.isBlank()) {
                            null
                        } else {
                            val parsed = inputString.toDoubleOrNull()
                            if (parsed != null) {
                                if (currentProduct.measureName == "Штука") parsed.toInt().toDouble() else parsed
                            } else {
                                if (inputString != "." && inputString != "-") return else null
                            }
                        }
                        if (currentProduct.quantity != newQuantityModel) {
                            currentProduct.quantity = newQuantityModel
                            currentProduct.validationError = null
                            currentProduct.syncState = ComingSyncState.LOCAL_DIRTY
                            onDataChangedListener?.invoke()
                        }
                    }
                    override fun afterTextChanged(s: Editable?) {}
                }
            }

            if (diameterWatcher == null) {
                diameterWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val pos = bindingOrAdapterPos() ?: return
                        val currentProduct = productList.getOrNull(pos) ?: return
                        val newDiameter = s?.toString()?.replace(',', '.')?.toDoubleOrNull()
                        if (currentProduct.diameter != newDiameter) {
                            currentProduct.diameter = newDiameter
                            currentProduct.syncState = ComingSyncState.LOCAL_DIRTY
                            onDataChangedListener?.invoke()
                        }
                    }
                    override fun afterTextChanged(s: Editable?) {}
                }
            }

            if (weightWatcher == null) {
                weightWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val pos = bindingOrAdapterPos() ?: return
                        val currentProduct = productList.getOrNull(pos) ?: return
                        val newWeight = s?.toString()?.replace(',', '.')?.toDoubleOrNull()
                        if (currentProduct.weight != newWeight) {
                            currentProduct.weight = newWeight
                            currentProduct.validationError = null
                            currentProduct.syncState = ComingSyncState.LOCAL_DIRTY
                            onDataChangedListener?.invoke()
                        }
                    }
                    override fun afterTextChanged(s: Editable?) {}
                }
            }

            if (wallThicknessWatcher == null) {
                wallThicknessWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val pos = bindingOrAdapterPos() ?: return
                        val currentProduct = productList.getOrNull(pos) ?: return
                        val newWall = s?.toString()?.replace(',', '.')?.toDoubleOrNull()
                        if (currentProduct.height != newWall) {
                            currentProduct.height = newWall
                            currentProduct.syncState = ComingSyncState.LOCAL_DIRTY
                            onDataChangedListener?.invoke()
                        }
                    }
                    override fun afterTextChanged(s: Editable?) {}
                }
            }

            if (batchWatcher == null) {
                batchWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val pos = bindingOrAdapterPos() ?: return
                        val currentProduct = productList.getOrNull(pos) ?: return
                        val newBatch = s?.toString()?.trim()
                        if (currentProduct.batchNumber != newBatch) {
                            currentProduct.batchNumber = newBatch
                            currentProduct.validationError = null
                            currentProduct.syncState = ComingSyncState.LOCAL_DIRTY
                            onDataChangedListener?.invoke()
                        }
                    }
                    override fun afterTextChanged(s: Editable?) {}
                }
            }

            if (barcodeWatcher == null) {
                barcodeWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val pos = bindingOrAdapterPos() ?: return
                        val currentProduct = productList.getOrNull(pos) ?: return
                        val text = BarcodeSanitizer.normalizeForComingVariation(s?.toString())
                        if (currentProduct.barcode != text) {
                            currentProduct.barcode = text
                            currentProduct.validationError = null
                            currentProduct.syncState = ComingSyncState.LOCAL_DIRTY
                        }
                        updateErrorVisuals(currentProduct)
                        onDataChangedListener?.invoke()
                    }
                }
            }
        }

        private fun bindingOrAdapterPos(): Int? {
            val pos = adapterPosition
            return if (pos != RecyclerView.NO_POSITION && pos < productList.size) pos else null
        }

        private fun formatQuantityText(product: Product): String {
            etQuantity.inputType = if (ComingQuantityDisplay.allowsFraction(product.measureName)) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            return ComingQuantityDisplay.format(product.quantity, product.measureName)
        }

        fun updateBarcodeOnly(product: Product) {
            barcodeWatcher?.let { tvBarcode.removeTextChangedListener(it) }
            tvBarcode.setText(product.barcode ?: "")
            updateErrorVisuals(product)
            barcodeWatcher?.let { tvBarcode.addTextChangedListener(it) }
        }

        fun updateQuantityOnly(product: Product) {
            quantityWatcher?.let { etQuantity.removeTextChangedListener(it) }
            etQuantity.setText(formatQuantityText(product))
            updateErrorVisuals(product)
            quantityWatcher?.let { etQuantity.addTextChangedListener(it) }
        }

        fun updateErrorVisuals(product: Product) {
            val explicitError = product.validationError?.takeIf { it.isNotBlank() }
            if (explicitError != null) {
                tvBarcodeError.text = explicitError
                tvBarcodeError.visibility = View.VISIBLE
                tvBarcode.setBackgroundResource(R.drawable.item_card_yellow_border_dark_blue_bg)
            } else if (tvBarcode.hasFocus() && product.barcode.isNullOrBlank()) {
                tvBarcodeError.text = "Заполните штрихкод"
                tvBarcodeError.visibility = View.VISIBLE
                tvBarcode.setBackgroundResource(R.drawable.item_card_yellow_border_dark_blue_bg)
            } else {
                tvBarcodeError.visibility = View.GONE
                tvBarcode.setBackgroundResource(R.drawable.input_field_yellow_border_blue_bg)
            }
        }

        @SuppressLint("SetTextI18n")
        fun bind(product: Product, position: Int) {
            // Detach ALL listeners BEFORE populating views to avoid stale ViewHolder cross-talk
            detachAllListeners()
            ensureWatchersInitialized()

            tvProductName.text = product.name ?: "Без названия"
            tvBarcode.setText(product.barcode ?: "")
            etQuantity.setText(formatQuantityText(product))
            etDiameter.setText(product.diameter?.toString() ?: "")
            etWeight.setText(product.weight?.toString() ?: "")
            etWallThickness.setText(product.height?.toString() ?: "")
            etBatchNumber.setText(product.batchNumber ?: "")
            cbOldProduct.isChecked = product.isOldProduct

            val currentMeasureIndex = measures.indexOf(product.measureName).takeIf { it != -1 } ?: 0
            spinnerMeasure.setSelection(currentMeasureIndex, false)

            updateErrorVisuals(product)

            // Re-attach single reusable listeners AFTER views hold current model values
            etQuantity.addTextChangedListener(quantityWatcher)
            etDiameter.addTextChangedListener(diameterWatcher)
            etWeight.addTextChangedListener(weightWatcher)
            etWallThickness.addTextChangedListener(wallThicknessWatcher)
            etBatchNumber.addTextChangedListener(batchWatcher)
            tvBarcode.addTextChangedListener(barcodeWatcher)

            cbOldProduct.setOnCheckedChangeListener { _, isChecked ->
                val pos = bindingOrAdapterPos() ?: return@setOnCheckedChangeListener
                val currentProduct = productList.getOrNull(pos) ?: return@setOnCheckedChangeListener
                if (currentProduct.isOldProduct != isChecked) {
                    currentProduct.isOldProduct = isChecked
                    currentProduct.validationError = null
                    currentProduct.syncState = ComingSyncState.LOCAL_DIRTY
                    onDataChangedListener?.invoke()
                }
            }

            spinnerMeasure.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val selectedMeasure = measures.getOrNull(pos) ?: return
                    val adapterPos = bindingOrAdapterPos() ?: return
                    val currentProduct = productList.getOrNull(adapterPos) ?: return
                    val normalizedMeasure = if (selectedMeasure == noMeasureValue) null else selectedMeasure
                    if (currentProduct.measureName != normalizedMeasure) {
                        currentProduct.measureName = normalizedMeasure
                        currentProduct.validationError = null
                        currentProduct.syncState = ComingSyncState.LOCAL_DIRTY
                        currentProduct.quantity = ComingQuantityDisplay.afterMeasureChange(
                            currentProduct.quantity,
                            normalizedMeasure
                        )
                        updateQuantityOnly(currentProduct)
                        onDataChangedListener?.invoke()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            tvBarcode.setOnFocusChangeListener { _, hasFocus ->
                val adapterPos = bindingOrAdapterPos() ?: return@setOnFocusChangeListener
                val currentProduct = productList.getOrNull(adapterPos) ?: return@setOnFocusChangeListener
                if (hasFocus) {
                    focusedPosition = adapterPos
                } else {
                    if (focusedPosition == adapterPos) {
                        focusedPosition = RecyclerView.NO_POSITION
                    }
                    val text = BarcodeSanitizer.normalizeForComingVariation(tvBarcode.text?.toString())
                    if (currentProduct.barcode != text) {
                        currentProduct.barcode = text
                        currentProduct.syncState = ComingSyncState.LOCAL_DIRTY
                    }
                }
                updateErrorVisuals(currentProduct)
                onDataChangedListener?.invoke()
            }

            btnDelete.setOnClickListener {
                val adapterPos = bindingOrAdapterPos() ?: return@setOnClickListener
                onDelete(adapterPos)
            }

            btnCopy.setOnClickListener {
                val adapterPos = bindingOrAdapterPos() ?: return@setOnClickListener
                onCopy(adapterPos)
            }
        }
    }

    inner class CommentViewHolder(
        itemView: View,
        private val onCommentChangedCallback: (String?) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val etDocumentComment: EditText = itemView.findViewById(R.id.etDocumentComment)
        private var commentTextWatcher: TextWatcher? = null

        fun detachListeners() {
            commentTextWatcher?.let { etDocumentComment.removeTextChangedListener(it) }
        }

        @SuppressLint("SetTextI18n")
        fun bind(comment: String?) {
            detachListeners()
            etDocumentComment.setText(comment ?: "")
            if (commentTextWatcher == null) {
                commentTextWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        onCommentChangedCallback(s?.toString())
                    }
                    override fun afterTextChanged(s: Editable?) {}
                }
            }
            etDocumentComment.addTextChangedListener(commentTextWatcher)
        }
    }
}
