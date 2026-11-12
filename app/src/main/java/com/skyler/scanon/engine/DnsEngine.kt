package com.skyler.scanon.engine

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class DnsAnswer(val type: String, val data: String, val ttl: Long)

data class DnsResponse(
    val id: Int,
    val rcode: Int,
    val rcodeName: String,
    val answers: List<DnsAnswer>,
    val dnssecAd: Boolean,
    val truncated: Boolean,
)

data class DnsResult(
    val ok: Boolean,
    val failure: String = "",
    val detail: String = "",
    val latencyMs: Double? = null,
    val response: DnsResponse? = null,
)

data class DnsReport(
    val server: String,
    val protocol: String,
    val qtype: String = "A",
    val queries: Int = 0,
    val successes: Int = 0,
    val timeouts: Int = 0,
    val avgMs: Double? = null,
    val medianMs: Double? = null,
    val minMs: Double? = null,
    val maxMs: Double? = null,
    val jitterMs: Double? = null,
    val rcodeName: String = "",
    val answers: List<DnsAnswer> = emptyList(),
    val failures: Map<String, Int> = emptyMap(),
    val lastDetail: String = "",
    val poisoned: Boolean = false,
    val dnssecAd: Boolean = false,
    /** NXDOMAIN hijack detected (3.0): the resolver invents address answers for names that cannot exist. */
    val hijack: Boolean = false,
    /** Successful-run latencies behind the stats (kept so merged/refined reports pool real samples, 3.0). */
    val samples: List<Double> = emptyList(),
    /** End-to-end tunnel verification verdict: "" = not run, "pass…" / "fail…" with detail. */
    val e2e: String = "",
    /** Per-protocol verdicts from an Auto tunnel scan, e.g. "DNSTT ok · MasterDNS fail". "" otherwise. */
    val proto: String = "",
) {
    val successRate: Double get() = if (queries > 0) successes.toDouble() / queries else 0.0
    val lossPct: Double get() = if (queries > 0) (1.0 - successRate) * 100 else 100.0
    val working: Boolean get() = successes > 0 && !poisoned
    val addresses: List<String> get() = answers.filter { it.type == "A" || it.type == "AAAA" }.map { it.data }
}

/**
 * DNS over UDP, TCP, TLS (853) and HTTPS (RFC 8484 POST) with a hand-written wire format.
 *
 * Fixes over 2.3: random query ids that are verified together with the QR bit and the question; EDNS0 so large
 * answers are not truncated; automatic TCP retry on a truncated UDP answer; a name decoder that cannot loop;
 * rcode-aware success; DoH with chunked bodies and size limits.
 */
object DnsEngine {

    val QTYPES: LinkedHashMap<String, Int> = linkedMapOf(
        "A" to 1, "NS" to 2, "CNAME" to 5, "SOA" to 6, "NULL" to 10, "PTR" to 12, "MX" to 15, "TXT" to 16,
        "AAAA" to 28, "SRV" to 33, "SVCB" to 64, "HTTPS" to 65, "CAA" to 257,
    )
    private val QTYPE_NAMES: Map<Int, String> = QTYPES.entries.associate { it.value to it.key }
    private val RCODES = mapOf(0 to "NOERROR", 1 to "FORMERR", 2 to "SERVFAIL", 3 to "NXDOMAIN", 4 to "NOTIMP", 5 to "REFUSED")
    private val rnd = SecureRandom()
    private val tlsFactory: SSLSocketFactory by lazy { SSLSocketFactory.getDefault() as SSLSocketFactory }

    private fun nowMs() = System.nanoTime() / 1_000_000.0
    fun newId(): Int = rnd.nextInt(0x10000)

    /** Builds a query. Throws IllegalArgumentException for names that do not fit the DNS label rules. */
    fun buildQuery(domain: String, qtype: String, qid: Int = newId(), edns: Boolean = true): ByteArray {
        val name = domain.trim().trimEnd('.')
        require(name.isNotEmpty()) { "empty domain" }
        val ascii = java.net.IDN.toASCII(name)
        require(ascii.length <= 253) { "domain longer than 253 bytes" }
        val buf = ByteBuffer.allocate(512)
        buf.putShort(qid.toShort())
        buf.putShort(0x0100.toShort())           // RD
        buf.putShort(1)                           // QDCOUNT
        buf.putShort(0); buf.putShort(0)
        buf.putShort(if (edns) 1 else 0)          // ARCOUNT (OPT)
        for (label in ascii.split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            require(b.isNotEmpty()) { "empty label in '$domain'" }
            require(b.size <= 63) { "label longer than 63 bytes" }
            buf.put(b.size.toByte()); buf.put(b)
        }
        buf.put(0)
        buf.putShort((QTYPES[qtype.uppercase()] ?: 1).toShort())
        buf.putShort(1)                           // IN
        if (edns) {
            buf.put(0)                            // root
            buf.putShort(41)                      // OPT
            buf.putShort(1232)                    // UDP payload size (DNS flag day 2020)
            buf.putInt(0)                         // ext rcode, version, flags
            buf.putShort(0)                       // rdlen
        }
        return buf.array().copyOf(buf.position())
    }

