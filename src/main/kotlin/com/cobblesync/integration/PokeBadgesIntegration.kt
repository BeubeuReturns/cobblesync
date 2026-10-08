package com.cobblesync.integration

import com.cobblesync.CobbleSync
import com.cobblesync.data.LangFiles
import com.cobblesync.web.DashboardEvents
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * Optional PokeBadges support (gym badges per player). Reflection only: the mod is
 * All Rights Reserved, so its jar can't be bundled or committed as a compile dependency, and
 * everything here must stay inert when it isn't installed.
 *
 * Catalog (slots per region, active Unova set) comes from PokeBadges' own ModItems helpers.
 * Galar has 10 slots there, not 8: both version-exclusive gym pairs are collectable. Ownership is read from its per-world saved data, which also covers
 * offline players; the public API only accepts online ServerPlayers. Reads there are strictly
 * read-only: its getPlayer(uuid) does computeIfAbsent, which must not run off the server thread.
 */
object PokeBadgesIntegration {
    private const val MOD_ID = "pokebadges"
    private const val PKG = "net.levelscraft7.pokebadges"

    private val installed: Boolean by lazy { FabricLoader.getInstance().isModLoaded(MOD_ID) }

    @Volatile
    private var regionOrder: List<String> = emptyList()

    // PokeBadges' own saved data for the current world, fetched on the server thread at start.
    @Volatile
    private var savedData: Any? = null

    private var eventsRegistered = false

    private class Api(
        val regionById: Method,
        val orderedForRegion: Method,
        val compatibleForSlot: Method,
        val unovaSetOf: Method,
        val metaGet: Method,
        val metaLeader: Method,
        val metaType: Method,
        val storageIndex: Method,
        val savedDataGet: Method,
        val playersField: Field,
        val boxHas: Method,
        val boxTimestamp: Method
    )

    private val api: Api? by lazy {
        if (!installed) return@lazy null
        runCatching { resolveApi() }
            .onFailure { CobbleSync.LOGGER.warn("PokeBadges is installed but its API changed, badge display disabled", it) }
            .getOrNull()
    }

    private fun cls(name: String): Class<*> = Class.forName("$PKG.$name", true, PokeBadgesIntegration::class.java.classLoader)

    private fun resolveApi(): Api {
        val region = cls("BadgeRegion")
        val unovaSet = cls("UnovaBadgeSet")
        val modItems = cls("ModItems")
        val meta = cls("badges.BadgeMeta")
        val savedDataClass = cls("storage.BadgeBoxSavedData")
        val box = cls("storage.BadgeBoxSavedData\$PlayerBadgeBox")
        val intType = Int::class.javaPrimitiveType
        return Api(
            regionById = region.getMethod("byId", String::class.java),
            orderedForRegion = modItems.getMethod("orderedBadgesForRegion", region, unovaSet),
            compatibleForSlot = modItems.getMethod("compatibleBadgesForRegionSlot", region, intType, unovaSet),
            unovaSetOf = cls("server.PokeBadgesServerConfig").getMethod("unovaBadgeSet", MinecraftServer::class.java),
            metaGet = cls("badges.BadgeMetas").getMethod("get", Item::class.java),
            metaLeader = meta.getMethod("gymLeaderKey"),
            metaType = meta.getMethod("gymTypeKey"),
            storageIndex = cls("BadgeItem").getMethod("storageIndex"),
            savedDataGet = savedDataClass.getMethod("get", MinecraftServer::class.java),
            playersField = savedDataClass.getDeclaredField("players").apply { isAccessible = true },
            boxHas = box.getMethod("has", region, intType),
            boxTimestamp = box.getMethod("getTimestamp", region, intType)
        )
    }

    fun configure(order: List<String>) {
        regionOrder = order
    }

    fun onServerStarted(server: MinecraftServer) {
        val api = api ?: return
        savedData = runCatching { api.savedDataGet.invoke(null, server) }
            .onFailure { CobbleSync.LOGGER.warn("Couldn't read PokeBadges saved data", it) }
            .getOrNull()
        if (!eventsRegistered) {
            eventsRegistered = true
            registerEvents()
        }
    }

    fun onServerStopping() {
        savedData = null
    }

