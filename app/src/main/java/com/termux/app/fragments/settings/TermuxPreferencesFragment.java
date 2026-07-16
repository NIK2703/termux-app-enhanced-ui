package com.termux.app.fragments.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.BackupProgressController;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxBackupService;

import java.io.IOException;

@Keep
public class TermuxPreferencesFragment extends PreferenceFragmentCompat {

    private static final String LOG_TAG = "TermuxPreferencesFragment";

    private static final int REQUEST_CODE_BACKUP = 1001;
    private static final int REQUEST_CODE_RESTORE = 1002;

    /** Owns the backup/restore progress dialog (shared with BackupDialogActivity via the controller). */
    private BackupProgressController mBackupController;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.termux_preferences, rootKey);

        configureBackupPreference();
        configureRestorePreference();
        configureMessageHistoryMaxPreference();
        configurePerDirectoryMessageHistoryPreference();
        configureDirectoryHistoryMaxPreference();
        configureRestoreSessionsPreference();
        configureScreenOrientationPreference();
        configureSwipeRightmostNewTabPreference();
    }

    /**
     * Wire up the "Swipe rightmost tab for new session" switch (default off).
     * Mirrored into the "termux_prefs" file (key {@code swipe_rightmost_new_tab})
     * where SessionPagerManager reads it at gesture time to decide whether a
     * right-swipe on the last tab should reveal a new terminal session.
     */
    private void configureSwipeRightmostNewTabPreference() {
        final SwitchPreferenceCompat pref = findPreference("swipe_rightmost_new_tab");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        pref.setChecked(termuxPrefs.getBoolean("swipe_rightmost_new_tab", true));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putBoolean("swipe_rightmost_new_tab", (Boolean) newValue).apply();
            return true;
        });
    }

    // ---------------------------------------------------------------------------------------
    // Backup / Restore
    // ---------------------------------------------------------------------------------------

    private void configureBackupPreference() {
        final Preference pref = findPreference("backup_container");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(preference -> {
            FragmentActivity activity = getActivity();
            if (activity == null) return true;

            new AlertDialog.Builder(activity)
                .setTitle(R.string.backup_restore_dialog_title)
                .setMessage(R.string.backup_restore_warning_backup)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> startBackupFileChooser())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return true;
        });
    }

    private void configureRestorePreference() {
        final Preference pref = findPreference("restore_container");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(preference -> {
            FragmentActivity activity = getActivity();
            if (activity == null) return true;

            new AlertDialog.Builder(activity)
                .setTitle(R.string.backup_restore_dialog_title)
                .setMessage(R.string.backup_restore_warning_restore)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> startRestoreFileChooser())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return true;
        });
    }

    /**
     * Wire up the "Max remembered messages" slider (range 10–100, default 20).
     * SeekBarPreference in this preference version only supports a max, so the
     * minimum is enforced by clamping. The value is mirrored into the
     * "termux_prefs" file (key {@code message_history_max}) where TermuxActivity
     * reads it, so the new limit applies without restarting the activity.
     */
    private static final int MESSAGE_HISTORY_MAX_MIN = 10;
    private static final int MESSAGE_HISTORY_MAX_HARD_MAX = 100;

    private void configureMessageHistoryMaxPreference() {
        final SeekBarPreference pref = findPreference("message_history_max");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        int current = termuxPrefs.getInt("message_history_max", 20);
        if (current < MESSAGE_HISTORY_MAX_MIN) current = MESSAGE_HISTORY_MAX_MIN;
        if (current > MESSAGE_HISTORY_MAX_HARD_MAX) current = MESSAGE_HISTORY_MAX_HARD_MAX;
        pref.setValue(current);

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            int value = (Integer) newValue;
            if (value < MESSAGE_HISTORY_MAX_MIN) {
                value = MESSAGE_HISTORY_MAX_MIN;
                pref.setValue(value);   // snap the slider back to the minimum
            }
            // Mirror into termux_prefs so TermuxActivity picks it up without a restart.
            termuxPrefs.edit().putInt("message_history_max", value).apply();
            return true;
        });
    }

    /**
     * Wire up the "Per-directory message history" switch (default off).
     * Mirrored into the "termux_prefs" file (key {@code per_directory_message_history})
     * where TermuxActivity reads it to decide whether to keep per-directory
     * or global message history.
     */
    private void configurePerDirectoryMessageHistoryPreference() {
        final SwitchPreferenceCompat pref = findPreference("per_directory_message_history");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        pref.setChecked(termuxPrefs.getBoolean("per_directory_message_history", false));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putBoolean("per_directory_message_history", (Boolean) newValue).apply();
            return true;
        });
    }

    /**
     * Wire up the "Max remembered directories" slider (range 10–100, default 20),
     * mirroring the pattern from message_history_max.
     */
    private static final int DIRECTORY_HISTORY_MAX_MIN = 10;
    private static final int DIRECTORY_HISTORY_MAX_HARD_MAX = 100;

    private void configureDirectoryHistoryMaxPreference() {
        final SeekBarPreference pref = findPreference("directory_history_max");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        int current = termuxPrefs.getInt("directory_history_max", 20);
        if (current < DIRECTORY_HISTORY_MAX_MIN) current = DIRECTORY_HISTORY_MAX_MIN;
        if (current > DIRECTORY_HISTORY_MAX_HARD_MAX) current = DIRECTORY_HISTORY_MAX_HARD_MAX;
        pref.setValue(current);

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            int value = (Integer) newValue;
            if (value < DIRECTORY_HISTORY_MAX_MIN) {
                value = DIRECTORY_HISTORY_MAX_MIN;
                pref.setValue(value);
            }
            termuxPrefs.edit().putInt("directory_history_max", value).apply();
            return true;
        });
    }

    /**
     * Wire up the "Restore tabs on launch" switch (default on).
     */
    private void configureRestoreSessionsPreference() {
        final SwitchPreferenceCompat pref = findPreference("restore_sessions");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        pref.setChecked(termuxPrefs.getBoolean("restore_sessions", true));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putBoolean("restore_sessions", (Boolean) newValue).apply();
            return true;
        });
    }

    /**
     * Wire up the "Screen orientation" list (sensor / portrait / landscape).
     */
    private void configureScreenOrientationPreference() {
        final ListPreference pref = findPreference("screen_orientation");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        final boolean isTablet = requireContext().getResources()
                .getConfiguration().smallestScreenWidthDp >= 600;
        final String defaultOrientation = isTablet ? "sensor" : "portrait";
        final String current = termuxPrefs.getString("screen_orientation", defaultOrientation);
        pref.setValue(current);

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putString("screen_orientation", (String) newValue).apply();
            final FragmentActivity activity = getActivity();
            if (activity != null) {
                TermuxActivity.applyScreenOrientation(activity);
            }
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
