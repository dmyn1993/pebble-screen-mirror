package com.pebblemirror.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * Temporarily sets the system screen-off timeout to "never" while mirroring and
 * restores the exact previous value afterwards.
 *
 * Android's WRITE_SETTINGS is a special app-op permission; if it is not granted
 * this class is a no-op and MainActivity shows a grant button.
 */
public final class ScreenTimeoutManager {
    private static final String TAG = "PebbleMirrorTimeout";
    private static final String PREFS = "screen_timeout";
    private static final String KEY_ORIGINAL = "original_ms";
    private static final String KEY_ACTIVE = "active";
    private static final int FALLBACK_TIMEOUT_MS = 30000;
    private static final int KEEP_AWAKE_TIMEOUT_MS = Integer.MAX_VALUE;

    private ScreenTimeoutManager() {
    }

    public static boolean canWrite(Context context) {
        try {
            return Settings.System.canWrite(context);
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return true if the timeout is now (or already was) "never". */
    public static boolean begin(Context context) {
        if (!canWrite(context)) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ACTIVE, false)) {
            return true;
        }

        int original;
        try {
            original = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, FALLBACK_TIMEOUT_MS);
        } catch (Throwable t) {
            original = FALLBACK_TIMEOUT_MS;
        }
        if (original == KEEP_AWAKE_TIMEOUT_MS) {
            return true;
        }

        // Persist before modifying so a crash can be repaired on next launch.
        if (!prefs.edit()
                .putInt(KEY_ORIGINAL, original)
                .putBoolean(KEY_ACTIVE, true)
                .commit()) {
            return false;
        }
        if (!putTimeout(context, KEEP_AWAKE_TIMEOUT_MS)) {
            prefs.edit().clear().commit();
            return false;
        }
        Log.i(TAG, "screen timeout: " + original + " -> never");
        return true;
    }

    /** Restore the value captured by begin(). */
    public static void end(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ACTIVE, false)) {
            return;
        }
        int original = prefs.getInt(KEY_ORIGINAL, FALLBACK_TIMEOUT_MS);
        if (!putTimeout(context, original)) {
            // Keep the saved value so a later launch can retry.
            Log.w(TAG, "could not restore screen timeout");
            return;
        }
        prefs.edit().clear().commit();
        Log.i(TAG, "screen timeout restored: " + original);
    }

    /** If a previous process died while mirroring, restore on next app launch. */
    public static void restoreIfStale(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ACTIVE, false)) {
            end(context);
        }
    }

    private static boolean putTimeout(Context context, int timeoutMs) {
        try {
            return Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, timeoutMs);
        } catch (Throwable t) {
            Log.w(TAG, "unable to write screen timeout", t);
            return false;
        }
    }
}
