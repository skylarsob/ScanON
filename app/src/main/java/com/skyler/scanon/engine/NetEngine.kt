package com.skyler.scanon.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

/** Real network tests. Every function here blocks and must be called off the main thread. */
object NetEngine {

    private const val UA = "ScanON-Android/2.4"
    private val tlsFactory: SSLSocketFactory by lazy { SSLSocketFactory.getDefault() as SSLSocketFactory }
    private val rnd = SecureRandom()

    private fun nowMs() = System.nanoTime() / 1_000_000.0

    fun classify(e: Exception): String {
        if (e is SocketTimeoutException) return "timeout"
        val m = e.message.orEmpty()
        return when {
            m.contains("refused", true) -> "refused"
            m.contains("unreachable", true) || m.contains("No route", true) -> "unreachable"
            m.contains("EPERM", true) || m.contains("not permitted", true) -> "not_permitted"
            m.contains("Socket closed", true) -> "cancelled"
            else -> "other"
        }
    }

    private fun msg(e: Exception) = e.message ?: e.javaClass.simpleName

    private inline fun <T> tracked(c: Canceller, s: Closeable, block: () -> T): T {
        c.register(s)
        try {
            return block()
        } finally {
            c.unregister(s)
            Canceller.closeQuietly(s)
        }
    }

