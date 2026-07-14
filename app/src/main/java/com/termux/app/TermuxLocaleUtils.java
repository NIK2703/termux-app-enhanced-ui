package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Utility for overriding the app display language.
 * Supports two options: system default or English.
 */
public final class TermuxLocaleUtils {

    private static final String PREFERENCES_NAME = "com.termux_preferences";
    private static final String KEY_LOCALE_OVERRIDE = "locale_override";

    private TermuxLocaleUtils() {}

    /**
     * Read the current locale override preference.
     * @return {@code null} or {@code "system"} = system default, {@code "en"} = force English.
     */
    @Nullable
    public static String getLocaleOverride(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LOCALE_OVERRIDE, null);
    }

    /**
     * Persist the locale override choice.
     */
    public static void setLocaleOverride(@NonNull Context context, @Nullable String value) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCALE_OVERRIDE, value)
            .apply();
    }

    /**
     * Wrap a {@link Context} so that its resources use the overridden locale.
     * If the override is {@code null} or {@code "system"} the original context is returned unchanged.
     */
    @NonNull
    public static Context wrapContext(@NonNull Context context) {
        String override = getLocaleOverride(context);
        if (override == null || "system".equals(override)) return context;

        Locale locale;
        if ("en".equals(override)) {
            locale = Locale.ENGLISH;
        } else {
            return context;
        }

        Configuration config = new Configuration(context.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
            return context.createConfigurationContext(config);
        } else {
            config.locale = locale;
            android.content.res.Resources res = context.getResources();
            res.updateConfiguration(config, res.getDisplayMetrics());
            return context;
        }
    }
}
