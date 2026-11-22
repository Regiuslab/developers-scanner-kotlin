package com.example.scannerkotlin.api

import com.example.scannerkotlin.request.*
import com.example.scannerkotlin.response.*
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiBitrix {

    @POST("catalog.document.list")
    suspend fun getDocumentListSuspend(@Body request: CatalogDocumentListRequest): Response<CatalogDocumentListResponse>

    @POST("catalog.document.element.list")
    suspend fun getDocumentProducts(
        @Body list: CatalogDocumentElementListRequest
    ): Response<CatalogDocumentElementListResponse>

    @POST("catalog.product.offer.add")
    suspend fun addVariationsOfProduct(
        @Body request: ProductOfferRequest
    ): Response<ProductOfferResponse>

    @POST("catalog.product.offer.list")
    suspend fun getVariations(
        @Body request: VariationsRequest
    ): Response<VariationsResponse>

    @POST("catalog.product.list")
    suspend fun getProducts (
        @Body request: ProductRequest
    ): Response<ProductResponse>


    @GET("barcode.getproductid.json")
    suspend fun getProductIdByBarcode(
        @Query("barcode") barcode: String
    ): Response<ProductBarcodeResponse>

    @POST("barcode.setbyproductid.json")
    suspend fun setBarcodeByProductId(@Body request: BarcodeRequest): Response<BarcodeResponse>


    @POST("catalog.product.get")
    suspend fun getProductById(
        @Body request: ProductIdRequest
    ): Response<ProductResponse>

    @POST("catalog.document.conduct")
    suspend fun conductDocument(
        @Body request: ConductDocumentRequest
    ) : Response<HashMap<String, Any?>>

    @POST("catalog.document.element.update")
    suspend fun updateDocumentElement(
        @Body request: UpdatedDocumentElementsRequest
    ) : Response<HashMap<String, Any?>>

    @POST("catalog.document.element.delete")
    suspend fun deleteDocumentElement(
        @Body request: DeletedDocumentElementRequest
    ) : Response<HashMap<String, Any?>>

    @POST("catalog.product.update")
    suspend fun updateProduct(
        @Body request: UpdateProductRequest
    ) : Response<HashMap<String, Any?>>

    @POST("catalog.product.offer.update")
    suspend fun updateProductOffer(
        @Body request: UpdateProductRequest
    ) : Response<HashMap<String, Any?>>

    @POST("catalog.document.element.add")
    suspend fun addDocumentElement(
        @Body request: AddDocumentElementRequest
    ) : Response<HashMap<String, Any?>>

    /**
     * Portal TSD guard. Present only after `BITRIX_TSD_SERVER_LOCK_SPEC.md` is installed.
     * SERVER_GUARDED treats HTTP 404 as a hard stop, not a fallback to standard REST.
     */
    @POST("tsd.variation.resolveOrCreate")
    suspend fun tsdVariationResolveOrCreate(
        @Body request: TsdVariationResolveRequest
    ): Response<TsdVariationResolveResponse>

    @POST("tsd.document.syncAndConduct")
    suspend fun tsdDocumentSyncAndConduct(
        @Body request: TsdDocumentSyncRequest
    ): Response<TsdDocumentSyncResponse>

    @GET("catalog.store.list")
    suspend fun getStoreList(): Response<HashMap<String, Any?>>

    @POST("catalog.document.add")
    suspend fun addNewDocument(
        @Body request: NewDocumentRequest
    ) : Response<HashMap<String, Any?>>

    @POST("lists.element.get")
    fun getPasswords(@Body request: PasswordRequest): Call<PasswordResponse>


    @POST("catalog.storeproduct.list")
    suspend fun getStoreAmount(
        @Body request: StoreAmountRequest
    ): Response<StoreAmountResponse>

    @POST("catalog.documentcontractor.add")
    suspend fun addProvider(
        @Body request: ProviderRequest
    ): Response<HashMap<String, Any?>>

    @POST("crm.item.list")
    suspend fun getProviders(
        @Body request: GettingProvidersRequest
    ): GettingProvidersResponse


    @POST("crm.item.list")
    suspend fun getProcesses(
        @Body request: ProcessesRequest
    ): ProcessesResponse

    @POST("crm.item.update")
    suspend fun updateProcesses(
        @Body request: ProcessUpdateRequest
    )

    @POST("catalog.userfield.document.update")
    suspend fun updateComment(
        @Body request: UpdateCommentRequest
    )

    @POST("catalog.userfield.document.list")
    suspend fun getDocumentsNomenclature(
        @Body request: NomenclatureRequest
    ): Response<NomenclatureResponse>

    @POST("catalog.userfield.document.list")
    suspend fun getDocumentSgp(
        @Body request: SgpRequest
    ): Response<SgpResponse>

    @POST("crm.item.list")
    suspend fun getFirstBlanks(
        @Body request: BlankRequest
    ) : FirstBlankResponse

    @POST("crm.item.list")
    suspend fun getSecondBlanks(
        @Body request: BlankRequest
    ) : SecondBlankResponse

}