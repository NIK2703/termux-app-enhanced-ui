package com.termux.shared.termux.extrakeys;

import android.content.Context;
import android.view.ContextThemeWrapper;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TextStyle;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/** Termux app preference keys for per-theme color scheme selection (stored in termux.properties). */
public final class ColorSchemeUtils {

    private static final String LOG_TAG = "ColorSchemeUtils";

    /** Key in termux.properties selecting the color scheme for the light app theme. */
    public static final String KEY_COLOR_SCHEME_LIGHT = TermuxPropertyConstants.KEY_COLOR_SCHEME_LIGHT;
    /** Key in termux.properties selecting the color scheme for the dark app theme. */
    public static final String KEY_COLOR_SCHEME_DARK = TermuxPropertyConstants.KEY_COLOR_SCHEME_DARK;

    /** Perceived-brightness threshold (0-255) above which a color is treated as "light". */
    public static final int LIGHTNESS_THRESHOLD = 130;

    // Translucent button backgrounds: dark (for light schemes) / light (for dark schemes).
    public static final int BUTTON_BG_LIGHT_SCHEME = 0x0D000000;   // ~5% black
    public static final int BUTTON_BG_DARK_SCHEME = 0x0DFFFFFF;   // ~5% white
    public static final int BUTTON_BG_ACTIVE_LIGHT_SCHEME = 0x1F000000; // ~12% black
    public static final int BUTTON_BG_ACTIVE_DARK_SCHEME = 0x1FFFFFFF; // ~12% white

    private ColorSchemeUtils() {}

    /**
     * Convert an integer percentage (0‑100) to an alpha byte (0‑255).
     * Uses {@link Math#round} so 5% → 13 (0x0D) and 12% → 31 (0x1F),
     * matching the historical hardcoded defaults exactly.
     */
    public static int percentToAlpha(int percent) {
        int a = Math.round(percent * 255.0f / 100.0f);
        if (a < 0) return 0;
        if (a > 255) return 255;
        return a;
    }

    /** @deprecated Use {@link #getButtonBackground(boolean, int)} to honour user transparency slider. */
    @Deprecated
    public static int getButtonBackground(boolean isLight) {
        return getButtonBackground(isLight, 5);
    }

    /**
     * Build a translucent button background colour.
     *
     * @param isLight       Whether the colour scheme is perceived as light.
     * @param alphaPercent  User-configured alpha percentage (0‑20).
     * @return An ARGB colour with the given alpha over dark (light scheme) or light (dark scheme) base.
     */
    public static int getButtonBackground(boolean isLight, int alphaPercent) {
        int alpha = percentToAlpha(alphaPercent);
        int base = isLight ? 0x000000 : 0xFFFFFF;
        return (alpha << 24) | base;
    }

    /** @deprecated Use {@link #getButtonActiveBackground(boolean, int)} to honour user transparency slider. */
    @Deprecated
    public static int getButtonActiveBackground(boolean isLight) {
        return getButtonActiveBackground(isLight, 12);
    }

    /**
     * Build a translucent active/pressed button background colour.
     *
     * @param isLight       Whether the colour scheme is perceived as light.
     * @param alphaPercent  User-configured alpha percentage (10‑20).
     * @return An ARGB colour with the given alpha over dark (light scheme) or light (dark scheme) base.
     */
    public static int getButtonActiveBackground(boolean isLight, int alphaPercent) {
        int alpha = percentToAlpha(alphaPercent);
        int base = isLight ? 0x000000 : 0xFFFFFF;
        return (alpha << 24) | base;
    }

