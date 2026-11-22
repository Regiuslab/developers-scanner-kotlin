package com.example.scannerkotlin.persistence

import com.example.scannerkotlin.model.ComingDocumentDraftRecord
import com.example.scannerkotlin.model.ComingItemStatus
import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.ComingSyncState
import com.example.scannerkotlin.model.DocumentElement
import com.example.scannerkotlin.model.OperationStepJournalEntry
import com.example.scannerkotlin.model.Product
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32

/**
 * Crash-safe atomic file-backed implementation of [DraftStorageEngine] using write-temp + fsync +
 * CRC32 checksum + backup fallback. Used as fallback/test engine on plain JVM environments where
 * Android SQLite native libraries are unavailable, testing real disk persistence and crash recovery.
 */
class FileBackedDraftStorageEngine(
    private val storageDirectory: File
) : DraftStorageEngine {

    private val gson = Gson()
    private val lock = Any()
    private var nextJournalStepId = 1L

    private data class Snapshot(
        val comingDrafts: MutableMap<Int, ComingDocumentDraftRecord> = mutableMapOf(),
        val movingDrafts: MutableMap<Int, List<DocumentElement>> = mutableMapOf(),
        val journal: MutableList<OperationStepJournalEntry> = mutableListOf(),
        val nextStepId: Long = 1L
    )

    private val comingDrafts = mutableMapOf<Int, ComingDocumentDraftRecord>()
    private val movingDrafts = mutableMapOf<Int, List<DocumentElement>>()
    private val journal = mutableListOf<OperationStepJournalEntry>()

    init {
        if (!storageDirectory.exists()) {
            storageDirectory.mkdirs()
        }
        reloadFromDiskLocked()
    }

    private val primaryFile: File get() = File(storageDirectory, "tsd_state_snapshot.json")
    private val backupFile: File get() = File(storageDirectory, "tsd_state_snapshot.bak")
    private val tempFile: File get() = File(storageDirectory, "tsd_state_snapshot.tmp")

    private fun reloadFromDiskLocked() {
        val loaded = readValidatedFile(primaryFile) ?: readValidatedFile(backupFile)
        comingDrafts.clear()
        movingDrafts.clear()
        journal.clear()
        if (loaded != null) {
            comingDrafts.putAll(loaded.comingDrafts)
            movingDrafts.putAll(loaded.movingDrafts)
            journal.addAll(loaded.journal)
            nextJournalStepId = loaded.nextStepId.coerceAtLeast((journal.maxOfOrNull { it.stepId } ?: 0L) + 1L)
        }
    }

    private fun readValidatedFile(file: File): Snapshot? {
        if (!file.exists()) return null
        return try {
            val raw = file.readText(StandardCharsets.UTF_8)
            val nl = raw.indexOf('\n')
            if (nl <= 0 || !raw.startsWith("CRC32=")) return null
            val expectedCrc = raw.substring("CRC32=".length, nl).toLongOrNull() ?: return null
            val body = raw.substring(nl + 1)
            val crc = CRC32().apply { update(body.toByteArray(StandardCharsets.UTF_8)) }.value
            if (crc != expectedCrc) return null
            gson.fromJson(body, Snapshot::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    private fun persistLocked() {
        if (!storageDirectory.exists()) {
            storageDirectory.mkdirs()
        }
        val snapshot = Snapshot(
            comingDrafts = comingDrafts.mapValues { (_, v) ->
                v.copy(items = v.items.map { it.detachedCopy(preserveLocalItemId = true) })
            }.toMutableMap(),
            movingDrafts = movingDrafts.mapValues { (_, v) -> v.map { it.copy() } }.toMutableMap(),
            journal = journal.toMutableList(),
            nextStepId = nextJournalStepId
        )
        val json = gson.toJson(snapshot)
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val crc = CRC32().apply { update(bytes) }.value
        val framed = "CRC32=$crc\n$json".toByteArray(StandardCharsets.UTF_8)

        FileOutputStream(tempFile, false).use { fos ->
            fos.write(framed)
            fos.fd.sync()
        }
        if (primaryFile.exists()) {
            if (backupFile.exists()) backupFile.delete()
            primaryFile.copyTo(backupFile, overwrite = true)
            primaryFile.delete()
        }
        if (!tempFile.renameTo(primaryFile)) {
            tempFile.copyTo(primaryFile, overwrite = true)
            tempFile.delete()
        }
    }

    override fun loadComingDraft(documentId: Int): ComingDocumentDraftRecord? = synchronized(lock) {
        comingDrafts[documentId]?.let { cloneRecord(it) }
    }

    override fun listActiveComingDrafts(): List<ComingDocumentDraftRecord> = synchronized(lock) {
        comingDrafts.values
            .filter { it.isRecoverableActiveDraft }
            .sortedByDescending { it.lastActiveAtMs }
            .map { cloneRecord(it) }
    }

    override fun saveFullComingDraft(record: ComingDocumentDraftRecord) = synchronized(lock) {
        comingDrafts[record.documentId] = cloneRecord(record)
        persistLocked()
    }

    override fun upsertComingItem(
        documentId: Int,
        documentTitle: String?,
        destinationStoreId: Int,
        allowedNomenclatureIds: List<Double>?,
        item: Product,
        insertAtTop: Boolean
    ): ComingDocumentDraftRecord = synchronized(lock) {
        val now = System.currentTimeMillis()
        val existing = comingDrafts[documentId]
        val operationId = existing?.operationId ?: UUID.randomUUID().toString()
        val items = existing?.items?.map { it.detachedCopy(preserveLocalItemId = true) }?.toMutableList() ?: mutableListOf()
        val clonedItem = item.detachedCopy(preserveLocalItemId = true).apply {
            updatedAtMs = now
            syncState = ComingSyncState.LOCAL_SAVED
        }
        val idx = items.indexOfFirst { it.localItemId == clonedItem.localItemId }
        if (idx >= 0) {
            items[idx] = clonedItem
        } else if (insertAtTop) {
            items.add(0, clonedItem)
        } else {
            items.add(clonedItem)
        }

        val updated = (existing ?: ComingDocumentDraftRecord(
            documentId = documentId,
            operationId = operationId,
            documentTitle = documentTitle,
            destinationStoreId = destinationStoreId,
            allowedNomenclatureIds = allowedNomenclatureIds,
            createdAtMs = now
        )).copy(
            documentTitle = documentTitle ?: existing?.documentTitle,
            allowedNomenclatureIds = allowedNomenclatureIds ?: existing?.allowedNomenclatureIds,
            state = if (existing?.state == ComingOperationState.COMMITTED) ComingOperationState.DRAFT else (existing?.state ?: ComingOperationState.DRAFT),
            syncState = ComingSyncState.LOCAL_SAVED,
            updatedAtMs = now,
            lastActiveAtMs = now,
            items = items
        )
        comingDrafts[documentId] = updated
        persistLocked()
        cloneRecord(updated)
    }

    override fun updateItemQuantityAtomically(
        documentId: Int,
        localItemId: String,
        newQuantity: Double?,
        delta: Double?
    ): Product? = synchronized(lock) {
        val existing = comingDrafts[documentId] ?: return null
        val now = System.currentTimeMillis()
        var updatedProduct: Product? = null
        val newItems = existing.items.map { item ->
            if (item.localItemId == localItemId) {
                val copy = item.detachedCopy(preserveLocalItemId = true)
                val computed = when {
                    delta != null -> ((copy.quantity ?: 0.0) + delta).coerceAtLeast(0.0)
                    else -> newQuantity
                }?.let { q ->
                    if (copy.measureId == 5) q.toInt().toDouble() else q
                }
                copy.quantity = computed
                copy.syncState = ComingSyncState.LOCAL_SAVED
                copy.validationError = null
                copy.updatedAtMs = now
                updatedProduct = copy.detachedCopy(preserveLocalItemId = true)
                copy
            } else {
                item.detachedCopy(preserveLocalItemId = true)
            }
        }
        if (updatedProduct == null) return null
        comingDrafts[documentId] = existing.copy(
            items = newItems,
            updatedAtMs = now,
            lastActiveAtMs = now
        )
        persistLocked()
        updatedProduct
    }

    override fun updateItemBarcodeAtomically(
        documentId: Int,
        localItemId: String,
        barcode: String?
    ): Product? = synchronized(lock) {
        val existing = comingDrafts[documentId] ?: return null
        val now = System.currentTimeMillis()
        var updatedProduct: Product? = null
        val newItems = existing.items.map { item ->
            if (item.localItemId == localItemId) {
                val copy = item.detachedCopy(preserveLocalItemId = true)
                copy.barcode = barcode?.trim()?.takeIf { it.isNotEmpty() }
                copy.syncState = ComingSyncState.LOCAL_SAVED
                copy.validationError = null
                copy.updatedAtMs = now
                updatedProduct = copy.detachedCopy(preserveLocalItemId = true)
                copy
            } else {
                item.detachedCopy(preserveLocalItemId = true)
            }
        }
        if (updatedProduct == null) return null
        comingDrafts[documentId] = existing.copy(
            items = newItems,
            updatedAtMs = now,
            lastActiveAtMs = now
        )
        persistLocked()
        updatedProduct
    }

    override fun updateItemRemoteIdentifiers(
        documentId: Int,
        localItemId: String,
        offerId: Int?,
        idInDocument: Int?,
        itemStatus: ComingItemStatus,
        syncState: ComingSyncState,
        validationError: String?
    ) = synchronized(lock) {
        val existing = comingDrafts[documentId] ?: return
        val now = System.currentTimeMillis()
        val newItems = existing.items.map { item ->
            if (item.localItemId == localItemId) {
                item.detachedCopy(preserveLocalItemId = true).apply {
                    if (offerId != null) this.offerId = offerId
                    if (idInDocument != null) this.idInDocument = idInDocument
                    this.itemStatus = itemStatus
                    this.syncState = syncState
                    this.validationError = validationError
                    this.updatedAtMs = now
                }
            } else {
                item.detachedCopy(preserveLocalItemId = true)
            }
        }
        comingDrafts[documentId] = existing.copy(
            items = newItems,
            updatedAtMs = now,
            lastActiveAtMs = now
        )
        persistLocked()
    }

    override fun removeComingItem(
        documentId: Int,
        localItemId: String,
        remoteElementIdToDelete: Int?
    ): ComingDocumentDraftRecord? = synchronized(lock) {
        val existing = comingDrafts[documentId] ?: return null
        val now = System.currentTimeMillis()
        val remaining = existing.items.filterNot { it.localItemId == localItemId }
            .map { it.detachedCopy(preserveLocalItemId = true) }
        val deletedIds = if (remoteElementIdToDelete != null) {
            (existing.deletedRemoteElementIds + remoteElementIdToDelete).distinct()
        } else {
            existing.deletedRemoteElementIds
        }
        val updated = existing.copy(
            items = remaining,
            deletedRemoteElementIds = deletedIds,
            updatedAtMs = now,
            lastActiveAtMs = now
        )
        comingDrafts[documentId] = updated
        persistLocked()
        cloneRecord(updated)
    }

    override fun updateDraftMetadata(
        documentId: Int,
        comment: String?,
        state: ComingOperationState?,
        syncState: ComingSyncState?,
        lastErrorMessage: String?,
        incrementAttempt: Boolean
    ): ComingDocumentDraftRecord? = synchronized(lock) {
        val existing = comingDrafts[documentId] ?: return null
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            comment = comment ?: existing.comment,
            state = state ?: existing.state,
            syncState = syncState ?: existing.syncState,
            lastErrorMessage = when {
                lastErrorMessage == null -> existing.lastErrorMessage
                lastErrorMessage.isEmpty() -> null
                else -> lastErrorMessage
            },
            conductAttemptCount = if (incrementAttempt) existing.conductAttemptCount + 1 else existing.conductAttemptCount,
            updatedAtMs = now,
            lastActiveAtMs = now
        )
        comingDrafts[documentId] = updated
        persistLocked()
        cloneRecord(updated)
    }

    override fun markComingDraftCommitted(documentId: Int, operationId: String) = synchronized(lock) {
        val existing = comingDrafts[documentId] ?: return
        val now = System.currentTimeMillis()
        val committedItems = existing.items.map {
            it.detachedCopy(preserveLocalItemId = true).apply {
                itemStatus = ComingItemStatus.COMMITTED
                syncState = ComingSyncState.REMOTE_COMMITTED
                validationError = null
                updatedAtMs = now
            }
        }
        comingDrafts[documentId] = existing.copy(
            state = ComingOperationState.COMMITTED,
            syncState = ComingSyncState.REMOTE_COMMITTED,
            lastErrorMessage = null,
            updatedAtMs = now,
            lastActiveAtMs = now,
            items = committedItems
        )
        persistLocked()
    }

    override fun deleteComingDraft(documentId: Int) = synchronized(lock) {
        comingDrafts.remove(documentId)
        persistLocked()
    }

    override fun transitionInterruptedOperationsOnStartup(): List<ComingDocumentDraftRecord> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val transitioned = mutableListOf<ComingDocumentDraftRecord>()
        for ((docId, draft) in comingDrafts.entries.toList()) {
            if (draft.state == ComingOperationState.PROCESSING || draft.state == ComingOperationState.VALIDATING) {
                val targetState = if (draft.state == ComingOperationState.PROCESSING) {
                    ComingOperationState.RECONCILIATION_REQUIRED
                } else {
                    ComingOperationState.DRAFT
                }
                val updated = draft.copy(
                    state = targetState,
                    lastErrorMessage = if (targetState == ComingOperationState.RECONCILIATION_REQUIRED) {
                        "Предыдущая операция проведения была прервана. Перед повторным проведением будет выполнена сверка с сервером Битрикс."
                    } else {
                        draft.lastErrorMessage
                    },
                    updatedAtMs = now
                )
                comingDrafts[docId] = updated
                journal.add(
                    OperationStepJournalEntry(
                        stepId = nextJournalStepId++,
                        operationId = draft.operationId,
                        documentId = docId,
                        stepType = "STARTUP_RECOVERY",
                        status = targetState.name,
                        details = "Process restarted while state=${draft.state.name}",
                        timestampMs = now
                    )
                )
                transitioned.add(cloneRecord(updated))
            }
        }
        if (transitioned.isNotEmpty()) {
            persistLocked()
        }
        transitioned
    }

    override fun appendStepJournal(entry: OperationStepJournalEntry) = synchronized(lock) {
        journal.add(entry.copy(stepId = nextJournalStepId++))
        persistLocked()
    }

    override fun getStepJournal(operationId: String): List<OperationStepJournalEntry> = synchronized(lock) {
        journal.filter { it.operationId == operationId }.toList()
    }

    override fun getStepJournalForDocument(documentId: Int): List<OperationStepJournalEntry> = synchronized(lock) {
        journal.filter { it.documentId == documentId }.toList()
    }

    override fun saveMovingDraft(documentId: Int, elements: List<DocumentElement>) = synchronized(lock) {
        movingDrafts[documentId] = elements.map { it.copy() }
        persistLocked()
    }

    override fun loadMovingDraft(documentId: Int): List<DocumentElement>? = synchronized(lock) {
        movingDrafts[documentId]?.map { it.copy() }
    }

    override fun deleteMovingDraft(documentId: Int) = synchronized(lock) {
        movingDrafts.remove(documentId)
        persistLocked()
    }

    private fun cloneRecord(record: ComingDocumentDraftRecord): ComingDocumentDraftRecord {
        return record.copy(
            items = record.items.map { it.detachedCopy(preserveLocalItemId = true) },
            allowedNomenclatureIds = record.allowedNomenclatureIds?.toList(),
            deletedRemoteElementIds = record.deletedRemoteElementIds.toList()
        )
    }
}