    /** Award/remove/restore all just trigger the player's existing "pokedex-updated" SSE signal. */
    private fun registerEvents() {
        val events = runCatching { cls("api.event.PokeBadgesEvents") }.getOrNull() ?: return
        for (kind in listOf("BadgeAwarded", "BadgeRemoved", "BadgeRestored")) {
            runCatching {
                val listenerType = cls("api.event.PokeBadgesEvents\$${kind}Listener")
                val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                    when (method.name) {
                        "equals" -> proxy === args?.getOrNull(0)
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "CobbleSync PokeBadges listener"
                        else -> {
                            notifyPlayerOf(args?.getOrNull(0))
                            null
                        }
                    }
                }
                events.getMethod("register$kind", listenerType).invoke(null, listener)
            }.onFailure { CobbleSync.LOGGER.warn("Couldn't hook PokeBadges $kind event", it) }
        }
    }

    private fun notifyPlayerOf(event: Any?) {
        val player = event?.let { runCatching { it.javaClass.getMethod("player").invoke(it) }.getOrNull() } as? ServerPlayer
        player?.let { DashboardEvents.pokedexUpdated(it.uuid) }
    }

    /** {available, regions: [{id, nameEn, nameFr, slots: [{badges: [...], ownedBadgeId?, obtainedAt?}]}]} */
    fun badgesJson(server: MinecraftServer?, uuid: UUID): JsonObject {
        val root = JsonObject()
        val api = api
        if (api == null || server == null) {
            root.addProperty("available", false)
            return root
        }

        val regions = JsonArray()
        runCatching {
            val unovaSet = api.unovaSetOf.invoke(null, server)
            // Plain HashMap.get, deliberately not getPlayer(): see class doc.
            val box = savedData?.let { (api.playersField.get(it) as? Map<*, *>)?.get(uuid) }

            for (regionId in regionOrder) {
                val region = runCatching { api.regionById.invoke(null, regionId) }.getOrNull() ?: continue
                val slotCount = (api.orderedForRegion.invoke(null, region, unovaSet) as List<*>).size
                if (slotCount == 0) continue

                val slots = JsonArray()
                for (slot in 0 until slotCount) {
                    val candidates = (api.compatibleForSlot.invoke(null, region, slot, unovaSet) as List<*>).filterIsInstance<Item>()
                    val slotObj = JsonObject()
                    val badges = JsonArray()
                    for (item in candidates) {
                        val id = BuiltInRegistries.ITEM.getKey(item)
                        badges.add(badgeJson(api, item, id.toString(), id.path))
                        if (box != null && !slotObj.has("ownedBadgeId")) {
                            val storageIndex = api.storageIndex.invoke(item) as Int
                            if (api.boxHas.invoke(box, region, storageIndex) as Boolean) {
                                slotObj.addProperty("ownedBadgeId", id.toString())
                                val timestamp = api.boxTimestamp.invoke(box, region, storageIndex) as Long
                                if (timestamp > 0) slotObj.addProperty("obtainedAt", timestamp)
                            }
                        }
                    }
                    slotObj.add("badges", badges)
                    slots.add(slotObj)
                }

                val regionObj = JsonObject()
                regionObj.addProperty("id", regionId)
                regionObj.addProperty("nameEn", lang("en_us", "region.$MOD_ID.$regionId") ?: regionId.replaceFirstChar { it.uppercase() })
                regionObj.addProperty("nameFr", lang("fr_fr", "region.$MOD_ID.$regionId") ?: regionId.replaceFirstChar { it.uppercase() })
                regionObj.add("slots", slots)
                regions.add(regionObj)
            }
        }.onFailure {
            // Most likely a rare concurrent write to PokeBadges' maps while we read them.
            CobbleSync.LOGGER.debug("PokeBadges read failed for {}", uuid, it)
        }

        root.addProperty("available", true)
        root.add("regions", regions)
        return root
    }

    private fun badgeJson(api: Api, item: Item, id: String, path: String): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", id)
        obj.addProperty("nameEn", lang("en_us", "item.$MOD_ID.$path") ?: path)
        obj.addProperty("nameFr", lang("fr_fr", "item.$MOD_ID.$path") ?: path)
        val meta = api.metaGet.invoke(null, item)
        if (meta != null) {
            val leader = api.metaLeader.invoke(meta) as String
            val type = api.metaType.invoke(meta) as String
            lang("en_us", "gymleader.$MOD_ID.$leader")?.let { obj.addProperty("leaderEn", it) }
            lang("fr_fr", "gymleader.$MOD_ID.$leader")?.let { obj.addProperty("leaderFr", it) }
            lang("en_us", "gymtype.$MOD_ID.$type")?.let { obj.addProperty("typeEn", it) }
            lang("fr_fr", "gymtype.$MOD_ID.$type")?.let { obj.addProperty("typeFr", it) }
        }
        return obj
    }

    private fun lang(file: String, key: String): String? = LangFiles.get(MOD_ID, file)[key]
}
