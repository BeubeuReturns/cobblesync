package com.cobblesync.discord

import com.cobblesync.CobbleSync
import com.cobblesync.config.DiscordConfig
import com.cobblesync.data.CobblemonLang
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Posts capture notifications to a Discord webhook (JDK HttpClient, no extra dependency).
 * Fire-and-forget on its own single-thread executor — never blocks or throws back into the
 * Cobblemon event-dispatch thread, and keeps working independently of the dashboard's web
 * server (which can be disabled in webserver.conf while notifications stay on).
 */
object DiscordNotifier {
    @Volatile
    private var config: DiscordConfig = DiscordConfig(false, "", "en", true, false)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "cobblesync-discord").apply { isDaemon = true } }
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun configure(config: DiscordConfig) {
        this.config = config
    }

    fun notifyShinyCaught(playerName: String, speciesId: String) {
        val c = config
        if (!c.enabled || !c.notifyShiny || c.webhookUrl.isBlank()) return
        val name = displayName(speciesId, c.language)
        val message = if (c.language == "fr") {
            "✨ **$playerName** a capturé un **$name** shiny !"
        } else {
            "✨ **$playerName** caught a shiny **$name**!"
        }
        post(c.webhookUrl, message)
    }

    fun notifyNewSpecies(playerName: String, speciesId: String) {
        val c = config
        if (!c.enabled || !c.notifyNewSpecies || c.webhookUrl.isBlank()) return
        val name = displayName(speciesId, c.language)
        val message = if (c.language == "fr") {
            "**$playerName** a capturé **$name** pour la première fois."
        } else {
            "**$playerName** caught **$name** for the first time."
        }
        post(c.webhookUrl, message)
    }

    fun shutdown() {
        executor.shutdown()
    }

    private fun displayName(speciesId: String, language: String): String {
        val path = speciesId.substringAfter(':')
        return if (language == "fr") CobblemonLang.speciesNameFr(path, path) else CobblemonLang.speciesNameEn(path, path)
    }

    private fun post(webhookUrl: String, content: String) {
        executor.submit {
            try {
                val payload = JsonObject().apply { addProperty("content", content) }.toString()
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() >= 300) {
                    CobbleSync.LOGGER.warn("Discord webhook returned HTTP {}", response.statusCode())
                }
            } catch (e: Exception) {
                CobbleSync.LOGGER.warn("Failed to post Discord webhook notification", e)
            }
        }
    }
}