    /**
     * Decodes a (possibly compressed) name. Compression pointers must point strictly backwards and at most 64
     * jumps are followed, so a malicious loop terminates. 2.3 spun forever on a pointer loop.
     */
    fun readName(data: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var offset = start
        var next = -1
        var jumps = 0
        var total = 0
        while (true) {
            if (offset >= data.size) throw IllegalArgumentException("name runs past end of message")
            val len = data[offset].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (next < 0) next = offset + 1
                    break
                }
                (len and 0xC0) == 0xC0 -> {
                    if (offset + 1 >= data.size) throw IllegalArgumentException("truncated compression pointer")
                    val ptr = ((len and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    if (next < 0) next = offset + 2
                    if (ptr >= offset) throw IllegalArgumentException("forward compression pointer")
                    if (++jumps > 64) throw IllegalArgumentException("compression loop")
                    offset = ptr
                }
                (len and 0xC0) != 0 -> throw IllegalArgumentException("unsupported label type")
                else -> {
                    val end = offset + 1 + len
                    if (end > data.size) throw IllegalArgumentException("label runs past end of message")
                    labels += String(data, offset + 1, len, Charsets.UTF_8)
                    total += len + 1
                    if (total > 255) throw IllegalArgumentException("name too long")
                    offset = end
                }
            }
        }
        return labels.joinToString(".") to next
    }

    private fun u16(d: ByteArray, o: Int): Int {
        if (o + 1 >= d.size) throw IllegalArgumentException("short read")
        return ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
    }

    private fun u32(d: ByteArray, o: Int): Long {
        if (o + 3 >= d.size) throw IllegalArgumentException("short read")
        return ((d[o].toLong() and 0xFF) shl 24) or ((d[o + 1].toLong() and 0xFF) shl 16) or
            ((d[o + 2].toLong() and 0xFF) shl 8) or (d[o + 3].toLong() and 0xFF)
    }

    private fun decodeRdata(data: ByteArray, type: Int, off: Int, len: Int): String = try {
        when {
            type == 1 && len == 4 -> IpUtils.longToIpv4(u32(data, off))
            type == 28 && len == 16 -> IpUtils.formatIpv6(data.copyOfRange(off, off + 16))
            type == 2 || type == 5 || type == 12 -> readName(data, off).first
            type == 15 -> "${u16(data, off)} ${readName(data, off + 2).first}"
            type == 16 -> {
                val parts = ArrayList<String>()
                var i = off
                val end = off + len
                while (i < end) {
                    val ln = data[i].toInt() and 0xFF
                    parts += String(data, i + 1, minOf(ln, end - i - 1), Charsets.UTF_8)
                    i += ln + 1
                }
                parts.joinToString(" ")
            }
            type == 33 -> "${u16(data, off)} ${u16(data, off + 2)} ${u16(data, off + 4)} ${readName(data, off + 6).first}"
            type == 6 -> {
                val (m, o2) = readName(data, off)
                val (r, o3) = readName(data, o2)
                "$m $r serial=${u32(data, o3)}"
            }
            else -> data.copyOfRange(off, off + len).joinToString("") { String.format(java.util.Locale.US, "%02x", it) }
        }
    } catch (_: Exception) {
        "(unparsable rdata)"
    }

    /** Parses a response. When [expectId] is given the id must match and the QR bit must be set. */
    fun parseResponse(data: ByteArray, expectId: Int? = null): DnsResponse {
        require(data.size >= 12) { "response too short" }
        val id = u16(data, 0)
        val flags = u16(data, 2)
        if (expectId != null) {
            require(id == expectId) { "answer id mismatch (spoofed or stray packet)" }
            require(flags and 0x8000 != 0) { "not a response (QR bit clear)" }
        }
        val qd = u16(data, 4)
        val an = u16(data, 6)
        val rcode = flags and 0x0F
        var off = 12
        repeat(qd) { off = readName(data, off).second + 4 }
        val answers = ArrayList<DnsAnswer>()
        for (i in 0 until an) {
            if (off >= data.size) break
            val nameEnd = readName(data, off).second
            if (nameEnd + 10 > data.size) break
            val type = u16(data, nameEnd)
            val ttl = u32(data, nameEnd + 4)
            val rdlen = u16(data, nameEnd + 8)
            val rdata = nameEnd + 10
            if (rdata + rdlen > data.size) break
            if (type != 41) answers += DnsAnswer(QTYPE_NAMES[type] ?: type.toString(), decodeRdata(data, type, rdata, rdlen), ttl)
            off = rdata + rdlen
        }
        return DnsResponse(id, rcode, RCODES[rcode] ?: rcode.toString(), answers, flags and 0x0020 != 0, flags and 0x0200 != 0)
    }

