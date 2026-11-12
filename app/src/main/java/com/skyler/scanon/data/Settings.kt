package com.skyler.scanon.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Appearance presets (2.5). CLASSIC keeps the 2.3/2.4 teal look and honours [ThemeMode];
 * the others are fixed-tone designs from the design brief. */
enum class AppearancePreset { CLASSIC, OBSIDIAN, TOKYO, MATRIX, FROSTED }

/** UI density (2.5). COMPACT is the old opt-in "compact" look; SPACIOUS enlarges spacing and text slightly. */
enum class DensityMode { COMPACT, COMFORTABLE, SPACIOUS }

data class AppSettings(
    /** "system", "en" or "fa". */
    val lang: String = "system",
    val theme: ThemeMode = ThemeMode.DARK,
    val reduceMotion: Boolean = false,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    val notifyOnFinish: Boolean = true,
    /** The 2.3 look: everything drawn ~18% smaller regardless of the system font size. Off by default now. */
    val compact: Boolean = false,
    val historyCap: Int = 200,
    // ---- appearance engine (2.5) ----
    val preset: AppearancePreset = AppearancePreset.OBSIDIAN,
    /** Custom accent as ARGB; 0 = use the preset's own accent. */
    val accentArgb: Int = 0,
    /** Glass intensity 0..1: how translucent the panels are and how strong the background glow is. */
    val glass: Float = 0.6f,
    val densityMode: DensityMode = DensityMode.COMFORTABLE,
    /** Corner radius multiplier 0.5..1.5. */
    val radiusScale: Float = 1.0f,
    /** First-run introduction finished (2.8). False only until the intro is completed or skipped once. */
    val onboarded: Boolean = false,
)

/** Saved form state of the DNS scanner (same keys as 2.3 so existing settings carry over). */
data class DnsForm(
    val servers: String, val domain: String, val qtype: String, val protocol: String,
    val attempts: String, val timeout: String, val dohHost: String,
    /** Parallel resolvers probed at once (2.8). */
    val workers: String = "24",
    /** Active tunnel profile id, "" = tunnel mode off (2.8). */
    val tunnel: String = "",
    /** DNS port for UDP/TCP probes (2.8, SlipNet parity). */
    val port: String = "53",
    /** Shuffle the resolver list before scanning (2.8). */
    val shuffle: Boolean = true,
    /** After a working resolver, also test its whole /24 subnet (2.8). */
    val nearby: Boolean = false,
    /** End-to-end tunnel verification for working resolvers (2.8). */
    val e2e: Boolean = false,
    val e2eTimeoutMs: String = "4000",
    val e2eConcurrency: String = "5",
    /** Custom resolver ranges (CIDRs/IPs, one per line) expanded at scan start (2.8). */
    val ranges: String = "",
)

