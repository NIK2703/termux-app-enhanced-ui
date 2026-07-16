package com.termux.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxLocaleUtils;
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

/**
 * App appearance settings (theme, color schemes, panel transparency).
 * Theme and colour-scheme changes broadcast to the running TermuxActivity
 * and also recreate the Settings activity so its own theme updates.
 * Transparency sliders persist via auto-persistence (same {@code
 * com.termux_preferences} file that TermuxAppSharedPreferences reads).
 */
@Keep
public class AppearancePreferencesFragment extends PreferenceFragmentCompat {

    private static final String LOG_TAG = "AppearancePrefsFragment";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.termux_appearance_preferences, rootKey);

        // --- Theme ---
        configureColorSchemePreference("color_scheme_light", false);
        configureColorSchemePreference("color_scheme_dark", true);

        final ListPreference themePref = findPreference("theme_mode");
        if (themePref != null) {
            String currentValue = readNightModeProperty();
            if (!android.text.TextUtils.isEmpty(currentValue))
                themePref.setValue(currentValue);
            else
                themePref.setValue("system");

            themePref.setOnPreferenceChangeListener((preference, newValue) -> {
                String val = (String) newValue;
                writeNightModeProperty(val);
                NightMode.setAppNightMode(val);
                // Broadcast to TermuxActivity for terminal + panel restyling.
                Context ctx = getContext();
                if (ctx != null) {
                    TermuxActivity.updateTermuxActivityStyling(ctx, true);
                }
                // Recreate the current (Settings) activity so its own theme updates.
                AppCompatActivity activity = (AppCompatActivity) getActivity();
                if (activity != null) {
                    activity.recreate();
                }
                return true;
            });
        }

        // --- App language ---
        final ListPreference localePref = findPreference("locale_override");
        if (localePref != null) {
            localePref.setValue(TermuxLocaleUtils.getLocaleOverride());

            localePref.setOnPreferenceChangeListener((preference, newValue) -> {
                String val = (String) newValue;
                TermuxLocaleUtils.applyLocale(val);
                // AppCompatDelegate.setApplicationLocales() recreates the affected activities
                // itself (including the Settings activity and the running TermuxActivity), so
                // strings and the activity label update automatically.
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
                    // Recolor the running terminal/panel live (no activity recreate needed).
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
    //  Night-mode (theme) property persistence via termux.properties
    // -----------------------------------------------------------------------

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


    // -----------------------------------------------------------------------
    //  Transparency-slider listeners (safe from SettingsActivity context)
    // -----------------------------------------------------------------------

    private void wireSliderListener(String key, Context context) {
        SeekBarPreference slider = findPreference(key);
        if (slider == null) return;
        slider.setOnPreferenceChangeListener((preference, newValue) -> {
            TermuxActivity.updateTermuxActivityStyling(context, false);
            return true;
        });
    }

}