    private fun failure(e: Exception, proto: String, timeoutMs: Int): DnsResult = when (e) {
        is SocketTimeoutException -> DnsResult(false, "timeout", "no $proto answer within $timeoutMs ms")
        is IllegalArgumentException -> DnsResult(false, "bad_response", "unparsable DNS response: ${e.message}")
        else -> {
            val n = e.javaClass.name
            val f = when {
                n.contains("SSL") || n.contains("Certificate") -> "tls"
                e.message.orEmpty().contains("refused", true) -> "refused"
                e.message.orEmpty().contains("Socket closed", true) -> "cancelled"
                else -> "other"
            }
            DnsResult(false, f, e.message ?: e.javaClass.simpleName)
        }
    }

    fun queryUdp(server: String, domain: String, qtype: String, port: Int = 53, timeoutMs: Int = 3000, c: Canceller = Canceller.none()): DnsResult {
        val id = newId()
        val query = try { buildQuery(domain, qtype, id) } catch (e: IllegalArgumentException) {
            return DnsResult(false, "bad_query", e.message.orEmpty())
        }
        val socket = DatagramSocket()
        c.register(socket)
        val t0 = nowMs()
        try {
            socket.soTimeout = timeoutMs
            val addr = InetAddress.getByName(server)
            socket.connect(addr, port)            // only accept datagrams from the resolver we asked
            socket.send(DatagramPacket(query, query.size))
            val buf = ByteArray(4096)
            val deadline = t0 + timeoutMs
            while (true) {
                val left = (deadline - nowMs()).toInt()
                if (left <= 0) throw SocketTimeoutException()
                socket.soTimeout = left
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val data = buf.copyOf(packet.length)
                // Ignore stray/injected packets with the wrong id instead of trusting the first datagram.
                if (data.size >= 2 && u16(data, 0) != id) continue
                val resp = parseResponse(data, id)
                val latency = nowMs() - t0
                if (resp.truncated) {
                    // Answer did not fit in UDP: retry over TCP like a real stub resolver would.
                    val tcp = queryTcp(server, domain, qtype, port, timeoutMs, c)
                    return if (tcp.ok) tcp.copy(detail = "UDP answer truncated - retried over TCP") else
                        DnsResult(true, detail = "truncated UDP answer (TCP retry failed)", latencyMs = latency, response = resp)
                }
                return DnsResult(true, latencyMs = latency, response = resp)
            }
        } catch (e: Exception) {
            return failure(e, "UDP", timeoutMs)
        } finally {
            c.unregister(socket)
            socket.close()
        }
    }

    private fun readFramed(input: DataInputStream): ByteArray {
        val len = input.readUnsignedShort()
        require(len >= 12) { "framed response too short" }
        val data = ByteArray(len)
        input.readFully(data)
        return data
    }

    private fun framed(query: ByteArray) = ByteArray(query.size + 2).also {
        it[0] = (query.size shr 8).toByte(); it[1] = query.size.toByte()
        System.arraycopy(query, 0, it, 2, query.size)
    }

