package com.skyler.scanon.engine

import java.math.BigInteger
import kotlin.random.Random

/**
 * Expands the IP scanner's target text into a de-duplicated list of canonical IP literals.
 *
 * Accepted per token (tokens split on whitespace, ',' and ';'; '#' starts a comment):
 *  - IPv4 / IPv6 literal (IPv6 may be bracketed)
 *  - IPv4 CIDR /0../32, IPv6 CIDR /0../128
 *  - IPv4 ranges: 1.2.3.1-1.2.3.9 or 1.2.3.1-9
 *  - hostnames (returned separately, resolved by the caller off the main thread)
 *
 * [maxHosts] is a global budget for the whole text (2.3 applied it per token, so ten /16 lines produced
 * 655 360 strings on the main thread). When [sampleLarge] is true a block bigger than the remaining budget is
 * randomly sampled instead of truncated to its first addresses.
 */
object TargetParser {

    data class Result(
        val ips: List<String>,
        val hostnames: List<String>,
        val notes: List<String>,
        val invalid: Int,
        val truncated: Boolean,
    )

    private val SPLIT = Regex("[\\s,;]+")

    fun parse(text: String, maxHosts: Int = 65_536, sampleLarge: Boolean = false, random: Random = Random.Default): Result {
        val out = LinkedHashSet<String>()
        val hosts = LinkedHashSet<String>()
        val notes = ArrayList<String>()
        var invalid = 0
        var truncated = false
        val budget = maxHosts.coerceAtLeast(1)

        val tokens = text.lineSequence()
            .map { it.substringBefore('#') }
            .flatMap { it.split(SPLIT).asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (tok in tokens) {
            if (out.size >= budget) {
                truncated = true
                notes += "target budget of $budget hosts reached - '$tok' and later entries skipped"
                break
            }
            val remaining = budget - out.size
            try {
                when {
                    tok.contains('/') -> {
                        val base = tok.substringBefore('/')
                        val prefix = tok.substringAfter('/').toIntOrNull()
                            ?: throw IllegalArgumentException("bad prefix")
                        val v4 = IpUtils.parseIpv4(base)
                        if (v4 != null) {
                            if (prefix !in 0..32) throw IllegalArgumentException("IPv4 prefix must be 0-32")
                            val r = expandV4Cidr(v4, prefix, remaining, sampleLarge, random, out)
                            if (r) { truncated = true; notes += "$tok: larger than the remaining budget - ${if (sampleLarge) "randomly sampled" else "truncated"}" }
                        } else {
                            val v6 = IpUtils.parseIpv6(base) ?: throw IllegalArgumentException("bad base")
                            if (prefix !in 0..128) throw IllegalArgumentException("IPv6 prefix must be 0-128")
                            val r = expandV6Cidr(v6, prefix, remaining, sampleLarge, random, out)
                            if (r) { truncated = true; notes += "$tok: larger than the remaining budget - ${if (sampleLarge) "randomly sampled" else "truncated"}" }
                        }
                    }
                    tok.contains('-') && IpUtils.parseIpv4(tok.substringBefore('-')) != null -> {
                        val start = IpUtils.parseIpv4(tok.substringBefore('-'))!!
                        val endTok = tok.substringAfter('-').trim()
                        val end = if (endTok.contains('.')) {
                            IpUtils.parseIpv4(endTok) ?: throw IllegalArgumentException("bad range end")
                        } else {
                            val last = endTok.toIntOrNull() ?: throw IllegalArgumentException("bad range end")
                            if (last !in 0..255) throw IllegalArgumentException("range end must be 0-255")
                            (start and 0xFFFFFF00L) or last.toLong()
                        }
                        if (end < start) throw IllegalArgumentException("range end is before its start")
                        val size = end - start + 1
                        if (size > remaining) {
                            truncated = true
                            notes += "$tok: larger than the remaining budget - ${if (sampleLarge) "randomly sampled" else "truncated"}"
                            if (sampleLarge) sampleLongs(start, size, remaining, random) { out += IpUtils.longToIpv4(it) }
                            else { var v = start; while (v <= end && out.size < budget) { out += IpUtils.longToIpv4(v); v++ } }
                        } else {
                            var v = start
                            while (v <= end) { out += IpUtils.longToIpv4(v); v++ }
                        }
                    }
                    else -> {
                        val canon = IpUtils.canonical(tok)
                        if (canon != null) out += canon
                        else {
                            val host = IpUtils.normalizeHost(tok)
                            if (host != null && host.contains('.')) hosts += host
                            else throw IllegalArgumentException("not an IP, range, CIDR or hostname")
                        }
                    }
                }
            } catch (e: IllegalArgumentException) {
                invalid++
                if (notes.size < 50) notes += "'$tok': ${e.message} - skipped"
            }
        }
        return Result(out.toList(), hosts.toList(), notes, invalid, truncated)
    }

    /** Returns true when the block was cut (truncated or sampled). */
    private fun expandV4Cidr(ip: Long, prefix: Int, remaining: Int, sample: Boolean, random: Random, out: MutableSet<String>): Boolean {
        val size = 1L shl (32 - prefix)
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val net = ip and mask
        // /32 = one host, /31 = both (RFC 3021 point-to-point), otherwise skip network and broadcast.
        val first: Long
        val count: Long
        when {
            prefix == 32 -> { first = net; count = 1 }
            prefix == 31 -> { first = net; count = 2 }
            else -> { first = net + 1; count = size - 2 }
        }
        if (count <= remaining) {
            for (i in 0 until count) out += IpUtils.longToIpv4(first + i)
            return false
        }
        if (sample) sampleLongs(first, count, remaining, random) { out += IpUtils.longToIpv4(it) }
        else for (i in 0 until remaining.toLong()) out += IpUtils.longToIpv4(first + i)
        return true
    }

    private fun expandV6Cidr(addr: ByteArray, prefix: Int, remaining: Int, sample: Boolean, random: Random, out: MutableSet<String>): Boolean {
        val hostBits = 128 - prefix
        val base = IpUtils.ipv6ToBig(addr).shiftRight(hostBits).shiftLeft(hostBits)
        val size = BigInteger.ONE.shiftLeft(hostBits)
        if (size <= BigInteger.valueOf(remaining.toLong())) {
            var i = BigInteger.ZERO
            while (i < size) { out += IpUtils.formatIpv6(IpUtils.bigToIpv6(base + i)); i += BigInteger.ONE }
            return false
        }
        if (sample) {
            val seen = HashSet<BigInteger>()
            var guard = 0
            while (seen.size < remaining && guard++ < remaining * 20) {
                val off = BigInteger(hostBits, java.util.Random(random.nextLong())).mod(size)
                if (seen.add(off)) out += IpUtils.formatIpv6(IpUtils.bigToIpv6(base + off))
            }
        } else {
            var i = BigInteger.ZERO
            val lim = BigInteger.valueOf(remaining.toLong())
            while (i < lim) { out += IpUtils.formatIpv6(IpUtils.bigToIpv6(base + i)); i += BigInteger.ONE }
        }
        return true
    }

    /** Draws [n] distinct values from [start, start+size) without materialising the range. */
    private inline fun sampleLongs(start: Long, size: Long, n: Int, random: Random, sink: (Long) -> Unit) {
        val seen = HashSet<Long>(n * 2)
        var guard = 0
        while (seen.size < n && seen.size < size && guard++ < n * 20) {
            val v = start + random.nextLong(size)
            if (seen.add(v)) sink(v)
        }
    }

    /** "443, 2053 8443-8445" → [443, 2053, 8443, 8444, 8445]; invalid entries are dropped. Max 64 ports. */
    fun parsePorts(text: String): List<Int> {
        val out = LinkedHashSet<Int>()
        for (tok in text.split(SPLIT)) {
            val t = tok.trim()
            if (t.isEmpty()) continue
            if (t.contains('-')) {
                val a = t.substringBefore('-').toIntOrNull() ?: continue
                val b = t.substringAfter('-').toIntOrNull() ?: continue
                if (a !in 1..65535 || b !in 1..65535 || b < a) continue
                for (p in a..minOf(b, a + 63)) out += p
            } else {
                val p = t.toIntOrNull() ?: continue
                if (p in 1..65535) out += p
            }
            if (out.size >= 64) break
        }
        return out.take(64)
    }
}
