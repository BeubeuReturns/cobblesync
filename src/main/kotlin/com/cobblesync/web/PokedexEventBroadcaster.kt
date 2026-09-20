package com.cobblesync.web

import com.cobblesync.CobbleSync
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Registry of open SSE connections per player, used to notify the dashboard in real time (see
 * [PokedexHandler]) without the browser having to poll.
 */
object PokedexEventBroadcaster {
    private val connections = ConcurrentHashMap<UUID, CopyOnWriteArraySet<OutputStream>>()

    fun register(uuid: UUID, out: OutputStream) {
        connections.getOrPut(uuid) { CopyOnWriteArraySet() }.add(out)
    }

    fun unregister(uuid: UUID, out: OutputStream) {
        connections[uuid]?.remove(out)
    }

    fun notifyUpdated(uuid: UUID) {
        val streams = connections[uuid] ?: return
        BroadcastExecutor.execute {
            val message = "event: pokedex-updated\ndata: {}\n\n".toByteArray(StandardCharsets.UTF_8)
            for (out in streams) {
                try {
                    synchronized(out) {
                        out.write(message)
                        out.flush()
                    }
                } catch (e: Exception) {
                    streams.remove(out)
                    CobbleSync.LOGGER.debug("SSE connection closed for {}", uuid)
                }
            }
        }
    }
}
