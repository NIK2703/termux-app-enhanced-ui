package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

@Keep
public class TerminalIOPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(TerminalIOPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.termux_terminal_io_preferences, rootKey);
        
        // Add listener to apply text input enabled change immediately
        SwitchPreferenceCompat textInputPref = findPreference("text_input_enabled");
        if (textInputPref != null) {
            textInputPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = (Boolean) newValue;
                
                // Send broadcast to update toggle button visibility in TermuxActivity
                android.content.Intent intent = new android.content.Intent("com.termux.TEXT_INPUT_ENABLED_CHANGED");
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
                
                return true;
            });
        }

        // Apply tab panel position change immediately in the running activity.
        androidx.preference.ListPreference tabPanelPositionPref = findPreference("tab_panel_position");
        if (tabPanelPositionPref != null) {
            tabPanelPositionPref.setOnPreferenceChangeListener((preference, newValue) -> {
                android.content.Intent intent = new android.content.Intent("com.termux.TAB_PANEL_POSITION_CHANGED");
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
                return true;
            });
        }

        // Apply tab height mode change immediately in the running activity.
        androidx.preference.ListPreference tabHeightModePref = findPreference("tab_height_mode");
        if (tabHeightModePref != null) {
            tabHeightModePref.setOnPreferenceChangeListener((preference, newValue) -> {
                android.content.Intent intent = new android.content.Intent("com.termux.TAB_HEIGHT_MODE_CHANGED");
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
                return true;
            });
        }
    }

}

class TerminalIOPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static TerminalIOPreferencesDataStore mInstance;

    private TerminalIOPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized TerminalIOPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TerminalIOPreferencesDataStore(context);
        }
        return mInstance;
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
                mContext.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("text_input_enabled", value).apply();
                break;
            default:
                break;
        }
    }

    @Override
    public void putString(String key, String value) {
        if (mPreferences == null) return;
        if (key == null) return;

        switch (key) {
            case "tab_panel_position":
                mContext.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
                    .edit().putString("tab_panel_position", value).apply();
                break;
            case "tab_height_mode":
                mContext.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
                    .edit().putString("tab_height_mode", value).apply();
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
                return mContext.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
                    .getBoolean("text_input_enabled", true);
            default:
                return false;
        }
    }

    @Override
    public String getString(String key, String defValue) {
        if (mPreferences == null) return defValue;

        switch (key) {
            case "tab_panel_position":
                return mContext.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
                    .getString("tab_panel_position", "top");
            case "tab_height_mode":
                return mContext.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
                    .getString("tab_height_mode", "single");
            default:
                return defValue;
        }
    }

}
