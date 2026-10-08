package com.cobblesync.web

import com.cobblesync.CobbleSync
import com.cobblesync.data.CobblemonLang
import com.cobblesync.data.ItemNames
import com.cobblesync.data.RegionalForms
import com.cobblesync.player.CaptureDates.regionalCaptureKey
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.resources.ResourceLocation
import com.cobblesync.data.PlayerProgress
import com.cobblesync.data.WorldDataCache
import com.cobblesync.integration.PokeBadgesIntegration
import com.cobblesync.player.CaptureDates
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokedex.FormDexRecord
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress
import com.cobblemon.mod.common.api.pokedex.SpeciesDexRecord
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import net.minecraft.core.registries.BuiltInRegistries
import java.util.UUID

/**
 * Serves /api/players/{uuid}/pokedex, /team and /badges. Updates: see [DashboardEvents].
 *
 * "tier" ("caught"/"seen"/"unregistered") is a stable vocabulary, independent from Cobblemon's
 * enum names (which changed between versions). "knowledgeRaw" keeps the raw name for debugging.
 */
class PokedexHandler : HttpHandler {
    companion object {
        // getHighestLevel()/FormDexRecord.getKnowledge() are genuinely present at the JVM
        // bytecode level (verified with javap -v, even in Kotlin's own metadata), but Fabric
        // Loom's mod-jar remapping corrupts enough of that metadata that Kotlin's compiler can't
        // resolve them as normal calls. Same quirk hit for FormDexRecord.getKnowledge() on 1.7.3;
        // still present on 1.8.1, and not something a future Cobblemon release fixes since it's
        // about our own build pipeline. Reflection bypasses Kotlin's static resolution and calls
        // the real method directly; looked up once, not per request.
        private val speciesHighestLevelMethod = SpeciesDexRecord::class.java.getMethod("getHighestLevel")
        private val formKnowledgeMethod = FormDexRecord::class.java.getMethod("getKnowledge")

        private fun highestLevelOf(record: SpeciesDexRecord): Int =
            speciesHighestLevelMethod.invoke(record) as Int

        private fun knowledgeOf(formRecord: FormDexRecord): PokedexEntryProgress =
            formKnowledgeMethod.invoke(formRecord) as PokedexEntryProgress
    }

