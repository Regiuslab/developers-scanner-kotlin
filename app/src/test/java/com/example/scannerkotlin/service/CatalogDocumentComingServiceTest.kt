package com.example.scannerkotlin.service

import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.cache.CatalogReferenceCache
import com.example.scannerkotlin.config.BitrixConcurrencyMode
import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.ProductOffer
import com.example.scannerkotlin.persistence.ComingDraftRepository
import com.example.scannerkotlin.persistence.DraftStorageEngine
import com.example.scannerkotlin.persistence.FileBackedDraftStorageEngine
import com.example.scannerkotlin.request.AddDocumentElementRequest
import com.example.scannerkotlin.request.BarcodeRequest
import com.example.scannerkotlin.request.BlankRequest
import com.example.scannerkotlin.request.CatalogDocumentElementListRequest
import com.example.scannerkotlin.request.CatalogDocumentListRequest
import com.example.scannerkotlin.request.ConductDocumentRequest
import com.example.scannerkotlin.request.DeletedDocumentElementRequest
import com.example.scannerkotlin.request.GettingProvidersRequest
import com.example.scannerkotlin.request.NewDocumentRequest
import com.example.scannerkotlin.request.NomenclatureRequest
import com.example.scannerkotlin.request.PasswordRequest
import com.example.scannerkotlin.request.ProcessUpdateRequest
import com.example.scannerkotlin.request.ProcessesRequest
import com.example.scannerkotlin.request.ProductIdRequest
import com.example.scannerkotlin.request.ProductOfferRequest
import com.example.scannerkotlin.request.ProductRequest
import com.example.scannerkotlin.request.ProviderRequest
import com.example.scannerkotlin.request.SgpRequest
import com.example.scannerkotlin.request.StoreAmountRequest
import com.example.scannerkotlin.request.UpdateCommentRequest
import com.example.scannerkotlin.request.TsdDocumentSyncItem
import com.example.scannerkotlin.request.TsdDocumentSyncRequest
import com.example.scannerkotlin.request.TsdVariationResolveRequest
import com.example.scannerkotlin.request.UpdateProductRequest
import com.example.scannerkotlin.request.UpdatedDocumentElementsRequest
import com.example.scannerkotlin.request.VariationsRequest
import com.example.scannerkotlin.response.BarcodeResponse
import com.example.scannerkotlin.response.CatalogDocumentElementListResponse
import com.example.scannerkotlin.response.CatalogDocumentListResponse
import com.example.scannerkotlin.response.ConductResult
import com.example.scannerkotlin.response.FirstBlankResponse
import com.example.scannerkotlin.response.GettingProvidersResponse
import com.example.scannerkotlin.response.NomenclatureResponse
import com.example.scannerkotlin.response.PasswordResponse
import com.example.scannerkotlin.response.ProcessesResponse
import com.example.scannerkotlin.response.ProductBarcodeResponse
import com.example.scannerkotlin.response.ProductOfferResponse
import com.example.scannerkotlin.response.ProductResponse
import com.example.scannerkotlin.response.SecondBlankResponse
import com.example.scannerkotlin.response.SgpResponse
import com.example.scannerkotlin.response.StoreAmountResponse
import com.example.scannerkotlin.response.TsdDocumentSyncResponse
import com.example.scannerkotlin.response.TsdVariationResolveResponse
import com.example.scannerkotlin.response.VariationsResponse
import com.google.gson.internal.LinkedTreeMap
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Call
import retrofit2.Response
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive verification of incoming document validation, variation/barcode idempotency,
 * foreign barcode conflict protection, retry reconciliation, timeout recovery, and concurrency
 * (Scenarios IC-01 through IC-14 from Section 12.C).
 */
class CatalogDocumentComingServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var engine: FileBackedDraftStorageEngine
    private lateinit var repository: ComingDraftRepository
    private lateinit var fakeServer: FakeBitrixPortal

    @Before
    fun setUp() {
        CatalogReferenceCache.clearAll()
        engine = FileBackedDraftStorageEngine(tempFolder.newFolder("draft_db"))
        repository = ComingDraftRepository(engine)
        ComingDraftRepository.installEngineForTest(engine)
        fakeServer = FakeBitrixPortal()
    }

    // IC-01: Successful conduct of 10 valid products
    @Test
    fun `IC-01 conducts 10 valid products creating variations barcodes elements and committing once`() = runBlocking {
        val docId = 501
        fakeServer.seedDraftDocument(docId)
        val products = (1..10).map { i ->
            val pid = 100 + i
            fakeServer.seedParentProduct(pid, "Труба №$i", iblockId = 14, type = 3)
            comingProduct(id = pid, name = "Труба №$i", barcode = String.format("4600000000%03d", i), qty = i.toDouble())
        }

        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)
        val result = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = "Партия 10 шт",
            deletedProducts = emptyList(),
            updatedProducts = products,
            currentProductListState = products,
            productOffersList = products.map { ProductOffer(it) }
        )

        assertTrue("Expected success, got: ${result.message}", result.isSuccess)
        assertEquals(ComingOperationState.COMMITTED, result.state)
        assertEquals(10, fakeServer.offerAddCalls.get())
        assertEquals(10, fakeServer.barcodeSetCalls.get())
        assertEquals(10, fakeServer.elementAddCalls.get())
        assertEquals(1, fakeServer.conductCalls.get())
        assertEquals("Y", fakeServer.documentStatuses[docId])
        assertEquals(ComingOperationState.COMMITTED, repository.getDraft(docId)?.state)
    }

    // IC-02 & IC-03: Error on 10th position stops BEFORE creating variations 1..9, then retry succeeds after fix
    @Test
    fun `IC-02 and IC-03 tenth position unsupported nomenclature stops before creating 1-9 and succeeds after fix`() = runBlocking {
        val docId = 502
        fakeServer.seedDraftDocument(docId)
        val products = (1..10).map { i ->
            val pid = 200 + i
            // 10th item is a service (type = 7) that does NOT support variations!
            val serverType = if (i == 10) 7 else 3
            fakeServer.seedParentProduct(pid, "Позиция $i", iblockId = 14, type = serverType)
            comingProduct(id = pid, name = "Позиция $i", barcode = String.format("4601110000%03d", i), qty = 2.0)
        }.toMutableList()

        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)
        val firstAttempt = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = products,
            currentProductListState = products,
            productOffersList = products.map { ProductOffer(it) }
        )

        assertFalse(firstAttempt.isSuccess)
        assertEquals(9, firstAttempt.failedPositionIndex)
        assertTrue(firstAttempt.message.orEmpty().contains("Позиция №10"))
        assertTrue(firstAttempt.message.orEmpty().contains("не поддерживает создание вариаций"))
        // Crucial check: ZERO variations or barcodes created for items 1..9!
        assertEquals(0, fakeServer.offerAddCalls.get())
        assertEquals(0, fakeServer.barcodeSetCalls.get())
        assertEquals(0, fakeServer.conductCalls.get())

        // IC-03: User replaces 10th position with supported SKU nomenclature and retries without rescanning 1..9
        fakeServer.seedParentProduct(299, "Труба исправленная", iblockId = 14, type = 3)
        val fixedTenth = comingProduct(id = 299, name = "Труба исправленная", barcode = "4601110000010", qty = 2.0)
        products[9] = fixedTenth

        val secondAttempt = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = products,
            currentProductListState = products,
            productOffersList = products.map { ProductOffer(it) }
        )

        assertTrue("Expected retry success: ${secondAttempt.message}", secondAttempt.isSuccess)
        assertEquals(10, fakeServer.offerAddCalls.get())
        assertEquals(10, fakeServer.barcodeSetCalls.get())
        assertEquals(10, fakeServer.elementAddCalls.get())
        assertEquals(1, fakeServer.conductCalls.get())
    }

    // IC-04 & IC-05: Existing compatible variation and barcode are reused without duplicate creation
    @Test
    fun `IC-04 and IC-05 reuses existing compatible offer and barcode when parentId matches`() = runBlocking {
        val docId = 504
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.seedExistingOffer(offerId = 432, parentId = 51, name = "Труба 63", barcode = "1234567890123")

        val item = comingProduct(id = 51, name = "Труба 63", barcode = "1234567890123", qty = 5.0)
        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)

        val result = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )

        assertTrue(result.isSuccess)
        assertEquals(432, item.offerId)
        assertEquals(0, fakeServer.offerAddCalls.get())
        assertEquals(0, fakeServer.barcodeSetCalls.get())
        assertEquals(1, fakeServer.elementAddCalls.get())
        assertEquals(1, fakeServer.conductCalls.get())
    }

    // IC-06: Barcode belonging to a DIFFERENT product is rejected as conflict without modifying foreign product
    @Test
    fun `IC-06 rejects barcode belonging to another product and never mutates foreign item`() = runBlocking {
        val docId = 506
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.seedParentProduct(88, "Отвод 90", iblockId = 14, type = 3)
        fakeServer.seedExistingOffer(offerId = 9001, parentId = 88, name = "Отвод 90 вар.", barcode = "4607778889990")

        val item = comingProduct(id = 51, name = "Труба 63", barcode = "4607778889990", qty = 3.0).apply {
            weight = 99.0
            diameter = 63.0
        }
        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)

        val result = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )

        assertFalse(result.isSuccess)
        assertEquals(0, result.failedPositionIndex)
        assertTrue(
            "Actual message: ${result.message}",
            result.message.orEmpty().contains("другим товаром") || result.message.orEmpty().contains("другому товару")
        )
        assertTrue(result.message.orEmpty().contains("Отвод 90"))
        assertEquals(0, fakeServer.offerAddCalls.get())
        assertEquals(0, fakeServer.offerUpdateCalls.get()) // Foreign offer 9001 was NOT overwritten!
        assertEquals(0, fakeServer.elementAddCalls.get())
        assertEquals(0, fakeServer.conductCalls.get())
    }

    // IC-07 & IC-08: Repeat conduct and concurrent double-tap never double-conduct or double stock
    @Test
    fun `IC-07 and IC-08 blocks concurrent double tap and repeat conduct after commit`() = runBlocking {
        val docId = 508
        fakeServer.seedDraftDocument(docId)
        fakeServer.artificialConductDelayMs = 80L
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)

        val item = comingProduct(id = 51, name = "Труба 63", barcode = "4600000000508", qty = 4.0)
        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)

        val firstJob = async {
            service.conductDocumentSuspend(
                baseList = emptyList(),
                idDocument = docId,
                context = null,
                documentComment = null,
                deletedProducts = emptyList(),
                updatedProducts = listOf(item),
                currentProductListState = listOf(item),
                productOffersList = listOf(ProductOffer(item))
            )
        }
        delay(15L)
        val secondConcurrentJob = async {
            service.conductDocumentSuspend(
                baseList = emptyList(),
                idDocument = docId,
                context = null,
                documentComment = null,
                deletedProducts = emptyList(),
                updatedProducts = listOf(item),
                currentProductListState = listOf(item),
                productOffersList = listOf(ProductOffer(item))
            )
        }

        val r1 = firstJob.await()
        val r2 = secondConcurrentJob.await()

        assertTrue(r1.isSuccess)
        assertFalse("Concurrent duplicate tap must be blocked", r2.isSuccess)
        assertEquals(1, fakeServer.offerAddCalls.get())
        assertEquals(1, fakeServer.elementAddCalls.get())
        assertEquals(1, fakeServer.conductCalls.get())

        // IC-07: Subsequent repeat call after commit
        val r3 = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )
        assertTrue(r3.isSuccess)
        assertTrue(r3.reconciledAlreadyConducted)
        assertEquals(1, fakeServer.conductCalls.get())
        assertEquals(1, fakeServer.elementAddCalls.get())
    }

    // IC-09: Timeout after server actually conducted the document -> reconciled as COMMITTED without re-conducting
    @Test
    fun `IC-09 reconciles timeout when Bitrix actually conducted the document`() = runBlocking {
        val docId = 509
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.commitOnServerBeforeThrowingConductTimeout = true

        val item = comingProduct(id = 51, name = "Труба 63", barcode = "4600000000509", qty = 7.0)
        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)

        val result = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )

        assertTrue(result.isSuccess)
        assertTrue(result.reconciledAlreadyConducted)
        assertEquals(ComingOperationState.COMMITTED, result.state)
        assertEquals(1, fakeServer.conductCalls.get())
        assertEquals(1, fakeServer.elementAddCalls.get())
    }

    // IC-10, IC-11 & IC-13: Partial failure after creating variations & adding elements + process restart -> zero duplicates on retry
    @Test
    fun `IC-10 IC-11 IC-13 partial failure and restart reuse created variations and server elements without double stock`() = runBlocking {
        val docId = 510
        fakeServer.seedDraftDocument(docId)
        val products = (1..5).map { i ->
            val pid = 600 + i
            fakeServer.seedParentProduct(pid, "Труба $i", iblockId = 14, type = 3)
            comingProduct(id = pid, name = "Труба $i", barcode = String.format("4605100000%03d", i), qty = 3.0)
        }

        // First attempt: variations 1..5 and document elements 1..5 succeed, then conduct fails on server!
        fakeServer.failNextConductResponse = true
        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)
        val attempt1 = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = products,
            currentProductListState = products,
            productOffersList = products.map { ProductOffer(it) }
        )

        assertFalse(attempt1.isSuccess)
        assertEquals(5, fakeServer.offerAddCalls.get())
        assertEquals(5, fakeServer.barcodeSetCalls.get())
        assertEquals(5, fakeServer.elementAddCalls.get())
        assertEquals(1, fakeServer.conductCalls.get())

        // Simulate app crash/restart while state was interrupted -> reload from disk in a brand-new engine & repository!
        engine.updateDraftMetadata(docId, state = ComingOperationState.PROCESSING)
        val restartedEngine = FileBackedDraftStorageEngine(tempFolder.root.resolve("draft_db"))
        val restartedRepo = ComingDraftRepository(restartedEngine)
        val recoveredDrafts = restartedRepo.runStartupRecovery()
        val recovered = recoveredDrafts.firstOrNull { it.documentId == docId }
        assertNotNull(recovered)
        assertEquals(ComingOperationState.RECONCILIATION_REQUIRED, recovered!!.state)
        assertEquals(5, recovered.items.size)

        // Retry conduct after recovery: must reconcile, create 0 new variations, add 0 duplicate elements, and conduct once!
        val restartedService = CatalogDocumentComingService(fakeServer, fakeServer, restartedRepo)
        val attempt2 = restartedService.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = recovered.comment,
            deletedProducts = emptyList(),
            updatedProducts = recovered.items,
            currentProductListState = recovered.items,
            productOffersList = recovered.items.map { ProductOffer(it) }
        )

        assertTrue("Expected retry after restart to succeed: ${attempt2.message}", attempt2.isSuccess)
        assertEquals("Must NOT create duplicate variations", 5, fakeServer.offerAddCalls.get())
        assertEquals("Must NOT re-set barcodes", 5, fakeServer.barcodeSetCalls.get())
        assertEquals("Must NOT add duplicate document rows (no double stock!)", 5, fakeServer.elementAddCalls.get())
        assertEquals(5, fakeServer.documentElements[docId]?.size)
        assertEquals(2, fakeServer.conductCalls.get())
        assertEquals("Y", fakeServer.documentStatuses[docId])
    }

    // IC-12 & IC-14: Concurrent second TSD + stale local reference cache ignored during conduct
    @Test
    fun `IC-12 and IC-14 detects document already conducted by second TSD despite stale local cache`() = runBlocking {
        val docId = 512
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)

        // Poison local reference cache with stale barcode mapping to a wrong offerId
        CatalogReferenceCache.putBarcodeMapping("4605120000001", 99999)

        // Simulate TSD-1 conducting document 512 on the server right before TSD-2 taps "Провести"
        fakeServer.documentStatuses[docId] = "Y"

        val item = comingProduct(id = 51, name = "Труба 63", barcode = "4605120000001", qty = 2.0)
        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)
        val result = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )

        assertTrue(result.isSuccess)
        assertTrue(result.reconciledAlreadyConducted)
        assertEquals(0, fakeServer.offerAddCalls.get())
        assertEquals(0, fakeServer.elementAddCalls.get())
        assertEquals(0, fakeServer.conductCalls.get())
    }

    @Test
    fun `RV-01 saveSnapshot SQLite failure blocks Bitrix mutations and preserves draft`() = runBlocking {
        val docId = 601
        val failingEngine = FailingSnapshotEngine(engine)
        val failingRepo = ComingDraftRepository(failingEngine)
        ComingDraftRepository.installEngineForTest(failingEngine)

        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        val item = comingProduct(id = 51, name = "Труба 63", barcode = "4600000000601", qty = 2.0)
        val saved = failingRepo.saveSnapshot(
            documentId = docId,
            documentTitle = "Приход RV-01",
            destinationStoreId = 1,
            allowedNomenclatureIds = null,
            products = listOf(item),
            comment = "не терять"
        )
        assertTrue(saved is com.example.scannerkotlin.model.SaveOutcome.Saved)
        assertEquals(1, failingRepo.getDraft(docId)!!.items.size)

        failingEngine.failNextSaveFull = true
        val service = CatalogDocumentComingService(fakeServer, fakeServer, failingRepo)
        val result = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = "не терять",
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )

        assertFalse(result.isSuccess)
        assertTrue(
            "Actual message: ${result.message}",
            result.message.orEmpty().contains("сохранить черновик") &&
                result.message.orEmpty().contains("заблокировано")
        )
        assertEquals(0, fakeServer.offerAddCalls.get())
        assertEquals(0, fakeServer.barcodeSetCalls.get())
        assertEquals(0, fakeServer.elementAddCalls.get())
        assertEquals(0, fakeServer.conductCalls.get())

        failingEngine.failNextSaveFull = false
        val preserved = failingRepo.getDraft(docId)
        assertNotNull(preserved)
        assertEquals(1, preserved!!.items.size)
        assertEquals("4600000000601", preserved.items[0].barcode)
        assertEquals("не терять", preserved.comment)
        assertEquals(ComingOperationState.DRAFT, preserved.state)
    }

    @Test
    fun `RV-02 unverified document status blocks all mutating Bitrix calls`() = runBlocking {
        val docId = 602
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.omitDocumentsKeyInList = true

        val item = comingProduct(id = 51, name = "Труба 63", barcode = "4600000000602", qty = 1.0)
        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)
        val result = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )

        assertFalse(result.isSuccess)
        assertEquals(ComingOperationState.RECONCILIATION_REQUIRED, result.state)
        assertTrue(result.message.orEmpty().contains("достоверно подтвердить статус"))
        assertEquals(0, fakeServer.offerAddCalls.get())
        assertEquals(0, fakeServer.barcodeSetCalls.get())
        assertEquals(0, fakeServer.elementAddCalls.get())
        assertEquals(0, fakeServer.conductCalls.get())
    }

    @Test
    fun `RV-03 error after offer add requires reconciliation and retry reuses variation`() = runBlocking {
        val docId = 603
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.throwOnNextBarcodeSet = java.net.SocketTimeoutException("after offer.add")

        val item = comingProduct(id = 51, name = "Труба 63", barcode = "4600000000603", qty = 3.0)
        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)
        val first = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )

        assertFalse(first.isSuccess)
        assertEquals(ComingOperationState.RECONCILIATION_REQUIRED, first.state)
        assertEquals(1, fakeServer.offerAddCalls.get())
        assertEquals(0, fakeServer.elementAddCalls.get())
        assertEquals(0, fakeServer.conductCalls.get())
        val persistedOfferId = repository.getDraft(docId)!!.items[0].offerId
        assertNotNull(persistedOfferId)

        engine.updateDraftMetadata(docId, state = ComingOperationState.PROCESSING)
        val restartedEngine = FileBackedDraftStorageEngine(tempFolder.root.resolve("draft_db"))
        val restartedRepo = ComingDraftRepository(restartedEngine)
        val recovered = restartedRepo.runStartupRecovery().first { it.documentId == docId }
        assertEquals(ComingOperationState.RECONCILIATION_REQUIRED, recovered.state)
        assertEquals(persistedOfferId, recovered.items[0].offerId)

        val restartedService = CatalogDocumentComingService(fakeServer, fakeServer, restartedRepo)
        val retry = restartedService.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = recovered.items,
            currentProductListState = recovered.items,
            productOffersList = recovered.items.map { ProductOffer(it) }
        )

        assertTrue("Retry should succeed: ${retry.message}", retry.isSuccess)
        assertEquals("Must not create a second variation", 1, fakeServer.offerAddCalls.get())
        assertEquals(1, fakeServer.elementAddCalls.get())
        assertEquals(1, fakeServer.conductCalls.get())
    }

    @Test
    fun `RV-04 error after barcode set requires reconciliation without duplicate barcode bind`() = runBlocking {
        val docId = 604
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.throwAfterBindingBarcode = true
        fakeServer.failBarcodeLookupIfAlreadyBound = true

        val item = comingProduct(id = 51, name = "Труба 63", barcode = "4600000000604", qty = 1.0)
        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)
        val first = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )

        assertFalse(first.isSuccess)
        assertEquals(ComingOperationState.RECONCILIATION_REQUIRED, first.state)
        assertEquals(1, fakeServer.offerAddCalls.get())
        assertEquals(1, fakeServer.barcodeSetCalls.get())
        assertEquals(0, fakeServer.elementAddCalls.get())

        val retry = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )
        assertTrue("Retry should reuse bound barcode: ${retry.message}", retry.isSuccess)
        assertEquals(1, fakeServer.offerAddCalls.get())
        assertEquals(1, fakeServer.barcodeSetCalls.get())
        assertEquals(1, fakeServer.elementAddCalls.get())
        assertEquals(1, fakeServer.conductCalls.get())
    }

    @Test
    fun `RV-05 error after element add requires reconciliation and does not duplicate stock`() = runBlocking {
        val docId = 605
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.throwAfterAddingElement = true

        val item = comingProduct(id = 51, name = "Труба 63", barcode = "4600000000605", qty = 8.0)
        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)
        val first = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )

        assertFalse(first.isSuccess)
        assertEquals(ComingOperationState.RECONCILIATION_REQUIRED, first.state)
        assertEquals(1, fakeServer.elementAddCalls.get())
        assertEquals(1, fakeServer.documentElements[docId]?.size)
        assertEquals(0, fakeServer.conductCalls.get())

        val retry = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )
        assertTrue("Retry must match existing server element: ${retry.message}", retry.isSuccess)
        assertEquals(1, fakeServer.elementAddCalls.get())
        assertEquals(1, fakeServer.documentElements[docId]?.size)
        assertEquals(1, fakeServer.conductCalls.get())
    }

    @Test
    fun `RV-06 unverified status after conduct does not mark committed and retry reconciles`() = runBlocking {
        val docId = 606
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.omitDocumentsKeyInListAfterConduct = true

        val item = comingProduct(id = 51, name = "Труба 63", barcode = "4600000000606", qty = 1.0)
        val service = CatalogDocumentComingService(fakeServer, fakeServer, repository)
        val first = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )

        assertFalse(first.isSuccess)
        assertEquals(ComingOperationState.RECONCILIATION_REQUIRED, first.state)
        assertEquals("Y", fakeServer.documentStatuses[docId])
        assertEquals(1, fakeServer.conductCalls.get())
        assertEquals(ComingOperationState.RECONCILIATION_REQUIRED, repository.getDraft(docId)?.state)

        fakeServer.omitDocumentsKeyInList = false
        val retry = service.conductDocumentSuspend(
            baseList = emptyList(),
            idDocument = docId,
            context = null,
            documentComment = null,
            deletedProducts = emptyList(),
            updatedProducts = listOf(item),
            currentProductListState = listOf(item),
            productOffersList = listOf(ProductOffer(item))
        )
        assertTrue(retry.isSuccess)
        assertTrue(retry.reconciledAlreadyConducted)
        assertEquals(1, fakeServer.conductCalls.get())
        assertEquals(ComingOperationState.COMMITTED, retry.state)
    }

    @Test
    fun `CX-01 LEGACY two TSD racing a new barcode confirms extra variation limitation`() = runBlocking {
        val barcode = "4600000000801"
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.seedDraftDocument(801)
        fakeServer.seedDraftDocument(802)
        fakeServer.offerAddRaceParticipants = 2

        val (repoA, serviceA) = newIsolatedClient(fakeServer, "tsd_a_cx01")
        val (repoB, serviceB) = newIsolatedClient(fakeServer, "tsd_b_cx01")
        val itemA = comingProduct(id = 51, name = "Труба 63", barcode = barcode, qty = 2.0)
        val itemB = comingProduct(id = 51, name = "Труба 63", barcode = barcode, qty = 5.0)

        val jobA = async {
            serviceA.conductDocumentSuspend(
                baseList = emptyList(), idDocument = 801, context = null, documentComment = "TSD-A",
                deletedProducts = emptyList(), updatedProducts = listOf(itemA),
                currentProductListState = listOf(itemA), productOffersList = listOf(ProductOffer(itemA))
            )
        }
        val jobB = async {
            serviceB.conductDocumentSuspend(
                baseList = emptyList(), idDocument = 802, context = null, documentComment = "TSD-B",
                deletedProducts = emptyList(), updatedProducts = listOf(itemB),
                currentProductListState = listOf(itemB), productOffersList = listOf(ProductOffer(itemB))
            )
        }
        val rA = jobA.await()
        val rB = jobB.await()

        assertTrue("TSD-A: ${rA.message}", rA.isSuccess)
        assertTrue("TSD-B: ${rB.message}", rB.isSuccess)
        assertEquals("Y", fakeServer.documentStatuses[801])
        assertEquals("Y", fakeServer.documentStatuses[802])
        assertEquals(1, fakeServer.barcodeToOfferId.keys.count { it == barcode })
        val boundOffer = fakeServer.barcodeToOfferId[barcode]
        assertNotNull(boundOffer)
        val boundParent = ((fakeServer.offersById[boundOffer]!!["parentId"] as LinkedTreeMap<*, *>)["value"] as Number).toInt()
        assertEquals(51, boundParent)
        val extraVariations = fakeServer.offerAddCalls.get() - 1
        println(
            "CX-01 LEGACY CONFIRMED_LIMITATION offerAddCalls=${fakeServer.offerAddCalls.get()} " +
                "extraVariations=$extraVariations uniqueBarcodes=1 boundOffer=$boundOffer"
        )
        assertEquals(
            "LEGACY REST must expose the extra-variation race (do not hide it behind a unique mock)",
            2,
            fakeServer.offerAddCalls.get()
        )
        assertTrue(
            "Orphan variation exists without a barcode binding",
            fakeServer.offersById.size >= 2
        )
        assertNotNull(repoA.getDraft(801))
        assertNotNull(repoB.getDraft(802))
    }

    @Test
    fun `CX-01 SERVER_GUARDED two TSD racing a new barcode create one variation`() = runBlocking {
        val barcode = "4600000000801"
        fakeServer.tsdGuardEnabled = true
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.seedDraftDocument(901)
        fakeServer.seedDraftDocument(902)

        val (_, serviceA) = newIsolatedClient(fakeServer, "tsd_a_cx01g", BitrixConcurrencyMode.SERVER_GUARDED)
        val (_, serviceB) = newIsolatedClient(fakeServer, "tsd_b_cx01g", BitrixConcurrencyMode.SERVER_GUARDED)
        val itemA = comingProduct(id = 51, name = "Труба 63", barcode = barcode, qty = 2.0)
        val itemB = comingProduct(id = 51, name = "Труба 63", barcode = barcode, qty = 5.0)

        val jobA = async {
            serviceA.conductDocumentSuspend(
                baseList = emptyList(), idDocument = 901, context = null, documentComment = "TSD-A",
                deletedProducts = emptyList(), updatedProducts = listOf(itemA),
                currentProductListState = listOf(itemA), productOffersList = listOf(ProductOffer(itemA))
            )
        }
        val jobB = async {
            serviceB.conductDocumentSuspend(
                baseList = emptyList(), idDocument = 902, context = null, documentComment = "TSD-B",
                deletedProducts = emptyList(), updatedProducts = listOf(itemB),
                currentProductListState = listOf(itemB), productOffersList = listOf(ProductOffer(itemB))
            )
        }
        val rA = jobA.await()
        val rB = jobB.await()
        assertTrue("TSD-A: ${rA.message}", rA.isSuccess)
        assertTrue("TSD-B: ${rB.message}", rB.isSuccess)
        assertEquals(1, fakeServer.offerAddCalls.get())
        assertEquals(1, fakeServer.offersById.size)
        assertEquals(1, fakeServer.barcodeToOfferId.size)
        val offerA = itemA.offerId ?: fakeServer.barcodeToOfferId[barcode]
        val offerB = itemB.offerId ?: fakeServer.barcodeToOfferId[barcode]
        assertEquals(offerA, offerB)
        assertEquals(51, ((fakeServer.offersById[offerA]!!["parentId"] as LinkedTreeMap<*, *>)["value"] as Number).toInt())
        assertEquals("Y", fakeServer.documentStatuses[901])
        assertEquals("Y", fakeServer.documentStatuses[902])
        assertEquals(2.0, fakeServer.stockMovedByDocument[901])
        assertEquals(5.0, fakeServer.stockMovedByDocument[902])
    }

    @Test
    fun `CX-02 LEGACY two TSD conducting one document confirms duplicate row limitation`() = runBlocking {
        val docId = 812
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.elementListRaceParticipants = 2
        fakeServer.elementAddRaceParticipants = 2

        val itemA = comingProduct(id = 51, name = "Труба 63", barcode = "4600000000812", qty = 3.0)
        val itemB = comingProduct(id = 51, name = "Труба 63", barcode = "4600000000813", qty = 3.0)
        val (_, serviceA) = newIsolatedClient(fakeServer, "tsd_a_cx02")
        val (_, serviceB) = newIsolatedClient(fakeServer, "tsd_b_cx02")

        val jobA = async {
            serviceA.conductDocumentSuspend(
                baseList = emptyList(), idDocument = docId, context = null, documentComment = null,
                deletedProducts = emptyList(), updatedProducts = listOf(itemA),
                currentProductListState = listOf(itemA), productOffersList = listOf(ProductOffer(itemA))
            )
        }
        val jobB = async {
            serviceB.conductDocumentSuspend(
                baseList = emptyList(), idDocument = docId, context = null, documentComment = null,
                deletedProducts = emptyList(), updatedProducts = listOf(itemB),
                currentProductListState = listOf(itemB), productOffersList = listOf(ProductOffer(itemB))
            )
        }
        val rA = jobA.await()
        val rB = jobB.await()

        assertTrue("At least one client must finish: A=${rA.message} B=${rB.message}", rA.isSuccess || rB.isSuccess)
        assertEquals("Y", fakeServer.documentStatuses[docId])
        val rows = fakeServer.documentElements[docId]?.size ?: 0
        val stock = fakeServer.stockMovedByDocument[docId] ?: 0.0
        println(
            "CX-02 LEGACY CONFIRMED_LIMITATION rows=$rows stock=$stock " +
                "elementAddCalls=${fakeServer.elementAddCalls.get()} conductCalls=${fakeServer.conductCalls.get()}"
        )
        assertEquals(
            "LEGACY REST must expose duplicate document rows (do not treat this as uniqueness PASS)",
            2,
            rows
        )
        assertEquals(1, fakeServer.conductCalls.get())
        assertFalse(
            "A single intended line of qty=3 must not remain a consistent unique row; extra row is the confirmed limitation (stockMoved=$stock)",
            rows == 1 && kotlin.math.abs(stock - 3.0) < 0.001
        )
    }

    @Test
    fun `CX-02 SERVER_GUARDED two TSD conducting one document do not duplicate rows or stock`() = runBlocking {
        val docId = 912
        fakeServer.tsdGuardEnabled = true
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)

        val itemA = comingProduct(id = 51, name = "Труба 63", barcode = "4600000000912", qty = 3.0)
        val itemB = comingProduct(id = 51, name = "Труба 63", barcode = "4600000000912", qty = 3.0)
        val (_, serviceA) = newIsolatedClient(fakeServer, "tsd_a_cx02g", BitrixConcurrencyMode.SERVER_GUARDED)
        val (_, serviceB) = newIsolatedClient(fakeServer, "tsd_b_cx02g", BitrixConcurrencyMode.SERVER_GUARDED)

        val jobA = async {
            serviceA.conductDocumentSuspend(
                baseList = emptyList(), idDocument = docId, context = null, documentComment = null,
                deletedProducts = emptyList(), updatedProducts = listOf(itemA),
                currentProductListState = listOf(itemA), productOffersList = listOf(ProductOffer(itemA))
            )
        }
        val jobB = async {
            serviceB.conductDocumentSuspend(
                baseList = emptyList(), idDocument = docId, context = null, documentComment = null,
                deletedProducts = emptyList(), updatedProducts = listOf(itemB),
                currentProductListState = listOf(itemB), productOffersList = listOf(ProductOffer(itemB))
            )
        }
        val rA = jobA.await()
        val rB = jobB.await()
        assertTrue("A=${rA.message}", rA.isSuccess)
        assertTrue("B=${rB.message}", rB.isSuccess)
        assertEquals("Y", fakeServer.documentStatuses[docId])
        assertEquals(1, fakeServer.documentElements[docId]?.size)
        assertEquals(3.0, fakeServer.stockMovedByDocument[docId] ?: 0.0, 0.001)
        assertEquals(1, fakeServer.conductCalls.get())
        assertTrue(rA.reconciledAlreadyConducted || rB.reconciledAlreadyConducted)
    }

    @Test
    fun `CX-03 TSD-B lookup during TSD-A create reuses the winning barcode owner`() = runBlocking {
        fakeServer.seedDraftDocument(821)
        fakeServer.seedDraftDocument(822)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        val barcode = "4600000000821"
        val (repoA, serviceA) = newIsolatedClient(fakeServer, "tsd_a_cx03")
        val (_, serviceB) = newIsolatedClient(fakeServer, "tsd_b_cx03")
        val itemA = comingProduct(51, "Труба 63", barcode, 1.0)

        val first = serviceA.conductDocumentSuspend(
            baseList = emptyList(), idDocument = 821, context = null, documentComment = null,
            deletedProducts = emptyList(), updatedProducts = listOf(itemA),
            currentProductListState = listOf(itemA), productOffersList = listOf(ProductOffer(itemA))
        )
        assertTrue(first.isSuccess)
        val winner = fakeServer.barcodeToOfferId[barcode]
        assertNotNull(winner)

        CatalogReferenceCache.putBarcodeMapping(barcode, 99999)
        val itemB = comingProduct(51, "Труба 63", barcode, 2.0)
        val second = serviceB.conductDocumentSuspend(
            baseList = emptyList(), idDocument = 822, context = null, documentComment = null,
            deletedProducts = emptyList(), updatedProducts = listOf(itemB),
            currentProductListState = listOf(itemB), productOffersList = listOf(ProductOffer(itemB))
        )
        assertTrue("TSD-B should reuse server barcode despite stale cache: ${second.message}", second.isSuccess)
        assertEquals(winner, fakeServer.barcodeToOfferId[barcode])
        assertEquals(1, fakeServer.offerAddCalls.get())
        assertEquals("Y", fakeServer.documentStatuses[822])
        assertEquals(ComingOperationState.COMMITTED, repoA.getDraft(821)?.state)
    }

    @Test
    fun `CX-04 timeout after successful conduct then second TSD retry reconciles without double stock`() = runBlocking {
        val docId = 831
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.commitOnServerBeforeThrowingConductTimeout = true

        val itemA = comingProduct(51, "Труба 63", "4600000000831", 4.0)
        val (_, serviceA) = newIsolatedClient(fakeServer, "tsd_a_cx04")
        val rA = serviceA.conductDocumentSuspend(
            baseList = emptyList(), idDocument = docId, context = null, documentComment = null,
            deletedProducts = emptyList(), updatedProducts = listOf(itemA),
            currentProductListState = listOf(itemA), productOffersList = listOf(ProductOffer(itemA))
        )
        assertTrue("Timeout must reconcile as committed: ${rA.message}", rA.isSuccess)
        assertEquals("Y", fakeServer.documentStatuses[docId])

        val itemB = comingProduct(51, "Труба 63", "4600000000831", 4.0)
        val (_, serviceB) = newIsolatedClient(fakeServer, "tsd_b_cx04")
        val rB = serviceB.conductDocumentSuspend(
            baseList = emptyList(), idDocument = docId, context = null, documentComment = null,
            deletedProducts = emptyList(), updatedProducts = listOf(itemB),
            currentProductListState = listOf(itemB), productOffersList = listOf(ProductOffer(itemB))
        )
        assertTrue(rB.isSuccess)
        assertTrue(rB.reconciledAlreadyConducted)
        assertEquals(1, fakeServer.conductCalls.get())
        assertEquals("Y", fakeServer.documentStatuses[docId])
    }

    @Test
    fun `CX-05 stale catalog cache must not cause foreign overwrite or duplicate barcodes`() = runBlocking {
        val docId = 841
        fakeServer.seedDraftDocument(docId)
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.seedParentProduct(88, "Отвод 90", iblockId = 14, type = 3)
        fakeServer.seedExistingOffer(9001, 88, "Отвод 90 вар.", "4600000000841")
        CatalogReferenceCache.putBarcodeMapping("4600000000841", 51)

        val item = comingProduct(51, "Труба 63", "4600000000841", 1.0)
        val (_, service) = newIsolatedClient(fakeServer, "tsd_a_cx05")
        val result = service.conductDocumentSuspend(
            baseList = emptyList(), idDocument = docId, context = null, documentComment = null,
            deletedProducts = emptyList(), updatedProducts = listOf(item),
            currentProductListState = listOf(item), productOffersList = listOf(ProductOffer(item))
        )
        assertFalse(result.isSuccess)
        assertTrue(result.message.orEmpty().contains("другим товаром") || result.message.orEmpty().contains("другому товару"))
        assertEquals(9001, fakeServer.barcodeToOfferId["4600000000841"])
        assertEquals(0, fakeServer.offerUpdateCalls.get())
        assertEquals("N", fakeServer.documentStatuses[docId])
        assertEquals(0, fakeServer.conductCalls.get())
    }

    @Test
    fun `CX-06 SERVER_GUARDED concurrent add of the same logical row does not duplicate`() = runBlocking {
        fakeServer.tsdGuardEnabled = true
        fakeServer.seedDraftDocument(960)
        fakeServer.seedExistingOffer(432, 51, "Труба 63", "4600000000960")
        val request = TsdDocumentSyncRequest(
            operationId = "op-cx06",
            documentId = 960,
            items = listOf(
                TsdDocumentSyncItem(
                    localItemId = "logical-row-1",
                    offerId = 432,
                    quantity = 4.0,
                    storeTo = 1
                )
            )
        )
        val jobA = async { fakeServer.tsdDocumentSyncAndConduct(request) }
        val jobB = async { fakeServer.tsdDocumentSyncAndConduct(request) }
        val rA = jobA.await()
        val rB = jobB.await()
        assertTrue(rA.isSuccessful)
        assertTrue(rB.isSuccessful)
        assertEquals(1, fakeServer.documentElements[960]?.size)
        assertEquals(4.0, fakeServer.stockMovedByDocument[960] ?: 0.0, 0.001)
        assertEquals(1, fakeServer.conductCalls.get())
        assertEquals("Y", fakeServer.documentStatuses[960])
        val ids = setOf(
            rA.body()?.result?.items?.first()?.elementId,
            rB.body()?.result?.items?.first()?.elementId
        )
        assertEquals(1, ids.size)
    }

    @Test
    fun `CX-07 SERVER_GUARDED keeps two legitimate rows of the same offer`() = runBlocking {
        fakeServer.tsdGuardEnabled = true
        fakeServer.seedDraftDocument(970)
        fakeServer.seedExistingOffer(432, 51, "Труба 63", "4600000000970")
        val result = fakeServer.tsdDocumentSyncAndConduct(
            TsdDocumentSyncRequest(
                operationId = "op-cx07",
                documentId = 970,
                items = listOf(
                    TsdDocumentSyncItem("row-p1", 432, 2.0, 1, "P1"),
                    TsdDocumentSyncItem("row-p2", 432, 5.0, 1, "P2")
                )
            )
        )
        assertTrue(result.isSuccessful)
        assertEquals(2, fakeServer.documentElements[970]?.size)
        assertEquals(7.0, fakeServer.stockMovedByDocument[970] ?: 0.0, 0.001)
        val localIds = fakeServer.documentElements[970].orEmpty().map { it["localItemId"] }.toSet()
        assertEquals(setOf("row-p1", "row-p2"), localIds)
        assertEquals(1, fakeServer.offersById.size)
        assertEquals("Y", fakeServer.documentStatuses[970])
    }

    @Test
    fun `CX-08 SERVER_GUARDED retry with same operationId is idempotent`() = runBlocking {
        fakeServer.tsdGuardEnabled = true
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.seedDraftDocument(980)
        val item = comingProduct(51, "Труба 63", "4600000000980", 3.0)
        val engine = FileBackedDraftStorageEngine(tempFolder.newFolder("tsd_cx08"))
        val repo = ComingDraftRepository(engine)
        val service = CatalogDocumentComingService(
            apiBitrix = fakeServer,
            apiBarcode = fakeServer,
            draftRepository = repo,
            isolateProcessLocks = true,
            concurrencyMode = BitrixConcurrencyMode.SERVER_GUARDED
        )
        val first = service.conductDocumentSuspend(
            baseList = emptyList(), idDocument = 980, context = null, documentComment = null,
            deletedProducts = emptyList(), updatedProducts = listOf(item),
            currentProductListState = listOf(item), productOffersList = listOf(ProductOffer(item))
        )
        assertTrue(first.message, first.isSuccess)
        assertEquals(1, fakeServer.documentElements[980]?.size)
        assertEquals(3.0, fakeServer.stockMovedByDocument[980] ?: 0.0, 0.001)
        val operationId = repo.getDraft(980)!!.operationId
        engine.updateDraftMetadata(980, state = ComingOperationState.PROCESSING)
        val retry = service.conductDocumentSuspend(
            baseList = emptyList(), idDocument = 980, context = null, documentComment = null,
            deletedProducts = emptyList(), updatedProducts = listOf(item),
            currentProductListState = listOf(item), productOffersList = listOf(ProductOffer(item))
        )
        assertTrue(retry.message, retry.isSuccess)
        assertEquals(operationId, retry.operationId ?: repo.getDraft(980)?.operationId)
        assertEquals(1, fakeServer.documentElements[980]?.size)
        assertEquals(3.0, fakeServer.stockMovedByDocument[980] ?: 0.0, 0.001)
        assertEquals(1, fakeServer.conductCalls.get())
        assertEquals(1, fakeServer.offerAddCalls.get())
    }

    @Test
    fun `CX-09 SERVER_GUARDED same operationId with different payload is an explicit conflict`() = runBlocking {
        fakeServer.tsdGuardEnabled = true
        fakeServer.seedDraftDocument(990)
        fakeServer.seedExistingOffer(432, 51, "Труба 63", "4600000000990")
        val first = fakeServer.tsdDocumentSyncAndConduct(
            TsdDocumentSyncRequest(
                operationId = "op-cx09",
                documentId = 990,
                items = listOf(
                    TsdDocumentSyncItem("row-1", 432, 3.0, 1)
                )
            )
        )
        assertTrue(first.isSuccessful)
        assertEquals("Y", fakeServer.documentStatuses[990])
        val second = fakeServer.tsdDocumentSyncAndConduct(
            TsdDocumentSyncRequest(
                operationId = "op-cx09",
                documentId = 990,
                items = listOf(
                    TsdDocumentSyncItem("row-1", 432, 9.0, 1)
                )
            )
        )
        assertEquals(409, second.code())
        assertEquals(1, fakeServer.documentElements[990]?.size)
        assertEquals(3.0, fakeServer.stockMovedByDocument[990] ?: 0.0, 0.001)
        assertEquals(1, fakeServer.conductCalls.get())
    }

    @Test
    fun `CX-10 SERVER_GUARDED unavailable lock API stops safely without REST fallback`() = runBlocking {
        fakeServer.tsdGuardEnabled = false
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.seedDraftDocument(1001)
        val item = comingProduct(51, "Труба 63", "4600000001001", 1.0)
        val (_, service) = newIsolatedClient(fakeServer, "tsd_cx10", BitrixConcurrencyMode.SERVER_GUARDED)
        val result = service.conductDocumentSuspend(
            baseList = emptyList(), idDocument = 1001, context = null, documentComment = null,
            deletedProducts = emptyList(), updatedProducts = listOf(item),
            currentProductListState = listOf(item), productOffersList = listOf(ProductOffer(item))
        )
        assertFalse(result.isSuccess)
        assertTrue(result.message.orEmpty().contains("заблокировано") || result.message.orEmpty().contains("недоступна"))
        assertEquals(0, fakeServer.offerAddCalls.get())
        assertEquals(0, fakeServer.barcodeSetCalls.get())
        assertEquals(0, fakeServer.elementAddCalls.get())
        assertEquals(0, fakeServer.conductCalls.get())
        assertEquals("N", fakeServer.documentStatuses[1001])
        assertTrue("Guarded client must call tsd.variation.resolveOrCreate", fakeServer.tsdVariationCalls.get() >= 1)
    }

    @Test
    fun `CX-11 SERVER_GUARDED timeout after variation create recovers without a second offer`() = runBlocking {
        fakeServer.tsdGuardEnabled = true
        fakeServer.throwAfterTsdVariationCommit = true
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.seedDraftDocument(1011)
        val item = comingProduct(51, "Труба 63", "4600000001011", 2.0)
        val engine = FileBackedDraftStorageEngine(tempFolder.newFolder("tsd_cx11"))
        val repo = ComingDraftRepository(engine)
        val service = CatalogDocumentComingService(
            apiBitrix = fakeServer, apiBarcode = fakeServer, draftRepository = repo,
            isolateProcessLocks = true, concurrencyMode = BitrixConcurrencyMode.SERVER_GUARDED
        )
        val first = service.conductDocumentSuspend(
            baseList = emptyList(), idDocument = 1011, context = null, documentComment = null,
            deletedProducts = emptyList(), updatedProducts = listOf(item),
            currentProductListState = listOf(item), productOffersList = listOf(ProductOffer(item))
        )
        assertFalse(first.isSuccess)
        assertEquals(ComingOperationState.RECONCILIATION_REQUIRED, first.state)
        assertEquals(1, fakeServer.offerAddCalls.get())
        assertEquals(1, fakeServer.offersById.size)
        val retry = service.conductDocumentSuspend(
            baseList = emptyList(), idDocument = 1011, context = null, documentComment = null,
            deletedProducts = emptyList(), updatedProducts = listOf(item),
            currentProductListState = listOf(item), productOffersList = listOf(ProductOffer(item))
        )
        assertTrue(retry.message, retry.isSuccess)
        assertEquals(1, fakeServer.offerAddCalls.get())
        assertEquals(1, fakeServer.offersById.size)
        assertEquals(1, fakeServer.documentElements[1011]?.size)
        assertEquals(2.0, fakeServer.stockMovedByDocument[1011] ?: 0.0, 0.001)
    }

    @Test
    fun `CX-12 SERVER_GUARDED timeout after row add recovers without duplicate stock`() = runBlocking {
        fakeServer.tsdGuardEnabled = true
        fakeServer.throwAfterTsdDocumentRowCommit = true
        fakeServer.seedParentProduct(51, "Труба 63", iblockId = 14, type = 3)
        fakeServer.seedDraftDocument(1021)
        val item = comingProduct(51, "Труба 63", "4600000001021", 8.0)
        val engine = FileBackedDraftStorageEngine(tempFolder.newFolder("tsd_cx12"))
        val repo = ComingDraftRepository(engine)
        val service = CatalogDocumentComingService(
            apiBitrix = fakeServer, apiBarcode = fakeServer, draftRepository = repo,
            isolateProcessLocks = true, concurrencyMode = BitrixConcurrencyMode.SERVER_GUARDED
        )
        val first = service.conductDocumentSuspend(
            baseList = emptyList(), idDocument = 1021, context = null, documentComment = null,
            deletedProducts = emptyList(), updatedProducts = listOf(item),
            currentProductListState = listOf(item), productOffersList = listOf(ProductOffer(item))
        )
        assertFalse(first.isSuccess)
        assertEquals(ComingOperationState.RECONCILIATION_REQUIRED, first.state)
        assertEquals(1, fakeServer.documentElements[1021]?.size)
        assertEquals(null, fakeServer.stockMovedByDocument[1021])
        val retry = service.conductDocumentSuspend(
            baseList = emptyList(), idDocument = 1021, context = null, documentComment = null,
            deletedProducts = emptyList(), updatedProducts = listOf(item),
            currentProductListState = listOf(item), productOffersList = listOf(ProductOffer(item))
        )
        assertTrue(retry.message, retry.isSuccess)
        assertEquals(1, fakeServer.documentElements[1021]?.size)
        assertEquals(8.0, fakeServer.stockMovedByDocument[1021] ?: 0.0, 0.001)
        assertEquals(1, fakeServer.conductCalls.get())
        assertEquals(1, fakeServer.offerAddCalls.get())
    }

    private fun newIsolatedClient(
        server: FakeBitrixPortal,
        folder: String,
        concurrencyMode: BitrixConcurrencyMode = BitrixConcurrencyMode.LEGACY
    ): Pair<ComingDraftRepository, CatalogDocumentComingService> {
        val engine = FileBackedDraftStorageEngine(tempFolder.newFolder(folder))
        val repo = ComingDraftRepository(engine)
        val service = CatalogDocumentComingService(
            apiBitrix = server,
            apiBarcode = server,
            draftRepository = repo,
            isolateProcessLocks = true,
            concurrencyMode = concurrencyMode
        )
        return repo to service
    }

    private fun comingProduct(
        id: Int,
        name: String,
        barcode: String?,
        qty: Double = 1.0
    ): Product {
        return Product(
            idInDocument = null,
            id = id,
            iblockId = 14,
            name = name,
            measureId = 1,
            measureSymbol = "м",
            quantity = qty,
            barcode = barcode,
            diameter = 76.0,
            weight = 12.0,
            height = 4.0,
            parentId = null,
            storeAmount = null
        )
    }

    /**
     * Stateful in-memory fake of Bitrix24 REST endpoints used by incoming warehouse operations.
     */
    private class FakeBitrixPortal : ApiBitrix {
        val documentStatuses = ConcurrentHashMap<Int, String>()
        val parentProducts = ConcurrentHashMap<Int, LinkedTreeMap<String, Any?>>()
        val offersById = ConcurrentHashMap<Int, LinkedTreeMap<String, Any?>>()
        val barcodeToOfferId = ConcurrentHashMap<String, Int>()
        val documentElements = ConcurrentHashMap<Int, MutableList<LinkedTreeMap<String, Any?>>>()

        private val nextOfferId = AtomicInteger(5000)
        private val nextDocElementId = AtomicInteger(8000)

        val offerAddCalls = AtomicInteger(0)
        val offerUpdateCalls = AtomicInteger(0)
        val barcodeSetCalls = AtomicInteger(0)
        val elementAddCalls = AtomicInteger(0)
        val conductCalls = AtomicInteger(0)

        @Volatile
        var artificialConductDelayMs: Long = 0L

        @Volatile
        var commitOnServerBeforeThrowingConductTimeout: Boolean = false

        @Volatile
        var failNextConductResponse: Boolean = false

        @Volatile
        var omitDocumentsKeyInList: Boolean = false

        @Volatile
        var omitDocumentsKeyInListAfterConduct: Boolean = false

        @Volatile
        var throwOnNextBarcodeSet: Throwable? = null

        @Volatile
        var throwAfterBindingBarcode: Boolean = false

        @Volatile
        var throwAfterAddingElement: Boolean = false

        @Volatile
        var failBarcodeLookupIfAlreadyBound: Boolean = false

        @Volatile
        var lookupRaceParticipants: Int = 0
        private val lookupEntered = AtomicInteger(0)

        @Volatile
        var offerAddRaceParticipants: Int = 0
        private val offerAddEntered = AtomicInteger(0)

        @Volatile
        var elementAddRaceParticipants: Int = 0
        private val elementAddEntered = AtomicInteger(0)

        @Volatile
        var elementListRaceParticipants: Int = 0
        private val elementListEntered = AtomicInteger(0)

        /**
         * When true, `tsd.*` methods implement portal GET_LOCK semantics.
         * Default false: SERVER_GUARDED clients receive HTTP 404 (no silent REST fallback).
         */
        @Volatile
        var tsdGuardEnabled: Boolean = false

        @Volatile
        var throwOnNextTsdVariation: Boolean = false

        @Volatile
        var throwAfterTsdVariationCommit: Boolean = false

        @Volatile
        var throwAfterTsdDocumentRowCommit: Boolean = false

        @Volatile
        var throwOnNextTsdDocument: Boolean = false

        val tsdVariationCalls = AtomicInteger(0)
        val tsdDocumentCalls = AtomicInteger(0)
        val stockByOfferId = ConcurrentHashMap<Int, Double>()
        val stockMovedByDocument = ConcurrentHashMap<Int, Double>()

        private val tsdOps = ConcurrentHashMap<String, TsdOpRecord>()
        private val barcodeGuard = Any()
        private val documentGuard = Any()
        private val statusLock = Any()

        private data class TsdOpRecord(
            val key: String,
            val payload: String,
            var state: String,
            var offerId: Int? = null,
            var documentResult: TsdDocumentSyncResponse.Result? = null
        )

        fun seedDraftDocument(docId: Int, status: String = "N") {
            documentStatuses[docId] = status
            documentElements.putIfAbsent(docId, Collections.synchronizedList(mutableListOf()))
        }

        fun seedParentProduct(id: Int, name: String, iblockId: Int = 14, type: Int = 3, measure: Int = 1) {
            val map = LinkedTreeMap<String, Any?>().apply {
                put("id", id.toDouble())
                put("iblockId", iblockId.toDouble())
                put("name", name)
                put("type", type.toDouble())
                put("measure", measure.toDouble())
            }
            parentProducts[id] = map
        }

        fun seedExistingOffer(offerId: Int, parentId: Int, name: String, barcode: String) {
            val map = LinkedTreeMap<String, Any?>().apply {
                put("id", offerId.toDouble())
                put("iblockId", 15.0)
                put("name", name)
                put("parentId", LinkedTreeMap<String, Any?>().apply { put("value", parentId.toDouble()) })
            }
            offersById[offerId] = map
            barcodeToOfferId[barcode] = offerId
        }

        override suspend fun getDocumentListSuspend(request: CatalogDocumentListRequest): Response<CatalogDocumentListResponse> {
            if (omitDocumentsKeyInList) {
                return Response.success(CatalogDocumentListResponse(hashMapOf()))
            }
            val idFilter = request.filter["id"]
            val statusFilter = request.filter["status"] as? String
            val matchingIds = when (idFilter) {
                is Number -> listOf(idFilter.toInt()).filter { documentStatuses.containsKey(it) }
                is Collection<*> -> idFilter.mapNotNull { (it as? Number)?.toInt() }.filter { documentStatuses.containsKey(it) }
                else -> documentStatuses.keys().toList()
            }.filter { docId ->
                statusFilter == null || documentStatuses[docId] == statusFilter
            }

            val docs = matchingIds.map { docId ->
                LinkedTreeMap<String, Any?>().apply {
                    put("id", docId.toDouble())
                    put("docType", "A")
                    put("status", documentStatuses[docId])
                    put("title", "Приход №$docId")
                    put("currency", "BYN")
                }
            }
            return Response.success(CatalogDocumentListResponse(hashMapOf("documents" to docs)))
        }

        private fun copyTreeMap(src: Map<String, Any?>): LinkedTreeMap<String, Any?> {
            return LinkedTreeMap<String, Any?>().apply { putAll(src) }
        }

        override suspend fun getDocumentProducts(list: CatalogDocumentElementListRequest): Response<CatalogDocumentElementListResponse> {
            val raceN = elementListRaceParticipants
            if (raceN > 0) {
                elementListEntered.incrementAndGet()
                val deadline = System.currentTimeMillis() + 1500L
                while (elementListEntered.get() < raceN && System.currentTimeMillis() < deadline) {
                    delay(5L)
                }
            }
            val docId = (list.filter["docId"] as? Number)?.toInt() ?: 0
            val rows = documentElements[docId]?.map { copyTreeMap(it) } ?: emptyList()
            return Response.success(CatalogDocumentElementListResponse(hashMapOf("documentElements" to rows)))
        }

        override suspend fun addVariationsOfProduct(request: ProductOfferRequest): Response<ProductOfferResponse> {
            val raceN = offerAddRaceParticipants
            if (raceN > 0) {
                offerAddEntered.incrementAndGet()
                val deadline = System.currentTimeMillis() + 1500L
                while (offerAddEntered.get() < raceN && System.currentTimeMillis() < deadline) {
                    delay(5L)
                }
            }
            offerAddCalls.incrementAndGet()
            val newId = nextOfferId.incrementAndGet()
            val parentId = (request.fields["parentId"] as? Number)?.toInt() ?: 0
            val name = request.fields["name"]?.toString() ?: ""
            val map = LinkedTreeMap<String, Any?>().apply {
                put("id", newId.toDouble())
                put("iblockId", 15.0)
                put("name", name)
                put("parentId", LinkedTreeMap<String, Any?>().apply { put("value", parentId.toDouble()) })
            }
            offersById[newId] = map
            val offerResp = LinkedTreeMap<String, Any>().apply { put("id", newId.toDouble()) }
            return Response.success(ProductOfferResponse(result = hashMapOf("offer" to offerResp)))
        }

        override suspend fun getVariations(request: VariationsRequest): Response<VariationsResponse> {
            return Response.success(VariationsResponse(VariationsResponse.Result(emptyList())))
        }

        override suspend fun getProducts(request: ProductRequest): Response<ProductResponse> {
            val idFilter = request.filter?.get("id")
            val requestedIds: Set<Int>? = when (idFilter) {
                is Collection<*> -> idFilter.mapNotNull { (it as? Number)?.toInt() }.toSet()
                is Number -> setOf(idFilter.toInt())
                else -> null
            }
            val list = parentProducts.entries
                .filter { requestedIds == null || it.key in requestedIds }
                .map { copyTreeMap(it.value) }
            return Response.success(ProductResponse(result = hashMapOf("products" to list), next = null))
        }

        override suspend fun getProductIdByBarcode(barcode: String): Response<ProductBarcodeResponse> {
            if (failBarcodeLookupIfAlreadyBound && barcodeToOfferId.containsKey(barcode)) {
                failBarcodeLookupIfAlreadyBound = false
                throw java.net.SocketTimeoutException("barcode lookup after bind")
            }
            val raceN = lookupRaceParticipants
            if (raceN > 0) {
                lookupEntered.incrementAndGet()
                val deadline = System.currentTimeMillis() + 1500L
                while (lookupEntered.get() < raceN && System.currentTimeMillis() < deadline) {
                    delay(5L)
                }
            }
            val found = barcodeToOfferId[barcode]
            return Response.success(ProductBarcodeResponse(found?.toString() ?: ""))
        }

        override suspend fun setBarcodeByProductId(request: BarcodeRequest): Response<BarcodeResponse> {
            barcodeSetCalls.incrementAndGet()
            throwOnNextBarcodeSet?.let { error ->
                throwOnNextBarcodeSet = null
                throw error
            }
            val existing = barcodeToOfferId.putIfAbsent(request.barcode, request.productid)
            val bound = existing == null || existing == request.productid
            if (throwAfterBindingBarcode && bound) {
                throwAfterBindingBarcode = false
                throw java.net.SocketTimeoutException("timeout after barcode.setbyproductid")
            }
            return if (bound) {
                Response.success(BarcodeResponse(true))
            } else {
                Response.success(BarcodeResponse(false))
            }
        }

        override suspend fun getProductById(request: ProductIdRequest): Response<ProductResponse> {
            val id = request.id.toIntOrNull() ?: 0
            val found = offersById[id] ?: parentProducts[id]
                ?: return Response.error(404, ResponseBody.create(MediaType.parse("application/json"), "{}"))
            return Response.success(ProductResponse(result = hashMapOf<String, Any>("product" to copyTreeMap(found)), next = null))
        }

        override suspend fun conductDocument(request: ConductDocumentRequest): Response<HashMap<String, Any?>> {
            conductCalls.incrementAndGet()
            if (artificialConductDelayMs > 0) {
                delay(artificialConductDelayMs)
            }
            if (commitOnServerBeforeThrowingConductTimeout) {
                commitOnServerBeforeThrowingConductTimeout = false
                synchronized(statusLock) {
                    documentStatuses[request.id] = "Y"
                }
                throw SocketTimeoutException("Simulated read timeout after server committed")
            }
            if (failNextConductResponse) {
                failNextConductResponse = false
                return Response.success(
                    hashMapOf<String, Any?>(
                        "error" to "CONDUCT_ERR",
                        "error_description" to "Simulated Bitrix conduct rejection"
                    )
                )
            }
            val alreadyConducted = synchronized(statusLock) {
                if (documentStatuses[request.id] == "Y") {
                    true
                } else {
                    documentStatuses[request.id] = "Y"
                    applyStockForDocumentLocked(request.id)
                    false
                }
            }
            if (alreadyConducted) {
                return Response.success(
                    hashMapOf<String, Any?>(
                        "error" to "DOCUMENT_ALREADY_CONDUCTED",
                        "error_description" to "Document already conducted"
                    )
                )
            }
            if (omitDocumentsKeyInListAfterConduct) {
                omitDocumentsKeyInListAfterConduct = false
                omitDocumentsKeyInList = true
            }
            return Response.success(hashMapOf<String, Any?>("result" to true))
        }

        override suspend fun updateDocumentElement(request: UpdatedDocumentElementsRequest): Response<HashMap<String, Any?>> {
            val docId = (request.fields?.get("docId") as? Number)?.toInt() ?: 0
            val amount = request.fields?.get("amount")?.toString()?.toDoubleOrNull() ?: 0.0
            documentElements[docId]?.firstOrNull { (it["id"] as? Number)?.toInt() == request.id }?.let {
                it["amount"] = amount
            }
            return Response.success(hashMapOf<String, Any?>("result" to true))
        }

        override suspend fun deleteDocumentElement(request: DeletedDocumentElementRequest): Response<HashMap<String, Any?>> {
            val docId = (request.fields?.get("docId") as? Number)?.toInt() ?: 0
            documentElements[docId]?.removeAll { (it["id"] as? Number)?.toInt() == request.id }
            return Response.success(hashMapOf<String, Any?>("result" to true))
        }

        override suspend fun updateProduct(request: UpdateProductRequest): Response<HashMap<String, Any?>> {
            return Response.success(hashMapOf<String, Any?>("result" to true))
        }

        override suspend fun updateProductOffer(request: UpdateProductRequest): Response<HashMap<String, Any?>> {
            offerUpdateCalls.incrementAndGet()
            return Response.success(hashMapOf<String, Any?>("result" to true))
        }

        override suspend fun addDocumentElement(request: AddDocumentElementRequest): Response<HashMap<String, Any?>> {
            val raceN = elementAddRaceParticipants
            if (raceN > 0) {
                elementAddEntered.incrementAndGet()
                val deadline = System.currentTimeMillis() + 1500L
                while (elementAddEntered.get() < raceN && System.currentTimeMillis() < deadline) {
                    delay(5L)
                }
            }
            elementAddCalls.incrementAndGet()
            val docId = (request.fields?.get("docId") as? Number)?.toInt() ?: 0
            val elementId = (request.fields?.get("elementId") as? Number)?.toInt() ?: 0
            val storeTo = (request.fields?.get("storeTo") as? Number)?.toInt() ?: 1
            val amount = request.fields?.get("amount")?.toString()?.toDoubleOrNull() ?: 0.0
            val newId = nextDocElementId.incrementAndGet()

            val row = LinkedTreeMap<String, Any?>().apply {
                put("id", newId.toDouble())
                put("docId", docId.toDouble())
                put("elementId", elementId.toDouble())
                put("storeFrom", 0.0)
                put("storeTo", storeTo.toDouble())
                put("amount", amount)
            }
            documentElements.getOrPut(docId) { Collections.synchronizedList(mutableListOf()) }.add(row)

            if (throwAfterAddingElement) {
                throwAfterAddingElement = false
                throw java.net.SocketTimeoutException("timeout after catalog.document.element.add")
            }

            val docEl = LinkedTreeMap<String, Any?>().apply { put("id", newId.toDouble()) }
            return Response.success(hashMapOf<String, Any?>("result" to hashMapOf<String, Any?>("documentElement" to docEl)))
        }

        override suspend fun getStoreList(): Response<HashMap<String, Any?>> =
            Response.success(hashMapOf("result" to hashMapOf("stores" to emptyList<Any>())))

        override suspend fun addNewDocument(request: NewDocumentRequest): Response<HashMap<String, Any?>> =
            Response.success(hashMapOf("result" to true))

        override fun getPasswords(request: PasswordRequest): Call<PasswordResponse> =
            throw UnsupportedOperationException()

        override suspend fun getStoreAmount(request: StoreAmountRequest): Response<StoreAmountResponse> =
            throw UnsupportedOperationException()

        override suspend fun addProvider(request: ProviderRequest): Response<HashMap<String, Any?>> =
            Response.success(hashMapOf("result" to true))

        override suspend fun getProviders(request: GettingProvidersRequest): GettingProvidersResponse =
            throw UnsupportedOperationException()

        override suspend fun getProcesses(request: ProcessesRequest): ProcessesResponse =
            throw UnsupportedOperationException()

        override suspend fun updateProcesses(request: ProcessUpdateRequest) {}

        override suspend fun updateComment(request: UpdateCommentRequest) {}

        override suspend fun getDocumentsNomenclature(request: NomenclatureRequest): Response<NomenclatureResponse> =
            Response.success(NomenclatureResponse(null))

        override suspend fun getDocumentSgp(request: SgpRequest): Response<SgpResponse> =
            throw UnsupportedOperationException()

        override suspend fun getFirstBlanks(request: BlankRequest): FirstBlankResponse =
            throw UnsupportedOperationException()

        override suspend fun getSecondBlanks(request: BlankRequest): SecondBlankResponse =
            throw UnsupportedOperationException()

        override suspend fun tsdVariationResolveOrCreate(
            request: TsdVariationResolveRequest
        ): Response<TsdVariationResolveResponse> {
            tsdVariationCalls.incrementAndGet()
            if (!tsdGuardEnabled) {
                return Response.error(404, jsonError("NOT_FOUND", "tsd.variation.resolveOrCreate missing"))
            }
            if (throwOnNextTsdVariation) {
                throwOnNextTsdVariation = false
                throw SocketTimeoutException("timeout before tsd.variation.resolveOrCreate")
            }
            val barcode = request.barcode?.trim().orEmpty()
            val payload = "${request.parentProductId}|$barcode|${request.localItemId}"
            val opKey = "V:${request.operationId}:${request.localItemId}"
            synchronized(barcodeGuard) {
                val existingOp = tsdOps[opKey]
                if (existingOp != null && existingOp.state == "COMMITTED") {
                    if (existingOp.payload != payload) {
                        return conflict("OPERATION_PAYLOAD_CONFLICT", "operationId reused with different variation payload")
                    }
                    return Response.success(
                        TsdVariationResolveResponse(
                            result = TsdVariationResolveResponse.Result(
                                offerId = existingOp.offerId ?: 0,
                                barcode = barcode,
                                created = false,
                                operationId = request.operationId
                            )
                        )
                    )
                }
                tsdOps[opKey] = TsdOpRecord(opKey, payload, "STARTED")
                if (barcode.isNotEmpty()) {
                    val bound = barcodeToOfferId[barcode]
                    if (bound != null) {
                        val parent = parentIdOfOffer(bound)
                        if (parent != null && parent != request.parentProductId) {
                            tsdOps.remove(opKey)
                            return conflict("BARCODE_FOREIGN_CONFLICT", "barcode owned by parent $parent")
                        }
                        tsdOps[opKey] = TsdOpRecord(opKey, payload, "COMMITTED", offerId = bound)
                        return Response.success(
                            TsdVariationResolveResponse(
                                result = TsdVariationResolveResponse.Result(
                                    offerId = bound,
                                    barcode = barcode,
                                    created = false,
                                    operationId = request.operationId
                                )
                            )
                        )
                    }
                }
                val created = createOfferLocked(request.parentProductId, request.offerFields["name"]?.toString().orEmpty())
                if (barcode.isNotEmpty()) {
                    barcodeToOfferId.putIfAbsent(barcode, created)
                    val winner = barcodeToOfferId[barcode]!!
                    tsdOps[opKey] = TsdOpRecord(opKey, payload, "COMMITTED", offerId = winner)
                    if (throwAfterTsdVariationCommit) {
                        throwAfterTsdVariationCommit = false
                        throw SocketTimeoutException("timeout after tsd.variation.resolveOrCreate committed")
                    }
                    return Response.success(
                        TsdVariationResolveResponse(
                            result = TsdVariationResolveResponse.Result(
                                offerId = winner,
                                barcode = barcode,
                                created = winner == created,
                                operationId = request.operationId
                            )
                        )
                    )
                }
                tsdOps[opKey] = TsdOpRecord(opKey, payload, "COMMITTED", offerId = created)
                return Response.success(
                    TsdVariationResolveResponse(
                        result = TsdVariationResolveResponse.Result(
                            offerId = created,
                            barcode = barcode,
                            created = true,
                            operationId = request.operationId
                        )
                    )
                )
            }
        }

        override suspend fun tsdDocumentSyncAndConduct(
            request: TsdDocumentSyncRequest
        ): Response<TsdDocumentSyncResponse> {
            tsdDocumentCalls.incrementAndGet()
            if (!tsdGuardEnabled) {
                return Response.error(404, jsonError("NOT_FOUND", "tsd.document.syncAndConduct missing"))
            }
            if (throwOnNextTsdDocument) {
                throwOnNextTsdDocument = false
                throw SocketTimeoutException("timeout before tsd.document.syncAndConduct")
            }
            val payload = request.items.sortedBy { it.localItemId }.joinToString(";") {
                "${it.localItemId}:${it.offerId}:${it.quantity}:${it.storeTo}:${it.batchNumber.orEmpty()}"
            }
            val opKey = "D:${request.operationId}:${request.documentId}"
            synchronized(documentGuard) {
                val existingOp = tsdOps[opKey]
                if (existingOp?.state == "COMMITTED" && existingOp.documentResult != null) {
                    if (existingOp.payload != payload) {
                        return conflictDoc("OPERATION_PAYLOAD_CONFLICT", "operationId reused with different document payload")
                    }
                    return Response.success(TsdDocumentSyncResponse(result = existingOp.documentResult))
                }
                tsdOps[opKey] = TsdOpRecord(opKey, payload, "STARTED")
                if (documentStatuses[request.documentId] == "Y") {
                    val already = buildDocumentResult(request, alreadyConducted = true)
                    tsdOps[opKey] = TsdOpRecord(opKey, payload, "COMMITTED", documentResult = already)
                    return Response.success(TsdDocumentSyncResponse(result = already))
                }
                request.deletedRemoteElementIds.forEach { delId ->
                    documentElements[request.documentId]?.removeAll { (it["id"] as? Number)?.toInt() == delId }
                }
                val rows = documentElements.getOrPut(request.documentId) {
                    Collections.synchronizedList(mutableListOf())
                }
                val syncedItems = mutableListOf<TsdDocumentSyncResponse.SyncedItem>()
                val elementIds = mutableListOf<Int>()
                for (item in request.items) {
                    val existingRow = rows.firstOrNull { row ->
                        row["localItemId"] == item.localItemId && row["operationId"] == request.operationId
                    }
                    if (existingRow != null) {
                        val rowId = (existingRow["id"] as Number).toInt()
                        existingRow["amount"] = item.quantity
                        existingRow["storeTo"] = item.storeTo.toDouble()
                        existingRow["elementId"] = item.offerId.toDouble()
                        if (item.batchNumber != null) existingRow["batchNumber"] = item.batchNumber
                        elementIds.add(rowId)
                        syncedItems.add(
                            TsdDocumentSyncResponse.SyncedItem(
                                localItemId = item.localItemId,
                                offerId = item.offerId,
                                elementId = rowId,
                                quantity = item.quantity
                            )
                        )
                        continue
                    }
                    val newId = nextDocElementId.incrementAndGet()
                    elementAddCalls.incrementAndGet()
                    val row = LinkedTreeMap<String, Any?>().apply {
                        put("id", newId.toDouble())
                        put("docId", request.documentId.toDouble())
                        put("elementId", item.offerId.toDouble())
                        put("storeFrom", 0.0)
                        put("storeTo", item.storeTo.toDouble())
                        put("amount", item.quantity)
                        put("localItemId", item.localItemId)
                        put("operationId", request.operationId)
                        put("batchNumber", item.batchNumber)
                    }
                    rows.add(row)
                    if (throwAfterTsdDocumentRowCommit) {
                        throwAfterTsdDocumentRowCommit = false
                        throw SocketTimeoutException("timeout after tsd document row add")
                    }
                    elementIds.add(newId)
                    syncedItems.add(
                        TsdDocumentSyncResponse.SyncedItem(
                            localItemId = item.localItemId,
                            offerId = item.offerId,
                            elementId = newId,
                            quantity = item.quantity
                        )
                    )
                }
                synchronized(statusLock) {
                    if (documentStatuses[request.documentId] != "Y") {
                        documentStatuses[request.documentId] = "Y"
                        applyStockForDocumentLocked(request.documentId)
                        conductCalls.incrementAndGet()
                    }
                }
                val result = TsdDocumentSyncResponse.Result(
                    documentId = request.documentId,
                    status = "Y",
                    alreadyConducted = false,
                    elementIds = elementIds,
                    items = syncedItems,
                    operationId = request.operationId
                )
                tsdOps[opKey] = TsdOpRecord(opKey, payload, "COMMITTED", documentResult = result)
                return Response.success(TsdDocumentSyncResponse(result = result))
            }
        }

        private fun applyStockForDocumentLocked(docId: Int) {
            if (stockMovedByDocument.containsKey(docId)) return
            var total = 0.0
            documentElements[docId]?.forEach { row ->
                val offerId = (row["elementId"] as? Number)?.toInt() ?: return@forEach
                val amount = when (val raw = row["amount"]) {
                    is Number -> raw.toDouble()
                    else -> raw?.toString()?.toDoubleOrNull() ?: 0.0
                }
                stockByOfferId.merge(offerId, amount) { a, b -> a + b }
                total += amount
            }
            stockMovedByDocument[docId] = total
        }

        private fun createOfferLocked(parentId: Int, name: String): Int {
            offerAddCalls.incrementAndGet()
            val newId = nextOfferId.incrementAndGet()
            val map = LinkedTreeMap<String, Any?>().apply {
                put("id", newId.toDouble())
                put("iblockId", 15.0)
                put("name", name)
                put("parentId", LinkedTreeMap<String, Any?>().apply { put("value", parentId.toDouble()) })
            }
            offersById[newId] = map
            return newId
        }

        private fun parentIdOfOffer(offerId: Int): Int? {
            val offer = offersById[offerId] ?: return null
            return ((offer["parentId"] as? LinkedTreeMap<*, *>)?.get("value") as? Number)?.toInt()
        }

        private fun jsonError(code: String, description: String): ResponseBody {
            return ResponseBody.create(
                MediaType.parse("application/json"),
                """{"error":"$code","error_description":"$description"}"""
            )
        }

        private fun <T> conflict(code: String, description: String): Response<T> {
            return Response.error(409, jsonError(code, description))
        }

        private fun conflictDoc(code: String, description: String): Response<TsdDocumentSyncResponse> {
            return Response.error(409, jsonError(code, description))
        }

        private fun buildDocumentResult(
            request: TsdDocumentSyncRequest,
            alreadyConducted: Boolean
        ): TsdDocumentSyncResponse.Result {
            val rows = documentElements[request.documentId].orEmpty()
            val items = request.items.map { item ->
                val row = rows.firstOrNull { it["localItemId"] == item.localItemId }
                val rowId = (row?.get("id") as? Number)?.toInt() ?: 0
                TsdDocumentSyncResponse.SyncedItem(
                    localItemId = item.localItemId,
                    offerId = item.offerId,
                    elementId = rowId,
                    quantity = item.quantity
                )
            }
            return TsdDocumentSyncResponse.Result(
                documentId = request.documentId,
                status = documentStatuses[request.documentId] ?: "Y",
                alreadyConducted = alreadyConducted,
                elementIds = items.map { it.elementId }.filter { it > 0 },
                items = items,
                operationId = request.operationId
            )
        }
    }

    private class FailingSnapshotEngine(
        private val delegate: DraftStorageEngine
    ) : DraftStorageEngine by delegate {
        @Volatile
        var failNextSaveFull: Boolean = false

        override fun saveFullComingDraft(record: com.example.scannerkotlin.model.ComingDocumentDraftRecord) {
            if (failNextSaveFull) {
                throw RuntimeException("Simulated SQLite disk I/O error")
            }
            delegate.saveFullComingDraft(record)
        }
    }
}
