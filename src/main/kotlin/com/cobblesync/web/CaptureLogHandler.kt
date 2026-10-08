package com.cobblesync.web

import com.cobblesync.data.CaptureLog
import com.cobblesync.data.CobblemonLang
import com.cobblesync.data.RegionalForms
import com.cobblesync.player.PlayerRegistry
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/** Serves GET /api/capture-log, the global activity feed. Updates: see [DashboardEvents]. */
class CaptureLogHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val segments = exchange.requestURI.path.trim('/').split("/")
        when {
            segments.size == 2 -> handleLog(exchange)
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
            // Resolved here rather than client-side: the page's player list can be stale or empty
            // when the feed renders (e.g. a freshly opened world).
            runCatching { UUID.fromString(entry.playerUuid) }.getOrNull()
                ?.let { PlayerRegistry.nameOf(it) }
                ?.let { obj.addProperty("playerName", it) }
            obj.addProperty("speciesId", entry.speciesId)
            // Name and dex number inline, so the feed doesn't fetch each species' full detail.
            ResourceLocation.tryParse(entry.speciesId)?.let { PokemonSpecies.getByIdentifier(it) }?.let { species ->
                obj.addProperty("nationalDexNumber", species.nationalPokedexNumber)
                obj.addProperty("speciesNameEn", CobblemonLang.speciesNameEn(species.resourceIdentifier.path, species.name))
                obj.addProperty("speciesNameFr", CobblemonLang.speciesNameFr(species.resourceIdentifier.path, species.name))
                RegionalForms.formByName(species, entry.form)?.let { form ->
                    RegionalForms.regionOf(form)?.let { region ->
                        obj.addProperty("form", form.name)
                        obj.addProperty("region", region)
                    }
                }
            }
            obj.addProperty("shiny", entry.shiny)
            obj.addProperty("timestampMillis", entry.timestampMillis)
            array.add(obj)
        }
        respondJson(exchange, 200, array.toString())
    }

}
