package com.skyler.scanon.engine

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

/** Test methods the IP scanner can run against every target. */
enum class Method(val id: String, val label: String) {
    TCP("tcp", "TCP"),
    HTTP("http", "HTTP"),
    HTTPS("https", "HTTPS"),
    WS("ws", "WS"),
    ICMP("icmp", "ICMP");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** One attempt of one method against one IP. */
data class TestResult(
    val ok: Boolean,
    val failure: String = "",
    val detail: String = "",
    val connectMs: Double? = null,
    val tlsMs: Double? = null,
    val ttfbMs: Double? = null,
    val totalMs: Double? = null,
    val statusCode: Int? = null,
    val tlsVersion: String? = null,
    val cipher: String? = null,
    val certDaysLeft: Long? = null,
    /** ICMP only: replies/sent from one ping run. */
    val icmpSent: Int? = null,
    val icmpReceived: Int? = null,
)

/** Aggregate of all attempts of one method against one IP:port. Immutable (2.3 mutated it from several threads). */
data class TargetReport(
    val ip: String,
    val method: String,
    val port: Int = 443,
    val tests: Int = 0,
    val successes: Int = 0,
    val connectMin: Double? = null,
    val connectAvg: Double? = null,
    val connectMax: Double? = null,
    val connectMedian: Double? = null,
    val jitterMs: Double? = null,
    val tlsAvg: Double? = null,
    val ttfbAvg: Double? = null,
    val totalAvg: Double? = null,
    val totalMedian: Double? = null,
    val totalP90: Double? = null,
    val totalSamples: List<Double> = emptyList(),
    val statusCode: Int? = null,
    val failures: Map<String, Int> = emptyMap(),
    val lastDetail: String = "",
    val tlsVersion: String? = null,
    val cipher: String? = null,
    val certDaysLeft: Long? = null,
    val lossPct: Double? = null,
    val downloadMbps: Double? = null,
) {
    val key: String get() = "$ip|$port|$method"
    val successRate: Double get() = if (tests > 0) successes.toDouble() / tests else 0.0
    val working: Boolean get() = successRate >= 0.5

    /** Packet loss: the measured ICMP loss when present, otherwise the share of failed attempts. */
    val loss: Double get() = lossPct ?: if (tests > 0) (1.0 - successRate) * 100.0 else 100.0

    val reliability: String
        get() = when {
            tests == 0 -> "untested"
            failures.keys == setOf("not_permitted") -> "inconclusive"
            successRate == 1.0 -> if ((jitterMs ?: 0.0) > 30.0) "partial" else "success"
            successRate < 0.6 -> if (successRate > 0.0) "unstable" else "failed"
            (jitterMs ?: 0.0) > 30.0 -> "unstable"
            else -> "partial"
        }

    fun primaryMs(): Double? = when (method) {
        "https", "http", "ws" -> totalMedian ?: totalAvg ?: connectAvg
        "icmp" -> connectAvg
        else -> connectMedian ?: connectAvg
    }

    fun score(): Double {
        val primary = primaryMs() ?: return 0.0
        if (tests == 0) return 0.0
        val latScore = (100.0 - primary / 8.0).coerceAtLeast(0.0)
        val jitScore = (100.0 - (jitterMs ?: 0.0) * 2.0).coerceAtLeast(0.0)
        return successRate * 40.0 + 0.4 * latScore + 0.2 * jitScore
    }

    /** "1.2.3.4" or "[2606::1]:8443" style endpoint for display/export. */
    fun endpoint(defaultPort: Int = 443): String = when {
        port == defaultPort -> ip
        ip.contains(':') -> "[$ip]:$port"
        else -> "$ip:$port"
    }
}

/** Scan parameters. [normalized] clamps everything into safe bounds so bad input can never crash a scan. */
data class ScanConfig(
    val methods: List<String> = listOf("https"),
    val ports: List<Int> = listOf(443),
    val hostname: String = "",
    val path: String = "/",
    val timeoutMs: Int = 3000,
    val attempts: Int = 3,
    val workers: Int = 50,
    /** Shrink the per-attempt timeout to ~3x the observed p95 once enough samples exist. */
    val adaptiveTimeout: Boolean = false,
    /** Stop the remaining attempts of a target after this many consecutive failures (0 = never). */
    val failFastAfter: Int = 2,
    val delayBetweenAttemptsMs: Long = 120,
) {
    val port: Int get() = ports.firstOrNull() ?: 443

    fun normalized(): ScanConfig = copy(
        methods = methods.filter { Method.of(it) != null }.distinct().ifEmpty { listOf("tcp") },
        ports = ports.filter { it in 1..65535 }.distinct().ifEmpty { listOf(443) }.take(64),
        hostname = hostname.trim(),
        path = path.trim().let { if (it.startsWith("/")) it else "/$it" }.take(512),
        timeoutMs = timeoutMs.coerceIn(300, 30_000),
        attempts = attempts.coerceIn(1, 20),
        workers = workers.coerceIn(1, 256),
        failFastAfter = failFastAfter.coerceIn(0, 20),
        delayBetweenAttemptsMs = delayBetweenAttemptsMs.coerceIn(0, 5_000),
    )

    companion object {
        val PRESETS = listOf("very_slow", "slow", "balanced", "fast", "very_fast", "iran", "iran_fast", "iran_deep", "battery")

        /** (timeoutMs, attempts, workers) per preset. "iran": fewer workers so the scan does not congest a lossy link. */
        fun preset(name: String): ScanConfig = when (name) {
            "very_fast" -> ScanConfig(timeoutMs = 2000, attempts = 2, workers = 200)
            "fast" -> ScanConfig(timeoutMs = 2500, attempts = 3, workers = 100)
            "slow" -> ScanConfig(timeoutMs = 5000, attempts = 5, workers = 15)
            "very_slow" -> ScanConfig(timeoutMs = 6000, attempts = 7, workers = 5)
            "iran" -> ScanConfig(timeoutMs = 5000, attempts = 7, workers = 20)
            // Iran, in a hurry: more parallel workers, still enough attempts to survive loss.
            "iran_fast" -> ScanConfig(timeoutMs = 3000, attempts = 4, workers = 40)
            // Iran, maximum confidence: slow, many attempts, few workers — for ranking a shortlist.
            "iran_deep" -> ScanConfig(timeoutMs = 6000, attempts = 9, workers = 12)
            // Gentle on battery and data: few workers, few attempts.
            "battery" -> ScanConfig(timeoutMs = 5000, attempts = 3, workers = 8)
            else -> ScanConfig(timeoutMs = 4000, attempts = 5, workers = 30)
        }
    }
}

/** Locale-independent statistics (2.3 used default-locale String.format, which printed Persian digits into exports). */
object Stats {
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /** Nearest-rank percentile, p in (0,1]. */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val idx = ceil(s.size * p).toInt().coerceIn(1, s.size) - 1
        return s[idx]
    }

