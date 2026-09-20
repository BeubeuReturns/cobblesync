package com.cobblesync

import com.cobblesync.config.DiscordConfig
import com.cobblesync.config.WebServerConfig
import com.cobblesync.data.BiomeCategoryResolver
import com.cobblesync.data.CaptureLog
import com.cobblesync.data.ItemIconCache
import com.cobblesync.data.SpeciesInfoCache
import com.cobblesync.data.WorldDataCache
import com.cobblesync.discord.DiscordNotifier
import com.cobblesync.player.CaptureDates
import com.cobblesync.player.PlayerRegistry
import com.cobblesync.web.BroadcastExecutor
import com.cobblesync.web.CaptureLogBroadcaster
import com.cobblesync.web.CobbleSyncWebServer
import com.cobblesync.web.LeaderboardBroadcaster
import com.cobblesync.web.PokedexEventBroadcaster
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.pokedex.Dexes
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

object CobbleSync : ModInitializer {
    const val MOD_ID = "cobblesync"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    val configDir = FabricLoader.getInstance().configDir.resolve(MOD_ID)

    private var webServer: CobbleSyncWebServer? = null

    // Used to look up online players for the "current team" endpoint (PokedexHandler.handleTeam)
    // without needing a ServerPlayer passed all the way through from the join event.
    @Volatile
    var server: MinecraftServer? = null
        private set

    override fun onInitialize() {
        val config = WebServerConfig.loadOrCreate(configDir.resolve("webserver.conf"))
        PlayerRegistry.load(configDir.resolve("players.json"))
        CaptureDates.load(configDir.resolve("capture-dates.json"))
        CaptureLog.load(configDir.resolve("capture-log.json"))
        DiscordNotifier.configure(DiscordConfig.loadOrCreate(configDir.resolve("discord.conf")))

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            PlayerRegistry.recordJoin(player.uuid, player.gameProfile.name)
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            this.server = server
            BiomeCategoryResolver.configure(server.registryAccess())

            // WORLD_SPAWN_POOL only exists from SERVER_STARTING onward.
            Dexes.observable.subscribe { invalidateWorldCaches() }
            PokemonSpecies.observable.subscribe { invalidateWorldCaches() }
            CobblemonSpawnPools.WORLD_SPAWN_POOL.observable.subscribe { invalidateWorldCaches() }

            if (!config.enabled) {
                LOGGER.info("Web server disabled in webserver.conf, not starting.")
                return@register
            }
            webServer = CobbleSyncWebServer(config, configDir).also { it.start() }
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            webServer?.stop()
            webServer = null
            server = null
            DiscordNotifier.shutdown()
            BroadcastExecutor.shutdown()
            // Flushes any debounced write that hadn't fired yet, so a stop right after a catch
            // never loses it.
            PlayerRegistry.shutdown()
            CaptureDates.shutdown()
            CaptureLog.shutdown()
        }

        CobblemonEvents.POKEDEX_DATA_CHANGED_POST.subscribe { event ->
            LOGGER.debug("Pokedex updated for {} (knowledge={})", event.playerUUID, event.knowledge)
            PokedexEventBroadcaster.notifyUpdated(event.playerUUID)

            // ordinal 2 == "caught" (see PokedexHandler.kt) — record the first time this
            // happens for the species, since Cobblemon doesn't track a catch date itself.
            if (event.knowledge.ordinal == 2) {
                val speciesId = event.dataSource.getApparentSpecies().resourceIdentifier.toString()
                val isShiny = event.dataSource.pokemon.shiny
                val isNewCapture = CaptureDates.recordIfMissing(event.playerUUID, speciesId)
                // Tracked separately from isNewCapture: a shiny catch is always log-worthy, even
                // if the species was already caught in its normal form before.
                val isNewShiny = isShiny && CaptureDates.recordIfMissing(event.playerUUID, "$speciesId#shiny")

                if (isNewCapture || isNewShiny) {
                    CaptureLog.add(event.playerUUID, speciesId, isShiny)
                    CaptureLogBroadcaster.notifyNewEntry()
                    LeaderboardBroadcaster.notifyChanged()
                }

                // else-if: a species caught for the first time and shiny in the same event
                // should only post the shiny message, not both.
                val playerName = PlayerRegistry.nameOf(event.playerUUID) ?: event.playerUUID.toString()
                if (isNewShiny) {
                    DiscordNotifier.notifyShinyCaught(playerName, speciesId)
                } else if (isNewCapture) {
                    DiscordNotifier.notifyNewSpecies(playerName, speciesId)
                }
            }
        }
    }

    private fun invalidateWorldCaches() {
        WorldDataCache.invalidate()
        SpeciesInfoCache.invalidate()
        ItemIconCache.invalidate()
    }
}
