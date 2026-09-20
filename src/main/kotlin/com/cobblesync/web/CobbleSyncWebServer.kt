package com.cobblesync.web

import com.cobblesync.CobbleSync
import com.cobblesync.config.WebServerConfig
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors

class CobbleSyncWebServer(
    private val config: WebServerConfig,
    private val configDir: Path
) {
    private var httpServer: HttpServer? = null

    fun start() {
        val webRoot = configDir.resolve("web")
        StaticFileExtractor.extractIfMissing(webRoot)

        val http = HttpServer.create(InetSocketAddress(config.bindAddress, config.port), 0)
        http.executor = Executors.newCachedThreadPool()
        http.createContext("/api/players/", PokedexHandler())
        http.createContext("/api/players", PlayersHandler())
        http.createContext("/api/species/", SpeciesInfoHandler())
        http.createContext("/api/item/", ItemIconHandler())
        http.createContext("/api/capture-log", CaptureLogHandler())
        http.createContext("/api/leaderboard", LeaderboardHandler())
        http.createContext("/", StaticFileHandler(webRoot))
        http.start()
        httpServer = http

        CobbleSync.LOGGER.info("CobbleSync dashboard started on http://{}:{}", config.bindAddress, config.port)
    }

    fun stop() {
        httpServer?.stop(1)
        httpServer = null
        CobbleSync.LOGGER.info("CobbleSync web server stopped.")
    }
}
