package com.cobblesync.data

import com.cobblemon.mod.common.api.pokedex.Dexes
import com.cobblemon.mod.common.api.pokedex.entry.PokedexEntry
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import net.minecraft.resources.ResourceLocation

data class WorldSnapshot(
    val entriesBySpecies: Map<ResourceLocation, List<PokedexEntry>>,
    val implementedSpeciesIds: Set<ResourceLocation>,
    val spawnableShowdownIds: Set<String>
)

/**
 * Cache of world data (dex entries, implemented species, spawn pool) that doesn't depend on the
 * player being queried — avoids rescanning it on every HTTP request. Invalidated when Cobblemon
 * reloads its data (see subscriptions in CobbleSync.kt).
 */
object WorldDataCache {
    @Volatile
    private var snapshot: WorldSnapshot? = null

    fun get(): WorldSnapshot {
        return snapshot ?: synchronized(this) {
            snapshot ?: build().also { snapshot = it }
        }
    }

    fun invalidate() {
        snapshot = null
    }

    private fun build(): WorldSnapshot {
        val entriesBySpecies = Dexes.dexEntryMap.values.flatMap { it.getEntries() }.groupBy { it.speciesId }
        val implementedSpeciesIds = PokemonSpecies.implemented.map { it.resourceIdentifier }.toSet()
        val spawnableShowdownIds = CobblemonSpawnPools.WORLD_SPAWN_POOL
            .filterIsInstance<PokemonSpawnDetail>()
            .mapNotNull { it.pokemon.species?.lowercase() }
            .toSet()
        return WorldSnapshot(entriesBySpecies, implementedSpeciesIds, spawnableShowdownIds)
    }
}
