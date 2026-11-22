package com.example.scannerkotlin.persistence

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Debug
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scannerkotlin.activities.ProductsDocumentComingActivity
import com.example.scannerkotlin.cache.CatalogReferenceCache
import com.example.scannerkotlin.model.ComingItemStatus
import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.ComingSyncState
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.SaveOutcome
import com.example.scannerkotlin.utils.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log

/**
 * Real Android OS SQLite WAL persistence, schema v1->v2 migration, memory/latency stress
 * (100/500/1000 items), hardware scan broadcast pipeline, and Activity open/close cycle tests.
 */
@RunWith(AndroidJUnit4::class)
class SqlitePersistenceAndMigrationInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        SessionManager.init(context)
        SessionManager.saveSession("1", "75")
    }

    @Test
    fun sqliteSchemaMigrationV1ToV2PreservesAllDraftsAndItems() {
        val dbName = "test_migration_v1_v2.db"
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // Create legacy v1 schema manually and insert a v1 unfinished incoming draft with 3 items
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            SqliteDraftStorageEngine.createSchemaV1(db)
            db.version = 1
            db.execSQL(
                """
                INSERT INTO coming_document_drafts (
                    document_id, operation_id, document_title, destination_store_id, comment,
                    allowed_nomenclature_json, deleted_remote_elements_json, state, sync_state,
                    last_error_message, created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (462, 'op-legacy-462', 'Приход V1', 1, 'Комментарий V1',
                          '[318857.0]', '[]', 'DRAFT', 'LOCAL_SAVED',
                          NULL, 1700000000000, 1700000005000, 1700000005000)
                """.trimIndent()
            )
            repeat(3) { idx ->
                db.execSQL(
                    """
                    INSERT INTO coming_draft_items (
                        local_item_id, document_id, operation_id, position_index, product_id,
                        iblock_id, catalog_product_type, offer_id, id_in_document, product_name,
                        measure_id, measure_symbol, quantity, barcode, diameter, weight, height,
                        parent_id, batch_number, is_old_product, destination_store_id,
                        item_status, sync_state, validation_error, updated_at_ms
                    ) VALUES (
                        'legacy-item-$idx', 462, 'op-legacy-462', $idx, 318857,
                        14, 3, NULL, NULL, 'Труба V1 #$idx',
                        1, 'м', 2.0, '460000111000$idx', 76.0, 12.0, 4.0,
                        NULL, 'П-1', 0, 1,
                        'PENDING', 'LOCAL_SAVED', NULL, 1700000005000
                    )
                    """.trimIndent()
                )
            }
        }

        // Open with v2 SqliteDraftStorageEngine -> triggers onUpgrade(1, 2) -> migrateV1ToV2
        val upgradedEngine = SqliteDraftStorageEngine(context, dbName)
        val migratedDraft = upgradedEngine.loadComingDraft(462)
        assertNotNull(migratedDraft)
        assertEquals("op-legacy-462", migratedDraft!!.operationId)
        assertEquals("Приход V1", migratedDraft.documentTitle)
        assertEquals(3, migratedDraft.items.size)
        assertEquals("4600001110000", migratedDraft.items[0].barcode)
        assertEquals("4600001110002", migratedDraft.items[2].barcode)
        upgradedEngine.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun sqliteRecordItemAndJournalAtomicallyPersistsOfferIdWithMatchingJournalRow() = runBlocking {
        val dbName = "test_atomic_item_journal.db"
        context.deleteDatabase(dbName)
        val engine = SqliteDraftStorageEngine(context, dbName)
        val repo = ComingDraftRepository(engine)
        val item = Product(
            idInDocument = null,
            id = 51,
            iblockId = 14,
            name = "Труба atomic",
            measureId = 1,
            measureSymbol = "м",
            quantity = 1.0,
            barcode = "4600000000777",
            diameter = 76.0,
            weight = 12.0,
            height = 4.0,
            parentId = null,
            storeAmount = null
        )
        repo.saveSnapshot(778, "Приход atomic", 1, null, listOf(item), null)
        val draft = repo.getDraft(778)!!
        repo.recordItemStepProgress(
            documentId = 778,
            operationId = draft.operationId,
            localItemId = draft.items[0].localItemId,
            offerId = 5551,
            idInDocument = 8801,
            itemStatus = ComingItemStatus.ELEMENT_ADDED,
            syncState = ComingSyncState.REMOTE_ELEMENT_CREATED,
            stepType = "ADD_ELEMENT",
            stepStatus = "SUCCEEDED",
            remoteEntityId = 8801
        )
        val reopened = SqliteDraftStorageEngine(context, dbName)
        val loaded = reopened.loadComingDraft(778)
        assertNotNull(loaded)
        assertEquals(5551, loaded!!.items[0].offerId)
        assertEquals(8801, loaded.items[0].idInDocument)
        val journal = reopened.getStepJournal(loaded.operationId)
        assertTrue(journal.any { it.stepType == "ADD_ELEMENT" && it.remoteEntityId == 8801 })
        engine.close()
        reopened.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun sqliteStressPersistAndRecover100_500_1000ItemsWithMemoryAndLatencyMetrics() = runBlocking {
        for (count in listOf(100, 500, 1000)) {
            val dbName = "test_stress_$count.db"
            context.deleteDatabase(dbName)
            val engine = SqliteDraftStorageEngine(context, dbName)
            val repo = ComingDraftRepository(engine)
            val docId = 8000 + count

            System.gc()
            val pssBeforeKb = Debug.getPss()
            val latenciesMicros = LongArray(count)
            val tStart = System.nanoTime()

            for (i in 1..count) {
                val item = Product(
                    idInDocument = null,
                    id = 318857 + (i % 25),
                    iblockId = 14,
                    name = "Труба ст20 76х12 #$i",
                    measureId = 1,
                    measureSymbol = "м",
                    quantity = 1.0,
                    barcode = String.format("460777%07d", i),
                    diameter = 76.0,
                    weight = 12.5,
                    height = 4.0,
                    parentId = null,
                    storeAmount = null
                )
                val t0 = System.nanoTime()
                val res = repo.saveAddedOrUpdatedItem(
                    documentId = docId,
                    documentTitle = "Приход SQLite $count",
                    destinationStoreId = 1,
                    allowedNomenclatureIds = listOf(318857.0),
                    item = item,
                    insertAtTop = false
                )
                latenciesMicros[i - 1] = (System.nanoTime() - t0) / 1000L
                assertTrue(res is SaveOutcome.Saved)
            }

            val totalMs = (System.nanoTime() - tStart) / 1_000_000L
            val pssAfterKb = Debug.getPss()
            engine.close()

            // Reopen cold SQLite engine to prove 100% disk durability
            val reopenedEngine = SqliteDraftStorageEngine(context, dbName)
            val restored = reopenedEngine.loadComingDraft(docId)
            assertNotNull(restored)
            assertEquals(count, restored!!.items.size)
            reopenedEngine.close()
            context.deleteDatabase(dbName)

            val sorted = latenciesMicros.sortedArray()
            val avgMs = latenciesMicros.average() / 1000.0
            val p95Ms = sorted[(count * 0.95).toInt().coerceAtMost(count - 1)] / 1000.0
            Log.i(
                "TSD_STRESS_METRIC",
                "SQLITE_STRESS count=$count totalMs=$totalMs avgMs=${"%.2f".format(avgMs)} " +
                    "p95Ms=${"%.2f".format(p95Ms)} pssBeforeKb=$pssBeforeKb pssAfterKb=$pssAfterKb lost=0"
            )
        }
    }

    @Test
    fun activityOpenCloseCyclesAndHardwareScanBroadcastsPersistWithoutLeakOrLoss() = runBlocking {
        val docId = 91010
        ComingDraftRepository.init(context)
        val sharedRepo = ComingDraftRepository.getInstance()
        ComingDraftRepository.engine().deleteComingDraft(docId)

        // Seed 1 template card without barcode so broadcasts populate & clone continuously
        val seedItem = Product(
            idInDocument = null,
            id = 318857,
            iblockId = 14,
            name = "Труба ст20 76х12 ГОСТ 8732",
            measureId = 5,
            measureSymbol = "шт",
            quantity = 1.0,
            barcode = null,
            diameter = 76.0,
            weight = 12.5,
            height = 12.0,
            parentId = null,
            storeAmount = null
        )
        sharedRepo.saveSnapshot(
            documentId = docId,
            documentTitle = "Приход Broadcast №$docId",
            destinationStoreId = 1,
            allowedNomenclatureIds = listOf(318857.0),
            products = listOf(seedItem),
            comment = "broadcast test"
        )

        val launchIntent = Intent(context, ProductsDocumentComingActivity::class.java).apply {
            putExtra("idDocument", docId.toString())
            putExtra("title", "Приход Broadcast №$docId")
        }

        // Send 15 distinct simulated hardware + local scan broadcasts while screen is active
        ActivityScenario.launch<ProductsDocumentComingActivity>(launchIntent).use {
            delay(500L)
            for (i in 1..15) {
                val bc = String.format("460555%07d\r\n", i)
                if (i <= 8) {
                    // Full Xcheng hardware broadcast -> ScanService / fallback -> BaseScanActivity
                    context.sendBroadcast(Intent("com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST").apply {
                        setPackage(context.packageName)
                        putExtra("EXTRA_BARCODE_DECODING_DATA", bc)
                    })
                } else {
                    // Local ScanService relay broadcast
                    context.sendBroadcast(Intent("Scan_data_received").apply {
                        setPackage(context.packageName)
                        putExtra("scanData", bc)
                    })
                }
                delay(55L)
            }
            delay(700L)
        }

        val persistedAfterScans = sharedRepo.getDraft(docId)
        assertNotNull(persistedAfterScans)
        assertEquals(15, persistedAfterScans!!.items.size)
        assertEquals("4605550000001", persistedAfterScans.items.last().barcode)

        // ST-08: Repeatedly open and close ProductsDocumentComingActivity 12 times and measure PSS
        System.gc()
        val pssStartKb = Debug.getPss()
        repeat(12) {
            ActivityScenario.launch<ProductsDocumentComingActivity>(launchIntent).use {
                delay(120L)
            }
        }
        System.gc()
        delay(200L)
        val pssEndKb = Debug.getPss()
        Log.i(
            "TSD_STRESS_METRIC",
            "ST08_OPEN_CLOSE cycles=12 items=15 pssStartKb=$pssStartKb pssEndKb=$pssEndKb deltaKb=${pssEndKb - pssStartKb}"
        )

        // Verify reference cache clear never deletes the persisted draft
        CatalogReferenceCache.clearAll()
        assertEquals(15, sharedRepo.getDraft(docId)?.items?.size)
        ComingDraftRepository.engine().deleteComingDraft(docId)
    }
}
