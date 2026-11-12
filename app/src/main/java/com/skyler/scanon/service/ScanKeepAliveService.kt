package com.skyler.scanon.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.skyler.scanon.ScanonApplication

/**
 * Keeps the process at foreground priority while a long scan runs with the app in the background, so Android
 * does not kill it half-way (2.3 lost the whole scan when the user switched apps for a minute). The scan itself
 * stays in its ViewModel; this service only holds the ongoing progress notification.
 */
class ScanKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notifier = (application as ScanonApplication).container.notifier
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "ScanON"
        val done = intent?.getLongExtra(EXTRA_DONE, 0) ?: 0
        val total = intent?.getLongExtra(EXTRA_TOTAL, 0) ?: 0
        val n = notifier.progress(title, done, total)
        try {
            ServiceCompat.startForeground(
                this, ScanNotifier.PROGRESS_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (_: Exception) {
            // Foreground start not allowed right now (e.g. app already in background): the scan still runs.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        // Android 15 data-sync time limit: let go gracefully.
        stopSelf()
    }

    companion object {
        private const val ACTION_STOP = "stop"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"

        /** Call once when a scan starts (the app is in the foreground then, so the start is allowed). */
        fun start(context: Context, title: String, total: Long) {
            val i = Intent(context, ScanKeepAliveService::class.java)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_DONE, 0L).putExtra(EXTRA_TOTAL, total)
            try { ContextCompat.startForegroundService(context, i) } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            // Stop THROUGH the service, not with stopService: a scan that ends before
            // the service has run startForeground() (tiny target list, instant cancel)
            // makes a direct stopService race the pending startForegroundService call,
            // and Android kills the app with ForegroundServiceDidNotStartInTimeException.
            // Delivering the "stop" action as a start command queues it behind the
            // start command, so startForeground() always runs first. startService can
            // throw from the background on Android 12+; only then fall back to the
            // direct stopService (+ notification cancel).
            try {
                context.startService(Intent(context, ScanKeepAliveService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
                try { context.stopService(Intent(context, ScanKeepAliveService::class.java)) } catch (_: Exception) {}
                try { NotificationManagerCompat.from(context).cancel(ScanNotifier.PROGRESS_ID) } catch (_: Exception) {}
            }
        }
    }
}
