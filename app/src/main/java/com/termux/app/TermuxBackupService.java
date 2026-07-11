package com.termux.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.BackupDialogActivity;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service that runs a Termux data backup or restore.
 *
 * <p>The (potentially long, multi-minute) tar operation always runs here so it is not killed
 * when the app/activity is destroyed. The UI may observe it in two ways:
 * <ul>
 *   <li><b>Dialog mode</b> (default): a progress dialog in the preferences screen polls
 *       {@link #getProgressCopied()}/{@link #getProgressTotal()}/{@link #isFinished()} while the
 *       app stays on screen.</li>
 *   <li><b>Background mode</b>: once {@link #enterBackground()} is called (by tapping the
 *       dialog's "Background" button, or automatically when the app is minimized), the service
 *       becomes a foreground service and shows a persistent notification with progress. This keeps
 *       the operation alive and visible after the app is backgrounded / the screen is locked.</li>
 * </ul>
 *
 * <p>The SAF document URI is passed via {@link Intent#setData(Uri)} together with
 * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} / {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION}.
 * Those URI grants are held by the system for our UID and survive the activity being destroyed,
 * which is exactly what makes the operation reliable after the app is minimized.</p>
 */
public final class TermuxBackupService extends Service {

    private static final String LOG_TAG = "TermuxBackupService";

    /** Intent action to perform a backup (write the tar.gz to the given URI). */
    public static final String ACTION_BACKUP = "com.termux.app.TermuxBackupService.ACTION_BACKUP";
    /** Intent action to perform a restore (read the tar.gz from the given URI). */
    public static final String ACTION_RESTORE = "com.termux.app.TermuxBackupService.ACTION_RESTORE";
    /** Intent action to cancel the running operation (sent from the notification's cancel button). */
    public static final String ACTION_CANCEL = "com.termux.app.TermuxBackupService.ACTION_CANCEL";

    private static final String EXTRA_ESTIMATED_SIZE = "com.termux.app.TermuxBackupService.extra_estimated_size";

    /** Live instance, so the UI dialog can poll progress without binding. */
    private static volatile TermuxBackupService sInstance;
    /**
     * Result of the last finished operation, retained after {@link #onDestroy()} so the UI can
     * read it even if it polls after the service has already torn itself down. Without this a
     * successful-looking poll (svc == null) would force a false "success" when the operation
     * actually failed.
     */
    private static volatile Error sLastResult;

    private volatile boolean mFinished = false;
    private volatile boolean mInForeground = false;
    /** True only after startForeground() has actually succeeded — guards the matching stopForeground(). */
    private volatile boolean mStartedForeground = false;
    private volatile boolean mIsRestore = false;
    private volatile long mProgressCopied = 0;
    private volatile long mProgressTotal = 0;
    private final AtomicReference<Error> mResult = new AtomicReference<>();
    /** Ensures showResult() runs exactly once across worker / enterBackground races. */
    private final AtomicBoolean mResultShown = new AtomicBoolean(false);
    /** Set when the user cancels; the worker checks it and kills the tar process. */
    private final AtomicBoolean mCancelled = new AtomicBoolean(false);
    /** Bumped at the start of every operation; the auto-dismiss timer only cancels "its own" notification. */
    private final AtomicInteger mEpoch = new AtomicInteger();
    private PowerManager.WakeLock mWakeLock;
    private Thread mWorker;

    private Handler mMainHandler;
    private volatile Runnable mAutoDismissRunnable;

    // ------------------------------------------------------------------
    // Public entry points used by the preferences fragment
    // ------------------------------------------------------------------

    public static void startBackup(Context context, Uri uri, long estimatedSize) {
        Intent intent = new Intent(context, TermuxBackupService.class)
            .setAction(ACTION_BACKUP)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .putExtra(EXTRA_ESTIMATED_SIZE, estimatedSize);
        start(context, intent);
    }

    public static void startRestore(Context context, Uri uri, long estimatedSize) {
        Intent intent = new Intent(context, TermuxBackupService.class)
            .setAction(ACTION_RESTORE)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(EXTRA_ESTIMATED_SIZE, estimatedSize);
        start(context, intent);
    }

    private static void start(Context context, Intent intent) {
        // Started as a normal service (not foreground) so we are NOT forced to call
        // startForeground within ~5s. The UI stays on screen with a dialog; we only enter
        // foreground mode when the user taps "Background" or the app is minimized.
        context.startService(intent);
    }

    /** @return the running service instance, or null if no operation is in progress. */
    @Nullable
    public static TermuxBackupService getInstance() {
        return sInstance;
    }

    /** @return the result of the most recently finished operation (survives onDestroy). */
    @Nullable
    public static Error getLastResult() {
        return sLastResult;
    }

    /** @return true once the operation has been cancelled by the user. */
    public boolean isCancelled() { return mCancelled.get(); }

    /** Cancel the running operation: the worker observes this and kills the tar process. */
    public void cancelOperation() {
        mCancelled.set(true);
    }

    // ---- progress observers for the dialog ----

    public boolean isInForeground() { return mInForeground; }
    public boolean isRestore() { return mIsRestore; }

    /**
     * Called when the user taps the notification and the app re-opens the progress dialog.
     * Leaves foreground mode (dropping the notification and cancelling its auto-dismiss timer) so
     * the dialog becomes the single source of truth again — exactly as when the operation was
     * started. If the operation already finished, nothing to do (the result notification is gone
     * or will be replaced by the fragment's result handling).
     */
    public void returnToDialog() {
        if (!mInForeground) return;
        mInForeground = false;
        mStartedForeground = false;
        // Cancel any pending auto-dismiss so it cannot stopSelf() under the reopened dialog.
        final int epoch = mEpoch.incrementAndGet();
        if (mMainHandler != null) {
            mMainHandler.removeCallbacks(mAutoDismissRunnable);
        }
        mAutoDismissRunnable = null;
        NotificationManager nm = NotificationUtils.getNotificationManager(this);
        if (nm != null) nm.cancel(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
    public boolean isFinished() { return mFinished; }
    public long getProgressCopied() { return mProgressCopied; }
    public long getProgressTotal() { return mProgressTotal; }
    @Nullable
    public Error getResult() { return mResult.get(); }

    /**
     * Switch this service into foreground (notification) mode. Safe to call multiple times.
     * Typically invoked from the dialog's "Background" button or when the app is minimized.
     */
    public void enterBackground() {
        if (mInForeground) return;
        // If the operation already finished (e.g. user minimized at the very end, or pressed
        // Back while the dialog was still up), we still enter foreground mode so the result
        // notification can pop heads-up AND auto-dismiss (the timer stops the service) — exactly
        // like any other background completion. We just skip the live progress notification.
        mInForeground = true;
        setupNotificationChannels();
        Notification notification = mFinished
            ? null
            : buildProgressNotification(mIsRestore, mProgressCopied, mProgressTotal);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID,
                    notification != null ? notification
                        : buildResultNotification(mIsRestore, mResult.get()),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID,
                    notification != null ? notification : buildResultNotification(mIsRestore, mResult.get()));
            }
            // Only flag "started" AFTER startForeground() actually succeeded, so the
            // worker-thread showResult() cannot race a stopForeground() before we are foreground.
            mStartedForeground = true;
            if (mFinished) {
                // Already done: surface the result immediately (head-up + auto-dismiss timer).
                showResult(mIsRestore, mResult.get());
            }
        } catch (Exception e) {
            // Foreground promotion refused (e.g. not in a permitted state on API 31+).
            // Stay in dialog-less mode; the operation still runs, just without a notification.
            Logger.logStackTraceWithMessage(LOG_TAG, "startForeground failed", e);
            mInForeground = false;
        }
    }

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // The cancel PendingIntent sets ONLY the action (no data URI), so handle it BEFORE the
        // data/action presence guard below — otherwise it would be treated as a malformed start
        // and the service would stopSelf() without ever cancelling the running operation.
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelOperation();
            // If no live operation exists (worker already finished or never started), the flag
            // alone won't produce any result notification or stopSelf. Clean up the empty service
            // here so it doesn't linger with a stale notification.
            if (mWorker == null || !mWorker.isAlive()) {
                NotificationManager nm = NotificationUtils.getNotificationManager(this);
                if (nm != null) nm.cancel(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID);
                if (mStartedForeground) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                    } else {
                        stopForeground(true);
                    }
                    mStartedForeground = false;
                }
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (intent == null || intent.getAction() == null || intent.getData() == null) {
            Logger.logError(LOG_TAG, "TermuxBackupService started with missing action/uri");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (mWorker != null && mWorker.isAlive()) {
            // Already running an operation; ignore the duplicate start.
            return START_NOT_STICKY;
        }

        mEpoch.incrementAndGet(); // new operation => invalidate any pending auto-dismiss timer
        mIsRestore = ACTION_RESTORE.equals(intent.getAction());
        final boolean isRestore = mIsRestore;
        final Uri uri = intent.getData();
        final long estimatedSize = intent.getLongExtra(EXTRA_ESTIMATED_SIZE, 0);
        final AtomicReference<Error> result = new AtomicReference<>();

        sInstance = this;
        sLastResult = null;
        mMainHandler = new Handler(Looper.getMainLooper());
        mWorker = new Thread(() -> {
            try {
                if (isRestore) {
                    runRestore(uri, estimatedSize, result);
                } else {
                    runBackup(uri, estimatedSize, result);
                }
            } finally {
                // Publish the result BEFORE flipping the finished flag so any reader that sees
                // mFinished == true also sees the correctly published result (safe-publication).
                mResult.set(result.get());
                sLastResult = result.get();
                mFinished = true;
                releaseWakeLock();
                // In background mode, surface the result as a heads-up notification (which
                // auto-dismisses and stops the service after 8s). In dialog mode we do NOT
                // post stopSelf here — the fragment shows the bottom Toast (as in the upstream
                // commit) and then stops the service; that also avoids racing enterBackground()'s
                // auto-dismiss timer. The service simply stays alive until the fragment (or
                // enterBackground on minimize / Back) stops it.
                if (mInForeground) {
                    showResult(isRestore, result.get());
                } else if (mCancelled.get()) {
                    // Dialog mode + cancelled: the controller already dismissed the dialog and
                    // showed a toast, but nobody will call finish() / stopService for us after
                    // the worker finishes because the poll loop is dead. Stop the now-idle
                    // service ourselves once cleanup (rollback/delete partial) is complete.
                    stopSelf();
                }
            }
        }, "TermuxBackupWorker");
        try {
            mWorker.start();
            acquireWakeLock(); // acquire only after a successful start (avoid a leak on start() failure)
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start backup worker", t);
            releaseWakeLock();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        if (mAutoDismissRunnable != null && mMainHandler != null) {
            mMainHandler.removeCallbacks(mAutoDismissRunnable);
        }
        // Drop any foreground state so we don't leave a dangling notification / foreground rank
        // when the service is destroyed while still in foreground mode.
        if (mStartedForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
            } catch (Exception ignored) { }
            mStartedForeground = false;
            mInForeground = false;
        }
        releaseWakeLock();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------
    // Core operations
    // ------------------------------------------------------------------

    private void runBackup(Uri uri, long estimatedSize, AtomicReference<Error> out) {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) {
                out.set(new Error("Failed to open output file"));
                return;
            }
            final Error[] holder = new Error[1];
            TermuxBackupUtils.backup(this, os, error -> holder[0] = error,
                (copied, total) -> publishProgress(false, copied, total > 0 ? total : estimatedSize),
                mCancelled);
            if (holder[0] != null) {
                // A cancelled or failed backup must not leave a partial destination file behind.
                try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                out.set(holder[0]);
            }
        } catch (IOException | RuntimeException e) {
            try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            out.set(new Error(e.getMessage(), e));
        }
    }

    private void runRestore(Uri uri, long estimatedSize, AtomicReference<Error> out) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                out.set(new Error("Failed to open input file"));
                return;
            }
            final Error[] holder = new Error[1];
            TermuxBackupUtils.restore(this, is, error -> holder[0] = error,
                (copied, total) -> publishProgress(true, copied, total > 0 ? total : estimatedSize),
                mCancelled);
            out.set(holder[0]);
        } catch (IOException | RuntimeException e) {
            out.set(new Error(e.getMessage(), e));
        }
    }

    // ------------------------------------------------------------------
    // Notification handling
    // ------------------------------------------------------------------

    private void setupNotificationChannels() {
        // Two channels: progress is SILENT/LOW (no heads-up spam during updates),
        // the result channel is HIGH so the completion notification actually pops heads-up.
        NotificationUtils.setupNotificationChannel(this,
            TermuxConstants.TERMUX_BACKUP_PROGRESS_NOTIFICATION_CHANNEL_ID,
            getString(R.string.backup_service_channel_name),
            NotificationManager.IMPORTANCE_LOW);
        NotificationUtils.setupNotificationChannel(this,
            TermuxConstants.TERMUX_BACKUP_RESULT_NOTIFICATION_CHANNEL_ID,
            getString(R.string.backup_service_channel_name),
            NotificationManager.IMPORTANCE_HIGH);
    }

    private PendingIntent contentIntent() {
        Intent launch = new Intent(this, BackupDialogActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private PendingIntent cancelIntent() {
        Intent cancel = new Intent(this, TermuxBackupService.class)
            .setAction(ACTION_CANCEL);
        return PendingIntent.getService(this, 1, cancel,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private Notification buildProgressNotification(boolean isRestore, long copied, long total) {
        int pct = (total > 0) ? (int) Math.min(copied * 100 / total, 100) : 0;
        CharSequence title = getString(isRestore
            ? R.string.backup_service_notification_restore_title
            : R.string.backup_service_notification_title);
        CharSequence text = getString(R.string.backup_service_notification_progress, pct);

        Notification.Builder builder = NotificationUtils.geNotificationBuilder(this,
            TermuxConstants.TERMUX_BACKUP_PROGRESS_NOTIFICATION_CHANNEL_ID,
            Notification.PRIORITY_LOW, title, text, text,
            contentIntent(), null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null) {
            builder = new Notification.Builder(this)
                .setChannelId(TermuxConstants.TERMUX_BACKUP_PROGRESS_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title).setContentText(text);
        }
        builder.setSmallIcon(R.drawable.ic_service_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.backup_service_notification_cancel), cancelIntent());
        if (total > 0) {
            builder.setProgress(100, pct, false);
        } else {
            builder.setProgress(0, 0, true); // indeterminate when size is unknown
        }
        return builder.build();
    }

    private void publishProgress(boolean isRestore, long copied, long total) {
        if (mFinished) return;
        mProgressCopied = copied;
        mProgressTotal = total;
        if (!mInForeground) return; // dialog observes these fields directly
        NotificationManager nm = NotificationUtils.getNotificationManager(this);
        if (nm == null) return;
        nm.notify(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID,
            buildProgressNotification(isRestore, copied, total));
    }

    /** Build the final (success / failed / cancelled) heads-up notification, without posting it. */
    private Notification buildResultNotification(boolean isRestore, @Nullable Error error) {
        boolean cancelled = error == TermuxBackupUtils.CANCELLED_ERROR;
        boolean success = error == null;
        CharSequence title = cancelled
            ? getString(R.string.backup_restore_cancelled)
            : success
                ? getString(isRestore ? R.string.backup_service_notification_restore_success
                                      : R.string.backup_service_notification_success)
                : getString(isRestore ? R.string.backup_service_notification_restore_failed
                                      : R.string.backup_service_notification_failed);
        CharSequence text = (success || cancelled) ? null : Error.getMinimalErrorString(error);

        // Result notification: HIGH priority + sound so it pops heads-up. Progress bar cleared.
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(this,
            TermuxConstants.TERMUX_BACKUP_RESULT_NOTIFICATION_CHANNEL_ID,
            Notification.PRIORITY_HIGH,
            title, text, text,
            contentIntent(), null, NotificationUtils.NOTIFICATION_MODE_SOUND);
        if (builder == null) {
            builder = new Notification.Builder(this)
                .setChannelId(TermuxConstants.TERMUX_BACKUP_RESULT_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title).setContentText(text);
        }
        return builder.setSmallIcon(success ? R.drawable.ic_service_notification : R.drawable.ic_error_notification)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build();
    }

    private void showResult(boolean isRestore, @Nullable Error error) {
        // Run at most once (worker and enterBackground() can both race to call this).
        if (!mResultShown.compareAndSet(false, true)) return;

        NotificationManager nm = NotificationUtils.getNotificationManager(this);
        if (nm == null) return;

        // Drop the live progress (text) notification IMMEDIATELY so it disappears from the shade
        // the moment the operation ends. We then post the result as a *fresh* notification below.
        nm.cancel(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID);

        // Post the result as a fresh, high-priority heads-up notification. Posting it AFTER
        // cancelling the progress one guarantees the system treats it as a NEW notification and
        // actually pops the heads-up — updating an in-place notification often suppresses it.
        nm.notify(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID,
            buildResultNotification(isRestore, error));

        // Leave foreground only if we actually entered it (else stopForeground() would throw).
        if (mStartedForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                stopForeground(STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
        }

        // Safety net: auto-dismiss the result notification if the user does not tap it.
        // The teardown (stopSelf) happens INSIDE this runnable, after the timer fires, so
        // onDestroy() (which cancels the timer) no longer defeats it. An epoch guard makes
        // sure a stale timer cannot cancel a newer operation's notification.
        final int id = TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID;
        final int epoch = mEpoch.get();
        final Context appCtx = getApplicationContext();
        mAutoDismissRunnable = () -> {
            if (mEpoch.get() != epoch) return; // a newer operation started; leave its notification alone
            NotificationManager later = NotificationUtils.getNotificationManager(appCtx);
            if (later != null) later.cancel(id);
            stopSelf();
        };
        if (mMainHandler != null) {
            mMainHandler.postDelayed(mAutoDismissRunnable, 8000);
        }
    }

    // ------------------------------------------------------------------
    // Wake lock
    // ------------------------------------------------------------------

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Termux:BackupRestore");
            mWakeLock.acquire(); // no timeout — released explicitly in releaseWakeLock()
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to acquire wake lock", e);
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            try { mWakeLock.release(); } catch (Exception ignored) { }
            mWakeLock = null;
        }
    }
}
