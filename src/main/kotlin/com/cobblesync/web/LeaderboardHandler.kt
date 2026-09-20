package com.cobblesync.web

import com.cobblesync.data.PlayerProgress
import com.cobblesync.data.ProgressSummary
import com.cobblesync.data.WorldDataCache
import com.cobblesync.player.CaptureDates
import com.cobblesync.player.PlayerRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Serves GET /api/leaderboard and GET /api/leaderboard/events (SSE) — server-wide rankings:
 * completion %, shinies caught, and captures in the last 7 days.
 */
class LeaderboardHandler : HttpHandler {
    private data class Row(
        val uuid: String,
        val name: String,
        val summary: ProgressSummary,
        val shinyCount: Int,
        val weeklyCount: Int
    )

    override fun handle(exchange: HttpExchange) {
        val segments = exchange.requestURI.path.trim('/').split("/")
        when {
            segments.size == 2 -> handleLeaderboard(exchange)
            segments.size == 3 && segments[2] == "events" -> handleEvents(exchange)
            else -> respondJson(exchange, 404, jsonError("Unknown route"))
        }
    }

    private fun handleLeaderboard(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            respondJson(exchange, 405, jsonError("Method not allowed"))
            return
        }

        val world = WorldDataCache.get()
        val relevantSpeciesIds = PlayerProgress.relevantSpeciesIds(world)
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000

        val rows = PlayerRegistry.all().map { player ->
            val uuid = UUID.fromString(player.uuid)
            val summary = PlayerProgress.summarize(uuid, world, relevantSpeciesIds)
            val entries = CaptureDates.entriesFor(uuid)
            // Distinct species caught shiny, not total shiny catches — CaptureDates only
            // records a species' first shiny, not every duplicate.
            val shinyCount = entries.keys.count { it.endsWith("#shiny") }
            val weeklyCount = entries.values.count { it >= weekAgo }
            Row(player.uuid, player.name, summary, shinyCount, weeklyCount)
        }

        val root = JsonObject()
        root.add("completion", completionArray(rows))
        root.add("shinyCount", shinyCountArray(rows))
        root.add("weekly", weeklyArray(rows))

        respondJson(exchange, 200, root.toString())
    }

    private fun completionArray(rows: List<Row>): JsonArray {
        val array = JsonArray()
        rows.sortedWith(compareByDescending<Row> { percentOf(it.summary) }.thenBy { it.name.lowercase() })
            .forEach { row ->
                val obj = JsonObject()
                obj.addProperty("uuid", row.uuid)
                obj.addProperty("name", row.name)
                obj.addProperty("caughtCount", row.summary.caughtCount)
                obj.addProperty("totalKnownSpecies", row.summary.totalKnownSpecies)
                obj.addProperty("percent", percentOf(row.summary))
                array.add(obj)
            }
        return array
    }

    private fun shinyCountArray(rows: List<Row>): JsonArray {
        val array = JsonArray()
        rows.filter { it.shinyCount > 0 }
            .sortedWith(compareByDescending<Row> { it.shinyCount }.thenBy { it.name.lowercase() })
            .forEach { row ->
                val obj = JsonObject()
                obj.addProperty("uuid", row.uuid)
                obj.addProperty("name", row.name)
                obj.addProperty("count", row.shinyCount)
                array.add(obj)
            }
        return array
    }

    private fun weeklyArray(rows: List<Row>): JsonArray {
        val array = JsonArray()
        rows.filter { it.weeklyCount > 0 }
            .sortedWith(compareByDescending<Row> { it.weeklyCount }.thenBy { it.name.lowercase() })
            .forEach { row ->
                val obj = JsonObject()
                obj.addProperty("uuid", row.uuid)
                obj.addProperty("name", row.name)
                obj.addProperty("count", row.weeklyCount)
                array.add(obj)
            }
        return array
    }

    private fun percentOf(summary: ProgressSummary): Double =
        if (summary.totalKnownSpecies > 0) (summary.caughtCount.toDouble() / summary.totalKnownSpecies) * 100 else 0.0

    /** SSE: a signal (not a diff) that the leaderboard changed — the client does a plain refetch. */
    private fun handleEvents(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)

        val out = exchange.responseBody
        LeaderboardBroadcaster.register(out)
        try {
            while (true) {
                Thread.sleep(25_000)
                synchronized(out) {
                    out.write(":ping\n\n".toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
            }
        } catch (e: Exception) {
            // Client disconnected or server shutting down.
        } finally {
            LeaderboardBroadcaster.unregister(out)
            exchange.close()
        }
    }
}
