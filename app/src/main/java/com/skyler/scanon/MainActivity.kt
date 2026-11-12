@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.skyler.scanon

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skyler.scanon.data.SettingsRepository
import com.skyler.scanon.ui.components.AnimatedBackground
import com.skyler.scanon.ui.components.AppIcon
import com.skyler.scanon.ui.components.AppIconKind
import com.skyler.scanon.ui.components.LocalHapticsOn
import com.skyler.scanon.ui.components.LocalSnack
import com.skyler.scanon.ui.screens.DiagnosticsScreen
import com.skyler.scanon.ui.screens.DnsScanScreen
import com.skyler.scanon.ui.screens.DomainScanScreen
import com.skyler.scanon.ui.screens.GroupsScreen
import com.skyler.scanon.ui.screens.GuideScreen
import com.skyler.scanon.ui.screens.HistoryScreen
import com.skyler.scanon.ui.screens.IpScanScreen
import com.skyler.scanon.ui.screens.SettingsScreen
import com.skyler.scanon.ui.screens.VaultScreen
import com.skyler.scanon.ui.theme.ScanonTheme
import com.skyler.scanon.ui.vm.DnsScanViewModel
import com.skyler.scanon.ui.vm.DomainScanViewModel
import com.skyler.scanon.ui.vm.IpScanViewModel
import com.skyler.scanon.ui.vm.VaultViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** Tab requested from outside (e.g. text shared into the app opens the IP scanner). */
    private val requestedTab = MutableStateFlow<Int?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as ScanonApplication).container
        handleIntent(intent)
        val animationsOff = try {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) { false }
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val persian = resources.configuration.locales[0].language == "fa"
            ScanonTheme(settings, persian, animationsOff) {
                CompositionLocalProvider(LocalHapticsOn provides settings.haptics) {
                    ScanonRoot(
                        requestedTab = requestedTab,
                        keepScreenOn = settings.keepScreenOn,
                        onboarded = settings.onboarded,
                        onLanguageChanged = { applyLanguage(it) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            (application as ScanonApplication).container.incomingTargets.value = text.take(2_000_000)
            requestedTab.value = 0
        }
    }

    fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Persian/English/system. On Android 13+ the choice is also registered with the system per-app language setting. */
    private fun applyLanguage(code: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                getSystemService(LocaleManager::class.java)?.applicationLocales =
                    if (code == "system") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
            } catch (_: Exception) {}
        }
        recreate()
    }

    companion object {
        /**
         * The locale the app must actually use (2.9 language rework). A manual
         * choice wins; "system" resolves from the DEVICE's locale via
         * [android.content.res.Resources.getSystem] — never from the app's own
         * current configuration. The old code returned the base context
         * untouched for "system" and trusted the framework/per-app-locale
         * state, which on the test phone delivered Persian strings with an
         * LTR layout direction after Persian had been picked manually (the
         * reported "System flips it" bug). Unsupported device languages fall
         * back to English.
         */
        fun effectiveLocale(lang: String): Locale = when (lang) {
            "fa", "en" -> Locale.forLanguageTag(lang)
            else -> {
                val sys = android.content.res.Resources.getSystem().configuration.locales[0]?.language
                if (sys == "fa" || sys == "en") Locale.forLanguageTag(sys) else Locale.ENGLISH
            }
        }

        /** Always wraps — including for "system" — so locale AND layout direction are deterministic. */
        fun wrapLocale(base: Context): Context {
            val lang = base.getSharedPreferences(SettingsRepository.NAME, Context.MODE_PRIVATE).getString("lang", "system") ?: "system"
            val locale = effectiveLocale(lang)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            return base.createConfigurationContext(config)
        }
    }
}

private data class Tab(val icon: AppIconKind, val label: Int)

private val TABS = listOf(
    Tab(AppIconKind.Globe, R.string.nav_ip), Tab(AppIconKind.Dns, R.string.nav_dns), Tab(AppIconKind.Target, R.string.nav_domain),
    Tab(AppIconKind.Activity, R.string.nav_diag), Tab(AppIconKind.Star, R.string.nav_vault),
)

