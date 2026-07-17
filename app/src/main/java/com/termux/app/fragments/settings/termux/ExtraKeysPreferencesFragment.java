package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Settings for the extra keys panel. All values are persisted directly to
 * {@code termux.properties} (the same file edited manually) and applied live to the
 * running TermuxActivity via {@link TermuxActivity#updateTermuxActivityStyling(Context, boolean)}.
 */
@Keep
public class ExtraKeysPreferencesFragment extends PreferenceFragmentCompat {

    private static final String LOG_TAG = "ExtraKeysPrefsFragment";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.termux_extra_keys_preferences, rootKey);

        // --- Keys layout (JSON) ---
        final EditTextPreference layoutPref = findPreference(TermuxPropertyConstants.KEY_EXTRA_KEYS);
        if (layoutPref != null) {
            String current = readProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS);
            if (current != null) layoutPref.setText(current);
            layoutPref.setSummary(current != null
                ? current
                : getString(R.string.extra_keys_layout_summary));
            layoutPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String val = (String) newValue;
                writeProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS, val);
                layoutPref.setSummary(val);
                applyChanges(context);
                return true;
            });
        }

        // --- Keys style ---
        final ListPreference stylePref = findPreference(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE);
        if (stylePref != null) {
            String current = readProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE);
            if (current != null) stylePref.setValue(current);
            else stylePref.setValue(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE);
            stylePref.setOnPreferenceChangeListener((preference, newValue) -> {
                writeProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, (String) newValue);
                applyChanges(context);
                return true;
            });
        }

        // --- All caps text ---
        final SwitchPreferenceCompat allCapsPref = findPreference(TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS);
        if (allCapsPref != null) {
            String current = readProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS);
            allCapsPref.setChecked(current == null || Boolean.parseBoolean(current));
            allCapsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                writeProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS,
                    Boolean.toString((Boolean) newValue));
                applyChanges(context);
                return true;
            });
        }

        // --- Special button mode (sticky / hold) ---
        final ListPreference modePref = findPreference(TermuxPropertyConstants.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE);
        if (modePref != null) {
            String current = readProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE);
            if (current != null) modePref.setValue(current);
            else modePref.setValue(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE);
            modePref.setOnPreferenceChangeListener((preference, newValue) -> {
                writeProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE, (String) newValue);
                applyChanges(context);
                return true;
            });
        }
    }

    /** Persist termux.properties and restyle the running activity so changes apply immediately. */
    private void applyChanges(Context context) {
        TermuxActivity.updateTermuxActivityStyling(context, true);
    }

    private String readProperty(String key) {
        File propsFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        if (!propsFile.isFile()) return null;
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(propsFile)) {
            props.load(in);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to read termux.properties: " + e.getMessage());
            return null;
        }
        return props.getProperty(key);
    }

    private void writeProperty(String key, String value) {
        File propsFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        Properties props = new Properties();
        if (propsFile.isFile()) {
            try (FileInputStream in = new FileInputStream(propsFile)) {
                props.load(in);
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Failed to read termux.properties for update: " + e.getMessage());
            }
        }
        props.setProperty(key, value);
        try (FileOutputStream out = new FileOutputStream(propsFile)) {
            props.store(out, null);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to write termux.properties: " + e.getMessage());
        }
    }
}
