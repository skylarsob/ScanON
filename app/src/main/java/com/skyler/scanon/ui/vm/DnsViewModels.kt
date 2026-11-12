package com.skyler.scanon.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.scanon.R
import com.skyler.scanon.ScanonApplication
import com.skyler.scanon.data.DnsForm
import com.skyler.scanon.engine.Canceller
import com.skyler.scanon.engine.DiagEngine
import com.skyler.scanon.engine.DnsCatalog
import com.skyler.scanon.engine.DnsEngine
import com.skyler.scanon.engine.DnsReport
import com.skyler.scanon.engine.ExportFormat
import com.skyler.scanon.engine.Exporters
import com.skyler.scanon.engine.IpUtils
import com.skyler.scanon.engine.NetEngine
import com.skyler.scanon.engine.ScanConfig
import com.skyler.scanon.engine.TargetReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

data class DnsFormState(
    val servers: String, val domain: String, val qtype: String, val protocol: String,
    val attempts: String, val timeoutS: String, val dohHost: String,
    val workers: String = "24", val tunnel: String = "",
    val port: String = "53", val shuffle: Boolean = true, val nearby: Boolean = false,
    val e2e: Boolean = false, val e2eTimeoutMs: String = "4000", val e2eConcurrency: String = "5",
    val ranges: String = "",
) {
    val serverList: List<String>
        get() = servers.split(Regex("[\\s,;]+")).mapNotNull { IpUtils.canonical(it.trim()) }.distinct()
    val invalidServers: List<String>
        get() = servers.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() && IpUtils.canonical(it) == null }
    val domainError get() = domain.isNotBlank() && IpUtils.normalizeHost(domain) == null
    val attemptsError get() = attempts.toIntOrNull()?.let { it !in 1..20 } ?: true
    val timeoutError get() = timeoutS.toDoubleOrNull()?.let { it < 0.3 || it > 30 } ?: true
    val workersError get() = workers.toIntOrNull()?.let { it !in 1..128 } ?: true
    val portError get() = port.toIntOrNull()?.let { it !in 1..65535 } ?: true
    val e2eTimeoutError get() = e2eTimeoutMs.toIntOrNull()?.let { it !in 500..30_000 } ?: true
    val e2eConcError get() = e2eConcurrency.toIntOrNull()?.let { it !in 1..10 } ?: true
    val valid get() = (serverList.isNotEmpty() || ranges.isNotBlank()) && !domainError && !attemptsError && !timeoutError && !workersError && !portError && !e2eTimeoutError && !e2eConcError
}

class DnsScanViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ScanonApplication).container
    private val res get() = c.localizedResources()

    private val _form = MutableStateFlow(c.settings.dnsForm().let {
        DnsFormState(it.servers, it.domain, it.qtype, it.protocol, it.attempts, it.timeout, it.dohHost, it.workers, it.tunnel,
            it.port, it.shuffle, it.nearby, it.e2e, it.e2eTimeoutMs, it.e2eConcurrency, it.ranges)
    })
    val form: StateFlow<DnsFormState> = _form.asStateFlow()
    // Scan state lives in the process-wide session (2.8) so the scan survives the UI.
    private val session = c.dnsSession
    val results: StateFlow<List<DnsReport>> = session.results
    val running: StateFlow<Boolean> = session.running
    val progress: StateFlow<Progress> = session.progress
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun update(f: (DnsFormState) -> DnsFormState) {
        val s = f(_form.value)
        _form.value = s
        c.settings.saveDnsForm(DnsForm(s.servers, s.domain, s.qtype, s.protocol, s.attempts, s.timeoutS, s.dohHost, s.workers, s.tunnel,
            s.port, s.shuffle, s.nearby, s.e2e, s.e2eTimeoutMs, s.e2eConcurrency, s.ranges))
    }

    fun fillKnown(includeV6: Boolean = false) =
        update { it.copy(servers = (DnsCatalog.KNOWN_RESOLVERS + if (includeV6) DnsCatalog.IPV6_RESOLVERS else emptyList()).joinToString("\n")) }

    /** Speed presets for the DNS scanner (2.8): the same idea as the IP scanner's presets. */
    fun applyDnsPreset(name: String) {
        update {
            when (name) {
                "fast" -> it.copy(attempts = "3", timeoutS = "2", workers = "32")
                "thorough" -> it.copy(attempts = "7", timeoutS = "5", workers = "16")
                else -> it.copy(attempts = "5", timeoutS = "3", workers = "24")
            }
        }
        // 2.8.3: confirm the tap out loud. Until now the chips gave no feedback at
        // all (never showed as selected, and the fields they change sit further
        // down the card), so a working preset looked exactly like a dead one.
        val f = _form.value
        val label = res.getString(when (name) {
            "fast" -> R.string.dns_preset_fast
            "thorough" -> R.string.dns_preset_thorough
            else -> R.string.preset_balanced
        })
        _events.tryEmit(res.getString(R.string.dns_preset_applied, label, f.attempts, f.timeoutS, f.workers))
    }

    /** How many Iranian datacenter resolvers are bundled (0 until loaded). */
    val iranTotal = MutableStateFlow(0)

    /** How many /24 ranges those resolvers live in (3.0; 0 until loaded). */
    val iranRangeTotal = MutableStateFlow(0)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            iranTotal.value = com.skyler.scanon.engine.IrResolvers.count(getApplication())
            iranRangeTotal.value = com.skyler.scanon.engine.IrResolvers.rangeCount(getApplication())
        }
    }

    /**
     * Loads [n] of the Iranian resolver RANGES into the custom-range field
     * (3.0, Skylar's request): the scan then expands them and tries every
     * address as a resolver. Appends to whatever ranges are already there,
     * without duplicates.
     */
    fun loadIranRanges(n: Int) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { com.skyler.scanon.engine.IrResolvers.sampleRanges(getApplication(), n) }
            if (list.isEmpty()) { _events.tryEmit(res.getString(R.string.err_no_targets)); return@launch }
            update { f ->
                val existing = f.ranges.lines().map { it.trim() }.filter { it.isNotEmpty() }
                f.copy(ranges = (existing + list).distinct().joinToString("\n"))
            }
            _events.tryEmit(res.getString(R.string.iran_ranges_loaded, list.size))
        }
    }

    /**
     * Loads Iranian datacenter resolvers (2.8.3, the net2share/ir-resolvers
     * list) into the server list: a random sample of [n], or the whole list
     * when [n] is large. Replaces the current list, like SlipNet's Import.
     */
    fun loadIranSample(n: Int) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { com.skyler.scanon.engine.IrResolvers.sample(getApplication(), n) }
            if (list.isEmpty()) { _events.tryEmit(res.getString(R.string.err_no_targets)); return@launch }
            update { it.copy(servers = list.joinToString("\n")) }
            _events.tryEmit(res.getString(R.string.iran_loaded, list.size))
        }
    }

    /**
     * Tunnel protocol mode (2.8, redesigned 2.8.2): a mode ONLY selects which
     * protocol's carrier record types get probed. It never touches the server
     * list, the test domain or any other setting — the domain is the user's
     * own tunnel zone, typed by them, never filled in by the app.
     */
    fun applyTunnelMode(id: String) {
        if (com.skyler.scanon.engine.TunnelModes.of(id) == null) return
        update { it.copy(tunnel = id) }
    }

    fun clearTunnel() = update { it.copy(tunnel = "") }

    fun tunnelMode() = com.skyler.scanon.engine.TunnelModes.of(_form.value.tunnel)

    /** True while the "no test domain" warning dialog should be showing. */
    val domainWarn = MutableStateFlow(false)

    fun confirmDomainWarn() { domainWarn.value = false; start(force = true) }
    fun dismissDomainWarn() { domainWarn.value = false }

    /** Working resolvers ranked for tunnel use (best first). */
    fun tunnelRanked(): List<DnsReport> = session.results.value.filter { it.working }
        .sortedWith(compareByDescending<DnsReport> { com.skyler.scanon.engine.tunnelScore(it) }
            .thenBy { it.medianMs ?: Double.MAX_VALUE })

    fun tunnelReportText(): String {
        val m = tunnelMode() ?: return ""
        return com.skyler.scanon.engine.tunnelReport(m, _form.value.domain, session.results.value)
    }

    fun start(force: Boolean = false) {
        if (session.running.value) return
        val f = _form.value
        if (!f.valid) { _events.tryEmit(res.getString(R.string.err_fix_fields)); return }
        // A tunnel scan without the user's own tunnel domain proves nothing —
        // the whole test is whether resolvers reach *that* zone's server.
        // Warn first (2.8.2) instead of silently scanning a placeholder.
        if (!force && tunnelMode() != null &&
            (f.domain.isBlank() || f.domain.trim().equals("example.com", true))) {
            domainWarn.value = true; return
        }
        if (!force && f.e2e && f.domain.isBlank()) { domainWarn.value = true; return }
        val domain = IpUtils.normalizeHost(f.domain) ?: "example.com"
        val attempts = f.attempts.toIntOrNull() ?: 5
        val timeoutMs = ((f.timeoutS.toDoubleOrNull() ?: 3.0) * 1000).toInt()
        val workers = (f.workers.toIntOrNull() ?: 24).coerceIn(1, 128)
        val port = f.port.toIntOrNull() ?: 53
        val profile = tunnelMode()
        // One fresh random label per tunnel scan: only the tunnel's authoritative
        // server can answer it, which is what makes the tunnel test honest.
        val randomLabel = "s" + java.util.Random().nextInt(0xFFFFFF).toString(16).padStart(6, '0')
        val cancel = Canceller().also { session.canceller = it }
        session.running.value = true
        session.results.value = emptyList()
        val title = res.getString(R.string.nav_dns)
        session.job = c.appScope.launch {
            var began = false
            try {
                // Resolver list = typed servers + the custom ranges expanded (SlipNet's
                // "Custom range" scan: every IP in the range is tried as a resolver).
                session.progress.value = Progress(phase = res.getString(R.string.phase_expanding), startedAt = System.currentTimeMillis())
                var servers = f.serverList
                if (f.ranges.isNotBlank()) {
                    val expanded = withContext(Dispatchers.Default) {
                        com.skyler.scanon.engine.TargetParser.parse(f.ranges, 100_000, true).ips
                    }
                    servers = (servers + expanded).distinct()
                }
                if (servers.isEmpty()) { _events.emit(res.getString(R.string.err_no_targets)); return@launch }
                if (f.shuffle) servers = servers.shuffled()
                session.progress.value = Progress(0, servers.size.toLong(), System.currentTimeMillis(), res.getString(R.string.scanning))
                c.work.begin(title, servers.size.toLong()); began = true

                suspend fun probePhase(list: List<String>, att: Int, publish: Boolean = true): List<DnsReport> {
                    val sem = Semaphore(workers)
                    val queue = ConcurrentLinkedQueue<DnsReport>()
                    return withContext(Dispatchers.IO) {
                        list.map { srv ->
                            async {
                                sem.withPermit {
                                    val r = if (profile != null)
                                        DnsCatalog.probeTunnel(srv, domain, profile, f.protocol, att, timeoutMs, f.dohHost, cancel, randomLabel, port)
                                    else
                                        DnsCatalog.probeAuto(srv, domain, f.qtype, f.protocol, att, timeoutMs, f.dohHost, cancel, port)
                                    queue.add(r)
                                    if (publish) withContext(Dispatchers.Main) {
                                        val batch = generateSequence { queue.poll() }.toList()
                                        if (batch.isNotEmpty()) {
                                            session.results.update { cur -> cur + batch }
                                            session.progress.update { it.copy(done = it.done + batch.size) }
                                            c.work.progress(title, session.progress.value.done, session.progress.value.total)
                                        }
                                    }
                                    r
                                }
                            }
                        }.awaitAll()
                    }
                }

                var reports = probePhase(servers, attempts)

                // Nearby /24 expansion (SlipNet parity): a working resolver usually means
                // its whole subnet is full of sibling resolvers.
                if (f.nearby && !cancel.cancelled) {
                    val tested = reports.map { it.server }.toHashSet()
                    val subnets = reports.filter { it.working }.mapNotNull { r ->
                        com.skyler.scanon.engine.IpUtils.parseIpv4(r.server)?.let { it and 0xFFFFFF00L }
                    }.distinct().take(16)
                    val extra = subnets.flatMap { base -> (0..255).map { com.skyler.scanon.engine.IpUtils.longToIpv4(base + it) } }
                        .filter { it !in tested }.distinct()
                    if (extra.isNotEmpty()) {
                        session.progress.update { it.copy(total = it.total + extra.size, phase = res.getString(R.string.phase_nearby)) }
                        reports = reports + probePhase(extra, 2)
                    }
                }

                // E2E tunnel verification for the working set, bounded concurrency.
                if (f.e2e && !cancel.cancelled) {
                    val working = reports.filter { it.working }
                    if (working.isNotEmpty()) {
                        val e2eTimeout = f.e2eTimeoutMs.toIntOrNull() ?: 4000
                        val e2eConc = (f.e2eConcurrency.toIntOrNull() ?: 5).coerceIn(1, 10)
                        session.progress.value = Progress(0, working.size.toLong(), System.currentTimeMillis(), res.getString(R.string.phase_e2e))
                        val sem = Semaphore(e2eConc)
                        val verdicts = withContext(Dispatchers.IO) {
                            working.map { r ->
                                async {
                                    sem.withPermit {
                                        val v = com.skyler.scanon.engine.DnsEngine.verifyE2e(r.server, domain, port, e2eTimeout, cancel).second
                                        session.progress.update { it.copy(done = it.done + 1) }
                                        c.work.progress(title, session.progress.value.done, working.size.toLong())
                                        r.server to v
                                    }
                                }
                            }.awaitAll()
                        }.toMap()
                        reports = reports.map { r -> verdicts[r.server]?.let { r.copy(e2e = it) } ?: r }
                        session.results.update { cur -> cur.map { r -> verdicts[r.server]?.let { r.copy(e2e = it) } ?: r } }
                    }
                }

                // Accuracy refine pass (3.0): re-probe the top of the ranking with
                // extra attempts and pool the samples, so the order she acts on is
                // not one noisy pass — the leaders have to prove themselves twice.
                if (!cancel.cancelled) {
                    val leaders = (if (profile != null) reports.filter { it.working } else sortDns(reports).filter { it.working }).take(8)
                    if (leaders.size > 1) {
                        session.progress.value = Progress(0, leaders.size.toLong(), System.currentTimeMillis(), res.getString(R.string.phase_refine))
                        val extra = probePhase(leaders.map { it.server }, maxOf(attempts + 2, 5), publish = false)
                        val byServer = extra.associateBy { it.server }
                        reports = reports.map { old -> byServer[old.server]?.let { DnsCatalog.mergeReports(old, it) } ?: old }
                    }
                }

                // Tunnel results rank by tunnel score; normal results by working + median.
                val sorted = if (profile != null) reports.filter { it.working }
                    .sortedWith(compareByDescending<DnsReport> { com.skyler.scanon.engine.tunnelScore(it) }
                        .thenBy { it.medianMs ?: Double.MAX_VALUE }) + reports.filter { !it.working }
                else sortDns(reports)
                session.results.value = sorted
                val ok = sorted.count { it.working }
                if (sorted.isNotEmpty()) {
                    try {
                        if (profile != null) c.history.add("dns",
                            "tunnel ${profile.name}: $ok/${sorted.size} resolvers · $domain",
                            com.skyler.scanon.engine.tunnelReport(profile, domain, sorted))
                        else c.history.add("dns", "$ok/${sorted.size} resolvers working · $domain", Exporters.dns(sorted, ExportFormat.TXT))
                    } catch (_: Exception) {}
                }
                _events.emit(res.getString(R.string.dns_finished, ok, sorted.size))
                c.work.end(title, res.getString(R.string.dns_finished, ok, sorted.size)); began = false
            } catch (e: CancellationException) {
                if (began) c.work.end(title, res.getString(R.string.cancelled))
                throw e
            } catch (e: Exception) {
                if (began) c.work.end(title, e.message ?: "")
                _events.emit(res.getString(R.string.err_scan_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                session.running.value = false
                session.progress.update { it.copy(phase = "") }
            }
        }
    }

    /** Imports a resolver list file (SlipNet's "Import"): replaces the typed server list. */
    fun loadFile(uri: android.net.Uri) = viewModelScope.launch {
        try {
            val text = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    String(input.readNBytesCompat(4 * 1024 * 1024), Charsets.UTF_8)
                } ?: ""
            }
            val ips = text.split(Regex("[\\s,;]+")).mapNotNull { IpUtils.canonical(it.trim()) }.distinct()
            if (ips.isEmpty()) _events.emit(res.getString(R.string.err_empty_file))
            else update { it.copy(servers = ips.joinToString("\n")) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.emit(res.getString(R.string.err_read_file, e.message ?: e.javaClass.simpleName))
        }
    }

    fun cancel() { session.canceller?.cancelAll(); session.job?.cancel() }

    fun export(format: ExportFormat, workingOnly: Boolean): String =
        Exporters.dns(session.results.value.filter { !workingOnly || it.working }, format)

    fun serverList(workingOnly: Boolean) =
        session.results.value.filter { !workingOnly || it.working }.joinToString("\n") { it.server }

    companion object {
        fun sortDns(list: List<DnsReport>) = list.sortedWith(
            compareByDescending<DnsReport> { it.working }.thenBy { it.hijack }
                .thenBy { it.medianMs ?: Double.MAX_VALUE }
        )
    }
}

