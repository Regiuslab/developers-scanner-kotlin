package com.example.scannerkotlin.persistence

import android.content.Context
import com.example.scannerkotlin.model.ComingDocumentDraftRecord
import com.example.scannerkotlin.model.ComingItemStatus
import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.ComingSyncState
import com.example.scannerkotlin.model.OperationStepJournalEntry
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.SaveOutcome
import com.example.scannerkotlin.utils.TsdLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Coordinates atomic, sequential persistence of incoming document drafts, scans,
 * quantity updates, and step journals without blocking the main UI thread.
 */
class ComingDraftRepository(
    private val engine: DraftStorageEngine,
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) {
    private val writeMutex = Mutex()

    suspend fun loadOrInitializeDraft(
        documentId: Int,
        documentTitle: String?,
        allowedNomenclatureIds: List<Double>?,
        destinationStoreId: Int = 1
    ): ComingDocumentDraftRecord = withContext(ioDispatcher) {
        writeMutex.withLock {
            val existing = engine.loadComingDraft(documentId)
            if (existing != null && existing.state != ComingOperationState.COMMITTED) {
                val mergedTitle = documentTitle ?: existing.documentTitle
                val mergedAllowed = allowedNomenclatureIds ?: existing.allowedNomenclatureIds
                val updated = if (mergedTitle != existing.documentTitle || mergedAllowed != existing.allowedNomenclatureIds) {
                    val copy = existing.copy(
                        documentTitle = mergedTitle,
                        allowedNomenclatureIds = mergedAllowed,
                        lastActiveAtMs = System.currentTimeMillis()
                    )
                    engine.saveFullComingDraft(copy)
                    copy
                } else {
                    existing
                }
                TsdLogger.info(
                    category = "RECOVERY",
                    event = "draft_loaded",
                    operationId = updated.operationId,
                    documentId = documentId,
                    fields = mapOf(
                        "items" to updated.items.size,
                        "state" to updated.state.name
                    )
                )
                return@withLock updated
            }

            val fresh = ComingDocumentDraftRecord(
                documentId = documentId,
                operationId = UUID.randomUUID().toString(),
                documentTitle = documentTitle,
                destinationStoreId = destinationStoreId,
                allowedNomenclatureIds = allowedNomenclatureIds,
                state = ComingOperationState.DRAFT,
                syncState = ComingSyncState.LOCAL_SAVED
            )
            engine.saveFullComingDraft(fresh)
            TsdLogger.info(
                category = "DRAFT",
                event = "draft_initialized",
                operationId = fresh.operationId,
                documentId = documentId
            )
            fresh
        }
    }

    suspend fun listRecoverableDrafts(): List<ComingDocumentDraftRecord> = withContext(ioDispatcher) {
        writeMutex.withLock {
            engine.listActiveComingDrafts()
        }
    }

    suspend fun runStartupRecovery(): List<ComingDocumentDraftRecord> = withContext(ioDispatcher) {
        writeMutex.withLock {
            val transitioned = engine.transitionInterruptedOperationsOnStartup()
            val active = engine.listActiveComingDrafts()
            if (active.isNotEmpty() || transitioned.isNotEmpty()) {
                TsdLogger.info(
                    category = "RECOVERY",
                    event = "startup_recovery_completed",
                    fields = mapOf(
                        "activeDrafts" to active.size,
                        "interruptedOperations" to transitioned.size
                    )
                )
            }
            active
        }
    }

    suspend fun saveAddedOrUpdatedItem(
        documentId: Int,
        documentTitle: String?,
        destinationStoreId: Int = 1,
        allowedNomenclatureIds: List<Double>? = null,
        item: Product,
        insertAtTop: Boolean = true
    ): SaveOutcome = withContext(ioDispatcher) {
        writeMutex.withLock {
            try {
                val summary = engine.upsertComingItemFast(
                    documentId = documentId,
                    documentTitle = documentTitle,
                    destinationStoreId = destinationStoreId,
                    allowedNomenclatureIds = allowedNomenclatureIds,
                    item = item,
                    insertAtTop = insertAtTop
                )
                TsdLogger.info(
                    category = "SCAN_PERSIST",
                    event = "item_persisted",
                    operationId = summary.operationId,
                    documentId = documentId,
                    fields = mapOf(
                        "localItemId" to item.localItemId,
                        "productId" to item.id,
                        "barcode" to (item.barcode ?: ""),
                        "qty" to item.quantity,
                        "totalItems" to summary.totalItems
                    )
                )
                SaveOutcome.Saved(summary.updatedAtMs, summary.totalItems)
            } catch (t: Throwable) {
                TsdLogger.error(
                    category = "SCAN_PERSIST",
                    event = "item_persist_failed",
                    documentId = documentId,
                    fields = mapOf("localItemId" to item.localItemId),
                    throwable = t
                )
                SaveOutcome.Failed("Не удалось сохранить позицию в локальное хранилище", t)
            }
        }
    }

    suspend fun saveBarcodeScanAtomically(
        documentId: Int,
        localItemId: String,
        barcode: String
    ): Pair<SaveOutcome, Product?> = withContext(ioDispatcher) {
        writeMutex.withLock {
            try {
                val updatedProduct = engine.updateItemBarcodeAtomically(documentId, localItemId, barcode)
                    ?: return@withLock SaveOutcome.Failed("Позиция для сохранения штрихкода не найдена") to null
                val summary = engine.getDraftSummary(documentId)
                TsdLogger.info(
                    category = "SCAN_PERSIST",
                    event = "barcode_saved",
                    operationId = summary?.operationId,
                    documentId = documentId,
                    fields = mapOf(
                        "localItemId" to localItemId,
                        "barcode" to barcode,
                        "totalItems" to (summary?.totalItems ?: 0)
                    )
                )
                SaveOutcome.Saved(updatedProduct.updatedAtMs, summary?.totalItems ?: 1) to updatedProduct
            } catch (t: Throwable) {
                TsdLogger.error(
                    category = "SCAN_PERSIST",
                    event = "barcode_save_failed",
                    documentId = documentId,
                    fields = mapOf("localItemId" to localItemId),
                    throwable = t
                )
                SaveOutcome.Failed("Ошибка записи штрихкода на диск", t) to null
            }
        }
    }

    suspend fun updateQuantityAtomically(
        documentId: Int,
        localItemId: String,
        newQuantity: Double? = null,
        delta: Double? = null
    ): Pair<SaveOutcome, Product?> = withContext(ioDispatcher) {
        writeMutex.withLock {
            try {
                val updatedProduct = engine.updateItemQuantityAtomically(
                    documentId = documentId,
                    localItemId = localItemId,
                    newQuantity = newQuantity,
                    delta = delta
                ) ?: return@withLock SaveOutcome.Failed("Позиция не найдена при обновлении количества") to null
                val summary = engine.getDraftSummary(documentId)
                TsdLogger.info(
                    category = "SCAN_PERSIST",
                    event = "quantity_updated_atomically",
                    operationId = summary?.operationId,
                    documentId = documentId,
                    fields = mapOf(
                        "localItemId" to localItemId,
                        "qty" to updatedProduct.quantity,
                        "delta" to delta
                    )
                )
                SaveOutcome.Saved(updatedProduct.updatedAtMs, summary?.totalItems ?: 1) to updatedProduct
            } catch (t: Throwable) {
                TsdLogger.error(
                    category = "SCAN_PERSIST",
                    event = "quantity_update_failed",
                    documentId = documentId,
                    fields = mapOf("localItemId" to localItemId),
                    throwable = t
                )
                SaveOutcome.Failed("Ошибка атомарного сохранения количества", t) to null
            }
        }
    }

    suspend fun saveSnapshot(
        documentId: Int,
        documentTitle: String?,
        destinationStoreId: Int,
        allowedNomenclatureIds: List<Double>?,
        products: List<Product>,
        comment: String?,
        deletedRemoteIds: List<Int> = emptyList()
    ): SaveOutcome = withContext(ioDispatcher) {
        writeMutex.withLock {
            try {
                val now = System.currentTimeMillis()
                val existing = engine.loadComingDraft(documentId)
                val previousByLocalId = existing?.items?.associateBy { it.localItemId }.orEmpty()
                val mergedItems = products.map { incoming ->
                    mergeIncomingWithPersistedRemoteState(incoming, previousByLocalId[incoming.localItemId])
                }
                val record = ComingDocumentDraftRecord(
                    documentId = documentId,
                    operationId = existing?.operationId ?: UUID.randomUUID().toString(),
                    documentTitle = documentTitle ?: existing?.documentTitle,
                    destinationStoreId = destinationStoreId,
                    destinationStoreTitle = existing?.destinationStoreTitle,
                    comment = comment,
                    allowedNomenclatureIds = allowedNomenclatureIds ?: existing?.allowedNomenclatureIds,
                    state = existing?.state?.takeIf { it != ComingOperationState.COMMITTED } ?: ComingOperationState.DRAFT,
                    syncState = ComingSyncState.LOCAL_SAVED,
                    lastErrorMessage = existing?.lastErrorMessage,
                    conductAttemptCount = existing?.conductAttemptCount ?: 0,
                    createdAtMs = existing?.createdAtMs ?: now,
                    updatedAtMs = now,
                    lastActiveAtMs = now,
                    items = mergedItems,
                    deletedRemoteElementIds = (existing?.deletedRemoteElementIds.orEmpty() + deletedRemoteIds).distinct()
                )
                engine.saveFullComingDraft(record)
                SaveOutcome.Saved(now, products.size)
            } catch (t: Throwable) {
                TsdLogger.error(
                    category = "SCAN_PERSIST",
                    event = "snapshot_save_failed",
                    documentId = documentId,
                    throwable = t
                )
                SaveOutcome.Failed("Ошибка сохранения черновика", t)
            }
        }
    }

    suspend fun removeItem(
        documentId: Int,
        localItemId: String,
        remoteElementIdToDelete: Int?
    ): SaveOutcome = withContext(ioDispatcher) {
        writeMutex.withLock {
            try {
                val updated = engine.removeComingItem(documentId, localItemId, remoteElementIdToDelete)
                SaveOutcome.Saved(updated?.updatedAtMs ?: System.currentTimeMillis(), updated?.items?.size ?: 0)
            } catch (t: Throwable) {
                SaveOutcome.Failed("Ошибка удаления позиции", t)
            }
        }
    }

    suspend fun transitionState(
        documentId: Int,
        newState: ComingOperationState,
        syncState: ComingSyncState? = null,
        errorMessage: String? = null,
        incrementAttempt: Boolean = false
    ): ComingDocumentDraftRecord? = withContext(ioDispatcher) {
        writeMutex.withLock {
            val updated = engine.updateDraftMetadata(
                documentId = documentId,
                state = newState,
                syncState = syncState,
                lastErrorMessage = errorMessage ?: "",
                incrementAttempt = incrementAttempt
            )
            TsdLogger.info(
                category = "DOC_STATE",
                event = "state_transition",
                operationId = updated?.operationId,
                documentId = documentId,
                fields = mapOf(
                    "newState" to newState.name,
                    "attempt" to updated?.conductAttemptCount,
                    "err" to (errorMessage ?: "")
                )
            )
            updated
        }
    }

    suspend fun recordItemStepProgress(
        documentId: Int,
        operationId: String,
        localItemId: String,
        offerId: Int?,
        idInDocument: Int?,
        itemStatus: ComingItemStatus,
        syncState: ComingSyncState,
        stepType: String,
        stepStatus: String,
        remoteEntityId: Int? = offerId ?: idInDocument,
        details: String? = null,
        validationError: String? = null
    ) = withContext(ioDispatcher) {
        writeMutex.withLock {
            engine.recordItemAndJournalAtomically(
                documentId = documentId,
                localItemId = localItemId,
                offerId = offerId,
                idInDocument = idInDocument,
                itemStatus = itemStatus,
                syncState = syncState,
                validationError = validationError,
                journalEntry = OperationStepJournalEntry(
                    operationId = operationId,
                    documentId = documentId,
                    localItemId = localItemId,
                    stepType = stepType,
                    status = stepStatus,
                    remoteEntityId = remoteEntityId,
                    details = details
                )
            )
        }
    }

    suspend fun appendJournal(
        operationId: String,
        documentId: Int,
        stepType: String,
        status: String,
        localItemId: String? = null,
        remoteEntityId: Int? = null,
        details: String? = null
    ) = withContext(ioDispatcher) {
        writeMutex.withLock {
            engine.appendStepJournal(
                OperationStepJournalEntry(
                    operationId = operationId,
                    documentId = documentId,
                    localItemId = localItemId,
                    stepType = stepType,
                    status = status,
                    remoteEntityId = remoteEntityId,
                    details = details
                )
            )
        }
    }

    suspend fun markCommittedAndFinalize(documentId: Int, operationId: String) = withContext(ioDispatcher) {
        writeMutex.withLock {
            engine.markComingDraftCommitted(documentId, operationId)
            engine.appendStepJournal(
                OperationStepJournalEntry(
                    operationId = operationId,
                    documentId = documentId,
                    stepType = "FINALIZE_COMMIT",
                    status = "COMMITTED",
                    details = "Document verified conducted on server"
                )
            )
            TsdLogger.info(
                category = "DOC_STATE",
                event = "document_committed",
                operationId = operationId,
                documentId = documentId
            )
        }
    }

    suspend fun getDraft(documentId: Int): ComingDocumentDraftRecord? = withContext(ioDispatcher) {
        writeMutex.withLock {
            engine.loadComingDraft(documentId)
        }
    }

    suspend fun getJournal(operationId: String): List<OperationStepJournalEntry> = withContext(ioDispatcher) {
        writeMutex.withLock {
            engine.getStepJournal(operationId)
        }
    }

    /**
     * Incoming UI state may omit offerId/idInDocument after a crash/retry. Never wipe
     * already persisted remote identifiers or regress item progress.
     */
    private fun mergeIncomingWithPersistedRemoteState(incoming: Product, previous: Product?): Product {
        val copy = incoming.detachedCopy(preserveLocalItemId = true)
        if (previous == null) return copy
        if (copy.offerId == null) copy.offerId = previous.offerId
        if (copy.idInDocument == null) copy.idInDocument = previous.idInDocument
        if (itemStatusRank(previous.itemStatus) > itemStatusRank(copy.itemStatus)) {
            copy.itemStatus = previous.itemStatus
            copy.syncState = previous.syncState
        }
        return copy
    }

    private fun itemStatusRank(status: ComingItemStatus): Int = when (status) {
        ComingItemStatus.PENDING -> 0
        ComingItemStatus.VALIDATED -> 1
        ComingItemStatus.VARIATION_RESOLVED -> 2
        ComingItemStatus.BARCODE_ASSIGNED -> 3
        ComingItemStatus.ELEMENT_ADDED -> 4
        ComingItemStatus.METADATA_UPDATED -> 5
        ComingItemStatus.COMMITTED -> 6
        ComingItemStatus.ERROR -> 0
    }

    companion object {
        @Volatile
        private var defaultEngine: DraftStorageEngine? = null

        @Volatile
        private var defaultRepository: ComingDraftRepository? = null

        fun init(context: Context) {
            if (defaultEngine == null) {
                synchronized(this) {
                    if (defaultEngine == null) {
                        val sqlite = SqliteDraftStorageEngine(context.applicationContext)
                        defaultEngine = sqlite
                        defaultRepository = ComingDraftRepository(sqlite)
                    }
                }
            }
        }

        fun installEngineForTest(engine: DraftStorageEngine) {
            synchronized(this) {
                defaultEngine = engine
                defaultRepository = ComingDraftRepository(engine)
            }
        }

        fun engine(): DraftStorageEngine {
            defaultEngine?.let { return it }
            synchronized(this) {
                defaultEngine?.let { return it }
                val fallbackDir = File(System.getProperty("java.io.tmpdir"), "tsd_fallback_store")
                val fallback = FileBackedDraftStorageEngine(fallbackDir)
                defaultEngine = fallback
                defaultRepository = ComingDraftRepository(fallback)
                return fallback
            }
        }

        fun getInstance(): ComingDraftRepository {
            defaultRepository?.let { return it }
            engine()
            return defaultRepository!!
        }
    }
}
