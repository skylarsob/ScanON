package com.skyler.scanon.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.scanon.R
import com.skyler.scanon.ScanonApplication
import com.skyler.scanon.data.AppSettings
import com.skyler.scanon.data.GroupsStore
import com.skyler.scanon.data.HistoryStore
import com.skyler.scanon.data.VaultStore
import com.skyler.scanon.engine.Canceller
import com.skyler.scanon.engine.DiagEngine
import com.skyler.scanon.engine.Fmt
import com.skyler.scanon.engine.IpUtils
import com.skyler.scanon.engine.NetEngine
import com.skyler.scanon.engine.ScanConfig
import com.skyler.scanon.engine.Stats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class DiagViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ScanonApplication).container
    private val _snap = MutableStateFlow(DiagEngine.Snapshot())
    val snap: StateFlow<DiagEngine.Snapshot> = _snap.asStateFlow()
    private val _checks = MutableStateFlow<List<DiagEngine.Check>>(emptyList())
    val checks: StateFlow<List<DiagEngine.Check>> = _checks.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private var job: Job? = null
    private var canceller: Canceller? = null

    val order = listOf("gateway", "sysdns", "dns1111", "https", "ipv6", "portal", "trace", "mtu")

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _snap.value = withContext(Dispatchers.IO) { DiagEngine.snapshot(getApplication()) }
    }

    fun run() {
        if (_running.value) return
        val cancel = Canceller().also { canceller = it }
        _running.value = true
        _checks.value = emptyList()
        job = viewModelScope.launch {
            try {
                val snap = withContext(Dispatchers.IO) { DiagEngine.snapshot(getApplication()) }
                _snap.value = snap
                val all = DiagEngine.runAll(snap, cancel) { ch ->
                    _checks.update { cur -> (cur + ch).sortedBy { order.indexOf(it.id) } }
                }
                val text = "transport ${snap.transport} · IP ${snap.privateIp} · gateway ${snap.gateway} · DNS ${snap.dnsServers.joinToString()} · VPN ${snap.isVpn}\n" +
                    all.joinToString("\n") { "[${when (it.ok) { true -> "OK"; false -> "FAIL"; null -> "--" }}] ${it.name}: ${it.detail}" }
                try { c.history.add("diag", "${all.count { it.ok == true }}/${all.size} checks passed · ${snap.transport}", text) } catch (_: Exception) {}
            } finally {
                _running.value = false
            }
        }
    }

    fun cancel() { canceller?.cancelAll(); job?.cancel() }

    fun asText(): String = (listOf("${snap.value.transport} · ${snap.value.privateIp}") +
        checks.value.map { "[${when (it.ok) { true -> "OK"; false -> "FAIL"; null -> "--" }}] ${it.name}: ${it.detail}" }).joinToString("\n")

}