    override fun handle(exchange: HttpExchange) {
        val segments = exchange.requestURI.path.trim('/').split("/")
        // ["api", "players", "{uuid}", "pokedex"|"team"|"badges"]
        if (segments.size != 4) {
            respondJson(exchange, 404, jsonError("Unknown route"))
            return
        }

        when (segments[3]) {
            "pokedex" -> handlePokedex(exchange, segments[2])
            "team" -> handleTeam(exchange, segments[2])
            "badges" -> handleBadges(exchange, segments[2])
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

            // Cobblemon 1.8 confirmed PokedexEntryProgress's real constant names
            // (UNREGISTERED/SEEN/OWNED) against the published jar, named enum instead of ordinal.
            val speciesTier = when (record?.getKnowledge()) {
                PokedexEntryProgress.OWNED -> "caught"
                PokedexEntryProgress.SEEN -> "seen"
                else -> "unregistered"
            }
            when (speciesTier) {
                "caught" -> caughtCount++
                "seen" -> seenCount++
            }

            val species = PokemonSpecies.getByIdentifier(speciesId)

            // Pokedex display forms split in two: regional ones (Alola, Galar, Hisui, Paldea) go in
            // "regionals" (one tab each in the sheet), the rest stay in "forms".
            val standardForms = JsonObject()
            val regionalForms = LinkedHashMap<String, Pair<FormData, JsonObject>>()
            if (species != null) {
                for (entry in world.entriesBySpecies[speciesId].orEmpty()) {
                    for (pokedexForm in entry.forms) {
                        val displayForm = pokedexForm.displayForm
                        if (standardForms.has(displayForm) || regionalForms.containsKey(displayForm)) continue
                        val formObj = formJson(record?.getFormRecord(displayForm))
                        val formData = RegionalForms.formOf(species, pokedexForm)
                        if (RegionalForms.isRegional(formData)) {
                            regionalForms[displayForm] = formData to formObj
                        } else {
                            standardForms.add(displayForm, formObj)
                        }
                    }
                }
            }

            // One card per species, tier included, like Cobblemon's own Pokedex.
            val cardTier = speciesTier

            val speciesObj = JsonObject()
            speciesObj.addProperty("tier", cardTier)
            // Dex number + possible genders: lets the client sort/filter without an async
            // per-card request on first render.
            species?.let {
                speciesObj.addProperty("nationalDexNumber", it.nationalPokedexNumber)
                val possibleGenders = JsonArray()
                it.possibleGenders.forEach { gender -> possibleGenders.add(gender.name) }
                speciesObj.add("possibleGenders", possibleGenders)
            }

            // Never encountered: no FormDexRecord, nothing more than the dex number is returned.
            // The frontend shows "???" with no sprite or detail.
            if (record != null) {
                // Added here (not just via /api/species/{id}) so search works on first render,
                // without waiting on the per-card async fetch.
                species?.let {
                    speciesObj.addProperty("nameEn", CobblemonLang.speciesNameEn(speciesId.path, it.name))
                    speciesObj.addProperty("nameFr", CobblemonLang.speciesNameFr(speciesId.path, it.name))
                    // Types too, so grid cards never need the heavy /api/species detail.
                    speciesObj.add("types", typesJson(it.standardForm))
                }

                speciesObj.addProperty("knowledgeRaw", record.getKnowledge().name)
                if (cardTier == "caught") {
                    CaptureDates.get(uuid, speciesId.toString())?.let { speciesObj.addProperty("caughtAtMillis", it) }
                }
                speciesObj.addProperty("highestLevel", highestLevelOf(record))

                val aspects = JsonArray()
                record.getAspects().forEach { aspects.add(it) }
                speciesObj.add("aspects", aspects)

                speciesObj.add("forms", standardForms)

                // One group per region, not per form: Paldean Tauros' three breeds (and Galarian
                // Darmanitan's Zen mode) share one, keyed by the region's main form.
                if (species != null && regionalForms.isNotEmpty()) {
                    val regionals = JsonObject()
                    regionalForms.entries
                        .groupBy { RegionalForms.regionOf(it.value.first)!! }
                        .forEach { (region, entries) ->
                            val members = entries.map { Triple(it.key, it.value.first, it.value.second) }
                            val main = members.firstOrNull { RegionalForms.variantOf(it.second, region) == null } ?: members.first()
                            regionals.add(main.second.name, regionalJson(uuid, species, speciesId, region, main.second, members))
                        }
                    speciesObj.add("regionals", regionals)
                }
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

    private fun tierOf(progress: PokedexEntryProgress?): String = when (progress) {
        PokedexEntryProgress.OWNED -> "caught"
        PokedexEntryProgress.SEEN -> "seen"
        else -> "unregistered"
    }

    /** {tier, genders, shinyStates} for one pokedex display form. */
    private fun formJson(formRecord: FormDexRecord?): JsonObject {
        val formObj = JsonObject()
        formObj.addProperty("tier", tierOf(formRecord?.let { knowledgeOf(it) }))
        val genders = JsonArray()
        formRecord?.getGenders()?.forEach { genders.add(it.name) }
        formObj.add("genders", genders)
        val shinyStates = JsonArray()
        formRecord?.getSeenShinyStates()?.forEach { shinyStates.add(it) }
        formObj.add("shinyStates", shinyStates)
        return formObj
    }

    private fun bestTier(forms: JsonObject): String {
        val tiers = forms.entrySet().map { it.value.asJsonObject.get("tier").asString }
        return when {
            "caught" in tiers -> "caught"
            "seen" in tiers -> "seen"
            else -> "unregistered"
        }
    }

    private fun typesJson(form: FormData): JsonArray {
        val types = JsonArray()
        types.add(form.primaryType.name)
        form.secondaryType?.let { types.add(it.name) }
        return types
    }

    /**
     * One region's forms within a species entry: {tier, form (main), region, groupForms, types,
     * caughtAtMillis, aspects, forms}. The frontend overlays it on the species entry for that
     * region's tab and composes "Alolan Vulpix"/"Goupix d'Alola". With several forms (Paldean
     * Tauros' breeds), tier is the best among them and types are the main form's.
     */
    private fun regionalJson(
        uuid: UUID,
        species: Species,
        speciesId: ResourceLocation,
        region: String,
        main: FormData,
        members: List<Triple<String, FormData, JsonObject>>
    ): JsonObject {
        val forms = JsonObject()
        members.forEach { (displayForm, _, formObj) -> forms.add(displayForm, formObj) }
        val tier = bestTier(forms)

        val obj = JsonObject()
        obj.addProperty("tier", tier)
        obj.addProperty("form", main.name)
        obj.addProperty("region", region)
        val groupForms = JsonArray()
        members.forEach { groupForms.add(it.second.name) }
        obj.add("groupForms", groupForms)
        obj.add("types", typesJson(main))
        if (tier == "caught") {
            members.mapNotNull { CaptureDates.get(uuid, regionalCaptureKey(speciesId.toString(), it.second.name)) }
                .minOrNull()
                ?.let { obj.addProperty("caughtAtMillis", it) }
        }
        // Only these forms' traits, not the species-wide aspect set.
        val aspects = LinkedHashSet<String>()
        members.forEach { (_, _, formObj) ->
            if (formObj.getAsJsonArray("shinyStates").any { it.asString == "shiny" }) aspects.add("shiny")
            formObj.getAsJsonArray("genders").forEach { aspects.add(it.asString.lowercase()) }
        }
        obj.add("aspects", JsonArray().apply { aspects.forEach { add(it) } })
        obj.add("forms", forms)
        return obj
    }

    /** Gym badges from the optional PokeBadges mod; {"available": false} when it isn't installed. */
    private fun handleBadges(exchange: HttpExchange, rawUuid: String) {
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
        respondJson(exchange, 200, PokeBadgesIntegration.badgesJson(CobbleSync.server, uuid).toString())
    }

    /**
     * Current party, online players only. Cobblemon already keeps a connected player's party
     * fully resident in memory (it needs live access for battles), so reading it here is a plain
     * in-memory read, not a disk load. An offline player's party store isn't necessarily loaded
     * at all, and loading it just to show a "team" nobody is playing isn't worth the extra I/O,
     * so those simply report online:false with an empty team.
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
            // Real form, so an Alolan Vulpix shows as one (name, types, sprite, sheet).
            RegionalForms.regionOf(pokemon.form)?.let { region ->
                obj.addProperty("form", pokemon.form.name)
                obj.addProperty("region", region)
            }
            obj.add("types", typesJson(pokemon.form))
            val aspects = JsonArray()
            pokemon.aspects.forEach { aspects.add(it) }
            obj.add("aspects", aspects)

            // Party-detail fields (nature/ability/held item/IV-EV), only meaningful for a live
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
                val heldItemId = BuiltInRegistries.ITEM.getKey(heldItem.item)
                obj.addProperty("heldItemId", heldItemId.toString())
                ItemNames.addTo(obj, heldItemId, "heldItem")
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
