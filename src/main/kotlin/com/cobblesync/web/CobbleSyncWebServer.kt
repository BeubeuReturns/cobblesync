package com.cobblesync.web

import com.cobblesync.CobbleSync
import com.cobblesync.config.WebServerConfig
import com.sun.net.httpserver.Filter
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class CobbleSyncWebServer(
    private val config: WebServerConfig,
    private val configDir: Path
) {
    private var httpServer: HttpServer? = null
    private var executor: ThreadPoolExecutor? = null

    fun start() {
        val webRoot = configDir.resolve("web")
        StaticFileExtractor.extractIfMissing(webRoot)

        val http = HttpServer.create(InetSocketAddress(config.bindAddress, config.port), 0)
        // Bounded: live streams are capped at DashboardEvents.MAX_CONNECTIONS, the rest serves
        // short requests. Beyond the limit, new requests are dropped instead of spawning threads
        // until the game server runs out of memory.
        val pool = ThreadPoolExecutor(
            4, DashboardEvents.MAX_CONNECTIONS + 50, 60, TimeUnit.SECONDS, SynchronousQueue()
        ) { r -> Thread(r, "cobblesync-web").apply { isDaemon = true } }
        http.executor = pool
        executor = pool

        val routes = listOf(
            "/api/players/" to PokedexHandler(),
            "/api/players" to PlayersHandler(),
            "/api/species/" to SpeciesInfoHandler(),
            "/api/item/" to ItemIconHandler(),
            "/api/capture-log" to CaptureLogHandler(),
            "/api/leaderboard" to LeaderboardHandler(),
            "/api/events" to DashboardEvents,
            "/" to StaticFileHandler(webRoot),
        )
        for ((path, handler) in routes) {
            http.createContext(path, handler).filters.add(SecurityHeaders)
        }
        http.start()
        httpServer = http

        CobbleSync.LOGGER.info("CobbleSync dashboard started on http://{}:{}", config.bindAddress, config.port)
    }

    fun stop() {
        DashboardEvents.closeAll()
        httpServer?.stop(1)
        httpServer = null
        executor?.shutdownNow()
        executor = null
        CobbleSync.LOGGER.info("CobbleSync web server stopped.")
    }

    /**
     * Read-only dashboard: GET/HEAD only, and browser hardening headers on every response.
     * The CSP allows exactly what the page loads: its own files, plus sprites from
     * raw.githubusercontent.com and form lookups on pokeapi.co.
     */
    private object SecurityHeaders : Filter() {
        override fun description() = "CobbleSync security headers"

        override fun doFilter(exchange: HttpExchange, chain: Chain) {
            val headers = exchange.responseHeaders
            headers.add("X-Content-Type-Options", "nosniff")
            headers.add("X-Frame-Options", "DENY")
            headers.add("Referrer-Policy", "no-referrer")
            headers.add(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: https://raw.githubusercontent.com; " +
                    "connect-src 'self' https://pokeapi.co; style-src 'self' 'unsafe-inline'; " +
                    "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
            )
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                headers.add("Allow", "GET, HEAD")
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return
            }
            chain.doFilter(exchange)
        }
    }
}
