package com.cobblesync.web

import com.cobblesync.data.BiomeCategoryResolver
import com.cobblesync.data.CobblemonLang
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
import com.cobblemon.mod.common.pokemon.requirements.FriendshipRequirement
import com.cobblemon.mod.common.pokemon.requirements.LevelRequirement
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation

/**
 * Groups spawn entries by rarity+level+sky-light range — see the spawns-building loop in
 * [SpeciesInfoHandler]. skyLightRange is part of the key (not just unioned like biomes/structures)
 * because two entries sharing the same bucket/level can still mean genuinely different things —
 * e.g. Gastly has separate "common, lvl 6-31" entries for a dark-outdoors spawn and a lit
 * "is_spooky" biome spawn; merging those would silently lose the light-condition distinction.
 */
private data class SpawnKey(val bucket: String, val levelRange: String?, val skyLightRange: String?)

/** Accumulates the union of biomes/structures across every SpawnDetail sharing a [SpawnKey]. */
private class SpawnGroup(val bucket: String, val levelRange: String?, val skyLightRange: String?) {
    val biomes = LinkedHashSet<String>()
    val structures = LinkedHashSet<String>()
}

/**
 * Second-pass merge key (see the spawns-building loop) — [SpawnGroup]s that land on the exact
 * same final biome/structure set only differ by skyLightRange, meaning at least one path there
 * was unconditional; merged under this key so the light badge doesn't show on a duplicate list.
 */
private data class SpawnMergeKey(
    val bucket: String,
    val levelRange: String?,
    val biomes: Set<String>,
    val structures: Set<String>
)

