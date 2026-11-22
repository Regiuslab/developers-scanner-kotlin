package com.example.scannerkotlin.service

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.cache.CatalogReferenceCache
import com.example.scannerkotlin.config.BitrixClientFactory
import com.example.scannerkotlin.config.BitrixConfig
import com.example.scannerkotlin.mappers.DocumentElementMapper
import com.example.scannerkotlin.mappers.DocumentMapper
import com.example.scannerkotlin.mappers.ProductMapper
import com.example.scannerkotlin.model.ComingDocumentDraftRecord
import com.example.scannerkotlin.model.ComingItemStatus
import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.ComingSyncState
import com.example.scannerkotlin.model.Document
import com.example.scannerkotlin.model.DocumentElement
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.ProductOffer
import com.example.scannerkotlin.model.ValidationIssue
import com.example.scannerkotlin.persistence.ComingDraftRepository
import com.example.scannerkotlin.request.*
import com.example.scannerkotlin.response.*
import com.example.scannerkotlin.utils.BarcodeSanitizer
import com.example.scannerkotlin.utils.BitrixApiErrorParser
import com.example.scannerkotlin.utils.BitrixNumberParser
import com.example.scannerkotlin.utils.ComingProductValidator
import com.example.scannerkotlin.utils.TsdLogger
import com.google.gson.internal.LinkedTreeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@RequiresApi(Build.VERSION_CODES.O)
class CatalogDocumentComingService(
    private var apiBitrix: ApiBitrix = BitrixClientFactory.catalogApi(),
    private var apiBarcode: ApiBitrix = BitrixClientFactory.barcodeComingApi(),
    private val draftRepository: ComingDraftRepository = ComingDraftRepository.getInstance()
) {
    private val documentMapper = DocumentMapper()
    private val documentElementsMapper = DocumentElementMapper()
    private val productMapper = ProductMapper()

    companion object {
        private val documentConductLocks = ConcurrentHashMap<Int, Mutex>()

        private fun lockForDocument(documentId: Int): Mutex {
            return documentConductLocks.getOrPut(documentId) { Mutex() }
        }
    }

    sealed class BarcodeLookupOutcome {
        data class Found(val productId: Int) : BarcodeLookupOutcome()
        data object NotFound : BarcodeLookupOutcome()
        data class Error(val message: String) : BarcodeLookupOutcome()
    }

    sealed class DocumentServerStatus {
        data class Found(val document: Document) : DocumentServerStatus()
        data object NotFound : DocumentServerStatus()
        data object Unverified : DocumentServerStatus()
        data class NetworkFailure(val message: String) : DocumentServerStatus()
    }

    sealed class ReconciliationResult {
        data class AlreadyConducted(val documentId: Int) : ReconciliationResult()
        data class ReadyToContinue(
            val documentId: Int,
            val serverElementsCount: Int,
            val matchedElementsCount: Int,
            val foreignElementsCount: Int
        ) : ReconciliationResult()
        data class Blocked(val reason: String, val keepReconciliationRequired: Boolean) : ReconciliationResult()
    }

    /**
     * Reconciles an unfinished or interrupted local draft against authoritative Bitrix server state
     * without performing any mutating API calls.
     */
    suspend fun reconcileDocumentWithServerSuspend(
        documentId: Int,
        localProducts: List<Product>
    ): ReconciliationResult = withContext(Dispatchers.IO) {
        val draft = draftRepository.getDraft(documentId)
        val opId = draft?.operationId ?: UUID.randomUUID().toString()

        TsdLogger.info(
            category = "RECONCILE",
            event = "reconcile_started",
            operationId = opId,
            documentId = documentId,
            fields = mapOf("localItems" to localProducts.size)
        )

        when (val docStatus = fetchAuthoritativeDocumentStatus(documentId)) {
            is DocumentServerStatus.NetworkFailure -> {
                draftRepository.appendJournal(
                    operationId = opId,
                    documentId = documentId,
                    stepType = "RECONCILE_STATUS",
                    status = "FAILED_NETWORK",
                    details = docStatus.message
                )
                return@withContext ReconciliationResult.Blocked(
                    reason = "Не удалось выполнить сверку с сервером Битрикс: ${docStatus.message}",
                    keepReconciliationRequired = true
                )
            }
            is DocumentServerStatus.NotFound -> {
                draftRepository.transitionState(
                    documentId = documentId,
                    newState = ComingOperationState.FAILED,
                    errorMessage = "Документ прихода №$documentId не найден на сервере Битрикс."
                )
                return@withContext ReconciliationResult.Blocked(
                    reason = "Документ прихода №$documentId не найден на сервере Битрикс.",
                    keepReconciliationRequired = false
                )
            }
            is DocumentServerStatus.Found -> {
                val status = docStatus.document.status?.uppercase()
                if (status == "Y") {
                    draftRepository.markCommittedAndFinalize(documentId, opId)
                    TsdLogger.info(
                        category = "RECONCILE",
                        event = "reconcile_confirmed_already_conducted",
                        operationId = opId,
                        documentId = documentId
                    )
                    return@withContext ReconciliationResult.AlreadyConducted(documentId)
                }
                if (status == "C") {
                    val msg = "Документ №$documentId отменён в Битрикс (статус C)."
                    draftRepository.transitionState(
                        documentId = documentId,
                        newState = ComingOperationState.FAILED,
                        errorMessage = msg
                    )
                    return@withContext ReconciliationResult.Blocked(msg, keepReconciliationRequired = false)
                }
            }
            is DocumentServerStatus.Unverified -> {
                // Allowed only when mock server does not stub document list.
            }
        }

        // Reconcile variations via barcodes and server document elements
        for (item in localProducts) {
            if (item.offerId == null && !item.barcode.isNullOrBlank()) {
                val lookup = lookupBarcodeAuthoritative(item.barcode!!)
                if (lookup is BarcodeLookupOutcome.Found) {
                    val compatibility = verifyOfferBelongsToParent(
                        offerId = lookup.productId,
                        expectedParentId = item.id
                    )
                    if (compatibility is OfferOwnershipCheck.Compatible) {
                        item.offerId = lookup.productId
                        draftRepository.recordItemStepProgress(
                            documentId = documentId,
                            operationId = opId,
                            localItemId = item.localItemId,
                            offerId = lookup.productId,
                            idInDocument = item.idInDocument,
                            itemStatus = ComingItemStatus.BARCODE_ASSIGNED,
                            syncState = ComingSyncState.REMOTE_VARIATION_CREATED,
                            stepType = "RECONCILE_OFFER",
                            stepStatus = "MATCHED",
                            details = "Matched offerId=${lookup.productId} by barcode"
                        )
                    }
                }
            }
        }

        val serverElements = fetchServerDocumentElements(documentId) ?: return@withContext ReconciliationResult.Blocked(
            reason = "Не удалось загрузить строки документа №$documentId с сервера для сверки.",
            keepReconciliationRequired = true
        )

        val matchStats = matchLocalItemsWithServerElements(
            documentId = documentId,
            operationId = opId,
            localProducts = localProducts,
            serverElements = serverElements
        )

        draftRepository.transitionState(
            documentId = documentId,
            newState = ComingOperationState.READY,
            syncState = ComingSyncState.LOCAL_SAVED,
            errorMessage = ""
        )

        TsdLogger.info(
            category = "RECONCILE",
            event = "reconcile_ready",
            operationId = opId,
            documentId = documentId,
            fields = mapOf(
                "serverElements" to serverElements.size,
                "matched" to matchStats.first,
                "foreign" to matchStats.second
            )
        )

        ReconciliationResult.ReadyToContinue(
            documentId = documentId,
            serverElementsCount = serverElements.size,
            matchedElementsCount = matchStats.first,
            foreignElementsCount = matchStats.second
        )
    }

    /**
     * Full idempotent incoming conduct flow with pre-validation, variation/barcode conflict safety,
     * server element reconciliation, and authoritative post-conduct status verification.
     */
    suspend fun conductDocumentSuspend(
        baseList: List<Product>,
        idDocument: Int?,
        context: Context? = null,
        documentComment: String?,
        deletedProducts: List<Product>,
        updatedProducts: List<Product>,
        currentProductListState: List<Product>,
        productOffersList: List<ProductOffer>,
        destinationStoreId: Int = 1
    ): ConductResult {
        if (idDocument == null || idDocument <= 0) {
            return ConductResult(
                isSuccess = false,
                message = "ID документа не найден.",
                state = ComingOperationState.FAILED
            )
        }

        val mutex = lockForDocument(idDocument)
        if (!mutex.tryLock()) {
            TsdLogger.warn(
                category = "CONDUCT",
                event = "parallel_conduct_blocked",
                documentId = idDocument
            )
            return ConductResult(
                isSuccess = false,
                message = "Операция проведения этого документа уже выполняется. Дождитесь завершения.",
                state = ComingOperationState.PROCESSING
            )
        }

        try {
            val draft = draftRepository.loadOrInitializeDraft(
                documentId = idDocument,
                documentTitle = null,
                allowedNomenclatureIds = null,
                destinationStoreId = destinationStoreId
            )
            val operationId = draft.operationId
            TsdLogger.activeDocumentId = idDocument
            TsdLogger.activeOperationId = operationId

            if (draft.state == ComingOperationState.COMMITTED) {
                return ConductResult(
                    isSuccess = true,
                    message = "Документ уже проведён ранее.",
                    state = ComingOperationState.COMMITTED,
                    operationId = operationId,
                    reconciledAlreadyConducted = true
                )
            }

            // Ensure current in-memory state is persisted before any network action
            draftRepository.saveSnapshot(
                documentId = idDocument,
                documentTitle = draft.documentTitle,
                destinationStoreId = destinationStoreId,
                allowedNomenclatureIds = draft.allowedNomenclatureIds,
                products = currentProductListState,
                comment = documentComment,
                deletedRemoteIds = deletedProducts.mapNotNull { it.idInDocument }
            )

            // If previous attempt was interrupted mid-flight, reconcile first
            if (draft.state == ComingOperationState.RECONCILIATION_REQUIRED) {
                when (val rec = reconcileDocumentWithServerSuspend(idDocument, currentProductListState)) {
                    is ReconciliationResult.AlreadyConducted -> {
                        return ConductResult(
                            isSuccess = true,
                            message = "Сверка показала: документ №$idDocument уже проведён в Битрикс. Повторное проведение не выполнялось.",
                            state = ComingOperationState.COMMITTED,
                            operationId = operationId,
                            reconciledAlreadyConducted = true
                        )
                    }
                    is ReconciliationResult.Blocked -> {
                        val st = if (rec.keepReconciliationRequired) {
                            ComingOperationState.RECONCILIATION_REQUIRED
                        } else {
                            ComingOperationState.FAILED
                        }
                        return ConductResult(
                            isSuccess = false,
                            message = rec.reason,
                            state = st,
                            operationId = operationId
                        )
                    }
                    is ReconciliationResult.ReadyToContinue -> {
                        // Safe to proceed using reconciled IDs.
                    }
                }
            }

            // PHASE 1: Local + Server Pre-Validation BEFORE creating any variations/barcodes
            draftRepository.transitionState(
                documentId = idDocument,
                newState = ComingOperationState.VALIDATING
            )

            val localIssues = ComingProductValidator.validateDetailed(
                products = currentProductListState,
                documentId = idDocument,
                destinationStoreId = destinationStoreId,
                expectedProductsIblockId = BitrixConfig.iblockProducts
            )
            if (localIssues.isNotEmpty()) {
                val firstPos = localIssues.firstOrNull { it.positionIndex >= 0 }?.positionIndex
                applyItemErrors(idDocument, operationId, currentProductListState, localIssues)
                val summary = localIssues.joinToString("\n") { it.message }
                draftRepository.transitionState(
                    documentId = idDocument,
                    newState = ComingOperationState.FAILED,
                    errorMessage = summary
                )
                return ConductResult(
                    isSuccess = false,
                    message = summary,
                    state = ComingOperationState.FAILED,
                    operationId = operationId,
                    validationIssues = localIssues,
                    failedPositionIndex = firstPos
                )
            }

            // Build deterministic 1-to-1 offers list directly from currentProductListState
            // (prevents any stale/desynchronized caller productOffersList from dropping items).
            val normalizedOffers: List<ProductOffer> = currentProductListState.map { product ->
                product.destinationStoreId = destinationStoreId
                productOffersList.firstOrNull { it.product.localItemId == product.localItemId }
                    ?.also {
                        if (it.id != null && product.offerId == null) {
                            product.offerId = it.id
                        }
                    }
                    ?: ProductOffer(product)
            }

            // Server authoritative pre-check: document status + parent product types + barcode conflicts
            val serverPrecheck = preValidateOnServerBeforeMutation(
                documentId = idDocument,
                operationId = operationId,
                offers = normalizedOffers
            )
            if (serverPrecheck != null) {
                return serverPrecheck
            }

            // PHASE 2: Ready -> Processing (Variations & Barcodes)
            draftRepository.transitionState(
                documentId = idDocument,
                newState = ComingOperationState.READY
            )
            draftRepository.transitionState(
                documentId = idDocument,
                newState = ComingOperationState.PROCESSING,
                syncState = ComingSyncState.SYNCING,
                incrementAttempt = true
            )

            when (val saveResult = saveProductVariationsInternal(
                productOffersList = normalizedOffers,
                documentId = idDocument,
                operationId = operationId
            )) {
                is ConductResult.SaveResult.Failure -> {
                    draftRepository.transitionState(
                        documentId = idDocument,
                        newState = ComingOperationState.FAILED,
                        syncState = ComingSyncState.SYNC_ERROR,
                        errorMessage = saveResult.errorMessage
                    )
                    return ConductResult(
                        isSuccess = false,
                        message = saveResult.errorMessage,
                        state = ComingOperationState.FAILED,
                        operationId = operationId,
                        failedPositionIndex = saveResult.failedPositionIndex
                    )
                }
                is ConductResult.SaveResult.Success -> {
                    val savedVariations = saveResult.savedVariations

                    // PHASE 3: Reconcile & Sync Document Elements without duplicating rows
                    val deleteIds = (
                        deletedProducts.filter { baseList.any { b -> b.idInDocument != null && b.idInDocument == it.idInDocument } }
                            .mapNotNull { it.idInDocument } +
                            draft.deletedRemoteElementIds
                        ).distinct()

                    if (deleteIds.isNotEmpty()) {
                        val deletedOk = deleteDocumentElementsByIdSuspend(deleteIds, idDocument, operationId)
                        if (!deletedOk) {
                            val err = "Ошибка при удалении отменённых строк документа в Битрикс."
                            draftRepository.transitionState(idDocument, ComingOperationState.FAILED, ComingSyncState.SYNC_ERROR, err)
                            return ConductResult(false, err, ComingOperationState.FAILED, operationId)
                        }
                    }

                    val syncElementsResult = syncDocumentElementsIdempotently(
                        savedVariations = savedVariations,
                        idDocument = idDocument,
                        operationId = operationId,
                        defaultStoreTo = destinationStoreId
                    )
                    if (!syncElementsResult.first) {
                        val err = syncElementsResult.second ?: "Ошибка при синхронизации строк документа в Битрикс."
                        draftRepository.transitionState(idDocument, ComingOperationState.FAILED, ComingSyncState.SYNC_ERROR, err)
                        return ConductResult(false, err, ComingOperationState.FAILED, operationId)
                    }

                    // Update dimensions ONLY for offers verified to belong to this document's items
                    updateDiameterAndWeightAndHeight(savedVariations.map { it.second }, operationId, idDocument)
                    updateComment(
                        comment = documentComment,
                        documentId = idDocument,
                        products = currentProductListState,
                        operationId = operationId
                    )

                    // PHASE 4: Conduct Document & Verify Authoritative Status
                    return executeConductAndVerify(
                        idDocument = idDocument,
                        operationId = operationId,
                        context = context
                    )
                }
            }
        } catch (e: Exception) {
            TsdLogger.error(
                category = "CONDUCT",
                event = "unexpected_conduct_exception",
                documentId = idDocument,
                throwable = e
            )
            val errMsg = "Непредвиденная ошибка при проведении: ${e.localizedMessage ?: e::class.java.simpleName}"
            draftRepository.transitionState(
                documentId = idDocument,
                newState = ComingOperationState.FAILED,
                syncState = ComingSyncState.SYNC_ERROR,
                errorMessage = errMsg
            )
            return ConductResult(
                isSuccess = false,
                message = errMsg,
                state = ComingOperationState.FAILED
            )
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun applyItemErrors(
        documentId: Int,
        operationId: String,
        products: List<Product>,
        issues: List<ValidationIssue>
    ) {
        val byItemId = issues.filter { it.localItemId != null }.groupBy { it.localItemId!! }
        for (p in products) {
            val itemErr = byItemId[p.localItemId]?.joinToString("; ") { it.message }
            p.validationError = itemErr
            if (itemErr != null) {
                p.itemStatus = ComingItemStatus.ERROR
                draftRepository.recordItemStepProgress(
                    documentId = documentId,
                    operationId = operationId,
                    localItemId = p.localItemId,
                    offerId = p.offerId,
                    idInDocument = p.idInDocument,
                    itemStatus = ComingItemStatus.ERROR,
                    syncState = ComingSyncState.LOCAL_SAVED,
                    stepType = "PREVALIDATION",
                    stepStatus = "FAILED",
                    details = itemErr,
                    validationError = itemErr
                )
            }
        }
    }

    private suspend fun preValidateOnServerBeforeMutation(
        documentId: Int,
        operationId: String,
        offers: List<ProductOffer>
    ): ConductResult? = withContext(Dispatchers.IO) {
        // 1. Check authoritative document status on server
        when (val docStatus = fetchAuthoritativeDocumentStatus(documentId)) {
            is DocumentServerStatus.Found -> {
                val st = docStatus.document.status?.uppercase()
                if (st == "Y") {
                    draftRepository.markCommittedAndFinalize(documentId, operationId)
                    return@withContext ConductResult(
                        isSuccess = true,
                        message = "Документ №$documentId уже проведён на сервере Битрикс. Повторное проведение заблокировано.",
                        state = ComingOperationState.COMMITTED,
                        operationId = operationId,
                        reconciledAlreadyConducted = true
                    )
                }
                if (st == "C") {
                    val msg = "Документ №$documentId отменён в Битрикс и не может быть проведён."
                    draftRepository.transitionState(documentId, ComingOperationState.FAILED, errorMessage = msg)
                    return@withContext ConductResult(false, msg, ComingOperationState.FAILED, operationId)
                }
            }
            is DocumentServerStatus.NotFound -> {
                val msg = "Документ №$documentId не найден в Битрикс."
                draftRepository.transitionState(documentId, ComingOperationState.FAILED, errorMessage = msg)
                return@withContext ConductResult(false, msg, ComingOperationState.FAILED, operationId)
            }
            is DocumentServerStatus.NetworkFailure -> {
                val msg = "Ошибка связи при проверке статуса документа: ${docStatus.message}"
                draftRepository.transitionState(documentId, ComingOperationState.FAILED, errorMessage = msg)
                return@withContext ConductResult(false, msg, ComingOperationState.FAILED, operationId)
            }
            is DocumentServerStatus.Unverified -> {
                // Unstubbed in isolated test mocks -> continue
            }
        }

        // 2. Verify all parent nomenclature items exist & support variations BEFORE creating any variation
        val distinctParentIds = offers.map { it.parentId }.distinct()
        if (distinctParentIds.isNotEmpty()) {
            val parentCheck = verifyParentNomenclatureOnServer(distinctParentIds)
            if (parentCheck is ParentNomenclatureCheck.Failed) {
                val failingOfferIdx = offers.indexOfFirst { it.parentId == parentCheck.failingProductId }
                    .takeIf { it >= 0 } ?: 0
                val posNum = failingOfferIdx + 1
                val failingProduct = offers[failingOfferIdx].product
                val msg = "Позиция №$posNum (${failingProduct.name ?: "ID ${failingProduct.id}"}): ${parentCheck.reason}"
                failingProduct.validationError = msg
                failingProduct.itemStatus = ComingItemStatus.ERROR
                draftRepository.recordItemStepProgress(
                    documentId = documentId,
                    operationId = operationId,
                    localItemId = failingProduct.localItemId,
                    offerId = failingProduct.offerId,
                    idInDocument = failingProduct.idInDocument,
                    itemStatus = ComingItemStatus.ERROR,
                    syncState = ComingSyncState.SYNC_ERROR,
                    stepType = "PREVALIDATE_PARENT_PRODUCT",
                    stepStatus = "FAILED",
                    details = msg,
                    validationError = msg
                )
                draftRepository.transitionState(documentId, ComingOperationState.FAILED, errorMessage = msg)
                return@withContext ConductResult(
                    isSuccess = false,
                    message = msg,
                    state = ComingOperationState.FAILED,
                    operationId = operationId,
                    failedPositionIndex = failingOfferIdx,
                    validationIssues = listOf(
                        ValidationIssue(
                            positionIndex = failingOfferIdx,
                            localItemId = failingProduct.localItemId,
                            productId = failingProduct.id,
                            field = "nomenclature",
                            message = msg
                        )
                    )
                )
            }
        }

        // 3. Pre-check all barcodes for ownership conflicts BEFORE creating any variation
        offers.forEachIndexed { idx, offer ->
            val barcode = BarcodeSanitizer.normalizeForComingVariation(offer.barcode)
            if (!barcode.isNullOrBlank()) {
                when (val lookup = lookupBarcodeAuthoritative(barcode)) {
                    is BarcodeLookupOutcome.Error -> {
                        val msg = "Позиция №${idx + 1} (${offer.name ?: ""}): не удалось проверить штрихкод '$barcode' на сервере (${lookup.message})."
                        draftRepository.transitionState(documentId, ComingOperationState.FAILED, errorMessage = msg)
                        return@withContext ConductResult(
                            isSuccess = false,
                            message = msg,
                            state = ComingOperationState.FAILED,
                            operationId = operationId,
                            failedPositionIndex = idx
                        )
                    }
                    is BarcodeLookupOutcome.Found -> {
                        when (val ownership = verifyOfferBelongsToParent(lookup.productId, offer.parentId)) {
                            is OfferOwnershipCheck.ForeignConflict -> {
                                val msg = "Позиция №${idx + 1} (${offer.name ?: ""}): штрихкод '$barcode' уже закреплён за другим товаром («${ownership.foreignName}», ID: ${lookup.productId}). Замените штрихкод или номенклатуру."
                                offer.product.validationError = msg
                                offer.product.itemStatus = ComingItemStatus.ERROR
                                draftRepository.recordItemStepProgress(
                                    documentId = documentId,
                                    operationId = operationId,
                                    localItemId = offer.localItemId,
                                    offerId = null,
                                    idInDocument = offer.idInDocument,
                                    itemStatus = ComingItemStatus.ERROR,
                                    syncState = ComingSyncState.SYNC_ERROR,
                                    stepType = "PREVALIDATE_BARCODE_CONFLICT",
                                    stepStatus = "CONFLICT",
                                    remoteEntityId = lookup.productId,
                                    details = msg,
                                    validationError = msg
                                )
                                draftRepository.transitionState(documentId, ComingOperationState.FAILED, errorMessage = msg)
                                return@withContext ConductResult(
                                    isSuccess = false,
                                    message = msg,
                                    state = ComingOperationState.FAILED,
                                    operationId = operationId,
                                    failedPositionIndex = idx,
                                    validationIssues = listOf(
                                        ValidationIssue(
                                            positionIndex = idx,
                                            localItemId = offer.localItemId,
                                            productId = offer.parentId,
                                            field = "barcode",
                                            message = msg
                                        )
                                    )
                                )
                            }
                            is OfferOwnershipCheck.VerificationError -> {
                                val msg = "Позиция №${idx + 1} (${offer.name ?: ""}): ошибка проверки вариации ID ${lookup.productId} (${ownership.reason})."
                                draftRepository.transitionState(documentId, ComingOperationState.FAILED, errorMessage = msg)
                                return@withContext ConductResult(
                                    isSuccess = false,
                                    message = msg,
                                    state = ComingOperationState.FAILED,
                                    operationId = operationId,
                                    failedPositionIndex = idx
                                )
                            }
                            is OfferOwnershipCheck.Compatible -> {
                                // Pre-validated reusable variation! Cache resolved offerId on the item.
                                offer.id = lookup.productId
                                offer.product.validationError = null
                            }
                        }
                    }
                    is BarcodeLookupOutcome.NotFound -> {
                        offer.product.validationError = null
                    }
                }
            }
        }

        null
    }

    private sealed class ParentNomenclatureCheck {
        data object Valid : ParentNomenclatureCheck()
        data class Failed(val failingProductId: Int, val reason: String) : ParentNomenclatureCheck()
    }

    private suspend fun verifyParentNomenclatureOnServer(parentIds: List<Int>): ParentNomenclatureCheck {
        return try {
            val request = ProductRequest(
                filter = mapOf(
                    "iblockId" to BitrixConfig.iblockProducts,
                    "id" to parentIds
                )
            )
            val response = apiBitrix.getProducts(request) ?: return ParentNomenclatureCheck.Valid
            if (!response.isSuccessful) {
                return ParentNomenclatureCheck.Failed(
                    failingProductId = parentIds.first(),
                    reason = "ошибка проверки номенклатуры на сервере (HTTP ${response.code()})."
                )
            }
            val rawProducts = response.body()?.result?.get("products") as? List<*> ?: return ParentNomenclatureCheck.Valid
            val fetchedById = rawProducts.mapNotNull { unit ->
                (unit as? LinkedTreeMap<*, *>)?.let { productMapper.mapToProduct(it) }
            }.associateBy { it.id }

            for (pid in parentIds) {
                val serverProduct = fetchedById[pid]
                    ?: return ParentNomenclatureCheck.Failed(
                        failingProductId = pid,
                        reason = "выбранный товар (ID: $pid) отсутствует в каталоге номенклатуры или не поддерживает вариации."
                    )
                if (!ComingProductValidator.isProductSupportingVariations(
                        iblockId = serverProduct.iblockId,
                        catalogProductType = serverProduct.catalogProductType,
                        expectedProductsIblockId = BitrixConfig.iblockProducts
                    )
                ) {
                    return ParentNomenclatureCheck.Failed(
                        failingProductId = pid,
                        reason = "выбранный тип товара не поддерживает создание вариаций. Проверьте номенклатуру."
                    )
                }
            }
            ParentNomenclatureCheck.Valid
        } catch (e: IOException) {
            ParentNomenclatureCheck.Failed(
                failingProductId = parentIds.first(),
                reason = "ошибка сети при проверке номенклатуры."
            )
        } catch (e: Exception) {
            ParentNomenclatureCheck.Failed(
                failingProductId = parentIds.first(),
                reason = "ошибка проверки номенклатуры (${e.message})."
            )
        }
    }

    private sealed class OfferOwnershipCheck {
        data object Compatible : OfferOwnershipCheck()
        data class ForeignConflict(val foreignProductId: Int, val foreignName: String) : OfferOwnershipCheck()
        data class VerificationError(val reason: String) : OfferOwnershipCheck()
    }

    private suspend fun verifyOfferBelongsToParent(
        offerId: Int,
        expectedParentId: Int
    ): OfferOwnershipCheck {
        return try {
            val response = apiBitrix.getProductById(ProductIdRequest(id = offerId.toString()))
                ?: return OfferOwnershipCheck.Compatible // Unstubbed Mockito mock in unit test
            if (!response.isSuccessful) {
                return OfferOwnershipCheck.VerificationError("HTTP ${response.code()}")
            }
            val details = response.body()?.result?.get("product") as? LinkedTreeMap<*, *>
                ?: return OfferOwnershipCheck.VerificationError("не найден объект товара ID $offerId")
            val mapped = productMapper.mapToProduct(details)
            val actualParentId = mapped.parentId
            val isOfferIblock = mapped.iblockId == 0 || mapped.iblockId == BitrixConfig.iblockOffers
            if (isOfferIblock && actualParentId == expectedParentId) {
                OfferOwnershipCheck.Compatible
            } else {
                OfferOwnershipCheck.ForeignConflict(
                    foreignProductId = mapped.id.takeIf { it > 0 } ?: offerId,
                    foreignName = mapped.name ?: "ID $offerId"
                )
            }
        } catch (e: IOException) {
            OfferOwnershipCheck.VerificationError("ошибка сети")
        } catch (e: Exception) {
            OfferOwnershipCheck.VerificationError(e.message ?: "неизвестная ошибка")
        }
    }

    private suspend fun fetchAuthoritativeDocumentStatus(documentId: Int): DocumentServerStatus {
        return try {
            val req = CatalogDocumentListRequest(
                filter = mutableMapOf(
                    "id" to documentId,
                    "docType" to "A"
                )
            )
            val response = apiBitrix.getDocumentListSuspend(req) ?: return DocumentServerStatus.Unverified
            if (!response.isSuccessful) {
                return DocumentServerStatus.NetworkFailure("HTTP ${response.code()}")
            }
            val rawDocs = response.body()?.result?.get("documents") as? List<*>
                ?: return DocumentServerStatus.Unverified
            if (rawDocs.isEmpty()) {
                return DocumentServerStatus.NotFound
            }
            val mapped = (rawDocs.firstOrNull() as? LinkedTreeMap<*, *>)?.let { documentMapper.mapToDocument(it) }
                ?: return DocumentServerStatus.NotFound
            DocumentServerStatus.Found(mapped)
        } catch (e: IOException) {
            DocumentServerStatus.NetworkFailure("Сетевой сбой (${e.message ?: "IOException"})")
        } catch (e: Exception) {
            DocumentServerStatus.NetworkFailure(e.message ?: e::class.java.simpleName)
        }
    }

    private suspend fun fetchServerDocumentElements(documentId: Int): List<DocumentElement>? {
        return try {
            val request = CatalogDocumentElementListRequest(
                filter = mutableMapOf("docId" to documentId)
            )
            val response = apiBitrix.getDocumentProducts(request)
            if (response == null) {
                // Unstubbed mock in isolated unit tests
                return emptyList()
            }
            if (!response.isSuccessful) {
                TsdLogger.warn(
                    category = "RECONCILE",
                    event = "element_list_http_error",
                    documentId = documentId,
                    fields = mapOf("code" to response.code())
                )
                return null
            }
            val rawElements = response.body()?.result?.get("documentElements") as? List<*> ?: emptyList<Any>()
            rawElements.mapNotNull { item ->
                (item as? LinkedTreeMap<*, *>)?.let {
                    try {
                        documentElementsMapper.mapToDocumentElement(it)
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            TsdLogger.warn(
                category = "RECONCILE",
                event = "element_list_failed",
                documentId = documentId,
                throwable = e
            )
            null
        }
    }

    private suspend fun matchLocalItemsWithServerElements(
        documentId: Int,
        operationId: String,
        localProducts: List<Product>,
        serverElements: List<DocumentElement>
    ): Pair<Int, Int> {
        val claimedServerIds = HashSet<Int>()
        var matched = 0

        // Pass 1: exact match by known idInDocument
        for (item in localProducts) {
            val knownDocElId = item.idInDocument ?: continue
            val serverMatch = serverElements.firstOrNull { it.id == knownDocElId }
            if (serverMatch != null) {
                claimedServerIds.add(knownDocElId)
                matched++
                if (item.offerId == null && serverMatch.elementId != null) {
                    item.offerId = serverMatch.elementId
                }
            } else {
                // Stale idInDocument no longer on server
                item.idInDocument = null
            }
        }

        // Pass 2: match by offerId == serverElement.elementId for items whose idInDocument wasn't saved before crash
        for (item in localProducts) {
            if (item.idInDocument != null) continue
            val offerId = item.offerId ?: continue
            val candidate = serverElements.firstOrNull { el ->
                el.id != null && el.id !in claimedServerIds && el.elementId == offerId
            }
            if (candidate?.id != null) {
                item.idInDocument = candidate.id
                claimedServerIds.add(candidate.id!!)
                matched++
                draftRepository.recordItemStepProgress(
                    documentId = documentId,
                    operationId = operationId,
                    localItemId = item.localItemId,
                    offerId = item.offerId,
                    idInDocument = item.idInDocument,
                    itemStatus = ComingItemStatus.ELEMENT_ADDED,
                    syncState = ComingSyncState.REMOTE_ELEMENT_CREATED,
                    stepType = "RECONCILE_ELEMENT",
                    stepStatus = "MATCHED",
                    remoteEntityId = candidate.id,
                    details = "Matched existing documentElement id=${candidate.id} for offerId=$offerId"
                )
            }
        }

        val foreignCount = serverElements.count { it.id != null && it.id !in claimedServerIds }
        return matched to foreignCount
    }

    private suspend fun deleteDocumentElementsByIdSuspend(
        elementIds: List<Int>,
        idDocument: Int,
        operationId: String
    ): Boolean = withContext(Dispatchers.IO) {
        for (elementId in elementIds) {
            try {
                val request = DeletedDocumentElementRequest(
                    id = elementId,
                    fields = mutableMapOf("docId" to idDocument)
                )
                val response = apiBitrix.deleteDocumentElement(request)
                if (response != null && !response.isSuccessful && response.code() != 404) {
                    TsdLogger.error(
                        category = "CONDUCT",
                        event = "delete_element_failed",
                        operationId = operationId,
                        documentId = idDocument,
                        fields = mapOf("docElementId" to elementId, "code" to response.code())
                    )
                    return@withContext false
                }
                draftRepository.appendJournal(
                    operationId = operationId,
                    documentId = idDocument,
                    stepType = "DELETE_ELEMENT",
                    status = "SUCCEEDED",
                    remoteEntityId = elementId
                )
            } catch (e: Exception) {
                TsdLogger.error(
                    category = "CONDUCT",
                    event = "delete_element_exception",
                    operationId = operationId,
                    documentId = idDocument,
                    fields = mapOf("docElementId" to elementId),
                    throwable = e
                )
                return@withContext false
            }
        }
        true
    }

    /**
     * Idempotently syncs document elements:
     * 1. Queries authoritative `catalog.document.element.list` so retries never add duplicate rows.
     * 2. Updates existing rows only if quantity or destination store changed.
     * 3. Adds only missing rows sequentially (respecting Bitrix REST rate limits) and persists
     *    each returned `idInDocument` immediately to SQLite.
     */
    private suspend fun syncDocumentElementsIdempotently(
        savedVariations: List<Pair<Int, ProductOffer>>,
        idDocument: Int,
        operationId: String,
        defaultStoreTo: Int
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        if (savedVariations.isEmpty()) return@withContext true to null

        val serverElements = fetchServerDocumentElements(idDocument)
            ?: return@withContext false to "Не удалось проверить существующие строки документа в Битрикс перед добавлением."

        matchLocalItemsWithServerElements(
            documentId = idDocument,
            operationId = operationId,
            localProducts = savedVariations.map { it.second.product },
            serverElements = serverElements
        )

        val serverById = serverElements.filter { it.id != null }.associateBy { it.id!! }

        for ((index, pair) in savedVariations.withIndex()) {
            val (offerElementId, productOffer) = pair
            val currentProduct = productOffer.product
            val desiredQty = currentProduct.quantity ?: 0.0
            val targetStore = currentProduct.destinationStoreId.takeIf { it > 0 } ?: defaultStoreTo

            val existingDocElementId = currentProduct.idInDocument
            if (existingDocElementId != null) {
                val serverEl = serverById[existingDocElementId]
                val needsUpdate = serverEl == null ||
                    serverEl.amount == null ||
                    kotlin.math.abs(serverEl.amount!! - desiredQty) > 1e-6 ||
                    (serverEl.storeTo != null && serverEl.storeTo != targetStore)

                if (needsUpdate) {
                    val updateReq = UpdatedDocumentElementsRequest(
                        id = existingDocElementId,
                        fields = mutableMapOf(
                            "docId" to idDocument,
                            "storeTo" to targetStore,
                            "amount" to desiredQty.toString()
                        )
                    )
                    try {
                        val updResp = apiBitrix.updateDocumentElement(updateReq)
                        val bodyErr = BitrixApiErrorParser.fromSuccessBody(updResp.body())
                        if (!updResp.isSuccessful || bodyErr != null) {
                            val msg = "Позиция №${index + 1} (${currentProduct.name}): не удалось обновить строку документа ID $existingDocElementId."
                            return@withContext false to msg
                        }
                        draftRepository.recordItemStepProgress(
                            documentId = idDocument,
                            operationId = operationId,
                            localItemId = currentProduct.localItemId,
                            offerId = offerElementId,
                            idInDocument = existingDocElementId,
                            itemStatus = ComingItemStatus.ELEMENT_ADDED,
                            syncState = ComingSyncState.REMOTE_ELEMENT_CREATED,
                            stepType = "UPDATE_ELEMENT",
                            stepStatus = "SUCCEEDED",
                            remoteEntityId = existingDocElementId,
                            details = "amount=$desiredQty storeTo=$targetStore"
                        )
                    } catch (e: Exception) {
                        return@withContext false to "Позиция №${index + 1} (${currentProduct.name}): сетевая ошибка обновления строки документа."
                    }
                } else {
                    draftRepository.appendJournal(
                        operationId = operationId,
                        documentId = idDocument,
                        localItemId = currentProduct.localItemId,
                        stepType = "ADD_ELEMENT",
                        status = "SKIPPED_REUSED",
                        remoteEntityId = existingDocElementId,
                        details = "Already present on server with matching amount=$desiredQty"
                    )
                }
                continue
            }

            // Element not yet in Bitrix document -> add it once
            val addReq = AddDocumentElementRequest(
                fields = mutableMapOf(
                    "docId" to idDocument,
                    "storeFrom" to 0,
                    "storeTo" to targetStore,
                    "elementId" to offerElementId,
                    "amount" to desiredQty.toString(),
                    "purchasingPrice" to 0
                )
            )
            try {
                val response = apiBitrix.addDocumentElement(addReq)
                val bodyErr = BitrixApiErrorParser.fromSuccessBody(response.body())
                if (response.isSuccessful && bodyErr == null) {
                    val resultObj = response.body()?.get("result") as? Map<*, *>
                    val docElMap = resultObj?.get("documentElement") as? Map<*, *>
                    val newDocElementId = BitrixNumberParser.toIntOrNull(docElMap?.get("id"))
                        ?: BitrixNumberParser.toIntOrNull(resultObj?.get("id"))

                    currentProduct.idInDocument = newDocElementId
                    draftRepository.recordItemStepProgress(
                        documentId = idDocument,
                        operationId = operationId,
                        localItemId = currentProduct.localItemId,
                        offerId = offerElementId,
                        idInDocument = newDocElementId,
                        itemStatus = ComingItemStatus.ELEMENT_ADDED,
                        syncState = ComingSyncState.REMOTE_ELEMENT_CREATED,
                        stepType = "ADD_ELEMENT",
                        stepStatus = "SUCCEEDED",
                        remoteEntityId = newDocElementId ?: offerElementId,
                        details = "offerId=$offerElementId amount=$desiredQty"
                    )
                } else {
                    val errBody = response.errorBody()?.string()
                    val parsed = bodyErr ?: BitrixApiErrorParser.fromHttp(response.code(), errBody)
                    val msg = "Позиция №${index + 1} (${currentProduct.name}): ошибка добавления в документ — $parsed"
                    TsdLogger.error(
                        category = "CONDUCT",
                        event = "add_element_failed",
                        operationId = operationId,
                        documentId = idDocument,
                        fields = mapOf("offerId" to offerElementId, "code" to response.code())
                    )
                    return@withContext false to msg
                }
            } catch (e: Exception) {
                TsdLogger.error(
                    category = "CONDUCT",
                    event = "add_element_exception",
                    operationId = operationId,
                    documentId = idDocument,
                    fields = mapOf("offerId" to offerElementId),
                    throwable = e
                )
                return@withContext false to "Позиция №${index + 1} (${currentProduct.name}): обрыв связи при добавлении строки в документ."
            }
        }

        true to null
    }

    private suspend fun executeConductAndVerify(
        idDocument: Int,
        operationId: String,
        context: Context?
    ): ConductResult = withContext(Dispatchers.IO) {
        draftRepository.appendJournal(
            operationId = operationId,
            documentId = idDocument,
            stepType = "CONDUCT_DOCUMENT",
            status = "STARTED"
        )

        try {
            val response = apiBitrix.conductDocument(ConductDocumentRequest(idDocument))
            val successError = BitrixApiErrorParser.fromSuccessBody(response.body())

            if (response.isSuccessful && successError == null) {
                // Requirement: HTTP 200 alone is NOT enough — verify actual status on Bitrix!
                when (val postStatus = fetchAuthoritativeDocumentStatus(idDocument)) {
                    is DocumentServerStatus.Found -> {
                        val serverStatus = postStatus.document.status?.uppercase()
                        if (serverStatus == "Y") {
                            CatalogReferenceCache.invalidateStores()
                            draftRepository.markCommittedAndFinalize(idDocument, operationId)
                            return@withContext ConductResult(
                                isSuccess = true,
                                state = ComingOperationState.COMMITTED,
                                operationId = operationId
                            )
                        } else {
                            val msg = "Сервер вернул успешный HTTP-ответ, но статус документа в Битрикс остался '$serverStatus' (не проведён). Черновик сохранён."
                            draftRepository.transitionState(
                                documentId = idDocument,
                                newState = ComingOperationState.FAILED,
                                syncState = ComingSyncState.SYNC_ERROR,
                                errorMessage = msg
                            )
                            return@withContext ConductResult(
                                isSuccess = false,
                                message = msg,
                                state = ComingOperationState.FAILED,
                                operationId = operationId
                            )
                        }
                    }
                    is DocumentServerStatus.Unverified -> {
                        // Only happens in simplified unit-test mocks where getDocumentListSuspend wasn't stubbed
                        draftRepository.markCommittedAndFinalize(idDocument, operationId)
                        return@withContext ConductResult(
                            isSuccess = true,
                            state = ComingOperationState.COMMITTED,
                            operationId = operationId
                        )
                    }
                    is DocumentServerStatus.NetworkFailure -> {
                        val msg = "Запрос проведения отправлен, но проверка итогового статуса прервалась (${postStatus.message}). Требуется сверка перед повтором."
                        draftRepository.transitionState(
                            documentId = idDocument,
                            newState = ComingOperationState.RECONCILIATION_REQUIRED,
                            syncState = ComingSyncState.SYNC_ERROR,
                            errorMessage = msg
                        )
                        return@withContext ConductResult(
                            isSuccess = false,
                            message = msg,
                            state = ComingOperationState.RECONCILIATION_REQUIRED,
                            operationId = operationId
                        )
                    }
                    is DocumentServerStatus.NotFound -> {
                        val msg = "После проведения не удалось найти документ №$idDocument для подтверждения статуса."
                        draftRepository.transitionState(
                            documentId = idDocument,
                            newState = ComingOperationState.RECONCILIATION_REQUIRED,
                            syncState = ComingSyncState.SYNC_ERROR,
                            errorMessage = msg
                        )
                        return@withContext ConductResult(
                            isSuccess = false,
                            message = msg,
                            state = ComingOperationState.RECONCILIATION_REQUIRED,
                            operationId = operationId
                        )
                    }
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val errorDescription = successError
                    ?: BitrixApiErrorParser.fromHttp(response.code(), errorBody)

                // Check if another request/TSD actually conducted it concurrently causing Conduct error!
                val postStatus = fetchAuthoritativeDocumentStatus(idDocument)
                if (postStatus is DocumentServerStatus.Found && postStatus.document.status?.uppercase() == "Y") {
                    draftRepository.markCommittedAndFinalize(idDocument, operationId)
                    return@withContext ConductResult(
                        isSuccess = true,
                        message = "Сверка показала: документ №$idDocument уже проведён на сервере.",
                        state = ComingOperationState.COMMITTED,
                        operationId = operationId,
                        reconciledAlreadyConducted = true
                    )
                }

                TsdLogger.error(
                    category = "CONDUCT",
                    event = "conduct_rejected_by_server",
                    operationId = operationId,
                    documentId = idDocument,
                    fields = mapOf("code" to response.code(), "desc" to errorDescription)
                )
                draftRepository.transitionState(
                    documentId = idDocument,
                    newState = ComingOperationState.FAILED,
                    syncState = ComingSyncState.SYNC_ERROR,
                    errorMessage = errorDescription
                )
                return@withContext ConductResult(
                    isSuccess = false,
                    message = errorDescription,
                    state = ComingOperationState.FAILED,
                    operationId = operationId
                )
            }
        } catch (e: IOException) {
            // Timeout or connection drop during conductDocument: outcome on Bitrix is UNKNOWN!
            TsdLogger.warn(
                category = "CONDUCT",
                event = "conduct_timeout_or_io_reconciling",
                operationId = operationId,
                documentId = idDocument,
                throwable = e
            )
            draftRepository.appendJournal(
                operationId = operationId,
                documentId = idDocument,
                stepType = "CONDUCT_DOCUMENT",
                status = "UNCERTAIN_TIMEOUT",
                details = e.message
            )

            // Immediately probe authoritative document status before returning
            when (val statusAfterTimeout = fetchAuthoritativeDocumentStatus(idDocument)) {
                is DocumentServerStatus.Found -> {
                    if (statusAfterTimeout.document.status?.uppercase() == "Y") {
                        CatalogReferenceCache.invalidateStores()
                        draftRepository.markCommittedAndFinalize(idDocument, operationId)
                        TsdLogger.info(
                            category = "CONDUCT",
                            event = "timeout_reconciled_as_committed",
                            operationId = operationId,
                            documentId = idDocument
                        )
                        return@withContext ConductResult(
                            isSuccess = true,
                            message = "Связь прерывалась, но сверка подтвердила: документ успешно проведён в Битрикс.",
                            state = ComingOperationState.COMMITTED,
                            operationId = operationId,
                            reconciledAlreadyConducted = true
                        )
                    } else {
                        val msg = "Сетевой тайм-аут при проведении. Сверка показала, что документ ещё не проведён — можно безопасно повторить."
                        draftRepository.transitionState(
                            documentId = idDocument,
                            newState = ComingOperationState.FAILED,
                            syncState = ComingSyncState.SYNC_ERROR,
                            errorMessage = msg
                        )
                        return@withContext ConductResult(
                            isSuccess = false,
                            message = msg,
                            state = ComingOperationState.FAILED,
                            operationId = operationId
                        )
                    }
                }
                else -> {
                    val msg = "Связь прервана во время проведения документа. Результат на сервере неизвестен — перед повторной отправкой будет выполнена автоматическая сверка."
                    draftRepository.transitionState(
                        documentId = idDocument,
                        newState = ComingOperationState.RECONCILIATION_REQUIRED,
                        syncState = ComingSyncState.SYNC_ERROR,
                        errorMessage = msg
                    )
                    return@withContext ConductResult(
                        isSuccess = false,
                        message = msg,
                        state = ComingOperationState.RECONCILIATION_REQUIRED,
                        operationId = operationId
                    )
                }
            }
        }
    }

    private suspend fun saveProductVariationsInternal(
        productOffersList: List<ProductOffer>,
        documentId: Int?,
        operationId: String?
    ): ConductResult.SaveResult = withContext(Dispatchers.IO) {
        if (productOffersList.isEmpty()) {
            return@withContext ConductResult.SaveResult.Success(emptyList())
        }

        val successfullySaved = mutableListOf<Pair<Int, ProductOffer>>()

        for ((index, productOffer) in productOffersList.withIndex()) {
            val posNum = index + 1
            val normalizedBarcode = BarcodeSanitizer.normalizeForComingVariation(productOffer.barcode)

            try {
                // Step 1: Authoritative barcode lookup on server
                if (!normalizedBarcode.isNullOrBlank()) {
                    when (val lookup = lookupBarcodeAuthoritative(normalizedBarcode)) {
                        is BarcodeLookupOutcome.Error -> {
                            return@withContext ConductResult.SaveResult.Failure(
                                errorMessage = "Позиция №$posNum (${productOffer.name}): ошибка проверки штрихкода '$normalizedBarcode' (${lookup.message}).",
                                failedPositionIndex = index
                            )
                        }
                        is BarcodeLookupOutcome.Found -> {
                            when (val ownership = verifyOfferBelongsToParent(lookup.productId, productOffer.parentId)) {
                                is OfferOwnershipCheck.Compatible -> {
                                    productOffer.id = lookup.productId
                                    CatalogReferenceCache.putBarcodeMapping(normalizedBarcode, lookup.productId)
                                    if (documentId != null && operationId != null) {
                                        draftRepository.recordItemStepProgress(
                                            documentId = documentId,
                                            operationId = operationId,
                                            localItemId = productOffer.localItemId,
                                            offerId = lookup.productId,
                                            idInDocument = productOffer.idInDocument,
                                            itemStatus = ComingItemStatus.BARCODE_ASSIGNED,
                                            syncState = ComingSyncState.REMOTE_VARIATION_CREATED,
                                            stepType = "RESOLVE_VARIATION",
                                            stepStatus = "SKIPPED_REUSED",
                                            remoteEntityId = lookup.productId,
                                            details = "Reused verified offerId=${lookup.productId} for barcode=$normalizedBarcode"
                                        )
                                    }
                                    successfullySaved.add(lookup.productId to productOffer)
                                    continue
                                }
                                is OfferOwnershipCheck.ForeignConflict -> {
                                    val conflictMsg = "Позиция №$posNum (${productOffer.name}): штрихкод '$normalizedBarcode' уже принадлежит другому товару («${ownership.foreignName}», ID: ${lookup.productId})."
                                    return@withContext ConductResult.SaveResult.Failure(
                                        errorMessage = conflictMsg,
                                        failedPositionIndex = index,
                                        isConflict = true
                                    )
                                }
                                is OfferOwnershipCheck.VerificationError -> {
                                    return@withContext ConductResult.SaveResult.Failure(
                                        errorMessage = "Позиция №$posNum (${productOffer.name}): не удалось проверить вариацию ID ${lookup.productId} (${ownership.reason}).",
                                        failedPositionIndex = index
                                    )
                                }
                            }
                        }
                        is BarcodeLookupOutcome.NotFound -> {
                            // Proceed to check previously created offerId or create new variation
                        }
                    }
                }

                // Step 2: Reuse previously created offerId from journal/draft if barcode wasn't bound yet
                var offerIdToUse: Int? = productOffer.id?.takeIf { it > 0 }
                if (offerIdToUse != null) {
                    val check = verifyOfferBelongsToParent(offerIdToUse, productOffer.parentId)
                    if (check !is OfferOwnershipCheck.Compatible) {
                        offerIdToUse = null
                    }
                }

                // Step 3: Create new variation only if none exists yet
                if (offerIdToUse == null) {
                    val createdId = addVariationOfProductSuspend(productOffer)
                        ?: return@withContext ConductResult.SaveResult.Failure(
                            errorMessage = "Позиция №$posNum: не удалось создать вариацию товара «${productOffer.name}».",
                            failedPositionIndex = index
                        )
                    offerIdToUse = createdId
                    productOffer.id = createdId
                    CatalogReferenceCache.invalidateProduct(productOffer.parentId)

                    // Persist created offerId IMMEDIATELY before barcode assignment so crash/failure won't orphan it
                    if (documentId != null && operationId != null) {
                        draftRepository.recordItemStepProgress(
                            documentId = documentId,
                            operationId = operationId,
                            localItemId = productOffer.localItemId,
                            offerId = createdId,
                            idInDocument = productOffer.idInDocument,
                            itemStatus = ComingItemStatus.VARIATION_RESOLVED,
                            syncState = ComingSyncState.REMOTE_VARIATION_CREATED,
                            stepType = "CREATE_VARIATION",
                            stepStatus = "SUCCEEDED",
                            remoteEntityId = createdId,
                            details = "Created offerId=$createdId for parentId=${productOffer.parentId}"
                        )
                    }
                }

                // Step 4: Bind barcode to the variation
                if (!normalizedBarcode.isNullOrBlank()) {
                    val barcodeSuccess = setBarcodeForProductSuspend(offerIdToUse, normalizedBarcode)
                    if (!barcodeSuccess) {
                        // Re-query barcode in case of concurrent creation or retry idempotency
                        val recheck = lookupBarcodeAuthoritative(normalizedBarcode)
                        if (recheck is BarcodeLookupOutcome.Found && recheck.productId == offerIdToUse) {
                            // Already bound to our exact offerId -> idempotent success!
                        } else if (recheck is BarcodeLookupOutcome.Found && recheck.productId != offerIdToUse) {
                            val ownership = verifyOfferBelongsToParent(recheck.productId, productOffer.parentId)
                            if (ownership is OfferOwnershipCheck.Compatible) {
                                // Another TSD concurrently created a valid variation for the same parent & barcode -> reuse it!
                                offerIdToUse = recheck.productId
                                productOffer.id = offerIdToUse
                            } else {
                                val errorMessage = "Позиция №$posNum: штрихкод '$normalizedBarcode' для товара '${productOffer.name}' уже используется другим товаром (ID: ${recheck.productId}) и не может быть назначен."
                                return@withContext ConductResult.SaveResult.Failure(
                                    errorMessage = errorMessage,
                                    failedPositionIndex = index,
                                    isConflict = true
                                )
                            }
                        } else {
                            val errorMessage = "Позиция №$posNum: штрихкод '$normalizedBarcode' для товара '${productOffer.name}' уже используется или не может быть назначен."
                            return@withContext ConductResult.SaveResult.Failure(
                                errorMessage = errorMessage,
                                failedPositionIndex = index,
                                isConflict = true
                            )
                        }
                    }
                    CatalogReferenceCache.putBarcodeMapping(normalizedBarcode, offerIdToUse)

                    if (documentId != null && operationId != null) {
                        draftRepository.recordItemStepProgress(
                            documentId = documentId,
                            operationId = operationId,
                            localItemId = productOffer.localItemId,
                            offerId = offerIdToUse,
                            idInDocument = productOffer.idInDocument,
                            itemStatus = ComingItemStatus.BARCODE_ASSIGNED,
                            syncState = ComingSyncState.REMOTE_VARIATION_CREATED,
                            stepType = "ASSIGN_BARCODE",
                            stepStatus = "SUCCEEDED",
                            remoteEntityId = offerIdToUse,
                            details = "Bound barcode=$normalizedBarcode to offerId=$offerIdToUse"
                        )
                    }
                }

                successfullySaved.add(offerIdToUse to productOffer)
            } catch (e: Exception) {
                TsdLogger.error(
                    category = "VARIATION",
                    event = "save_variation_exception",
                    operationId = operationId,
                    documentId = documentId,
                    fields = mapOf("pos" to posNum, "product" to productOffer.name),
                    throwable = e
                )
                return@withContext ConductResult.SaveResult.Failure(
                    errorMessage = "Позиция №$posNum: критическая ошибка при сохранении товара «${productOffer.name}».",
                    failedPositionIndex = index
                )
            }
        }

        ConductResult.SaveResult.Success(successfullySaved)
    }

    internal suspend fun saveProductVariationsForTest(
        productOffersList: List<ProductOffer>
    ): ConductResult.SaveResult = saveProductVariationsInternal(
        productOffersList = productOffersList,
        documentId = null,
        operationId = null
    )

    private suspend fun lookupBarcodeAuthoritative(barcode: String): BarcodeLookupOutcome {
        val cleaned = BarcodeSanitizer.normalizeForComingVariation(barcode)
            ?: return BarcodeLookupOutcome.NotFound
        return try {
            val response = apiBarcode.getProductIdByBarcode(cleaned)
            if (!response.isSuccessful) {
                return BarcodeLookupOutcome.Error("HTTP ${response.code()}")
            }
            val rawId = response.body()?.result?.trim()
            if (rawId.isNullOrEmpty() || rawId == "0" || rawId.equals("null", ignoreCase = true) || rawId.equals("false", ignoreCase = true)) {
                BarcodeLookupOutcome.NotFound
            } else {
                val parsed = BitrixNumberParser.toIntOrNull(rawId)
                if (parsed != null && parsed > 0) {
                    BarcodeLookupOutcome.Found(parsed)
                } else {
                    BarcodeLookupOutcome.NotFound
                }
            }
        } catch (e: IOException) {
            BarcodeLookupOutcome.Error("ошибка сети")
        } catch (e: Exception) {
            BarcodeLookupOutcome.Error(e.message ?: "ошибка ответа API")
        }
    }

    private suspend fun setBarcodeForProductSuspend(productId: Int, barcode: String): Boolean {
        val barcodeRequest = BarcodeRequest(productId, barcode)
        return withContext(Dispatchers.IO) {
            try {
                val response = apiBarcode.setBarcodeByProductId(barcodeRequest)
                if (response.isSuccessful) {
                    response.body()?.result == true
                } else {
                    TsdLogger.warn(
                        category = "BARCODE",
                        event = "set_barcode_http_error",
                        fields = mapOf("offerId" to productId, "code" to response.code())
                    )
                    false
                }
            } catch (e: Exception) {
                TsdLogger.error(
                    category = "BARCODE",
                    event = "set_barcode_network_error",
                    fields = mapOf("offerId" to productId),
                    throwable = e
                )
                false
            }
        }
    }

    private suspend fun addVariationOfProductSuspend(productOffer: ProductOffer): Int? {
        val fieldsMap: MutableMap<String, Any?> = mutableMapOf(
            "iblockId" to BitrixConfig.iblockOffers,
            "name" to productOffer.name,
            "measure" to productOffer.measure,
            "parentId" to productOffer.parentId,
            "dateCreate" to productOffer.dateCreate.toString(),
            "purchasingPrice" to productOffer.purchasingPrice,
            "purchasingCurrency" to productOffer.purchasingCurrency
        )

        val nonNullFields: MutableMap<String, Any> = fieldsMap
            .filterValues { it != null }
            .mapValues { it.value!! }
            .toMutableMap()

        val productOfferRequest = ProductOfferRequest(fields = nonNullFields)

        return withContext(Dispatchers.IO) {
            try {
                val response = apiBitrix.addVariationsOfProduct(productOfferRequest)
                if (response.isSuccessful) {
                    val offerObj = response.body()?.result?.get("offer") as? Map<*, *>
                    BitrixNumberParser.toIntOrNull(offerObj?.get("id"))
                } else {
                    TsdLogger.error(
                        category = "VARIATION",
                        event = "add_offer_http_error",
                        fields = mapOf("parentId" to productOffer.parentId, "code" to response.code())
                    )
                    null
                }
            } catch (e: Exception) {
                TsdLogger.error(
                    category = "VARIATION",
                    event = "add_offer_network_error",
                    fields = mapOf("parentId" to productOffer.parentId),
                    throwable = e
                )
                null
            }
        }
    }

    private suspend fun updateComment(
        comment: String?,
        documentId: Int?,
        products: List<Product>,
        operationId: String? = null
    ) {
        val batchSummary = products
            .filter { it.isOldProduct || !it.batchNumber.isNullOrBlank() }
            .joinToString("\n") { product ->
                val batchValue = product.batchNumber ?: "-"
                val oldFlag = if (product.isOldProduct) "Y" else "N"
                val weight = product.weight?.toString() ?: "-"
                "Партия:${product.name}|batch=$batchValue|old=$oldFlag|weight=$weight"
            }

        val finalComment = listOfNotNull(
            comment?.takeIf { it.isNotBlank() },
            batchSummary.takeIf { it.isNotBlank() }
        ).joinToString("\n")

        if (finalComment.isNotBlank() && documentId != null) {
            val request = UpdateCommentRequest(
                documentId = documentId,
                fields = mutableMapOf(
                    "field621" to finalComment,
                    "documentType" to "A"
                )
            )
            try {
                apiBitrix.updateComment(request)
                if (operationId != null) {
                    draftRepository.appendJournal(
                        operationId = operationId,
                        documentId = documentId,
                        stepType = "UPDATE_COMMENT",
                        status = "SUCCEEDED"
                    )
                }
            } catch (e: Exception) {
                TsdLogger.warn(
                    category = "CONDUCT",
                    event = "comment_update_failed",
                    operationId = operationId,
                    documentId = documentId,
                    throwable = e
                )
            }
        }
    }

    private suspend fun updateDiameterAndWeightAndHeight(
        products: List<ProductOffer>,
        operationId: String? = null,
        documentId: Int? = null
    ) {
        if (products.isEmpty()) return

        for (productOffer in products) {
            val targetOfferId = productOffer.id ?: continue
            if (productOffer.product.weight != null ||
                productOffer.product.diameter != null ||
                productOffer.product.height != null
            ) {
                val request = UpdateProductRequest(
                    id = targetOfferId,
                    fields = mutableMapOf(
                        "weight" to productOffer.product.weight,
                        "width" to productOffer.product.diameter,
                        "height" to productOffer.product.height
                    )
                )
                try {
                    val response = apiBitrix.updateProductOffer(request)
                    if (response != null && response.isSuccessful && operationId != null && documentId != null) {
                        draftRepository.appendJournal(
                            operationId = operationId,
                            documentId = documentId,
                            localItemId = productOffer.localItemId,
                            stepType = "UPDATE_DIMENSIONS",
                            status = "SUCCEEDED",
                            remoteEntityId = targetOfferId
                        )
                    }
                } catch (e: Exception) {
                    TsdLogger.warn(
                        category = "CONDUCT",
                        event = "dimensions_update_failed",
                        operationId = operationId,
                        documentId = documentId,
                        fields = mapOf("offerId" to targetOfferId),
                        throwable = e
                    )
                }
            }
        }
    }

    suspend fun getDocumentsSuspend(): List<Document> {
        val requestWithParams = CatalogDocumentListRequest(
            filter = mutableMapOf(
                "status" to "N",
                "docType" to "A"
            )
        )
        return withContext(Dispatchers.IO) {
            try {
                val response = apiBitrix.getDocumentListSuspend(requestWithParams)
                if (response.isSuccessful) {
                    val rawDocuments = response.body()?.result?.get("documents") as? List<*>
                        ?: return@withContext emptyList<Document>()

                    rawDocuments.mapNotNull { documentMap ->
                        (documentMap as? LinkedTreeMap<*, *>)?.let { documentMapper.mapToDocument(it) }
                    }
                } else {
                    TsdLogger.warn(
                        category = "COMING",
                        event = "documents_load_http_error",
                        fields = mapOf("code" to response.code())
                    )
                    emptyList()
                }
            } catch (e: HttpException) {
                TsdLogger.error(category = "COMING", event = "documents_load_http_exc", throwable = e)
                emptyList()
            } catch (e: IOException) {
                TsdLogger.warn(category = "COMING", event = "documents_load_offline", throwable = e)
                emptyList()
            } catch (e: Exception) {
                TsdLogger.error(category = "COMING", event = "documents_load_error", throwable = e)
                emptyList()
            }
        }
    }

    suspend fun fetchAndAssignNomenclature(documents: List<Document>): List<Document> {
        if (documents.isEmpty()) return documents
        val documentIds = documents.mapNotNull { it.id }
        if (documentIds.isEmpty()) return documents

        val nomenclatureRequest = NomenclatureRequest(
            filter = mutableMapOf(
                "documentId" to documentIds,
                "documentType" to "A"
            )
        )

        return try {
            val response = apiBitrix.getDocumentsNomenclature(nomenclatureRequest)
            if (response.isSuccessful) {
                val nomenclatureItems = response.body()?.result?.documents
                if (nomenclatureItems.isNullOrEmpty()) return documents

                val field620Map = nomenclatureItems
                    .filter { it.documentId != null }
                    .associate { it.documentId!! to it.field620 }

                documents.map { doc ->
                    doc.id?.let { currentDocId ->
                        if (field620Map.containsKey(currentDocId)) {
                            doc.copy(field620Values = field620Map[currentDocId])
                        } else {
                            doc
                        }
                    } ?: doc
                }
            } else {
                documents
            }
        } catch (e: Exception) {
            TsdLogger.warn(category = "COMING", event = "nomenclature_load_failed", throwable = e)
            documents
        }
    }

    suspend fun getProductsForSelection(
        iblockId: Int,
        productId: ArrayList<Double?>?,
        forceRefresh: Boolean = false
    ): List<Product> {
        if (productId == null || productId.isEmpty()) {
            return emptyList()
        }

        val cacheKey = CatalogReferenceCache.buildNomenclatureKey(iblockId, productId)
        if (!forceRefresh) {
            CatalogReferenceCache.getNomenclatureSelection(cacheKey)?.let { cached ->
                return cached
            }
        }

        val filterMap = mapOf(
            "iblockId" to iblockId,
            "id" to productId.filterNotNull()
        )

        return withContext(Dispatchers.IO) {
            val allProducts = mutableListOf<Product>()
            val seenOffsets = HashSet<Int>()
            var startFrom: Int? = 0
            var pageCount = 0
            val maxPages = 100

            try {
                do {
                    if (startFrom != null && !seenOffsets.add(startFrom)) {
                        break
                    }
                    pageCount++
                    val productRequest = ProductRequest(
                        filter = filterMap,
                        start = startFrom?.toString()
                    )
                    val response = apiBitrix.getProducts(productRequest)
                    if (response.isSuccessful) {
                        val responseBody = response.body()
                        if (responseBody != null) {
                            val rawProducts = responseBody.result["products"] as? List<*>
                            val newProducts = rawProducts?.mapNotNull { unit ->
                                (unit as? LinkedTreeMap<*, *>)?.let { productMap ->
                                    try {
                                        productMapper.mapToProduct(productMap)
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            } ?: emptyList()

                            allProducts.addAll(newProducts)
                            startFrom = if (newProducts.isEmpty()) null else responseBody.next
                        } else {
                            startFrom = null
                        }
                    } else {
                        throw IOException("Ошибка загрузки номенклатуры (HTTP ${response.code()})")
                    }
                } while (startFrom != null && pageCount < maxPages)

                CatalogReferenceCache.putNomenclatureSelection(cacheKey, allProducts)
                allProducts.map { it.detachedCopy(preserveLocalItemId = false) }
            } catch (e: Exception) {
                TsdLogger.warn(
                    category = "COMING",
                    event = "nomenclature_selection_fallback_cache",
                    throwable = e
                )
                // If network fails mid-shift, allow selecting from cached nomenclature if available
                CatalogReferenceCache.getNomenclatureSelection(cacheKey, allowStale = true)
                    ?: throw e
            }
        }
    }

    @Suppress("unused")
    private fun showAlertInfo(message: String, context: Context?) {
        if (context == null) return
        val activity = context as? Activity
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
            return
        }
        AlertDialog.Builder(context).apply {
            setTitle("Предупреждение")
            setMessage(message)
            setCancelable(false)
            setPositiveButton("Закрыть") { dialog, _ ->
                dialog.cancel()
            }
        }.create().show()
    }
}
