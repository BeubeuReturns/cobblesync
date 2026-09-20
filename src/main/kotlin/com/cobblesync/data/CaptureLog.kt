package com.cobblesync.data

import com.cobblesync.CobbleSync
import com.cobblesync.util.DebouncedSaver
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class CaptureLogEntry(
    val playerUuid: String,
    val speciesId: String,
    val shiny: Boolean,
    val timestampMillis: Long
)

/**
 * Rolling log of captures across all players, for the community activity feed. Bounded to
 * MAX_ENTRIES, persisted as JSON, newest first.
 */
object CaptureLog {
    private const val MAX_ENTRIES = 200

    private val gson: Gson = GsonBuilder().create()
    private val listType = TypeToken.getParameterized(List::class.java, CaptureLogEntry::class.java).type

    private val entries = CopyOnWriteArrayList<CaptureLogEntry>()
    private var storagePath: Path? = null
    private val saver = DebouncedSaver { save() }

    fun load(path: Path) {
        storagePath = path
        if (!Files.exists(path)) return
        runCatching {
            Files.newBufferedReader(path).use { reader ->
                val loaded: List<CaptureLogEntry>? = gson.fromJson(reader, listType)
                loaded?.let { entries.addAll(it) }
            }
        }.onFailure { CobbleSync.LOGGER.warn("Failed to read capture-log.json", it) }
    }

    fun add(playerUuid: UUID, speciesId: String, shiny: Boolean) {
        entries.add(0, CaptureLogEntry(playerUuid.toString(), speciesId, shiny, System.currentTimeMillis()))
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        saver.requestSave()
    }

    fun all(): List<CaptureLogEntry> = entries.toList()

    fun shutdown() = saver.shutdown()

    private fun save() {
        val path = storagePath ?: return
        runCatching {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { writer -> gson.toJson(entries.toList(), writer) }
        }.onFailure { CobbleSync.LOGGER.warn("Failed to write capture-log.json", it) }
    }
}
