package com.cobblesync.player

import com.cobblesync.CobbleSync
import com.cobblesync.util.DebouncedSaver
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * First-caught timestamp per player per species (epoch millis). Cobblemon's pokedex API doesn't
 * track this, so it's recorded here the first time a species reaches "caught" tier.
 */
object CaptureDates {
    private val gson: Gson = GsonBuilder().create()
    private val mapType = TypeToken.getParameterized(
        Map::class.java,
        String::class.java,
        TypeToken.getParameterized(Map::class.java, String::class.java, java.lang.Long::class.java).type
    ).type

    private val dates = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>>()
    private var storagePath: Path? = null
    private val saver = DebouncedSaver { save() }

    fun load(path: Path) {
        storagePath = path
        if (!Files.exists(path)) return
        runCatching {
            Files.newBufferedReader(path).use { reader ->
                val loaded: Map<String, Map<String, Long>>? = gson.fromJson(reader, mapType)
                loaded?.forEach { (uuid, perSpecies) -> dates[UUID.fromString(uuid)] = ConcurrentHashMap(perSpecies) }
            }
        }.onFailure { CobbleSync.LOGGER.warn("Failed to read capture-dates.json", it) }
    }

    fun get(uuid: UUID, speciesId: String): Long? = dates[uuid]?.get(speciesId)

    /** All recorded entries for a player (plain "speciesId" and "speciesId#shiny" keys mixed). */
    fun entriesFor(uuid: UUID): Map<String, Long> = dates[uuid]?.toMap() ?: emptyMap()

    /** Returns true if this call actually recorded a new date (i.e. a genuinely new capture). */
    fun recordIfMissing(uuid: UUID, speciesId: String): Boolean {
        val perPlayer = dates.getOrPut(uuid) { ConcurrentHashMap() }
        val isNew = perPlayer.putIfAbsent(speciesId, System.currentTimeMillis()) == null
        if (isNew) saver.requestSave()
        return isNew
    }

    fun shutdown() = saver.shutdown()

    private fun save() {
        val path = storagePath ?: return
        runCatching {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { writer ->
                gson.toJson(dates.mapKeys { it.key.toString() }, writer)
            }
        }.onFailure { CobbleSync.LOGGER.warn("Failed to write capture-dates.json", it) }
    }
}
