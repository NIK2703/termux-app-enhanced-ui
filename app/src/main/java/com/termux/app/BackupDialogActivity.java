package com.termux.app;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Lightweight transparent activity launched by tapping the backup/restore notification. It shows the
 * live progress dialog OVER whatever screen the app is currently on (terminal, settings, or launched
 * from the background) and re-attaches to the running {@link TermuxBackupService}. It does NOT open
 * the settings screen — the dialog is the only thing it adds. Once the dialog closes (finished,
 * cancelled, or backgrounded) this activity finishes itself so the previous screen is revealed
 * unchanged underneath.
 */
public final class BackupDialogActivity extends AppCompatActivity {

    private BackupProgressController mController;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No content view: this activity is fully transparent and only hosts the progress dialog.
        mController = new BackupProgressController(this, true, this::finish);
        mController.reopen(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // SingleTop re-delivery: if the activity is reused (e.g. user tapped the notification
        // twice quickly), re-attach to the running operation. The controller's reopen() is
        // idempotent for an already-active dialog and cleanly handles the finished case.
        if (mController != null) mController.reopen(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // After a config change (rotation) or process-bg-kill the activity is recreated or
        // restored. If the operation is still running in background mode, pull it back to the
        // dialog. No-op if the operation already finished — the result was already surfaced
        // as a heads-up notification.
        TermuxBackupService svc = TermuxBackupService.getInstance();
        if (svc != null && svc.isInForeground() && !svc.isFinished()) {
            mController = new BackupProgressController(this, true, this::finish);
            mController.reopen(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // If the dialog is still up when we are paused (e.g. user tapped "Background" or the app
        // was minimized), move the operation to the notification and let this host finish.
        if (mController != null) mController.detach();
    }

    @Override
    protected void onDestroy() {
        if (mController != null) mController.detach();
        super.onDestroy();
    }
}
