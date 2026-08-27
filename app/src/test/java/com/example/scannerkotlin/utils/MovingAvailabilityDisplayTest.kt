package com.example.scannerkotlin.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MovingAvailabilityDisplayTest {

    @Test
    fun `does not show catalog quantity when stores are missing`() {
        val label = MovingAvailabilityDisplay.availableLabel(
            storesLoaded = false,
            mainAmount = 7.87,
            storeFrom = null
        )
        assertEquals(MovingAvailabilityDisplay.STORES_NOT_LOADED, label)
        assertFalse(label.contains("7.87"))
    }

    @Test
    fun `shows remainder after stores loaded`() {
        val label = MovingAvailabilityDisplay.availableLabel(
            storesLoaded = true,
            mainAmount = 7.87,
            storeFrom = 1
        )
        assertEquals("Доступно: 7.87", label)
    }
}
