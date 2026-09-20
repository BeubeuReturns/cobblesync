package com.cobblesync.data

import net.minecraft.resources.ResourceLocation
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache of resolved item icon PNG bytes served by /api/item/{id} — reading + parsing a model
 * JSON just to find a texture path isn't expensive, but it's pure classpath I/O with no reason
 * to repeat it per request. Misses (icon not found) are cached too via Optional.empty(), same
 * "never changes while the server runs" lifecycle as SpeciesInfoCache.
 */
object ItemIconCache {
    private val cache = ConcurrentHashMap<ResourceLocation, ByteArray?>()
    private val EMPTY = ByteArray(0)

    fun getOrCompute(id: ResourceLocation, compute: () -> ByteArray?): ByteArray? {
        val cached = cache[id]
        if (cached != null) return if (cached === EMPTY) null else cached
        val computed = compute()
        cache[id] = computed ?: EMPTY
        return computed
    }

    fun invalidate() {
        cache.clear()
    }
}