/**
 * Serves GET /api/species/{id} (e.g. /api/species/cobblemon:bulbasaur) — static per-species info
 * (types, stats, spawns), independent of any player. Cached: see [SpeciesInfoCache].
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

        val root = SpeciesInfoCache.getOrCompute(resourceLocation) { buildSpeciesInfo(resourceLocation) }
        if (root == null) {
            respondJson(exchange, 404, jsonError("Unknown species"))
            return
        }

        respondJson(exchange, 200, root.toString())
    }

    private fun buildSpeciesInfo(resourceLocation: ResourceLocation): JsonObject? {
        val species = PokemonSpecies.getByIdentifier(resourceLocation) ?: return null

        val root = JsonObject()
        root.addProperty("id", resourceLocation.toString())
        root.addProperty("nationalDexNumber", species.nationalPokedexNumber)
        // Both languages are sent together: switching the UI language needs no extra request.
        root.addProperty("nameEn", CobblemonLang.speciesNameEn(resourceLocation.path, species.name))
        root.addProperty("nameFr", CobblemonLang.speciesNameFr(resourceLocation.path, species.name))
        // Nullable: not every species has flavor text depending on version/addons.
        CobblemonLang.speciesDescEn(resourceLocation.path)?.let { root.addProperty("descriptionEn", it) }
        CobblemonLang.speciesDescFr(resourceLocation.path)?.let { root.addProperty("descriptionFr", it) }

        val types = JsonArray()
        types.add(species.primaryType.name)
        species.secondaryType?.let { types.add(it.name) }
        root.add("types", types)

        // Cobblemon stores height in decimeters and weight in hectograms.
        root.addProperty("heightM", species.height / 10.0)
        root.addProperty("weightKg", species.weight / 10.0)

        root.addProperty("catchRate", species.catchRate)
        // Omitted (not null) for genderless species, same sentinel Cobblemon's own
        // possibleGenders getter checks — -1F means no gender axis at all, not "0% male".
        if (species.maleRatio != -1F) {
            root.addProperty("malePercent", species.maleRatio * 100)
        }

        val baseStats = JsonObject()
        // Stats.PERMANENT (the engine's own "the 6 non-battle-only stats" set) is private in the
        // published jar — list them explicitly instead, in the conventional HP/Atk/Def/SpA/SpD/Spe
        // display order.
        listOf(Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED)
            .forEach { stat -> species.baseStats[stat]?.let { baseStats.addProperty(stat.showdownId, it) } }
        root.add("baseStats", baseStats)

        // Full ancestry, not just the immediate parent — a 3-stage line (e.g. Cleffa -> Clefairy
        // -> Clefable) needs both hops back visible from Clefable's own card. Oldest ancestor
        // first. preEvolution doesn't branch (unlike evolutions), so this is always a flat list.
        val evolvesFrom = JsonArray()
        run {
            val ancestors = mutableListOf<Species>()
            var current = species.preEvolution?.species
            var guard = 0
            while (current != null && guard < MAX_EVOLUTION_DEPTH) {
                ancestors.add(current)
                current = current.preEvolution?.species
                guard++
            }
            val oldestFirst = ancestors.asReversed()
            // Each ancestor's OWN trigger is "how it evolves into the next step" — preEvolution
            // only gives us the species, not that relationship, so look it up on the ancestor's
            // own forward `evolutions` (the same data buildEvolvesTo reads, just walked backward).
            val chain = oldestFirst + species
            for (i in oldestFirst.indices) {
                val from = chain[i]
                val to = chain[i + 1]
                val entry = speciesRefJson(from)
                val evolution = from.evolutions.firstOrNull { PokemonSpecies.getByName(it.result.species ?: "") == to }
                if (evolution != null) entry.add("trigger", triggerJson(evolution))

                // Alternate evolutions from this ancestor that DON'T lead toward the current
                // species — e.g. Poliwhirl evolving into Politoed (trade) as well as Poliwrath
                // (Water Stone, the path toward the current species). Without this, opening
                // Poliwrath's own card would show no trace of Politoed at all, since evolvesTo is
                // only ever built forward from the CURRENT species.
                val altEvolutions = JsonArray()
                from.evolutions.forEach { alt ->
                    val altTarget = PokemonSpecies.getByName(alt.result.species ?: "") ?: return@forEach
                    if (altTarget == to) return@forEach
                    val altEntry = speciesRefJson(altTarget)
                    altEntry.add("trigger", triggerJson(alt))
                    altEntry.add("evolvesTo", buildEvolvesTo(altTarget, 0))
                    altEvolutions.add(altEntry)
                }
                if (altEvolutions.size() > 0) entry.add("altEvolutions", altEvolutions)

                evolvesFrom.add(entry)
            }
        }
        root.add("evolvesFrom", evolvesFrom)

        root.add("evolvesTo", buildEvolvesTo(species, 0))

        val abilities = JsonArray()
        species.abilities.forEach {
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
        species.eggGroups.forEach { eggGroups.add(it.name) }
        root.add("eggGroups", eggGroups)

        val drops = JsonArray()
        // DropEntry doesn't expose an item on the base interface (it also covers CommandDropEntry,
        // which has no fixed item at all) — ItemDropEntry (and EvolutionItemDropEntry, which
        // extends it) is the concrete case with an actual item to show.
        species.drops.entries.filterIsInstance<ItemDropEntry>().forEach { drop ->
            val dropObj = JsonObject()
            dropObj.addProperty("item", drop.item.toString())
            dropObj.addProperty("percentage", drop.percentage)
            drops.add(dropObj)
        }
        root.add("drops", drops)

        val levelUpMoves = JsonArray()
        species.moves.levelUpMoves.toSortedMap().forEach { (level, templates) ->
            templates.forEach { move -> levelUpMoves.add(moveJson(move, level)) }
        }
        root.add("levelUpMoves", levelUpMoves)

        // TM/tutor/egg moves have no level attached — sorted alphabetically (by English name;
        // the French list won't be perfectly alphabetical after a language toggle, an accepted
        // minor imperfection rather than re-sorting client-side per language). Can run into the
        // hundreds for some species (e.g. Clefairy has ~150 TM moves) — left to the frontend to
        // collapse behind a "show more" toggle, same pattern already used for biome lists.
        fun moveListJson(templates: List<MoveTemplate>): JsonArray {
            val arr = JsonArray()
            templates.sortedBy { CobblemonLang.moveNameEn(it.name) }.forEach { move -> arr.add(moveJson(move)) }
            return arr
        }
        root.add("tmMoves", moveListJson(species.moves.tmMoves))
        root.add("tutorMoves", moveListJson(species.moves.tutorMoves))
        root.add("eggMoves", moveListJson(species.moves.eggMoves))

        // Grouped by rarity+level rather than added to a plain list — Cobblemon can register
        // several SpawnDetail entries sharing the same bucket/level but different biome subsets
        // (different presets/contexts we don't otherwise surface), which showed up as multiple
        // "Common, lvl 34-51"-style rows in a row; merge those into one row with the union of
        // their biomes/structures instead.
        val spawnsByKey = LinkedHashMap<SpawnKey, SpawnGroup>()
        val showdownId = species.showdownId()
        CobblemonSpawnPools.WORLD_SPAWN_POOL.forEach { detail ->
            if (detail !is PokemonSpawnDetail) return@forEach
            val detailSpecies = detail.pokemon.species ?: return@forEach
            if (!detailSpecies.equals(showdownId, ignoreCase = true)) return@forEach

            // A spawn can be gated by a structure (e.g. mansion) instead of a biome — not
            // precomputed by Cobblemon like validBiomes (a structure is a world position, not a
            // finite list), so read the raw conditions instead.
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
            val key = SpawnKey(detail.bucket.name, levelRange, skyLightRange)
            val group = spawnsByKey.getOrPut(key) { SpawnGroup(detail.bucket.name, levelRange, skyLightRange) }
            group.biomes.addAll(biomes)
            group.structures.addAll(structures)
        }
        // Second pass: skyLightRange being part of SpawnKey can leave two groups with the exact
        // same final biome+structure set (e.g. Tartard has both a light-gated and an unconditional
        // path to the same swamp biomes at "common, lvl 34-51") — showing the light badge on a
        // duplicate of a biome list that's ALSO reachable unconditionally is misleading, so merge
        // those and drop the restriction rather than displaying the same biomes twice.
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
            // Only keep the light-range badge when every path to this exact biome set agreed on
            // it — if it also shows up unconditionally (or under a different range), the biome
            // list as a whole isn't actually restricted, so the badge would just be misleading.
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
     * not just the name — used for the hover tooltip on move rows, same in-game-Pokédex-like info
     * a player would see off a TM without adding a real dependency on the mod that inspired it.
     * [level] is only set for level-up moves.
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
     * Raw sky light bounds as "min-max", not a derived day/night guess — Cobblemon's bundled spawn
     * data has no explicit time-of-day field at all (confirmed: 0 of 825 spawn_pool_world files use
     * one); sky light is the actual mechanism, and showing the real number is always accurate,
     * unlike inferring "day"/"night" from it (a dark condition could just as easily mean a cave).
     * Null if the spawn has no light-based condition at all.
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

    /** Small {id, nationalDexNumber, nameEn, nameFr} reference, used for evolvesFrom/evolvesTo. */
    private fun speciesRefJson(species: Species): JsonObject {
        val ref = JsonObject()
        ref.addProperty("id", species.resourceIdentifier.toString())
        ref.addProperty("nationalDexNumber", species.nationalPokedexNumber)
        ref.addProperty("nameEn", CobblemonLang.speciesNameEn(species.resourceIdentifier.path, species.name))
        ref.addProperty("nameFr", CobblemonLang.speciesNameFr(species.resourceIdentifier.path, species.name))
        return ref
    }

    /**
     * Recursively walks forward evolutions, not just the immediate next stage — each entry
     * carries its own nested "evolvesTo", so a 3-stage line (e.g. Zubat -> Golbat -> Crobat) is
     * fully visible from the first species' own card. Also naturally handles branching lines
     * (e.g. Eevee) since a species can have more than one entry in its own evolutions set.
     */
    private fun buildEvolvesTo(from: Species, depth: Int): JsonArray {
        val result = JsonArray()
        if (depth >= MAX_EVOLUTION_DEPTH) return result

        from.evolutions.forEach { evolution ->
            val targetName = evolution.result.species ?: return@forEach
            val target = PokemonSpecies.getByName(targetName) ?: return@forEach
            val entry = speciesRefJson(target)
            entry.add("trigger", triggerJson(evolution))
            entry.add("evolvesTo", buildEvolvesTo(target, depth + 1))
            result.add(entry)
        }

        return result
    }

    /** {kind, ...} describing how an Evolution triggers — shared by evolvesFrom and evolvesTo. */
    private fun triggerJson(evolution: Evolution): JsonObject {
        val trigger = JsonObject()
        when (evolution) {
            is LevelUpEvolution -> {
                // "level_up" is the variant for any passive, tick-checked evolution — not just
                // number-of-levels ones. Friendship-gated evolutions (Togepi, Cleffa, etc.) use
                // this same variant with a FriendshipRequirement instead of a LevelRequirement.
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
                // requiredContext is a vanilla ItemPredicate (could in principle match a tag or
                // several items) — Cobblemon evolutions only ever use it for "exactly one specific
                // item" in practice, so the first resolved item is the right one to show; omit the
                // item name entirely (falls back to a generic label client-side) rather than guess
                // for the rare case it's something else.
                evolution.requiredContext.items().orElse(null)?.firstOrNull()?.value()?.let { item ->
                    trigger.addProperty("item", BuiltInRegistries.ITEM.getKey(item).toString())
                }
            }
            else -> trigger.addProperty("kind", "other")
        }
        return trigger
    }

    companion object {
        // Defensive cap on evolution chain recursion (real Pokémon lines are at most 3 stages) —
        // guards against a malformed addon-defined evolution loop hanging a request.
        private const val MAX_EVOLUTION_DEPTH = 6
    }
}
