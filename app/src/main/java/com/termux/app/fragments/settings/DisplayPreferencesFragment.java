package com.termux.app.fragments.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.termux.app.fragments.settings.TermuxPreferenceFragmentBase;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.theme.NightMode;

/**
 * The single "Display" screen. Subsections:
 * Theme, Language, Panel Transparency (former Appearance),
 * View, Window (former Display),
 * Tabs (moved here from the deleted Sessions screen).
 *
 * No global PreferenceDataStore is used — every persisted key is wired with an
 * explicit OnPreferenceChangeListener (and setPersistent(false) where needed) so
 * values land in the correct backing store (termux.properties / termux_prefs /
 * TermuxAppSharedPreferences) without relying on the framework's default persist.
 */
@Keep
public class DisplayPreferencesFragment extends TermuxPreferenceFragmentBase {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.termux_display_preferences, rootKey);

        // --- Theme ---
        configureColorSchemePreference("color_scheme_light", false);
        configureColorSchemePreference("color_scheme_dark", true);

        final ListPreference themePref = findPreference("theme_mode");
        if (themePref != null) {
            themePref.setPersistent(false);
            TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context, true);
            String currentValue = prefs != null ? prefs.getNightMode() : "system";
            themePref.setValue(!android.text.TextUtils.isEmpty(currentValue) ? currentValue : "system");

            themePref.setOnPreferenceChangeListener((preference, newValue) -> {
                String val = (String) newValue;
                if (prefs != null) prefs.setNightMode(val);
                NightMode.setAppNightMode(val);
                Context ctx = getContext();
                if (ctx != null) {
                    TermuxActivity.updateTermuxActivityStyling(ctx, true);
                }
                AppCompatActivity activity = (AppCompatActivity) getActivity();
                if (activity != null) {
                    activity.recreate();
                }
                return true;
            });
        }

        // --- Transparency ---
        SeekBarPreference inactiveSlider = findPreference("button_bg_inactive_alpha");
        if (inactiveSlider != null) {
            inactiveSlider.setMin(0);
            inactiveSlider.setMax(10);
        }
        SeekBarPreference activeSlider = findPreference("button_bg_active_alpha");
        if (activeSlider != null) {
            activeSlider.setMin(10);
            activeSlider.setMax(20);
        }
        wireSliderListener("button_bg_inactive_alpha", context);
        wireSliderListener("button_bg_active_alpha", context);

        // --- View: terminal margin ---
        final SwitchPreferenceCompat marginPref = findPreference("terminal_margin_adjustment");
        if (marginPref != null) {
            marginPref.setPersistent(false);
            TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context, true);
            marginPref.setChecked(prefs != null && prefs.isTerminalMarginAdjustmentEnabled());
            marginPref.setOnPreferenceChangeListener((preference, newValue) -> {
                if (prefs != null) prefs.setTerminalMarginAdjustment((Boolean) newValue);
                return true;
            });
        }

        // --- Window: screen orientation ---
        final ListPreference orientationPref = findPreference("screen_orientation");
        if (orientationPref != null) {
            orientationPref.setPersistent(false);
            final SharedPreferences termuxPrefs =
                    requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
            final boolean isTablet = requireContext().getResources()
                    .getConfiguration().smallestScreenWidthDp >= 600;
            orientationPref.setValue(termuxPrefs.getString("screen_orientation", isTablet ? "sensor" : "portrait"));
            orientationPref.setOnPreferenceChangeListener((preference, newValue) -> {
                termuxPrefs.edit().putString("screen_orientation", (String) newValue).apply();
                final androidx.fragment.app.FragmentActivity activity = getActivity();
                if (activity != null) {
                    TermuxActivity.applyScreenOrientation(activity);
                }
                return true;
            });
        }

        // --- Tabs ---
        configureTabPanelPositionPreference();
        configureTabHeightModePreference();
        configureSwipeRightmostNewTabPreference();
        configureRestoreSessionsPreference();
        configureDirectoryHistoryMaxPreference();
    }

    private void configureDirectoryHistoryMaxPreference() {
        final androidx.preference.SeekBarPreference pref = findPreference("directory_history_max");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        pref.setPersistent(false);
        int current = termuxPrefs.getInt("directory_history_max", 20);
        if (current < 10) current = 10;
        if (current > 100) current = 100;
        pref.setValue(current);

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            int value = (Integer) newValue;
            if (value < 10) {
                value = 10;
                pref.setValue(value);
            }
            termuxPrefs.edit().putInt("directory_history_max", value).apply();
            return true;
        });
    }

    // -----------------------------------------------------------------------
    //  Colour-scheme selection (light/dark)
    // -----------------------------------------------------------------------

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
                    TermuxActivity.updateTermuxActivityStyling(ctx, false);
                });
            return true;
        });
    }

    private void updateColorSchemeSummary(Preference pref, boolean isNight) {
        pref.setSummary(ColorSchemeUtils.schemeDisplayName(
            ColorSchemeUtils.getSelectedSchemeName(isNight)));
    }

    // -----------------------------------------------------------------------
    //  Transparency-slider listeners
    // -----------------------------------------------------------------------

    private void wireSliderListener(String key, Context context) {
        SeekBarPreference slider = findPreference(key);
        if (slider == null) return;
        slider.setOnPreferenceChangeListener((preference, newValue) -> {
            TermuxActivity.updateTermuxActivityStyling(context, false);
            return true;
        });
    }

    // -----------------------------------------------------------------------
    //  Tabs
    // -----------------------------------------------------------------------

    private void configureSwipeRightmostNewTabPreference() {
        final SwitchPreferenceCompat pref = findPreference("swipe_rightmost_new_tab");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        pref.setPersistent(false);
        pref.setChecked(termuxPrefs.getBoolean("swipe_rightmost_new_tab", true));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putBoolean("swipe_rightmost_new_tab", (Boolean) newValue).apply();
            return true;
        });
    }

    private void configureTabPanelPositionPreference() {
        final ListPreference pref = findPreference("tab_panel_position");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        pref.setPersistent(false);
        pref.setValue(termuxPrefs.getString("tab_panel_position", "top"));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putString("tab_panel_position", (String) newValue).apply();
            final Context context = requireContext();
            final Intent intent = new Intent("com.termux.TAB_PANEL_POSITION_CHANGED");
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
            return true;
        });
    }

    private void configureTabHeightModePreference() {
        final ListPreference pref = findPreference("tab_height_mode");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        pref.setPersistent(false);
        pref.setValue(termuxPrefs.getString("tab_height_mode", "single"));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putString("tab_height_mode", (String) newValue).apply();
            final Context context = requireContext();
            final Intent intent = new Intent("com.termux.TAB_HEIGHT_MODE_CHANGED");
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
            return true;
        });
    }

    private void configureRestoreSessionsPreference() {
        final SwitchPreferenceCompat pref = findPreference("restore_sessions");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        pref.setPersistent(false);
        pref.setChecked(termuxPrefs.getBoolean("restore_sessions", true));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putBoolean("restore_sessions", (Boolean) newValue).apply();
            return true;
        });
    }

}
