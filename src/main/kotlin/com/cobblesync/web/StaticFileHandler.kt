package com.cobblesync.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.nio.file.Files
import java.nio.file.Path

class StaticFileHandler(private val webRoot: Path) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        var relative = exchange.requestURI.path.removePrefix("/")
        if (relative.isEmpty()) relative = "index.html"

        val requested = webRoot.resolve(relative).normalize()
        if (!requested.startsWith(webRoot) || !Files.isRegularFile(requested)) {
            val notFound = "404 - not found".toByteArray()
            exchange.sendResponseHeaders(404, notFound.size.toLong())
            exchange.responseBody.use { it.write(notFound) }
            return
        }

        val contentType = when (requested.toString().substringAfterLast('.', "")) {
            "html" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }

        // mtime+size as ETag: not a real hash, but enough to detect a changed file.
        val etag = "\"${Files.getLastModifiedTime(requested).toMillis()}-${Files.size(requested)}\""
        exchange.responseHeaders.add("ETag", etag)
        // no-cache forces revalidation on every request; the actual gain comes from 304s, not max-age.
        exchange.responseHeaders.add("Cache-Control", "no-cache")

        if (exchange.requestHeaders.getFirst("If-None-Match") == etag) {
            exchange.sendResponseHeaders(304, -1)
            exchange.responseBody.close()
            return
        }

        val bytes = Files.readAllBytes(requested)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
