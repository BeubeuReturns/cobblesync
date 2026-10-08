package com.cobblesync.data

import com.cobblesync.CobbleSync
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads mods' bundled lang files (assets/<namespace>/lang/<file>.json) straight from the
 * classpath, which works server-side too. Loaded once per namespace/language, never invalidated
 * (static jar contents). Vanilla Minecraft only ships en_us in its jar; other languages are
 * client-side downloads, so they're simply missing here.
 */
object LangFiles {
    private val cache = ConcurrentHashMap<String, Map<String, String>>()
    private val mapType = TypeToken.getParameterized(Map::class.java, String::class.java, String::class.java).type

    fun get(namespace: String, file: String): Map<String, String> =
        cache.computeIfAbsent("$namespace/$file") { load(namespace, file) }

    private fun load(namespace: String, file: String): Map<String, String> {
        val stream = CobbleSync::class.java.classLoader.getResourceAsStream("assets/$namespace/lang/$file.json")
            ?: return emptyMap()
        return stream.use { input ->
            @Suppress("UNCHECKED_CAST")
            (Gson().fromJson(input.reader(StandardCharsets.UTF_8), mapType) as? Map<String, String>) ?: emptyMap()
        }
    }
}
