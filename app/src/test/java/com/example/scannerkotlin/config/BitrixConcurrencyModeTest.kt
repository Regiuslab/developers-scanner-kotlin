package com.example.scannerkotlin.config

import org.junit.Assert.assertEquals
import org.junit.Test

class BitrixConcurrencyModeTest {
    @Test
    fun `blank or unknown config stays LEGACY and does not enable guarded mode`() {
        assertEquals(BitrixConcurrencyMode.LEGACY, BitrixConcurrencyMode.fromConfig(null))
        assertEquals(BitrixConcurrencyMode.LEGACY, BitrixConcurrencyMode.fromConfig(""))
        assertEquals(BitrixConcurrencyMode.LEGACY, BitrixConcurrencyMode.fromConfig("legacy"))
        assertEquals(BitrixConcurrencyMode.LEGACY, BitrixConcurrencyMode.fromConfig("oops"))
        assertEquals("LEGACY_MODE", BitrixConcurrencyMode.LEGACY.toDiagnosticLabel())
    }

    @Test
    fun `server guarded aliases are explicit`() {
        assertEquals(BitrixConcurrencyMode.SERVER_GUARDED, BitrixConcurrencyMode.fromConfig("SERVER_GUARDED"))
        assertEquals(BitrixConcurrencyMode.SERVER_GUARDED, BitrixConcurrencyMode.fromConfig("server-guarded"))
        assertEquals(BitrixConcurrencyMode.SERVER_GUARDED, BitrixConcurrencyMode.fromConfig("SERVER_GUARDED_MODE"))
        assertEquals("SERVER_GUARDED_MODE", BitrixConcurrencyMode.SERVER_GUARDED.toDiagnosticLabel())
    }
}
