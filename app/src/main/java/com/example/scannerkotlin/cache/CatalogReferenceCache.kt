package com.example.scannerkotlin.cache

import com.example.scannerkotlin.model.Product
import com.example.scannerkotlin.model.Store
import com.example.scannerkotlin.utils.TsdLogger

/**
 * Bounded in-memory LRU + TTL cache strictly for read-only reference catalog data
 * (stores, nomenclature selection templates, product display metadata).
 *
 * Architectural invariants:
 * 1. NEVER stores unfinished document drafts or operation journals (those live in SQLite).
 * 2. Clearing or trimming this cache has ZERO effect on saved scans or unfinished documents.
 * 3. Returns defensive copies of [Product] so UI mutations never corrupt cached templates.
 * 4. Must be bypassed (`forceRefresh = true`) for authoritative pre-conduct server checks.
 */
object CatalogReferenceCache {

    const val DEFAULT_STORES_TTL_MS = 5 * 60 * 1000L
    const val DEFAULT_NOMENCLATURE_TTL_MS = 5 * 60 * 1000L
    const val DEFAULT_PRODUCT_TTL_MS = 3 * 60 * 1000L

    const val MAX_NOMENCLATURE_SETS = 24
    const val MAX_CACHED_PRODUCTS = 500
    const val MAX_BARCODE_LOOKUPS = 500

    private data class TimedValue<T>(
        val value: T,
        val expiresAtMs: Long,
        val cachedAtMs: Long
    ) {
        fun isFresh(nowMs: Long): Boolean = nowMs <= expiresAtMs
    }

    private class BoundedLruMap<K, V>(private val maxEntries: Int) :
        LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxEntries
        }
    }

    private val lock = Any()
    private var storesEntry: TimedValue<List<Store>>? = null
    private val nomenclatureSets = BoundedLruMap<String, TimedValue<List<Product>>>(MAX_NOMENCLATURE_SETS)
    private val productsById = BoundedLruMap<Int, TimedValue<Product>>(MAX_CACHED_PRODUCTS)
    private val productIdByBarcode = BoundedLruMap<String, TimedValue<Int>>(MAX_BARCODE_LOOKUPS)

    fun getStores(
        allowStale: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): List<Store>? = synchronized(lock) {
        val entry = storesEntry ?: return null
        if (entry.isFresh(nowMs) || allowStale) {
            entry.value.map { it.copy() }
        } else {
            null
        }
    }

    fun putStores(
        stores: List<Store>,
        ttlMs: Long = DEFAULT_STORES_TTL_MS,
        nowMs: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        if (stores.isEmpty()) return@synchronized
        storesEntry = TimedValue(stores.map { it.copy() }, nowMs + ttlMs, nowMs)
    }

    fun buildNomenclatureKey(iblockId: Int, productIds: Collection<Double?>?): String {
        val normalized = productIds
            ?.mapNotNull { it?.toInt() }
            ?.distinct()
            ?.sorted()
            ?.joinToString(",")
            .orEmpty()
        return "$iblockId:$normalized"
    }

    fun getNomenclatureSelection(
        key: String,
        allowStale: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): List<Product>? = synchronized(lock) {
        val entry = nomenclatureSets[key] ?: return null
        if (entry.isFresh(nowMs) || allowStale) {
            entry.value.map { it.detachedCopy(preserveLocalItemId = false) }
        } else {
            nomenclatureSets.remove(key)
            null
        }
    }

    fun putNomenclatureSelection(
        key: String,
        products: List<Product>,
        ttlMs: Long = DEFAULT_NOMENCLATURE_TTL_MS,
        nowMs: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        if (products.isEmpty()) return@synchronized
        val snapshots = products.map { it.detachedCopy(preserveLocalItemId = false) }
        nomenclatureSets[key] = TimedValue(snapshots, nowMs + ttlMs, nowMs)
        for (p in snapshots) {
            if (p.id > 0) {
                productsById[p.id] = TimedValue(p.detachedCopy(preserveLocalItemId = false), nowMs + DEFAULT_PRODUCT_TTL_MS, nowMs)
            }
        }
    }

    fun getProduct(
        productId: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Product? = synchronized(lock) {
        val entry = productsById[productId] ?: return null
        if (entry.isFresh(nowMs)) {
            entry.value.detachedCopy(preserveLocalItemId = false)
        } else {
            productsById.remove(productId)
            null
        }
    }

    fun putProduct(
        product: Product,
        ttlMs: Long = DEFAULT_PRODUCT_TTL_MS,
        nowMs: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        if (product.id <= 0) return@synchronized
        productsById[product.id] = TimedValue(
            product.detachedCopy(preserveLocalItemId = false),
            nowMs + ttlMs,
            nowMs
        )
    }

    fun getBarcodeMapping(
        barcode: String,
        nowMs: Long = System.currentTimeMillis()
    ): Int? = synchronized(lock) {
        val normalized = barcode.trim()
        val entry = productIdByBarcode[normalized] ?: return null
        if (entry.isFresh(nowMs)) {
            entry.value
        } else {
            productIdByBarcode.remove(normalized)
            null
        }
    }

    fun putBarcodeMapping(
        barcode: String,
        productId: Int,
        ttlMs: Long = DEFAULT_PRODUCT_TTL_MS,
        nowMs: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        val normalized = barcode.trim()
        if (normalized.isEmpty() || productId <= 0) return@synchronized
        productIdByBarcode[normalized] = TimedValue(productId, nowMs + ttlMs, nowMs)
    }

    fun invalidateProduct(productId: Int): Unit = synchronized(lock) {
        productsById.remove(productId)
        nomenclatureSets.clear()
    }

    fun invalidateBarcode(barcode: String): Unit = synchronized(lock) {
        productIdByBarcode.remove(barcode.trim())
    }

    fun invalidateStores() = synchronized(lock) {
        storesEntry = null
    }

    fun trimForMemoryPressure() = synchronized(lock) {
        nomenclatureSets.clear()
        productIdByBarcode.clear()
        val targetSize = productsById.size / 2
        val iterator = productsById.entries.iterator()
        while (productsById.size > targetSize && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
        TsdLogger.info(
            category = "CACHE",
            event = "trimmed_on_memory_pressure",
            fields = mapOf("remainingProducts" to productsById.size)
        )
    }

    /**
     * Clears ONLY ephemeral reference cache. Persistent drafts and journals remain untouched.
     */
    fun clearAll() = synchronized(lock) {
        storesEntry = null
        nomenclatureSets.clear()
        productsById.clear()
        productIdByBarcode.clear()
        TsdLogger.info(category = "CACHE", event = "reference_cache_cleared")
    }

    data class CacheStats(
        val hasStores: Boolean,
        val nomenclatureSetCount: Int,
        val productCount: Int,
        val barcodeCount: Int
    )

    fun stats(): CacheStats = synchronized(lock) {
        CacheStats(
            hasStores = storesEntry != null,
            nomenclatureSetCount = nomenclatureSets.size,
            productCount = productsById.size,
            barcodeCount = productIdByBarcode.size
        )
    }
}
