package com.example.scannerkotlin.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.scannerkotlin.R
import com.example.scannerkotlin.adapter.DocumentComingAdapter
import com.example.scannerkotlin.decoration.SpaceItemDecoration
import com.example.scannerkotlin.listener.OnItemClickListener
import com.example.scannerkotlin.model.Document
import com.example.scannerkotlin.service.CatalogDocumentComingService

import com.example.scannerkotlin.utils.SessionManager

import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@RequiresApi(Build.VERSION_CODES.O)
class DocumentComingActivity : AppCompatActivity() {


    private lateinit var adapter: DocumentComingAdapter
    private lateinit var service: CatalogDocumentComingService
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyListTextView: TextView

    private var loadDocumentsJob: Job? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        if (!SessionManager.isUserLoggedIn()) {
            Log.e("DocumentComingActivity", "User not logged in. Finishing activity.")
            Toast.makeText(this, "Сессия истекла. Пожалуйста, войдите снова.", Toast.LENGTH_LONG).show()
            finish()
            return
        }


        setContentView(R.layout.activity_document_coming)

        service = CatalogDocumentComingService()


        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.documentRecycleView)
        emptyListTextView = findViewById(R.id.emptyListTextView)

        setupRecyclerView()
        loadDocuments()
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(SpaceItemDecoration(15))

        adapter = DocumentComingAdapter(mutableListOf())
        recyclerView.adapter = adapter
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("NotifyDataSetChanged")
    private fun loadDocuments() {
        loadDocumentsJob?.cancel()
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyListTextView.visibility = View.GONE
        Log.d("DocumentComingActivity", "Starting document load...")

        loadDocumentsJob = lifecycleScope.launch {
            var finalDocuments: List<Document> = emptyList()
            var error: Throwable? = null

            try {

                val initialDocuments = withContext(Dispatchers.IO) {
                    service.getDocumentsSuspend()
                }
                Log.d("DocumentComingActivity", "Loaded ${initialDocuments.size} initial documents")


                if (initialDocuments.isNotEmpty()) {
                    Log.d("DocumentComingActivity", "Fetching nomenclature for ${initialDocuments.size} documents...")
                    finalDocuments = withContext(Dispatchers.IO) {

                        service.fetchAndAssignNomenclature(initialDocuments)
                    }
                    Log.d("DocumentComingActivity", "Nomenclature fetched. Final document count: ${finalDocuments.size}")


                    if (finalDocuments.isNotEmpty()) {
                        finalDocuments.forEachIndexed { index, doc ->
                            Log.d("DocumentComingActivity", "Doc $index ID ${doc.id}, Nomenclature: ${doc.field620Values}")
                        }
                    }

                } else {

                    finalDocuments = initialDocuments
                }

            } catch (e: Exception) {
                Log.e("DocumentComingActivity", "Error loading documents or nomenclature", e)
                error = e
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (isActive) {
                        if (error != null) {
                            Toast.makeText(
                                this@DocumentComingActivity,
                                "Ошибка загрузки: ${error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            emptyListTextView.text = "Ошибка загрузки"
                            emptyListTextView.visibility = View.VISIBLE
                            recyclerView.visibility = View.GONE
                            adapter.updateData(emptyList())
                        } else {

                            if (finalDocuments.isEmpty()) {
                                Log.d("DocumentComingActivity", "Final document list is empty. Showing empty message.")
                                emptyListTextView.text = "Список пуст"
                                emptyListTextView.visibility = View.VISIBLE
                                recyclerView.visibility = View.GONE
                            } else {
                                Log.d("DocumentComingActivity", "Final document list is not empty. Showing RecyclerView.")
                                emptyListTextView.visibility = View.GONE
                                recyclerView.visibility = View.VISIBLE
                            }

                            adapter.updateData(finalDocuments)
                        }
                    } else {
                        Log.d("DocumentComingActivity", "Document loading coroutine was cancelled. UI not updated.")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loadDocumentsJob?.cancel()
        Log.d("DocumentComingActivity", "onDestroy called, job cancelled.")
    }
}