package com.cobblesync.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties

data class WebServerConfig(
    val enabled: Boolean,
    val bindAddress: String,
    val port: Int,
    val publicUrl: String,
    val pokebadgesRegionOrder: List<String>
) {
    /** A setting added after the first release: written into older files that lack it. */
    private class LaterSetting(val key: String, val comment: String, val defaultValue: String)

    companion object {
        private const val DEFAULT_ENABLED = true
        private const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
        private const val DEFAULT_PORT = 8080

        private val PUBLIC_URL = LaterSetting(
            "public-url",
            """
            # Dashboard address shown to players in chat (on join and with /cobblesync), e.g.
            # http://my-server.example:8080. Leave empty: singleplayer uses http://localhost:<port>,
            # a dedicated server then shows no link (it can't know its own public address).
            """.trimIndent(),
            ""
        )

        private val REGION_ORDER = LaterSetting(
            "pokebadges-region-order",
            """
            # PokeBadges (optional mod): badge regions shown on the dashboard, in this order.
            # Regions left out are hidden. Ids: kanto,johto,hoenn,sinnoh,unova,kalos,galar,paldea
            """.trimIndent(),
            "sinnoh,kanto,johto,hoenn,unova,kalos,galar,paldea"
        )

        private val LATER_SETTINGS = listOf(PUBLIC_URL, REGION_ORDER)

        fun loadOrCreate(path: Path): WebServerConfig {
            val properties = Properties()
            val alreadyExists = Files.exists(path)

            if (alreadyExists) {
                Files.newInputStream(path).use { properties.load(it) }
            } else {
                Files.createDirectories(path.parent)
            }

            fun later(setting: LaterSetting) = properties.getProperty(setting.key, setting.defaultValue)

            val config = WebServerConfig(
                enabled = properties.getProperty("enabled", DEFAULT_ENABLED.toString()).toBoolean(),
                bindAddress = properties.getProperty("bind-address", DEFAULT_BIND_ADDRESS),
                port = properties.getProperty("port", DEFAULT_PORT.toString()).toIntOrNull() ?: DEFAULT_PORT,
                publicUrl = later(PUBLIC_URL).trim().trimEnd('/'),
                pokebadgesRegionOrder = later(REGION_ORDER).split(",")
                    .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            )

            if (!alreadyExists) {
                config.save(path)
            } else {
                // Append missing settings so they're discoverable, without rewriting the user's file.
                val missing = LATER_SETTINGS.filter { !properties.containsKey(it.key) }
                if (missing.isNotEmpty()) {
                    Files.writeString(path, missing.joinToString("") { block(it, it.defaultValue) }, StandardOpenOption.APPEND)
                }
            }

            return config
        }

        private fun block(setting: LaterSetting, value: String): String {
            val nl = System.lineSeparator()
            return nl + setting.comment.replace("\n", nl) + nl + "${setting.key}=$value" + nl
        }
    }

    private fun save(path: Path) {
        val content = """
            # CobbleSync web server settings
            # Set to false to disable the built-in HTTP server (e.g. if using an external reverse proxy)
            enabled=$enabled
            # Listen address (0.0.0.0 = all interfaces)
            bind-address=$bindAddress
            # Dashboard port
            port=$port
        """.trimIndent().replace("\n", System.lineSeparator()) + System.lineSeparator() +
            block(PUBLIC_URL, publicUrl) +
            block(REGION_ORDER, pokebadgesRegionOrder.joinToString(","))
        Files.writeString(path, content)
    }
}
