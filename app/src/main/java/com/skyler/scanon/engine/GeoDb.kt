package com.skyler.scanon.engine

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.nio.channels.FileChannel

data class GeoInfo(
    val countryCode: String?,
    val countryName: String?,
    val city: String?,
    val asn: Long?,
    val org: String?,
) {
    val flag: String
        get() {
            val cc = countryCode ?: return ""
            if (cc.length != 2 || !cc.all { it.isLetter() }) return ""
            val sb = StringBuilder()
            sb.appendCodePoint(Character.toUpperCase(cc[0]) - 'A' + 0x1F1E6)
            sb.appendCodePoint(Character.toUpperCase(cc[1]) - 'A' + 0x1F1E6)
            return sb.toString()
        }

    val asLabel: String?
        get() = when {
            asn != null && asn > 0 && org != null -> "AS$asn $org"
            asn != null && asn > 0 -> "AS$asn"
            else -> org
        }

    val shortLabel: String
        get() = listOfNotNull(flag.ifEmpty { null }, countryCode, asLabel).joinToString(" ").trim()

    /** Text used by the result search box: country, city, ASN and organisation. */
    val searchText: String
        get() = listOfNotNull(countryCode, countryName, city, asn?.let { "AS$it" }, org).joinToString(" ")
}

/**
 * Offline GeoLite2 City + ASN lookups.
 *
 * Fixes over 2.3: the AssetFileDescriptor is closed after mapping, a failure of one database no longer disables
 * the other, lookup errors are caught (corrupt data used to crash the composition), results are LRU-cached,
 * and readiness is observable as a StateFlow.
 */
object GeoDb {
    enum class State { LOADING, READY, PARTIAL, FAILED }

    @Volatile private var cityDb: MmdbReader? = null
    @Volatile private var asnDb: MmdbReader? = null
    private val _state = MutableStateFlow(State.LOADING)
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile var error: String? = null
        private set
    val ready: Boolean get() = _state.value == State.READY || _state.value == State.PARTIAL

    private val cache = LruCache<String, Any>(2048)
    private val NONE = Any()

    @Synchronized
    fun init(context: Context) {
        if (cityDb != null && asnDb != null) return
        val errors = ArrayList<String>()
        if (cityDb == null) cityDb = load(context, "GeoLite2-City.mmdb", errors)
        if (asnDb == null) asnDb = load(context, "GeoLite2-ASN.mmdb", errors)
        error = errors.joinToString("; ").ifEmpty { null }
        _state.value = when {
            cityDb != null && asnDb != null -> State.READY
            cityDb != null || asnDb != null -> State.PARTIAL
            else -> State.FAILED
        }
    }

    private fun load(context: Context, name: String, errors: MutableList<String>): MmdbReader? = try {
        context.assets.openFd(name).use { afd ->
            FileInputStream(afd.fileDescriptor).channel.use { ch ->
                MmdbReader.open(ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength))
            }
        }
    } catch (e: Exception) {
        errors += "$name: ${e.message ?: e.javaClass.simpleName}"
        null
    }

    fun lookup(ip: String): GeoInfo? {
        if (!ready) return null
        cache.get(ip)?.let { return if (it === NONE) null else it as GeoInfo }
        val info = try { compute(ip) } catch (_: Exception) { null }
        cache.put(ip, info ?: NONE)
        return info
    }

    private fun compute(ip: String): GeoInfo? {
        val city = cityDb?.lookup(ip)
        val asn = asnDb?.lookup(ip)
        if (city == null && asn == null) return null
        val country = city?.get("country") as? Map<*, *>
        val registered = city?.get("registered_country") as? Map<*, *>
        fun names(m: Map<*, *>?): String? = ((m?.get("names") as? Map<*, *>)?.get("en")) as? String
        return GeoInfo(
            countryCode = country?.get("iso_code") as? String ?: registered?.get("iso_code") as? String,
            countryName = names(country) ?: names(registered),
            city = names(city?.get("city") as? Map<*, *>),
            asn = (asn?.get("autonomous_system_number") as? Number)?.toLong(),
            org = asn?.get("autonomous_system_organization") as? String,
        )
    }
}
