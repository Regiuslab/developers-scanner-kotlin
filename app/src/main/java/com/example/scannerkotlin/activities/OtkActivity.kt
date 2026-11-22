package com.example.scannerkotlin.activities

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView // Убедимся, что TextView импортирован
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.scannerkotlin.R
import com.example.scannerkotlin.service.OtkService
import com.example.scannerkotlin.service.ScanService
import com.example.scannerkotlin.utils.BaseScanActivity
import com.example.scannerkotlin.utils.SessionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class OtkActivity : BaseScanActivity() {

    private lateinit var logoutButton: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var scanPromptTextView: TextView

    private var service: OtkService? = null
    private val scanGate = com.example.scannerkotlin.utils.BarcodeScanGate(duplicateWindowMs = 500L)

    override fun onBarcodeScanned(barcode: String) {
        if (!scanGate.tryAcquire(barcode)) return
        processBarcode(barcode)
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("OtkActivity", "Coroutine Exception: ${throwable.localizedMessage}", throwable)
        lifecycleScope.launch(Dispatchers.Main) {
            showLoading(false)
            showAlertInfo("Произошла непредвиденная ошибка: ${throwable.message}")
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otk)


        service = OtkService()

        logoutButton = findViewById(R.id.logoutButtonOtk)
        progressBar = findViewById(R.id.progressBarOtk)
        scanPromptTextView = findViewById(R.id.textView2)

        logoutButton.setOnClickListener {
            performLogout()
        }
        Log.w("OtkActivity", "check")
    }

    private fun showLoading(isLoading: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isLoading) {
                progressBar.visibility = View.VISIBLE
                scanPromptTextView.visibility = View.GONE
                logoutButton.isEnabled = false
            } else {
                progressBar.visibility = View.GONE
                scanPromptTextView.visibility = View.VISIBLE
                logoutButton.isEnabled = true
            }
        }
    }



    private fun processBarcode(barcode: String) {
        showLoading(true)
        lifecycleScope.launch(coroutineExceptionHandler) {
            try {
                val processByBarcode = service?.getProcessByBarcode(barcode)

                if (processByBarcode?.result?.items?.isEmpty() == true) {
                    withContext(Dispatchers.Main) {

                        showAlertInfo("Не найден процесс в работе со штрихкодом $barcode")
                    }

                    withContext(Dispatchers.Main) { showLoading(false) }
                    return@launch
                }




                val firstItem = processByBarcode?.result?.items?.firstOrNull()
                if (firstItem == null) {
                    Log.e("OtkActivity", "First item is null after getting process by barcode.")
                    withContext(Dispatchers.Main) {
                        showAlertInfo("Не удалось получить детали процесса.")
                    }
                    withContext(Dispatchers.Main) { showLoading(false) }
                    return@launch
                }



                withContext(Dispatchers.Main) { showLoading(false) }


                showStatusConfirmationDialogWithDefectInput(
                    itemDescription = firstItem.title,
                    onPassed = {
                        showLoading(true)
                        lifecycleScope.launch(coroutineExceptionHandler) {
                            try {
                                Log.d("OtkActivity", "Attempting to mark item ${firstItem.id} as PASSED")
                                service?.updateItem(id = firstItem.id, marriage = null)
                                Log.d("OtkActivity", "Item ${firstItem.id} marked as PASSED.")
                                withContext(Dispatchers.Main) {
                                    showAlertInfo("Элемент отмечен: Пройден")
                                }
                            } catch (updateException: Exception) {
                                Log.e("OtkActivity", "Error updating item ${firstItem.id} as PASSED", updateException)
                                withContext(Dispatchers.Main) {
                                    showAlertInfo("Ошибка при обновлении статуса 'Пройден': ${updateException.message}")
                                }
                            } finally {

                                withContext(Dispatchers.Main) { showLoading(false) }
                            }
                        }
                    },
                    onNotPassedWithReason = { defectReason ->
                        showLoading(true)
                        lifecycleScope.launch(coroutineExceptionHandler) {
                            try {
                                Log.d("OtkActivity", "Attempting to mark item ${firstItem.id} as NOT PASSED with reason: $defectReason")
                                service?.updateItem(id = firstItem.id, marriage = defectReason)
                                Log.d("OtkActivity", "Item ${firstItem.id} marked as NOT PASSED. Reason: $defectReason")
                                withContext(Dispatchers.Main) {
                                    showAlertInfo("Элемент отмечен: Не пройден (брак: $defectReason)")
                                }
                            } catch (updateException: Exception) {
                                Log.e("OtkActivity", "Error updating item ${firstItem.id} as NOT PASSED", updateException)
                                withContext(Dispatchers.Main) {
                                    showAlertInfo("Ошибка при обновлении статуса 'Не пройден': ${updateException.message}")
                                }
                            } finally {
                                withContext(Dispatchers.Main) { showLoading(false) }
                            }
                        }
                    },
                    onCancelInitialChoice = {
                        Log.d("OtkActivity", "Status selection cancelled by user for ${firstItem.id}")

                    }
                )

            } catch (e: IOException) {
                Log.e("OtkActivity", "Network or IO Error processing barcode $barcode", e)
                withContext(Dispatchers.Main) {

                    showAlertInfo("Ошибка сети при обработке штрихкода")
                }
            } catch (e: Exception) {
                Log.e("OtkActivity", "Unexpected error processing barcode $barcode", e)
                withContext(Dispatchers.Main) {
                    showAlertInfo("Произошла ошибка при обработке штрихкода: ${e.message}")
                }
            } finally {
                scanGate.release()
                if (progressBar.visibility == View.VISIBLE) {
                    withContext(Dispatchers.Main) { showLoading(false) }
                }
            }
        }
    }


    private fun performLogout() {
        Log.d("OtkActivity", "Logout button clicked.")
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
            Log.w("OtkActivity", "Attempted to show alert dialog on finishing/destroyed activity")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showStatusConfirmationDialogWithDefectInput(
        itemDescription: String?,
        onPassed: () -> Unit,
        onNotPassedWithReason: (defectReason: String) -> Unit,
        onCancelInitialChoice: (() -> Unit)? = null
    ) {
        if (!isFinishing && !isDestroyed) {
            val dialog = Dialog(this)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(R.layout.dialog_status_confirmation_with_defect)


            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
            dialog.window?.setGravity(Gravity.CENTER)
            dialog.setCancelable(false)


            val tvDialogTitle: TextView = dialog.findViewById(R.id.tvDialogTitle)
            val btnCloseDialog: ImageButton = dialog.findViewById(R.id.btnCloseDialog)
            val tvMessage: TextView = dialog.findViewById(R.id.tvDialogMessage)
            val layoutInitialButtons: LinearLayout = dialog.findViewById(R.id.layoutInitialButtons)
            val btnPassed: MaterialButton = dialog.findViewById(R.id.btnDialogPassed)
            val btnNotPassed: MaterialButton = dialog.findViewById(R.id.btnDialogNotPassed)

            val layoutDefectInput: LinearLayout = dialog.findViewById(R.id.layoutDefectInput)
            val etDefectReason: com.google.android.material.textfield.TextInputEditText = dialog.findViewById(R.id.etDefectReason)
            val btnSubmitDefect: MaterialButton = dialog.findViewById(R.id.btnDialogSubmitDefect)
            val btnCancelDefect: MaterialButton = dialog.findViewById(R.id.btnDialogCancelDefect)


            tvMessage.text = "Как оценить элемент: \"${itemDescription ?: "Неизвестный элемент"}\"?"

            btnCloseDialog.setOnClickListener {
                onCancelInitialChoice?.invoke()
                dialog.dismiss()
            }

            btnPassed.setOnClickListener {
                onPassed()
                dialog.dismiss()
            }

            btnNotPassed.setOnClickListener {
                layoutInitialButtons.visibility = View.GONE
                layoutDefectInput.visibility = View.VISIBLE
                etDefectReason.requestFocus()

            }

            btnSubmitDefect.setOnClickListener {
                val reason = etDefectReason.text.toString().trim()
                if (reason.isNotBlank()) {
                    onNotPassedWithReason(reason)
                    dialog.dismiss()
                } else {

                    (etDefectReason.parent.parent as? com.google.android.material.textfield.TextInputLayout)?.error = "Укажите причину брака"

                }
            }

            btnCancelDefect.setOnClickListener {

                layoutDefectInput.visibility = View.GONE
                layoutInitialButtons.visibility = View.VISIBLE
                (etDefectReason.parent.parent as? com.google.android.material.textfield.TextInputLayout)?.error = null
                etDefectReason.text = null



            }


            dialog.setOnCancelListener {
                onCancelInitialChoice?.invoke()
            }

            dialog.show()

        } else {
            Log.w("OtkActivity", "Attempted to show status confirmation dialog on finishing/destroyed activity")
        }
    }


    override fun onDestroy() {
        super.onDestroy()
    }
}