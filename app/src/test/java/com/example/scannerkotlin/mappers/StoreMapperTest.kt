package com.example.scannerkotlin.mappers

import com.google.gson.internal.LinkedTreeMap
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreMapperTest {

    @Test
    fun `maps store id from string double and int`() {
        val mapper = StoreMapper()
        assertEquals(1, mapper.mapToStore(storeMap("1")).id)
        assertEquals(1, mapper.mapToStore(storeMap(1.0)).id)
        assertEquals(1, mapper.mapToStore(storeMap(1)).id)
    }

    private fun storeMap(id: Any): LinkedTreeMap<String, Any> {
        return LinkedTreeMap<String, Any>().apply {
            put("id", id)
            put("title", "Дороша")
            put("active", "Y")
            put("address", "")
            put("description", "")
            put("issuingCenter", "N")
            put("phone", "")
            put("schedule", "")
        }
    }
}