    fun queryTcp(server: String, domain: String, qtype: String, port: Int = 53, timeoutMs: Int = 3000, c: Canceller = Canceller.none()): DnsResult {
        val id = newId()
        val query = try { buildQuery(domain, qtype, id) } catch (e: IllegalArgumentException) {
            return DnsResult(false, "bad_query", e.message.orEmpty())
        }
        val socket = Socket()
        c.register(socket)
        val t0 = nowMs()
        return try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(InetAddress.getByName(server), port), timeoutMs)
            socket.getOutputStream().apply { write(framed(query)); flush() }
            val data = readFramed(DataInputStream(socket.getInputStream()))
            DnsResult(true, latencyMs = nowMs() - t0, response = parseResponse(data, id))
        } catch (e: Exception) {
            failure(e, "TCP", timeoutMs)
        } finally {
            c.unregister(socket)
            Canceller.closeQuietly(socket)
        }
    }

    private fun tlsOver(raw: Socket, sni: String, port: Int): SSLSocket {
        val tls = tlsFactory.createSocket(raw, sni, port, true) as SSLSocket
        val p = tls.sslParameters
        p.endpointIdentificationAlgorithm = "HTTPS"
        if (!IpUtils.isIp(sni)) p.serverNames = listOf(SNIHostName(sni))
        tls.sslParameters = p
        tls.startHandshake()
        return tls
    }

    fun queryDot(server: String, domain: String, qtype: String, authName: String = "", port: Int = 853, timeoutMs: Int = 4000, c: Canceller = Canceller.none()): DnsResult {
        val id = newId()
        val query = try { buildQuery(domain, qtype, id) } catch (e: IllegalArgumentException) {
            return DnsResult(false, "bad_query", e.message.orEmpty())
        }
        val raw = Socket()
        c.register(raw)
        val t0 = nowMs()
        return try {
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress(InetAddress.getByName(server), port), timeoutMs)
            val tls = tlsOver(raw, authName.ifEmpty { server }, port)
            c.register(tls)
            try {
                tls.outputStream.apply { write(framed(query)); flush() }
                val data = readFramed(DataInputStream(tls.inputStream))
                DnsResult(true, latencyMs = nowMs() - t0, response = parseResponse(data, id))
            } finally {
                c.unregister(tls); Canceller.closeQuietly(tls)
            }
        } catch (e: Exception) {
            failure(e, "DoT", timeoutMs)
        } finally {
            c.unregister(raw)
            Canceller.closeQuietly(raw)
        }
    }

    /** Reads an HTTP/1.1 body honouring Content-Length or chunked transfer encoding, capped at [max] bytes. */
    fun readHttpBody(input: InputStream, headers: String, preRead: ByteArray, max: Int = 65_535): ByteArray {
        val out = ByteArrayOutputStream()
        val stream = java.io.SequenceInputStream(preRead.inputStream(), input)
        val chunked = Regex("transfer-encoding:\\s*chunked", RegexOption.IGNORE_CASE).containsMatchIn(headers)
        if (chunked) {
            while (true) {
                val line = readLine(stream) ?: break
                val size = line.substringBefore(';').trim().toIntOrNull(16) ?: throw IllegalArgumentException("bad chunk size")
                if (size == 0) break
                require(out.size() + size <= max) { "DoH body too large" }
                val chunk = ByteArray(size)
                DataInputStream(stream).readFully(chunk)
                out.write(chunk)
                readLine(stream) // CRLF after chunk
            }
            return out.toByteArray()
        }
        val clen = Regex("content-length:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(headers)?.groupValues?.get(1)?.toIntOrNull()
        if (clen != null) {
            require(clen <= max) { "DoH body too large ($clen bytes)" }
            val body = ByteArray(clen)
            var read = 0
            while (read < clen) {
                val n = stream.read(body, read, clen - read)
                if (n < 0) break
                read += n
            }
            return body.copyOf(read)
        }
        // Connection: close without a length: read to EOF, bounded.
        val buf = ByteArray(4096)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            require(out.size() <= max) { "DoH body too large" }
        }
        return out.toByteArray()
    }

    private fun readLine(s: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = s.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 1024) throw IllegalArgumentException("header line too long")
        }
    }

    fun queryDoh(serverIp: String, hostname: String, domain: String, qtype: String, path: String = "/dns-query", port: Int = 443, timeoutMs: Int = 5000, c: Canceller = Canceller.none()): DnsResult {
        // RFC 8484 recommends id 0 for HTTP caching; we keep a random one and verify it like the other transports.
        val id = newId()
        val body = try { buildQuery(domain, qtype, id) } catch (e: IllegalArgumentException) {
            return DnsResult(false, "bad_query", e.message.orEmpty())
        }
        val raw = Socket()
        c.register(raw)
        val t0 = nowMs()
        return try {
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress(InetAddress.getByName(serverIp), port), timeoutMs)
            val tls = tlsOver(raw, hostname, port)
            c.register(tls)
            try {
                val head = "POST $path HTTP/1.1\r\nHost: $hostname\r\nUser-Agent: ScanON-Android/2.4\r\n" +
                    "Content-Type: application/dns-message\r\nAccept: application/dns-message\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                tls.outputStream.apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
                val input = tls.inputStream
                val buf = ByteArray(16 * 1024)
                var len = 0
                var end = -1
                while (len < buf.size) {
                    val n = input.read(buf, len, buf.size - len)
                    if (n < 0) break
                    val from = (len - 3).coerceAtLeast(0)
                    len += n
                    end = NetEngine.indexOfCrlfCrlf(buf, from, len)
                    if (end >= 0) break
                }
                if (end < 0) return DnsResult(false, "other", "DoH: no HTTP response head")
                val headers = String(buf, 0, end, Charsets.ISO_8859_1)
                val status = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})").find(headers)?.groupValues?.get(1)?.toIntOrNull()
                if (status != 200) return DnsResult(false, "other", "DoH HTTP status ${status ?: "?"}")
                val pre = buf.copyOfRange(end + 4, len)
                val payload = readHttpBody(input, headers, pre)
                DnsResult(true, latencyMs = nowMs() - t0, response = parseResponse(payload, id))
            } finally {
                c.unregister(tls); Canceller.closeQuietly(tls)
            }
        } catch (e: Exception) {
            failure(e, "DoH", timeoutMs)
        } finally {
            c.unregister(raw)
            Canceller.closeQuietly(raw)
        }
    }

    /** A response is a *working* resolver answer only when the rcode says the resolver did its job. */
    fun isUsefulRcode(rcode: Int) = rcode == 0 || rcode == 3

    private fun randomLabel(len: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..len).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    /** A resolver hijacks NXDOMAINs when it invents address answers for a name that cannot exist (3.0). */
    fun isHijackResponse(r: DnsResponse?): Boolean =
        r != null && r.rcode == 0 && r.answers.any { it.type == "A" || it.type == "AAAA" }

    /**
     * Probes one resolver [attempts] times and builds its report.
     *
     * Accuracy contract (3.0): with [cacheBust] on (the ranking probes), attempt 1
     * asks the plain name (it also fills the answer list) and every later attempt
     * asks a FRESH random subname, so each timed run is a genuine recursion.
     * Repeating one name — the pre-3.0 behaviour — ranked resolvers by cache
     * speed and flattered almost every number. A separate honesty probe then
     * asks for a name that cannot exist: inventing an address for it sets
     * [DnsReport.hijack]. Answers of 0.0.0.0 / :: (filter sinks) count as
     * poisoned. Callers that need the domain's own answers every attempt (the
     * Domain scanner, tunnel probes whose names are already random) pass
     * cacheBust = false and skip both extras.
     */
    fun probeResolver(
        server: String, domain: String, qtype: String = "A", protocol: String = "udp", attempts: Int = 3,
        timeoutMs: Int = 3000, dohHostname: String = "cloudflare-dns.com", dotAuthName: String = "",
        c: Canceller = Canceller.none(), port: Int = 53, cacheBust: Boolean = true,
    ): DnsReport {
        fun runOnce(name: String, type: String): DnsResult = when (protocol) {
            "doh" -> queryDoh(server, dohHostname, name, type, timeoutMs = timeoutMs, c = c)
            "dot" -> queryDot(server, name, type, dotAuthName, timeoutMs = timeoutMs, c = c)
            "tcp" -> queryTcp(server, name, type, port = port, timeoutMs = timeoutMs, c = c)
            else -> queryUdp(server, name, type, port = port, timeoutMs = timeoutMs, c = c)
        }
        val latencies = ArrayList<Double>()
        var queries = 0; var successes = 0; var timeouts = 0
        var rcodeName = ""; var answers: List<DnsAnswer> = emptyList(); var lastDetail = ""; var ad = false
        var sink = false
        val failures = LinkedHashMap<String, Int>()
        repeat(attempts.coerceIn(1, 20)) { i ->
            if (c.cancelled) return@repeat
            val name = if (cacheBust && i > 0) "${randomLabel(12)}.$domain" else domain
            val r = runOnce(name, qtype)
            queries++
            val resp = r.response
            if (r.ok && resp != null && isUsefulRcode(resp.rcode)) {
                successes++
                r.latencyMs?.let { latencies += it }
                rcodeName = resp.rcodeName
                ad = ad || resp.dnssecAd
                if (resp.answers.isNotEmpty()) answers = resp.answers
                if (resp.answers.any { (it.type == "A" && it.data == "0.0.0.0") || (it.type == "AAAA" && it.data == "::") }) sink = true
                if (r.detail.isNotEmpty()) lastDetail = r.detail
            } else {
                val f = if (r.ok && resp != null) "rcode_${resp.rcodeName}" else r.failure
                if (r.ok && resp != null) { rcodeName = resp.rcodeName; lastDetail = "resolver answered ${resp.rcodeName}" }
                else lastDetail = r.detail
                if (f == "timeout") timeouts++
                failures[f] = (failures[f] ?: 0) + 1
            }
        }
        var hijack = false
        if (cacheBust && !c.cancelled && queries > 0) {
            val h = runOnce("${randomLabel(10)}.example.com", "A")
            if (h.ok) hijack = isHijackResponse(h.response)
        }
        val stat = Stats.dropWarmup(latencies)
        val addr = answers.filter { it.type == "A" || it.type == "AAAA" }
        return DnsReport(
            server = server, protocol = protocol, qtype = qtype, queries = queries, successes = successes, timeouts = timeouts,
            avgMs = stat.takeIf { it.isNotEmpty() }?.average(), medianMs = Stats.median(stat),
            minMs = stat.minOrNull(), maxMs = stat.maxOrNull(), jitterMs = Stats.mad(stat),
            rcodeName = rcodeName, answers = answers, failures = failures, lastDetail = lastDetail,
            poisoned = sink || (addr.isNotEmpty() && addr.all { IpUtils.isBogon(it.data) }), dnssecAd = ad,
            hijack = hijack, samples = stat,
        )
    }

    /**
     * End-to-end tunnel verification (2.8, SlipNet's "HTTP/SSH verification" idea at the
     * DNS layer): proves a resolver can carry a real tunnel session, not just answer
     * one small query. Three checks, all through this resolver only:
     *
     * 1. a query with a long (48-char) random label under the tunnel domain — tunnel
     *    packets *are* long labels; resolvers/proxies that reject, truncate or mangle
     *    long names fail here;
     * 2. a TXT query for the tunnel domain, where a truncated UDP answer must be
     *    recovered over TCP (queryUdp already retries), and
     * 3. an A query for a real outside name (cloudflare.com) that must return a real,
     *    non-bogon address — the resolver must give real internet answers too.
     *
     * Returns (passed, one-line detail). A full protocol handshake (DNSTT key exchange,
     * SlipNet's own framing) needs each app's server keys and stays in those apps.
     */
    fun verifyE2e(server: String, domain: String, port: Int = 53, timeoutMs: Int = 4000, c: Canceller = Canceller.none()): Pair<Boolean, String> {
        val t0 = nowMs()
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val label = (1..48).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        val long = queryUdp(server, "$label.$domain", "TXT", port, timeoutMs, c)
        val longResp = long.response
        if (!long.ok || longResp == null || !isUsefulRcode(longResp.rcode))
            return false to "fail: long-label query ${long.failure.ifEmpty { long.detail }}"
        if (longResp.answers.isNotEmpty() && longResp.answers.all { IpUtils.isBogon(it.data) })
            return false to "fail: long-label answer is a bogon (intercepted)"
        if (c.cancelled) return false to "fail: cancelled"
        val txt = queryUdp(server, domain, "TXT", port, timeoutMs, c)
        val txtResp = txt.response
        if (!txt.ok || txtResp == null || !isUsefulRcode(txtResp.rcode))
            return false to "fail: TXT query ${txt.failure.ifEmpty { txt.detail }}"
        if (c.cancelled) return false to "fail: cancelled"
        val ext = queryUdp(server, "cloudflare.com", "A", port, timeoutMs, c)
        val extResp = ext.response
        if (!ext.ok || extResp == null) return false to "fail: outside lookup ${ext.failure.ifEmpty { ext.detail }}"
        val aAnswers = extResp.answers.filter { it.type == "A" }
        if (aAnswers.isEmpty()) return false to "fail: no A answer for cloudflare.com"
        if (aAnswers.all { IpUtils.isBogon(it.data) }) return false to "fail: outside answer is a bogon (poisoned)"
        return true to "pass in ${Fmt.ms(nowMs() - t0)} ms"
    }
}

