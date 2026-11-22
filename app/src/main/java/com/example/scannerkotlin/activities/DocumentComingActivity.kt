package com.example.scannerkotlin.activities

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
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
import com.example.scannerkotlin.model.ComingDocumentDraftRecord
import com.example.scannerkotlin.model.Document
import com.example.scannerkotlin.persistence.ComingDraftRepository
import com.example.scannerkotlin.service.CatalogDocumentComingService
import com.example.scannerkotlin.utils.SessionManager
import com.example.scannerkotlin.utils.TsdLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    }

    override fun onResume() {
        super.onResume()
        loadDocuments()
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(SpaceItemDecoration(15))
        adapter = DocumentComingAdapter(mutableListOf())
        recyclerView.adapter = adapter
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadDocuments() {
        loadDocumentsJob?.cancel()
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyListTextView.visibility = View.GONE

        loadDocumentsJob = lifecycleScope.launch {
            var finalDocuments: List<Document> = emptyList()
            var localDrafts: List<ComingDocumentDraftRecord> = emptyList()
            var error: Throwable? = null

            try {
                localDrafts = ComingDraftRepository.getInstance().listRecoverableDrafts()
                val initialDocuments = withContext(Dispatchers.IO) {
                    service.getDocumentsSuspend()
                }
                finalDocuments = if (initialDocuments.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        service.fetchAndAssignNomenclature(initialDocuments)
                    }
                } else {
                    initialDocuments
                }
            } catch (e: Exception) {
                TsdLogger.error(category = "COMING", event = "load_documents_failed", throwable = e)
                error = e
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (isActive) {
                        // Merge offline/active local drafts so unfinished documents are always reachable even offline!
                        val serverIds = finalDocuments.mapNotNull { it.id }.toSet()
                        val syntheticFromLocalDrafts = localDrafts
                            .filter { it.documentId !in serverIds }
                            .map { draft ->
                                Document(
                                    commentary = draft.comment,
                                    createdBy = null,
                                    currency = "BYN",
                                    dateCreate = null,
                                    dateDocument = null,
                                    dateModify = null,
                                    dateStatus = null,
                                    docNumber = draft.documentId.toString(),
                                    docType = "A",
                                    id = draft.documentId,
                                    modifiedBy = null,
                                    responsibleId = null,
                                    siteId = null,
                                    status = "N",
                                    statusBy = null,
                                    title = draft.documentTitle ?: "Приход №${draft.documentId}",
                                    total = null,
                                    field620Values = draft.allowedNomenclatureIds
                                )
                            }

                        val mergedList = syntheticFromLocalDrafts + finalDocuments
                        if (mergedList.isEmpty()) {
                            emptyListTextView.text = if (error != null) "Ошибка загрузки" else "Список пуст"
                            emptyListTextView.visibility = View.VISIBLE
                            recyclerView.visibility = View.GONE
                            adapter.updateData(emptyList(), emptyList())
                        } else {
                            emptyListTextView.visibility = View.GONE
                            recyclerView.visibility = View.VISIBLE
                            adapter.updateData(mergedList, localDrafts)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        loadDocumentsJob?.cancel()
        super.onDestroy()
    }
}
