package com.pebblemirror.companion.inject;

import android.os.SystemClock;
import android.util.Log;

import com.pebblemirror.companion.protocol.WatchProtocol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Injects input by running {@code input ...} through Shizuku's shell or a root
 * shell. Commands are serialized on a background thread; gesture methods return
 * immediately and report an optimistic result.
 */
public final class ShellInjector implements GestureInjector {
    private static final String TAG = "PebbleMirrorShell";
    private static final String APP_PACKAGE = "com.pebblemirror.companion";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile Boolean rootAvailable;

    public enum Mode {
        SHIZUKU,
        ROOT
    }

    private final Mode mode;

    public ShellInjector(Mode mode) {
        this.mode = mode;
    }

    public static void detectRootAsync() {
        if (rootAvailable != null) {
            return;
        }
        EXECUTOR.execute(() -> rootAvailable = probeRoot());
    }

    /** Allow the user to re-trigger the root prompt after changing their mind. */
    public static void resetRootDetection() {
        rootAvailable = null;
    }

    public static boolean isRootAvailable() {
        return Boolean.TRUE.equals(rootAvailable);
    }

    public static boolean isRootCheckPending() {
        return rootAvailable == null;
    }

    private static boolean probeRoot() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true)
                    .start();
            // Wait first: a root manager prompt can otherwise block readLine()
            // forever and leave the detection thread stuck.
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy();
                return false;
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            return process.exitValue() == 0 && output.toString().contains("uid=0");
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @Override
    public boolean tap(int x, int y) {
        return submit("/system/bin/input tap " + x + " " + y);
    }

    @Override
    public boolean longPress(int x, int y) {
        return submit("/system/bin/input swipe " + x + " " + y + " " + x + " " + y + " 700");
    }

    @Override
    public boolean swipe(int x1, int y1, int x2, int y2, long durationMs) {
        long duration = Math.max(80, Math.min(2000, durationMs));
        return submit("/system/bin/input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + duration);
    }

    @Override
    public boolean path(List<int[]> points, long durationMs) {
        if (!isAvailable() || points == null || points.size() < 2) {
            return false;
        }
        List<int[]> sampled = points;
        if (sampled.size() > 256) {
            sampled = resample(points, 256);
        }
        long duration = Math.max(50, Math.min(3000, durationMs));
        StringBuilder args = new StringBuilder();
        args.append(duration);
        for (int[] point : sampled) {
            args.append(' ').append(point[0]).append(' ').append(point[1]);
        }
        StringBuilder helper = new StringBuilder();
        helper.append("app=$(pm path ").append(APP_PACKAGE)
                .append(" | head -1 | cut -d: -f2); ");
        helper.append("CLASSPATH=$app /system/bin/app_process /system/bin ")
                .append(PathInjectorMain.class.getName())
                .append(' ').append(args);
        String fallback = pathFallbackCommand(points, duration);
        Log.i(TAG, "path via app_process: " + sampled.size() + " points, "
                + duration + "ms");
        return submit(helper + " || { " + fallback + "; }");
    }

    /**
     * Fallback for devices where app_process/InputManagerGlobal is blocked.
     * Each `input motionevent` is a separate app_process, so use fewer points
     * for short gestures and compensate the fixed process cost.
     */
    private String pathFallbackCommand(List<int[]> points, long durationMs) {
        final long duration = Math.max(100, Math.min(3000, durationMs));
        final int targetPoints = (int) (duration / 28);
        int count = Math.max(8, Math.min(60, targetPoints));
        count = Math.min(count, points.size());
        List<int[]> sampled = resample(points, count);
        long stepMs = Math.max(0,
                duration / Math.max(1, sampled.size()) - 28);

        StringBuilder command = new StringBuilder();
        int[] first = sampled.get(0);
        command.append("/system/bin/input motionevent DOWN ")
                .append(first[0]).append(' ').append(first[1]);
        for (int i = 1; i < sampled.size(); i++) {
            int[] point = sampled.get(i);
            if (stepMs > 0) {
                command.append("; /system/bin/sleep ")
                        .append(String.format(Locale.US, "%.3f", stepMs / 1000.0));
            }
            command.append("; /system/bin/input motionevent MOVE ")
                    .append(point[0]).append(' ').append(point[1]);
        }
        if (stepMs > 0) {
            command.append("; /system/bin/sleep ")
                    .append(String.format(Locale.US, "%.3f", stepMs / 1000.0));
        }
        int[] last = sampled.get(sampled.size() - 1);
        command.append("; /system/bin/input motionevent UP ")
                .append(last[0]).append(' ').append(last[1]);
        return command.toString();
    }

    /** Uniform arc-length resampling so fast shell gestures keep their shape. */
    private static List<int[]> resample(List<int[]> points, int count) {
        if (count >= points.size() || count < 2) {
            return points;
        }
        double[] cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            int[] a = points.get(i - 1);
            int[] b = points.get(i);
            cumulative[i] = cumulative[i - 1]
                    + Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        double total = cumulative[cumulative.length - 1];
        if (total <= 0) {
            return points.subList(0, 2);
        }
        List<int[]> out = new ArrayList<>(count);
        out.add(points.get(0));
        int segment = 1;
        for (int i = 1; i < count - 1; i++) {
            double target = total * i / (count - 1);
            while (segment < points.size() - 1
                    && cumulative[segment] < target) {
                segment++;
            }
            int[] a = points.get(segment - 1);
            int[] b = points.get(segment);
            double span = cumulative[segment] - cumulative[segment - 1];
            double t = span > 0
                    ? (target - cumulative[segment - 1]) / span : 0;
            out.add(new int[]{
                    (int) Math.round(a[0] + (b[0] - a[0]) * t),
                    (int) Math.round(a[1] + (b[1] - a[1]) * t)
            });
        }
        out.add(points.get(points.size() - 1));
        return out;
    }

    @Override
    public boolean key(int key) {
        final int keyCode;
        if (key == WatchProtocol.KEY_BACK) {
            keyCode = 4;
        } else if (key == WatchProtocol.KEY_HOME) {
            keyCode = 3;
        } else if (key == WatchProtocol.KEY_RECENTS) {
            keyCode = 187;
        } else {
            return false;
        }
        return submit("/system/bin/input keyevent " + keyCode);
    }

    private boolean submit(String command) {
        if (!isAvailable()) {
            return false;
        }
        EXECUTOR.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            try {
                if (mode == Mode.SHIZUKU) {
                    String output = ShizukuCompat.run(command);
                    if (!output.isEmpty()) {
                        Log.i(TAG, "shizuku output: " + output.trim());
                    }
                } else {
                    runRoot(command);
                }
                Log.i(TAG, "command finished in "
                        + (SystemClock.elapsedRealtime() - started) + "ms");
            } catch (Throwable t) {
                Log.w(TAG, "Injection failed: " + command, t);
            }
        });
        return true;
    }

    private static void runRoot(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy();
            throw new IllegalStateException("Root command timed out: " + command);
        }
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (process.exitValue() != 0) {
            Log.w(TAG, "Root command exited " + process.exitValue() + ": " + output);
        }
    }

    @Override
    public String describe() {
        return mode == Mode.SHIZUKU ? "Shizuku" : "Root";
    }

    @Override
    public boolean isAvailable() {
        return mode == Mode.SHIZUKU ? ShizukuCompat.isAvailable() : isRootAvailable();
    }
}
