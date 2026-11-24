package com.example.scannerkotlin.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovingBarcodeLookupTest {

    @Test
    fun `manual and hardware paths share sanitizer normalization`() {
        val raw = "  4601234567890\r\n"
        assertEquals(BarcodeSanitizer.sanitize(raw), MovingBarcodeLookup.normalize(raw))
        assertNull(MovingBarcodeLookup.normalize(" \r\n\t "))
        assertNull(MovingBarcodeLookup.normalize(""))
        assertNull(MovingBarcodeLookup.normalize(null))
    }

    @Test
    fun `blank barcode is not a lookup and missing barcode is not found`() {
        assertFalse(shouldQuery("   "))
        assertTrue(shouldQuery("4601234567890\n"))

        assertTrue(MovingBarcodeLookup.parseProductIds(null) is MovingBarcodeLookup.Match.NotFound)
        assertTrue(MovingBarcodeLookup.parseProductIds("") is MovingBarcodeLookup.Match.NotFound)
        assertTrue(MovingBarcodeLookup.parseProductIds("0") is MovingBarcodeLookup.Match.NotFound)
        assertTrue(MovingBarcodeLookup.parseProductIds("false") is MovingBarcodeLookup.Match.NotFound)
    }

    @Test
    fun `single id is a match and several ids are ambiguous`() {
        val single = MovingBarcodeLookup.parseProductIds(" 318857 ")
        assertTrue(single is MovingBarcodeLookup.Match.Single)
        assertEquals("318857", (single as MovingBarcodeLookup.Match.Single).productId)

        val many = MovingBarcodeLookup.parseProductIds("11, 22")
        assertTrue(many is MovingBarcodeLookup.Match.Ambiguous)
        assertEquals(listOf("11", "22"), (many as MovingBarcodeLookup.Match.Ambiguous).productIds)
    }

    @Test
    fun `repeat enter is ignored while in flight and a later intentional entry is allowed`() {
        val gate = BarcodeScanGate(duplicateWindowMs = 500L)
        val barcode = MovingBarcodeLookup.normalize("4601234567890")!!
        assertTrue(gate.tryAcquire(barcode, nowMs = 1_000L))
        assertFalse(gate.tryAcquire(barcode, nowMs = 1_050L))
        gate.release()
        assertFalse(gate.tryAcquire(barcode, nowMs = 1_200L))
        gate.release()
        assertTrue(gate.tryAcquire(barcode, nowMs = 1_800L))
    }

    private fun shouldQuery(raw: String?): Boolean {
        return !MovingBarcodeLookup.normalize(raw).isNullOrBlank()
    }
}
