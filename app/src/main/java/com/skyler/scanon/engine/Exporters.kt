package com.skyler.scanon.engine

import org.json.JSONArray
import org.json.JSONObject

enum class ExportFormat(val ext: String, val mime: String) {
    TXT("txt", "text/plain"),
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
}

/** Plain-text / CSV / JSON renderers. Numbers are always written with a '.' decimal point and ASCII digits. */
object Exporters {

    fun csvField(v: Any?): String {
        val s = v?.toString() ?: ""
        if (s.isEmpty()) return ""
        val needsQuote = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || s.first() == ' ' || s.last() == ' '
        // Neutralise spreadsheet formula injection (=, +, -, @ at the start of a text cell).
        val safe = if (s.first() in "=+@" || (s.first() == '-' && s.toDoubleOrNull() == null)) "'$s" else s
        return if (needsQuote) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun csv(rows: List<List<Any?>>) = rows.joinToString("\r\n") { r -> r.joinToString(",") { csvField(it) } } + "\r\n"

    fun ipLine(r: TargetReport): String =
        "${r.endpoint()}  ${r.method}  ${r.reliability}  ${Fmt.ms(r.primaryMs())} ms  ok ${r.successes}/${r.tests}" +
            "  jitter ${Fmt.ms(r.jitterMs)}  loss ${Fmt.pct(r.loss)}  score ${Fmt.ms(r.score())}" +
            (r.downloadMbps?.let { "  ${Fmt.num(it)} Mbps" } ?: "")

    fun ips(reports: List<TargetReport>, format: ExportFormat, geo: (String) -> GeoInfo? = { null }): String = when (format) {
        ExportFormat.TXT -> reports.joinToString("\n") { ipLine(it) } + "\n"
        ExportFormat.CSV -> csv(
            listOf(listOf("ip", "port", "method", "reliability", "score", "median_ms", "p90_ms", "jitter_ms", "loss_pct",
                "tcp_median_ms", "tls_avg_ms", "ttfb_avg_ms", "successes", "tests", "http_status", "tls_version",
                "cert_days_left", "download_mbps", "country", "asn", "org", "failures")) +
                reports.map { r ->
                    val g = geo(r.ip)
                    listOf(r.ip, r.port, r.method, r.reliability, Fmt.num(r.score()), Fmt.num(r.primaryMs()), Fmt.num(r.totalP90),
                        Fmt.num(r.jitterMs), Fmt.num(r.loss), Fmt.num(r.connectMedian), Fmt.num(r.tlsAvg), Fmt.num(r.ttfbAvg),
                        r.successes, r.tests, r.statusCode, r.tlsVersion, r.certDaysLeft, r.downloadMbps?.let { Fmt.num(it, 2) },
                        g?.countryCode, g?.asn, g?.org, r.failures.entries.joinToString(" ") { "${it.key}=${it.value}" })
                }
        )
        ExportFormat.JSON -> JSONArray().apply {
            reports.forEach { r ->
                put(JSONObject().apply {
                    put("ip", r.ip); put("port", r.port); put("method", r.method); put("reliability", r.reliability)
                    put("score", round(r.score())); putOpt("median_ms", r.primaryMs()?.let(::round))
                    putOpt("p90_ms", r.totalP90?.let(::round)); putOpt("jitter_ms", r.jitterMs?.let(::round))
                    put("loss_pct", round(r.loss)); putOpt("tcp_median_ms", r.connectMedian?.let(::round))
                    putOpt("tls_avg_ms", r.tlsAvg?.let(::round)); putOpt("ttfb_avg_ms", r.ttfbAvg?.let(::round))
                    put("successes", r.successes); put("tests", r.tests); putOpt("http_status", r.statusCode)
                    putOpt("tls_version", r.tlsVersion); putOpt("cert_days_left", r.certDaysLeft)
                    putOpt("download_mbps", r.downloadMbps?.let(::round))
                    put("samples_ms", JSONArray(r.totalSamples.map(::round)))
                    put("failures", JSONObject(r.failures as Map<*, *>))
                    geo(r.ip)?.let { g ->
                        put("geo", JSONObject().apply {
                            putOpt("country", g.countryCode); putOpt("city", g.city); putOpt("asn", g.asn); putOpt("org", g.org)
                        })
                    }
                })
            }
        }.toString(2)
    }

    fun dnsLine(r: DnsReport): String =
        "${r.server}  ${r.protocol.uppercase()}  ${if (r.working) "ok" else if (r.poisoned) "POISONED" else "fail"}" +
            "  ${Fmt.ms(r.medianMs)} ms  ok ${r.successes}/${r.queries}  ${r.rcodeName}  " +
            r.answers.take(4).joinToString(", ") { "${it.type} ${it.data}" } +
            (if (r.hijack) "  HIJACK" else "") +
            (if (r.proto.isNotEmpty()) "  ${r.proto}" else "") +
            (if (r.e2e.isNotEmpty()) "  E2E ${r.e2e}" else "") +
            (if (r.lastDetail.isNotEmpty()) "  [${r.lastDetail}]" else "")

    fun dns(reports: List<DnsReport>, format: ExportFormat): String = when (format) {
        ExportFormat.TXT -> reports.joinToString("\n") { dnsLine(it) } + "\n"
        ExportFormat.CSV -> csv(
            listOf(listOf("server", "protocol", "qtype", "working", "poisoned", "median_ms", "avg_ms", "jitter_ms", "loss_pct",
                "successes", "queries", "rcode", "dnssec_ad", "answers", "failures")) +
                reports.map { r ->
                    listOf(r.server, r.protocol, r.qtype, r.working, r.poisoned, Fmt.num(r.medianMs), Fmt.num(r.avgMs), Fmt.num(r.jitterMs),
                        Fmt.num(r.lossPct), r.successes, r.queries, r.rcodeName, r.dnssecAd,
                        r.answers.joinToString(" ") { "${it.type}:${it.data}" },
                        r.failures.entries.joinToString(" ") { "${it.key}=${it.value}" })
                }
        )
        ExportFormat.JSON -> JSONArray().apply {
            reports.forEach { r ->
                put(JSONObject().apply {
                    put("server", r.server); put("protocol", r.protocol); put("qtype", r.qtype)
                    put("working", r.working); put("poisoned", r.poisoned)
                    putOpt("median_ms", r.medianMs?.let(::round)); putOpt("avg_ms", r.avgMs?.let(::round))
                    putOpt("jitter_ms", r.jitterMs?.let(::round)); put("loss_pct", round(r.lossPct))
                    put("successes", r.successes); put("queries", r.queries); put("rcode", r.rcodeName); put("dnssec_ad", r.dnssecAd)
                    put("answers", JSONArray().apply {
                        r.answers.forEach { a -> put(JSONObject().apply { put("type", a.type); put("data", a.data); put("ttl", a.ttl) }) }
                    })
                    put("failures", JSONObject(r.failures as Map<*, *>))
                })
            }
        }.toString(2)
    }

    private fun round(v: Double): Double = Math.round(v * 100.0) / 100.0
}
