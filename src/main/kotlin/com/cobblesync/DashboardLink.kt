package com.cobblesync

import com.cobblesync.config.WebServerConfig
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Clickable dashboard link in chat: sent on every join, and on demand with /cobblesync. */
object DashboardLink {
    private lateinit var config: WebServerConfig
    private var warnedNoUrl = false

    fun register(config: WebServerConfig) {
        this.config = config

        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            sendTo(handler.player, server)
        }

        // No client command may use this root: the server tree shadows it on connect (that's why
        // the model export lives under /cobblesyncexport).
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("cobblesync").executes { context ->
                    val source = context.source
                    val player = source.player
                    if (player != null) {
                        if (!sendTo(player, source.server)) source.sendFailure(Component.literal(noLinkText(player)))
                    } else {
                        val url = urlFor(source.server)
                        source.sendSystemMessage(Component.literal("[CobbleSync] ${url ?: "public-url is not set in webserver.conf"}"))
                    }
                    1
                }
            )
        }
    }

    /** Explicit public-url, else localhost in singleplayer; null on a dedicated server without one. */
    private fun urlFor(server: MinecraftServer): String? = when {
        !config.enabled -> null
        config.publicUrl.isNotEmpty() -> config.publicUrl
        !server.isDedicatedServer -> "http://localhost:${config.port}"
        else -> {
            if (!warnedNoUrl) {
                warnedNoUrl = true
                CobbleSync.LOGGER.warn("No public-url in webserver.conf: players won't get a dashboard link in chat.")
            }
            null
        }
    }

    private fun sendTo(player: ServerPlayer, server: MinecraftServer): Boolean {
        val url = urlFor(server) ?: return false
        val french = player.clientInformation().language().startsWith("fr")
        val link = Component.literal(url).withStyle { style ->
            style.withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(
                    HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        Component.literal(if (french) "Ouvrir le dashboard" else "Open the dashboard")
                    )
                )
        }
        val message = Component.literal("[CobbleSync] ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(if (french) "Ton Pokédex en ligne : " else "Your online Pokédex: ").withStyle(ChatFormatting.WHITE))
            .append(link)
        player.sendSystemMessage(message)
        return true
    }

    private fun noLinkText(player: ServerPlayer): String =
        if (player.clientInformation().language().startsWith("fr")) {
            "[CobbleSync] Aucun lien configuré (public-url dans webserver.conf)."
        } else {
            "[CobbleSync] No link configured (public-url in webserver.conf)."
        }
}
