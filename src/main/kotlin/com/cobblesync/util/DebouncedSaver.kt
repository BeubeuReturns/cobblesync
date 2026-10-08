package com.cobblesync.util

import com.cobblesync.CobbleSync
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coalesces frequent "please persist this" requests (one per catch) into a single delayed
 * background write, instead of a synchronous full-file rewrite on every event. Cobblemon fires
 * capture/scan events synchronously, likely on the main tick thread, so writing straight to disk
 * there blocks the tick and rewrites the entire file for one changed entry.
 *
 * [requestSave] is cheap and safe from any thread: it marks the data dirty and schedules exactly
 * one write [delayMillis] out if nothing's already scheduled. Calls inside that window just keep
 * the dirty flag set instead of pushing the write back out, so a burst of catches collapses into
 * one disk write with bounded latency. [flushNow] writes immediately and synchronously, used on
 * shutdown so a pending debounced save is never lost.
 */
class DebouncedSaver(
    private val delayMillis: Long = 2000,
    private val writer: () -> Unit
) {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "cobblesync-saver").apply { isDaemon = true }
    }
    private val dirty = AtomicBoolean(false)
    private var pending: ScheduledFuture<*>? = null

    fun requestSave() {
        dirty.set(true)
        synchronized(this) {
            if (pending?.isDone != false) {
                pending = scheduler.schedule({ flush() }, delayMillis, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun flush() {
        if (dirty.compareAndSet(true, false)) {
            runCatching { writer() }.onFailure { CobbleSync.LOGGER.warn("Failed to persist data", it) }
        }
    }

    fun flushNow() {
        synchronized(this) { pending?.cancel(false) }
        flush()
    }

    fun shutdown() {
        flushNow()
        scheduler.shutdown()
    }
}
