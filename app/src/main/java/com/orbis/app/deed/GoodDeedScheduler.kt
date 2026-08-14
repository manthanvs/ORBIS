package com.orbis.app.deed

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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.orbis.app.MainActivity
import com.orbis.app.R
import com.orbis.app.data.DatabaseProvider
import java.util.concurrent.TimeUnit

/**
 * Periodically invites the user to do something small and real.
 *
 * The prompt is skipped when a deed is already logged for today: nagging someone
 * who has done the thing is the fastest way to get the app muted.
 */
class GoodDeedWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = GoodDeedRepository(
            DatabaseProvider.get(applicationContext).goodDeedDao()
        )

        // One read of the log for both answers, rather than one each.
        val summary = repository.summary()
        if (summary.doneToday) return Result.success()

        notify(applicationContext, summary.streak)
        return Result.success()
    }

    private fun notify(context: Context, streak: Int) {
        // On Android 13+ this is a runtime permission; posting without it is a
        // silent no-op, so check rather than pretend it worked.
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.deed_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { setShowBadge(true) }
        )

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_GOOD_DEED)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = if (streak > 0) {
            context.getString(R.string.deed_notification_text_streak, streak)
        } else {
            context.getString(R.string.deed_notification_text)
        }

        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.deed_notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_orbis_notification)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL_ID = "orbis_good_deed"
        private const val NOTIFICATION_ID = 2
    }
}

object GoodDeedScheduler {

    private const val WORK_NAME = "orbis_good_deed_prompt"

    /**
     * WorkManager's minimum period is 15 minutes; a daily prompt is the intent, and
     * the worker itself skips days already completed.
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<GoodDeedWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(2, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Fires the prompt immediately, for testing the loop without waiting a day. */
    fun promptNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            androidx.work.OneTimeWorkRequestBuilder<GoodDeedWorker>().build()
        )
    }
}
