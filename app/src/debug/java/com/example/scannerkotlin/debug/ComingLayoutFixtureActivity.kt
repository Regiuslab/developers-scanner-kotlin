package com.example.scannerkotlin.debug

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.example.scannerkotlin.activities.MainActivity
import com.example.scannerkotlin.activities.ProductsDocumentComingActivity
import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.persistence.ComingDraftRepository
import com.example.scannerkotlin.utils.ComingDocumentDraftManager
import com.example.scannerkotlin.utils.SessionManager

/**
 * Debug-only launcher (excluded from release builds via `src/debug`) that seeds persistent
 * coming drafts and opens the real [ProductsDocumentComingActivity] for layout, stress,
 * and crash-recovery verification without touching production Bitrix data.
 */
@RequiresApi(Build.VERSION_CODES.O)
class ComingLayoutFixtureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SessionManager.saveSession("1", "75")
        MainActivity.resetRecoveryPromptFlagForTest()

        if (intent.getBooleanExtra(EXTRA_FORCE_DEBUG_CRASH, false)) {
            // Debug-only crash trigger to verify CrashDiagnosticsHandler + SQLite recovery (CR-07).
            window.decorView.post {
                throw RuntimeException("DEBUG_TEST_FORCED_CRASH")
            }
            return
        }

        val reopenOnly = intent.getBooleanExtra(EXTRA_REOPEN_EXISTING, false)
        val documentId = intent.getIntExtra(EXTRA_DOCUMENT_ID, DEFAULT_DOCUMENT_ID)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Приход №$documentId"

        if (!reopenOnly) {
            val longName = intent.getBooleanExtra(EXTRA_LONG_NAME, true)
            val fillBarcodes = intent.getBooleanExtra(EXTRA_FILL_BARCODES, false)
            val productCount = intent.getIntExtra(EXTRA_PRODUCT_COUNT, 1).coerceIn(1, 1200)

            val products = (0 until productCount).map { index ->
                Product(
                    idInDocument = null,
                    id = 318857 + (index % 20),
                    iblockId = 14,
                    name = if (longName) {
                        "Труба ст20 76х12 ГОСТ 8732-78 бесшовная горячедеформированная #${index + 1}"
                    } else {
                        "Труба ст20 76х12 #${index + 1}"
                    },
                    measureId = 5,
                    measureSymbol = "шт",
                    quantity = 1.0,
                    barcode = if (fillBarcodes) String.format("460999%07d", index + 1) else null,
                    diameter = 76.0,
                    weight = 12.5,
                    height = 12.0,
                    parentId = null,
                    storeAmount = null,
                    batchNumber = "П-2026-09",
                    isOldProduct = false
                )
            }

            ComingDocumentDraftManager.saveDraft(
                documentId = documentId,
                products = products,
                comment = "fixture comment",
                documentTitle = title
            )

            val forceState = intent.getStringExtra(EXTRA_FORCE_STATE)
            if (!forceState.isNullOrBlank()) {
                ComingDraftRepository.engine().updateDraftMetadata(
                    documentId = documentId,
                    state = ComingOperationState.fromNameOrDefault(forceState)
                )
            }
        }

        val open = Intent(this, ProductsDocumentComingActivity::class.java).apply {
            putExtra("idDocument", documentId.toString())
            putExtra("title", title)
        }
        startActivity(open)
        finish()
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "documentId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LONG_NAME = "longName"
        const val EXTRA_PRODUCT_COUNT = "productCount"
        const val EXTRA_FILL_BARCODES = "fillBarcodes"
        const val EXTRA_REOPEN_EXISTING = "reopenExisting"
        const val EXTRA_FORCE_STATE = "forceState"
        const val EXTRA_FORCE_DEBUG_CRASH = "forceDebugCrash"
        const val DEFAULT_DOCUMENT_ID = 900001
    }
}