/** Resolver catalogue and the "Auto" protocol / record-type strategies used by the DNS and Domain scanners. */
object DnsCatalog {
    val KNOWN_RESOLVERS = listOf(
        "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "94.140.14.14", "94.140.15.15",
        "208.67.222.222", "76.76.2.0", "185.228.168.9", "178.22.122.100", "185.51.200.2",
        "185.51.200.172", "185.231.182.126", "10.202.10.202", "10.202.10.102",
    )
    val IPV6_RESOLVERS = listOf("2606:4700:4700::1111", "2001:4860:4860::8888", "2620:fe::fe")

    /** Resolvers with a published DoH/DoT name (used as SNI and for certificate verification). */
    val DOH_HOSTS = mapOf(
        "1.1.1.1" to "cloudflare-dns.com", "1.0.0.1" to "cloudflare-dns.com",
        "2606:4700:4700::1111" to "cloudflare-dns.com",
        "8.8.8.8" to "dns.google", "8.8.4.4" to "dns.google", "2001:4860:4860::8888" to "dns.google",
        "9.9.9.9" to "dns.quad9.net", "2620:fe::fe" to "dns.quad9.net",
        "94.140.14.14" to "dns.adguard-dns.com", "94.140.15.15" to "dns.adguard-dns.com",
        "208.67.222.222" to "doh.opendns.com", "76.76.2.0" to "freedns.controld.com",
    )

