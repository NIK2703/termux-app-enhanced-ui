package com.termux.app.activities;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;

import java.io.File;
import java.util.Properties;

/**
 * Applies the Termux:Style colour scheme (the same one the terminal uses) to the Settings screen.
 * <p>
 * The activity background / status bar follow the scheme background, and each
 * {@link PreferenceCategory} header additionally layers the inactive-element background colour
 * (the same translucent overlay used for the bottom-panel buttons) on top of the scheme background,
 * so the headers read as a distinct but scheme-consistent band.
 */
public final class SettingsColorScheme {

    private SettingsColorScheme() {}

    // 5% inactive overlay matches the default button-bg alpha used across the app.
    private static final int INACTIVE_ALPHA_PERCENT = 5;

    /** @return Whether the current UI configuration is in night mode. */
    private static boolean isNightMode(@NonNull Activity activity) {
        int uiMode = activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Make sure {@link TerminalColors#COLOR_SCHEME} reflects the current night mode, mirroring the
     * terminal's {@code applyTerminalColorScheme()} colour resolution (without touching fonts or
     * the live emulator).
     */
    private static void ensureColorSchemeLoaded(@NonNull Activity activity) {
        boolean isNight = isNightMode(activity);
        File colorsFile = ColorSchemeUtils.getColorSchemeFileForTheme(isNight);
        boolean customApplied = (colorsFile != null) && ColorSchemeUtils.loadTerminalColorScheme(colorsFile);
        if (!customApplied) {
            if (!isNight) {
                TerminalColors.COLOR_SCHEME.updateWith(getLightTerminalColorScheme());
            } else {
                TerminalColors.COLOR_SCHEME.updateWith(new Properties());
            }
        }
    }

    /** Built-in light terminal scheme, matching the one used by the terminal in light mode. */
    private static Properties getLightTerminalColorScheme() {
        Properties props = new Properties();
        props.setProperty("background", "#ffffff");
        props.setProperty("foreground", "#000000");
        props.setProperty("color0",  "#000000");
        props.setProperty("color1",  "#cd0000");
        props.setProperty("color2",  "#00cd00");
        props.setProperty("color3",  "#cdcd00");
        props.setProperty("color4",  "#1060c0");
        props.setProperty("color5",  "#cd00cd");
        props.setProperty("color6",  "#00cdcd");
        props.setProperty("color7",  "#404040");
        props.setProperty("color8",  "#808080");
        props.setProperty("color9",  "#ff0000");
        props.setProperty("color10", "#00ff00");
        props.setProperty("color11", "#ffff00");
        props.setProperty("color12", "#1080ff");
        props.setProperty("color13", "#ff00ff");
        props.setProperty("color14", "#00ffff");
        props.setProperty("color15", "#ffffff");
        return props;
    }

    /**
     * Resolve the scheme colours for the given activity.
     *
     * @return {@code int[0]} = scheme background, {@code int[1]} = scheme foreground,
     *         {@code int[2]} = header background (scheme bg + inactive overlay),
     *         {@code int[3]} = 1 if scheme is light, else 0.
     */
    @NonNull
    public static int[] resolveSchemeColors(@NonNull Activity activity) {
        ensureColorSchemeLoaded(activity);

        int schemeBg = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
        int schemeFg = ColorSchemeUtils.getSchemeForeground();
        boolean isLight = ColorSchemeUtils.isTerminalSchemeLight();

        int inactiveOverlay = ColorSchemeUtils.getButtonBackground(isLight, INACTIVE_ALPHA_PERCENT);
        int headerBg = compositeColors(schemeBg, inactiveOverlay);

        return new int[]{ schemeBg, schemeFg, headerBg, isLight ? 1 : 0 };
    }

    /** Alpha-composite {@code overlay} (with alpha) on top of {@code background} (assumed opaque). */
    public static int compositeColors(int background, int overlay) {
        int alpha = Color.alpha(overlay);
        if (alpha == 0) return background;
        if (alpha == 255) return overlay;
        int invAlpha = 255 - alpha;
        int r = (Color.red(background) * invAlpha + Color.red(overlay) * alpha) / 255;
        int g = (Color.green(background) * invAlpha + Color.green(overlay) * alpha) / 255;
        int b = (Color.blue(background) * invAlpha + Color.blue(overlay) * alpha) / 255;
        return Color.rgb(r, g, b);
    }

    /** Apply {@code alpha} (0–255) to the RGB of {@code color}, keeping the scheme hue. */
    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** Apply the scheme background + status-bar + toolbar theme to the activity window. */
    public static void applyToActivity(@NonNull Activity activity, int schemeBg, int headerBg,
                                        int schemeFg, boolean isLight) {
        Window window = activity.getWindow();
        if (window == null) return;
        // Settings background = scheme background with the inactive-element overlay layered on top
        // (same treatment as the category headers), so the whole screen reads as one scheme band.
        window.getDecorView().setBackgroundColor(headerBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = window.getDecorView().getSystemUiVisibility();
            if (isLight) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }

        // Transparent status bar so it shows the (overlaid) header/toolbar background underneath.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
        }

        // Toolbar background + title follow the (overlaid) scheme (overrides the theme colorPrimary red).
        View toolbar = activity.findViewById(com.termux.shared.R.id.toolbar);
        if (toolbar instanceof androidx.appcompat.widget.Toolbar) {
            androidx.appcompat.widget.Toolbar abToolbar = (androidx.appcompat.widget.Toolbar) toolbar;
            abToolbar.setBackgroundColor(headerBg);
            abToolbar.setTitleTextColor(schemeFg);
            android.graphics.drawable.Drawable navIcon = abToolbar.getNavigationIcon();
            if (navIcon != null) {
                androidx.core.graphics.drawable.DrawableCompat.setTintList(
                    navIcon,
                    android.content.res.ColorStateList.valueOf(schemeFg));
            }
        } else if (toolbar != null) {
            toolbar.setBackgroundColor(headerBg);
            TextView toolbarTitle = toolbar.findViewById(androidx.appcompat.R.id.title);
            if (toolbarTitle != null) toolbarTitle.setTextColor(schemeFg);
        }
    }
}
