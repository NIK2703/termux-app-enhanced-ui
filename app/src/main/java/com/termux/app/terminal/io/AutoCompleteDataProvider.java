package com.termux.app.terminal.io;

import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminal.TermuxColorSchemeManager;

import java.util.ArrayList;

/**
 * Narrow read-only data + callback surface that {@link AutoCompletePopupManager}
 * uses to build and position the suggestion windows. Keeping this an explicit
 * interface (rather than handing the manager the whole {@code AutoCompleteController})
 * enforces the responsibility split:
 *
 * <ul>
 *   <li>{@code AutoCompleteController} — owns the suggestion data, the fetch/merge
 *       pipeline, input handling and candidate insertion;</li>
 *   <li>{@code AutoCompletePopupManager} — owns the TWO popup windows (build,
 *       show, position, dismiss, per-window content rebuild & bold-span refresh);</li>
 *   <li>{@code AutoCompleteTextRenderer} — pure string/Spannable shaping.</li>
 * </ul>
 */
interface AutoCompleteDataProvider {

    /** Current merged suggestion strings (shell completions first, then history). */
    @NonNull ArrayList<String> getSuggestions();

    /** Parallel isShell flags for {@link #getSuggestions()}. */
    @NonNull ArrayList<Boolean> getIsShell();

    /** Parallel shell candidate types (null for history entries). */
    @NonNull ArrayList<ShellCompletionProvider.CandidateType> getShellTypes();

    /** Parallel shell candidate metadata (null for history entries). */
    @NonNull ArrayList<ShellCompletionProvider.ShellCandidate> getShellMeta();

    /** Number of leading shell-completion entries in the suggestion list. */
    int getShellSuggestionCount();

    /** Max number of suggestions to RENDER (user setting). */
    int getDisplayMax();

    /** The EditText the popups are anchored to. */
    @Nullable EditText getInputField();

    /** Colour-scheme manager vending popup colours. */
    @NonNull TermuxColorSchemeManager getColorSchemeManager();

    /** Host window (for the global layout listener), or null if unavailable. */
    @Nullable android.view.Window getWindow();

    /** Build a single suggestion row TextView (wires the tap/swipe handlers). */
    @NonNull TextView buildSuggestionTextView(@NonNull String suggestion, @NonNull String input, boolean isShell);

    /** Current history version (to skip a rebuild when history is unchanged). */
    int getHistoryVersion();

    /** Callback run when the popup is dismissed by a suggestion tap. */
    void onSuggestionDismissed();
}
