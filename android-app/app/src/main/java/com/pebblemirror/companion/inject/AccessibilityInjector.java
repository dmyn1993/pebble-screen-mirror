package com.pebblemirror.companion.inject;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pebblemirror.companion.protocol.WatchProtocol;

import java.util.ArrayList;
import java.util.List;

/** Synthesizes gestures through the optional accessibility service. */
public final class AccessibilityInjector implements GestureInjector {
    private static final String TAG = "PebbleMirrorA11y";
    private final Handler main = new Handler(Looper.getMainLooper());

    public static boolean isServiceAvailable() {
        return MirrorAccessibilityService.getInstance() != null;
    }

    @Override
    public boolean tap(int x, int y) {
        return stroke(x, y, x + 1, y, 50);
    }

    @Override
    public boolean longPress(int x, int y) {
        return stroke(x, y, x + 1, y, 700);
    }

    @Override
    public boolean swipe(int x1, int y1, int x2, int y2, long durationMs) {
        return stroke(x1, y1, x2, y2, Math.max(80, Math.min(2000, durationMs)));
    }

    @Override
    public boolean path(List<int[]> points, long durationMs) {
        if (points == null || points.size() < 2
                || MirrorAccessibilityService.getInstance() == null) {
            return false;
        }
        final List<int[]> copy = new ArrayList<>(points);
        main.post(() -> {
            MirrorAccessibilityService service = MirrorAccessibilityService.getInstance();
            if (service == null) {
                return;
            }
            Path path = new Path();
            int[] first = copy.get(0);
            path.moveTo(first[0], first[1]);
            for (int i = 1; i < copy.size(); i++) {
                int[] point = copy.get(i);
                path.lineTo(point[0], point[1]);
            }
            long duration = Math.max(80, Math.min(30000, durationMs));
            Log.i(TAG, "dispatchPath " + copy.size() + " points duration=" + duration);
            dispatchPath(service, path, duration);
        });
        return true;
    }

    @Override
    public boolean key(int key) {
        final int action;
        if (key == WatchProtocol.KEY_BACK) {
            action = AccessibilityService.GLOBAL_ACTION_BACK;
        } else if (key == WatchProtocol.KEY_HOME) {
            action = AccessibilityService.GLOBAL_ACTION_HOME;
        } else if (key == WatchProtocol.KEY_RECENTS) {
            action = AccessibilityService.GLOBAL_ACTION_RECENTS;
        } else {
            return false;
        }
        main.post(() -> {
            MirrorAccessibilityService service = MirrorAccessibilityService.getInstance();
            if (service != null) {
                boolean result = service.performGlobalAction(action);
                Log.i(TAG, "global action " + action + " -> " + result);
            }
        });
        return true;
    }

    private boolean stroke(int x1, int y1, int x2, int y2, long durationMs) {
        if (MirrorAccessibilityService.getInstance() == null) {
            return false;
        }
        main.post(() -> {
            MirrorAccessibilityService service = MirrorAccessibilityService.getInstance();
            if (service == null) {
                return;
            }
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            Log.i(TAG, "dispatchGesture " + x1 + "," + y1 + " -> " + x2 + "," + y2
                    + " duration=" + durationMs);
            dispatchPath(service, path, durationMs);
        });
        return true;
    }

    private void dispatchPath(MirrorAccessibilityService service, Path path,
                              long durationMs) {
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        service.dispatchGesture(gesture,
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        Log.i(TAG, "gesture completed");
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        Log.w(TAG, "gesture cancelled");
                    }
                }, null);
    }

    @Override
    public String describe() {
        return "无障碍服务";
    }

    @Override
    public boolean isAvailable() {
        return isServiceAvailable();
    }
}
