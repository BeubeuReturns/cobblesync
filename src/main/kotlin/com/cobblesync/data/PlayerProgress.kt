package com.cobblesync.data

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import net.minecraft.resources.ResourceLocation
import java.util.UUID

data class ProgressSummary(
    val caughtCount: Int,
    val seenCount: Int,
    val totalKnownSpecies: Int
)

/**
 * Shared player-progress computation, used by PokedexHandler (per-player detail) and
 * LeaderboardHandler (all-player aggregate). Keeps the "which species count toward the
 * denominator" filter defined in exactly one place.
 */
object PlayerProgress {
    /**
     * Species counted as "known": Cobblemon-implemented OR present in the world spawn pool
     * (covers addons like MissingMons that don't set `implemented`).
     */
    fun relevantSpeciesIds(world: WorldSnapshot): List<ResourceLocation> =
        world.entriesBySpecies.keys.filter { id ->
            val species = PokemonSpecies.getByIdentifier(id)
            species != null && (id in world.implementedSpeciesIds || species.showdownId().lowercase() in world.spawnableShowdownIds)
        }

    /**
     * world/relevantSpeciesIds are accepted as params (not recomputed per call) so a caller
     * iterating many players — the leaderboard — only builds the filtered list once.
     */
    fun summarize(
        uuid: UUID,
        world: WorldSnapshot = WorldDataCache.get(),
        relevantSpeciesIds: List<ResourceLocation> = relevantSpeciesIds(world)
    ): ProgressSummary {
        val pokedex = Cobblemon.playerDataManager.getPokedexData(uuid)
        var caughtCount = 0
        var seenCount = 0

        for (speciesId in relevantSpeciesIds) {
            val record = pokedex.speciesRecords[speciesId]
            when (record?.getKnowledge()?.ordinal) {
                2 -> caughtCount++
                1 -> seenCount++
            }
        }

        return ProgressSummary(caughtCount, seenCount, relevantSpeciesIds.size)
    }
}
