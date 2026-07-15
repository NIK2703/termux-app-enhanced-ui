package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.text.TextUtils;
import android.util.TypedValue;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;

public final class TermuxActivityUtils {

    private TermuxActivityUtils() {}

    /**
     * Apply the stored screen orientation setting. Called from onCreate and onWindowFocusChanged.
     */
    public static void applyScreenOrientation(@NonNull Activity activity) {
        final SharedPreferences prefs = activity.getSharedPreferences("termux_prefs", Activity.MODE_PRIVATE);
        final boolean isTablet = activity.getResources().getConfiguration().smallestScreenWidthDp >= 600;
        final String value = prefs.getString("screen_orientation",
                isTablet ? "sensor" : "portrait");
        final int orientation;
        switch (value) {
            case "portrait":
                orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                break;
            case "landscape":
                orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                break;
            case "portrait_follow_sensor":
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                break;
            case "landscape_follow_sensor":
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                break;
            case "sensor":
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR;
                break;
            default:
                orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                break;
        }
        activity.setRequestedOrientation(orientation);
    }

    /**
     * Send a broadcast to reload styling. Static so it can be called from dialogs that
     * don't hold an Activity reference.
     */
    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    /**
     * Start TermuxActivity with a fresh task.
     */
    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    /**
     * Create a new intent to launch TermuxActivity.
     */
    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Start TermuxActivity and close all existing terminal sessions, opening a fresh one.
     * Used after a data restore so the user does not keep stale sessions.
     */
    public static void startTermuxActivityWithSessionReset(@NonNull final Context context) {
        Intent intent = newInstance(context);
        intent.putExtra(TERMUX_ACTIVITY.EXTRA_RESET_SESSIONS, true);
        ActivityUtils.startActivity(context, intent);
    }

    /**
     * Finish the activity preventing duplicate finish() calls.
     */
    public static void finishActivityIfNotFinishing(@NonNull Activity activity) {
        if (!activity.isFinishing()) {
            activity.finish();
        }
    }

    /**
     * Convert dp to pixels.
     */
    public static int dpToPx(@NonNull Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