enum class VaultSort { ADDED, LATENCY, LABEL, STATUS }

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ScanonApplication).container
    private val res get() = c.localizedResources()
    val sort = MutableStateFlow(VaultSort.ADDED)
    val entries: StateFlow<List<VaultStore.Entry>> = combine(c.vault.items, sort) { list, s ->
        when (s) {
            VaultSort.ADDED -> list
            VaultSort.LATENCY -> list.sortedBy { if (it.lastOk == true) it.lastMs ?: Double.MAX_VALUE else Double.MAX_VALUE }
            VaultSort.LABEL -> list.sortedBy { it.label.ifEmpty { it.ip }.lowercase() }
            VaultSort.STATUS -> list.sortedByDescending { it.lastOk == true }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    private var job: Job? = null
    private var canceller = Canceller()
    private var lastRemoved: VaultStore.Entry? = null

    init { viewModelScope.launch { c.vault.load() } }

    fun add(ip: String, label: String, hostname: String, port: Int = 443) = viewModelScope.launch {
        val canon = IpUtils.canonical(ip)
        if (canon == null) { _events.emit(res.getString(R.string.err_bad_ip)); return@launch }
        val host = IpUtils.normalizeHost(hostname) ?: "cloudflare.com"
        val added = c.vault.add(canon, label, host, port)
        _events.emit(res.getString(if (added) R.string.vault_saved else R.string.vault_exists))
    }

    fun remove(e: VaultStore.Entry) = viewModelScope.launch {
        lastRemoved = e
        c.vault.remove(e.key)
    }

    fun undoRemove() = viewModelScope.launch { lastRemoved?.let { c.vault.restore(it) }; lastRemoved = null }

    fun setLabel(e: VaultStore.Entry, label: String) = viewModelScope.launch { c.vault.setLabel(e.key, label) }

    /** Retest = 3 HTTPS attempts on the entry's own port and SNI; the median is stored (2.3: one attempt, port 443 only). */
    private suspend fun retestOne(e: VaultStore.Entry, cancel: Canceller) {
        _busy.update { it + e.key }
        try {
            val cfg = ScanConfig(methods = listOf("https"), ports = listOf(e.port), hostname = e.hostname, timeoutMs = 4000, attempts = 3, failFastAfter = 2)
            val results = withContext(Dispatchers.IO) { NetEngine.runMethod(e.ip, e.port, "https", cfg, cancel) }
            if (cancel.cancelled) return
            val oks = results.filter { it.ok }.mapNotNull { it.totalMs }
            c.vault.recordCheck(e.key, oks.isNotEmpty(), Stats.median(oks))
        } finally {
            _busy.update { it - e.key }
        }
    }

    fun retest(e: VaultStore.Entry) = viewModelScope.launch { retestOne(e, canceller) }

    fun retestAll() {
        if (job?.isActive == true) return
        val list = entries.value
        if (list.isEmpty()) return
        canceller = Canceller()
        val cancel = canceller
        val title = res.getString(R.string.vault_title)
        c.work.begin(title, list.size.toLong())
        job = c.appScope.launch {
            try {
                val sem = Semaphore(8)
                list.map { e -> async { sem.withPermit { retestOne(e, cancel) } } }.awaitAll()
                val ok = c.vault.items.value.count { it.lastOk == true }
                _events.emit(res.getString(R.string.vault_retest_done, ok, list.size))
                c.work.end(title, res.getString(R.string.vault_retest_done, ok, list.size))
            } catch (e: CancellationException) {
                c.work.end(title, res.getString(R.string.cancelled))
                throw e
            }
        }
    }

    fun cancel() { canceller.cancelAll(); job?.cancel(); _busy.value = emptySet() }

    fun export(workingOnly: Boolean): String = entries.value.filter { !workingOnly || it.lastOk == true }.joinToString("\n") { e ->
        listOf(if (e.port == 443) e.ip else if (e.ip.contains(':')) "[${e.ip}]:${e.port}" else "${e.ip}:${e.port}",
            e.label, "SNI ${e.hostname}", e.lastMs?.let { "${Fmt.ms(it)} ms" } ?: "").filter { it.isNotEmpty() }.joinToString("  ")
    }

}

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ScanonApplication).container
    val query = MutableStateFlow("")
    val kind = MutableStateFlow<String?>(null)
    val entries: StateFlow<List<HistoryStore.Entry>> = combine(c.history.items, query, kind) { list, q, k ->
        list.filter { (k == null || it.kind == k) && (q.isBlank() || it.summary.contains(q, true) || it.body.contains(q, true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var lastDeleted: List<HistoryStore.Entry> = emptyList()

    init { viewModelScope.launch { c.history.load() } }

    fun delete(e: HistoryStore.Entry) = viewModelScope.launch { lastDeleted = listOf(e); c.history.delete(e.id) }
    fun clearAll() = viewModelScope.launch { lastDeleted = c.history.items.value; c.history.clear() }
    fun undo() = viewModelScope.launch { lastDeleted.forEach { c.history.restore(it) }; lastDeleted = emptyList() }

    fun formatTs(ts: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))
}

class GroupsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ScanonApplication).container
    val groups: StateFlow<List<GroupsStore.Group>> = c.groups.items
    init { viewModelScope.launch { c.groups.load() } }
    fun upsert(name: String, kind: String, items: List<String>) = viewModelScope.launch {
        if (name.isNotBlank()) c.groups.upsert(GroupsStore.Group(name, kind, items))
    }
    fun delete(g: GroupsStore.Group) = viewModelScope.launch { c.groups.delete(g.name, g.kind) }
    fun rename(g: GroupsStore.Group, new: String) = viewModelScope.launch { c.groups.rename(g.name, g.kind, new) }
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ScanonApplication).container
    private val res get() = c.localizedResources()
    val settings: StateFlow<AppSettings> = c.settings.settings
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    fun update(f: (AppSettings) -> AppSettings) = c.settings.update(f)

    suspend fun backupText(): String = c.backup.export()

    fun writeBackup(uri: Uri) = viewModelScope.launch {
        try {
            val text = c.backup.export()
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                    ?: throw java.io.IOException("cannot open destination")
            }
            _events.emit(res.getString(R.string.backup_saved))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _events.emit(res.getString(R.string.err_backup, e.message ?: e.javaClass.simpleName))
        }
    }

    fun restoreBackup(uri: Uri) = viewModelScope.launch {
        try {
            val text = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { String(it.readNBytesCompat(16 * 1024 * 1024)) } ?: ""
            }
            val s = c.backup.import(text)
            _events.emit(res.getString(R.string.backup_restored, s.vault, s.groups, s.history))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _events.emit(res.getString(R.string.err_backup, e.message ?: e.javaClass.simpleName))
        }
    }
}
