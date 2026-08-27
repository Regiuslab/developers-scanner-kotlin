package com.example.scannerkotlin.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitrixNumberParserTest {

    @Test
    fun `parses double int and string ids`() {
        assertEquals(1, BitrixNumberParser.toIntOrNull(1.0))
        assertEquals(1, BitrixNumberParser.toIntOrNull(1))
        assertEquals(1, BitrixNumberParser.toIntOrNull("1"))
        assertEquals(1, BitrixNumberParser.toIntOrNull("1.0"))
        assertNull(BitrixNumberParser.toIntOrNull(null))
        assertEquals(0, BitrixNumberParser.toIntOrDefault(null, 0))
    }
}
