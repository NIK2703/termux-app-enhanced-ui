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

    /**
     * (Re)compute ALL cached colours from the scheme and panel alpha percentages.
     *
     * @param prefs The app preferences (used to read button alpha percentages).
     */
    public void recompute(@NonNull TermuxAppSharedPreferences prefs) {
        recompute(prefs.getButtonBgInactiveAlpha(), prefs.getButtonBgActiveAlpha());
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
