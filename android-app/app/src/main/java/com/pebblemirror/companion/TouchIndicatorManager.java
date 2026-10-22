package com.pebblemirror.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.pebblemirror.companion.inject.ShellInjector;
import com.pebblemirror.companion.inject.ShizukuCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Toggles the system "show touches" pointer dot while mirroring.
 *
 * "show_touches" is a private secure setting, so a normal app cannot change it
 * even with WRITE_SETTINGS. We deliberately use Shizuku (or a root shell), which
 * already powers the optional input injectors, and fall back to no indicator if
 * neither is available.
 */
public final class TouchIndicatorManager {
    private static final String TAG = "PebbleMirrorTouches";
    private static final String PREFS = "touch_indicator";
    private static final String KEY_ORIGINAL = "original_value";
    private static final String KEY_ACTIVE = "active";
    private static final String SECURE_KEY = "show_touches";

    private TouchIndicatorManager() {
    }

    public static boolean isAvailable() {
        return ShizukuCompat.isAvailable() || ShellInjector.isRootAvailable();
    }

    public static boolean begin(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ACTIVE, false)) {
            return true;
        }
        if (!isAvailable()) {
            Log.w(TAG, "no Shizuku/root shell available");
            return false;
        }

        String original;
        try {
            original = runShell("settings get secure " + SECURE_KEY).trim();
        } catch (Throwable t) {
            Log.w(TAG, "could not read show_touches", t);
            return false;
        }
        if (original.isEmpty()) {
            original = "null";
        }
        if ("1".equals(original)) {
            return true;
        }

        if (!prefs.edit()
                .putString(KEY_ORIGINAL, original)
                .putBoolean(KEY_ACTIVE, true)
                .commit()) {
            return false;
        }
        if (!runShellOk("settings put secure " + SECURE_KEY + " 1")) {
            Log.w(TAG, "could not enable show_touches");
            prefs.edit().clear().commit();
            return false;
        }
        Log.i(TAG, "show_touches: " + original + " -> 1");
        return true;
    }

    public static void end(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ACTIVE, false)) {
            return;
        }
        String original = prefs.getString(KEY_ORIGINAL, "null");
        String command = "null".equals(original)
                ? "settings delete secure " + SECURE_KEY
                : "settings put secure " + SECURE_KEY + " " + original;
        if (!runShellOk(command)) {
            Log.w(TAG, "could not restore show_touches");
            return;
        }
        prefs.edit().clear().commit();
        Log.i(TAG, "show_touches restored: " + original);
    }

    public static void restoreIfStale(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ACTIVE, false)) {
            end(context);
        }
    }

    private static boolean runShellOk(String command) {
        try {
            String output = runShell(command + " && echo pebble-ok");
            return output.contains("pebble-ok");
        } catch (Throwable t) {
            Log.w(TAG, "shell command failed: " + command, t);
            return false;
        }
    }

    private static String runShell(String command) throws Exception {
        if (ShizukuCompat.isAvailable()) {
            return ShizukuCompat.run(command);
        }
        if (ShellInjector.isRootAvailable()) {
            return runRoot(command);
        }
        throw new IllegalStateException("no Shizuku/root shell");
    }

    private static String runRoot(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy();
            throw new IllegalStateException("root command timed out");
        }
        return output.toString();
    }
}
