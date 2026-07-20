package com.termux.shared.termux.theme;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.theme.NightMode;

public class TermuxThemeUtils {

    /** Get the {@link com.termux.shared.termux.settings.properties.TermuxPropertyConstants#KEY_NIGHT_MODE}
     * value from the app preferences and set it to app wide night mode value. */
    public static void setAppNightMode(@NonNull Context context) {
        NightMode.setAppNightMode(TermuxAppSharedProperties.getNightMode(context));
    }

    /** Set name as app wide night mode value. */
    public static void setAppNightMode(@Nullable String name) {
        NightMode.setAppNightMode(name);
    }

}