    /** Population standard deviation; 0 for a single sample. */
    fun stddev(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        if (values.size == 1) return 0.0
        val avg = values.average()
        return sqrt(values.sumOf { (it - avg) * (it - avg) } / values.size)
    }

    /** RFC 3550-style jitter: mean absolute difference between consecutive samples. */
    fun meanDelta(values: List<Double>): Double? {
        if (values.size < 2) return if (values.isEmpty()) null else 0.0
        var sum = 0.0
        for (i in 1 until values.size) sum += kotlin.math.abs(values[i] - values[i - 1])
        return sum / (values.size - 1)
    }

    /** Drop the cold first sample once there are enough to spare (radio wake-up, route and TLS setup). */
    fun dropWarmup(values: List<Double>): List<Double> = if (values.size >= 4) values.drop(1) else values

    /**
     * Median absolute deviation — the robust jitter (3.0). Stddev lets one bad
     * run (a radio stall, a retransmit) dominate the number; MAD reports the
     * spread of the typical runs, which is what "stable vs flaky" means.
     */
    fun mad(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        if (values.size == 1) return 0.0
        val med = median(values) ?: return null
        return median(values.map { kotlin.math.abs(it - med) })
    }
}

object Fmt {
    fun ms(v: Double?, decimals: Int = 0): String =
        if (v == null) "-" else String.format(Locale.US, "%.${decimals}f", v)

    fun num(v: Double?, decimals: Int = 1): String =
        if (v == null) "-" else String.format(Locale.US, "%.${decimals}f", v)

    fun pct(v: Double?): String = if (v == null) "-" else String.format(Locale.US, "%.0f%%", v)
}
