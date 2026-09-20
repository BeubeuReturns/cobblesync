package com.cobblesync.data

import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BiomeTags
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome
import java.util.concurrent.ConcurrentHashMap

/**
 * Classifies a biome into a broad category (forest, desert, ocean, ...) for badge coloring —
 * real vanilla biome tags first (mod-authored biomes, e.g. Terralith, tag themselves correctly
 * for cross-mod compatibility, so this works for modded biomes too), falling back to a keyword
 * match on the biome's own path for the handful of common categories vanilla has no tag for at
 * all (desert, swamp, plains, snow, caves, mushroom).
 */
object BiomeCategoryResolver {
    private val cache = ConcurrentHashMap<ResourceLocation, String>()

    @Volatile
    private var registryAccess: RegistryAccess? = null

    fun configure(registryAccess: RegistryAccess) {
        this.registryAccess = registryAccess
        cache.clear()
    }

    // First match wins. Nether/end checked first since everything past that point is implicitly
    // overworld — mirrors how the frontend used to special-case nether/end before falling back to
    // a flat "overworld" bucket, just with real tags instead of a hardcoded name list now.
    private val TAG_CATEGORIES: List<Pair<TagKey<Biome>, String>> = listOf(
        BiomeTags.IS_NETHER to "nether",
        BiomeTags.IS_END to "end",
        BiomeTags.IS_DEEP_OCEAN to "ocean",
        BiomeTags.IS_OCEAN to "ocean",
        BiomeTags.IS_BEACH to "beach",
        BiomeTags.IS_RIVER to "river",
        BiomeTags.IS_BADLANDS to "badlands",
        BiomeTags.IS_TAIGA to "taiga",
        BiomeTags.IS_JUNGLE to "jungle",
        BiomeTags.IS_SAVANNA to "savanna",
        BiomeTags.IS_FOREST to "forest",
        BiomeTags.IS_MOUNTAIN to "mountain",
        BiomeTags.IS_HILL to "mountain",
    )

    // Only for categories with no vanilla tag at all, plus a broader net for anything the tags
    // above missed. Order matters: first matching substring wins.
    private val KEYWORD_CATEGORIES: List<Pair<String, String>> = listOf(
        "desert" to "desert",
        "swamp" to "swamp",
        "bog" to "swamp",
        "marsh" to "swamp",
        "wetland" to "swamp",
        "bayou" to "swamp",
        "shrubland" to "savanna",
        "scrubland" to "savanna",
        "chaparral" to "savanna",
        "mushroom" to "mushroom",
        "cave" to "cave",
        "cavern" to "cave",
        "dripstone" to "cave",
        "deep_dark" to "cave",
        "snow" to "snow",
        "frozen" to "snow",
        "ice" to "snow",
        "tundra" to "snow",
        "glacier" to "snow",
        "frost" to "snow",
        "forest" to "forest",
        "wood" to "forest",
        "grove" to "forest",
        "jungle" to "jungle",
        "ocean" to "ocean",
        "sea" to "ocean",
        "river" to "river",
        "beach" to "beach",
        "shore" to "beach",
        "coast" to "beach",
        "savanna" to "savanna",
        "badlands" to "badlands",
        "mesa" to "badlands",
        "outback" to "badlands",
        "mountain" to "mountain",
        "peak" to "mountain",
        "cliff" to "mountain",
        "ridge" to "mountain",
        "plains" to "plains",
        "grassland" to "plains",
        "pumpkin" to "plains",
        "meadow" to "plains",
        "prairie" to "plains",
        "field" to "plains",
    )

    fun categoryOf(biomeId: ResourceLocation): String = cache.getOrPut(biomeId) { resolve(biomeId) }

    // Not a real biome at all: CobbleSafari (and possibly other minigame-style addons) registers
    // per-type themed pseudo-dimensions as if they were biomes (cobblesafari:ghost,
    // cobblesafari:water, ...) so its safari-zone spawns plug into Cobblemon's normal spawn
    // system. Tagged as its own category rather than left to fall into "other" so the frontend
    // can flag it as a distinct, non-natural location instead of silently mixing it in.
    private val PSEUDO_BIOME_NAMESPACES = setOf("cobblesafari")

    private fun resolve(biomeId: ResourceLocation): String {
        if (biomeId.namespace in PSEUDO_BIOME_NAMESPACES) return "safari"

        registryAccess?.let { access ->
            val holder = access.registryOrThrow(Registries.BIOME).getHolder(biomeId).orElse(null)
            if (holder != null) {
                for ((tag, category) in TAG_CATEGORIES) {
                    if (holder.`is`(tag)) return category
                }
            }
        }

        val path = biomeId.path.lowercase()
        for ((keyword, category) in KEYWORD_CATEGORIES) {
            if (path.contains(keyword)) return category
        }

        return "other"
    }
}
