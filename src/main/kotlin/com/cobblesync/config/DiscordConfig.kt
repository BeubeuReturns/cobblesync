package com.cobblesync.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties

data class DiscordConfig(
    val enabled: Boolean,
    val webhookUrl: String,
    val language: String,
    val notifyShiny: Boolean,
    val notifyNewSpecies: Boolean,
    val notifyLegendary: Boolean = true,
    val notifyBadges: Boolean = true,
    val notifyRegionComplete: Boolean = true
) {
    /** A setting added after the first release: written into older files that lack it. */
    private class LaterSetting(val key: String, val comment: String, val defaultValue: String)

    companion object {
        private const val DEFAULT_ENABLED = false
        private const val DEFAULT_WEBHOOK_URL = ""
        private const val DEFAULT_LANGUAGE = "en"
        private const val DEFAULT_NOTIFY_SHINY = true
        private const val DEFAULT_NOTIFY_NEW_SPECIES = false

        private val NOTIFY_LEGENDARY = LaterSetting(
            "notify-legendary",
            "# Post a message on every legendary or mythical catch",
            "true"
        )
        private val NOTIFY_BADGES = LaterSetting(
            "notify-badges",
            "# PokeBadges (optional mod): post a message when a player earns a gym badge",
            "true"
        )
        private val NOTIFY_REGION_COMPLETE = LaterSetting(
            "notify-region-complete",
            "# PokeBadges (optional mod): post a message when a player has every badge of a region",
            "true"
        )

        private val LATER_SETTINGS = listOf(NOTIFY_LEGENDARY, NOTIFY_BADGES, NOTIFY_REGION_COMPLETE)

        fun loadOrCreate(path: Path): DiscordConfig {
            val properties = Properties()
            val alreadyExists = Files.exists(path)

            if (alreadyExists) {
                Files.newInputStream(path).use { properties.load(it) }
            } else {
                Files.createDirectories(path.parent)
            }

            fun later(setting: LaterSetting) = properties.getProperty(setting.key, setting.defaultValue).trim().toBoolean()

            val config = DiscordConfig(
                enabled = properties.getProperty("enabled", DEFAULT_ENABLED.toString()).toBoolean(),
                webhookUrl = properties.getProperty("webhook-url", DEFAULT_WEBHOOK_URL).trim(),
                language = properties.getProperty("language", DEFAULT_LANGUAGE).lowercase().let { if (it == "fr") "fr" else "en" },
                notifyShiny = properties.getProperty("notify-shiny", DEFAULT_NOTIFY_SHINY.toString()).toBoolean(),
                notifyNewSpecies = properties.getProperty("notify-new-species", DEFAULT_NOTIFY_NEW_SPECIES.toString()).toBoolean(),
                notifyLegendary = later(NOTIFY_LEGENDARY),
                notifyBadges = later(NOTIFY_BADGES),
                notifyRegionComplete = later(NOTIFY_REGION_COMPLETE)
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
            // Leading newline: a hand-edited file may not end with one.
            return nl + setting.comment + nl + "${setting.key}=$value" + nl
        }
    }

    private fun save(path: Path) {
        val content = """
            # CobbleSync Discord webhook notifications
            # Set to true and fill in webhook-url to enable.
            enabled=$enabled
            # Discord webhook URL (Server Settings > Integrations > Webhooks). Keep this secret.
            webhook-url=$webhookUrl
            # Language for message text, independent of any dashboard viewer's toggle: en or fr
            language=$language
            # Post a message on a player's first shiny catch of each species
            notify-shiny=$notifyShiny
            # Post a message on a player's first-ever catch of a species
            notify-new-species=$notifyNewSpecies
        """.trimIndent().replace("\n", System.lineSeparator()) + System.lineSeparator() +
            block(NOTIFY_LEGENDARY, notifyLegendary.toString()) +
            block(NOTIFY_BADGES, notifyBadges.toString()) +
            block(NOTIFY_REGION_COMPLETE, notifyRegionComplete.toString())
        Files.writeString(path, content)
    }
}