    fun probeProto(
        server: String, domain: String, qtype: String, proto: String, attempts: Int, timeoutMs: Int,
        customDohHost: String, c: Canceller, port: Int = 53, cacheBust: Boolean = true,
    ): DnsReport {
        val doh = DOH_HOSTS[server] ?: customDohHost.trim().ifEmpty { "cloudflare-dns.com" }
        val dot = DOH_HOSTS[server].orEmpty()
        fun with(p: String) = DnsEngine.probeResolver(server, domain, qtype, p, attempts, timeoutMs, doh, dot, c, port, cacheBust)
        if (proto.equals("both", true)) {
            // SlipNet's "Both": the resolver is measured on UDP and on TCP and the two are
            // merged, so the counts show exactly which transport carries the tunnel.
            val u = with("udp"); if (c.cancelled) return u
            val t = with("tcp")
            val failures = LinkedHashMap<String, Int>()
            listOf(u, t).forEach { p -> p.failures.forEach { (k, v) -> failures[k] = (failures[k] ?: 0) + v } }
            // Pool the real samples (3.0): the old code took a median of two medians.
            val pool = u.samples + t.samples
            return DnsReport(
                server = server, protocol = "udp+tcp", qtype = qtype,
                queries = u.queries + t.queries, successes = u.successes + t.successes,
                timeouts = u.timeouts + t.timeouts,
                avgMs = pool.takeIf { it.isNotEmpty() }?.average(),
                medianMs = Stats.median(pool),
                minMs = pool.minOrNull(), maxMs = pool.maxOrNull(),
                jitterMs = Stats.mad(pool),
                rcodeName = u.rcodeName.ifEmpty { t.rcodeName },
                answers = if (u.answers.isNotEmpty()) u.answers else t.answers,
                failures = failures,
                lastDetail = "UDP ${u.successes}/${u.queries} · TCP ${t.successes}/${t.queries}",
                poisoned = u.poisoned || t.poisoned, dnssecAd = u.dnssecAd || t.dnssecAd,
                hijack = u.hijack || t.hijack, samples = pool,
            )
        }
        if (!proto.equals("auto", true)) return with(proto.lowercase())
        // Auto: UDP, then TCP when port 53 is intercepted/blocked, then DoT, then DoH for resolvers that publish one.
        val udp = with("udp"); if (udp.successes > 0 || c.cancelled) return udp
        val tcp = with("tcp"); if (tcp.successes > 0 || c.cancelled) return tcp
        val dotR = with("dot"); if (dotR.successes > 0 || c.cancelled) return dotR
        if (DOH_HOSTS.containsKey(server) || customDohHost.isNotBlank()) {
            val dohR = with("doh"); if (dohR.successes > 0) return dohR
        }
        return dotR
    }

