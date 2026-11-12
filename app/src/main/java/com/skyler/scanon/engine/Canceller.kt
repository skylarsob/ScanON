package com.skyler.scanon.engine

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the sockets and processes a scan has open so that pressing Cancel really stops it.
 *
 * Blocking socket connect/read and a running `ping` do not react to coroutine cancellation; in 2.3 a cancelled
 * scan kept every in-flight socket and ping process alive until its own timeout. [cancelAll] closes them, which
 * makes the blocked call throw immediately.
 */
class Canceller {
    private val open: MutableSet<Closeable> = ConcurrentHashMap.newKeySet()

    @Volatile
    var cancelled: Boolean = false
        private set

    fun <T : Closeable> register(c: T): T {
        open.add(c)
        if (cancelled) closeQuietly(c)
        return c
    }

    fun unregister(c: Closeable) {
        open.remove(c)
    }

    fun cancelAll() {
        cancelled = true
        val snapshot = open.toList()
        open.clear()
        snapshot.forEach { closeQuietly(it) }
    }

    companion object {
        /** A canceller that is never cancelled, for one-off calls (diagnostics, vault retest of a single entry). */
        fun none() = Canceller()

        fun closeQuietly(c: Closeable?) {
            try { c?.close() } catch (_: Exception) {}
        }
    }
}
