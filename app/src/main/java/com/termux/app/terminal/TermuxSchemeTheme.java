package com.termux.app.terminal;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.app.activities.SettingsColorScheme;
import com.termux.shared.interact.SchemeDialogTheme;

/**
 * Single apply-path for the Termux:Style colour scheme across every non-panel surface
 * (dialogs, context menu, fallback view trees). All colours are read from the
 * {@link TermuxColorSchemeManager} cache so a single {@code recompute()} + {@code applySchemeColors()}
 * pass restyles the whole UI.
 */
public final class TermuxSchemeTheme {

    private TermuxSchemeTheme() {}

    /**
     * Wrap a base context so any dialog built from it is coloured with the active Termux:Style
     * scheme from the first render (no post-show tint flash). Use this as the context for
     * {@code AlertDialog.Builder} / {@code MaterialAlertDialogBuilder} instead of the raw activity.
     */
    @NonNull
    public static Context schemeContext(@NonNull Context context) {
        return SchemeDialogTheme.wrap(context);
    }

    /** Recursively paint every TextView under {@code root} with {@code color}. */
    public static void tintViewTreeText(@NonNull View root, int color) {
        if (root instanceof TextView) {
            ((TextView) root).setTextColor(color);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintViewTreeText(group.getChildAt(i), color);
            }
        }
    }

    /** Build a single-colour pressed/selected selector for list highlights. */
    @NonNull
    public static android.graphics.drawable.StateListDrawable makeHighlightSelectorPublic(@NonNull TermuxColorSchemeManager csm) {
        int highlight = SettingsColorScheme.withAlpha(csm.getSchemeForeground(), 0x26); // scheme fg @15%
        return makeHighlightSelectorPublic(highlight);
    }

    /** Build a single-colour pressed/selected selector for list highlights from an explicit colour. */
    @NonNull
    public static android.graphics.drawable.StateListDrawable makeHighlightSelectorPublic(int highlight) {
        android.graphics.drawable.StateListDrawable selector =
                new android.graphics.drawable.StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(highlight));
        selector.addState(new int[]{android.R.attr.state_selected},
                new ColorDrawable(highlight));
        selector.addState(new int[]{}, new ColorDrawable(0));
        return selector;
    }
}
