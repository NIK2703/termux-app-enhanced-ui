package com.termux.app.terminal;

import android.graphics.Color;

import androidx.annotation.NonNull;

import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;

/**
 * Caches all UI colours derived from the terminal colour scheme and panel transparency prefs.
 * <p>
 * Must be {@link #recompute(TermuxAppSharedPreferences)}d whenever the scheme or the
 * inactive/active-alpha sliders change so that every styled element uses fresh colours
 * without recomputing them on every draw / event.
 */
public final class TermuxColorSchemeManager {

    // --- Panel / button colours ---
    private int mButtonBg = 0;
    private int mButtonActiveBg = 0;
    private int mButtonText = 0;
    private int mTextSelectionHighlightColor = 0;
    private boolean mIsSchemeLight = false;

    // --- Context-popup colours ---
    private int mHistoryPopupBg = 0;
    private int mHistoryTextColor = 0;
    private int mHistoryPopupSepColor = 0;
    private int mHistoryHighlightFill = 0;

    // --- Bash syntax-highlight token colours ---
    private int mTokenCommand = 0;
    private int mTokenPath = 0;
    private int mTokenVariable = 0;
    private int mTokenOption = 0;
    private int mTokenQuoted = 0;
    private int mTokenOperator = 0;

    /**
     * (Re)compute ALL cached colours from the scheme and panel alpha percentages.
     *
     * @param prefs The app preferences (used to read button alpha percentages).
     */
    public void recompute(@NonNull TermuxAppSharedPreferences prefs) {
        recompute(prefs.getButtonBgInactiveAlpha(), prefs.getButtonBgActiveAlpha());
    }

    /** Convenience overload using the default button alpha percentages. */
    public void recompute() {
        recompute(5, 12);
    }

    /**
     * (Re)compute all cached colours from the scheme and explicit alpha percentages.
     *
     * @param inactivePct Inactive button background alpha (0–100).
     * @param activePct   Active button background alpha (0–100).
     */
    public void recompute(int inactivePct, int activePct) {
        mIsSchemeLight = ColorSchemeUtils.isTerminalSchemeLight();

        // Panel button colours
        mButtonBg = ColorSchemeUtils.getButtonBackground(mIsSchemeLight, inactivePct);
        mButtonActiveBg = ColorSchemeUtils.getButtonActiveBackground(mIsSchemeLight, activePct);
        mButtonText = ColorSchemeUtils.getSchemeForeground();
        mTextSelectionHighlightColor = mIsSchemeLight
                ? Color.argb(38, 0, 0, 0)        // light scheme -> black @15%
                : Color.argb(38, 255, 255, 255); // dark scheme -> white @15%

        // Context-popup colours
        int inactiveOverlay = ColorSchemeUtils.getButtonBackground(mIsSchemeLight, inactivePct);
        int schemeBg = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
        mHistoryPopupBg = compositeColors(schemeBg, inactiveOverlay);
        mHistoryTextColor = mButtonText;
        mHistoryPopupSepColor = (mHistoryTextColor & 0x00FFFFFF) | (0x3C << 24);
        mHistoryHighlightFill = mIsSchemeLight
                ? Color.argb(26, 0, 0, 0)
                : Color.argb(26, 255, 255, 255);

        // Bash syntax-highlight token colours. Derived from the ACTIVE Termux
        // Styling colour scheme's own ANSI palette (the same 16-colour terminal
        // palette the user picks in Termux:Styling), so they follow the selected
        // scheme for both light and dark themes and never clash with it.
        //
        // For a light background we use the normal (0-7) ANSI colours (dark,
        // legible on light); for a dark background we use the bright (8-15)
        // variants (vivid on black). Each role maps to a distinct palette slot:
        //   command -> green, path -> blue, variable -> red, option -> yellow,
        //   quoted -> cyan, operator -> magenta.
        int base = mIsSchemeLight ? 0 : 8; // 0 = normal, 8 = bright
        mTokenCommand  = schemeAnsi(base + 2);  // green
        mTokenPath     = schemeAnsi(base + 4);  // blue
        mTokenVariable = schemeAnsi(base + 1);  // red
        mTokenOption   = schemeAnsi(base + 3);  // yellow
        mTokenQuoted   = schemeAnsi(base + 6);  // cyan
        mTokenOperator = schemeAnsi(base + 5);  // magenta
    }

    /**
     * Resolve an ANSI palette colour (0-15) from the currently applied Termux
     * Styling colour scheme. Falls back to a neutral grey if the scheme is unset.
     */
    private static int schemeAnsi(int index) {
        if (index < 0 || index > 15) return Color.GRAY;
        try {
            int[] palette = TerminalColors.COLOR_SCHEME.mDefaultColors;
            // A palette slot of 0 means the scheme has not been initialised yet
            // (no Termux:Styling scheme loaded) — fall back to a neutral grey so
            // highlighting never paints with a transparent/black colour.
            if (palette != null && index < palette.length && palette[index] != 0) {
                return palette[index];
            }
        } catch (Exception ignored) {
            // Scheme not yet initialised — fall through to fallback.
        }
        return Color.GRAY;
    }

    // --- Getters ---

    /** @return Cached panel/button background colour. */
    public int getButtonBg() { return mButtonBg; }

    /** @return Cached panel/button active background colour. */
    public int getButtonActiveBg() { return mButtonActiveBg; }

    /** @return Cached panel/button text (scheme foreground) colour. */
    public int getButtonText() { return mButtonText; }

    /** @return Cached text selection highlight colour. */
    public int getTextSelectionHighlightColor() { return mTextSelectionHighlightColor; }

    /** @return Whether the current scheme is perceived as light. */
    public boolean isSchemeLight() { return mIsSchemeLight; }

    /** @return Cached context-popup background colour (scheme bg + inactive overlay). */
    public int getHistoryPopupBg() { return mHistoryPopupBg; }

    /** @return Cached history popup text colour. */
    public int getHistoryTextColor() { return mHistoryTextColor; }

    /** @return Cached history popup separator colour (foreground @ ~24% alpha). */
    public int getHistoryPopupSepColor() { return mHistoryPopupSepColor; }

    /** @return Cached highlight fill for the history popup item under the finger. */
    public int getHistoryHighlightFill() { return mHistoryHighlightFill; }

    // --- Bash syntax-highlight token colours ---

    /** @return Cached colour for a recognised command name. */
    public int getTokenCommand() { return mTokenCommand; }
    /** @return Cached colour for a path / directory token. */
    public int getTokenPath() { return mTokenPath; }
    /** @return Cached colour for a variable expansion ({@code $VAR}, {@code ${VAR}}). */
    public int getTokenVariable() { return mTokenVariable; }
    /** @return Cached colour for an option / flag ({@code -x}, {@code --long}). */
    public int getTokenOption() { return mTokenOption; }
    /** @return Cached colour for a quoted string. */
    public int getTokenQuoted() { return mTokenQuoted; }
    /** @return Cached colour for an operator / redirection ({@code |}, {@code >}, {@code &&}). */
    public int getTokenOperator() { return mTokenOperator; }

    // --- Static utility ---

    /**
     * Alpha-composite {@code overlay} (with alpha) on top of {@code background}
     * (assumed opaque) using standard over operator.
     *
     * @return Fully opaque ARGB colour.
     */
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
}
