package com.cobblesync.web

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shared dispatcher for all SSE broadcasts (pokedex/capture-log/leaderboard). Cobblemon fires the
 * events that trigger these synchronously (POKEDEX_DATA_CHANGED_POST, likely on the main server
 * tick thread) — writing to open dashboard sockets directly from that callback would block the
 * tick if a client's connection is slow or stalled. Every notify* call is dispatched here instead,
 * so it always returns immediately; a single background thread is enough since catches/scans are
 * low-frequency events, and it keeps write ordering deterministic without adding real concurrency.
 */
object BroadcastExecutor {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cobblesync-sse-broadcast").apply { isDaemon = true }
    }

    fun execute(task: () -> Unit) {
        executor.execute(task)
    }

    fun shutdown() {
        executor.shutdown()
    }
}
