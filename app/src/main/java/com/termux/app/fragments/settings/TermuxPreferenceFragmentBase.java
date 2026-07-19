package com.termux.app.fragments.settings;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragmentCompat;

/**
 * Base class for all Termux settings fragments. Removes the default AndroidX
 * Preference dividers so no stray separator line is drawn at the end of each
 * preference category (sub-section) on a settings screen.
 */
public abstract class TermuxPreferenceFragmentBase extends PreferenceFragmentCompat {

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setDivider(null);
    }
}
