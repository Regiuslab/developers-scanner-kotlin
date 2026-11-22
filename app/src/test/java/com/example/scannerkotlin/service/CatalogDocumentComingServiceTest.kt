package com.example.scannerkotlin.service

import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.cache.CatalogReferenceCache
import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.ProductOffer
import com.example.scannerkotlin.persistence.ComingDraftRepository
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

        fun seedDraftDocument(docId: Int, status: String = "N") {
            documentStatuses[docId] = status
            documentElements.putIfAbsent(docId, mutableListOf())
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
            val docId = (list.filter["docId"] as? Number)?.toInt() ?: 0
            val rows = documentElements[docId]?.map { copyTreeMap(it) } ?: emptyList()
            return Response.success(CatalogDocumentElementListResponse(hashMapOf("documentElements" to rows)))
        }

        override suspend fun addVariationsOfProduct(request: ProductOfferRequest): Response<ProductOfferResponse> {
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
            val found = barcodeToOfferId[barcode]
            return Response.success(ProductBarcodeResponse(found?.toString() ?: ""))
        }

        override suspend fun setBarcodeByProductId(request: BarcodeRequest): Response<BarcodeResponse> {
            barcodeSetCalls.incrementAndGet()
            val existing = barcodeToOfferId.putIfAbsent(request.barcode, request.productid)
            return if (existing == null || existing == request.productid) {
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
                documentStatuses[request.id] = "Y"
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
            documentStatuses[request.id] = "Y"
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
            documentElements.getOrPut(docId) { mutableListOf() }.add(row)

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
    }
}
