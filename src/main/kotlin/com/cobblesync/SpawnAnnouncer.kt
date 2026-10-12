package com.cobblesync

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Server-wide chat message + sound when a shiny or a rare species (legendary, mythical...)
 * spawns naturally. Categories come from webserver.conf: "shiny" plus Cobblemon species labels.
 */
object SpawnAnnouncer {
    private class Rarity(val fr: String, val en: String)

    private val RARITIES = linkedMapOf(
        "legendary" to Rarity("Pokémon légendaire", "Legendary Pokémon"),
        "mythical" to Rarity("Pokémon fabuleux", "Mythical Pokémon"),
        "ultra_beast" to Rarity("Ultra-Chimère", "Ultra Beast"),
        "paradox" to Rarity("Pokémon Paradoxe", "Paradox Pokémon"),
    )

    fun register(categories: List<String>) {
        if (categories.isEmpty()) return
        val shinyOn = "shiny" in categories
        val labels = categories.filter { it in RARITIES }

        // Checked at the end of the tick, not in the event: the event fires before finalizeSpawn
        // and before the entity joins the world, so the shiny roll may not be applied yet, and
        // another mod may still cancel the spawn. (server.execute doesn't help: called from the
        // server thread it runs immediately.) Only Pokémon actually in the world get announced.
        CobblemonEvents.ENTITY_SPAWN.subscribe(Priority.LOWEST) { event ->
            val entity = event.entity as? PokemonEntity ?: return@subscribe
            if (!event.isCanceled) pending.add(entity)
        }

        ServerTickEvents.END_SERVER_TICK.register {
            while (true) {
                val entity = pending.poll() ?: break
                val level = entity.level() as? ServerLevel ?: continue
                if (entity.isRemoved || level.getEntity(entity.uuid) == null) continue
                val pokemon = entity.pokemon
                val shiny = shinyOn && pokemon.shiny
                val rarity = labels.firstOrNull { it in pokemon.species.labels }?.let { RARITIES[it] }
                if (!shiny && rarity == null) continue
                CobbleSync.LOGGER.info("Spawn announced: {}{}", if (shiny) "shiny " else "", pokemon.species.name)
                announce(level, entity, shiny, rarity)
            }
        }
    }

    private val pending = ConcurrentLinkedQueue<PokemonEntity>()

    private fun announce(level: ServerLevel, entity: PokemonEntity, shiny: Boolean, rarity: Rarity?) {
        for (player in level.server.playerList.players) {
            player.sendSystemMessage(message(player, entity, shiny, rarity))
            // Rare species: low bell. Shiny only: high chime.
            if (rarity != null) {
                player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER, 0.8f, 0.7f)
            } else {
                player.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.MASTER, 0.8f, 1.5f)
            }
        }
    }

    // "✦ Un Ponyta shiny est apparu !" / "★ Pokémon légendaire en vue : Mewtwo !"
    // Built per player: each gets their own language, the species name is translated client-side.
    private fun message(player: ServerPlayer, entity: PokemonEntity, shiny: Boolean, rarity: Rarity?): Component {
        val french = player.clientInformation().language().startsWith("fr")
        val name: MutableComponent = entity.pokemon.species.translatedName.copy()
            .withStyle(if (shiny) ChatFormatting.YELLOW else ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
        val icon = Component.literal(if (rarity != null) "★ " else "✦ ")
            .withStyle(if (shiny) ChatFormatting.GOLD else ChatFormatting.LIGHT_PURPLE)

        val text = Component.empty().withStyle(ChatFormatting.WHITE)
        if (rarity == null) {
            if (french) {
                text.append("Un ").append(name).append(" shiny est apparu !")
            } else {
                text.append("A shiny ").append(name).append(" appeared!")
            }
        } else {
            val kind = when {
                french && shiny -> "${rarity.fr} shiny"
                french -> rarity.fr
                shiny -> "Shiny ${rarity.en.replaceFirstChar { it.lowercase() }}"
                else -> rarity.en
            }
            text.append(if (french) "$kind en vue : " else "$kind spotted: ").append(name).append(if (french) " !" else "!")
        }
        return icon.append(text)
    }
}
