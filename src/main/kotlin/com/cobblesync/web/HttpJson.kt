package com.cobblesync.web

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import java.nio.charset.StandardCharsets

fun respondJson(exchange: HttpExchange, status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

fun jsonError(message: String): String {
    val obj = JsonObject()
    obj.addProperty("error", message)
    return obj.toString()
}
