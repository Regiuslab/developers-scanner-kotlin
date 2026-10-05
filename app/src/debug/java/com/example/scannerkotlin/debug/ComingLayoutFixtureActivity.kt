package com.example.scannerkotlin.debug

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.example.scannerkotlin.activities.ProductsDocumentComingActivity
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.utils.ComingDocumentDraftManager

/**
 * Debug-only launcher that seeds an in-memory coming draft and opens the real
 * ProductsDocumentComingActivity. Used for layout screenshots without Bitrix.
 */
@RequiresApi(Build.VERSION_CODES.O)
class ComingLayoutFixtureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val documentId = intent.getIntExtra(EXTRA_DOCUMENT_ID, DEFAULT_DOCUMENT_ID)
        val longName = intent.getBooleanExtra(EXTRA_LONG_NAME, true)
        val productCount = intent.getIntExtra(EXTRA_PRODUCT_COUNT, 1).coerceIn(1, 5)

        val products = (0 until productCount).map { index ->
            Product(
                idInDocument = null,
                id = 318857 + index,
                iblockId = 14,
                name = if (longName) {
                    "Труба ст20 76х12 ГОСТ 8732-78 бесшовная горячедеформированная для нефтегазопроводов"
                } else {
                    "Труба ст20 76х12 #${index + 1}"
                },
                measureId = 5,
                measureSymbol = "шт",
                quantity = 1.0,
                barcode = null,
                diameter = 76.0,
                weight = 12.5,
                height = 12.0,
                parentId = null,
                storeAmount = null,
                batchNumber = "П-2026-09",
                isOldProduct = false
            )
        }

        ComingDocumentDraftManager.saveDraft(documentId, products, "fixture comment")

        val open = Intent(this, ProductsDocumentComingActivity::class.java).apply {
            putExtra("idDocument", documentId.toString())
            putExtra("title", "Приход №fixture")
        }
        startActivity(open)
        finish()
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "documentId"
        const val EXTRA_LONG_NAME = "longName"
        const val EXTRA_PRODUCT_COUNT = "productCount"
        const val DEFAULT_DOCUMENT_ID = 900001
    }
}
