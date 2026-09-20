package com.cobblesync.data

import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache of static per-species info (types, stats, spawns) served by /api/species/{id} — never
 * changes while a server is running, except on a data reload.
 */
object SpeciesInfoCache {
    private val cache = ConcurrentHashMap<ResourceLocation, JsonObject>()

    fun getOrCompute(id: ResourceLocation, compute: () -> JsonObject?): JsonObject? {
        cache[id]?.let { return it }
        val computed = compute() ?: return null
        cache[id] = computed
        return computed
    }

    fun invalidate() {
        cache.clear()
    }
}
