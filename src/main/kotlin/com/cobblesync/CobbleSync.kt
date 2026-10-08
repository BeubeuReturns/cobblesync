package com.cobblesync

import com.cobblesync.config.DiscordConfig
import com.cobblesync.config.WebServerConfig
import com.cobblesync.data.BiomeCategoryResolver
import com.cobblesync.data.CaptureLog
import com.cobblesync.data.ItemIconCache
import com.cobblesync.data.RegionalForms
import com.cobblesync.data.SpeciesInfoCache
import com.cobblesync.data.WorldDataCache
import com.cobblesync.discord.DiscordNotifier
import com.cobblesync.integration.PokeBadgesIntegration
import com.cobblesync.player.CaptureDates
import com.cobblesync.player.PlayerRegistry
import com.cobblesync.web.BroadcastExecutor
import com.cobblesync.web.CobbleSyncWebServer
import com.cobblesync.web.DashboardEvents
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.pokedex.Dexes
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
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

    private var reloadHooksRegistered = false

    override fun onInitialize() {
        val config = WebServerConfig.loadOrCreate(configDir.resolve("webserver.conf"))
        DiscordNotifier.configure(DiscordConfig.loadOrCreate(configDir.resolve("discord.conf")))
        PokeBadgesIntegration.configure(config.pokebadgesRegionOrder)
        DashboardLink.register(config)

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            PlayerRegistry.recordJoin(player.uuid, player.gameProfile.name)
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            this.server = server
            BiomeCategoryResolver.configure(server.registryAccess())

            // Per-world data lives in the save, not in config/: the pokedex itself is per world,
            // so a shared history would leak captures between singleplayer saves.
            val worldDataDir = server.getWorldPath(LevelResource.ROOT).resolve(MOD_ID)
            PlayerRegistry.load(worldDataDir.resolve("players.json"))
            CaptureDates.load(worldDataDir.resolve("capture-dates.json"))
            CaptureLog.load(worldDataDir.resolve("capture-log.json"))
            PokeBadgesIntegration.onServerStarted(server)

            // WORLD_SPAWN_POOL only exists from SERVER_STARTING onward. Registered once: in
            // singleplayer this event fires again for every world opened.
            if (!reloadHooksRegistered) {
                reloadHooksRegistered = true
                Dexes.observable.subscribe { invalidateWorldCaches() }
                PokemonSpecies.observable.subscribe { invalidateWorldCaches() }
                CobblemonSpawnPools.WORLD_SPAWN_POOL.observable.subscribe { invalidateWorldCaches() }
            }

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
            PokeBadgesIntegration.onServerStopping()
            // The next world may have different datapacks/addons.
            invalidateWorldCaches()
        }

        CobblemonEvents.POKEDEX_DATA_CHANGED_POST.subscribe { event ->
            LOGGER.debug("Pokedex updated for {} (knowledge={})", event.playerUUID, event.knowledge)
            DashboardEvents.pokedexUpdated(event.playerUUID)

            // Record the first time a species reaches "caught," since Cobblemon doesn't track a
            // catch date itself. Cobblemon 1.8 confirmed PokedexEntryProgress's real constant
            // names (UNREGISTERED/SEEN/OWNED) against the published jar, no more magic ordinal.
            if (event.knowledge == PokedexEntryProgress.OWNED) {
                val speciesId = event.dataSource.getApparentSpecies().resourceIdentifier.toString()
                val isShiny = event.dataSource.pokemon.shiny
                val regionalForm = event.dataSource.getApparentForm().takeIf { RegionalForms.isRegional(it) }?.name
                val isNewCapture = CaptureDates.recordIfMissing(event.playerUUID, speciesId)
                // Tracked separately from isNewCapture: a shiny catch is always log-worthy, even
                // if the species was already caught in its normal form before.
                val isNewShiny = isShiny && CaptureDates.recordIfMissing(event.playerUUID, "$speciesId#shiny")
                // Regional forms have their own card, so their first capture is news too.
                val isNewRegional = regionalForm != null &&
                    CaptureDates.recordIfMissing(event.playerUUID, CaptureDates.regionalCaptureKey(speciesId, regionalForm))

                if (isNewCapture || isNewShiny || isNewRegional) {
                    CaptureLog.add(event.playerUUID, speciesId, isShiny, regionalForm)
                    DashboardEvents.captureLogUpdated()
                    DashboardEvents.leaderboardUpdated()
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
