package com.termux.app.terminal;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

public final class SessionTitleUtils {
    private SessionTitleUtils() {}

    /** Возвращает отображаемое имя сессии по правилу: name если не пуст, иначе title, иначе дефолтный ресурс. */
    public static String resolveDisplayName(@NonNull Context context,
            @Nullable String sessionName, @Nullable String title) {
        if (sessionName != null && !sessionName.isEmpty()) return sessionName;
        if (title != null && !title.isEmpty()) return title;
        return context.getString(R.string.session_default_title);
    }
}
