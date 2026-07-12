package com.termux.app.fragments.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

import com.termux.R;
import com.termux.app.BackupProgressController;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxBackupService;
import com.termux.app.TermuxBackupUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.theme.NightMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

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

        // NOTE: Do NOT set a PreferenceDataStore here. TermuxPreferencesDataStore does not
        // override getString()/putString(), so ListPreference would throw
        // UnsupportedOperationException when reading its persisted value and crash the fragment.
        // Theme selection is written manually to termux.properties (see writeNightModeProperty).

        setPreferencesFromResource(R.xml.termux_preferences, rootKey);

        configureColorSchemePreference("color_scheme_light", false);
        configureColorSchemePreference("color_scheme_dark", true);

        configureBackupPreference();
        configureRestorePreference();
        configureMessageHistoryMaxPreference();
        configureScreenOrientationPreference();

        // Setup theme ListPreference: load current value from termux.properties
        final ListPreference themePref = findPreference("theme_mode");
        if (themePref != null) {
            // Read current night-mode from the properties file
            String currentValue = readNightModeProperty();
            if (!TextUtils.isEmpty(currentValue))
                themePref.setValue(currentValue);
            else
                themePref.setValue("system");

            themePref.setOnPreferenceChangeListener((preference, newValue) -> {
                String val = (String) newValue;
                writeNightModeProperty(val);
                NightMode.setAppNightMode(val);
                // Apply immediately: broadcast to TermuxActivity so the terminal/activity
                // theme is reloaded and recreated without needing to reopen the app.
                Context ctx = getContext();
                if (ctx != null) {
                    TermuxActivity.updateTermuxActivityStyling(ctx, true);
                }
                // Also recreate the current (Settings) activity so its own theme updates.
                AppCompatActivity activity = (AppCompatActivity) getActivity();
                if (activity != null) {
                    activity.recreate();
                }
                return true;
            });
        }
    }

    // ----------------------------------------------------------------------------------------
    // Backup / Restore
    // ----------------------------------------------------------------------------------------

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
     * Wire up the "Screen orientation" list (sensor / portrait / landscape).
     * The chosen value is mirrored into the "termux_prefs" file (key
     * {@code screen_orientation}) where TermuxActivity reads it to call
     * {@code setRequestedOrientation}. Default is "sensor" on tablets and
     * "portrait" on phones.
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
            // Mirror into termux_prefs so TermuxActivity picks it up without a restart.
            termuxPrefs.edit().putString("screen_orientation", (String) newValue).apply();
            // Apply immediately to the live activity (SettingsActivity hosts this
            // fragment), so the change takes effect without restarting Termux.
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
        intent.putExtra(Intent.EXTRA_TITLE, getString(R.string.backup_restore_backup_filename));
        startActivityForResult(intent, REQUEST_CODE_BACKUP);
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

        // The controller owns the dialog + the service start; we just hand it the params.
        mBackupController = new BackupProgressController(activity, false, null);
        if (requestCode == REQUEST_CODE_BACKUP) {
            final long estimatedSize = TermuxBackupUtils.getEstimatedBackupSize(activity);
            mBackupController.start(R.string.backup_restore_backup_started,
                estimatedSize, false, false, uri);
        } else if (requestCode == REQUEST_CODE_RESTORE) {
            final long totalBytes = getStreamSize(activity, uri);
            mBackupController.start(R.string.backup_restore_restore_started,
                totalBytes, true, true, uri);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // After being backgrounded (notification took over), re-attach to the still-running
        // operation and restore the live progress dialog on expand. No-op if the operation
        // already finished (the result was already surfaced as a heads-up).
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
        // If the dialog is still up when the app is minimized, move the operation to the
        // background notification so it keeps running and stays visible. The controller detaches
        // its poll loop; the service keeps the notification alive.
        if (mBackupController != null) mBackupController.detach();
    }

    @Override
    public void onDestroy() {
        if (mBackupController != null) mBackupController.detach();
        super.onDestroy();
    }

    /** Query the size of a SAF content URI (or -1 if unknown). */
    private static long getStreamSize(Context context, Uri uri) {
        try (android.os.ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                long size = pfd.getStatSize();
                if (size > 0) return size;
            }
        } catch (IOException ignored) { }
        return -1;
    }

    /**
     * Wire up a per-theme color-scheme Preference. Clicking it opens the same style-picker dialog
     * as choosing a Termux:Style from the terminal (an AlertDialog listing every scheme shipped by
     * the installed Termux:Style app, read straight from its assets). Selecting one persists the
     * choice to termux.properties, writes it to the matching per-theme colors file and recolors the
     * running terminal live (no activity recreate).
     *
     * @param key    The preference key ("color_scheme_light" / "color_scheme_dark").
     * @param isNight Whether this preference drives the dark or light terminal scheme.
     */
    private void configureColorSchemePreference(String key, boolean isNight) {
        final Preference pref = findPreference(key);
        if (pref == null) return;

        updateColorSchemeSummary(pref, isNight);

        pref.setOnPreferenceClickListener(preference -> {
            Context ctx = getContext();
            if (ctx == null) return true;
            ColorSchemeUtils.showColorSchemeDialog(ctx, isNight, pref.getTitle(),
                getString(R.string.error_styling_not_installed), () -> {
                    updateColorSchemeSummary(pref, isNight);
                    // Recolor the running terminal/panel live (no activity recreate needed).
                    TermuxActivity.updateTermuxActivityStyling(ctx, false);
                });
            return true;
        });
    }

    /** Set the preference summary to the currently selected scheme's display name. */
    private void updateColorSchemeSummary(Preference pref, boolean isNight) {
        pref.setSummary(ColorSchemeUtils.schemeDisplayName(ColorSchemeUtils.getSelectedSchemeName(isNight)));
    }

    /** Read the `night-mode` value from the termux.properties file on disk. */
    private String readNightModeProperty() {
        File propsFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        if (!propsFile.isFile()) return null;
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(propsFile)) {
            props.load(in);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to read termux.properties: " + e.getMessage());
            return null;
        }
        return props.getProperty(TermuxPropertyConstants.KEY_NIGHT_MODE);
    }

    /** Write the `night-mode` value to the termux.properties file on disk, preserving other keys. */
    private void writeNightModeProperty(String value) {
        File propsFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        Properties props = new Properties();
        if (propsFile.isFile()) {
            try (FileInputStream in = new FileInputStream(propsFile)) {
                props.load(in);
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Failed to read termux.properties for update: " + e.getMessage());
            }
        }
        props.setProperty(TermuxPropertyConstants.KEY_NIGHT_MODE, value);
        try (FileOutputStream out = new FileOutputStream(propsFile)) {
            props.store(out, null);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to write termux.properties: " + e.getMessage());
        }
    }

}
