package com.termux.app.terminal;

import android.graphics.Paint;
import android.widget.TextView;

import androidx.annotation.NonNull;

public final class SessionAppearanceUtils {
    private SessionAppearanceUtils() {}

    /**
     * Применяет стиль завершённой/упавшей сессии: красный цвет при
     * {@code !running && exitStatus != 0}, иначе defaultColor, а также
     * strike-through при {@code !running}.
     */
    public static void applyFinishedSessionStyling(@NonNull TextView textView,
            boolean running, int exitStatus, int errorColor, int defaultColor) {
        if (!running && exitStatus != 0) {
            textView.setTextColor(errorColor);
        } else {
            textView.setTextColor(defaultColor);
        }
        if (!running) {
            textView.setPaintFlags(textView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            textView.setPaintFlags(textView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        }
    }
}
