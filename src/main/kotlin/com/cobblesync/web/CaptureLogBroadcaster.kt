package com.cobblesync.web

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet

/** Registry of open SSE connections for the global capture activity feed (not per-player). */
object CaptureLogBroadcaster {
    private val connections = CopyOnWriteArraySet<OutputStream>()

    fun register(out: OutputStream) {
        connections.add(out)
    }

    fun unregister(out: OutputStream) {
        connections.remove(out)
    }

    fun notifyNewEntry() {
        BroadcastExecutor.execute {
            val message = "event: capture-log-updated\ndata: {}\n\n".toByteArray(StandardCharsets.UTF_8)
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
