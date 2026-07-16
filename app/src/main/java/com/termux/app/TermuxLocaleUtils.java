package com.termux.app;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Utility for overriding the app display language.
 *
 * Uses {@link AppCompatDelegate#setApplicationLocales(LocaleListCompat)} (AndroidX AppCompat 1.6+),
 * which is the supported per-app language mechanism: it applies the locale at the application level
 * (so system-resolved strings like the activity {@code android:label} are also localized), persists
 * the choice automatically, and recreates the affected activities itself. No manual
 * {@link android.content.res.Configuration} manipulation or {@code ContextWrapper} is needed.
 */
public final class TermuxLocaleUtils {

    private TermuxLocaleUtils() {}

    /**
     * Apply the app display language.
     *
     * @param value {@code null}, {@code "system"} or empty = follow the system language;
     *              any BCP-47 language tag (e.g. {@code "en"}) forces that language.
     */
    public static void applyLocale(@Nullable String value) {
        LocaleListCompat locales;
        if (value == null || value.isEmpty() || "system".equals(value)) {
            locales = LocaleListCompat.getEmptyLocaleList();
        } else {
            locales = LocaleListCompat.forLanguageTags(value);
        }
        AppCompatDelegate.setApplicationLocales(locales);
    }

    /**
     * Read the currently selected locale override, or {@code "system"} if none is set.
     */
    @NonNull
    public static String getLocaleOverride() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (locales.isEmpty()) return "system";
        String tag = locales.toLanguageTags();
        return (tag == null || tag.isEmpty()) ? "system" : tag;
    }
}
