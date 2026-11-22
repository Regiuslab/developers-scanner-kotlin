package com.example.scannerkotlin.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.scannerkotlin.R
import com.example.scannerkotlin.model.ComingDocumentDraftRecord
import com.example.scannerkotlin.persistence.ComingDraftRepository
import com.example.scannerkotlin.utils.DeveloperCredits
import com.example.scannerkotlin.utils.SessionManager
import com.example.scannerkotlin.utils.TsdLogger
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var scanButton: MaterialButton
    private lateinit var productsButton: MaterialButton
    private lateinit var documentButton: MaterialButton
    private lateinit var logoutButton: MaterialButton
    private lateinit var sgpButton: MaterialButton

    companion object {
        @Volatile
        private var hasShownProcessRecoveryPrompt: Boolean = false

        fun resetRecoveryPromptFlagForTest() {
            hasShownProcessRecoveryPrompt = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!SessionManager.isUserLoggedIn()) {
            goToAuthActivity()
            return
        }

        setContentView(R.layout.activity_main)

        try {
            SessionManager.requireUserId()
        } catch (e: IllegalStateException) {
            TsdLogger.error(category = "SESSION", event = "missing_user_id_on_main", throwable = e)
            Toast.makeText(this, "Ошибка сессии. Пожалуйста, перезайдите.", Toast.LENGTH_LONG).show()
            goToAuthActivity()
            return
        }

        scanButton = findViewById(R.id.scanButton)
        productsButton = findViewById(R.id.productsButton)
        documentButton = findViewById(R.id.documentButton)
        logoutButton = findViewById(R.id.logoutButton)
        sgpButton = findViewById(R.id.incomingSgpButton)

        scanButton.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        productsButton.setOnClickListener {
            startActivity(Intent(this, DocumentMovingActivity::class.java))
        }
        documentButton.setOnClickListener {
            startActivity(Intent(this, DocumentComingActivity::class.java))
        }
        logoutButton.setOnClickListener {
            performLogout()
        }
        sgpButton.setOnClickListener {
            startActivity(Intent(this, SgpActivity::class.java))
        }
        findViewById<TextView>(R.id.regiusLabel).setOnClickListener {
            DeveloperCredits.showAboutDialog(this)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onResume() {
        super.onResume()
        checkAndOfferDraftRecovery()
    }

    @SuppressLint("SetTextI18n")
    private fun checkAndOfferDraftRecovery() {
        lifecycleScope.launch {
            val activeDrafts = ComingDraftRepository.getInstance().listRecoverableDrafts()
            if (isFinishing || isDestroyed) return@launch

            if (activeDrafts.isEmpty()) {
                documentButton.text = getString(R.string.productsButton)
                return@launch
            }

            documentButton.text = "${getString(R.string.productsButton)} (${activeDrafts.size})"

            if (!hasShownProcessRecoveryPrompt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                hasShownProcessRecoveryPrompt = true
                showRecoveryPromptDialog(activeDrafts)
            }
        }
    }

    private fun showRecoveryPromptDialog(activeDrafts: List<ComingDocumentDraftRecord>) {
        if (isFinishing || isDestroyed || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val timeFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

        if (activeDrafts.size == 1) {
            val draft = activeDrafts.first()
            val title = draft.documentTitle ?: "Приход №${draft.documentId}"
            val updatedAt = timeFmt.format(Date(draft.updatedAtMs))
            MaterialAlertDialogBuilder(this, R.style.MyCustomMaterialAlertDialogStyle)
                .setTitle("Восстановление документа")
                .setMessage(
                    "Найден незавершённый документ:\n" +
                        "«$title»\n" +
                        "Позиций: ${draft.itemCount} • ${draft.state.toRussianLabel()}\n" +
                        "Изменён: $updatedAt\n\n" +
                        "Продолжить работу с сохранённого места?"
                )
                .setPositiveButton("Продолжить") { dialog, _ ->
                    dialog.dismiss()
                    openRecoveredDraft(draft)
                }
                .setNegativeButton("Позже") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        } else {
            val labels = activeDrafts.map { draft ->
                val title = draft.documentTitle ?: "Приход №${draft.documentId}"
                val updatedAt = timeFmt.format(Date(draft.updatedAtMs))
                "$title — ${draft.itemCount} поз. (${draft.state.toRussianLabel()}, $updatedAt)"
            }.toTypedArray()

            MaterialAlertDialogBuilder(this, R.style.MyCustomMaterialAlertDialogStyle)
                .setTitle("Выберите незавершённый приход (${activeDrafts.size})")
                .setItems(labels) { dialog, which ->
                    dialog.dismiss()
                    activeDrafts.getOrNull(which)?.let { openRecoveredDraft(it) }
                }
                .setNegativeButton("Позже") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun openRecoveredDraft(draft: ComingDocumentDraftRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        TsdLogger.info(
            category = "RECOVERY",
            event = "user_opened_recovered_draft",
            operationId = draft.operationId,
            documentId = draft.documentId,
            fields = mapOf("items" to draft.itemCount, "state" to draft.state.name)
        )
        val intent = Intent(this, ProductsDocumentComingActivity::class.java).apply {
            putExtra("idDocument", draft.documentId.toString())
            putExtra("title", draft.documentTitle ?: "Приход №${draft.documentId}")
            draft.allowedNomenclatureIds?.let {
                putExtra("field620Values", ArrayList(it))
            }
        }
        startActivity(intent)
    }

    private fun performLogout() {
        // Logout clears user session ONLY; saved warehouse drafts remain intact on disk!
        SessionManager.clearSession()
        goToAuthActivity()
    }

    private fun goToAuthActivity() {
        val intent = Intent(this, AuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