@Composable
fun ScanonRoot(requestedTab: MutableStateFlow<Int?>, keepScreenOn: Boolean, onboarded: Boolean, onLanguageChanged: (String) -> Unit) {
    // First-run introduction (2.8): until it is finished or skipped, it replaces the
    // whole UI. It can be replayed from Settings → About.
    if (!onboarded) {
        Box(Modifier.fillMaxSize()) {
            AnimatedBackground(boost = false)
            com.skyler.scanon.ui.screens.OnboardingScreen(onLanguageChanged)
        }
        return
    }
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity
    // Tab and overlay survive rotation and process death (2.3 used plain remember: back to tab 1 on recreate).
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    val snackHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snack: (String) -> Unit = remember { { msg -> scope.launch { snackHost.currentSnackbarData?.dismiss(); snackHost.showSnackbar(msg) } } }

    val ipVm: IpScanViewModel = viewModel()
    val dnsVm: DnsScanViewModel = viewModel()
    val domainVm: DomainScanViewModel = viewModel()
    val vaultVm: VaultViewModel = viewModel()
    val ipRunning by ipVm.running.collectAsStateWithLifecycle()
    val dnsRunning by dnsVm.running.collectAsStateWithLifecycle()
    val domainRunning by domainVm.running.collectAsStateWithLifecycle()
    val anyRunning = ipRunning || dnsRunning || domainRunning

    // Keep the screen on only while something is scanning (and the user allows it).
    DisposableEffect(anyRunning, keepScreenOn) {
        activity?.setKeepScreenOn(anyRunning && keepScreenOn)
        onDispose { activity?.setKeepScreenOn(false) }
    }
    LaunchedEffect(vaultVm) { vaultVm.events.collect { snack(it) } }
    val req by requestedTab.collectAsStateWithLifecycle()
    LaunchedEffect(req) { req?.let { tab = it; overlay = null; requestedTab.value = null } }

    // Ask for the notification permission once, the first time a scan starts (Android 13+).
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(anyRunning) {
        if (anyRunning && Build.VERSION.SDK_INT >= 33 && activity != null) {
            val prefs = activity.getSharedPreferences(SettingsRepository.NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("_asked_notif", false)) {
                prefs.edit { putBoolean("_asked_notif", true) }
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // System back closes an overlay page instead of leaving the app; on a non-first tab it returns to the first.
    BackHandler(enabled = overlay != null || tab != 0) { if (overlay != null) overlay = null else tab = 0 }

    CompositionLocalProvider(LocalSnack provides snack) {
        Box(Modifier.fillMaxSize()) {
            AnimatedBackground(boost = anyRunning)
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackHost) },
                topBar = {
                    val onBar = MaterialTheme.colorScheme.onPrimaryContainer
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                com.skyler.scanon.ui.components.SpinningRadar(onBar, size = 22.dp, durationMillis = 9000)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when (overlay) {
                                        "groups" -> stringResource(R.string.groups_title)
                                        "guide" -> stringResource(R.string.guide_title)
                                        "history" -> stringResource(R.string.nav_history)
                                        "settings" -> stringResource(R.string.nav_settings)
                                        else -> stringResource(R.string.app_name)
                                    },
                                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        navigationIcon = {
                            if (overlay != null) IconButton(onClick = { overlay = null }) { AppIcon(AppIconKind.Back, onBar) }
                        },
                        actions = {
                            if (overlay == null) {
                                IconButton(onClick = { overlay = "history" }) { AppIcon(AppIconKind.Clock, onBar) }
                                IconButton(onClick = { overlay = "groups" }) { AppIcon(AppIconKind.Folder, onBar) }
                                IconButton(onClick = { overlay = "guide" }) { AppIcon(AppIconKind.Info, onBar) }
                                IconButton(onClick = { overlay = "settings" }) { AppIcon(AppIconKind.Sliders, onBar) }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
                            titleContentColor = onBar,
                        ),
                    )
                },
                bottomBar = {
                    if (overlay == null) Column {
                        // Gradient hairline above the bar (2.6).
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(
                            Color.Transparent, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f), Color.Transparent))))
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                        TABS.forEachIndexed { i, t ->
                            val busy = (i == 0 && ipRunning) || (i == 1 && dnsRunning) || (i == 2 && domainRunning)
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = {
                                    // Selected icon springs up a size (2.6).
                                    val selScale by animateFloatAsState(
                                        if (tab == i) 1.18f else 1f,
                                        androidx.compose.animation.core.spring(stiffness = 420f, dampingRatio = 0.55f),
                                        label = "navIcon")
                                    AppIcon(t.icon, if (tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        Modifier.graphicsLayer { scaleX = selScale; scaleY = selScale },
                                        filled = t.icon == AppIconKind.Star && tab == i)
                                },
                                // A dot in the label tells you a scan is still running on that tab.
                                label = { Text((if (busy) "• " else "") + stringResource(t.label), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    // Overlay pages slide up and fade in over the tabs (2.6).
                    androidx.compose.animation.AnimatedContent(
                        targetState = overlay,
                        transitionSpec = {
                            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
                                androidx.compose.animation.slideInVertically(
                                    androidx.compose.animation.core.spring<IntOffset>(stiffness = 300f, dampingRatio = 0.85f)) { it / 10 }) togetherWith
                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(130))
                        },
                        label = "overlay",
                    ) { ov ->
                        when (ov) {
                            "groups" -> GroupsScreen()
                            "guide" -> GuideScreen()
                            "history" -> HistoryScreen()
                            "settings" -> SettingsScreen(onLanguageChanged)
                            else -> {
                                // Directional tab slide (2.6): the new tab
                                // slides in from the side it sits on, a full
                                // third of the width, with a spring settle and
                                // a slight scale. 2.5 slid only width/7, which
                                // read as a plain fade.
                                val floatSpring = androidx.compose.animation.core.spring<Float>(
                                    stiffness = 300f, dampingRatio = 0.82f)
                                val slideSpring = androidx.compose.animation.core.spring<IntOffset>(
                                    stiffness = 300f, dampingRatio = 0.82f)
                                androidx.compose.animation.AnimatedContent(
                                    targetState = tab,
                                    transitionSpec = {
                                        val dir = if (targetState > initialState) 1 else -1
                                        (androidx.compose.animation.fadeIn(floatSpring) +
                                            androidx.compose.animation.slideInHorizontally(slideSpring) { dir * it / 3 } +
                                            androidx.compose.animation.scaleIn(floatSpring, initialScale = 0.985f)) togetherWith
                                            (androidx.compose.animation.fadeOut(
                                                androidx.compose.animation.core.tween(120)) +
                                                androidx.compose.animation.slideOutHorizontally(slideSpring) { -dir * it / 3 } +
                                                androidx.compose.animation.scaleOut(
                                                    androidx.compose.animation.core.tween(120), targetScale = 0.985f))
                                    },
                                    label = "tab",
                                ) { t ->
                                    when (t) {
                                        0 -> IpScanScreen(ipVm)
                                        1 -> DnsScanScreen(dnsVm)
                                        2 -> DomainScanScreen(domainVm)
                                        3 -> DiagnosticsScreen()
                                        else -> VaultScreen(vaultVm)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
