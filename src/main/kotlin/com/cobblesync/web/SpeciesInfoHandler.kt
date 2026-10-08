package com.cobblesync.web

import com.cobblesync.data.BiomeCategoryResolver
import com.cobblesync.data.CobblemonLang
import com.cobblesync.data.ItemNames
import com.cobblesync.data.RegionalForms
import com.cobblemon.mod.common.pokemon.FormData
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import com.cobblesync.data.SpeciesInfoCache
import com.cobblemon.mod.common.api.drop.ItemDropEntry
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.evolution.variants.ItemInteractionEvolution
import com.cobblemon.mod.common.pokemon.evolution.variants.LevelUpEvolution
import com.cobblemon.mod.common.pokemon.evolution.variants.TradeEvolution
import com.cobblemon.mod.common.api.conditional.RegistryLikeCondition
import com.cobblemon.mod.common.api.conditional.RegistryLikeIdentifierCondition
import com.cobblemon.mod.common.api.conditional.RegistryLikeTagCondition
import com.cobblemon.mod.common.pokemon.requirements.BiomeRequirement
import com.cobblemon.mod.common.pokemon.requirements.FriendshipRequirement
import com.cobblemon.mod.common.pokemon.requirements.LevelRequirement
import net.minecraft.world.level.biome.Biome
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation

/**
 * Groups spawn entries by rarity+level+sky-light range, see the spawns-building loop in
 * [SpeciesInfoHandler]. skyLightRange is part of the key, not just unioned like biomes/structures,
 * because two entries with the same bucket/level can still mean different things: Gastly has
 * separate "common, lvl 6-31" entries for a dark-outdoors spawn and a lit "is_spooky" biome spawn,
 * and merging them would lose the light-condition distinction.
 */
private data class SpawnKey(val bucket: String, val levelRange: String?, val skyLightRange: String?)

/** Accumulates the union of biomes/structures across every SpawnDetail sharing a [SpawnKey]. */
private class SpawnGroup(val bucket: String, val levelRange: String?, val skyLightRange: String?) {
    val biomes = LinkedHashSet<String>()
    val structures = LinkedHashSet<String>()
}

/**
 * Second-pass merge key (see the spawns-building loop): [SpawnGroup]s landing on the same final
 * biome/structure set only differ by skyLightRange, meaning at least one path was unconditional.
 * Merged under this key so the light badge doesn't show on a duplicate list.
 */
private data class SpawnMergeKey(
    val bucket: String,
    val levelRange: String?,
    val biomes: Set<String>,
    val structures: Set<String>
)

/**
 * Serves GET /api/species/{id} (e.g. /api/species/cobblemon:bulbasaur): static per-species info
 * (types, stats, spawns), independent of any player. Cached, see [SpeciesInfoCache].
 */
class SpeciesInfoHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            respondJson(exchange, 405, jsonError("Method not allowed"))
            return
        }

        val segments = exchange.requestURI.path.trim('/').split("/")
        // ["api", "species", "{id}"]
        if (segments.size != 3) {
            respondJson(exchange, 404, jsonError("Unknown route"))
            return
        }

        val resourceLocation = try {
            ResourceLocation.parse(segments[2])
        } catch (e: Exception) {
            respondJson(exchange, 400, jsonError("Invalid species id"))
            return
        }

        // ?form=Alola serves that regional form's sheet; no parameter means the standard form.
        val formName = exchange.requestURI.rawQuery
            ?.split("&")
            ?.firstOrNull { it.startsWith("form=") }
            ?.substringAfter("=")
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            ?.takeIf { it.isNotBlank() }

        val cacheKey = if (formName == null) resourceLocation.toString() else "$resourceLocation#$formName"
        val root = SpeciesInfoCache.getOrCompute(cacheKey) { buildSpeciesInfo(resourceLocation, formName) }
        if (root == null) {
            respondJson(exchange, 404, jsonError("Unknown species"))
            return
        }

        respondJson(exchange, 200, root.toString())
    }

    private fun buildSpeciesInfo(resourceLocation: ResourceLocation, formName: String?): JsonObject? {
        val species = PokemonSpecies.getByIdentifier(resourceLocation) ?: return null
        // Only regional forms have their own sheet; anything else shows the standard form.
        val form = RegionalForms.formByName(species, formName)?.takeIf { RegionalForms.isRegional(it) }
            ?: species.standardForm
        val region = RegionalForms.regionOf(form)

        val root = JsonObject()
        root.addProperty("id", resourceLocation.toString())
        root.addProperty("nationalDexNumber", species.nationalPokedexNumber)
        // Both languages are sent together: switching the UI language needs no extra request.
        root.addProperty("nameEn", CobblemonLang.speciesNameEn(resourceLocation.path, species.name))
        root.addProperty("nameFr", CobblemonLang.speciesNameFr(resourceLocation.path, species.name))
        if (region != null) {
            root.addProperty("form", form.name)
            root.addProperty("region", region)
        }
        // Nullable: not every species has flavor text depending on version/addons.
        CobblemonLang.speciesDescEn(resourceLocation.path)?.let { root.addProperty("descriptionEn", it) }
        CobblemonLang.speciesDescFr(resourceLocation.path)?.let { root.addProperty("descriptionFr", it) }

        val types = JsonArray()
        types.add(form.primaryType.name)
        form.secondaryType?.let { types.add(it.name) }
        root.add("types", types)

        // Cobblemon stores height in decimeters and weight in hectograms.
        root.addProperty("heightM", form.height / 10.0)
        root.addProperty("weightKg", form.weight / 10.0)

        root.addProperty("catchRate", form.catchRate)
        // Omitted (not null) for genderless species, same sentinel Cobblemon's own
        // possibleGenders getter checks: -1F means no gender axis, not "0% male".
        if (form.maleRatio != -1F) {
            root.addProperty("malePercent", form.maleRatio * 100)
        }

        val baseStats = JsonObject()
        // Stats.PERMANENT is private in the published jar, so list stats explicitly in the
        // conventional HP/Atk/Def/SpA/SpD/Spe order.
        listOf(Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED)
            .forEach { stat -> form.baseStats[stat]?.let { baseStats.addProperty(stat.showdownId, it) } }
        root.add("baseStats", baseStats)

        // Full ancestry, not just the immediate parent: a 3-stage line (Cleffa -> Clefairy ->
        // Clefable) needs both hops visible from Clefable's own card. Oldest first; preEvolution
        // doesn't branch, so this is always a flat list. Walked per form, so Alolan Ninetales
        // goes back to Alolan Vulpix.
        val evolvesFrom = JsonArray()
        run {
            val ancestors = mutableListOf<Pair<Species, FormData>>()
            var current = form.preEvolution
            var guard = 0
            while (current != null && guard < MAX_EVOLUTION_DEPTH) {
                // A Bias marker (Pikachu Alola-Bias before Alolan Raichu) shows as the plain species.
                ancestors.add(current.species to RegionalForms.normalize(current.form))
                current = current.form.preEvolution
                guard++
            }
            val oldestFirst = ancestors.asReversed()
            // Each ancestor's own trigger is how it evolves into the next step. preEvolution only
            // gives the species, not the relationship, so look it up on the ancestor's own
            // forward `evolutions` (same data buildEvolvesTo reads, just walked backward).
            val chain = oldestFirst + (species to form)
            for (i in oldestFirst.indices) {
                val (fromSpecies, fromForm) = chain[i]
                val (toSpecies, toForm) = chain[i + 1]
                val entry = speciesRefJson(fromSpecies, fromForm)
                val resolved = distinctTargets(RegionalForms.evolutionsOf(fromForm))
                // Exact form match first; species-only match as a fallback for addon data that
                // doesn't spell out the form on one side.
                val towardCurrent = resolved.firstOrNull { (_, target) -> target?.first == toSpecies && target.second.name == toForm.name }
                    ?: resolved.firstOrNull { (_, target) -> target?.first == toSpecies }
                towardCurrent?.let { entry.add("trigger", triggerJson(it.first)) }

                // Alternate evolutions that don't lead toward the current species, e.g. Poliwhirl
                // evolving into Politoed (trade) alongside Poliwrath (Water Stone). Without this,
                // Poliwrath's own card would show no trace of Politoed, since evolvesTo only
                // builds forward from the current species.
                val altEvolutions = JsonArray()
                resolved.forEach { (alt, target) ->
                    if (target == null || alt === towardCurrent?.first) return@forEach
                    val (altSpecies, altForm) = target
                    val altEntry = speciesRefJson(altSpecies, altForm)
                    altEntry.add("trigger", triggerJson(alt))
                    altEntry.add("evolvesTo", buildEvolvesTo(altForm, 0))
                    altEvolutions.add(altEntry)
                }
                if (altEvolutions.size() > 0) entry.add("altEvolutions", altEvolutions)

                evolvesFrom.add(entry)
            }
        }
        root.add("evolvesFrom", evolvesFrom)

        root.add("evolvesTo", buildEvolvesTo(form, 0))

        val abilities = JsonArray()
        form.abilities.forEach {
            val abilityObj = JsonObject()
            abilityObj.addProperty("en", CobblemonLang.abilityNameEn(it.template.name))
            abilityObj.addProperty("fr", CobblemonLang.abilityNameFr(it.template.name))
            // Nullable: not every ability has a description depending on version/addons.
            CobblemonLang.abilityDescEn(it.template.name)?.let { d -> abilityObj.addProperty("descEn", d) }
            CobblemonLang.abilityDescFr(it.template.name)?.let { d -> abilityObj.addProperty("descFr", d) }
            abilities.add(abilityObj)
        }
        root.add("abilities", abilities)

        val eggGroups = JsonArray()
        // Enum constant ("HUMAN_LIKE") rather than showdownID ("Human-Like"): a stable key for
        // frontend translation.
        form.eggGroups.forEach { eggGroups.add(it.name) }
        root.add("eggGroups", eggGroups)

        val drops = JsonArray()
        // DropEntry's base interface has no item (it also covers CommandDropEntry, which has
        // none). ItemDropEntry (and its subclass EvolutionItemDropEntry) is the concrete case
        // with a real item.
        form.drops.entries.filterIsInstance<ItemDropEntry>().forEach { drop ->
            val dropObj = JsonObject()
            dropObj.addProperty("item", drop.item.toString())
            ItemNames.addTo(dropObj, drop.item, "item")
            dropObj.addProperty("percentage", drop.percentage)
            drops.add(dropObj)
        }
        root.add("drops", drops)

        val levelUpMoves = JsonArray()
        form.moves.levelUpMoves.toSortedMap().forEach { (level, templates) ->
            templates.forEach { move -> levelUpMoves.add(moveJson(move, level)) }
        }
        root.add("levelUpMoves", levelUpMoves)

        // TM/tutor/egg moves have no level, sorted alphabetically by English name (the French
        // list won't be perfectly sorted after a language toggle, an accepted tradeoff). Can run
        // into the hundreds (Clefairy has ~150 TM moves), so the frontend collapses them behind a
        // "show more" toggle, same pattern as the biome lists.
        fun moveListJson(templates: List<MoveTemplate>): JsonArray {
            val arr = JsonArray()
            templates.sortedBy { CobblemonLang.moveNameEn(it.name) }.forEach { move -> arr.add(moveJson(move)) }
            return arr
        }
        root.add("tmMoves", moveListJson(form.moves.tmMoves))
        root.add("tutorMoves", moveListJson(form.moves.tutorMoves))
        root.add("eggMoves", moveListJson(form.moves.eggMoves))

        // Grouped by rarity+level instead of a plain list: Cobblemon can register several
        // SpawnDetail entries sharing the same bucket/level with different biome subsets, which
        // showed up as duplicate "Common, lvl 34-51" rows. Merge those into one row with the
        // union of their biomes/structures.
        val spawnsByKey = LinkedHashMap<SpawnKey, SpawnGroup>()
        val showdownId = species.showdownId()
        CobblemonSpawnPools.WORLD_SPAWN_POOL.forEach { detail ->
            if (detail !is PokemonSpawnDetail) return@forEach
            val detailSpecies = detail.pokemon.species ?: return@forEach
            if (!detailSpecies.equals(showdownId, ignoreCase = true)) return@forEach
            // "vulpix alolan" spawns belong to the Alola sheet only; the standard sheet keeps
            // every non-regional spawn (incl. other cosmetic forms).
            val spawnForm = RegionalForms.formFor(species, detail.pokemon)
            val belongs = if (region != null) spawnForm.name == form.name else !RegionalForms.isRegional(spawnForm)
            if (!belongs) return@forEach

            // A spawn can be gated by a structure (e.g. mansion) instead of a biome. Not
            // precomputed like validBiomes (a structure is a world position, not a finite list),
            // so read the raw conditions instead.
            val structures = mutableListOf<String>()
            for (condition in detail.conditions) {
                condition.structures?.forEach { either ->
                    structures.add(either.map({ it.toString() }, { "#" + it.location() }))
                }
            }
            val biomes = detail.validBiomes.map { it.toString() }

            // No resolved biome and no structure (e.g. a missing compat mod's biome tag): spawn
            // isn't actionable, skip it.
            if (biomes.isEmpty() && structures.isEmpty()) return@forEach

            val levelRange = detail.levelRange?.let { "${it.first}-${it.last}" }
            val skyLightRange = skyLightRangeOf(detail)
            // Cobblemon 1.8 changed SpawnDetail.bucket from an enum to a String, already
            // lowercase/hyphenated ("common", "ultra-rare"), matching RARITY_NAMES/RARITY_COLORS
            // on the frontend. No .name lookup needed.
            val key = SpawnKey(detail.bucket, levelRange, skyLightRange)
            val group = spawnsByKey.getOrPut(key) { SpawnGroup(detail.bucket, levelRange, skyLightRange) }
            group.biomes.addAll(biomes)
            group.structures.addAll(structures)
        }
        // Second pass: skyLightRange being part of SpawnKey can leave two groups with the same
        // final biome/structure set (Tartard has both a light-gated and an unconditional path to
        // the same swamp biomes). Showing the light badge when the biomes are also reachable
        // unconditionally is misleading, so merge and drop the restriction.
        val mergedByBiomes = LinkedHashMap<SpawnMergeKey, MutableList<SpawnGroup>>()
        spawnsByKey.values.forEach { group ->
            val mergeKey = SpawnMergeKey(group.bucket, group.levelRange, group.biomes, group.structures)
            mergedByBiomes.getOrPut(mergeKey) { mutableListOf() }.add(group)
        }

        val spawns = JsonArray()
        mergedByBiomes.forEach { (mergeKey, groups) ->
            val spawnObj = JsonObject()
            spawnObj.addProperty("bucket", mergeKey.bucket)
            mergeKey.levelRange?.let { spawnObj.addProperty("levelRange", it) }
            // Only keep the light-range badge when every path to this biome set agreed on it. If
            // it also shows up unconditionally or under a different range, the list isn't
            // actually restricted, so the badge would mislead.
            if (groups.size == 1) {
                groups[0].skyLightRange?.let { spawnObj.addProperty("skyLightRange", it) }
            }
            val biomesArr = JsonArray()
            mergeKey.biomes.forEach { biomeId ->
                val biomeObj = JsonObject()
                biomeObj.addProperty("id", biomeId)
                biomeObj.addProperty("category", BiomeCategoryResolver.categoryOf(ResourceLocation.parse(biomeId)))
                biomesArr.add(biomeObj)
            }
            spawnObj.add("biomes", biomesArr)
            val structuresArr = JsonArray()
            mergeKey.structures.forEach { structuresArr.add(it) }
            spawnObj.add("structures", structuresArr)
            spawns.add(spawnObj)
        }
        root.add("spawns", spawns)

        return root
    }

    /**
     * Full battle detail for one move (type/category/power/accuracy/PP/crit ratio/description),
     * used for the hover tooltip on move rows. Same info a player would see off a TM, without
     * depending on the mod that inspired it. [level] is only set for level-up moves.
     */
    private fun moveJson(move: MoveTemplate, level: Int? = null): JsonObject {
        val moveObj = JsonObject()
        level?.let { moveObj.addProperty("level", it) }
        moveObj.addProperty("nameEn", CobblemonLang.moveNameEn(move.name))
        moveObj.addProperty("nameFr", CobblemonLang.moveNameFr(move.name))
        moveObj.addProperty("type", move.elementalType.name)
        moveObj.addProperty("category", move.damageCategory.name)
        moveObj.addProperty("power", move.power)
        moveObj.addProperty("accuracy", move.accuracy)
        moveObj.addProperty("pp", move.pp)
        moveObj.addProperty("maxPp", move.maxPp)
        moveObj.addProperty("critRatio", move.critRatio)
        // Nullable: not every move has a description depending on version/addons (mirrors the
        // ability desc fields above).
        CobblemonLang.moveDescEn(move.name)?.let { moveObj.addProperty("descEn", it) }
        CobblemonLang.moveDescFr(move.name)?.let { moveObj.addProperty("descFr", it) }
        return moveObj
    }

    /**
     * Raw sky light bounds as "min-max", not a derived day/night guess. Cobblemon's spawn data
     * has no explicit time-of-day field (0 of 825 spawn_pool_world files use one); sky light is
     * the real mechanism, and showing the number is always accurate, unlike inferring day/night
     * from it (a dark condition could just as easily mean a cave). Null if there's no light condition.
     */
    private fun skyLightRangeOf(detail: PokemonSpawnDetail): String? {
        var minSky: Int? = null
        var maxSky: Int? = null
        for (condition in detail.conditions) {
            condition.minSkyLight?.let { minSky = if (minSky == null) it else maxOf(minSky!!, it) }
            condition.maxSkyLight?.let { maxSky = if (maxSky == null) it else minOf(maxSky!!, it) }
        }
        if (minSky == null && maxSky == null) return null
        val effectiveMin = minSky ?: 0
        val effectiveMax = maxSky ?: 15
        return "$effectiveMin-$effectiveMax"
    }

    /**
     * Small {id, nationalDexNumber, nameEn, nameFr} reference used for evolvesFrom/evolvesTo,
     * plus {form, region} when it's a regional form, so the frontend opens the right card.
     */
    private fun speciesRefJson(species: Species, form: FormData): JsonObject {
        val ref = JsonObject()
        ref.addProperty("id", species.resourceIdentifier.toString())
        ref.addProperty("nationalDexNumber", species.nationalPokedexNumber)
        ref.addProperty("nameEn", CobblemonLang.speciesNameEn(species.resourceIdentifier.path, species.name))
        ref.addProperty("nameFr", CobblemonLang.speciesNameFr(species.resourceIdentifier.path, species.name))
        RegionalForms.regionOf(form)?.let { region ->
            ref.addProperty("form", form.name)
            ref.addProperty("region", region)
        }
        return ref
    }

    /**
     * Recursively walks forward evolutions, not just the next stage: each entry carries its own
     * nested "evolvesTo", so a 3-stage line (Zubat -> Golbat -> Crobat) is fully visible from the
     * first card. Also handles branching lines (Eevee) since a form can have multiple entries in
     * its own evolutions set. Per form: Galarian Meowth leads to Perrserker, not Persian.
     */
    private fun buildEvolvesTo(from: FormData, depth: Int): JsonArray {
        val result = JsonArray()
        if (depth >= MAX_EVOLUTION_DEPTH) return result

        distinctTargets(RegionalForms.evolutionsOf(from)).forEach { (evolution, target) ->
            val (targetSpecies, targetForm) = target ?: return@forEach
            val entry = speciesRefJson(targetSpecies, targetForm)
            entry.add("trigger", triggerJson(evolution))
            entry.add("evolvesTo", buildEvolvesTo(targetForm, depth + 1))
            result.add(entry)
        }

        return result
    }

    /**
     * Each evolution with its resolved (species, form) target, one per target: merged Bias
     * evolutions often lead to the same place as the standard ones (Pichu -> Pikachu twice).
     * Unresolvable targets are kept (null) so callers can still skip them explicitly.
     */
    private fun distinctTargets(evolutions: List<Evolution>): List<Pair<Evolution, Pair<Species, FormData>?>> {
        val seen = HashSet<String>()
        return evolutions.map { it to RegionalForms.resolve(it.result) }.filter { (_, target) ->
            target == null || seen.add("${target.first.resourceIdentifier}#${target.second.name}")
        }
    }

    /** {kind, ...} describing how an Evolution triggers, shared by evolvesFrom and evolvesTo. */
    private fun triggerJson(evolution: Evolution): JsonObject {
        val trigger = JsonObject()
        when (evolution) {
            is LevelUpEvolution -> {
                // "level_up" covers any passive, tick-checked evolution, not just level-count
                // ones. Friendship-gated evolutions (Togepi, Cleffa) use the same variant with a
                // FriendshipRequirement instead of a LevelRequirement.
                val levelReq = evolution.requirements.filterIsInstance<LevelRequirement>().singleOrNull()
                val friendshipReq = evolution.requirements.filterIsInstance<FriendshipRequirement>().singleOrNull()
                when {
                    levelReq != null -> {
                        trigger.addProperty("kind", "level")
                        trigger.addProperty("level", levelReq.minLevel)
                    }
                    friendshipReq != null -> trigger.addProperty("kind", "friendship")
                    else -> trigger.addProperty("kind", "other")
                }
            }
            is TradeEvolution -> trigger.addProperty("kind", "trade")
            is ItemInteractionEvolution -> {
                trigger.addProperty("kind", "item")
                // requiredContext is a vanilla ItemPredicate (could match a tag or several items
                // in principle), but Cobblemon evolutions only ever use it for one specific item,
                // so the first resolved item is correct. Omit the item name entirely (frontend
                // falls back to a generic label) for the rare case it's something else.
                evolution.requiredContext.items().orElse(null)?.firstOrNull()?.value()?.let { item ->
                    val itemId = BuiltInRegistries.ITEM.getKey(item)
                    trigger.addProperty("item", itemId.toString())
                    ItemNames.addTo(trigger, itemId, "item")
                }
            }
            else -> trigger.addProperty("kind", "other")
        }
        // Regional evolutions depend on where they happen (Pikachu + Thunder Stone on a beach or
        // tropical island gives Alolan Raichu): biome categories required, or excluded.
        evolution.requirements.filterIsInstance<BiomeRequirement>().forEach { req ->
            biomeCategoriesJson(req.biomeCondition)?.let { trigger.add("biomes", it) }
            biomeCategoriesJson(req.biomeAnticondition)?.let { trigger.add("notBiomes", it) }
        }
        return trigger
    }

    private fun biomeCategoriesJson(condition: RegistryLikeCondition<Biome>?): JsonArray? {
        val categories = when (condition) {
            is RegistryLikeTagCondition -> BiomeCategoryResolver.categoriesOf(condition.tag)
            is RegistryLikeIdentifierCondition -> listOf(BiomeCategoryResolver.categoryOf(condition.identifier))
            else -> emptyList()
        }
        if (categories.isEmpty()) return null
        return JsonArray().apply { categories.forEach { add(it) } }
    }

    companion object {
        // Defensive cap on evolution chain recursion (real lines are at most 3 stages), guards
        // against a malformed addon-defined evolution loop hanging a request.
        private const val MAX_EVOLUTION_DEPTH = 6
    }
}
