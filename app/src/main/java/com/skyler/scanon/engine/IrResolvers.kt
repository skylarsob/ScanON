package com.skyler.scanon.engine

import android.content.Context

/**
 * Real Iranian resolver catalogue (2.8.3): the net2share/ir-resolvers list —
 * resolver IPs that actually run inside Iranian datacenters and ISP networks,
 * bundled as the `ir-resolvers.txt` asset (7,854 addresses at build time).
 *
 * This replaces the hand-picked provider list (Shecan/Electro/…) that shipped
 * up to 2.8.2: Skylar asked for the datacenters' own resolvers instead. The
 * list is far too big to scan whole on a phone, so the UI loads a random
 * sample of a chosen size (or everything, for a desktop-style sweep).
 */
object IrResolvers {
    private const val ASSET = "ir-resolvers.txt"

    @Volatile private var cached: List<String>? = null

    /** All bundled resolver IPs, parsed and validated once. Call off the main thread. */
    fun load(context: Context): List<String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val list = try {
                context.assets.open(ASSET).bufferedReader().useLines { lines ->
                    lines.map { it.trim() }.filter { it.isNotEmpty() && IpUtils.parseIpv4(it) != null }.toList()
                }
            } catch (_: Exception) { emptyList() }
            cached = list
            return list
        }
    }

    fun count(context: Context): Int = load(context).size

    private const val RANGES_ASSET = "ir-ranges.txt"

    @Volatile private var cachedRanges: List<String>? = null

    /**
     * The /24 ranges those resolvers live in (3.0): 3,754 networks derived from
     * the bundled list. A range scan tries every address in them as a resolver,
     * which finds the sibling resolvers the list itself missed.
     */
    fun loadRanges(context: Context): List<String> {
        cachedRanges?.let { return it }
        synchronized(this) {
            cachedRanges?.let { return it }
            val list = try {
                context.assets.open(RANGES_ASSET).bufferedReader().useLines { lines ->
                    lines.map { it.trim() }.filter { it.endsWith("/24") && IpUtils.parseIpv4(it.removeSuffix("/24")) != null }.toList()
                }
            } catch (_: Exception) { emptyList() }
            cachedRanges = list
            return list
        }
    }

    fun rangeCount(context: Context): Int = loadRanges(context).size

    /** A random sample of [n] ranges (all of them, shuffled, when [n] covers the list). */
    fun sampleRanges(context: Context, n: Int): List<String> {
        val all = loadRanges(context)
        if (all.isEmpty()) return emptyList()
        val shuffled = all.shuffled()
        return if (n >= all.size) shuffled else shuffled.take(n.coerceAtLeast(1))
    }

    /** A random sample of [n] resolvers (all of them, shuffled, when [n] covers the list). */
    fun sample(context: Context, n: Int): List<String> {
        val all = load(context)
        if (all.isEmpty()) return emptyList()
        val shuffled = all.shuffled()
        return if (n >= all.size) shuffled else shuffled.take(n.coerceAtLeast(1))
    }
}
