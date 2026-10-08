package com.cobblesync.data

import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache of static per-species info (types, stats, spawns) served by /api/species/{id}, keyed by
 * species id plus regional form ("cobblemon:vulpix#Alola"). Never changes while a server is
 * running, except on a data reload.
 */
object SpeciesInfoCache {
    private val cache = ConcurrentHashMap<String, JsonObject>()

    fun getOrCompute(key: String, compute: () -> JsonObject?): JsonObject? {
        cache[key]?.let { return it }
        val computed = compute() ?: return null
        cache[key] = computed
        return computed
    }

    fun invalidate() {
        cache.clear()
    }
}
