package com.skyler.scanon.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.TimeUnit

/** Connectivity snapshot + real end-to-end checks. */
object DiagEngine {

    data class Snapshot(
        val transport: String = "-",
        val privateIp: String = "-",
        val privateIpv6: String = "-",
        val gateway: String = "-",
        val dnsServers: List<String> = emptyList(),
        val isVpn: Boolean = false,
        val validated: Boolean = false,
        val metered: Boolean = false,
        val downKbps: Int = 0,
        val upKbps: Int = 0,
    )

    /** ok: true = pass, false = fail, null = not applicable / inconclusive. */
    data class Check(val id: String, val name: String, val ok: Boolean?, val detail: String, val ms: Double? = null)

    fun snapshot(context: Context): Snapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return Snapshot()
        val caps = cm.getNetworkCapabilities(network)
        val links = cm.getLinkProperties(network)
        val transport = when {
            caps == null -> "-"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        val addrs = links?.linkAddresses?.map { it.address }?.filter { !it.isLoopbackAddress }.orEmpty()
        val v4 = addrs.firstOrNull { it is Inet4Address }?.hostAddress ?: "-"
        val v6 = addrs.firstOrNull { it !is Inet4Address && !it.isLinkLocalAddress }?.hostAddress?.substringBefore('%') ?: "-"
        val gw = links?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null && !it.gateway!!.isAnyLocalAddress }
            ?.gateway?.hostAddress?.substringBefore('%') ?: "-"
        val dns = links?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }.orEmpty()
        return Snapshot(
            transport = transport, privateIp = v4, privateIpv6 = v6, gateway = gw, dnsServers = dns,
            isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = cm.isActiveNetworkMetered,
            downKbps = caps?.linkDownstreamBandwidthKbps ?: 0,
            upKbps = caps?.linkUpstreamBandwidthKbps ?: 0,
        )
    }

    private fun ms(v: Double?) = if (v == null) "-" else "${Fmt.ms(v)} ms"

    fun gatewayCheck(snap: Snapshot, c: Canceller): Check {
        if (snap.gateway == "-") return Check("gateway", "Gateway", null, "no default gateway reported by Android")
        val r = NetEngine.icmpTest(snap.gateway, 2, 2, c)
        return Check("gateway", "Gateway ${snap.gateway}", r.ok,
            if (r.ok) "answers in ${ms(r.connectMs)} (${r.detail})" else "no answer: ${r.detail}", r.connectMs)
    }

    private fun dnsCheck(id: String, name: String, server: String, c: Canceller): Check {
        val rep = DnsEngine.probeResolver(server, "cloudflare.com", "A", "udp", 2, 3000, c = c)
        val ips = rep.answers.filter { it.type == "A" }.joinToString { it.data }
        return Check(id, name, rep.successes > 0 && !rep.poisoned, when {
            rep.poisoned -> "HIJACK SUSPECTED: answers for cloudflare.com are private IPs ($ips)"
            rep.successes > 0 -> "answers in ${ms(rep.avgMs)}: ${ips.ifEmpty { "no A records" }}"
            else -> "no answer (${rep.failures.keys.joinToString()})"
        }, rep.avgMs)
    }

    fun systemDnsCheck(snap: Snapshot, c: Canceller): Check {
        val sys = snap.dnsServers.firstOrNull()
            ?: return Check("sysdns", "System DNS", null, "Android reports no DNS server for this network")
        return dnsCheck("sysdns", "System DNS $sys", sys, c)
    }

    fun directDnsCheck(c: Canceller) = dnsCheck("dns1111", "Direct DNS 1.1.1.1", "1.1.1.1", c)

    fun httpsCheck(c: Canceller): Check {
        val r = NetEngine.httpsTest("1.1.1.1", "cloudflare.com", 443, "/", 5000, c)
        return Check("https", "HTTPS to 1.1.1.1 (SNI cloudflare.com)", r.ok,
            if (r.ok) "HTTP ${r.statusCode} in ${ms(r.totalMs)} (TLS ${ms(r.tlsMs)}, ${r.tlsVersion ?: "?"})"
            else "${r.failure}: ${r.detail}", r.totalMs)
    }

    fun ipv6Check(c: Canceller): Check {
        val aaaa = DnsEngine.queryUdp("1.1.1.1", "cloudflare.com", "AAAA", timeoutMs = 3000, c = c)
        val v6 = aaaa.response?.answers?.firstOrNull { it.type == "AAAA" }?.data
            ?: return Check("ipv6", "IPv6", null, "no AAAA answer for cloudflare.com via 1.1.1.1")
        val tcp = NetEngine.tcpTest(v6, 443, 3000, c)
        return Check("ipv6", "IPv6 ($v6)", tcp.ok,
            if (tcp.ok) "TCP 443 connects in ${ms(tcp.connectMs)}" else "TCP 443 failed: ${tcp.detail}", tcp.connectMs)
    }

    fun captivePortalCheck(): Check = try {
        val conn = URL("http://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            val code = conn.responseCode
            val len = conn.contentLength
            when {
                code == 204 -> Check("portal", "Captive portal", true, "generate_204 answered 204 - open internet, no portal")
                code in 300..399 -> Check("portal", "Captive portal", false, "redirect (HTTP $code) - a login portal is intercepting")
                code == 200 && len != 0 -> Check("portal", "Captive portal", false, "HTTP 200 with content - a login portal is likely intercepting")
                else -> Check("portal", "Captive portal", null, "unexpected answer HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Check("portal", "Captive portal", false, "check failed: ${e.message ?: e.javaClass.simpleName}")
    }

    fun cloudflareTrace(): Check = try {
        val conn = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6000; conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "ScanON-Android/2.4")
            val text = conn.inputStream.bufferedReader().use { it.readText().take(8192) }
            val map = text.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
            Check("trace", "Exit identity (Cloudflare trace)", true,
                "exit IP ${map["ip"] ?: "?"} · country ${map["loc"] ?: "?"} · edge ${map["colo"] ?: "?"} · " +
                    "TLS ${map["tls"] ?: "?"} · HTTP ${map["http"] ?: "?"} · warp ${map["warp"] ?: "off"}")
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Check("trace", "Exit identity (Cloudflare trace)", false, "trace failed: ${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * Path MTU: largest don't-fragment ping payload that gets an answer, tried from big to small.
     * 2.3 stopped at the first size that did not answer and reported "unsupported".
     */
    fun mtuProbe(target: String = "1.1.1.1", c: Canceller): Check {
        val sizes = listOf(1472, 1452, 1400, 1300, 1200, 1000, 548)
        for (size in sizes) {
            if (c.cancelled) break
            val out = try {
                val p = ProcessBuilder("/system/bin/ping", "-n", "-c", "1", "-W", "2", "-M", "do", "-s", size.toString(), target)
                    .redirectErrorStream(true).start()
                val closer = java.io.Closeable { p.destroy() }
                c.register(closer)
                try {
                    if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
                    p.inputStream.bufferedReader().use { it.readText() }
                } finally { c.unregister(closer) }
            } catch (e: Exception) {
                return Check("mtu", "Path MTU to $target", null, "ping unavailable: ${e.message}")
            }
            if (out.contains("invalid", true) || out.contains("usage", true) || out.contains("unknown", true) ||
                out.contains("not permitted", true)
            ) return Check("mtu", "Path MTU to $target", null, "this device's ping cannot set DF - MTU probe unsupported")
            if (Regex("1 (packets )?received|bytes from").containsMatchIn(out))
                return Check("mtu", "Path MTU to $target", true, "payload $size B passed with DF set - path MTU at least ${size + 28}")
        }
        return Check("mtu", "Path MTU to $target", false, "even a 548 B DF payload failed - path is very constrained or $target is unreachable")
    }

    /** All checks run concurrently (2.3 ran them one after another, ~30 s worst case). Order of the list is stable. */
    suspend fun runAll(snap: Snapshot, c: Canceller, onCheck: (Check) -> Unit = {}): List<Check> = coroutineScope {
        val jobs = listOf<suspend () -> Check>(
            { gatewayCheck(snap, c) }, { systemDnsCheck(snap, c) }, { directDnsCheck(c) }, { httpsCheck(c) },
            { ipv6Check(c) }, { captivePortalCheck() }, { cloudflareTrace() }, { mtuProbe("1.1.1.1", c) },
        ).map { f -> async(Dispatchers.IO) { f().also(onCheck) } }
        jobs.awaitAll()
    }
}
