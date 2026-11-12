package com.skyler.scanon.engine

import kotlin.math.roundToInt

/**
 * DNS-tunnel protocol modes (2.8, redesigned 2.8.2).
 *
 * DNS tunnels push traffic *through a normal DNS resolver*: the client encodes
 * upstream data in the labels of queries under a zone the tunnel server is
 * authoritative for (an NS delegation), and the resolver in the middle must
 * forward those queries faithfully and return the answers unmodified. The
 * resolver that works best is not just "the fastest": it must not intercept or
 * poison answers, must pass the record types the protocol rides on, must
 * honour EDNS and large answers, must survive long QNAMEs, and must have
 * near-zero loss — a DNS tunnel retransmits at the DNS layer, so 20% loss
 * feels like a dead link.
 *
 * Modes are *protocols*, never apps (apps like SlipNet or WhiteDNS bundle
 * several of these protocols; there is no "WhiteDNS protocol"). No mode
 * carries a domain: the test domain is always the user's own, typed in the
 * form. What a mode selects is which carrier record types get probed on
 * fresh random subdomains of that domain:
 *
 * - **Auto** (the default): the union of every carrier below — TXT, A, AAAA,
 *   CNAME, MX, NS, SRV, NULL, HTTPS, SVCB — plus a long-name probe, so one
 *   scan ranks a resolver for every protocol at once and reports a
 *   per-protocol verdict for each resolver.
 * - **DNSTT**: TXT only. DNSTT, NoizDNS (a DPI-evasion variant of DNSTT that
 *   uses the same server and wire format) and Slipstream all carry data in
 *   TXT queries and TXT answers, so one mode covers all three.
 * - **VayDNS**: a DNSTT fork whose downstream record type is configurable —
 *   TXT (default), CNAME, A, AAAA, MX, NS or SRV.
 * - **MasterDNS**: the MasterDnsVPN / StormDNS / CottenDNS family, whose
 *   clients pick their carriers from TXT, NULL, HTTPS, SVCB (and MX). NULL
 *   and HTTPS/SVCB are the types resolvers most often strip, so they are the
 *   discriminating test for this family.
 * - **Soar**: TXT downstream with an AAAA-chunk fallback, and long QNAMEs
 *   (it probes name lengths from ~101 up to 253 chars), so this mode adds
 *   the long-name probe.
 * - **TheFeed**: encrypted feed/chat data rides standard DNS TXT queries and
 *   responses in both directions — TXT only, like DNSTT.
 */
data class TunnelMode(
    val id: String,
    /** Short chip label — a protocol name, e.g. "DNSTT". */
    val name: String,
    /** Carrier record types probed on fresh random subdomains of the test domain. */
    val types: List<String>,
    /** Also probe a long (~155-char) multi-label name, the shape of real tunnel packets. */
    val longName: Boolean = false,
)

object TunnelModes {
    val ALL: List<TunnelMode> = listOf(
        TunnelMode("auto", "Auto",
            listOf("TXT", "A", "AAAA", "CNAME", "MX", "NS", "SRV", "NULL", "HTTPS", "SVCB"), longName = true),
        TunnelMode("dnstt", "DNSTT", listOf("TXT")),
        TunnelMode("vaydns", "VayDNS", listOf("TXT", "CNAME", "A", "AAAA", "MX", "NS", "SRV")),
        TunnelMode("masterdns", "MasterDNS", listOf("TXT", "NULL", "HTTPS", "SVCB", "MX")),
        TunnelMode("soar", "Soar", listOf("TXT", "AAAA"), longName = true),
        TunnelMode("thefeed", "TheFeed", listOf("TXT")),
    )

    fun of(id: String): TunnelMode? = ALL.firstOrNull { it.id == id }
}

/**
 * 0..100 tunnel suitability of one resolver report. Working and unpoisoned
 * are hard requirements; after that, success rate dominates (loss kills a
 * tunnel), then the full median, then jitter.
 */
fun tunnelScore(r: DnsReport): Int {
    if (!r.working) return 0
    val median = r.medianMs ?: return 0
    val reliability = r.successRate * 60.0
    val speed = (40.0 - median / 12.0).coerceIn(0.0, 40.0)
    val jitterPenalty = ((r.jitterMs ?: 0.0) / 8.0).coerceIn(0.0, 15.0)
    var score = (reliability + speed - jitterPenalty).roundToInt().coerceIn(1, 100)
    // E2E verification, when run, is decisive: a resolver that cannot carry a real
    // tunnel session is capped no matter how fast its single queries looked.
    if (r.e2e.startsWith("fail")) score = minOf(score, 25)
    else if (r.e2e.startsWith("pass")) score = minOf(100, score + 5)
    return score
}

/** Plain-text tunnel report: ranked working resolvers, ready to share or save. */
fun tunnelReport(mode: TunnelMode, domain: String, reports: List<DnsReport>): String {
    val ranked = reports.filter { it.working }
        .sortedWith(compareByDescending<DnsReport> { tunnelScore(it) }.thenBy { it.medianMs ?: Double.MAX_VALUE })
    val sb = StringBuilder()
    sb.append("ScanON tunnel report — ${mode.name} protocol mode\n")
    sb.append("Tunnel domain tested: $domain\n")
    sb.append("Ranked by tunnel score (success rate first, then median, then jitter).\n")
    sb.append("Use the best resolver as the DNS server in your tunnel client.\n\n")
    if (ranked.isEmpty()) {
        sb.append("No working resolver found for this domain. Try the Thorough preset or a different network.\n")
    } else {
        ranked.forEachIndexed { i, r ->
            sb.append("${i + 1}. ${r.server}  score ${tunnelScore(r)}  ${Fmt.ms(r.medianMs)} ms median  ")
            sb.append("ok ${r.successes}/${r.queries}  loss ${Fmt.pct(r.lossPct)}  ${r.protocol.uppercase()}")
            if (r.proto.isNotEmpty()) sb.append("  ${r.proto}")
            if (r.e2e.isNotEmpty()) sb.append("  E2E ${r.e2e}")
            if (r.lastDetail.isNotEmpty()) sb.append("  [${r.lastDetail}]")
            sb.append("\n")
        }
    }
    val bad = reports.filter { !it.working }
    if (bad.isNotEmpty()) {
        sb.append("\nNot usable: ${bad.joinToString(", ") { it.server + if (it.poisoned) " (poisoned)" else " (no answer)" }}\n")
    }
    return sb.toString()
}
