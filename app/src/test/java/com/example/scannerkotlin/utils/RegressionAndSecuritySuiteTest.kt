package com.example.scannerkotlin.utils

import com.example.scannerkotlin.api.ApiBitrix
import com.example.scannerkotlin.mappers.DocumentMapper
import com.example.scannerkotlin.mappers.ProductMapper
import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.request.PasswordRequest
import com.example.scannerkotlin.response.PasswordResponse
import com.example.scannerkotlin.service.UserService
import com.google.gson.internal.LinkedTreeMap
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class RegressionAndSecuritySuiteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        TsdLogger.clearRingBufferForTest()
    }

    @Test
    fun `TsdLogger redacts webhook tokens passwords and PINs from logs`() {
        val rawUrl = "https://bitrix.izocom.by/rest/1/sh1lchx64vrzcor6/barcode.setbyproductid.json"
        val redactedUrl = TsdLogger.redact(rawUrl)
        assertFalse(redactedUrl.contains("sh1lchx64vrzcor6"))
        assertTrue(redactedUrl.contains("/rest/1/[REDACTED]"))

        TsdLogger.info(
            category = "AUTH",
            event = "test_event",
            fields = mapOf(
                "pin" to "1234",
                "password" to "9999",
                "safeField" to "ok"
            )
        )
        val lastLog = TsdLogger.recentEntries(1).single()
        assertFalse(lastLog.contains("1234"))
        assertFalse(lastLog.contains("9999"))
        assertTrue(lastLog.contains("pin=[REDACTED]"))
        assertTrue(lastLog.contains("safeField=ok"))
    }

    @Test
    fun `CrashDiagnosticsHandler persists and consumes crash marker without leaking tokens`() {
        val diagDir = tempFolder.newFolder("diag")
        CrashDiagnosticsHandler.installWithDirectory(diagDir)
        TsdLogger.activeDocumentId = 462
        TsdLogger.activeOperationId = "op-crash-test"

        CrashDiagnosticsHandler.recordCrashSync(
            "main",
            IllegalStateException("Failed at https://bitrix.izocom.by/rest/1/c953o6imkob2gpwd/catalog.product.get")
        )

        val record = CrashDiagnosticsHandler.consumeLastCrashRecord()
        assertNotNull(record)
        assertEquals(462, record!!.activeDocumentId)
        assertEquals("op-crash-test", record.activeOperationId)
        assertEquals("java.lang.IllegalStateException", record.exceptionType)
        assertFalse(record.sanitizedMessage.contains("c953o6imkob2gpwd"))
    }

    @Test
    fun `UserService handles invalid PIN empty list without IndexOutOfBoundsException or leaking PIN`() = runBlocking {
        val api = Mockito.mock(ApiBitrix::class.java)
        val passwordResponse = PasswordResponse(result = emptyList())
        val fakeCall = object : Call<PasswordResponse> {
            override fun execute(): Response<PasswordResponse> = Response.success(passwordResponse)
            override fun enqueue(callback: Callback<PasswordResponse>) {}
            override fun isExecuted(): Boolean = true
            override fun cancel() {}
            override fun isCanceled(): Boolean = false
            override fun clone(): Call<PasswordResponse> = this
            override fun request(): Request = Request.Builder().url("https://localhost/").build()
            override fun timeout(): Timeout = Timeout.NONE
        }
        val secretPin = "7391"
        Mockito.`when`(
            api.getPasswords(
                PasswordRequest(
                    iblockId = 18,
                    iblockTypeId = "lists",
                    filter = mapOf("PROPERTY_71" to mapOf("1451" to secretPin))
                )
            )
        ).thenReturn(fakeCall)

        val userService = UserService(api)
        val creds = userService.getPasswords(secretPin)
        assertNotNull(creds)
        assertTrue(creds!!.isEmpty())
        assertTrue("PIN must never appear in diagnostic logs", TsdLogger.recentEntries(20).none { it.contains(secretPin) })
    }

    @Test
    fun `ComingProductValidator catches duplicate barcodes unsupported product types and zero quantities`() {
        val p1 = Product(
            idInDocument = null,
            id = 101,
            iblockId = 14,
            name = "Труба 1",
            measureId = 1,
            measureSymbol = "м",
            quantity = 2.0,
            barcode = "4600000000011",
            diameter = null,
            weight = null,
            height = null,
            parentId = null,
            storeAmount = null
        )
        val p2DuplicateBarcode = p1.detachedCopy(preserveLocalItemId = false).apply {
            name = "Труба 2"
            barcode = "4600000000011"
        }
        val p3UnsupportedType = p1.detachedCopy(preserveLocalItemId = false).apply {
            name = "Услуга резки"
            barcode = "4600000000033"
            catalogProductType = 7 // TYPE_SERVICE
        }
        val p4ZeroQty = p1.detachedCopy(preserveLocalItemId = false).apply {
            name = "Труба 0"
            barcode = "4600000000044"
            quantity = 0.0
        }

        val issues = ComingProductValidator.validateDetailed(
            products = listOf(p1, p2DuplicateBarcode, p3UnsupportedType, p4ZeroQty),
            documentId = 555,
            destinationStoreId = 1,
            expectedProductsIblockId = 14
        )
        assertEquals(3, issues.size)
        assertTrue(issues.any { it.positionIndex == 1 && it.message.contains("уже указан в позиции №1") })
        assertTrue(issues.any { it.positionIndex == 2 && it.message.contains("не поддерживает создание вариаций") })
        assertTrue(issues.any { it.positionIndex == 3 && it.message.contains("больше 0") })
    }

    @Test
    fun `ProductMapper and DocumentMapper parse string and numeric Bitrix fields safely`() {
        val productMap = LinkedTreeMap<String, Any?>().apply {
            put("id", "318857")
            put("iblockId", "15")
            put("name", "Труба ст20")
            put("measure", "5")
            put("type", "4")
            put("parentId", LinkedTreeMap<String, Any?>().apply { put("value", "51") })
        }
        val mappedProduct = ProductMapper().mapToProduct(productMap)
        assertEquals(318857, mappedProduct.id)
        assertEquals(15, mappedProduct.iblockId)
        assertEquals(5, mappedProduct.measureId)
        assertEquals("Штука", mappedProduct.measureName)
        assertEquals(4, mappedProduct.catalogProductType)
        assertEquals(51, mappedProduct.parentId)

        val docMap = LinkedTreeMap<String, Any?>().apply {
            put("id", "462")
            put("docType", "A")
            put("status", "Y")
            put("title", "Приход #462")
        }
        val mappedDoc = DocumentMapper().mapToDocument(docMap)
        assertNotNull(mappedDoc)
        assertEquals(462, mappedDoc!!.id)
        assertEquals("Y", mappedDoc.status)
    }
}
