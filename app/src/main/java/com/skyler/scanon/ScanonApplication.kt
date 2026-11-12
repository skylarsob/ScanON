package com.skyler.scanon

import android.app.Application
import com.skyler.scanon.data.BackupManager
import com.skyler.scanon.data.GroupsStore
import com.skyler.scanon.data.HistoryStore
import com.skyler.scanon.data.SettingsRepository
import com.skyler.scanon.data.VaultStore
import com.skyler.scanon.engine.GeoDb
import com.skyler.scanon.service.ScanNotifier
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Light-weight dependency container: one instance of every store, created once per process. */
class AppContainer(private val app: Application) {
    val settings = SettingsRepository(app)

    @Volatile private var resCache: Pair<String, android.content.res.Resources>? = null

    /**
     * Resources resolved in the language the user actually picked (2.9). The
     * Application context never passes through the Activity's locale wrap, so
     * ViewModel strings (snackbars, history titles, notification text) used to
     * follow the raw framework locale instead of the in-app choice and could
     * disagree with the visible UI. Cached per language setting; the getter
     * in each ViewModel re-reads this, so a language switch is picked up
     * without stale strings surviving in a retained ViewModel.
     */
    fun localizedResources(): android.content.res.Resources {
        val lang = settings.settings.value.lang
        resCache?.let { if (it.first == lang) return it.second }
        val r = MainActivity.wrapLocale(app).resources
        resCache = lang to r
        return r
    }
    val history = HistoryStore(app.filesDir, settings.settings.value.historyCap)
    val vault = VaultStore(app.filesDir)
    val groups = GroupsStore(app.filesDir)
    val backup = BackupManager(vault, groups, history, settings)
    val notifier = ScanNotifier(app)
    val work = com.skyler.scanon.service.BackgroundWork(app, notifier, settings)

    // Process-wide scan sessions (2.8): scans live here, not in ViewModels, so they keep
    // running in the background and their state survives the UI being closed and reopened.
    val ipSession = com.skyler.scanon.ui.vm.IpScanSession()
    val dnsSession = com.skyler.scanon.ui.vm.DnsScanSession()
    val domainSession = com.skyler.scanon.ui.vm.DomainScanSession()

    /** Process-wide scope for work that must not die with a screen (store warm-up, GeoIP loading). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        android.util.Log.e("ScanON", "background task failed", e)
    })

    /** Shared one-shot handoff of text from other apps ("Share to ScanON") into the IP scanner. */
    val incomingTargets = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

class ScanonApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch(Dispatchers.IO) { GeoDb.init(this@ScanonApplication) }
        container.appScope.launch(Dispatchers.IO) {
            container.history.load(); container.vault.load(); container.groups.load()
        }
        container.notifier.createChannel()
    }
}
