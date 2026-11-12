package com.skyler.scanon.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * A JSON-array file with:
 *  - a Mutex around every read-modify-write (2.3's "Retest all" lost updates when checks finished together),
 *  - atomic writes (temp file + fsync + rename) so a crash mid-write cannot truncate the file,
 *  - per-record tolerance: one bad record is skipped instead of wiping the whole list, and an unreadable file is
 *    kept aside as `<name>.corrupt-<time>` instead of being overwritten by the next save,
 *  - an observable [items] StateFlow, all IO on Dispatchers.IO.
 */
abstract class JsonListStore<T>(private val file: File) {
    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()
    @Volatile private var loaded = false

    protected abstract fun decode(o: JSONObject): T
    protected abstract fun encode(t: T): JSONObject
    protected open fun order(list: List<T>): List<T> = list

    suspend fun load(): List<T> = withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded() } }

    private fun ensureLoaded(): List<T> {
        if (loaded) return _items.value
        val list = readFile()
        _items.value = order(list)
        loaded = true
        return _items.value
    }

    private fun readFile(): List<T> {
        if (!file.exists()) return emptyList()
        val text = try { file.readText() } catch (_: Exception) { return emptyList() }
        if (text.isBlank()) return emptyList()
        val arr = try { JSONArray(text) } catch (_: Exception) {
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            return emptyList()
        }
        val out = ArrayList<T>(arr.length())
        for (i in 0 until arr.length()) {
            try { out += decode(arr.getJSONObject(i)) } catch (_: Exception) { /* skip just this record */ }
        }
        return out
    }

    private fun writeFile(list: List<T>) {
        val arr = JSONArray()
        list.forEach { arr.put(encode(it)) }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(arr.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            try { out.fd.sync() } catch (_: Exception) {}
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("could not replace ${file.name}")
        }
    }

    /** Atomically transforms the list and persists it. Returns the new list. */
    suspend fun update(transform: (List<T>) -> List<T>): List<T> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = order(transform(ensureLoaded()))
            writeFile(next)
            _items.value = next
            next
        }
    }

    suspend fun replaceAll(list: List<T>) = update { list }
}

class HistoryStore(dir: File, private val cap: Int = 200) : JsonListStore<HistoryStore.Entry>(File(dir, "history.json")) {
    data class Entry(val id: Long, val ts: Long, val kind: String, val summary: String, val body: String)

    override fun decode(o: JSONObject) = Entry(o.getLong("id"), o.getLong("ts"), o.getString("kind"), o.getString("summary"), o.getString("body"))
    override fun encode(t: Entry) = JSONObject().put("id", t.id).put("ts", t.ts).put("kind", t.kind).put("summary", t.summary).put("body", t.body)
    override fun order(list: List<Entry>) = list.sortedByDescending { it.id }.take(cap)

    suspend fun add(kind: String, summary: String, body: String) = update { list ->
        val id = (list.maxOfOrNull { it.id } ?: 0L) + 1
        listOf(Entry(id, System.currentTimeMillis(), kind, summary, body)) + list
    }

    suspend fun delete(id: Long) = update { l -> l.filter { it.id != id } }
    suspend fun restore(e: Entry) = update { l -> if (l.any { it.id == e.id }) l else l + e }
    suspend fun clear() = update { emptyList() }
}

class VaultStore(dir: File) : JsonListStore<VaultStore.Entry>(File(dir, "vault.json")) {
    data class Entry(
        val ip: String,
        val label: String = "",
        val hostname: String = "cloudflare.com",
        val port: Int = 443,
        val addedTs: Long = System.currentTimeMillis(),
        val lastOk: Boolean? = null,
        val lastMs: Double? = null,
        val lastCheckTs: Long = 0L,
        /** Last few retest results in ms (null = failed), newest last; drives the trend line. */
        val recent: List<Double?> = emptyList(),
    ) {
        val key get() = "$ip|$hostname|$port"
    }

    override fun decode(o: JSONObject): Entry {
        val recent = o.optJSONArray("recent")?.let { a -> (0 until a.length()).map { if (a.isNull(it)) null else a.getDouble(it) } }.orEmpty()
        return Entry(
            ip = o.getString("ip"), label = o.optString("label", ""), hostname = o.optString("hostname", "cloudflare.com"),
            port = o.optInt("port", 443), addedTs = o.optLong("added", 0L),
            lastOk = if (o.has("lastOk")) o.getBoolean("lastOk") else null,
            lastMs = if (o.has("lastMs")) o.getDouble("lastMs") else null,
            lastCheckTs = o.optLong("lastCheck", 0L), recent = recent,
        )
    }

    override fun encode(t: Entry) = JSONObject().apply {
        put("ip", t.ip); put("label", t.label); put("hostname", t.hostname); put("port", t.port)
        put("added", t.addedTs); put("lastCheck", t.lastCheckTs)
        t.lastOk?.let { put("lastOk", it) }
        t.lastMs?.let { put("lastMs", it) }
        put("recent", JSONArray().apply { t.recent.forEach { put(it ?: JSONObject.NULL) } })
    }

    /** Adds an entry; the same IP verified with a different SNI or port is a different entry (2.3 deduped by IP only). */
    suspend fun add(ip: String, label: String = "", hostname: String = "cloudflare.com", port: Int = 443): Boolean {
        var added = false
        update { list ->
            if (list.any { it.ip == ip && it.hostname == hostname && it.port == port }) list
            else { added = true; listOf(Entry(ip, label, hostname, port)) + list }
        }
        return added
    }

    suspend fun remove(key: String) = update { l -> l.filter { it.key != key } }
    suspend fun restore(e: Entry) = update { l -> if (l.any { it.key == e.key }) l else listOf(e) + l }
    suspend fun setLabel(key: String, label: String) = update { l -> l.map { if (it.key == key) it.copy(label = label.trim()) else it } }

    suspend fun recordCheck(key: String, ok: Boolean, ms: Double?) = update { l ->
        l.map {
            if (it.key == key) it.copy(lastOk = ok, lastMs = ms, lastCheckTs = System.currentTimeMillis(),
                recent = (it.recent + (if (ok) ms else null)).takeLast(8))
            else it
        }
    }
}

class GroupsStore(dir: File) : JsonListStore<GroupsStore.Group>(File(dir, "groups.json")) {
    data class Group(val name: String, val kind: String, val items: List<String>)

    override fun decode(o: JSONObject): Group {
        val a = o.getJSONArray("items")
        return Group(o.getString("name"), o.optString("kind", "ip"), (0 until a.length()).map { a.getString(it) })
    }

    override fun encode(t: Group) = JSONObject().put("name", t.name).put("kind", t.kind).put("items", JSONArray(t.items))
    override fun order(list: List<Group>) = list.sortedWith(compareBy({ it.kind }, { it.name.lowercase() }))

    suspend fun upsert(g: Group) = update { list ->
        val clean = g.copy(name = g.name.trim(), items = g.items.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        val idx = list.indexOfFirst { it.name.equals(clean.name, true) && it.kind == clean.kind }
        if (idx >= 0) list.toMutableList().also { it[idx] = clean } else list + clean
    }

    suspend fun delete(name: String, kind: String) = update { l -> l.filterNot { it.name == name && it.kind == kind } }

    suspend fun rename(old: String, kind: String, new: String) = update { l ->
        if (new.isBlank() || l.any { it.name.equals(new.trim(), true) && it.kind == kind }) l
        else l.map { if (it.name == old && it.kind == kind) it.copy(name = new.trim()) else it }
    }
}
