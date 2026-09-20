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

data class KnownPlayer(val uuid: String, val name: String)

/**
 * Lightweight registry of players seen by the mod (uuid -> name), used to build the dashboard's
 * player tabs since Cobblemon has no player-enumeration API. Persisted as JSON.
 */
object PlayerRegistry {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = TypeToken.getParameterized(List::class.java, KnownPlayer::class.java).type
    private val players = ConcurrentHashMap<UUID, String>()
    private var storagePath: Path? = null
    private val saver = DebouncedSaver { save() }

    fun load(path: Path) {
        storagePath = path
        if (!Files.exists(path)) return
        runCatching {
            Files.newBufferedReader(path).use { reader ->
                val loaded: List<KnownPlayer>? = gson.fromJson(reader, listType)
                loaded?.forEach { players[UUID.fromString(it.uuid)] = it.name }
            }
        }.onFailure { CobbleSync.LOGGER.warn("Failed to read players.json", it) }
    }

    fun recordJoin(uuid: UUID, name: String) {
        val previous = players.put(uuid, name)
        if (previous != name) saver.requestSave()
    }

    fun all(): List<KnownPlayer> = players.entries
        .map { KnownPlayer(it.key.toString(), it.value) }
        .sortedBy { it.name.lowercase() }

    fun nameOf(uuid: UUID): String? = players[uuid]

    fun shutdown() = saver.shutdown()

    private fun save() {
        val path = storagePath ?: return
        runCatching {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { writer -> gson.toJson(all(), writer) }
        }.onFailure { CobbleSync.LOGGER.warn("Failed to write players.json", it) }
    }
}
