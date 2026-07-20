package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceDataStore;
import com.termux.app.fragments.settings.TermuxPreferenceFragmentBase;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

/**
 * The single "Input" screen. Handles both the S2 terminal I/O keys (soft keyboard,
 * text input) routed through {@link TerminalIOPreferencesDataStore} and the S3 extra
 * keys keys persisted directly to {@code termux.properties} (plus hide extra keys with
 * keyboard stored in {@link TermuxAppSharedPreferences}).
 */
@Keep
public class TerminalIOPreferencesFragment extends TermuxPreferenceFragmentBase {

    private static final String LOG_TAG = "TerminalIOPrefsFragment";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(new TerminalIOPreferencesDataStore(context));

        setPreferencesFromResource(R.xml.termux_terminal_io_preferences, rootKey);

        // Broadcast so TermuxActivity updates the text-input toggle button immediately.
        SwitchPreferenceCompat textInputPref = findPreference("text_input_enabled");
        if (textInputPref != null) {
            textInputPref.setOnPreferenceChangeListener((preference, newValue) -> {
                android.content.Intent intent = new android.content.Intent("com.termux.TEXT_INPUT_ENABLED_CHANGED");
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
                return true;
            });
        }

        // Keep the extra-keys layout summary in sync with the current value.
        EditTextPreference layoutPref = findPreference(TermuxPropertyConstants.KEY_EXTRA_KEYS);
        if (layoutPref != null) {
            layoutPref.setSummary(layoutPref.getText() != null && !layoutPref.getText().isEmpty()
                ? layoutPref.getText()
                : getString(R.string.extra_keys_layout_summary));
            layoutPref.setOnPreferenceChangeListener((preference, newValue) -> {
                layoutPref.setSummary((String) newValue);
                return true;
            });
        }

        // --- History (moved here from the deleted Sessions screen) ---
        configureHistorySlider("message_history_max", 10, 100);
        configureHistorySlider("directory_history_max", 10, 100);
    }

    private void configureHistorySlider(String key, int min, int max) {
        androidx.preference.SeekBarPreference slider = findPreference(key);
        if (slider == null) return;
        slider.setOnPreferenceChangeListener((preference, newValue) -> {
            int value = (Integer) newValue;
            if (value < min) {
                value = min;
                slider.setValue(value);
            }
            return true;
        });
    }

}

class TerminalIOPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static TerminalIOPreferencesDataStore mInstance;

    public TerminalIOPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized TerminalIOPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TerminalIOPreferencesDataStore(context);
        }
        return mInstance;
    }

    private android.content.SharedPreferences getTermuxPrefs() {
        return mContext.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        if (mPreferences == null) return;
        if (key == null) return;

        switch (key) {
            case "soft_keyboard_enabled":
                    mPreferences.setSoftKeyboardEnabled(value);
                break;
            case "soft_keyboard_enabled_only_if_no_hardware":
                mPreferences.setSoftKeyboardEnabledOnlyIfNoHardware(value);
                break;
            case "text_input_enabled":
                // Save to shared preferences (this controls whether text input is enabled in settings)
                getTermuxPrefs().edit().putBoolean("text_input_enabled", value).apply();
                break;
            case "text_input_append_enter":
                if (mPreferences != null) mPreferences.setTextInputAppendEnter(value);
                break;
            case "text_input_hide_on_send":
                if (mPreferences != null) mPreferences.setTextInputHideOnSend(value);
                break;
            case "per_directory_message_history":
                getTermuxPrefs().edit().putBoolean("per_directory_message_history", value).apply();
                break;
            case TermuxPreferenceConstants.TERMUX_APP.KEY_HIDE_EXTRA_KEYS_WITH_KEYBOARD:
                if (mPreferences != null) mPreferences.setHideExtraKeysWithKeyboard(value);
                break;
            default:
                break;
        }
    }

    @Override
    public void putInt(String key, int value) {
        if (key == null) return;
        switch (key) {
            case "suggestions_max_count":
                getTermuxPrefs().edit().putInt("suggestions_max_count", value).apply();
                break;
            case "message_history_max":
                getTermuxPrefs().edit().putInt("message_history_max", value).apply();
                break;
            case "directory_history_max":
                getTermuxPrefs().edit().putInt("directory_history_max", value).apply();
                break;
            default:
                break;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        if (mPreferences == null) return false;

        switch (key) {
            case "soft_keyboard_enabled":
                return mPreferences.isSoftKeyboardEnabled();
            case "soft_keyboard_enabled_only_if_no_hardware":
                return mPreferences.isSoftKeyboardEnabledOnlyIfNoHardware();
            case "text_input_enabled":
                return getTermuxPrefs().getBoolean("text_input_enabled", true);
            case "text_input_append_enter":
                return mPreferences != null && mPreferences.shouldTextInputAppendEnter();
            case "text_input_hide_on_send":
                return mPreferences != null && mPreferences.shouldTextInputHideOnSend();
            case "per_directory_message_history":
                return getTermuxPrefs().getBoolean("per_directory_message_history", false);
            case TermuxPreferenceConstants.TERMUX_APP.KEY_HIDE_EXTRA_KEYS_WITH_KEYBOARD:
                return mPreferences != null && mPreferences.shouldHideExtraKeysWithKeyboard();
            default:
                return false;
        }
    }

    @Override
    public int getInt(String key, int defValue) {
        if (key == null) return defValue;
        switch (key) {
            case "suggestions_max_count":
                return getTermuxPrefs().getInt("suggestions_max_count", 4);
            case "message_history_max":
                return getTermuxPrefs().getInt("message_history_max", 20);
            case "directory_history_max":
                return getTermuxPrefs().getInt("directory_history_max", 20);
            default:
                return defValue;
        }
    }

    @Override
    public String getString(String key, String defValue) {
        if (key == null) return defValue;
        switch (key) {
            case TermuxPropertyConstants.KEY_EXTRA_KEYS:
                return readProperty(key);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE:
                return readPropertyOrDefault(key, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE:
                return readPropertyOrDefault(key, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS:
                return readPropertyOrDefault(key, "true");
            default:
                return defValue;
        }
    }

    @Override
    public void putString(String key, String value) {
        if (key == null) return;
        switch (key) {
            case TermuxPropertyConstants.KEY_EXTRA_KEYS:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE:
                writeProperty(key, value);
                TermuxActivity.updateTermuxActivityStyling(mContext, true);
                break;
            default:
                break;
        }
    }

    private String readPropertyOrDefault(String key, String defValue) {
        String v = readProperty(key);
        return v != null ? v : defValue;
    }

    private String readProperty(String key) {
        java.io.File propsFile = new java.io.File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        if (!propsFile.isFile()) return null;
        java.util.Properties props = new java.util.Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(propsFile)) {
            props.load(in);
        } catch (java.io.IOException e) {
            Logger.logError("TerminalIOPrefsFragment", "Failed to read termux.properties: " + e.getMessage());
            return null;
        }
        return props.getProperty(key);
    }

    private void writeProperty(String key, String value) {
        java.io.File propsFile = new java.io.File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        java.util.Properties props = new java.util.Properties();
        if (propsFile.isFile()) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(propsFile)) {
                props.load(in);
            } catch (java.io.IOException e) {
                Logger.logError("TerminalIOPrefsFragment", "Failed to read termux.properties for update: " + e.getMessage());
            }
        }
        props.setProperty(key, value);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(propsFile)) {
            props.store(out, null);
        } catch (java.io.IOException e) {
            Logger.logError("TerminalIOPrefsFragment", "Failed to write termux.properties: " + e.getMessage());
        }
    }

}
