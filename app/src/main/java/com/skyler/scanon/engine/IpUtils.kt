package com.skyler.scanon.engine

import java.math.BigInteger

/**
 * Pure, allocation-light IP helpers. Nothing here touches the network: a hostname is never resolved
 * by accident (the 2.3 parser called InetAddress.getByName on anything it did not recognise, which does a DNS
 * lookup and, on the main thread, threw NetworkOnMainThreadException).
 */
object IpUtils {

    /** Parses dotted-quad IPv4 strictly (4 decimal parts, 0..255, no leading '+', max 3 digits). */
    fun parseIpv4(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty() || t.length > 15) return null
        var value = 0L
        var part = 0
        var digits = 0
        var parts = 0
        for (c in t) {
            if (c == '.') {
                if (digits == 0) return null
                value = (value shl 8) or part.toLong()
                parts++
                part = 0; digits = 0
            } else if (c in '0'..'9') {
                digits++
                if (digits > 3) return null
                part = part * 10 + (c - '0')
                if (part > 255) return null
            } else return null
        }
        if (digits == 0 || parts != 3) return null
        return (value shl 8) or part.toLong()
    }

    fun longToIpv4(v: Long): String =
        "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

    fun isIpv4(s: String) = parseIpv4(s) != null

    /** Parses IPv6 (with "::", embedded IPv4 tail, optional %zone and [brackets]) into 16 bytes. */
    fun parseIpv6(input: String): ByteArray? {
        var s = input.trim().removePrefix("[").removeSuffix("]")
        val pct = s.indexOf('%')
        if (pct >= 0) s = s.substring(0, pct)
        if (!s.contains(':')) return null
        var tail: ByteArray? = null
        val lastColon = s.lastIndexOf(':')
        if (s.substring(lastColon + 1).contains('.')) {
            val v4 = parseIpv4(s.substring(lastColon + 1)) ?: return null
            tail = byteArrayOf((v4 shr 24).toByte(), (v4 shr 16).toByte(), (v4 shr 8).toByte(), v4.toByte())
            s = s.substring(0, lastColon) + if (s.substring(0, lastColon).endsWith(":")) ":" else ""
            // "::1.2.3.4" -> body "::" ; "64:ff9b::1.2.3.4" -> body "64:ff9b::"
            if (s.endsWith(":::")) s = s.dropLast(1)
        }
        val halves = s.split("::")
        if (halves.size > 2) return null
        fun groups(p: String): List<Int>? {
            if (p.isEmpty()) return emptyList()
            return p.split(':').map { g ->
                if (g.isEmpty() || g.length > 4) return null
                g.toIntOrNull(16) ?: return null
            }
        }
        val head = groups(halves[0]) ?: return null
        val back = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val tailGroups = if (tail != null) 2 else 0
        val total = head.size + back.size + tailGroups
        if (halves.size == 1 && total != 8) return null
        if (halves.size == 2 && total > 7) return null
        val vals = IntArray(8)
        head.forEachIndexed { i, v -> vals[i] = v }
        back.forEachIndexed { i, v -> vals[8 - back.size - tailGroups + i] = v }
        if (tail != null) {
            vals[6] = ((tail[0].toInt() and 255) shl 8) or (tail[1].toInt() and 255)
            vals[7] = ((tail[2].toInt() and 255) shl 8) or (tail[3].toInt() and 255)
        }
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i * 2] = (vals[i] shr 8).toByte()
            out[i * 2 + 1] = vals[i].toByte()
        }
        return out
    }

    fun isIpv6(s: String) = parseIpv6(s) != null
    fun isIp(s: String) = isIpv4(s) || isIpv6(s)

    /** RFC 5952 canonical text form (lowercase, longest zero run compressed). */
    fun formatIpv6(b: ByteArray): String {
        require(b.size == 16)
        val g = IntArray(8) { ((b[it * 2].toInt() and 255) shl 8) or (b[it * 2 + 1].toInt() and 255) }
        var bestStart = -1; var bestLen = 0; var i = 0
        while (i < 8) {
            if (g[i] == 0) {
                var j = i
                while (j < 8 && g[j] == 0) j++
                if (j - i > bestLen && j - i >= 2) { bestStart = i; bestLen = j - i }
                i = j
            } else i++
        }
        val sb = StringBuilder()
        i = 0
        while (i < 8) {
            if (i == bestStart) {
                sb.append("::"); i += bestLen; continue
            }
            if (sb.isNotEmpty() && !sb.endsWith(":")) sb.append(':')
            sb.append(Integer.toHexString(g[i]))
            i++
        }
        return sb.toString()
    }

    fun ipv6ToBig(b: ByteArray): BigInteger = BigInteger(1, b)
    fun bigToIpv6(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(16)
        val n = minOf(16, raw.size)
        System.arraycopy(raw, raw.size - n, out, 16 - n, n)
        return out
    }

    /** Normalises any IP literal to its canonical form, or null when it is not an IP. */
    fun canonical(s: String): String? {
        parseIpv4(s)?.let { return longToIpv4(it) }
        parseIpv6(s)?.let { return formatIpv6(it) }
        return null
    }

    /**
     * Private, loopback, link-local, CGNAT, benchmark, documentation, multicast and other non-routable space.
     * A DNS answer made only of these for a public name is the signature of DNS interception
     * (e.g. Iran's 10.10.34.34/35/36 block page).
     */
    fun isBogon(ip: String): Boolean {
        parseIpv4(ip)?.let { v ->
            fun inNet(net: String, prefix: Int): Boolean {
                val n = parseIpv4(net)!!
                val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                return (v and mask) == (n and mask)
            }
            return inNet("0.0.0.0", 8) || inNet("10.0.0.0", 8) || inNet("100.64.0.0", 10) ||
                inNet("127.0.0.0", 8) || inNet("169.254.0.0", 16) || inNet("172.16.0.0", 12) ||
                inNet("192.0.0.0", 24) || inNet("192.0.2.0", 24) || inNet("192.168.0.0", 16) ||
                inNet("198.18.0.0", 15) || inNet("198.51.100.0", 24) || inNet("203.0.113.0", 24) ||
                inNet("224.0.0.0", 4) || inNet("240.0.0.0", 4)
        }
        parseIpv6(ip)?.let { b ->
            val first = b[0].toInt() and 255
            val second = b[1].toInt() and 255
            if (b.all { it == 0.toByte() }) return true                       // ::
            if (b.take(15).all { it == 0.toByte() } && b[15] == 1.toByte()) return true // ::1
            if ((first and 0xFE) == 0xFC) return true                          // fc00::/7 ULA
            if (first == 0xFE && (second and 0xC0) == 0x80) return true        // fe80::/10
            if (first == 0xFF) return true                                     // multicast
            if (first == 0x20 && second == 0x01 && b[2] == 0x0d.toByte() && b[3] == 0xb8.toByte()) return true // 2001:db8::/32
            // ::ffff:a.b.c.d — judge the embedded IPv4
            if (b.take(10).all { it == 0.toByte() } && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()) {
                val v4 = ((b[12].toLong() and 255) shl 24) or ((b[13].toLong() and 255) shl 16) or
                    ((b[14].toLong() and 255) shl 8) or (b[15].toLong() and 255)
                return isBogon(longToIpv4(v4))
            }
            return false
        }
        return false
    }

    /** True when [ip] lies inside [cidr] (both families). */
    fun cidrContains(cidr: String, ip: String): Boolean {
        val slash = cidr.indexOf('/')
        if (slash < 0) return canonical(cidr) == canonical(ip)
        val base = cidr.substring(0, slash)
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return false
        val b4 = parseIpv4(base)
        val i4 = parseIpv4(ip)
        if (b4 != null && i4 != null) {
            if (prefix !in 0..32) return false
            val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            return (b4 and mask) == (i4 and mask)
        }
        val b6 = parseIpv6(base) ?: return false
        val i6 = parseIpv6(ip) ?: return false
        if (prefix !in 0..128) return false
        val shift = 128 - prefix
        return ipv6ToBig(b6).shiftRight(shift) == ipv6ToBig(i6).shiftRight(shift)
    }

    /** "host", "host:port", "[v6]:port", "https://host/path?x" → bare lowercase host, or null when invalid. */
    fun normalizeHost(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            return canonical(s.substring(1, end))
        }
        if (s.count { it == ':' } == 1) s = s.substringBefore(':')
        canonical(s)?.let { return it }
        s = s.trimEnd('.').lowercase()
        val ascii = try { java.net.IDN.toASCII(s) } catch (_: Exception) { return null }
        if (ascii.isEmpty() || ascii.length > 253) return null
        val labels = ascii.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return null
        if (!labels.all { l -> l.all { it.isLetterOrDigit() || it == '-' || it == '_' } }) return null
        return ascii
    }
}
