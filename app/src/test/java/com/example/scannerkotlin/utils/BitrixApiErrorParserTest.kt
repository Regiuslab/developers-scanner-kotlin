package com.example.scannerkotlin.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrixApiErrorParserTest {

    @Test
    fun `wraps unknown api error with russian hint`() {
        val message = BitrixApiErrorParser.fromHttp(
            400,
            """{"error":"ERROR_CORE","error_description":"Unknown API error"}"""
        )
        assertTrue(message.contains("Bitrix не провёл документ"))
        assertTrue(message.contains("ERROR_CORE"))
        assertTrue(message.contains("склады"))
        assertFalse(message.equals("Unknown API error"))
    }

    @Test
    fun `detects error inside http 200 body`() {
        val message = BitrixApiErrorParser.fromSuccessBody(
            mapOf("error" to "ERROR_CORE", "error_description" to "Unknown API error")
        )
        assertTrue(message != null)
        assertTrue(message!!.contains("ERROR_CORE"))
    }
}
