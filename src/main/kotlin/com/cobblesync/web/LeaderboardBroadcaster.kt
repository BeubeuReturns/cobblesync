package com.cobblesync.web

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet

/** Registry of open SSE connections for the leaderboard (not per-player). */
object LeaderboardBroadcaster {
    private val connections = CopyOnWriteArraySet<OutputStream>()

    fun register(out: OutputStream) {
        connections.add(out)
    }

    fun unregister(out: OutputStream) {
        connections.remove(out)
    }

    fun notifyChanged() {
        BroadcastExecutor.execute {
            val message = "event: leaderboard-updated\ndata: {}\n\n".toByteArray(StandardCharsets.UTF_8)
            for (out in connections) {
                try {
                    synchronized(out) {
                        out.write(message)
                        out.flush()
                    }
                } catch (e: Exception) {
                    connections.remove(out)
                }
            }
        }
    }
}
