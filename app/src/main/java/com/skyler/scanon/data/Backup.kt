package com.skyler.scanon.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One-file backup of the Vault, Groups, History and settings (new in 2.4). Restore merges: existing entries
 * are kept, entries from the backup are added when they are not already there.
 */
class BackupManager(
    private val vault: VaultStore,
    private val groups: GroupsStore,
    private val history: HistoryStore,
    private val settings: SettingsRepository,
) {
    data class Summary(val vault: Int, val groups: Int, val history: Int)

    suspend fun export(includeHistory: Boolean = true): String {
        val o = JSONObject()
        o.put("format", "scanon-backup")
        o.put("version", 1)
        o.put("created", System.currentTimeMillis())
        o.put("vault", JSONArray().apply { vault.load().forEach { put(vaultCodec.encode(it)) } })
        o.put("groups", JSONArray().apply { groups.load().forEach { put(groupCodec.encode(it)) } })
        if (includeHistory) o.put("history", JSONArray().apply { history.load().forEach { put(historyCodec.encode(it)) } })
        o.put("prefs", JSONObject().apply {
            settings.exportPrefs().forEach { (k, v) ->
                when (v) {
                    is Set<*> -> put(k, JSONArray(v.toList()))
                    null -> {}
                    else -> put(k, v)
                }
            }
        })
        return o.toString(2)
    }

    /** Throws IllegalArgumentException when the text is not a ScanON backup. */
    suspend fun import(text: String, restorePrefs: Boolean = true): Summary {
        val o = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("not a JSON backup file") }
        require(o.optString("format") == "scanon-backup") { "not a ScanON backup" }
        val v = o.optJSONArray("vault")?.let { a -> (0 until a.length()).mapNotNull { runCatching { vaultCodec.decode(a.getJSONObject(it)) }.getOrNull() } }.orEmpty()
        val g = o.optJSONArray("groups")?.let { a -> (0 until a.length()).mapNotNull { runCatching { groupCodec.decode(a.getJSONObject(it)) }.getOrNull() } }.orEmpty()
        val h = o.optJSONArray("history")?.let { a -> (0 until a.length()).mapNotNull { runCatching { historyCodec.decode(a.getJSONObject(it)) }.getOrNull() } }.orEmpty()
        var nv = 0; var ng = 0; var nh = 0
        vault.update { cur -> val keys = cur.map { it.key }.toSet(); val add = v.filter { it.key !in keys }; nv = add.size; cur + add }
        groups.update { cur -> val keys = cur.map { it.kind + "|" + it.name.lowercase() }.toSet(); val add = g.filter { (it.kind + "|" + it.name.lowercase()) !in keys }; ng = add.size; cur + add }
        history.update { cur ->
            val sigs = cur.map { it.ts to it.summary }.toSet()
            var next = (cur.maxOfOrNull { it.id } ?: 0L)
            val add = h.filter { (it.ts to it.summary) !in sigs }.sortedBy { it.ts }.map { next += 1; it.copy(id = next) }
            nh = add.size; cur + add
        }
        if (restorePrefs) o.optJSONObject("prefs")?.let { p ->
            val map = HashMap<String, Any?>()
            p.keys().forEach { k ->
                when (val x = p.get(k)) {
                    is JSONArray -> map[k] = (0 until x.length()).map { x.getString(it) }.toSet()
                    // org.json reads a whole-number float ("1") back as Integer; without
                    // the FLOAT_KEYS check it would be stored with putInt and later crash
                    // the float readers. Keep known float keys as floats.
                    is Number -> map[k] = if (k in SettingsRepository.FLOAT_KEYS || x is Double || x is Float) x.toFloat() else x.toInt()
                    else -> map[k] = x
                }
            }
            settings.importPrefs(map)
        }
        return Summary(nv, ng, nh)
    }

    // Small codecs that reuse the stores' JSON shape without touching their files.
    private object vaultCodec {
        fun encode(e: VaultStore.Entry) = JSONObject().apply {
            put("ip", e.ip); put("label", e.label); put("hostname", e.hostname); put("port", e.port); put("added", e.addedTs)
        }
        fun decode(o: JSONObject) = VaultStore.Entry(
            ip = o.getString("ip"), label = o.optString("label"), hostname = o.optString("hostname", "cloudflare.com"),
            port = o.optInt("port", 443), addedTs = o.optLong("added", System.currentTimeMillis()),
        )
    }

    private object groupCodec {
        fun encode(g: GroupsStore.Group) = JSONObject().put("name", g.name).put("kind", g.kind).put("items", JSONArray(g.items))
        fun decode(o: JSONObject): GroupsStore.Group {
            val a = o.getJSONArray("items")
            return GroupsStore.Group(o.getString("name"), o.optString("kind", "ip"), (0 until a.length()).map { a.getString(it) })
        }
    }

    private object historyCodec {
        fun encode(e: HistoryStore.Entry) = JSONObject().put("ts", e.ts).put("kind", e.kind).put("summary", e.summary).put("body", e.body)
        fun decode(o: JSONObject) = HistoryStore.Entry(0, o.getLong("ts"), o.getString("kind"), o.getString("summary"), o.getString("body"))
    }
}
