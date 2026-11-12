package com.skyler.scanon

import com.skyler.scanon.engine.CloudflareRanges
import com.skyler.scanon.engine.IpUtils
import com.skyler.scanon.engine.TargetParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class IpParsingTest {

    @Test fun ipv4Strict() {
        assertEquals(0x01020304L, IpUtils.parseIpv4("1.2.3.4"))
        assertEquals(0xFFFFFFFFL, IpUtils.parseIpv4("255.255.255.255"))
        assertNull(IpUtils.parseIpv4("256.1.1.1"))
        assertNull(IpUtils.parseIpv4("1.2.3"))
        assertNull(IpUtils.parseIpv4("1.2.3.4.5"))
        assertNull(IpUtils.parseIpv4("1..3.4"))
        assertNull(IpUtils.parseIpv4("a.b.c.d"))
        assertNull(IpUtils.parseIpv4("1.2.3.4/24"))
        assertNull(IpUtils.parseIpv4("1234.1.1.1"))
    }

    @Test fun ipv6ParseAndFormat() {
        assertEquals("2606:4700::1111", IpUtils.canonical("2606:4700:0:0:0:0:0:1111"))
        assertEquals("::1", IpUtils.canonical("0:0:0:0:0:0:0:1"))
        assertEquals("::", IpUtils.canonical("::"))
        assertEquals("::ffff:1.2.3.4".let { IpUtils.canonical(it) }, "::ffff:102:304")
        assertEquals("64:ff9b::102:304", IpUtils.canonical("64:ff9b::1.2.3.4"))
        assertEquals("2001:db8::1", IpUtils.canonical("[2001:DB8::1]"))
        assertEquals("fe80::1", IpUtils.canonical("fe80::1%wlan0"))
        assertNull(IpUtils.parseIpv6("1:2:3:4:5:6:7:8:9"))
        assertNull(IpUtils.parseIpv6("1::2::3"))
        assertNull(IpUtils.parseIpv6("12345::"))
        assertNull(IpUtils.parseIpv6("1.2.3.4"))
        // RFC 5952: a single zero group is not compressed, the longest run is.
        assertEquals("1:0:2::3", IpUtils.canonical("1:0:2:0:0:0:0:3"))
    }

    @Test fun bogons() {
        listOf("10.10.34.34", "192.168.1.1", "127.0.0.1", "100.64.0.1", "0.0.0.0", "169.254.1.1", "198.18.0.1",
            "224.0.0.1", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1").forEach { assertTrue(IpUtils.isBogon(it), it) }
        listOf("1.1.1.1", "8.8.8.8", "104.16.0.1", "2606:4700::1111", "172.32.0.1", "100.128.0.1").forEach { assertFalse(IpUtils.isBogon(it), it) }
    }

    @Test fun cidrContains() {
        assertTrue(IpUtils.cidrContains("104.16.0.0/13", "104.23.255.255"))
        assertFalse(IpUtils.cidrContains("104.16.0.0/13", "104.24.0.0"))
        assertTrue(IpUtils.cidrContains("2606:4700::/32", "2606:4700:abcd::1"))
        assertFalse(IpUtils.cidrContains("2606:4700::/32", "2606:4701::1"))
        assertTrue(IpUtils.cidrContains("0.0.0.0/0", "9.9.9.9"))
    }

    @Test fun normalizeHost() {
        assertEquals("example.com", IpUtils.normalizeHost("https://user@Example.COM:8443/path?q=1#x"))
        assertEquals("example.com", IpUtils.normalizeHost("example.com."))
        assertEquals("1.2.3.4", IpUtils.normalizeHost("1.2.3.4:80"))
        assertEquals("2001:db8::1", IpUtils.normalizeHost("[2001:db8::1]:443"))
        assertTrue(IpUtils.normalizeHost("مثال.آزمایشی")!!.startsWith("xn--"))
        assertNull(IpUtils.normalizeHost("bad host"))
        assertNull(IpUtils.normalizeHost("a..b"))
        assertNull(IpUtils.normalizeHost(""))
    }

    @Test fun cidrSlash31And32() {
        // 2.3 produced zero hosts for /31.
        assertEquals(listOf("10.0.0.0", "10.0.0.1"), TargetParser.parse("10.0.0.1/31").ips)
        assertEquals(listOf("10.0.0.7"), TargetParser.parse("10.0.0.7/32").ips)
        val r24 = TargetParser.parse("192.0.2.77/24").ips
        assertEquals(254, r24.size)
        assertEquals("192.0.2.1", r24.first())
        assertEquals("192.0.2.254", r24.last())
        assertEquals(2, TargetParser.parse("192.0.2.0/30").ips.size)
    }

    @Test fun badPrefixesRejected() {
        val r = TargetParser.parse("1.2.3.4/33\n1.2.3.4/-1\n::/129\n1.2.3.4/x")
        assertTrue(r.ips.isEmpty())
        assertEquals(4, r.invalid)
    }

    @Test fun ranges() {
        assertEquals((1..9).map { "1.2.3.$it" }, TargetParser.parse("1.2.3.1-1.2.3.9").ips)
        assertEquals((250..255).map { "1.2.3.$it" }, TargetParser.parse("1.2.3.250-255").ips)
        val crossing = TargetParser.parse("1.2.3.254-1.2.4.1").ips
        assertEquals(listOf("1.2.3.254", "1.2.3.255", "1.2.4.0", "1.2.4.1"), crossing)
        val bad = TargetParser.parse("1.2.3.9-1.2.3.1\n1.2.3.1-300")
        assertTrue(bad.ips.isEmpty()); assertEquals(2, bad.invalid)
    }

    @Test fun globalBudgetAndSampling() {
        val r = TargetParser.parse("10.0.0.0/16\n10.1.0.0/16", maxHosts = 1000)
        assertEquals(1000, r.ips.size)
        assertTrue(r.truncated)
        val s = TargetParser.parse("10.0.0.0/8", maxHosts = 500, sampleLarge = true, random = Random(1))
        assertEquals(500, s.ips.size)
        assertTrue(s.ips.all { IpUtils.cidrContains("10.0.0.0/8", it) })
        // Sampled, not the first 500 addresses.
        assertFalse(s.ips.all { it.startsWith("10.0.") })
    }

    @Test fun ipv6Cidr() {
        val r = TargetParser.parse("2001:db8::/126")
        assertEquals(listOf("2001:db8::", "2001:db8::1", "2001:db8::2", "2001:db8::3"), r.ips)
        val big = TargetParser.parse("2606:4700::/32", maxHosts = 50, sampleLarge = true, random = Random(7))
        assertEquals(50, big.ips.size)
        assertTrue(big.ips.all { IpUtils.cidrContains("2606:4700::/32", it) })
        // Base not aligned to prefix is masked.
        assertEquals("2001:db8::4", TargetParser.parse("2001:db8::5/126").ips.first())
    }

    @Test fun hostnamesCommentsAndDedupe() {
        val r = TargetParser.parse("# list\n1.1.1.1, 1.1.1.1 ; example.com\n1.1.1.1 # dup\nnot_valid!")
        assertEquals(listOf("1.1.1.1"), r.ips)
        assertEquals(listOf("example.com"), r.hostnames)
        assertEquals(1, r.invalid)
    }

    @Test fun ports() {
        assertEquals(listOf(443, 2053, 8443, 8444, 8445), TargetParser.parsePorts("443, 2053 8443-8445 443"))
        assertEquals(emptyList<Int>(), TargetParser.parsePorts("0 70000 abc"))
        assertEquals(64, TargetParser.parsePorts("1-1000").size)
    }

    @Test fun cloudflareRandom() {
        val ips = CloudflareRanges.randomIps(300, Random(3))
        assertEquals(300, ips.size)
        assertTrue(ips.all { CloudflareRanges.contains(it) })
        assertTrue(ips.none { it.endsWith(".0") || it.endsWith(".255") })
        val v6 = CloudflareRanges.randomIpv6(20, Random(4))
        assertEquals(20, v6.size)
        assertTrue(v6.all { CloudflareRanges.contains(it) })
    }
}
