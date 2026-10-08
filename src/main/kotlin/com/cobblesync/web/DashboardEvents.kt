package com.cobblesync.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * GET /api/events: the dashboard's single SSE stream. Browsers allow 6 connections per host
 * across all tabs, and one stream per feed (activity, leaderboard, player) used to starve image
 * loads as soon as two tabs were open. Events are signals, not diffs: the client refetches.
 *  - capture-log-updated, leaderboard-updated
 *  - pokedex-updated, data {"uuid": ...}: the client ignores other players'.
 */
object DashboardEvents : HttpHandler {
    private val connections = CopyOnWriteArraySet<OutputStream>()

    fun captureLogUpdated() = broadcast("capture-log-updated", "{}")

    fun leaderboardUpdated() = broadcast("leaderboard-updated", "{}")

    fun pokedexUpdated(uuid: UUID) = broadcast("pokedex-updated", "{\"uuid\":\"$uuid\"}")

    private fun broadcast(event: String, data: String) {
        if (connections.isEmpty()) return
        BroadcastExecutor.execute {
            val message = "event: $event\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8)
            for (out in connections) {
                try {
                    synchronized(out) {
                        out.write(message)
                        out.flush()
                    }
                } catch (e: Exception) {
                    connections.remove(out)
                }
            }
        }
    }

    /** On server stop: ends every stream, so browsers reconnect to the next world's server. */
    fun closeAll() {
        for (out in connections) runCatching { out.close() }
        connections.clear()
    }

    override fun handle(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)

        val out = exchange.responseBody
        connections.add(out)
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
            connections.remove(out)
            exchange.close()
        }
    }
}
