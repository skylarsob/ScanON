package com.skyler.scanon.service

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.skyler.scanon.data.SettingsRepository
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reference-counted keep-alive for scans: the foreground service runs while at least one scan (IP, DNS, domain,
 * vault retest) is active, progress is mirrored into the notification (throttled to 1/s) and a "finished"
 * notification is posted when a scan ends while the app is in the background.
 */
class BackgroundWork(
    private val app: Application,
    private val notifier: ScanNotifier,
    private val settings: SettingsRepository,
) {
    private val active = AtomicInteger(0)
    @Volatile private var lastUpdate = 0L

    private fun inForeground(): Boolean = try {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    } catch (_: Exception) { true }

    fun begin(title: String, total: Long) {
        if (active.incrementAndGet() == 1) ScanKeepAliveService.start(app, title, total)
    }

    fun progress(title: String, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 1000) return
        lastUpdate = now
        if (active.get() > 0) notifier.updateProgress(title, done, total)
    }

    fun end(title: String, summary: String) {
        val last = active.decrementAndGet() <= 0
        if (last) {
            active.set(0)
            ScanKeepAliveService.stop(app)
        }
        // Only the LAST scan to finish posts the "finished" notification. Before
        // 2.8.3 every scan posted one, so overlapping scans (e.g. a deep verify
        // after the main scan) spammed a finished alert while another scan was
        // still running.
        if (last && !inForeground() && settings.settings.value.notifyOnFinish) notifier.finished(title, summary)
    }
}
