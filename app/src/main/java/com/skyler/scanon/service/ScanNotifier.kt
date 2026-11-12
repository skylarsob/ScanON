package com.skyler.scanon.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.skyler.scanon.MainActivity
import com.skyler.scanon.R

/** Progress + "scan finished" notifications. Every call is a no-op when the user denied notifications. */
class ScanNotifier(private val context: Context) {

    /** Locale-wrapped context (2.9): notification text follows the in-app language choice like the rest of the UI. */
    private val lc: Context get() = MainActivity.wrapLocale(context)

    fun createChannel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, lc.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        // "Finished" alerts get their own channel at DEFAULT importance: on the old
        // shared LOW channel they never popped up or made a sound, so a finished
        // background scan went unnoticed.
        nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, lc.getString(R.string.notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(title: String, done: Long, total: Long) = NotificationCompat.Builder(lc, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_scan)
        .setContentTitle(title)
        .setContentText(if (total > 0) "$done / $total" else "")
        .setProgress(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), done.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), total <= 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .build()

    /** Updates the ongoing notification in place (no service restart, which is not allowed from the background). */
    fun updateProgress(title: String, done: Long, total: Long) {
        if (!canPost()) return
        try { NotificationManagerCompat.from(context).notify(PROGRESS_ID, progress(title, done, total)) } catch (_: SecurityException) {}
    }

    fun finished(title: String, text: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(lc, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try { NotificationManagerCompat.from(context).notify(DONE_ID, n) } catch (_: SecurityException) {}
    }

    companion object {
        const val CHANNEL = "scans"
        const val DONE_CHANNEL = "scans_done"
        const val PROGRESS_ID = 1
        const val DONE_ID = 2
    }
}