    fun probeAuto(
        server: String, domain: String, qtype: String, proto: String, attempts: Int, timeoutMs: Int,
        customDohHost: String, c: Canceller, port: Int = 53, cacheBust: Boolean = true,
    ): DnsReport {
        if (!qtype.equals("auto", true)) return probeProto(server, domain, qtype, proto, attempts, timeoutMs, customDohHost, c, port, cacheBust)
        val a = probeProto(server, domain, "A", proto, attempts, timeoutMs, customDohHost, c, port, cacheBust)
        if (a.answers.isNotEmpty() || a.successes == 0 || c.cancelled) return a
        val aaaa = probeProto(server, domain, "AAAA", proto, attempts, timeoutMs, customDohHost, c, port, cacheBust)
        return if (aaaa.answers.isNotEmpty()) aaaa else a
    }

    /**
     * Merges two reports for the same resolver (3.0 refine pass): counts and
     * failures add up, and the latency stats are recomputed from the pooled
     * raw samples — never from averages of averages.
     */
    fun mergeReports(a: DnsReport, b: DnsReport): DnsReport {
        val pool = a.samples + b.samples
        val failures = LinkedHashMap<String, Int>()
        listOf(a, b).forEach { p -> p.failures.forEach { (k, v) -> failures[k] = (failures[k] ?: 0) + v } }
        return a.copy(
            queries = a.queries + b.queries, successes = a.successes + b.successes, timeouts = a.timeouts + b.timeouts,
            avgMs = pool.takeIf { it.isNotEmpty() }?.average(), medianMs = Stats.median(pool),
            minMs = pool.minOrNull(), maxMs = pool.maxOrNull(), jitterMs = Stats.mad(pool),
            answers = if (a.answers.isNotEmpty()) a.answers else b.answers,
            failures = failures, lastDetail = b.lastDetail.ifEmpty { a.lastDetail },
            poisoned = a.poisoned || b.poisoned, hijack = a.hijack || b.hijack,
            dnssecAd = a.dnssecAd || b.dnssecAd, samples = pool,
            e2e = a.e2e.ifEmpty { b.e2e }, proto = a.proto.ifEmpty { b.proto },
        )
    }