/** One resolver's view of a domain in the Domain scanner. */
data class ResolverRow(
    val server: String,
    val label: String,
    val aAnswers: List<String>,
    val aaaaAnswers: List<String>,
    val ok: Boolean,
    val poisoned: Boolean,
    val protocol: String,
    val avgMs: Double?,
)

class DomainScanViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ScanonApplication).container
    private val res get() = c.localizedResources()

    // State lives in the process-wide session (2.8) so the scan survives the UI.
    private val session = c.domainSession
    val domain = session.domain
    val rows: StateFlow<List<ResolverRow>> = session.rows
    val ips: StateFlow<List<TargetReport>> = session.ips
    val modal: StateFlow<Set<String>> = session.modal
    val phase: StateFlow<String> = session.phase
    val error: StateFlow<String> = session.error
    val running: StateFlow<Boolean> = session.running
    val progress: StateFlow<Progress> = session.progress
    /** Hijacked (private/reserved) answers that were excluded from the HTTPS test. */
    val skipped: StateFlow<List<String>> = session.skipped

    private fun probeOne(server: String, label: String, d: String, cancel: Canceller): ResolverRow {
        var a = DnsEngine.probeResolver(server, d, "A", "udp", 2, 2200, c = cancel, cacheBust = false)
        var proto = "udp"
        val dohHost = DnsCatalog.DOH_HOSTS[server]
        if (a.successes == 0 && dohHost != null && !cancel.cancelled) {
            val doh = DnsEngine.probeResolver(server, d, "A", "doh", 2, 3500, dohHost, c = cancel, cacheBust = false)
            if (doh.successes > 0) { a = doh; proto = "doh" }
        }
        val aaaa = DnsEngine.probeResolver(server, d, "AAAA", proto, 1, 2500, dohHost ?: "cloudflare-dns.com", c = cancel, cacheBust = false)
        return ResolverRow(
            server, label, a.answers.filter { it.type == "A" }.map { it.data }.distinct(),
            aaaa.answers.filter { it.type == "AAAA" }.map { it.data }.distinct(),
            a.successes > 0, a.poisoned, proto, a.avgMs,
        )
    }

    fun run(includeIpv6: Boolean = false) {
        if (session.running.value) return
        val d = IpUtils.normalizeHost(domain.value)
        if (d == null || IpUtils.isIp(d)) { session.error.value = res.getString(R.string.err_bad_domain); return }
        domain.value = d
        val cancel = Canceller().also { session.canceller = it }
        session.running.value = true
        session.error.value = ""; session.rows.value = emptyList(); session.ips.value = emptyList(); session.modal.value = emptySet(); session.skipped.value = emptyList()
        val title = res.getString(R.string.nav_domain)
        c.work.begin(title, 0)
        session.job = c.appScope.launch {
            try {
                val system = withContext(Dispatchers.IO) { DiagEngine.snapshot(getApplication()).dnsServers.mapNotNull { IpUtils.canonical(it) }.distinct() }
                val servers = system.map { it to "system" } + DnsCatalog.KNOWN_RESOLVERS.filter { it !in system }.map { it to "" }
                session.phase.value = res.getString(R.string.phase_asking, servers.size)
                session.progress.value = Progress(0, servers.size.toLong(), System.currentTimeMillis(), session.phase.value)
                val rows = withContext(Dispatchers.IO) {
                    servers.map { (s, label) ->
                        async {
                            probeOne(s, label, d, cancel).also { row ->
                                withContext(Dispatchers.Main) {
                                    session.rows.update { it + row }
                                    session.progress.update { it.copy(done = it.done + 1) }
                                }
                            }
                        }
                    }.awaitAll()
                }
                // The majority answer among healthy resolvers; a resolver that disagrees is flagged as divergent.
                val sets = rows.filter { it.ok && !it.poisoned && it.aAnswers.isNotEmpty() }.map { it.aAnswers.toSet() }
                session.modal.value = sets.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: emptySet()
                val union = rows.flatMap { it.aAnswers + if (includeIpv6) it.aaaaAnswers else emptyList() }.distinct()
                // Never "test" hijack answers (10.10.34.x etc.): they are block pages, not the domain's servers.
                val (bogon, testable) = union.partition { IpUtils.isBogon(it) }
                session.skipped.value = bogon
                if (testable.isEmpty()) {
                    session.phase.value = ""
                    session.error.value = res.getString(R.string.domain_no_ips)
                    return@launch
                }
                session.phase.value = res.getString(R.string.phase_testing, testable.size, d)
                val cfg = ScanConfig(methods = listOf("https"), ports = listOf(443), hostname = d, timeoutMs = 4000, attempts = 5, workers = 25)
                session.progress.value = Progress(0, testable.size.toLong(), System.currentTimeMillis(), session.phase.value)
                val queue = ConcurrentLinkedQueue<TargetReport>()
                withContext(Dispatchers.IO) {
                    NetEngine.scan(testable, cfg, cancel) { r ->
                        queue.add(r)
                    }
                }
                val sorted = generateSequence { queue.poll() }.toList().sortedByDescending { it.score() }
                session.ips.value = sorted
                session.phase.value = ""
                if (sorted.isNotEmpty()) {
                    try {
                        c.history.add("domain", "$d · ${sorted.count { it.working }}/${sorted.size} IPs working",
                            rows.joinToString("\n") { r -> "${r.server} ${r.protocol} ${if (r.poisoned) "POISONED" else if (r.ok) "ok" else "fail"} A: ${r.aAnswers.joinToString(", ")}" } +
                                "\n\n" + Exporters.ips(sorted, ExportFormat.TXT))
                    } catch (_: Exception) {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.error.value = e.message ?: e.javaClass.simpleName
            } finally {
                session.running.value = false
                session.phase.value = ""
                c.work.end(title, d)
            }
        }
    }

    fun cancel() { session.canceller?.cancelAll(); session.job?.cancel() }

    fun export(format: ExportFormat, workingOnly: Boolean) =
        Exporters.ips(session.ips.value.filter { !workingOnly || it.working }, format)

}
