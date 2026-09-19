package com.orbis.app.earn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.orbis.app.MainActivity
import com.orbis.app.R
import java.util.concurrent.TimeUnit

/**
 * Pays out a focus session that ended while nobody had ORBIS open - which is the
 * point of a focus session.
 *
 * WorkManager may run it a little late under Doze. That only delays the reward;
 * [FocusSession] decides completion from the wall clock, not from when this runs,
 * and the Earn screen claims it on sight if the user gets there first.
 */
class FocusSessionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        FocusSession.init(context)

        val state = FocusSession.state.value
        if (state.broken) {
            // No notification for a broken session. The user knows they opened a
            // feed, and being told about it afterwards is exactly the shaming the
            // dashboard invariant rules out.
            FocusSession.clear(context)
            return Result.success()
        }

        if (!FocusSession.claimIfComplete(context, System.currentTimeMillis())) {
            return Result.success()
        }

        val awarded = EarnRepository.shared(context).award(EarnAction.FOCUS_SESSION)
        notify(context, awarded)
        return Result.success()
    }

    private fun notify(context: Context, awardedMillis: Long) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.earn_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )

        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_GOOD_DEED)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = if (awardedMillis > 0L) {
            context.getString(R.string.focus_done_text, awardedMillis / 60_000L)
        } else {
            context.getString(R.string.focus_done_text_capped)
        }

        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.focus_done_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_orbis_notification)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val WORK_NAME = "orbis_focus_session"
        private const val CHANNEL_ID = "orbis_earn"

        /** 1 is the tunnel's foreground notification and 2 the good-deed prompt. */
        private const val NOTIFICATION_ID = 3
        private const val REQUEST_CODE = 3

        /**
         * Schedules the payout for when the session ends. REPLACE, so starting a new
         * session never leaves an older job to fire at the wrong time.
         */
        fun schedule(context: Context, delayMillis: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FocusSessionWorker>()
                    .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
