package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import com.termux.app.fragments.settings.TermuxPreferenceFragmentBase;

import com.termux.R;
import com.termux.app.BackupProgressController;
import com.termux.app.TermuxBackupService;

import java.io.IOException;

@Keep
public class BackupRestorePreferencesFragment extends TermuxPreferenceFragmentBase {

    private static final String LOG_TAG = "BackupRestorePreferencesFragment";

    private static final int REQUEST_CODE_BACKUP = 1001;
    private static final int REQUEST_CODE_RESTORE = 1002;

    /** Owns the backup/restore progress dialog (shared with BackupDialogActivity via the controller). */
    private BackupProgressController mBackupController;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.termux_backup_restore_preferences, rootKey);

        configureBackupPreference();
        configureRestorePreference();
    }

    private void configureBackupPreference() {
        final Preference pref = findPreference("backup_container");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(preference -> {
            FragmentActivity activity = getActivity();
            if (activity == null) return true;

            AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.backup_restore_dialog_title)
                .setMessage(R.string.backup_restore_warning_backup)
                .setPositiveButton(android.R.string.ok, (d, which) -> startBackupFileChooser())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
            dialog.show();
            return true;
        });
    }

    private void configureRestorePreference() {
        final Preference pref = findPreference("restore_container");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(preference -> {
            FragmentActivity activity = getActivity();
            if (activity == null) return true;

            AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.backup_restore_dialog_title)
                .setMessage(R.string.backup_restore_warning_restore)
                .setPositiveButton(android.R.string.ok, (d, which) -> startRestoreFileChooser())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
            dialog.show();
            return true;
        });
    }

    private void startBackupFileChooser() {
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/gzip");
        intent.putExtra(Intent.EXTRA_TITLE, getDefaultBackupFilename());
        startActivityForResult(intent, REQUEST_CODE_BACKUP);
    }

    private String getDefaultBackupFilename() {
        java.text.SimpleDateFormat dateFmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        java.text.SimpleDateFormat timeFmt =
                new java.text.SimpleDateFormat("HH-mm", java.util.Locale.US);
        java.util.Date now = new java.util.Date();
        return "termux-backup-" + dateFmt.format(now) + "_" + timeFmt.format(now) + ".tar.gz";
    }

    private void startRestoreFileChooser() {
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_RESTORE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != AppCompatActivity.RESULT_OK || data == null || data.getData() == null)
            return;

        Uri uri = data.getData();
        FragmentActivity activity = getActivity();
        if (activity == null) return;

        mBackupController = new BackupProgressController(activity, false, null);
        if (requestCode == REQUEST_CODE_BACKUP) {
            mBackupController.start(R.string.backup_restore_backup_started,
                0L, false, false, uri);
        } else if (requestCode == REQUEST_CODE_RESTORE) {
            final long totalBytes = getStreamSize(activity, uri);
            mBackupController.start(R.string.backup_restore_restore_started,
                totalBytes, true, true, uri);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        TermuxBackupService svc = TermuxBackupService.getInstance();
        FragmentActivity activity = getActivity();
        if (activity != null && svc != null && svc.isInForeground() && !svc.isFinished()) {
            mBackupController = new BackupProgressController(activity, false, null);
            mBackupController.reopen(activity);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mBackupController != null) mBackupController.detach();
    }

    @Override
    public void onDestroy() {
        if (mBackupController != null) mBackupController.detach();
        super.onDestroy();
    }

    /** Query the size of a SAF content URI (or -1 if unknown). */
    private static long getStreamSize(Context context, Uri uri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                long size = pfd.getStatSize();
                if (size > 0) return size;
            }
        } catch (IOException ignored) { }
        return -1;
    }

}
