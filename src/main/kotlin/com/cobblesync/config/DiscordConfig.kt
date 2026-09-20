package com.cobblesync.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class DiscordConfig(
    val enabled: Boolean,
    val webhookUrl: String,
    val language: String,
    val notifyShiny: Boolean,
    val notifyNewSpecies: Boolean
) {
    companion object {
        private const val DEFAULT_ENABLED = false
        private const val DEFAULT_WEBHOOK_URL = ""
        private const val DEFAULT_LANGUAGE = "en"
        private const val DEFAULT_NOTIFY_SHINY = true
        private const val DEFAULT_NOTIFY_NEW_SPECIES = false

        fun loadOrCreate(path: Path): DiscordConfig {
            val properties = Properties()
            val alreadyExists = Files.exists(path)

            if (alreadyExists) {
                Files.newInputStream(path).use { properties.load(it) }
            } else {
                Files.createDirectories(path.parent)
            }

            val config = DiscordConfig(
                enabled = properties.getProperty("enabled", DEFAULT_ENABLED.toString()).toBoolean(),
                webhookUrl = properties.getProperty("webhook-url", DEFAULT_WEBHOOK_URL),
                language = properties.getProperty("language", DEFAULT_LANGUAGE).lowercase().let { if (it == "fr") "fr" else "en" },
                notifyShiny = properties.getProperty("notify-shiny", DEFAULT_NOTIFY_SHINY.toString()).toBoolean(),
                notifyNewSpecies = properties.getProperty("notify-new-species", DEFAULT_NOTIFY_NEW_SPECIES.toString()).toBoolean()
            )

            if (!alreadyExists) {
                config.save(path)
            }

            return config
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
            # Post a message on every shiny catch
            notify-shiny=$notifyShiny
            # Post a message on a player's first-ever catch of a species
            notify-new-species=$notifyNewSpecies
        """.trimIndent() + System.lineSeparator()
        Files.writeString(path, content)
    }
}