    /**
     * Tunnel protocol probe (2.8, redesigned 2.8.2): asks one resolver for
     * exactly what the real tunnel clients ask it for, against the user's own
     * [domain] (their tunnel zone):
     *
     * 1. an A and a TXT query for the domain itself (delegation sanity; the
     *    TXT answer is also TheFeed's / DNSTT's downstream channel), and
     * 2. for every carrier record type of [mode], a query for a fresh random
     *    subdomain of the domain — the shape of every real tunnel packet.
     *    Only the tunnel's own authoritative server can answer that name, so
     *    an answer (or a clean NXDOMAIN relayed from it) proves the resolver
     *    really recursed to the tunnel server instead of intercepting, while
     *    a bogon/block-page answer marks the resolver poisoned.
     * 3. Modes with [TunnelMode.longName] also probe a ~155-char multi-label
     *    name (real tunnel packets are long; some resolvers drop them).
     *
     * Per-type attempts are capped at 3 when a mode has many types (Auto has
     * ten) so one resolver stays affordable. Parts are merged into one
     * report: counts summed (the headline success rate is the client's real
     * one), median = median of part medians, [DnsReport.lastDetail] keeps the
     * per-type breakdown ("TXT 3/3 · NULL 0/3 · …"), and in Auto mode
     * [DnsReport.proto] carries the per-protocol verdicts.
     */
    fun probeTunnel(
        server: String, domain: String, mode: TunnelMode, proto: String, attempts: Int, timeoutMs: Int,
        customDohHost: String, c: Canceller, randomLabel: String, port: Int = 53,
    ): DnsReport {
        val typeAttempts = if (mode.types.size > 4) attempts.coerceAtMost(3) else attempts
        val parts = ArrayList<DnsReport>()
        val a = probeAuto(server, domain, "A", proto, attempts, timeoutMs, customDohHost, c, port, cacheBust = false); parts += a
        val txt = probeAuto(server, domain, "TXT", proto, attempts, timeoutMs, customDohHost, c, port, cacheBust = false); parts += txt
        val subRates = LinkedHashMap<String, Double>()
        mode.types.forEachIndexed { i, t ->
            if (c.cancelled) return@forEachIndexed
            val r = probeAuto(server, "$randomLabel$i.$domain", t, proto, typeAttempts, timeoutMs, customDohHost, c, port, cacheBust = false)
            parts += r
            subRates[t] = if (r.queries > 0) r.successes.toDouble() / r.queries else 0.0
        }
        var longRate: Double? = null
        if (mode.longName && !c.cancelled) {
            val seg = "a".repeat(24)
            val longName = "$seg$randomLabel.$seg.$seg.$domain"
            val r = probeAuto(server, longName, "TXT", proto, typeAttempts.coerceAtMost(3), timeoutMs, customDohHost, c, port, cacheBust = false)
            parts += r
            longRate = if (r.queries > 0) r.successes.toDouble() / r.queries else 0.0
        }
        val failures = LinkedHashMap<String, Int>()
        parts.forEach { p -> p.failures.forEach { (k, v) -> failures[k] = (failures[k] ?: 0) + v } }
        val pool = parts.flatMap { it.samples }
        val detail = StringBuilder()
        detail.append("domain A ${a.successes}/${a.queries} · domain TXT ${txt.successes}/${txt.queries}")
        subRates.forEach { (t, _) ->
            val r = parts[2 + mode.types.indexOf(t)]
            detail.append(" · $t ${r.successes}/${r.queries}")
        }
        if (longRate != null) {
            val r = parts.last()
            detail.append(" · long ${r.successes}/${r.queries}")
        }
        // Per-protocol verdicts (Auto mode): a family passes when its carriers
        // mostly get through. Half is the bar — below that the tunnel is unusable.
        var protoVerdicts = ""
        if (mode.id == "auto") {
            fun ok(vararg ts: String) = ts.any { (subRates[it] ?: 0.0) >= 0.5 }
            val dnstt = ok("TXT")
            val vay = dnstt || ok("CNAME", "A", "AAAA", "MX", "NS", "SRV")
            val master = ok("TXT", "NULL", "HTTPS", "SVCB")
            val soar = ok("TXT") || ok("AAAA")
            fun v(b: Boolean) = if (b) "ok" else "fail"
            protoVerdicts = "DNSTT ${v(dnstt)} · VayDNS ${v(vay)} · MasterDNS ${v(master)} · Soar ${v(soar)} · TheFeed ${v(dnstt)}"
        }
        return DnsReport(
            server = server, protocol = a.protocol, qtype = "Tunnel",
            queries = parts.sumOf { it.queries }, successes = parts.sumOf { it.successes },
            timeouts = parts.sumOf { it.timeouts },
            avgMs = pool.takeIf { it.isNotEmpty() }?.average(),
            medianMs = Stats.median(pool),
            minMs = pool.minOrNull(), maxMs = pool.maxOrNull(),
            jitterMs = Stats.mad(pool), samples = pool,
            rcodeName = a.rcodeName, answers = a.answers, failures = failures,
            lastDetail = detail.toString(),
            poisoned = parts.any { it.poisoned }, dnssecAd = parts.any { it.dnssecAd },
            proto = protoVerdicts,
        )
    }
}
