package com.example.scannerkotlin.persistence

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.scannerkotlin.model.ComingDocumentDraftRecord
import com.example.scannerkotlin.model.ComingItemStatus
import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.ComingSyncState
import com.example.scannerkotlin.model.DocumentElement
import com.example.scannerkotlin.model.OperationStepJournalEntry
import com.example.scannerkotlin.model.Product
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Transactional Android SQLite storage with Write-Ahead Logging (WAL) for TSD operational drafts.
 * Stored in internal database directory (`tsd_operational_state.db`) — survives crashes,
 * `am force-stop`, LMK process eviction, and device reboots, and is isolated from cache cleanup.
 */
class SqliteDraftStorageEngine(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION), DraftStorageEngine {

    private val gson = Gson()
    private val lock = Any()

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSchemaV1(db)
        migrateV1ToV2(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            migrateV1ToV2(db)
        }
    }

    companion object {
        const val DATABASE_NAME = "tsd_operational_state.db"
        const val DATABASE_VERSION = 2

        private const val TABLE_COMING_DRAFTS = "coming_document_drafts"
        private const val TABLE_COMING_ITEMS = "coming_draft_items"
        private const val TABLE_STEP_JOURNAL = "operation_step_journal"
        private const val TABLE_MOVING_DRAFTS = "moving_document_drafts"

        fun createSchemaV1(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_COMING_DRAFTS (
                    document_id INTEGER PRIMARY KEY,
                    operation_id TEXT NOT NULL,
                    document_title TEXT,
                    destination_store_id INTEGER NOT NULL DEFAULT 1,
                    comment TEXT,
                    allowed_nomenclature_json TEXT,
                    deleted_remote_elements_json TEXT,
                    state TEXT NOT NULL,
                    sync_state TEXT NOT NULL,
                    last_error_message TEXT,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    last_active_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_COMING_ITEMS (
                    local_item_id TEXT PRIMARY KEY,
                    document_id INTEGER NOT NULL,
                    operation_id TEXT NOT NULL,
                    position_index INTEGER NOT NULL,
                    product_id INTEGER NOT NULL,
                    iblock_id INTEGER NOT NULL,
                    catalog_product_type INTEGER,
                    offer_id INTEGER,
                    id_in_document INTEGER,
                    product_name TEXT,
                    measure_id INTEGER,
                    measure_symbol TEXT,
                    quantity REAL,
                    barcode TEXT,
                    diameter REAL,
                    weight REAL,
                    height REAL,
                    parent_id INTEGER,
                    batch_number TEXT,
                    is_old_product INTEGER NOT NULL DEFAULT 0,
                    destination_store_id INTEGER NOT NULL DEFAULT 1,
                    item_status TEXT NOT NULL,
                    sync_state TEXT NOT NULL,
                    validation_error TEXT,
                    updated_at_ms INTEGER NOT NULL,
                    FOREIGN KEY(document_id) REFERENCES $TABLE_COMING_DRAFTS(document_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_STEP_JOURNAL (
                    step_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    operation_id TEXT NOT NULL,
                    document_id INTEGER NOT NULL,
                    local_item_id TEXT,
                    step_type TEXT NOT NULL,
                    status TEXT NOT NULL,
                    remote_entity_id INTEGER,
                    details TEXT,
                    timestamp_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_MOVING_DRAFTS (
                    document_id INTEGER PRIMARY KEY,
                    elements_json TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        fun migrateV1ToV2(db: SQLiteDatabase) {
            if (!hasColumn(db, TABLE_COMING_DRAFTS, "destination_store_title")) {
                db.execSQL("ALTER TABLE $TABLE_COMING_DRAFTS ADD COLUMN destination_store_title TEXT")
            }
            if (!hasColumn(db, TABLE_COMING_DRAFTS, "conduct_attempt_count")) {
                db.execSQL("ALTER TABLE $TABLE_COMING_DRAFTS ADD COLUMN conduct_attempt_count INTEGER NOT NULL DEFAULT 0")
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_coming_items_doc_pos ON $TABLE_COMING_ITEMS(document_id, position_index)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_coming_items_barcode ON $TABLE_COMING_ITEMS(document_id, barcode)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_step_journal_op ON $TABLE_STEP_JOURNAL(operation_id, step_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_step_journal_doc ON $TABLE_STEP_JOURNAL(document_id, step_id)")
        }

        private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0 && cursor.getString(nameIdx).equals(column, ignoreCase = true)) {
                        return true
                    }
                }
            }
            return false
        }
    }

    override fun loadComingDraft(documentId: Int): ComingDocumentDraftRecord? = synchronized(lock) {
        val db = readableDatabase
        loadComingDraftInternal(db, documentId)
    }

    override fun listActiveComingDrafts(): List<ComingDocumentDraftRecord> = synchronized(lock) {
        val db = readableDatabase
        val docIds = mutableListOf<Int>()
        db.rawQuery(
            "SELECT document_id FROM $TABLE_COMING_DRAFTS WHERE state != ? ORDER BY last_active_at_ms DESC",
            arrayOf(ComingOperationState.COMMITTED.name)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                docIds.add(cursor.getInt(0))
            }
        }
        docIds.mapNotNull { loadComingDraftInternal(db, it) }
            .filter { it.isRecoverableActiveDraft }
    }

    override fun saveFullComingDraft(record: ComingDocumentDraftRecord) = synchronized(lock) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            upsertHeaderInternal(db, record)
            db.delete(TABLE_COMING_ITEMS, "document_id = ?", arrayOf(record.documentId.toString()))
            record.items.forEachIndexed { index, product ->
                insertItemInternal(db, record.documentId, record.operationId, index, product)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun getDraftSummary(documentId: Int): DraftWriteSummary? = synchronized(lock) {
        val db = readableDatabase
        queryDraftSummaryInternal(db, documentId)
    }

    override fun upsertComingItemFast(
        documentId: Int,
        documentTitle: String?,
        destinationStoreId: Int,
        allowedNomenclatureIds: List<Double>?,
        item: Product,
        insertAtTop: Boolean
    ): DraftWriteSummary = synchronized(lock) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransactionNonExclusive()
        try {
            val operationId = upsertSingleItemO1Internal(
                db = db,
                documentId = documentId,
                documentTitle = documentTitle,
                destinationStoreId = destinationStoreId,
                allowedNomenclatureIds = allowedNomenclatureIds,
                item = item,
                insertAtTop = insertAtTop,
                now = now
            )
            val totalItems = countItemsInternal(db, documentId)
            db.setTransactionSuccessful()
            DraftWriteSummary(operationId = operationId, updatedAtMs = now, totalItems = totalItems)
        } finally {
            db.endTransaction()
        }
    }

    override fun upsertComingItem(
        documentId: Int,
        documentTitle: String?,
        destinationStoreId: Int,
        allowedNomenclatureIds: List<Double>?,
        item: Product,
        insertAtTop: Boolean
    ): ComingDocumentDraftRecord = synchronized(lock) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransactionNonExclusive()
        try {
            upsertSingleItemO1Internal(
                db = db,
                documentId = documentId,
                documentTitle = documentTitle,
                destinationStoreId = destinationStoreId,
                allowedNomenclatureIds = allowedNomenclatureIds,
                item = item,
                insertAtTop = insertAtTop,
                now = now
            )
            db.setTransactionSuccessful()
            loadComingDraftInternal(db, documentId)!!
        } finally {
            db.endTransaction()
        }
    }

    private fun upsertSingleItemO1Internal(
        db: SQLiteDatabase,
        documentId: Int,
        documentTitle: String?,
        destinationStoreId: Int,
        allowedNomenclatureIds: List<Double>?,
        item: Product,
        insertAtTop: Boolean,
        now: Long
    ): String {
        var existingOpId: String? = null
        var existingState: String? = null
        db.rawQuery(
            "SELECT operation_id, state FROM $TABLE_COMING_DRAFTS WHERE document_id = ?",
            arrayOf(documentId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                existingOpId = c.getString(0)
                existingState = c.getStringOrNull(1)
            }
        }

        val operationId = existingOpId ?: UUID.randomUUID().toString()
        if (existingOpId == null) {
            val freshHeader = ComingDocumentDraftRecord(
                documentId = documentId,
                operationId = operationId,
                documentTitle = documentTitle,
                destinationStoreId = destinationStoreId,
                allowedNomenclatureIds = allowedNomenclatureIds,
                state = ComingOperationState.DRAFT,
                syncState = ComingSyncState.LOCAL_SAVED,
                createdAtMs = now,
                updatedAtMs = now,
                lastActiveAtMs = now
            )
            upsertHeaderInternal(db, freshHeader)
        } else {
            val cv = ContentValues().apply {
                if (!documentTitle.isNullOrBlank()) put("document_title", documentTitle)
                if (allowedNomenclatureIds != null) {
                    put("allowed_nomenclature_json", gson.toJson(allowedNomenclatureIds))
                }
                if (existingState == ComingOperationState.COMMITTED.name) {
                    put("state", ComingOperationState.DRAFT.name)
                }
                put("sync_state", ComingSyncState.LOCAL_SAVED.name)
                put("updated_at_ms", now)
                put("last_active_at_ms", now)
            }
            db.update(TABLE_COMING_DRAFTS, cv, "document_id = ?", arrayOf(documentId.toString()))
        }

        var targetPos: Int? = null
        db.rawQuery(
            "SELECT position_index FROM $TABLE_COMING_ITEMS WHERE document_id = ? AND local_item_id = ?",
            arrayOf(documentId.toString(), item.localItemId)
        ).use { c ->
            if (c.moveToFirst()) {
                targetPos = c.getInt(0)
            }
        }

        val positionIndex = targetPos ?: if (insertAtTop) {
            db.rawQuery(
                "SELECT COALESCE(MIN(position_index), 1) - 1 FROM $TABLE_COMING_ITEMS WHERE document_id = ?",
                arrayOf(documentId.toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } else {
            db.rawQuery(
                "SELECT COALESCE(MAX(position_index), -1) + 1 FROM $TABLE_COMING_ITEMS WHERE document_id = ?",
                arrayOf(documentId.toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }

        item.updatedAtMs = now
        item.syncState = ComingSyncState.LOCAL_SAVED
        insertItemInternal(db, documentId, operationId, positionIndex, item)
        return operationId
    }

    private fun countItemsInternal(db: SQLiteDatabase, documentId: Int): Int {
        db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_COMING_ITEMS WHERE document_id = ?",
            arrayOf(documentId.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun queryDraftSummaryInternal(db: SQLiteDatabase, documentId: Int): DraftWriteSummary? {
        val opId: String
        val updatedAt: Long
        db.rawQuery(
            "SELECT operation_id, updated_at_ms FROM $TABLE_COMING_DRAFTS WHERE document_id = ?",
            arrayOf(documentId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            opId = c.getString(0)
            updatedAt = c.getLong(1)
        }
        val count = countItemsInternal(db, documentId)
        return DraftWriteSummary(opId, updatedAt, count)
    }

    override fun updateItemQuantityAtomically(
        documentId: Int,
        localItemId: String,
        newQuantity: Double?,
        delta: Double?
    ): Product? = synchronized(lock) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransactionNonExclusive()
        try {
            var currentQty: Double? = null
            var measureId: Int? = null
            var found = false
            db.rawQuery(
                "SELECT quantity, measure_id FROM $TABLE_COMING_ITEMS WHERE document_id = ? AND local_item_id = ?",
                arrayOf(documentId.toString(), localItemId)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    found = true
                    currentQty = if (cursor.isNull(0)) null else cursor.getDouble(0)
                    measureId = if (cursor.isNull(1)) null else cursor.getInt(1)
                }
            }
            if (!found) return null

            val computed = when {
                delta != null -> ((currentQty ?: 0.0) + delta).coerceAtLeast(0.0)
                else -> newQuantity
            }?.let { q ->
                if (measureId == 5) q.toInt().toDouble() else q
            }

            val cv = ContentValues().apply {
                if (computed == null) putNull("quantity") else put("quantity", computed)
                put("sync_state", ComingSyncState.LOCAL_SAVED.name)
                putNull("validation_error")
                put("updated_at_ms", now)
            }
            db.update(
                TABLE_COMING_ITEMS,
                cv,
                "document_id = ? AND local_item_id = ?",
                arrayOf(documentId.toString(), localItemId)
            )
            touchDraftTimestampsInternal(db, documentId, now)
            db.setTransactionSuccessful()
            loadSingleItemInternal(db, documentId, localItemId)
        } finally {
            db.endTransaction()
        }
    }

    override fun updateItemBarcodeAtomically(
        documentId: Int,
        localItemId: String,
        barcode: String?
    ): Product? = synchronized(lock) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransactionNonExclusive()
        try {
            val cv = ContentValues().apply {
                if (barcode.isNullOrBlank()) putNull("barcode") else put("barcode", barcode.trim())
                put("sync_state", ComingSyncState.LOCAL_SAVED.name)
                putNull("validation_error")
                put("updated_at_ms", now)
            }
            val updated = db.update(
                TABLE_COMING_ITEMS,
                cv,
                "document_id = ? AND local_item_id = ?",
                arrayOf(documentId.toString(), localItemId)
            )
            if (updated <= 0) return null
            touchDraftTimestampsInternal(db, documentId, now)
            db.setTransactionSuccessful()
            loadSingleItemInternal(db, documentId, localItemId)
        } finally {
            db.endTransaction()
        }
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
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransactionNonExclusive()
        try {
            val cv = ContentValues().apply {
                if (offerId != null) put("offer_id", offerId)
                if (idInDocument != null) put("id_in_document", idInDocument)
                put("item_status", itemStatus.name)
                put("sync_state", syncState.name)
                if (validationError == null) putNull("validation_error") else put("validation_error", validationError)
                put("updated_at_ms", now)
            }
            db.update(
                TABLE_COMING_ITEMS,
                cv,
                "document_id = ? AND local_item_id = ?",
                arrayOf(documentId.toString(), localItemId)
            )
            touchDraftTimestampsInternal(db, documentId, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun removeComingItem(
        documentId: Int,
        localItemId: String,
        remoteElementIdToDelete: Int?
    ): ComingDocumentDraftRecord? = synchronized(lock) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransactionNonExclusive()
        try {
            val existing = loadComingDraftInternal(db, documentId) ?: return null
            db.delete(
                TABLE_COMING_ITEMS,
                "document_id = ? AND local_item_id = ?",
                arrayOf(documentId.toString(), localItemId)
            )
            val remaining = loadItemsInternal(db, documentId)
            remaining.forEachIndexed { idx, prod ->
                val cv = ContentValues().apply { put("position_index", idx) }
                db.update(
                    TABLE_COMING_ITEMS,
                    cv,
                    "document_id = ? AND local_item_id = ?",
                    arrayOf(documentId.toString(), prod.localItemId)
                )
            }
            val updatedDeletedIds = if (remoteElementIdToDelete != null) {
                (existing.deletedRemoteElementIds + remoteElementIdToDelete).distinct()
            } else {
                existing.deletedRemoteElementIds
            }
            val headerValues = ContentValues().apply {
                put("deleted_remote_elements_json", gson.toJson(updatedDeletedIds))
                put("updated_at_ms", now)
                put("last_active_at_ms", now)
            }
            db.update(TABLE_COMING_DRAFTS, headerValues, "document_id = ?", arrayOf(documentId.toString()))
            db.setTransactionSuccessful()
            loadComingDraftInternal(db, documentId)
        } finally {
            db.endTransaction()
        }
    }

    override fun updateDraftMetadata(
        documentId: Int,
        comment: String?,
        state: ComingOperationState?,
        syncState: ComingSyncState?,
        lastErrorMessage: String?,
        incrementAttempt: Boolean
    ): ComingDocumentDraftRecord? = synchronized(lock) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransactionNonExclusive()
        try {
            val existing = loadComingDraftInternal(db, documentId) ?: return null
            val cv = ContentValues().apply {
                if (comment != null) put("comment", comment)
                if (state != null) put("state", state.name)
                if (syncState != null) put("sync_state", syncState.name)
                if (lastErrorMessage != null) {
                    if (lastErrorMessage.isEmpty()) putNull("last_error_message") else put("last_error_message", lastErrorMessage)
                }
                if (incrementAttempt) {
                    put("conduct_attempt_count", existing.conductAttemptCount + 1)
                }
                put("updated_at_ms", now)
                put("last_active_at_ms", now)
            }
            db.update(TABLE_COMING_DRAFTS, cv, "document_id = ?", arrayOf(documentId.toString()))
            db.setTransactionSuccessful()
            loadComingDraftInternal(db, documentId)
        } finally {
            db.endTransaction()
        }
    }

    override fun markComingDraftCommitted(documentId: Int, operationId: String) = synchronized(lock) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransactionNonExclusive()
        try {
            val headerCv = ContentValues().apply {
                put("state", ComingOperationState.COMMITTED.name)
                put("sync_state", ComingSyncState.REMOTE_COMMITTED.name)
                putNull("last_error_message")
                put("updated_at_ms", now)
                put("last_active_at_ms", now)
            }
            db.update(TABLE_COMING_DRAFTS, headerCv, "document_id = ?", arrayOf(documentId.toString()))
            val itemCv = ContentValues().apply {
                put("item_status", ComingItemStatus.COMMITTED.name)
                put("sync_state", ComingSyncState.REMOTE_COMMITTED.name)
                putNull("validation_error")
                put("updated_at_ms", now)
            }
            db.update(TABLE_COMING_ITEMS, itemCv, "document_id = ?", arrayOf(documentId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun deleteComingDraft(documentId: Int) = synchronized(lock) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.delete(TABLE_COMING_ITEMS, "document_id = ?", arrayOf(documentId.toString()))
            db.delete(TABLE_COMING_DRAFTS, "document_id = ?", arrayOf(documentId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun transitionInterruptedOperationsOnStartup(): List<ComingDocumentDraftRecord> = synchronized(lock) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val transitionedIds = mutableListOf<Int>()
        db.beginTransactionNonExclusive()
        try {
            db.rawQuery(
                "SELECT document_id, operation_id FROM $TABLE_COMING_DRAFTS WHERE state IN (?, ?)",
                arrayOf(ComingOperationState.PROCESSING.name, ComingOperationState.VALIDATING.name)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getInt(0)
                    val opId = cursor.getString(1)
                    val stateRow = loadDraftStateName(db, docId)
                    val targetState = if (stateRow == ComingOperationState.PROCESSING.name) {
                        ComingOperationState.RECONCILIATION_REQUIRED
                    } else {
                        ComingOperationState.DRAFT
                    }
                    val cv = ContentValues().apply {
                        put("state", targetState.name)
                        if (targetState == ComingOperationState.RECONCILIATION_REQUIRED) {
                            put(
                                "last_error_message",
                                "Предыдущая операция проведения была прервана. Перед повторным проведением будет выполнена сверка с сервером Битрикс."
                            )
                        }
                        put("updated_at_ms", now)
                    }
                    db.update(TABLE_COMING_DRAFTS, cv, "document_id = ?", arrayOf(docId.toString()))
                    insertStepJournalInternal(
                        db,
                        OperationStepJournalEntry(
                            operationId = opId,
                            documentId = docId,
                            stepType = "STARTUP_RECOVERY",
                            status = targetState.name,
                            details = "Process restarted while state=$stateRow",
                            timestampMs = now
                        )
                    )
                    transitionedIds.add(docId)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        transitionedIds.mapNotNull { loadComingDraftInternal(db, it) }
    }

    override fun appendStepJournal(entry: OperationStepJournalEntry) = synchronized(lock) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            insertStepJournalInternal(db, entry)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun getStepJournal(operationId: String): List<OperationStepJournalEntry> = synchronized(lock) {
        val db = readableDatabase
        val entries = mutableListOf<OperationStepJournalEntry>()
        db.rawQuery(
            """
            SELECT step_id, operation_id, document_id, local_item_id, step_type, status, remote_entity_id, details, timestamp_ms
            FROM $TABLE_STEP_JOURNAL
            WHERE operation_id = ?
            ORDER BY step_id ASC
            """.trimIndent(),
            arrayOf(operationId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(readJournalEntry(cursor))
            }
        }
        entries
    }

    override fun getStepJournalForDocument(documentId: Int): List<OperationStepJournalEntry> = synchronized(lock) {
        val db = readableDatabase
        val entries = mutableListOf<OperationStepJournalEntry>()
        db.rawQuery(
            """
            SELECT step_id, operation_id, document_id, local_item_id, step_type, status, remote_entity_id, details, timestamp_ms
            FROM $TABLE_STEP_JOURNAL
            WHERE document_id = ?
            ORDER BY step_id ASC
            """.trimIndent(),
            arrayOf(documentId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(readJournalEntry(cursor))
            }
        }
        entries
    }

    override fun saveMovingDraft(documentId: Int, elements: List<DocumentElement>) = synchronized(lock) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("document_id", documentId)
            put("elements_json", gson.toJson(elements))
            put("updated_at_ms", now)
        }
        db.insertWithOnConflict(TABLE_MOVING_DRAFTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override fun loadMovingDraft(documentId: Int): List<DocumentElement>? {
        synchronized(lock) {
            val db = readableDatabase
            return db.rawQuery(
                "SELECT elements_json FROM $TABLE_MOVING_DRAFTS WHERE document_id = ?",
                arrayOf(documentId.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    val json = cursor.getString(0)
                    if (json == null) {
                        null
                    } else {
                        try {
                            val type = object : TypeToken<List<DocumentElement>>() {}.type
                            gson.fromJson<List<DocumentElement>>(json, type)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
        }
    }

    override fun deleteMovingDraft(documentId: Int) = synchronized(lock) {
        val db = writableDatabase
        db.delete(TABLE_MOVING_DRAFTS, "document_id = ?", arrayOf(documentId.toString()))
        Unit
    }

    private fun loadDraftStateName(db: SQLiteDatabase, documentId: Int): String? {
        db.rawQuery(
            "SELECT state FROM $TABLE_COMING_DRAFTS WHERE document_id = ?",
            arrayOf(documentId.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun touchDraftTimestampsInternal(db: SQLiteDatabase, documentId: Int, now: Long) {
        val cv = ContentValues().apply {
            put("updated_at_ms", now)
            put("last_active_at_ms", now)
        }
        db.update(TABLE_COMING_DRAFTS, cv, "document_id = ?", arrayOf(documentId.toString()))
    }

    private fun upsertHeaderInternal(db: SQLiteDatabase, record: ComingDocumentDraftRecord) {
        val cv = ContentValues().apply {
            put("document_id", record.documentId)
            put("operation_id", record.operationId)
            put("document_title", record.documentTitle)
            put("destination_store_id", record.destinationStoreId)
            put("destination_store_title", record.destinationStoreTitle)
            put("comment", record.comment)
            put(
                "allowed_nomenclature_json",
                record.allowedNomenclatureIds?.let { gson.toJson(it) }
            )
            put(
                "deleted_remote_elements_json",
                gson.toJson(record.deletedRemoteElementIds)
            )
            put("state", record.state.name)
            put("sync_state", record.syncState.name)
            put("last_error_message", record.lastErrorMessage)
            put("conduct_attempt_count", record.conductAttemptCount)
            put("created_at_ms", record.createdAtMs)
            put("updated_at_ms", record.updatedAtMs)
            put("last_active_at_ms", record.lastActiveAtMs)
        }
        db.insertWithOnConflict(TABLE_COMING_DRAFTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertItemInternal(
        db: SQLiteDatabase,
        documentId: Int,
        operationId: String,
        positionIndex: Int,
        product: Product
    ) {
        val cv = ContentValues().apply {
            put("local_item_id", product.localItemId)
            put("document_id", documentId)
            put("operation_id", operationId)
            put("position_index", positionIndex)
            put("product_id", product.id)
            put("iblock_id", product.iblockId)
            if (product.catalogProductType == null) putNull("catalog_product_type") else put("catalog_product_type", product.catalogProductType)
            if (product.offerId == null) putNull("offer_id") else put("offer_id", product.offerId)
            if (product.idInDocument == null) putNull("id_in_document") else put("id_in_document", product.idInDocument)
            put("product_name", product.name)
            if (product.measureId == null) putNull("measure_id") else put("measure_id", product.measureId)
            put("measure_symbol", product.measureSymbol)
            if (product.quantity == null) putNull("quantity") else put("quantity", product.quantity)
            put("barcode", product.barcode)
            if (product.diameter == null) putNull("diameter") else put("diameter", product.diameter)
            if (product.weight == null) putNull("weight") else put("weight", product.weight)
            if (product.height == null) putNull("height") else put("height", product.height)
            if (product.parentId == null) putNull("parent_id") else put("parent_id", product.parentId)
            put("batch_number", product.batchNumber)
            put("is_old_product", if (product.isOldProduct) 1 else 0)
            put("destination_store_id", product.destinationStoreId)
            put("item_status", product.itemStatus.name)
            put("sync_state", product.syncState.name)
            put("validation_error", product.validationError)
            put("updated_at_ms", product.updatedAtMs)
        }
        db.insertWithOnConflict(TABLE_COMING_ITEMS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertStepJournalInternal(db: SQLiteDatabase, entry: OperationStepJournalEntry) {
        val cv = ContentValues().apply {
            put("operation_id", entry.operationId)
            put("document_id", entry.documentId)
            put("local_item_id", entry.localItemId)
            put("step_type", entry.stepType)
            put("status", entry.status)
            if (entry.remoteEntityId == null) putNull("remote_entity_id") else put("remote_entity_id", entry.remoteEntityId)
            put("details", entry.details)
            put("timestamp_ms", entry.timestampMs)
        }
        db.insert(TABLE_STEP_JOURNAL, null, cv)
    }

    private fun loadComingDraftInternal(db: SQLiteDatabase, documentId: Int): ComingDocumentDraftRecord? {
        db.rawQuery(
            """
            SELECT document_id, operation_id, document_title, destination_store_id, destination_store_title,
                   comment, allowed_nomenclature_json, deleted_remote_elements_json, state, sync_state,
                   last_error_message, conduct_attempt_count, created_at_ms, updated_at_ms, last_active_at_ms
            FROM $TABLE_COMING_DRAFTS
            WHERE document_id = ?
            """.trimIndent(),
            arrayOf(documentId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val allowedIds = cursor.getStringOrNull(6)?.let { json ->
                try {
                    val type = object : TypeToken<List<Double>>() {}.type
                    gson.fromJson<List<Double>>(json, type)
                } catch (_: Exception) {
                    null
                }
            }
            val deletedRemoteIds = cursor.getStringOrNull(7)?.let { json ->
                try {
                    val type = object : TypeToken<List<Int>>() {}.type
                    gson.fromJson<List<Int>>(json, type)
                } catch (_: Exception) {
                    emptyList()
                }
            } ?: emptyList()

            val items = loadItemsInternal(db, documentId)
            return ComingDocumentDraftRecord(
                documentId = cursor.getInt(0),
                operationId = cursor.getString(1),
                documentTitle = cursor.getStringOrNull(2),
                destinationStoreId = cursor.getInt(3),
                destinationStoreTitle = cursor.getStringOrNull(4),
                comment = cursor.getStringOrNull(5),
                allowedNomenclatureIds = allowedIds,
                items = items,
                deletedRemoteElementIds = deletedRemoteIds,
                state = ComingOperationState.fromNameOrDefault(cursor.getStringOrNull(8)),
                syncState = ComingSyncState.fromNameOrDefault(cursor.getStringOrNull(9)),
                lastErrorMessage = cursor.getStringOrNull(10),
                conductAttemptCount = cursor.getInt(11),
                createdAtMs = cursor.getLong(12),
                updatedAtMs = cursor.getLong(13),
                lastActiveAtMs = cursor.getLong(14)
            )
        }
    }

    private fun loadItemsInternal(db: SQLiteDatabase, documentId: Int): List<Product> {
        val list = mutableListOf<Product>()
        db.rawQuery(
            """
            SELECT local_item_id, product_id, iblock_id, catalog_product_type, offer_id, id_in_document,
                   product_name, measure_id, measure_symbol, quantity, barcode, diameter, weight, height,
                   parent_id, batch_number, is_old_product, destination_store_id, item_status, sync_state,
                   validation_error, updated_at_ms
            FROM $TABLE_COMING_ITEMS
            WHERE document_id = ?
            ORDER BY position_index ASC
            """.trimIndent(),
            arrayOf(documentId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(readProductFromCursor(cursor))
            }
        }
        return list
    }

    private fun loadSingleItemInternal(db: SQLiteDatabase, documentId: Int, localItemId: String): Product? {
        db.rawQuery(
            """
            SELECT local_item_id, product_id, iblock_id, catalog_product_type, offer_id, id_in_document,
                   product_name, measure_id, measure_symbol, quantity, barcode, diameter, weight, height,
                   parent_id, batch_number, is_old_product, destination_store_id, item_status, sync_state,
                   validation_error, updated_at_ms
            FROM $TABLE_COMING_ITEMS
            WHERE document_id = ? AND local_item_id = ?
            """.trimIndent(),
            arrayOf(documentId.toString(), localItemId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) readProductFromCursor(cursor) else null
        }
    }

    private fun readProductFromCursor(cursor: Cursor): Product {
        val product = Product(
            idInDocument = cursor.getIntOrNull(5),
            id = cursor.getInt(1),
            iblockId = cursor.getInt(2),
            name = cursor.getStringOrNull(6),
            measureId = cursor.getIntOrNull(7),
            measureSymbol = cursor.getStringOrNull(8),
            quantity = cursor.getDoubleOrNull(9),
            barcode = cursor.getStringOrNull(10),
            diameter = cursor.getDoubleOrNull(11),
            weight = cursor.getDoubleOrNull(12),
            height = cursor.getDoubleOrNull(13),
            parentId = cursor.getIntOrNull(14),
            storeAmount = null,
            batchNumber = cursor.getStringOrNull(15),
            isOldProduct = cursor.getInt(16) != 0
        )
        product.localItemId = cursor.getString(0)
        product.catalogProductType = cursor.getIntOrNull(3)
        product.offerId = cursor.getIntOrNull(4)
        product.destinationStoreId = cursor.getInt(17)
        product.itemStatus = ComingItemStatus.fromNameOrDefault(cursor.getStringOrNull(18))
        product.syncState = ComingSyncState.fromNameOrDefault(cursor.getStringOrNull(19))
        product.validationError = cursor.getStringOrNull(20)
        product.updatedAtMs = cursor.getLong(21)
        return product
    }

    private fun readJournalEntry(cursor: Cursor): OperationStepJournalEntry {
        return OperationStepJournalEntry(
            stepId = cursor.getLong(0),
            operationId = cursor.getString(1),
            documentId = cursor.getInt(2),
            localItemId = cursor.getStringOrNull(3),
            stepType = cursor.getString(4),
            status = cursor.getString(5),
            remoteEntityId = cursor.getIntOrNull(6),
            details = cursor.getStringOrNull(7),
            timestampMs = cursor.getLong(8)
        )
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)
    private fun Cursor.getDoubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
}
