package com.cobblesync.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class WebServerConfig(
    val enabled: Boolean,
    val bindAddress: String,
    val port: Int
) {
    companion object {
        private const val DEFAULT_ENABLED = true
        private const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
        private const val DEFAULT_PORT = 8080

        fun loadOrCreate(path: Path): WebServerConfig {
            val properties = Properties()
            val alreadyExists = Files.exists(path)

            if (alreadyExists) {
                Files.newInputStream(path).use { properties.load(it) }
            } else {
                Files.createDirectories(path.parent)
            }

            val config = WebServerConfig(
                enabled = properties.getProperty("enabled", DEFAULT_ENABLED.toString()).toBoolean(),
                bindAddress = properties.getProperty("bind-address", DEFAULT_BIND_ADDRESS),
                port = properties.getProperty("port", DEFAULT_PORT.toString()).toIntOrNull() ?: DEFAULT_PORT
            )

            if (!alreadyExists) {
                config.save(path)
            }

            return config
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
        """.trimIndent() + System.lineSeparator()
        Files.writeString(path, content)
    }
}
