package com.cobblesync.web

import com.cobblesync.CobbleSync
import com.cobblesync.data.ItemIconCache
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import net.minecraft.resources.ResourceLocation

/**
 * Serves GET /api/item/{id} (e.g. /api/item/cobblemon:moon_stone) — the item's own icon texture,
 * extracted straight from whatever mod jar bundles it (same classpath-resource technique
 * CobblemonLang already uses for lang files). No 3D rendering needed: items are flat textures,
 * unlike Pokémon models. Cached: see [ItemIconCache].
 *
 * Only works for items whose mod bundles its assets in one unified jar (true for Cobblemon, and
 * for well-behaved Fabric mods generally) — vanilla Minecraft items have no texture available
 * server-side at all (client-only resources, not present in a dedicated server's classpath), so
 * those 404 and the frontend falls back to a text-only badge, same graceful-degradation pattern
 * used everywhere else in this project.
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

        val resourceLocation = try {
            ResourceLocation.parse(segments[2])
        } catch (e: Exception) {
            respondJson(exchange, 400, jsonError("Invalid item id"))
            return
        }

        val bytes = ItemIconCache.getOrCompute(resourceLocation) { resolveIcon(resourceLocation) }
        if (bytes == null) {
            respondJson(exchange, 404, jsonError("Unknown item icon"))
            return
        }

        exchange.responseHeaders.add("Content-Type", "image/png")
        // Static per server run (see ItemIconCache) — safe to let the browser cache aggressively,
        // unlike the dashboard's own HTML/JS/CSS which admins may edit live.
        exchange.responseHeaders.add("Cache-Control", "public, max-age=3600")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun resolveIcon(id: ResourceLocation): ByteArray? {
        // Common case: a flat icon item, texture directly at textures/item/<path>.png.
        textureBytes(id.namespace, "item/${id.path}")?.let { return it }

        // Otherwise resolve via the item model's layer0 texture reference (e.g. items whose
        // texture lives in a subfolder, like Cobblemon's evolution stones).
        val modelStream = classLoader().getResourceAsStream("assets/${id.namespace}/models/item/${id.path}.json")
            ?: return null
        val layer0 = modelStream.use { stream ->
            JsonParser.parseReader(stream.reader()).asJsonObject
                .getAsJsonObject("textures")
                ?.get("layer0")
                ?.asString
        } ?: return null

        val textureRef = try {
            ResourceLocation.parse(layer0)
        } catch (e: Exception) {
            return null
        }
        return textureBytes(textureRef.namespace, textureRef.path)
    }

    private fun textureBytes(namespace: String, texturePath: String): ByteArray? {
        val stream = classLoader().getResourceAsStream("assets/$namespace/textures/$texturePath.png") ?: return null
        return stream.use { it.readBytes() }
    }

    private fun classLoader() = CobbleSync::class.java.classLoader
}
