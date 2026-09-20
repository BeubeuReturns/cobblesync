package com.cobblesync.util

import com.cobblesync.CobbleSync
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coalesces frequent "please persist this" requests (e.g. one per catch) into a single delayed
 * background write, instead of a synchronous full-file rewrite on every single event. Cobblemon
 * fires capture/scan events synchronously (likely on the main server tick thread) — writing
 * straight to disk from there blocks the tick, and doing it on every catch individually rewrites
 * the *entire* file every time even though only one entry actually changed.
 *
 * [requestSave] is cheap and safe to call from any thread, including the tick thread: it marks
 * the data dirty and, if nothing is already scheduled, schedules exactly one write [delayMillis]
 * out. Further calls that land inside that window just keep the dirty flag set rather than
 * pushing the write back out — a burst of catches collapses into one disk write with a bounded
 * worst-case latency, instead of either firing per-event or never firing at all under steady load.
 * [flushNow] runs the write immediately and synchronously — used on shutdown so a pending
 * debounced save is never lost.
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
