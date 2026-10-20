package com.pebblemirror.companion.inject;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/** Small, defensive wrapper around the Shizuku API. */
public final class ShizukuCompat {
    private ShizukuCompat() {
    }

    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isAvailable() {
        return isBinderAlive() && hasPermission();
    }

    public static void requestPermission(int requestCode) {
        try {
            Shizuku.requestPermission(requestCode);
        } catch (Throwable ignored) {
            // Shizuku is not running or is too old; the caller shows a hint.
        }
    }

    public static void openManager(Context context) {
        Intent intent = context.getPackageManager()
                .getLaunchIntentForPackage("moe.shizuku.manager");
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    /**
     * Run a shell command as the Shizuku shell user. Returns the combined output;
     * an empty string usually means success for silent commands like `input tap`.
     */
    public static String run(String command) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("Shizuku is not available");
        }
        Process process = newProcess(new String[]{"sh", "-c", command});
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy();
                throw new IllegalStateException("Shell command timed out: " + command);
            }
        } catch (IllegalArgumentException e) {
            // Some Shizuku builds race between waitFor(timeout) and exitValue()
            // for very short-lived app_process helpers. The stdout reader has
            // already reached EOF at this point, so treat it as completed.
        }
        return output.toString();
    }

    /**
     * Shizuku 13.1.5 made newProcess() private and points developers at
     * bindUserService(). For a handful of short `input ...` commands the
     * deprecated process API is still the simplest option, so call it through
     * reflection. Revisit if a future Shizuku removes it entirely.
     */
    private static Process newProcess(String[] command) throws Exception {
        try {
            Method method = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            method.setAccessible(true);
            return (Process) method.invoke(null, command, null, null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
}
