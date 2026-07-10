package com.termux.shared.termux.extrakeys;

import android.graphics.Color;

import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TextStyle;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Single source of truth for terminal color-scheme handling, shared by both the Termux app and
 * Termux:Style so the "default colors file" marker, the custom-colors detection and the
 * light/dark auto-detection live in exactly one place (no duplicated logic between projects).
 */
public final class ColorSchemeUtils {

    /** Perceived-brightness threshold (0-255) above which a color is treated as "light". */
    public static final int LIGHTNESS_THRESHOLD = 130;

    // Translucent button backgrounds: dark (for light schemes) / light (for dark schemes).
    public static final int BUTTON_BG_LIGHT_SCHEME = 0x1A000000;   // ~10% black
    public static final int BUTTON_BG_DARK_SCHEME = 0x1AFFFFFF;   // ~10% white
    public static final int BUTTON_BG_ACTIVE_LIGHT_SCHEME = 0x40000000; // ~25% black
    public static final int BUTTON_BG_ACTIVE_DARK_SCHEME = 0x40FFFFFF; // ~25% white

    private ColorSchemeUtils() {}

    /**
     * @return Whether the given ARGB color is perceived as "light" (used to decide whether
     *         panel buttons need a dark or light translucent background and whether the status
     *         bar needs dark or light icons).
     */
    public static boolean isColorLight(int argb) {
        return TerminalColors.getPerceivedBrightnessOfColor(argb) >= LIGHTNESS_THRESHOLD;
    }

    /**
     * @return Whether the currently applied terminal color scheme (the static
     *         {@link TerminalColors#COLOR_SCHEME}) is light, based on its background color.
     */
    public static boolean isTerminalSchemeLight() {
        int background = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
        return isColorLight(background);
    }

    /**
     * @return The foreground color of the currently applied terminal color scheme, or a
     *         contrast color (black/white) derived from its background when no custom scheme is
     *         active (i.e. the built-in default was used).
     */
    public static int getSchemeForeground() {
        int foreground = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND];
        // The built-in default foreground is white; on a light scheme that is invisible, so fall
        // back to a contrast color based on the background brightness.
        if (foreground == 0xFFFFFFFF && isTerminalSchemeLight()) {
            return 0xFF000000;
        }
        return foreground;
    }

    /**
     * Whether the given colors.properties actually defines real terminal color keys
     * (background / foreground / cursor / colorN). A file that contains only comments or
     * unrelated keys (e.g. the "Default" marker written by Termux:Style) is treated as
     * "no custom colors".
     */
    public static boolean hasRealColors(Properties props) {
        for (String key : props.stringPropertyNames()) {
            if (key.equals("foreground") || key.equals("background") || key.equals("cursor")
                    || key.startsWith("color")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Load and apply a user colors.properties to the static {@link TerminalColors#COLOR_SCHEME}.
     *
     * @param file The {@code ~/.termux/colors.properties} file.
     * @return {@code true} if a real (non-default) custom color scheme was applied,
     *         {@code false} if the file is absent or only a "Default" marker (in which case the
     *         caller should fall back to the theme-derived light/dark scheme).
     */
    public static boolean loadTerminalColorScheme(File file) {
        if (file == null || !file.isFile()) return false;
        final Properties props = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (Exception e) {
            return false;
        }
        if (!hasRealColors(props)) return false;
        TerminalColors.COLOR_SCHEME.updateWith(props);
        return true;
    }

    public static int getButtonBackground(boolean isLight) {
        return isLight ? BUTTON_BG_LIGHT_SCHEME : BUTTON_BG_DARK_SCHEME;
    }

    public static int getButtonActiveBackground(boolean isLight) {
        return isLight ? BUTTON_BG_ACTIVE_LIGHT_SCHEME : BUTTON_BG_ACTIVE_DARK_SCHEME;
    }

    /**
     * Resolve the color-scheme file to use for the given UI night mode.
     * Per-theme files ({@code colors.light.properties} / {@code colors.dark.properties}) take
     * priority so a light/dark scheme can be assigned independently of the app theme; falls back
     * to the shared {@code colors.properties} when a per-theme file is absent.
     *
     * @return The file to load, or {@code null} if none exist (caller should use the theme-derived
     *         built-in light/dark scheme).
     */
    public static File getColorSchemeFileForTheme(boolean isNight) {
        File perTheme = isNight ? TermuxConstants.TERMUX_COLOR_DARK_PROPERTIES_FILE
                                : TermuxConstants.TERMUX_COLOR_LIGHT_PROPERTIES_FILE;
        if (perTheme.isFile()) return perTheme;
        if (TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE.isFile())
            return TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
        return null;
    }

}