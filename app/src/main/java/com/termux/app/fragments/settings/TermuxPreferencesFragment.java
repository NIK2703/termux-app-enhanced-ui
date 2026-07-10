package com.termux.app.fragments.settings;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.text.TextUtils;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxBackupUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.theme.NightMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

@Keep
public class TermuxPreferencesFragment extends PreferenceFragmentCompat {

    private static final String LOG_TAG = "TermuxPreferencesFragment";

    private static final int REQUEST_CODE_BACKUP = 1001;
    private static final int REQUEST_CODE_RESTORE = 1002;

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

        if (requestCode == REQUEST_CODE_BACKUP) {
            final long estimatedSize = TermuxBackupUtils.getEstimatedBackupSize(activity);
            runWithProgress(R.string.backup_restore_backup_started,
                R.string.backup_restore_backup_success, estimatedSize, false,
                (TermuxBackupUtils.ProgressCallback progressCb) -> {
                    try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                        if (out == null) return new Error("Failed to open output file");
                        final Error[] holder = new Error[1];
                        TermuxBackupUtils.backup(activity, out, error -> holder[0] = error, progressCb);
                        if (holder[0] != null) {
                            try { activity.getContentResolver().delete(uri, null, null); }
                            catch (Exception ignored) { }
                        }
                        return holder[0];
                    } catch (IOException | RuntimeException e) {
                        try { activity.getContentResolver().delete(uri, null, null); }
                        catch (Exception ignored) { }
                        return new Error(e.getMessage(), e);
                    }
                });
        } else if (requestCode == REQUEST_CODE_RESTORE) {
            final long totalBytes = getStreamSize(activity, uri);
            runWithProgress(R.string.backup_restore_restore_started,
                R.string.backup_restore_restore_success, totalBytes, true,
                (TermuxBackupUtils.ProgressCallback progressCb) -> {
                    try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                        if (in == null) return new Error("Failed to open input file");
                        final Error[] holder = new Error[1];
                        TermuxBackupUtils.restore(activity, in, error -> holder[0] = error, progressCb);
                        return holder[0];
                    } catch (IOException | RuntimeException e) {
                        return new Error(e.getMessage(), e);
                    }
                });
        }
    }

    /** Run the blocking backup/restore task on a background thread with a progress dialog.
     * The standard horizontal ProgressDialog is used: the title is shown on top, and the
     * percentage ("67%") is rendered by the dialog next to the bar (one place only).
     * @param launchTermuxOnSuccess if true (restore), open Termux after success and reset sessions */
    private void runWithProgress(int titleRes, final int successMsgRes,
                                 final long totalBytes, final boolean launchTermuxOnSuccess,
                                 final ProgressTask task) {
        FragmentActivity activity = getActivity();
        if (activity == null) return;

        PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "Termux:BackupRestore");
        wl.acquire(30 * 60 * 1000L);

        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle(getString(titleRes));
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setProgressNumberFormat(null); // no percentage number — only the bar itself
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            TermuxBackupUtils.ProgressCallback innerCb = (copied, total) -> {
                final long effective = total > 0 ? total : totalBytes;
                if (effective > 0) {
                    final int pct = (int) Math.min(copied * 100 / effective, 100);
                    activity.runOnUiThread(() -> progress.setProgress(pct));
                }
            };
            final Error error = task.doWork(innerCb);
            activity.runOnUiThread(() -> {
                progress.dismiss();
                wl.release();
                if (error == null) {
                    android.widget.Toast.makeText(activity, getString(successMsgRes),
                        android.widget.Toast.LENGTH_LONG).show();
                    if (launchTermuxOnSuccess) {
                        TermuxActivity.startTermuxActivityWithSessionReset(activity);
                    }
                } else {
                    new AlertDialog.Builder(activity)
                        .setTitle(R.string.backup_restore_failed_title)
                        .setMessage(Error.getMinimalErrorString(error))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                }
            });
        }).start();
    }

    /** A background task that receives a progress callback. */
    private interface ProgressTask {
        @Nullable
        Error doWork(TermuxBackupUtils.ProgressCallback progress);
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
