package com.cobblesync.web

import com.cobblesync.data.ItemIcons
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import net.minecraft.resources.ResourceLocation

/**
 * Serves GET /api/item/{id}.png (e.g. /api/item/cobblemon:moon_stone.png), the item's icon
 * texture, see [ItemIcons]. Missing icons 404 and the frontend falls back to a text-only badge.
 */
class ItemIconHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            respondJson(exchange, 405, jsonError("Method not allowed"))
            return
        }

        val segments = exchange.requestURI.path.trim('/').split("/")
        // ["api", "item", "{id}"]
        if (segments.size != 3) {
            respondJson(exchange, 404, jsonError("Unknown route"))
            return
        }

        // The frontend requests "{id}.png": WAFs like CrowdSec count extension-less URLs as page
        // crawling, and a species page loads dozens of icons at once. Bare ids still work.
        val resourceLocation = ResourceLocation.tryParse(segments[2].removeSuffix(".png"))
        if (resourceLocation == null) {
            respondJson(exchange, 400, jsonError("Invalid item id"))
            return
        }

        val bytes = ItemIcons.png(resourceLocation)
        if (bytes == null) {
            respondJson(exchange, 404, jsonError("Unknown item icon"))
            return
        }

        exchange.responseHeaders.add("Content-Type", "image/png")
        // Static per server run (see ItemIconCache), safe to let the browser cache aggressively,
        // unlike the dashboard's own HTML/JS/CSS which admins may edit live.
        exchange.responseHeaders.add("Cache-Control", "public, max-age=3600")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