    /**
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
        // Guarantee readable panel/button/tab text against the scheme background: force black on a
        // light scheme and white on a dark scheme whenever the foreground would otherwise be
        // low-contrast. Symmetric guard (previously only white-on-light was handled), which fixes
        // black text on a dark background in night mode.
        boolean schemeLight = isTerminalSchemeLight();
        boolean foregroundLight = TerminalColors.getPerceivedBrightnessOfColor(foreground) >= LIGHTNESS_THRESHOLD;
        if (schemeLight && foregroundLight) {
            return 0xFF000000;
        }
        if (!schemeLight && !foregroundLight) {
            return 0xFFFFFFFF;
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

    /**
     * Resolve the color-scheme file to use for the given UI night mode.
     * Per-theme files ({@code colors.light.properties} / {@code colors.dark.properties}) take
     * priority so a light/dark scheme can be assigned independently of the app theme.
     *
     * @return The per-theme file to load, or {@code null} if it does not exist (caller should use
     *         the theme-derived built-in light/dark scheme). The shared colors.properties is NOT
     *         used as a fallback: selection is per-theme, so choosing "Default" (which deletes the
     *         per-theme file) must reset to the built-in light/dark scheme, not to a stale shared file.
     */
    public static File getColorSchemeFileForTheme(boolean isNight) {
        File perTheme = isNight ? TermuxConstants.TERMUX_COLOR_DARK_PROPERTIES_FILE
                                : TermuxConstants.TERMUX_COLOR_LIGHT_PROPERTIES_FILE;
        return perTheme.isFile() ? perTheme : null;
    }

    /**
     * Sentinel display name (and Termux:Style asset marker) meaning "reset to the default terminal
     * scheme" — i.e. remove the per-theme colors file so the theme-derived built-in scheme is used.
     */
    public static final String SCHEME_DEFAULT = "Default";

    /** Termux:Style stores its color schemes as {@code *.properties} files under this asset folder. */
    private static final String STYLING_COLORS_ASSET_DIR = "colors";

    /**
     * List the color schemes shipped by the installed Termux:Style app, read directly from its
     * assets (no duplication, always current). The returned array is the asset file names (e.g.
     * {@code solarized-dark.properties}) sorted alphabetically, with {@link #SCHEME_DEFAULT} first.
     *
     * @return The scheme file names, or {@code null} if Termux:Style is not installed / has no assets.
     */
    public static String[] listStylingColorSchemes(Context context) {
        Context stylingContext = getStylingContext(context);
        if (stylingContext == null) return null;
        try {
            String[] files = stylingContext.getAssets().list(STYLING_COLORS_ASSET_DIR);
            if (files == null) return null;
            List<String> schemes = new ArrayList<>();
            for (String f : files) {
                if (f.endsWith(".properties")) schemes.add(f);
            }
            if (schemes.isEmpty()) return null;
            Collections.sort(schemes, String.CASE_INSENSITIVE_ORDER);
            schemes.add(0, SCHEME_DEFAULT);
            return schemes.toArray(new String[0]);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to list Termux:Style color assets: " + e.getMessage());
            return null;
        }
    }

    /**
     * Show the color-scheme picker dialog (the single shared style-picker used from both Settings
     * and the terminal's long-press menu). Lists every scheme shipped by the installed Termux:Style
     * app, and on selection persists the choice for the given theme, writes the per-theme colors
     * file and invokes {@code onApplied} so the caller can trigger a live restyle.
     *
     * @param context   A UI context to show the dialog with.
     * @param isNight   Whether the selection targets the dark or light terminal scheme.
     * @param title     Dialog title.
     * @param notInstalledMessage Message shown if Termux:Style is not installed.
     * @param onApplied Run after a scheme is applied (e.g. to reload styling live); may be {@code null}.
     */
    public static void showColorSchemeDialog(Context context, boolean isNight, CharSequence title,
                                             CharSequence notInstalledMessage, Runnable onApplied) {
        final String[] schemes = listStylingColorSchemes(context);
        final Context dialogContext = new ContextThemeWrapper(context, com.termux.shared.R.style.ThemeOverlay_BaseDialog_DayNight);
        if (schemes == null) {
            AlertDialog d = new MaterialAlertDialogBuilder(dialogContext)
                .setMessage(notInstalledMessage)
                .setPositiveButton(android.R.string.ok, null)
                .create();
            d.show();
            return;
        }

        final String[] labels = new String[schemes.length];
        for (int i = 0; i < schemes.length; i++)
            labels[i] = schemeDisplayName(schemes[i]);

        AlertDialog d = new MaterialAlertDialogBuilder(dialogContext)
            .setTitle(title)
            .setItems(labels, (dialog, which) -> {
                persistSelection(isNight, schemes[which]);
                applyStylingScheme(context, isNight, schemes[which]);
                if (onApplied != null) onApplied.run();
            })
            .create();
        d.show();
    }

