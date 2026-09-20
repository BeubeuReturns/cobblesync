package com.cobblesync.web

import com.cobblesync.player.PlayerRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler

/** Serves GET /api/players — players seen by the mod (uuid + name), for the dashboard tabs. */
class PlayersHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.sendResponseHeaders(405, -1)
            return
        }

        val array = JsonArray()
        PlayerRegistry.all().forEach { player ->
            val obj = JsonObject()
            obj.addProperty("uuid", player.uuid)
            obj.addProperty("name", player.name)
            array.add(obj)
        }

        respondJson(exchange, 200, array.toString())
    }
}
