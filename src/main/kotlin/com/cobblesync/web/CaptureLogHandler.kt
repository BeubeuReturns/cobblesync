package com.cobblesync.web

import com.cobblesync.data.CaptureLog
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.nio.charset.StandardCharsets

/** Serves GET /api/capture-log and GET /api/capture-log/events (SSE) — the global activity feed. */
class CaptureLogHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val segments = exchange.requestURI.path.trim('/').split("/")
        when {
            segments.size == 2 -> handleLog(exchange)
            segments.size == 3 && segments[2] == "events" -> handleEvents(exchange)
            else -> respondJson(exchange, 404, jsonError("Unknown route"))
        }
    }

    private fun handleLog(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            respondJson(exchange, 405, jsonError("Method not allowed"))
            return
        }
        val array = JsonArray()
        CaptureLog.all().forEach { entry ->
            val obj = JsonObject()
            obj.addProperty("playerUuid", entry.playerUuid)
            obj.addProperty("speciesId", entry.speciesId)
            obj.addProperty("shiny", entry.shiny)
            obj.addProperty("timestampMillis", entry.timestampMillis)
            array.add(obj)
        }
        respondJson(exchange, 200, array.toString())
    }

    /** SSE: a signal (not a diff) that a new capture happened — the client refetches the log. */
    private fun handleEvents(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)

        val out = exchange.responseBody
        CaptureLogBroadcaster.register(out)
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
            CaptureLogBroadcaster.unregister(out)
            exchange.close()
        }
    }
}
