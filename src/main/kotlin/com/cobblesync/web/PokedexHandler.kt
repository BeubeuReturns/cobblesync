package com.cobblesync.web

import com.cobblesync.CobbleSync
import com.cobblesync.data.CobblemonLang
import com.cobblesync.data.PlayerProgress
import com.cobblesync.data.WorldDataCache
import com.cobblesync.player.CaptureDates
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import net.minecraft.core.registries.BuiltInRegistries
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Serves /api/players/{uuid}/pokedex, /api/players/{uuid}/events (SSE), and
 * /api/players/{uuid}/team.
 *
 * "tier" ("caught"/"seen"/"unregistered") is a stable vocabulary, independent from Cobblemon's
 * enum names (which changed between versions). "knowledgeRaw" keeps the raw name for debugging.
 */
class PokedexHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val segments = exchange.requestURI.path.trim('/').split("/")
        // ["api", "players", "{uuid}", "pokedex"|"events"|"team"]
        if (segments.size != 4) {
            respondJson(exchange, 404, jsonError("Unknown route"))
            return
        }

        when (segments[3]) {
            "pokedex" -> handlePokedex(exchange, segments[2])
            "events" -> handleEvents(exchange, segments[2])
            "team" -> handleTeam(exchange, segments[2])
            else -> respondJson(exchange, 404, jsonError("Unknown route"))
        }
    }

    private fun handlePokedex(exchange: HttpExchange, rawUuid: String) {
        if (exchange.requestMethod != "GET") {
            respondJson(exchange, 405, jsonError("Method not allowed"))
            return
        }

        val uuid = try {
            UUID.fromString(rawUuid)
        } catch (e: IllegalArgumentException) {
            respondJson(exchange, 400, jsonError("Invalid UUID"))
            return
        }

        val pokedex = Cobblemon.playerDataManager.getPokedexData(uuid)
        val world = WorldDataCache.get()

        val relevantSpeciesIds = PlayerProgress.relevantSpeciesIds(world)

        var caughtCount = 0
        var seenCount = 0

        val speciesJson = JsonObject()
        for (speciesId in relevantSpeciesIds) {
            val record = pokedex.speciesRecords[speciesId]

            // .ordinal is a plain Java enum method, unaffected by the Kotlin accessor quirks seen
            // elsewhere on this jar.
            val speciesTier = when (record?.getKnowledge()?.ordinal) {
                2 -> "caught"
                1 -> "seen"
                else -> "unregistered"
            }
            when (speciesTier) {
                "caught" -> caughtCount++
                "seen" -> seenCount++
            }

            val species = PokemonSpecies.getByIdentifier(speciesId)

            val speciesObj = JsonObject()
            speciesObj.addProperty("tier", speciesTier)
            // Dex number + possible genders: lets the client sort/filter without an async
            // per-card request on first render.
            species?.let {
                speciesObj.addProperty("nationalDexNumber", it.nationalPokedexNumber)
                val possibleGenders = JsonArray()
                it.possibleGenders.forEach { gender -> possibleGenders.add(gender.name) }
                speciesObj.add("possibleGenders", possibleGenders)
            }

            // Never encountered: no FormDexRecord, nothing more than the dex number is returned —
            // the frontend shows "???" with no sprite or detail.
            if (record != null) {
                // Added here (not just via /api/species/{id}) so search works on first render,
                // without waiting on the per-card async fetch.
                species?.let {
                    speciesObj.addProperty("nameEn", CobblemonLang.speciesNameEn(speciesId.path, it.name))
                    speciesObj.addProperty("nameFr", CobblemonLang.speciesNameFr(speciesId.path, it.name))
                }

                speciesObj.addProperty("knowledgeRaw", record.getKnowledge().name)
                if (speciesTier == "caught") {
                    CaptureDates.get(uuid, speciesId.toString())?.let { speciesObj.addProperty("caughtAtMillis", it) }
                }
                // highestLevel isn't in the published 1.7.3 jar (only in the 1.8.0-dev source) —
                // add it back once released.

                val aspects = JsonArray()
                record.getAspects().forEach { aspects.add(it) }
                speciesObj.add("aspects", aspects)

                val formsJson = JsonObject()
                for (entry in world.entriesBySpecies[speciesId].orEmpty()) {
                    // FormDexRecord.getKnowledge() isn't accessible on this jar; derive the same
                    // tier from the pokedex manager's public methods instead.
                    val ownedForms = pokedex.getCaughtForms(entry).map { it.displayForm }.toSet()
                    val seenForms = pokedex.getEncounteredForms(entry).map { it.displayForm }.toSet()

                    for (form in entry.forms) {
                        if (formsJson.has(form.displayForm)) continue
                        val formRecord = record.getFormRecord(form.displayForm)

                        val formTier = when {
                            formRecord == null -> "unregistered"
                            form.displayForm in ownedForms -> "caught"
                            form.displayForm in seenForms -> "seen"
                            else -> "unregistered"
                        }

                        val formObj = JsonObject()
                        formObj.addProperty("tier", formTier)

                        val genders = JsonArray()
                        formRecord?.getGenders()?.forEach { genders.add(it.name) }
                        formObj.add("genders", genders)

                        val shinyStates = JsonArray()
                        formRecord?.getSeenShinyStates()?.forEach { shinyStates.add(it) }
                        formObj.add("shinyStates", shinyStates)

                        formsJson.add(form.displayForm, formObj)
                    }
                }
                speciesObj.add("forms", formsJson)
            }

            speciesJson.add(speciesId.toString(), speciesObj)
        }

        val root = JsonObject()
        root.addProperty("uuid", uuid.toString())
        root.addProperty("totalKnownSpecies", relevantSpeciesIds.size)
        root.addProperty("caughtCount", caughtCount)
        root.addProperty("seenCount", seenCount)
        root.add("species", speciesJson)

        respondJson(exchange, 200, root.toString())
    }

    /** SSE: a signal (not a diff) that the pokedex changed — the client does a plain refetch. */
    private fun handleEvents(exchange: HttpExchange, rawUuid: String) {
        val uuid = try {
            UUID.fromString(rawUuid)
        } catch (e: IllegalArgumentException) {
            respondJson(exchange, 400, jsonError("Invalid UUID"))
            return
        }

        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)

        val out = exchange.responseBody
        PokedexEventBroadcaster.register(uuid, out)
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
            PokedexEventBroadcaster.unregister(uuid, out)
            exchange.close()
        }
    }

    /**
     * Current party — online players only. Cobblemon already keeps a connected player's party
     * fully resident in memory (it needs live access for battles), so reading it here is a plain
     * in-memory read, not a disk load; an offline player's party store isn't necessarily loaded
     * at all, and loading it just to show a "team" nobody is actively playing with isn't worth
     * the extra I/O, so those simply report online:false with an empty team instead.
     */
    private fun handleTeam(exchange: HttpExchange, rawUuid: String) {
        if (exchange.requestMethod != "GET") {
            respondJson(exchange, 405, jsonError("Method not allowed"))
            return
        }

        val uuid = try {
            UUID.fromString(rawUuid)
        } catch (e: IllegalArgumentException) {
            respondJson(exchange, 400, jsonError("Invalid UUID"))
            return
        }

        val player = CobbleSync.server?.playerList?.getPlayer(uuid)
        val root = JsonObject()
        if (player == null) {
            root.addProperty("online", false)
            root.add("team", JsonArray())
            respondJson(exchange, 200, root.toString())
            return
        }

        root.addProperty("online", true)
        val team = JsonArray()
        Cobblemon.storage.getParty(player).forEach { pokemon ->
            val species = pokemon.species
            val obj = JsonObject()
            obj.addProperty("uuid", pokemon.uuid.toString())
            obj.addProperty("speciesId", species.resourceIdentifier.toString())
            obj.addProperty("nationalDexNumber", species.nationalPokedexNumber)
            obj.addProperty("nameEn", CobblemonLang.speciesNameEn(species.resourceIdentifier.path, species.name))
            obj.addProperty("nameFr", CobblemonLang.speciesNameFr(species.resourceIdentifier.path, species.name))
            pokemon.nickname?.let { obj.addProperty("nickname", it.string) }
            obj.addProperty("level", pokemon.level)
            obj.addProperty("currentHealth", pokemon.currentHealth)
            obj.addProperty("maxHealth", pokemon.maxHealth)
            obj.addProperty("shiny", pokemon.shiny)
            obj.addProperty("gender", pokemon.gender.name)
            val aspects = JsonArray()
            pokemon.aspects.forEach { aspects.add(it) }
            obj.add("aspects", aspects)

            // Party-detail fields (nature/ability/held item/IV-EV) — only meaningful for a live
            // party Pokémon, used by the team-card detail popup on the frontend.
            val nature = pokemon.nature
            obj.addProperty("natureNameEn", CobblemonLang.natureNameEn(nature.name.path))
            obj.addProperty("natureNameFr", CobblemonLang.natureNameFr(nature.name.path))
            nature.increasedStat?.let { obj.addProperty("natureIncreasedStat", it.showdownId) }
            nature.decreasedStat?.let { obj.addProperty("natureDecreasedStat", it.showdownId) }

            val ability = pokemon.ability
            obj.addProperty("abilityNameEn", CobblemonLang.abilityNameEn(ability.name))
            obj.addProperty("abilityNameFr", CobblemonLang.abilityNameFr(ability.name))
            CobblemonLang.abilityDescEn(ability.name)?.let { obj.addProperty("abilityDescEn", it) }
            CobblemonLang.abilityDescFr(ability.name)?.let { obj.addProperty("abilityDescFr", it) }

            val heldItem = pokemon.heldItem()
            if (!heldItem.isEmpty) {
                obj.addProperty("heldItemId", BuiltInRegistries.ITEM.getKey(heldItem.item).toString())
            }

            val ivsObj = JsonObject()
            val evsObj = JsonObject()
            Stats.PERMANENT.forEach { stat ->
                ivsObj.addProperty(stat.showdownId, pokemon.ivs.getOrDefault(stat))
                evsObj.addProperty(stat.showdownId, pokemon.evs.getOrDefault(stat))
            }
            obj.add("ivs", ivsObj)
            obj.add("evs", evsObj)

            team.add(obj)
        }
        root.add("team", team)

        respondJson(exchange, 200, root.toString())
    }
}
