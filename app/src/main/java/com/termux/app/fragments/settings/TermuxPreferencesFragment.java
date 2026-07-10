package com.termux.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.Keep;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
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

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        // NOTE: Do NOT set a PreferenceDataStore here. TermuxPreferencesDataStore does not
        // override getString()/putString(), so ListPreference would throw
        // UnsupportedOperationException when reading its persisted value and crash the fragment.
        // Theme selection is written manually to termux.properties (see writeNightModeProperty).

        setPreferencesFromResource(R.xml.termux_preferences, rootKey);

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

