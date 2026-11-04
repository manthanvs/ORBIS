package com.orbis.app.earn;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.orbis.app.MainActivity;
import com.orbis.app.R;

import java.util.concurrent.TimeUnit;

/**
 * Pays out a focus session that ended while nobody had ORBIS open - which is the
 * point of a focus session.
 *
 * <p>WorkManager may run it a little late under Doze. That only delays the
 * reward; {@link FocusSession} decides completion from the wall clock, not from
 * when this runs, and the Earn screen claims it on sight if the user gets there
 * first.
 *
 * <p>A plain {@code Worker} rather than a coroutine worker: {@code doWork} already
 * runs on a background thread, which is all the ledger write needs.
 */
public class FocusSessionWorker extends Worker {

    private static final String WORK_NAME = "orbis_focus_session";
    private static final String CHANNEL_ID = "orbis_earn";

    /** 1 is the tunnel's foreground notification. */
    private static final int NOTIFICATION_ID = 3;

    private static final int REQUEST_CODE = 3;

    public FocusSessionWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        FocusSession.init(context);

        if (FocusSession.state().get().getBroken()) {
            // No notification for a broken session. The user knows they opened a
            // feed, and being told about it afterwards is exactly the shaming the
            // dashboard invariant rules out.
            FocusSession.clear(context);
            return Result.success();
        }

        if (!FocusSession.claimIfComplete(context, System.currentTimeMillis())) {
            return Result.success();
        }

        long awarded = EarnRepository.shared(context).award(EarnAction.FOCUS_SESSION);
        notifyDone(context, awarded);
        return Result.success();
    }

    private void notifyDone(Context context, long awardedMillis) {
        boolean granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            return;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(
                new NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.earn_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT));

        PendingIntent open =
                PendingIntent.getActivity(
                        context,
                        REQUEST_CODE,
                        new Intent(context, MainActivity.class)
                                .setAction(MainActivity.ACTION_EARN)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String text =
                awardedMillis > 0L
                        ? context.getString(R.string.focus_done_text, awardedMillis / 60_000L)
                        : context.getString(R.string.focus_done_text_capped);

        manager.notify(
                NOTIFICATION_ID,
                new Notification.Builder(context, CHANNEL_ID)
                        .setContentTitle(context.getString(R.string.focus_done_title))
                        .setContentText(text)
                        .setSmallIcon(R.drawable.ic_orbis_notification)
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build());
    }

    /**
     * Schedules the payout for when the session ends. REPLACE, so starting a new
     * session never leaves an older job to fire at the wrong time.
     */
    public static void schedule(Context context, long delayMillis) {
        WorkManager.getInstance(context)
                .enqueueUniqueWork(
                        WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        new OneTimeWorkRequest.Builder(FocusSessionWorker.class)
                                .setInitialDelay(
                                        Math.max(delayMillis, 0L), TimeUnit.MILLISECONDS)
                                .build());
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}
