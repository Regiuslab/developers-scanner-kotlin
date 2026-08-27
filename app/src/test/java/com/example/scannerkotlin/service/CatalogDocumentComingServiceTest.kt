package com.example.scannerkotlin.service

import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.ProductOffer
import com.example.scannerkotlin.request.BarcodeRequest
import com.example.scannerkotlin.request.ProductOfferRequest
import com.example.scannerkotlin.response.BarcodeResponse
import com.example.scannerkotlin.response.ConductResult
import com.example.scannerkotlin.response.ProductBarcodeResponse
import com.example.scannerkotlin.response.ProductOfferResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import retrofit2.Response

class CatalogDocumentComingServiceTest {

    @Test
    fun `reuses existing offer when barcode already mapped`() = runBlocking {
        val apiBitrix = Mockito.mock(ApiBitrix::class.java)
        val apiBarcode = Mockito.mock(ApiBitrix::class.java)
        Mockito.`when`(apiBarcode.getProductIdByBarcode(anyString()))
            .thenReturn(Response.success(ProductBarcodeResponse("432")))

        val service = CatalogDocumentComingService(apiBitrix = apiBitrix, apiBarcode = apiBarcode)
        val result = service.saveProductVariationsForTest(listOf(productOffer(barcode = "1234567890123")))

        assertTrue(result is ConductResult.SaveResult.Success)
        val success = result as ConductResult.SaveResult.Success
        assertEquals(1, success.savedVariations.size)
        assertEquals(432, success.savedVariations.first().first)
        Mockito.verify(apiBitrix, Mockito.never())
            .addVariationsOfProduct(any(ProductOfferRequest::class.java))
        Mockito.verify(apiBarcode, Mockito.never())
            .setBarcodeByProductId(any(BarcodeRequest::class.java))
        Unit
    }

    @Test
    fun `creates offer and sets barcode when no existing barcode mapping`() = runBlocking {
        val apiBitrix = Mockito.mock(ApiBitrix::class.java)
        val apiBarcode = Mockito.mock(ApiBitrix::class.java)

        Mockito.`when`(apiBarcode.getProductIdByBarcode(anyString()))
            .thenReturn(Response.success(ProductBarcodeResponse(" ")))
        Mockito.`when`(apiBitrix.addVariationsOfProduct(any(ProductOfferRequest::class.java)))
            .thenReturn(Response.success(productOfferResponse(id = 777)))
        Mockito.`when`(apiBarcode.setBarcodeByProductId(any(BarcodeRequest::class.java)))
            .thenReturn(Response.success(BarcodeResponse(true)))

        val service = CatalogDocumentComingService(apiBitrix = apiBitrix, apiBarcode = apiBarcode)
        val result = service.saveProductVariationsForTest(listOf(productOffer(barcode = "1234567890123")))

        assertTrue(result is ConductResult.SaveResult.Success)
        val success = result as ConductResult.SaveResult.Success
        assertEquals(777, success.savedVariations.first().first)
        Mockito.verify(apiBitrix)
            .addVariationsOfProduct(any(ProductOfferRequest::class.java))
        Mockito.verify(apiBarcode)
            .setBarcodeByProductId(any(BarcodeRequest::class.java))
        Unit
    }

    @Test
    fun `returns failure when barcode assignment fails`() = runBlocking {
        val apiBitrix = Mockito.mock(ApiBitrix::class.java)
        val apiBarcode = Mockito.mock(ApiBitrix::class.java)

        Mockito.`when`(apiBarcode.getProductIdByBarcode(anyString()))
            .thenReturn(Response.success(ProductBarcodeResponse("")))
        Mockito.`when`(apiBitrix.addVariationsOfProduct(any(ProductOfferRequest::class.java)))
            .thenReturn(Response.success(productOfferResponse(id = 991)))
        Mockito.`when`(apiBarcode.setBarcodeByProductId(any(BarcodeRequest::class.java)))
            .thenReturn(Response.success(BarcodeResponse(false)))

        val service = CatalogDocumentComingService(apiBitrix = apiBitrix, apiBarcode = apiBarcode)
        val result = service.saveProductVariationsForTest(listOf(productOffer(barcode = "1234567890123")))

        assertTrue(result is ConductResult.SaveResult.Failure)
        val failure = result as ConductResult.SaveResult.Failure
        assertTrue(failure.errorMessage.contains("уже используется"))
    }

    private fun productOffer(barcode: String?): ProductOffer {
        val product = Product(
            idInDocument = null,
            id = 51,
            iblockId = 14,
            name = "Труба 63",
            measureId = 1,
            measureSymbol = "м",
            quantity = 1.0,
            barcode = barcode,
            diameter = null,
            weight = null,
            height = null,
            parentId = null,
            storeAmount = null
        )
        return ProductOffer(product)
    }

    private fun productOfferResponse(id: Int): ProductOfferResponse {
        val offerMap = hashMapOf<String, Any>("id" to id.toDouble())
        val resultMap = hashMapOf<String, Any>("offer" to offerMap)
        return ProductOfferResponse(result = resultMap)
    }
}