    fun tcpTest(ip: String, port: Int, timeoutMs: Int, c: Canceller = Canceller.none()): TestResult {
        val socket = Socket()
        return tracked(c, socket) {
            val t0 = nowMs()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)
                TestResult(true, connectMs = nowMs() - t0)
            } catch (e: Exception) {
                TestResult(false, classify(e), msg(e))
            }
        }
    }

    /** Result of reading an HTTP response head: status (null when none), time to first byte, error text. */
    data class Head(val status: Int?, val ttfbMs: Double?, val error: String, val headers: String)

    private val STATUS = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})")

    /** Reads up to the end of the response headers (max 16 KiB) with a buffered loop, not byte-by-byte. */
    fun readHead(input: InputStream, tSend: Double, max: Int = 16 * 1024): Head {
        val buf = ByteArray(max)
        var len = 0
        var ttfb: Double? = null
        try {
            while (len < max) {
                val n = input.read(buf, len, max - len)
                if (n < 0) break
                if (ttfb == null) ttfb = nowMs() - tSend
                val searchFrom = (len - 3).coerceAtLeast(0)
                len += n
                if (indexOfCrlfCrlf(buf, searchFrom, len) >= 0) break
            }
        } catch (e: Exception) {
            return Head(null, ttfb, msg(e), String(buf, 0, len, Charsets.ISO_8859_1))
        }
        val text = String(buf, 0, len, Charsets.ISO_8859_1)
        val status = STATUS.find(text)?.groupValues?.get(1)?.toIntOrNull()
        return Head(status, ttfb, if (status == null) "no HTTP response" else "", text)
    }

    fun indexOfCrlfCrlf(b: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i + 3 < to) {
            if (b[i] == '\r'.code.toByte() && b[i + 1] == '\n'.code.toByte() &&
                b[i + 2] == '\r'.code.toByte() && b[i + 3] == '\n'.code.toByte()
            ) return i
            i++
        }
        return -1
    }

    private fun request(method: String, path: String, host: String, extra: String = "") =
        "$method $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $UA\r\nAccept: */*\r\n${extra}Connection: close\r\n\r\n"

    fun httpTest(ip: String, hostname: String, port: Int, path: String, timeoutMs: Int, c: Canceller = Canceller.none()): TestResult {
        val socket = Socket()
        return tracked(c, socket) {
            val t0 = nowMs()
            try {
                socket.soTimeout = timeoutMs
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)
                val connectMs = nowMs() - t0
                val out = socket.getOutputStream()
                val tSend = nowMs()
                out.write(request("GET", path, hostname).toByteArray(Charsets.ISO_8859_1))
                out.flush()
                val head = readHead(socket.getInputStream(), tSend)
                val total = nowMs() - t0
                if (head.status == null) TestResult(false, "http", head.error, connectMs = connectMs, totalMs = total)
                else TestResult(true, connectMs = connectMs, ttfbMs = head.ttfbMs, totalMs = total, statusCode = head.status)
            } catch (e: Exception) {
                TestResult(false, classify(e), msg(e))
            }
        }
    }

    private fun isCertProblem(e: Exception): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t.javaClass.name.contains("Certificate") || t.message.orEmpty().contains("certificate", true) ||
                t.message.orEmpty().contains("hostname", true)
            ) return true
            t = t.cause
        }
        return false
    }

    /**
     * HTTPS pinned to [ip]: TCP connect, TLS handshake with SNI = [hostname] and full certificate + hostname
     * verification, then a real request. [websocket] sends an RFC 6455 upgrade instead and only counts 101 as success.
     */
    fun httpsTest(
        ip: String, hostname: String, port: Int, path: String, timeoutMs: Int,
        c: Canceller = Canceller.none(), websocket: Boolean = false, verifyTrace: Boolean = false,
    ): TestResult {
        val raw = Socket()
        return tracked(c, raw) {
            val t0 = nowMs()
            try {
                raw.soTimeout = timeoutMs
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)
            } catch (e: Exception) {
                return@tracked TestResult(false, classify(e), msg(e))
            }
            val connectMs = nowMs() - t0
            val tls = try {
                (tlsFactory.createSocket(raw, hostname, port, true) as SSLSocket).also { s ->
                    val p = s.sslParameters
                    p.endpointIdentificationAlgorithm = "HTTPS"
                    if (!IpUtils.isIp(hostname)) p.serverNames = listOf(SNIHostName(hostname))
                    s.sslParameters = p
                }
            } catch (e: Exception) {
                return@tracked TestResult(false, "tls", "TLS setup failed: ${msg(e)}", connectMs = connectMs)
            }
            tracked(c, tls) {
                val tTls = nowMs()
                try {
                    tls.startHandshake()
                } catch (e: Exception) {
                    val prefix = if (isCertProblem(e)) "certificate invalid for $hostname: " else "TLS handshake failed: "
                    val failure = if (e is SocketTimeoutException) "timeout" else "tls"
                    return@tracked TestResult(false, failure, prefix + msg(e), connectMs = connectMs)
                }
                val tlsMs = nowMs() - tTls
                val session = tls.session
                val days = try {
                    (session.peerCertificates.firstOrNull() as? X509Certificate)?.let {
                        TimeUnit.MILLISECONDS.toDays(it.notAfter.time - System.currentTimeMillis())
                    }
                } catch (_: Exception) { null }
                try {
                    val extra = if (websocket) {
                        val key = ByteArray(16).also { rnd.nextBytes(it) }
                        "Upgrade: websocket\r\nSec-WebSocket-Key: ${Base64.getEncoder().encodeToString(key)}\r\nSec-WebSocket-Version: 13\r\n"
                    } else ""
                    val req = if (websocket) {
                        "GET $path HTTP/1.1\r\nHost: $hostname\r\nUser-Agent: $UA\r\n${extra}Connection: Upgrade\r\n\r\n"
                    } else request("GET", path, hostname)
                    val out = tls.outputStream
                    val tSend = nowMs()
                    out.write(req.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    val head = readHead(tls.inputStream, tSend)
                    val total = nowMs() - t0
                    val base = TestResult(
                        ok = false, connectMs = connectMs, tlsMs = tlsMs, ttfbMs = head.ttfbMs, totalMs = total,
                        statusCode = head.status, tlsVersion = session.protocol, cipher = session.cipherSuite, certDaysLeft = days,
                    )
                    // Genuineness check (3.0): a 200 with a valid certificate still
                    // does not prove this IP is a Cloudflare edge — an intercepting
                    // middlebox with a trusted cert for the SNI name would pass.
                    // When the path is the trace endpoint, require the actual
                    // trace body (fl=/colo= fields only Cloudflare serves).
                    if (verifyTrace && head.status == 200) {
                        val body = readSmallBody(tls.inputStream, head.headers)
                        val colo = Regex("colo=([A-Za-z]{3})").find(body)?.groupValues?.get(1)
                        return@tracked if (colo != null && body.contains("fl=")) {
                            base.copy(ok = true, detail = "Cloudflare trace verified · colo $colo")
                        } else {
                            base.copy(failure = "not_cloudflare", detail = "HTTP 200 but the body is not a Cloudflare trace (intercepted or not Cloudflare)")
                        }
                    }
                    when {
                        head.status == null -> base.copy(failure = "http", detail = head.error, ttfbMs = null)
                        websocket && head.status != 101 ->
                            base.copy(failure = "ws", detail = "HTTP ${head.status} - no WebSocket endpoint at $path")
                        else -> base.copy(ok = true)
                    }
                } catch (e: Exception) {
                    TestResult(false, if (e is SocketTimeoutException) "timeout" else "http", msg(e), connectMs = connectMs, tlsMs = tlsMs)
                }
            }
        }
    }

    /** Reads a small response body (e.g. a trace page), starting from bytes the head reader already buffered. */
    private fun readSmallBody(input: InputStream, headText: String, max: Int = 4096): String {
        val sb = StringBuilder()
        val idx = headText.indexOf("\r\n\r\n")
        if (idx >= 0) sb.append(headText.substring(idx + 4))
        val buf = ByteArray(1024)
        try {
            while (sb.length < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - sb.length))
                if (n < 0) break
                sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private val PING_TIME = Regex("time[=<]([\\d.]+)\\s*ms")
    private val PING_LOSS = Regex("(\\d+(?:\\.\\d+)?)% packet loss")
    private val PING_TX = Regex("(\\d+) packets transmitted, (\\d+) (?:packets )?received")

    /** System ping with a hard deadline; the process is destroyed on timeout or cancel (2.3 waited forever). */
    fun icmpTest(ip: String, count: Int, timeoutSec: Int, c: Canceller = Canceller.none()): TestResult {
        val bin = if (IpUtils.isIpv6(ip) && java.io.File("/system/bin/ping6").exists()) "/system/bin/ping6" else "/system/bin/ping"
        val proc = try {
            ProcessBuilder(bin, "-n", "-c", count.toString(), "-i", "0.2", "-W", timeoutSec.toString(), ip)
                .redirectErrorStream(true).start()
        } catch (e: Exception) {
            return reachableFallback(ip, timeoutSec, "ping unavailable: ${msg(e)}")
        }
        val closer = Closeable { proc.destroy() }
        return tracked(c, closer) {
            val deadline = (count * timeoutSec + 3).toLong()
            val finished = try { proc.waitFor(deadline, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
            if (!finished) proc.destroyForcibly()
            val out = try { proc.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
            val times = PING_TIME.findAll(out).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
            val tx = PING_TX.find(out)
            val sent = tx?.groupValues?.get(1)?.toIntOrNull() ?: count
            val recv = tx?.groupValues?.get(2)?.toIntOrNull() ?: times.size
            val loss = PING_LOSS.find(out)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: if (sent > 0) (1.0 - recv.toDouble() / sent) * 100 else 100.0
            when {
                c.cancelled -> TestResult(false, "cancelled", "cancelled")
                out.contains("not permitted", true) -> TestResult(false, "not_permitted", out.trim().take(200))
                times.isEmpty() -> TestResult(false, "timeout", "system ping: no replies (${loss.toInt()}% loss)", icmpSent = sent, icmpReceived = 0)
                else -> TestResult(
                    ok = recv > 0, detail = "$recv/$sent replies, ${loss.toInt()}% loss (system ping)",
                    connectMs = times.average(), totalMs = times.max(), icmpSent = sent, icmpReceived = recv,
                )
            }
        }
    }

    private fun reachableFallback(ip: String, timeoutSec: Int, why: String): TestResult {
        val t0 = nowMs()
        return try {
            if (InetAddress.getByName(ip).isReachable(timeoutSec * 1000))
                TestResult(true, detail = "reachable (isReachable fallback)", connectMs = nowMs() - t0)
            else TestResult(false, "timeout", "not reachable (isReachable fallback; $why)")
        } catch (e: Exception) {
            TestResult(false, "other", "$why; ${msg(e)}")
        }
    }

    /**
     * Download throughput through [ip] using Cloudflare's public speed endpoint (SNI speed.cloudflare.com).
     * Only Cloudflare edge IPs serve it. Returns Mbit/s measured from first body byte to end, or a failure detail.
     */
    fun downloadTest(ip: String, bytes: Int = 4_000_000, timeoutMs: Int = 10_000, c: Canceller = Canceller.none()): Pair<Double?, String> {
        val host = "speed.cloudflare.com"
        val raw = Socket()
        return tracked(c, raw) {
            try {
                raw.soTimeout = timeoutMs
                raw.connect(InetSocketAddress(InetAddress.getByName(ip), 443), timeoutMs)
                val tls = tlsFactory.createSocket(raw, host, 443, true) as SSLSocket
                tracked(c, tls) {
                    val p = tls.sslParameters
                    p.endpointIdentificationAlgorithm = "HTTPS"
                    p.serverNames = listOf(SNIHostName(host))
                    tls.sslParameters = p
                    tls.startHandshake()
                    val out = tls.outputStream
                    out.write(request("GET", "/__down?bytes=$bytes", host).toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    val input = tls.inputStream
                    val head = readHead(input, nowMs(), 16 * 1024)
                    if (head.status != 200) return@tracked null to "HTTP ${head.status ?: "-"}: ${head.error}"
                    // Body bytes that arrived together with the headers count too.
                    val headerEnd = head.headers.indexOf("\r\n\r\n")
                    var got = if (headerEnd >= 0) (head.headers.length - headerEnd - 4).toLong() else 0L
                    val buf = ByteArray(64 * 1024)
                    val t0 = nowMs()
                    val hardStop = t0 + timeoutMs
                    while (got < bytes && nowMs() < hardStop) {
                        val n = input.read(buf)
                        if (n < 0) break
                        got += n
                    }
                    val secs = (nowMs() - t0) / 1000.0
                    if (got <= 0 || secs <= 0) null to "no body received"
                    else (got * 8 / 1_000_000.0 / secs) to "${got / 1024} KiB in ${Fmt.num(secs, 2)} s"
                }
            } catch (e: Exception) {
                null to "${classify(e)}: ${msg(e)}"
            }
        }
    }

    /** Resolves a hostname with the system resolver. Must run off the main thread. */
    fun resolveDomain(domain: String): Pair<List<String>, String?> = try {
        InetAddress.getAllByName(domain).mapNotNull { it.hostAddress?.let(IpUtils::canonical) }.distinct() to null
    } catch (e: Exception) {
        emptyList<String>() to "resolution failed: ${msg(e)}"
    }

    /** Shared rolling window of successful timings that drives the adaptive timeout. */
    class AdaptiveTimeout(private val maxMs: Int) {
        private val samples = ConcurrentLinkedDeque<Double>()
        fun record(ms: Double) {
            samples.addLast(ms)
            while (samples.size > 200) samples.pollFirst()
        }
        fun current(): Int {
            if (samples.size < 20) return maxMs
            val p95 = Stats.percentile(samples.toList(), 0.95) ?: return maxMs
            return (p95 * 3).toInt().coerceIn(800, maxMs)
        }
    }

    suspend fun runMethod(
        ip: String, port: Int, method: String, cfg: ScanConfig, c: Canceller,
        adaptive: AdaptiveTimeout? = null,
    ): List<TestResult> {
        val out = ArrayList<TestResult>()
        if (method == "icmp") {
            out += icmpTest(ip, cfg.attempts, (cfg.timeoutMs / 1000).coerceAtLeast(1), c)
            return out
        }
        var consecutiveFails = 0
        for (i in 0 until cfg.attempts) {
            coroutineContext.ensureActive()
            if (i > 0 && cfg.delayBetweenAttemptsMs > 0) delay(cfg.delayBetweenAttemptsMs) // cancellable (2.3: Thread.sleep)
            val timeout = adaptive?.current() ?: cfg.timeoutMs
            val host = cfg.hostname.ifEmpty { ip }
            val r = when (method) {
                "tcp" -> tcpTest(ip, port, timeout, c)
                "http" -> httpTest(ip, host, if (port == 443) 80 else port, cfg.path, timeout, c)
                "https" -> httpsTest(ip, host, port, cfg.path, timeout, c, verifyTrace = cfg.path.contains("cdn-cgi/trace"))
                "ws" -> httpsTest(ip, host, port, cfg.path, timeout, c, websocket = true)
                else -> TestResult(false, "other", "unknown method $method")
            }
            if (c.cancelled) break
            out += r
            if (r.ok) {
                consecutiveFails = 0
                (r.totalMs ?: r.connectMs)?.let { adaptive?.record(it) }
            } else {
                consecutiveFails++
                // Fail fast: a target that never answered gets dropped instead of burning every attempt.
                if (cfg.failFastAfter > 0 && consecutiveFails >= cfg.failFastAfter && out.none { it.ok }) break
            }
        }
        return out
    }

    fun aggregate(ip: String, method: String, results: List<TestResult>, port: Int = 443): TargetReport {
        val successes = results.count { it.ok }
        val connects = results.filter { it.ok }.mapNotNull { it.connectMs }
        val totals = Stats.dropWarmup(results.filter { it.ok }.mapNotNull { it.totalMs ?: it.connectMs })
        val tls = results.mapNotNull { it.tlsMs }
        val ttfb = results.mapNotNull { it.ttfbMs }
        val failures = LinkedHashMap<String, Int>()
        for (r in results) if (!r.ok && r.failure.isNotEmpty()) failures[r.failure] = (failures[r.failure] ?: 0) + 1
        val lastDetail = results.lastOrNull { it.detail.isNotEmpty() }?.detail.orEmpty()
        val last = results.lastOrNull { it.tlsVersion != null }
        val icmp = results.firstOrNull { it.icmpSent != null }
        // ICMP runs N pings in one attempt: report them as N tests so success rate means the same as elsewhere.
        val (tests, succ) = if (icmp != null) icmp.icmpSent!! to (icmp.icmpReceived ?: 0) else results.size to successes
        val loss = if (icmp != null && icmp.icmpSent!! > 0) (1.0 - (icmp.icmpReceived ?: 0).toDouble() / icmp.icmpSent) * 100 else null
        return TargetReport(
            ip = ip, method = method, port = port, tests = tests, successes = succ,
            connectMin = connects.minOrNull(), connectAvg = connects.takeIf { it.isNotEmpty() }?.average(),
            connectMax = connects.maxOrNull(), connectMedian = Stats.median(connects),
            jitterMs = Stats.mad(totals),
            tlsAvg = tls.takeIf { it.isNotEmpty() }?.average(), ttfbAvg = ttfb.takeIf { it.isNotEmpty() }?.average(),
            totalAvg = totals.takeIf { it.isNotEmpty() }?.average(), totalMedian = Stats.median(totals),
            totalP90 = Stats.percentile(totals, 0.9), totalSamples = totals,
            statusCode = results.lastOrNull { it.statusCode != null }?.statusCode,
            failures = failures, lastDetail = lastDetail,
            tlsVersion = last?.tlsVersion, cipher = last?.cipher, certDaysLeft = last?.certDaysLeft,
            lossPct = loss,
        )
    }

    /**
     * Scans every ip × port × method with a fixed pool of [ScanConfig.workers] coroutines fed by a channel.
     * 2.3 created one coroutine per target up front (65 536+ objects for a /16) and posted one UI coroutine per
     * result; here work items are produced lazily and [onReport] is called on the worker thread — the caller batches.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun scan(
        ips: List<String>, cfg0: ScanConfig, c: Canceller, onReport: (TargetReport) -> Unit,
    ) {
        val cfg = cfg0.normalized()
        val adaptive = if (cfg.adaptiveTimeout) AdaptiveTimeout(cfg.timeoutMs) else null
        val dispatcher = Dispatchers.IO.limitedParallelism(cfg.workers)
        data class Work(val ip: String, val port: Int, val method: String)
        val queue = Channel<Work>(capacity = cfg.workers * 2)
        coroutineScope {
            launch {
                try {
                    for (ip in ips) for (port in cfg.ports) for (m in cfg.methods) {
                        // ICMP has no port: run it once per IP.
                        if (m == "icmp" && port != cfg.ports.first()) continue
                        queue.send(Work(ip, port, m))
                    }
                } finally {
                    queue.close()
                }
            }
            repeat(cfg.workers) {
                launch(dispatcher) {
                    for (w in queue) {
                        ensureActive()
                        val res = runMethod(w.ip, w.port, w.method, cfg, c, adaptive)
                        if (c.cancelled) break
                        onReport(aggregate(w.ip, w.method, res, w.port))
                    }
                }
            }
        }
    }

    /** Number of work items [scan] will produce, for progress/ETA. */
    fun workCount(ipCount: Int, cfg: ScanConfig): Long {
        val n = cfg.normalized()
        val perIp = n.methods.sumOf { if (it == "icmp") 1L else n.ports.size.toLong() }
        return ipCount * perIp
    }

    suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