/** Saved form state of the IP scanner (new in 2.4: 2.3 forgot everything on every launch). */
data class IpForm(
    val methods: Set<String>, val ports: String, val hostname: String, val path: String, val preset: String,
    val workers: String, val timeout: String, val attempts: String, val adaptive: Boolean, val failFast: Boolean,
    val sample: Boolean, val maxHosts: String,
    /** Pause between attempts of one target, ms (2.8). */
    val delayMs: String = "120",
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun read(): AppSettings {
        val theme = when (prefs.getString("theme_mode", null)) {
            "system" -> ThemeMode.SYSTEM
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> if (prefs.getBoolean("dark", true)) ThemeMode.DARK else ThemeMode.LIGHT // 2.3 key
        }
        return AppSettings(
            lang = prefs.getString("lang", "system") ?: "system",
            theme = theme,
            reduceMotion = prefs.getBoolean("reduce_motion", false),
            haptics = prefs.getBoolean("haptics", true),
            keepScreenOn = prefs.getBoolean("keep_screen_on", true),
            notifyOnFinish = prefs.getBoolean("notify_finish", true),
            compact = prefs.getBoolean("compact", false),
            historyCap = prefs.getInt("history_cap", 200).coerceIn(20, 1000),
            preset = runCatching {
                AppearancePreset.valueOf(prefs.getString("preset", null) ?: "OBSIDIAN")
            }.getOrDefault(AppearancePreset.OBSIDIAN),
            accentArgb = prefs.getInt("accent_argb", 0),
            glass = prefs.tolerantFloat("glass", 0.6f).coerceIn(0f, 1f),
            densityMode = runCatching {
                DensityMode.valueOf(prefs.getString("density_mode", null) ?: "")
            }.getOrElse {
                if (prefs.getBoolean("compact", false)) DensityMode.COMPACT else DensityMode.COMFORTABLE
            },
            radiusScale = prefs.tolerantFloat("radius_scale", 1.0f).coerceIn(0.5f, 1.5f),
            onboarded = prefs.getBoolean("onboarded", false),
        )
    }

    /**
     * Reads a float preference without ever throwing. A backup restored by older
     * versions can hold the value as an Int (org.json prints a whole-number float
     * as "1", and the importer stored it with putInt); SharedPreferences.getFloat
     * then throws ClassCastException — which used to crash every launch, because
     * settings load in Application.onCreate. Reading via getAll heals installs
     * that are already corrupted.
     */
    private fun SharedPreferences.tolerantFloat(key: String, def: Float): Float =
        when (val v = all[key]) { is Number -> v.toFloat(); else -> def }

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_settings.value)
        prefs.edit {
            putString("lang", s.lang)
            putString("theme_mode", s.theme.name.lowercase())
            putBoolean("dark", s.theme != ThemeMode.LIGHT)
            putBoolean("reduce_motion", s.reduceMotion)
            putBoolean("haptics", s.haptics)
            putBoolean("keep_screen_on", s.keepScreenOn)
            putBoolean("notify_finish", s.notifyOnFinish)
            putBoolean("compact", s.compact || s.densityMode == DensityMode.COMPACT)
            putInt("history_cap", s.historyCap)
            putString("preset", s.preset.name)
            putInt("accent_argb", s.accentArgb)
            putFloat("glass", s.glass)
            putString("density_mode", s.densityMode.name)
            putFloat("radius_scale", s.radiusScale)
            putBoolean("onboarded", s.onboarded)
        }
        _settings.value = s
    }

    fun dnsForm() = DnsForm(
        servers = prefs.getString("dns_servers", "1.1.1.1\n8.8.8.8\n9.9.9.9") ?: "",
        domain = prefs.getString("dns_domain", "example.com") ?: "example.com",
        qtype = prefs.getString("dns_qtype", "Auto") ?: "Auto",
        protocol = prefs.getString("dns_protocol", "Auto") ?: "Auto",
        attempts = prefs.getString("dns_attempts", "5") ?: "5",
        timeout = prefs.getString("dns_timeout", "3") ?: "3",
        dohHost = prefs.getString("dns_doh", "cloudflare-dns.com") ?: "cloudflare-dns.com",
        workers = prefs.getString("dns_workers", "24") ?: "24",
        tunnel = prefs.getString("dns_tunnel", "") ?: "",
        port = prefs.getString("dns_port", "53") ?: "53",
        shuffle = prefs.getBoolean("dns_shuffle", true),
        nearby = prefs.getBoolean("dns_nearby", false),
        e2e = prefs.getBoolean("dns_e2e", false),
        e2eTimeoutMs = prefs.getString("dns_e2e_timeout", "4000") ?: "4000",
        e2eConcurrency = prefs.getString("dns_e2e_conc", "5") ?: "5",
        ranges = prefs.getString("dns_ranges", "") ?: "",
    )

    fun saveDnsForm(f: DnsForm) = prefs.edit {
        putString("dns_servers", f.servers); putString("dns_domain", f.domain); putString("dns_qtype", f.qtype)
        putString("dns_protocol", f.protocol); putString("dns_attempts", f.attempts); putString("dns_timeout", f.timeout)
        putString("dns_doh", f.dohHost); putString("dns_workers", f.workers); putString("dns_tunnel", f.tunnel)
        putString("dns_port", f.port); putBoolean("dns_shuffle", f.shuffle); putBoolean("dns_nearby", f.nearby)
        putBoolean("dns_e2e", f.e2e); putString("dns_e2e_timeout", f.e2eTimeoutMs); putString("dns_e2e_conc", f.e2eConcurrency)
        putString("dns_ranges", f.ranges)
    }

    fun ipForm() = IpForm(
        methods = prefs.getStringSet("ip_methods", setOf("https")) ?: setOf("https"),
        ports = prefs.getString("ip_ports", "443") ?: "443",
        hostname = prefs.getString("ip_host", "cloudflare.com") ?: "cloudflare.com",
        path = prefs.getString("ip_path", "/") ?: "/",
        preset = prefs.getString("ip_preset", "balanced") ?: "balanced",
        workers = prefs.getString("ip_workers", "30") ?: "30",
        timeout = prefs.getString("ip_timeout", "4") ?: "4",
        attempts = prefs.getString("ip_attempts", "5") ?: "5",
        adaptive = prefs.getBoolean("ip_adaptive", false),
        failFast = prefs.getBoolean("ip_failfast", true),
        sample = prefs.getBoolean("ip_sample", true),
        maxHosts = prefs.getString("ip_max_hosts", "4096") ?: "4096",
        delayMs = prefs.getString("ip_delay", "120") ?: "120",
    )

    fun saveIpForm(f: IpForm) = prefs.edit {
        putStringSet("ip_methods", f.methods); putString("ip_ports", f.ports); putString("ip_host", f.hostname)
        putString("ip_path", f.path); putString("ip_preset", f.preset); putString("ip_workers", f.workers)
        putString("ip_timeout", f.timeout); putString("ip_attempts", f.attempts); putBoolean("ip_adaptive", f.adaptive)
        putBoolean("ip_failfast", f.failFast); putBoolean("ip_sample", f.sample); putString("ip_max_hosts", f.maxHosts)
        putString("ip_delay", f.delayMs)
    }

    /** Raw preference map for backups (only the keys this app owns). */
    fun exportPrefs(): Map<String, Any?> = prefs.all.filterKeys { !it.startsWith("_") }

    fun importPrefs(map: Map<String, Any?>) {
        prefs.edit {
            map.forEach { (k, v) ->
                when (v) {
                    is String -> putString(k, v)
                    is Boolean -> putBoolean(k, v)
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is Float -> putFloat(k, v)
                    is Set<*> -> putStringSet(k, v.filterIsInstance<String>().toSet())
                }
            }
        }
        _settings.value = read()
    }

    companion object {
        const val NAME = "scanon_prefs"

        /** Preference keys that hold floats. The backup importer must keep these as
         * floats even when the JSON value is a whole number (see Backup). */
        val FLOAT_KEYS = setOf("radius_scale", "glass")
    }
}
