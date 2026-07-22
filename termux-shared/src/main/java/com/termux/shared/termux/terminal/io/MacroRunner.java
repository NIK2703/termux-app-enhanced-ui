package com.termux.shared.termux.terminal.io;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.extrakeys.BindingTokenizer;

import java.util.List;

/**
 * Executes a macro binding asynchronously, processing tokens sequentially.
 * Non-delay tokens are dispatched immediately via {@link MacroDispatcher}.
 * When a delay token is encountered, the remainder is scheduled on the main
 * thread {@link Handler} with the specified delay.
 * <p>
 * Only one macro runs at a time (single-flight). Starting a new macro
 * automatically cancels any previously running macro via {@link #cancel()}.
 */
public class MacroRunner {

    public interface MacroDispatcher {
        void dispatchToken(String token);
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final MacroDispatcher mDispatcher;

    @Nullable
    private List<String> mTokens;
    private int mIndex;
    @Nullable
    private Runnable mPendingStep;
    private boolean mRunning;

    public MacroRunner(@NonNull MacroDispatcher dispatcher) {
        this.mDispatcher = dispatcher;
    }

    /**
     * Start (or restart) asynchronous execution of a tokenized macro binding.
     * Cancels any previously running macro.
     *
     * @param newTokens the tokens to execute (may contain DELAY_XXX tokens)
     */
    public void start(@NonNull List<String> newTokens) {
        cancel();
        if (newTokens.isEmpty()) return;

        // If the binding contains only delays and no real keys, do nothing
        boolean hasAction = false;
        for (String token : newTokens) {
            if (!BindingTokenizer.isDelay(token) && !BindingTokenizer.hasDelayPrefix(token)) {
                hasAction = true;
                break;
            }
        }
        if (!hasAction) return;

        mTokens = newTokens;
        mIndex = 0;
        mRunning = true;
        executeStep();
    }

    /**
     * Cancel the currently running macro, if any.
     * Pending delayed steps are removed from the handler queue.
     */
    public void cancel() {
        if (mPendingStep != null) {
            mHandler.removeCallbacks(mPendingStep);
            mPendingStep = null;
        }
        mRunning = false;
        mTokens = null;
        mIndex = 0;
    }

    /** @return true if a macro is currently executing or has pending delayed steps */
    public boolean isRunning() {
        return mRunning;
    }

    private void executeStep() {
        mPendingStep = null;

        while (mRunning && mTokens != null && mIndex < mTokens.size()) {
            String token = mTokens.get(mIndex++);

            if (BindingTokenizer.isDelay(token)) {
                long delayMs = BindingTokenizer.parseDelayMs(token);
                mPendingStep = this::executeStep;
                mHandler.postDelayed(mPendingStep, delayMs);
                return;
            }

            // Skip invalid delay-like tokens (e.g. DELAY_ABC) — don't send them as literal keys
            if (BindingTokenizer.hasDelayPrefix(token)) {
                continue;
            }

            mDispatcher.dispatchToken(token);
        }

        mRunning = false;
        mTokens = null;
        mIndex = 0;
    }
}
