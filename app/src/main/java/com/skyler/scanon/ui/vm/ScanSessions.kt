package com.skyler.scanon.ui.vm

import com.skyler.scanon.engine.Canceller
import com.skyler.scanon.engine.DnsReport
import com.skyler.scanon.engine.ScanConfig
import com.skyler.scanon.engine.TargetReport
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide scan state (2.8). Until 2.7 every scan lived in its ViewModel:
 * the job ran on viewModelScope and `onCleared()` cancelled it, so closing the
 * app (or Android reclaiming the activity) killed a running scan even though
 * the foreground service kept the process alive.
 *
 * Now the flows, the job and the canceller live here, in the AppContainer —
 * one instance per process. ViewModels only *operate* the session; the scan
 * itself is launched on the application scope, keeps running with the app in
 * the background or closed, and when the user comes back the (possibly brand
 * new) ViewModel re-attaches to the same flows and shows the live progress
 * and results.
 */
class IpScanSession {
    val results = MutableStateFlow<List<TargetReport>>(emptyList())
    val running = MutableStateFlow(false)
    val progress = MutableStateFlow(Progress())
    val notes = MutableStateFlow("")
    var job: Job? = null
    var canceller: Canceller? = null
    var lastConfig: ScanConfig? = null
}

class DnsScanSession {
    val results = MutableStateFlow<List<DnsReport>>(emptyList())
    val running = MutableStateFlow(false)
    val progress = MutableStateFlow(Progress())
    var job: Job? = null
    var canceller: Canceller? = null
}

class DomainScanSession {
    val domain = MutableStateFlow("")
    val rows = MutableStateFlow<List<ResolverRow>>(emptyList())
    val ips = MutableStateFlow<List<TargetReport>>(emptyList())
    val modal = MutableStateFlow<Set<String>>(emptySet())
    val phase = MutableStateFlow("")
    val error = MutableStateFlow("")
    val running = MutableStateFlow(false)
    val progress = MutableStateFlow(Progress())
    val skipped = MutableStateFlow<List<String>>(emptyList())
    var job: Job? = null
    var canceller: Canceller? = null
}
