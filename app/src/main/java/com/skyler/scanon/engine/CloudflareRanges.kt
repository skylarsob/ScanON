package com.skyler.scanon.engine

import java.math.BigInteger
import kotlin.random.Random

/** Cloudflare's published edge ranges (https://www.cloudflare.com/ips/) and a size-weighted random picker. */
object CloudflareRanges {
    val CIDRS = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
        "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
        "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    )
    val CIDRS_V6 = listOf(
        "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
        "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32",
    )

    private data class Range(val base: Long, val size: Long)

    private val ranges: List<Range> = CIDRS.map {
        val base = IpUtils.parseIpv4(it.substringBefore('/'))!!
        Range(base, 1L shl (32 - it.substringAfter('/').toInt()))
    }
    private val totalSize = ranges.sumOf { it.size }

    fun contains(ip: String): Boolean =
        CIDRS.any { IpUtils.cidrContains(it, ip) } || CIDRS_V6.any { IpUtils.cidrContains(it, ip) }

    /** Random distinct IPv4 addresses, weighted by range size; network (.0) and broadcast (.255) octets skipped. */
    fun randomIps(count: Int, random: Random = Random.Default): List<String> {
        val wanted = count.coerceIn(1, 100_000)
        val out = LinkedHashSet<String>()
        var guard = 0
        val maxGuard = wanted * 30 + 100
        while (out.size < wanted && guard++ < maxGuard) {
            var pick = random.nextLong(totalSize)
            var chosen = ranges.last()
            for (r in ranges) {
                if (pick < r.size) { chosen = r; break }
                pick -= r.size
            }
            val v = chosen.base + random.nextLong(chosen.size)
            val last = v and 255
            if (last == 0L || last == 255L) continue
            out += IpUtils.longToIpv4(v)
        }
        return out.toList()
    }

    /** Random IPv6 addresses inside Cloudflare's /32 and /29 blocks (host part randomised, ::0 avoided). */
    fun randomIpv6(count: Int, random: Random = Random.Default): List<String> {
        val wanted = count.coerceIn(1, 10_000)
        val out = LinkedHashSet<String>()
        var guard = 0
        while (out.size < wanted && guard++ < wanted * 10) {
            val cidr = CIDRS_V6[random.nextInt(CIDRS_V6.size)]
            val prefix = cidr.substringAfter('/').toInt()
            val base = IpUtils.ipv6ToBig(IpUtils.parseIpv6(cidr.substringBefore('/'))!!)
            val host = BigInteger(128 - prefix, java.util.Random(random.nextLong()))
            if (host.signum() == 0) continue
            out += IpUtils.formatIpv6(IpUtils.bigToIpv6(base.add(host)))
        }
        return out.toList()
    }
}
