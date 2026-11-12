package com.skyler.scanon.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.skyler.scanon.ScanonApplication
import com.skyler.scanon.R
import com.skyler.scanon.engine.Canceller
import com.skyler.scanon.engine.CloudflareRanges
import com.skyler.scanon.engine.ExportFormat
import com.skyler.scanon.engine.Exporters
import com.skyler.scanon.engine.GeoDb
import com.skyler.scanon.engine.IpUtils
import com.skyler.scanon.engine.NetEngine
import com.skyler.scanon.engine.ScanConfig
import com.skyler.scanon.engine.TargetParser
import com.skyler.scanon.engine.TargetReport
import com.skyler.scanon.data.IpForm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

enum class SortKey { SCORE, LATENCY, JITTER, SUCCESS, LOSS, IP, SPEED }

data class ResultFilter(
    val query: String = "",
    val workingOnly: Boolean = false,
    val maxLatencyMs: Int? = null,
    val sort: SortKey = SortKey.SCORE,
    val ascending: Boolean = false,
)

data class Progress(val done: Long = 0, val total: Long = 0, val startedAt: Long = 0, val phase: String = "") {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    /** Items per second since start. */
    fun rate(now: Long = System.currentTimeMillis()): Double {
        val secs = (now - startedAt) / 1000.0
        return if (secs > 0.5) done / secs else 0.0
    }
    fun etaSeconds(now: Long = System.currentTimeMillis()): Long? {
        val r = rate(now)
        return if (r > 0 && total > done) ((total - done) / r).toLong() else null
    }
}

/** Form state of the IP scanner. Kept in a StateFlow and persisted, so nothing is lost on tab switch or relaunch. */
data class IpFormState(
    val targets: String = "",
    val methods: Set<String> = setOf("https"),
    val ports: String = "443",
    val hostname: String = "cloudflare.com",
    val path: String = "/",
    val preset: String = "balanced",
    val workers: String = "30",
    val timeoutS: String = "4",
    val attempts: String = "5",
    val adaptive: Boolean = false,
    val failFast: Boolean = true,
    val sample: Boolean = true,
    val maxHosts: String = "4096",
    val delayMs: String = "120",
    val cfCount: String = "100",
    val domain: String = "",
) {
    val portList get() = TargetParser.parsePorts(ports)
    val portError get() = ports.isNotBlank() && portList.isEmpty()
    val timeoutError get() = timeoutS.toDoubleOrNull()?.let { it < 0.3 || it > 30 } ?: true
    val workersError get() = workers.toIntOrNull()?.let { it !in 1..256 } ?: true
    val attemptsError get() = attempts.toIntOrNull()?.let { it !in 1..20 } ?: true
    val maxHostsError get() = maxHosts.toIntOrNull()?.let { it !in 1..262_144 } ?: true
    val delayError get() = delayMs.toLongOrNull()?.let { it !in 0..5000 } ?: true
    val hostError get() = hostname.isNotBlank() && IpUtils.normalizeHost(hostname) == null
    val valid get() = !portError && !timeoutError && !workersError && !attemptsError && !maxHostsError && !hostError && !delayError

    fun toConfig() = ScanConfig(
        methods = methods.toList().ifEmpty { listOf("tcp") },
        ports = portList.ifEmpty { listOf(443) },
        hostname = IpUtils.normalizeHost(hostname) ?: "",
        path = path.ifBlank { "/" },
        timeoutMs = ((timeoutS.toDoubleOrNull() ?: 3.0) * 1000).toInt(),
        attempts = attempts.toIntOrNull() ?: 3,
        workers = workers.toIntOrNull() ?: 30,
        adaptiveTimeout = adaptive,
        failFastAfter = if (failFast) 2 else 0,
        delayBetweenAttemptsMs = delayMs.toLongOrNull() ?: 120,
    ).normalized()
}

class IpScanViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val c = (app as ScanonApplication).container
    private val res get() = c.localizedResources()

    private val _form = MutableStateFlow(initialForm())
    val form: StateFlow<IpFormState> = _form.asStateFlow()

    // Scan state lives in the process-wide session (2.8): the scan keeps running in the
    // background and this ViewModel can be recreated without losing it.
    private val session = c.ipSession
    val results: StateFlow<List<TargetReport>> = session.results

    private val _filter = MutableStateFlow(ResultFilter())
    val filter: StateFlow<ResultFilter> = _filter.asStateFlow()

    /** Sorted + filtered view, computed off the main thread (2.3 re-sorted the whole list on every recomposition). */
    val visible: StateFlow<List<TargetReport>> = combine(session.results, _filter) { list, f -> applyFilter(list, f) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val running: StateFlow<Boolean> = session.running
    val progress: StateFlow<Progress> = session.progress
    val notes: StateFlow<String> = session.notes
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    /** Number of hosts the current target text expands to (live preview under the editor). */
    private val _preview = MutableStateFlow<TargetParser.Result?>(null)
    val preview: StateFlow<TargetParser.Result?> = _preview.asStateFlow()

    private var previewJob: Job? = null

    init {
        refreshPreview()
        viewModelScope.launch {
            c.incomingTargets.collect { text ->
                if (!text.isNullOrBlank()) {
                    setTargets(text)
                    c.incomingTargets.value = null
                    _events.tryEmit(res.getString(R.string.imported_shared))
                }
            }
        }
    }

    private fun initialForm(): IpFormState {
        val f = c.settings.ipForm()
        return IpFormState(
            targets = saved.get<String>("targets") ?: "",
            methods = f.methods, ports = f.ports, hostname = f.hostname, path = f.path, preset = f.preset,
            workers = f.workers, timeoutS = f.timeout, attempts = f.attempts, adaptive = f.adaptive,
            failFast = f.failFast, sample = f.sample, maxHosts = f.maxHosts, delayMs = f.delayMs,
        )
    }

    fun updateForm(f: (IpFormState) -> IpFormState) {
        val old = _form.value
        val new = f(old)
        _form.value = new
        if (new.targets != old.targets) {
            // SavedStateHandle survives process death; keep it small enough for the Binder transaction limit.
            saved["targets"] = if (new.targets.length < 50_000) new.targets else null
            refreshPreview()
        }
        if (new.sample != old.sample || new.maxHosts != old.maxHosts) refreshPreview()
        persistForm(new)
    }

    private fun persistForm(s: IpFormState) = c.settings.saveIpForm(
        IpForm(s.methods, s.ports, s.hostname, s.path, s.preset, s.workers, s.timeoutS, s.attempts, s.adaptive, s.failFast, s.sample, s.maxHosts, s.delayMs)
    )

    fun setTargets(text: String) = updateForm { it.copy(targets = text) }

    private fun refreshPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            delay(250) // debounce typing
            val f = _form.value
            _preview.value = if (f.targets.isBlank()) null
            else TargetParser.parse(f.targets, f.maxHosts.toIntOrNull() ?: 4096, f.sample)
        }
    }

    fun applyPreset(p: String) = updateForm {
        val cfg = ScanConfig.preset(p)
        it.copy(preset = p, workers = cfg.workers.toString(), timeoutS = (cfg.timeoutMs / 1000).toString(), attempts = cfg.attempts.toString())
    }

    fun setFilter(f: (ResultFilter) -> ResultFilter) = _filter.update(f)

    fun fillRandomCloudflare(v6: Boolean = false) {
        val n = _form.value.cfCount.toIntOrNull()?.coerceIn(1, 10_000) ?: 100
        setTargets((if (v6) CloudflareRanges.randomIpv6(n) else CloudflareRanges.randomIps(n)).joinToString("\n"))
    }

    fun dedupeTargets() = viewModelScope.launch(Dispatchers.Default) {
        val f = _form.value
        val r = TargetParser.parse(f.targets, f.maxHosts.toIntOrNull() ?: 4096, f.sample)
        val text = (r.ips + r.hostnames).joinToString("\n")
        withContext(Dispatchers.Main) { setTargets(text) }
    }

    fun appendTargets(lines: List<String>) {
        val cur = _form.value.targets.trimEnd()
        setTargets(if (cur.isEmpty()) lines.joinToString("\n") else cur + "\n" + lines.joinToString("\n"))
    }

    /** Resolves the domain off the main thread and appends its addresses to the targets. */
    fun resolveAndAdd() {
        val host = IpUtils.normalizeHost(_form.value.domain) ?: run {
            _events.tryEmit(res.getString(R.string.err_bad_domain)); return
        }
        viewModelScope.launch {
            val (ips, err) = withContext(Dispatchers.IO) { NetEngine.resolveDomain(host) }
            if (ips.isEmpty()) _events.emit(err ?: res.getString(R.string.err_no_addresses))
            else { appendTargets(ips); if (_form.value.hostname.isBlank()) updateForm { it.copy(hostname = host) } }
        }
    }

    /** Reads a text file from the system picker; bounded to 4 MiB and fully error-handled (2.3 crashed on failure). */
    fun loadFile(uri: Uri) = viewModelScope.launch {
        try {
            val text = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readNBytesCompat(4 * 1024 * 1024)
                    String(bytes, Charsets.UTF_8)
                } ?: ""
            }
            if (text.isBlank()) _events.emit(res.getString(R.string.err_empty_file)) else setTargets(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.emit(res.getString(R.string.err_read_file, e.message ?: e.javaClass.simpleName))
        }
    }

    fun quickScan() {
        if (session.running.value) return
        updateForm {
            it.copy(targets = CloudflareRanges.randomIps(100).joinToString("\n"), methods = setOf("https"), ports = "443",
                hostname = "cloudflare.com", path = "/cdn-cgi/trace", attempts = "5", timeoutS = "4", workers = "30", preset = "balanced")
        }
        startScan()
    }

    fun startScan(onlyIps: List<String>? = null) {
        if (session.running.value) return
        val f = _form.value
        if (!f.valid) { _events.tryEmit(res.getString(R.string.err_fix_fields)); return }
        val cfg = f.toConfig()
        session.lastConfig = cfg
        val cancel = Canceller().also { session.canceller = it }
        session.running.value = true
        session.job = c.appScope.launch {
            val title = res.getString(R.string.nav_ip)
            var began = false
            try {
                session.progress.value = Progress(phase = res.getString(R.string.phase_expanding), startedAt = System.currentTimeMillis())
                // Expansion and hostname resolution run on background threads (2.3 did both on the main thread).
                val ips: List<String> = onlyIps ?: withContext(Dispatchers.Default) {
                    val parsed = TargetParser.parse(f.targets, f.maxHosts.toIntOrNull() ?: 4096, f.sample)
                    val notes = parsed.notes.toMutableList()
                    val resolved = parsed.hostnames.map { h ->
                        async(Dispatchers.IO) {
                            val (list, err) = NetEngine.resolveDomain(h)
                            if (err != null) synchronized(notes) { notes += "$h: $err" }
                            list
                        }
                    }.awaitAll().flatten()
                    session.notes.value = notes.joinToString("\n")
                    (parsed.ips + resolved).distinct()
                }
                if (ips.isEmpty()) { _events.emit(res.getString(R.string.err_no_targets)); return@launch }
                val total = NetEngine.workCount(ips.size, cfg)
                if (onlyIps == null) session.results.value = emptyList()
                session.progress.value = Progress(0, total, System.currentTimeMillis(), res.getString(R.string.scanning))
                c.work.begin(title, total); began = true

                val queue = ConcurrentLinkedQueue<TargetReport>()
                val fresh = ArrayList<TargetReport>()
                val flusher = launch {
                    while (isActive) { delay(300); flush(queue, fresh, onlyIps != null, title) }
                }
                withContext(Dispatchers.IO) { NetEngine.scan(ips, cfg, cancel) { queue.add(it) } }
                flusher.cancel()
                flush(queue, fresh, onlyIps != null, title)
                val sorted = session.results.value.sortedByDescending { it.score() }
                session.results.value = sorted
                saveHistory(sorted, cfg)
                val working = sorted.count { it.working }
                c.work.end(title, res.getString(R.string.notif_done_ip, working, sorted.size)); began = false
                _events.emit(res.getString(R.string.scan_finished, working, sorted.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(res.getString(R.string.err_scan_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                if (began) c.work.end(title, res.getString(R.string.cancelled))
                session.running.value = false
                session.progress.update { it.copy(phase = "") }
            }
        }
    }

    /** Moves queued reports into the state in one batch: one list copy per 300 ms instead of one UI coroutine per result. */
    private fun flush(queue: ConcurrentLinkedQueue<TargetReport>, fresh: MutableList<TargetReport>, merge: Boolean, title: String) {
        if (queue.isEmpty()) return
        val batch = ArrayList<TargetReport>()
        while (true) batch += queue.poll() ?: break
        fresh += batch
        session.results.update { cur ->
            if (merge) {
                val byKey = batch.associateBy { it.key }
                val merged = cur.map { byKey[it.key] ?: it }
                val known = cur.map { it.key }.toHashSet()
                merged + batch.filter { it.key !in known }
            } else cur + batch
        }
        session.progress.update { it.copy(done = it.done + batch.size) }
        val p = session.progress.value
        c.work.progress(title, p.done, p.total)
    }

    fun cancel() {
        session.canceller?.cancelAll()
        session.job?.cancel()
    }

    /** Re-tests the 10 best working results with 7 slower attempts and replaces their numbers. */
    fun deepVerify() {
        if (session.running.value) return
        val top = session.results.value.filter { it.working }.sortedByDescending { it.score() }.take(10)
        if (top.isEmpty()) { _events.tryEmit(res.getString(R.string.err_nothing_working)); return }
        val base = session.lastConfig ?: _form.value.toConfig()
        val cancel = Canceller().also { session.canceller = it }
        session.running.value = true
        val title = res.getString(R.string.nav_ip)
        c.work.begin(title, top.size.toLong())
        session.job = c.appScope.launch {
            try {
                session.progress.value = Progress(0, top.size.toLong(), System.currentTimeMillis(), res.getString(R.string.deep_verify))
                val cfg = base.copy(attempts = 7, timeoutMs = maxOf(base.timeoutMs, 5000), failFastAfter = 0, adaptiveTimeout = false)
                val sem = Semaphore(5)
                val fresh = withContext(Dispatchers.IO) {
                    top.map { r ->
                        async {
                            sem.withPermit {
                                val out = NetEngine.aggregate(r.ip, r.method, NetEngine.runMethod(r.ip, r.port, r.method, cfg, cancel), r.port)
                                session.progress.update { it.copy(done = it.done + 1) }
                                out.copy(downloadMbps = r.downloadMbps)
                            }
                        }
                    }.awaitAll()
                }
                val byKey = fresh.associateBy { it.key }
                session.results.update { cur -> cur.map { byKey[it.key] ?: it }.sortedByDescending { it.score() } }
                _events.emit(res.getString(R.string.deep_done, fresh.count { it.working }, fresh.size))
            } finally {
                c.work.end(title, res.getString(R.string.deep_verify))
                session.running.value = false
                session.progress.update { it.copy(phase = "") }
            }
        }
    }

    /** Runs the scan again for targets that did not work. */
    fun rescanFailed() {
        val failed = session.results.value.filter { !it.working }.map { it.ip }.distinct()
        if (failed.isEmpty()) { _events.tryEmit(res.getString(R.string.err_no_failed)); return }
        startScan(failed)
    }

    /** Download throughput through the best Cloudflare IPs (speed.cloudflare.com, SNI pinned to each IP). */
    fun speedTestTop(n: Int = 5) {
        if (session.running.value) return
        val top = session.results.value.filter { it.working && CloudflareRanges.contains(it.ip) }
            .sortedByDescending { it.score() }.distinctBy { it.ip }.take(n)
        if (top.isEmpty()) { _events.tryEmit(res.getString(R.string.err_speed_cf_only)); return }
        val cancel = Canceller().also { session.canceller = it }
        session.running.value = true
        val title = res.getString(R.string.nav_ip)
        c.work.begin(title, top.size.toLong())
        session.job = c.appScope.launch {
            try {
                session.progress.value = Progress(0, top.size.toLong(), System.currentTimeMillis(), res.getString(R.string.speed_test))
                for (r in top) {
                    val (mbps, detail) = withContext(Dispatchers.IO) { NetEngine.downloadTest(r.ip, c = cancel) }
                    if (cancel.cancelled) break
                    session.results.update { cur -> cur.map { if (it.ip == r.ip) it.copy(downloadMbps = mbps) else it } }
                    session.progress.update { it.copy(done = it.done + 1) }
                    if (mbps == null) _events.emit("${r.ip}: $detail")
                }
            } finally {
                c.work.end(title, res.getString(R.string.speed_test))
                session.running.value = false
                session.progress.update { it.copy(phase = "") }
            }
        }
    }

    private suspend fun saveHistory(sorted: List<TargetReport>, cfg: ScanConfig) {
        if (sorted.isEmpty()) return
        val working = sorted.count { it.working }
        val header = "methods ${cfg.methods.joinToString(",")} · ports ${cfg.ports.joinToString(",")} · SNI ${cfg.hostname.ifEmpty { "-" }}\n"
        try {
            c.history.add("ip", "$working/${sorted.size} working", header + Exporters.ips(sorted, ExportFormat.TXT))
        } catch (_: Exception) {}
    }

    fun export(format: ExportFormat, workingOnly: Boolean): String {
        val list = visible.value.let { v -> if (workingOnly) v.filter { it.working } else v }
        return Exporters.ips(list, format) { GeoDb.lookup(it) }
    }

    fun ipList(workingOnly: Boolean): String =
        visible.value.filter { !workingOnly || it.working }.map { it.ip }.distinct().joinToString("\n")

    fun clearResults() { if (!session.running.value) { session.results.value = emptyList(); session.notes.value = "" } }

    companion object {
        fun applyFilter(list: List<TargetReport>, f: ResultFilter): List<TargetReport> {
            val q = f.query.trim().lowercase()
            var out = list.asSequence()
            if (f.workingOnly) out = out.filter { it.working }
            f.maxLatencyMs?.let { max -> out = out.filter { (it.primaryMs() ?: Double.MAX_VALUE) <= max } }
            if (q.isNotEmpty()) out = out.filter { r ->
                r.ip.contains(q) || r.method.contains(q) || r.reliability.contains(q) ||
                    (GeoDb.lookup(r.ip)?.searchText?.lowercase()?.contains(q) == true)
            }
            val cmp: Comparator<TargetReport> = when (f.sort) {
                SortKey.SCORE -> compareBy { it.score() }
                SortKey.LATENCY -> compareBy { it.primaryMs() ?: Double.MAX_VALUE }
                SortKey.JITTER -> compareBy { it.jitterMs ?: Double.MAX_VALUE }
                SortKey.SUCCESS -> compareBy { it.successRate }
                SortKey.LOSS -> compareBy { it.loss }
                SortKey.SPEED -> compareBy { it.downloadMbps ?: -1.0 }
                SortKey.IP -> Comparator { a, b -> compareIp(a.ip, b.ip) }
            }
            // "Natural" direction: best first for score/success/speed, lowest first for latency/jitter/loss/IP.
            val naturalDesc = f.sort in setOf(SortKey.SCORE, SortKey.SUCCESS, SortKey.SPEED)
            val desc = if (f.ascending) !naturalDesc else naturalDesc
            return out.sortedWith(if (desc) cmp.reversed() else cmp).toList()
        }

        fun compareIp(a: String, b: String): Int {
            val la = IpUtils.parseIpv4(a); val lb = IpUtils.parseIpv4(b)
            if (la != null && lb != null) return la.compareTo(lb)
            if (la != null) return -1
            if (lb != null) return 1
            val ba = IpUtils.parseIpv6(a); val bb = IpUtils.parseIpv6(b)
            if (ba != null && bb != null) return IpUtils.ipv6ToBig(ba).compareTo(IpUtils.ipv6ToBig(bb))
            return a.compareTo(b)
        }
    }
}

/** InputStream.readNBytes is API 33+; bounded read for older devices. */
fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
