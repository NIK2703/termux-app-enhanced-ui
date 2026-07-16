package com.termux.app.terminal.io;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure data model for per-session text input state.
 * <p>
 * Holds 4 maps keyed by {@link TerminalSession#mHandle}:
 * input text, panel visibility, focus state, caret position.
 * Provides save/restore to/from {@link Bundle} for activity recreation.
 */
public final class TextInputSessionStateManager {

    public static final String ARG_TEXT_INPUT_PER_SESSION = "text_input_per_session";
    public static final String ARG_TEXT_INPUT_VISIBLE_PER_SESSION = "text_input_visible_per_session";
    public static final String ARG_FOCUS_ON_INPUT_PER_SESSION = "focus_on_input_per_session";
    public static final String ARG_TEXT_INPUT_CARET_PER_SESSION = "text_input_caret_per_session";

    private final HashMap<String, String> mTextInputPerSession = new HashMap<>();
    private final HashMap<String, Boolean> mTextInputVisiblePerSession = new HashMap<>();
    private final HashMap<String, Boolean> mFocusOnInputPerSession = new HashMap<>();
    private final HashMap<String, Integer> mTextInputCaretPerSession = new HashMap<>();

    // ── Text input content ──

    public void saveInput(@NonNull String handle, @Nullable String text) {
        if (text != null && !text.isEmpty()) {
            mTextInputPerSession.put(handle, text);
        } else {
            mTextInputPerSession.remove(handle);
        }
    }

    @Nullable
    public String getInputText(@NonNull String handle) {
        return mTextInputPerSession.get(handle);
    }

    public boolean hasInput(@NonNull String handle) {
        return mTextInputPerSession.containsKey(handle);
    }

    // ── Panel visibility ──

    public void setVisible(@NonNull String handle, boolean visible) {
        mTextInputVisiblePerSession.put(handle, visible);
    }

    public boolean isVisible(@NonNull String handle) {
        return mTextInputVisiblePerSession.getOrDefault(handle, false);
    }

    public boolean hasVisible(@NonNull String handle) {
        return mTextInputVisiblePerSession.containsKey(handle);
    }

    // ── Focus (panel vs terminal) ──

    public void setFocusOnInput(@NonNull String handle, boolean focusOnInput) {
        mFocusOnInputPerSession.put(handle, focusOnInput);
    }

    public void setFocusOnInput(@Nullable TerminalSession session, boolean focusOnInput) {
        if (session != null) {
            mFocusOnInputPerSession.put(session.mHandle, focusOnInput);
        }
    }

    public boolean isFocusOnInput(@NonNull String handle) {
        return mFocusOnInputPerSession.getOrDefault(handle, false);
    }

    public boolean isFocusOnInput(@Nullable TerminalSession session) {
        return session != null && mFocusOnInputPerSession.getOrDefault(session.mHandle, false);
    }

    @NonNull
    public HashMap<String, Boolean> getFocusOnInputMap() {
        return mFocusOnInputPerSession;
    }

    // ── Caret ──

    /**
     * Store the caret (cursor) position for a session.
     * Values below 0 (e.g. -1, the sentinel returned by {@code EditText.getSelectionStart()}
     * when nothing is selected) are not valid positions and are ignored, so the stored
     * map only ever contains real indices.
     */
    public void setCaret(@NonNull String handle, int caret) {
        if (caret < 0) return;
        mTextInputCaretPerSession.put(handle, caret);
    }

    /**
     * @return the stored caret position, or {@code -1} if none has been recorded
     *         (distinct from position 0).
     */
    public int getCaret(@NonNull String handle) {
        Integer caret = mTextInputCaretPerSession.get(handle);
        return caret != null ? caret : -1;
    }

    public boolean hasCaret(@NonNull String handle) {
        return mTextInputCaretPerSession.containsKey(handle);
    }

    // ── Cleanup ──

    /**
     * Clear only the input text, caret and focus for a session, leaving its
     * recorded visibility state intact. Used after sending when the panel is
     * configured to stay open, so the per-session visible flag (which
     * {@link #isTextInputVisible(String)} / {@code onBackPressed} rely on) is
     * not wiped.
     */
    public void clearInput(@NonNull String handle) {
        mTextInputPerSession.remove(handle);
        mFocusOnInputPerSession.remove(handle);
        mTextInputCaretPerSession.remove(handle);
    }

    public void clear(@NonNull String handle) {
        mTextInputPerSession.remove(handle);
        mTextInputVisiblePerSession.remove(handle);
        mFocusOnInputPerSession.remove(handle);
        mTextInputCaretPerSession.remove(handle);
    }

    public void clear(@Nullable TerminalSession session) {
        if (session != null) clear(session.mHandle);
    }

    public void clearAll() {
        mTextInputPerSession.clear();
        mTextInputVisiblePerSession.clear();
        mFocusOnInputPerSession.clear();
        mTextInputCaretPerSession.clear();
    }

    // ── Bundle persistence (for activity recreation) ──

    public void restoreFromBundle(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        Bundle textInputBundle = savedInstanceState.getBundle(ARG_TEXT_INPUT_PER_SESSION);
        if (textInputBundle != null) {
            for (String key : textInputBundle.keySet()) {
                String value = textInputBundle.getString(key);
                if (value != null) mTextInputPerSession.put(key, value);
            }
        }

        Bundle visBundle = savedInstanceState.getBundle(ARG_TEXT_INPUT_VISIBLE_PER_SESSION);
        if (visBundle != null) {
            for (String key : visBundle.keySet()) {
                mTextInputVisiblePerSession.put(key, visBundle.getBoolean(key));
            }
        }

        Bundle focusBundle = savedInstanceState.getBundle(ARG_FOCUS_ON_INPUT_PER_SESSION);
        if (focusBundle != null) {
            for (String key : focusBundle.keySet()) {
                mFocusOnInputPerSession.put(key, focusBundle.getBoolean(key));
            }
        }

        Bundle caretBundle = savedInstanceState.getBundle(ARG_TEXT_INPUT_CARET_PER_SESSION);
        if (caretBundle != null) {
            for (String key : caretBundle.keySet()) {
                mTextInputCaretPerSession.put(key, caretBundle.getInt(key));
            }
        }
    }

    public void saveToBundle(@NonNull Bundle outState) {
        if (!mTextInputPerSession.isEmpty()) {
            Bundle textInputBundle = new Bundle();
            for (Map.Entry<String, String> e : mTextInputPerSession.entrySet()) {
                textInputBundle.putString(e.getKey(), e.getValue());
            }
            outState.putBundle(ARG_TEXT_INPUT_PER_SESSION, textInputBundle);
        }

        if (!mTextInputVisiblePerSession.isEmpty()) {
            Bundle visBundle = new Bundle();
            for (Map.Entry<String, Boolean> e : mTextInputVisiblePerSession.entrySet()) {
                visBundle.putBoolean(e.getKey(), e.getValue());
            }
            outState.putBundle(ARG_TEXT_INPUT_VISIBLE_PER_SESSION, visBundle);
        }

        if (!mFocusOnInputPerSession.isEmpty()) {
            Bundle focusBundle = new Bundle();
            for (Map.Entry<String, Boolean> e : mFocusOnInputPerSession.entrySet()) {
                focusBundle.putBoolean(e.getKey(), e.getValue());
            }
            outState.putBundle(ARG_FOCUS_ON_INPUT_PER_SESSION, focusBundle);
        }

        if (!mTextInputCaretPerSession.isEmpty()) {
            Bundle caretBundle = new Bundle();
            for (Map.Entry<String, Integer> e : mTextInputCaretPerSession.entrySet()) {
                caretBundle.putInt(e.getKey(), e.getValue());
            }
            outState.putBundle(ARG_TEXT_INPUT_CARET_PER_SESSION, caretBundle);
        }
    }
}
