package com.example.scannerkotlin.persistence

import com.example.scannerkotlin.cache.CatalogReferenceCache
import com.example.scannerkotlin.model.ComingItemStatus
import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.ComingSyncState
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.SaveOutcome
import com.example.scannerkotlin.model.Store
import com.example.scannerkotlin.utils.BarcodeSanitizer
import com.example.scannerkotlin.utils.HardwareScanBurstDeduplicator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Stress, crash-recovery, atomic quantity update, and cache-isolation tests
 * (ST-01..ST-05, CR-01, CR-02, CR-04, CR-05, CR-06, CR-07, CR-08).
 */
class ComingDraftPersistenceAndStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `ST-01 sequentially persists and recovers 100 scanned products with zero loss`() = runBlocking {
        runSequentialScanPersistenceBenchmark(count = 100, docId = 7001)
    }

    @Test
    fun `ST-02 sequentially persists and recovers 500 scanned products with zero loss`() = runBlocking {
        runSequentialScanPersistenceBenchmark(count = 500, docId = 7002)
    }

    @Test
    fun `ST-03 sequentially persists and recovers 1000 scanned products with zero loss`() = runBlocking {
        runSequentialScanPersistenceBenchmark(count = 1000, docId = 7003)
    }

    private suspend fun runSequentialScanPersistenceBenchmark(count: Int, docId: Int) {
        val dir = tempFolder.newFolder("stress_$count")
        val engine = FileBackedDraftStorageEngine(dir)
        val repo = ComingDraftRepository(engine)

        val items = (1..count).map { i ->
            sampleProduct(
                id = 300_000 + (i % 50),
                name = "Труба ст20 #$i",
                barcode = String.format("460900%07d", i),
                qty = 1.0
            )
        }

        val latenciesMicros = LongArray(count)
        val startNs = System.nanoTime()
        items.forEachIndexed { idx, item ->
            val t0 = System.nanoTime()
            val outcome = repo.saveAddedOrUpdatedItem(
                documentId = docId,
                documentTitle = "Приход стресс №$docId",
                destinationStoreId = 1,
                allowedNomenclatureIds = listOf(300001.0),
                item = item,
                insertAtTop = false
            )
            latenciesMicros[idx] = (System.nanoTime() - t0) / 1_000L
            assertTrue(outcome is SaveOutcome.Saved)
        }
        val totalMs = (System.nanoTime() - startNs) / 1_000_000L

        // Simulate abrupt process kill and cold restart from disk directory
        val coldRestartEngine = FileBackedDraftStorageEngine(dir)
        val recovered = coldRestartEngine.loadComingDraft(docId)
        assertNotNull(recovered)
        assertEquals(count, recovered!!.items.size)
        assertEquals("4609000000001", recovered.items.first().barcode)
        assertEquals(String.format("460900%07d", count), recovered.items.last().barcode)

        val sorted = latenciesMicros.sortedArray()
        val avgMs = latenciesMicros.average() / 1000.0
        val p95Ms = sorted[(count * 0.95).toInt().coerceAtMost(count - 1)] / 1000.0
        println("BENCHMARK[JVM_DISK_PERSIST count=$count]: totalMs=$totalMs avgMs=${"%.2f".format(avgMs)} p95Ms=${"%.2f".format(p95Ms)} lost=0")
    }

    @Test
    fun `ST-04 and CR-05 burst deduplication and 50 concurrent atomic quantity increments preserve exact sum`() = runBlocking {
        val dedup = HardwareScanBurstDeduplicator(hardwareBurstWindowMs = 180L)
        assertTrue(dedup.shouldAccept("4600000000001", nowMs = 1_000L))
        assertFalse("Bounce at +40ms must be suppressed", dedup.shouldAccept("4600000000001", nowMs = 1_040L))
        assertTrue("Intentional repeat scan at +250ms must pass", dedup.shouldAccept("4600000000001", nowMs = 1_250L))

        val dir = tempFolder.newFolder("atomic_qty")
        val repo = ComingDraftRepository(FileBackedDraftStorageEngine(dir))
        val docId = 7004
        val item = sampleProduct(id = 318857, name = "Труба 76х12", barcode = "4600000000001", qty = 1.0)
        repo.saveAddedOrUpdatedItem(docId, "Приход №7004", 1, null, item, insertAtTop = true)

        // 50 concurrent atomic +1.0 increments
        val jobs = (1..50).map {
            async {
                repo.updateQuantityAtomically(docId, item.localItemId, delta = 1.0)
            }
        }
        val outcomes = jobs.awaitAll()
        assertTrue(outcomes.all { it.first is SaveOutcome.Saved })

        // Simulate abrupt termination during/after updates + corrupt leftover .tmp file
        File(dir, "tsd_state_snapshot.tmp").writeText("CORRUPTED_PARTIAL_WRITE{{{")

        val restartedRepo = ComingDraftRepository(FileBackedDraftStorageEngine(dir))
        val restored = restartedRepo.getDraft(docId)
        assertNotNull(restored)
        assertEquals(1, restored!!.items.size)
        assertEquals(51.0, restored.items.first().quantity ?: 0.0, 0.0001)
    }

    @Test
    fun `CR-01 CR-02 CR-07 CR-08 recovers multiple active drafts in last-active order and continues scanning`() = runBlocking {
        val dir = tempFolder.newFolder("multi_draft_recovery")
        val repo = ComingDraftRepository(FileBackedDraftStorageEngine(dir))

        // Draft A: 10 scans
        repeat(10) { i ->
            repo.saveAddedOrUpdatedItem(
                documentId = 801,
                documentTitle = "Приход №801",
                destinationStoreId = 1,
                allowedNomenclatureIds = listOf(10.0),
                item = sampleProduct(10, "Товар А-$i", String.format("460801%07d", i + 1), 1.0),
                insertAtTop = false
            )
        }
        Thread.sleep(10)
        // Draft B: 15 scans (more recent)
        repeat(15) { i ->
            repo.saveAddedOrUpdatedItem(
                documentId = 802,
                documentTitle = "Приход №802",
                destinationStoreId = 1,
                allowedNomenclatureIds = listOf(20.0),
                item = sampleProduct(20, "Товар Б-$i", String.format("460802%07d", i + 1), 2.0),
                insertAtTop = false
            )
        }

        // Cold restart
        val restartedRepo = ComingDraftRepository(FileBackedDraftStorageEngine(dir))
        val activeDrafts = restartedRepo.runStartupRecovery()
        assertEquals(2, activeDrafts.size)
        assertEquals("Most recent active document must be first", 802, activeDrafts[0].documentId)
        assertEquals(15, activeDrafts[0].itemCount)
        assertEquals(801, activeDrafts[1].documentId)
        assertEquals(10, activeDrafts[1].itemCount)

        // CR-08: Continue scanning 5 more items into recovered Draft 801
        repeat(5) { j ->
            restartedRepo.saveAddedOrUpdatedItem(
                documentId = 801,
                documentTitle = "Приход №801",
                destinationStoreId = 1,
                allowedNomenclatureIds = listOf(10.0),
                item = sampleProduct(10, "Товар А-доп-$j", String.format("460801999%04d", j + 1), 1.0),
                insertAtTop = false
            )
        }
        val final801 = restartedRepo.getDraft(801)
        assertEquals(15, final801?.items?.size)
    }

    @Test
    fun `cache clearing and LRU eviction never delete saved incoming drafts`() = runBlocking {
        val dir = tempFolder.newFolder("cache_isolation")
        val repo = ComingDraftRepository(FileBackedDraftStorageEngine(dir))
        repo.saveAddedOrUpdatedItem(
            documentId = 999,
            documentTitle = "Незавершённый приход №999",
            destinationStoreId = 1,
            allowedNomenclatureIds = listOf(51.0),
            item = sampleProduct(51, "Труба", "4609990000001", 3.0)
        )

        CatalogReferenceCache.putStores(
            listOf(
                Store(
                    active = "Y",
                    address = "",
                    code = null,
                    dateCreate = java.util.Date(),
                    dateModify = java.util.Date(),
                    description = "",
                    email = null,
                    latitude = 0.0,
                    longitude = 0.0,
                    id = 1,
                    isIssuingCenter = "N",
                    modifiedBy = null,
                    phone = "",
                    schedule = "",
                    sort = 100,
                    title = "Основной склад",
                    userId = null,
                    xmlId = null
                )
            )
        )
        repeat(650) { idx ->
            CatalogReferenceCache.putProduct(sampleProduct(idx + 1, "Каталог #$idx", null, 1.0))
        }
        // Verify LRU bound is strictly respected
        assertTrue(CatalogReferenceCache.stats().productCount <= CatalogReferenceCache.MAX_CACHED_PRODUCTS)

        // Trim and clear reference cache completely
        CatalogReferenceCache.trimForMemoryPressure()
        CatalogReferenceCache.clearAll()
        assertEquals(0, CatalogReferenceCache.stats().productCount)

        // Unfinished draft MUST remain 100% intact!
        val draft = repo.getDraft(999)
        assertNotNull(draft)
        assertEquals(1, draft!!.items.size)
        assertEquals("4609990000001", draft.items.first().barcode)
    }

    @Test
    fun `RV-item-journal atomic persist recovers both offerId and journal after reload`() = runBlocking {
        val dir = tempFolder.newFolder("atomic_item_journal")
        val engine = FileBackedDraftStorageEngine(dir)
        val repo = ComingDraftRepository(engine)
        val item = sampleProduct(51, "Труба", "4600000000999", 2.0)
        repo.saveSnapshot(
            documentId = 777,
            documentTitle = "Приход atomic",
            destinationStoreId = 1,
            allowedNomenclatureIds = null,
            products = listOf(item),
            comment = null
        )
        val localId = repo.getDraft(777)!!.items[0].localItemId
        repo.recordItemStepProgress(
            documentId = 777,
            operationId = repo.getDraft(777)!!.operationId,
            localItemId = localId,
            offerId = 4321,
            idInDocument = null,
            itemStatus = ComingItemStatus.VARIATION_RESOLVED,
            syncState = ComingSyncState.REMOTE_VARIATION_CREATED,
            stepType = "CREATE_VARIATION",
            stepStatus = "SUCCEEDED",
            remoteEntityId = 4321,
            details = "Created offerId=4321"
        )

        val reloaded = ComingDraftRepository(FileBackedDraftStorageEngine(dir))
        val draft = reloaded.getDraft(777)
        assertNotNull(draft)
        assertEquals(4321, draft!!.items[0].offerId)
        assertEquals(ComingItemStatus.VARIATION_RESOLVED, draft.items[0].itemStatus)
        val journal = reloaded.getJournal(draft.operationId)
        assertTrue(journal.any { it.stepType == "CREATE_VARIATION" && it.remoteEntityId == 4321 })
    }

    @Test
    fun `saveSnapshot merge does not wipe persisted offerId from incoming UI copy`() = runBlocking {
        val dir = tempFolder.newFolder("merge_offer")
        val repo = ComingDraftRepository(FileBackedDraftStorageEngine(dir))
        val original = sampleProduct(51, "Труба", "4600000000888", 1.0)
        repo.saveSnapshot(51, "Приход", 1, null, listOf(original), null)
        val localId = repo.getDraft(51)!!.items[0].localItemId
        repo.recordItemStepProgress(
            documentId = 51,
            operationId = repo.getDraft(51)!!.operationId,
            localItemId = localId,
            offerId = 9009,
            idInDocument = 7007,
            itemStatus = ComingItemStatus.ELEMENT_ADDED,
            syncState = ComingSyncState.REMOTE_ELEMENT_CREATED,
            stepType = "ADD_ELEMENT",
            stepStatus = "SUCCEEDED"
        )

        val uiCopy = sampleProduct(51, "Труба", "4600000000888", 4.0)
        uiCopy.localItemId = localId
        uiCopy.offerId = null
        uiCopy.idInDocument = null
        val outcome = repo.saveSnapshot(51, "Приход", 1, null, listOf(uiCopy), "updated qty")
        assertTrue(outcome is SaveOutcome.Saved)

        val merged = repo.getDraft(51)!!
        assertEquals(9009, merged.items[0].offerId)
        assertEquals(7007, merged.items[0].idInDocument)
        assertEquals(4.0, merged.items[0].quantity!!, 0.0)
        assertEquals(ComingItemStatus.ELEMENT_ADDED, merged.items[0].itemStatus)
    }

    @Test
    fun `ST-05 BarcodeSanitizer strips CRLF scanner suffixes cleanly`() {
        assertEquals("4601234567890", BarcodeSanitizer.sanitize("  4601234567890\r\n "))
        assertEquals("0000000021968", BarcodeSanitizer.normalizeForComingVariation("0000000021968\n"))
        assertEquals(null, BarcodeSanitizer.sanitize(" \r\n\t "))
    }

    private fun sampleProduct(id: Int, name: String, barcode: String?, qty: Double): Product {
        return Product(
            idInDocument = null,
            id = id,
            iblockId = 14,
            name = name,
            measureId = 1,
            measureSymbol = "м",
            quantity = qty,
            barcode = barcode,
            diameter = 57.0,
            weight = 10.0,
            height = 3.5,
            parentId = null,
            storeAmount = null
        )
    }
}
