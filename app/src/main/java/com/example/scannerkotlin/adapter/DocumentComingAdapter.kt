package com.example.scannerkotlin.adapter

import android.annotation.SuppressLint
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.scannerkotlin.R
import com.example.scannerkotlin.activities.ProductsDocumentComingActivity
import com.example.scannerkotlin.model.ComingDocumentDraftRecord
import com.example.scannerkotlin.model.Document
import com.example.scannerkotlin.utils.DocumentListNewestFirst
import java.time.format.DateTimeFormatter

class DocumentComingAdapter(
    private val documentList: MutableList<Document>
) : RecyclerView.Adapter<DocumentComingAdapter.DocumentViewHolder>() {

    private var draftsByDocId: Map<Int, ComingDocumentDraftRecord> = emptyMap()

    class DocumentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDocumentType: TextView = itemView.findViewById(R.id.tvDocumentType)
        val tvDocumentNumber: TextView = itemView.findViewById(R.id.tvDocumentNumber)
        val tvArrivalDate: TextView = itemView.findViewById(R.id.tvArrivalDate)
        val btnOpen: Button = itemView.findViewById(R.id.btnOpen)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.document_coming_item, parent, false)
        return DocumentViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
        val document = documentList[position]
        val localDraft = document.id?.let { draftsByDocId[it] }

        holder.tvDocumentNumber.text = document.title ?: "Приход №${document.id ?: "-"}"

        val dateText = document.dateCreate?.let {
            try {
                it.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            } catch (_: Throwable) {
                it.toString()
            }
        } ?: "локальный черновик"
        holder.tvArrivalDate.text = "Дата прихода: $dateText"

        if (localDraft != null && localDraft.isRecoverableActiveDraft) {
            holder.tvDocumentType.text = "Сохранён на ТСД: ${localDraft.itemCount} поз. • ${localDraft.state.toRussianLabel()}"
            holder.btnOpen.text = "Продолжить"
        } else {
            holder.tvDocumentType.text = holder.itemView.context.getString(R.string.documentComingType)
            holder.btnOpen.text = holder.itemView.context.getString(R.string.btnOpenDocument)
        }

        holder.btnOpen.setOnClickListener {
            val context = holder.itemView.context
            val intent = Intent(context, ProductsDocumentComingActivity::class.java)
            intent.putExtra("title", document.title ?: localDraft?.documentTitle)
            intent.putExtra("idDocument", document.id.toString())

            val nomenclature = document.field620Values ?: localDraft?.allowedNomenclatureIds
            nomenclature?.let { values ->
                intent.putExtra("field620Values", ArrayList(values))
            }
            context.startActivity(intent)
        }
    }

    override fun getItemCount() = documentList.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(
        newDocuments: List<Document>,
        activeDrafts: List<ComingDocumentDraftRecord> = emptyList()
    ) {
        draftsByDocId = activeDrafts.associateBy { it.documentId }
        documentList.clear()
        documentList.addAll(DocumentListNewestFirst.sort(newDocuments))
        notifyDataSetChanged()
    }
}