    /** Persist the selected scheme file name for the given theme into termux.properties. */
    public static void persistSelection(boolean isNight, String schemeFile) {
        File propsFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        Properties props = new Properties();
        if (propsFile.isFile()) {
            try (FileInputStream in = new FileInputStream(propsFile)) {
                props.load(in);
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Failed to read termux.properties: " + e.getMessage());
            }
        }
        props.setProperty(isNight ? KEY_COLOR_SCHEME_DARK : KEY_COLOR_SCHEME_LIGHT, schemeFile);
        try (FileOutputStream out = new FileOutputStream(propsFile)) {
            props.store(out, null);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to write termux.properties: " + e.getMessage());
        }
    }

    /**
     * Turn a Termux:Style scheme asset file name into a human-readable label, matching Termux:Style's
     * own display formatting: strip the extension, replace '-' with spaces and title-case words.
     */
    public static String schemeDisplayName(String fileName) {
        if (SCHEME_DEFAULT.equals(fileName)) return SCHEME_DEFAULT;
        String name = fileName.replace('-', ' ');
        int dot = name.lastIndexOf('.');
        if (dot != -1) name = name.substring(0, dot);
        StringBuilder sb = new StringBuilder(name.length());
        boolean lastWhitespace = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(lastWhitespace ? Character.toUpperCase(c) : c);
                lastWhitespace = false;
            } else {
                sb.append(c);
                lastWhitespace = Character.isWhitespace(c);
            }
        }
        return sb.toString();
    }

    /**
     * Apply a Termux:Style color scheme to the per-theme file ({@code colors.light/dark.properties}),
     * reading the scheme content straight from the Termux:Style assets.
     * - {@link #SCHEME_DEFAULT}: delete the per-theme file so the theme-derived built-in scheme is used.
     * - otherwise: copy the named asset's contents into the per-theme file.
     *
     * @return {@code true} if the per-theme file was updated.
     */
    public static boolean applyStylingScheme(Context context, boolean isNight, String fileName) {
        File perThemeFile = isNight ? TermuxConstants.TERMUX_COLOR_DARK_PROPERTIES_FILE
                                    : TermuxConstants.TERMUX_COLOR_LIGHT_PROPERTIES_FILE;

        if (SCHEME_DEFAULT.equals(fileName)) {
            if (perThemeFile.isFile()) perThemeFile.delete();
            return true;
        }

        Context stylingContext = getStylingContext(context);
        if (stylingContext == null) return false;

        try (InputStream in = stylingContext.getAssets().open(STYLING_COLORS_ASSET_DIR + "/" + fileName);
             FileOutputStream out = new FileOutputStream(perThemeFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return true;
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to apply Termux:Style scheme \"" + fileName + "\": " + e.getMessage());
            return false;
        }
    }

    /** Package context of the installed Termux:Style app, or {@code null} if not installed. */
    private static Context getStylingContext(Context context) {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_STYLING_PACKAGE_NAME);
    }

    /**
     * Resolve the scheme asset file name currently selected for the given theme, stored in
     * {@code termux.properties} under {@code color-scheme-light} / {@code color-scheme-dark}.
     *
     * @return The selected scheme file name, or {@link #SCHEME_DEFAULT} if none.
     */
    public static String getSelectedSchemeName(boolean isNight) {
        Properties props = new Properties();
        File propsFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        if (propsFile.isFile()) {
            try (FileInputStream in = new FileInputStream(propsFile)) {
                props.load(in);
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Failed to read termux.properties: " + e.getMessage());
            }
        }
        String value = props.getProperty(isNight ? KEY_COLOR_SCHEME_DARK : KEY_COLOR_SCHEME_LIGHT);
        return (value == null || value.isEmpty()) ? SCHEME_DEFAULT : value;
    }

}