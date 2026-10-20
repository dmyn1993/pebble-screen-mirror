package com.pebblemirror.companion.inject;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs as shell/root through app_process. It injects the entire path from one
 * process, so the Shizuku/Root path has no per-point {@code input} startup
 * cost and can preserve both shape and real gesture duration.
 *
 * Usage: PathInjectorMain <durationMs> <x0> <y0> <x1> <y1> ...
 */
public final class PathInjectorMain {
    public static void main(String[] args) {
        try {
            if (args.length < 4 || ((args.length - 1) % 2) != 0) {
                System.err.println("usage: PathInjectorMain <durationMs> <x y>...");
                System.exit(2);
            }
            long durationMs = Long.parseLong(args[0]);
            durationMs = Math.max(50, Math.min(30000, durationMs));
            List<int[]> points = new ArrayList<>();
            for (int i = 1; i + 1 < args.length; i += 2) {
                points.add(new int[]{
                        Integer.parseInt(args[i]),
                        Integer.parseInt(args[i + 1])
                });
            }
            if (points.size() < 2) {
                System.exit(2);
            }

            Class<?> managerClass = Class.forName("android.hardware.input.InputManagerGlobal");
            Method getInstance = managerClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object manager = getInstance.invoke(null);
            Method injectMethod = managerClass.getDeclaredMethod(
                    "injectInputEvent", InputEvent.class, int.class);
            injectMethod.setAccessible(true);

            long start = SystemClock.uptimeMillis();
            int[] first = points.get(0);
            inject(injectMethod, manager, MotionEvent.obtain(
                    start, start, MotionEvent.ACTION_DOWN, first[0], first[1], 0));

            for (int i = 1; i < points.size(); i++) {
                long target = start + durationMs * i / (points.size() - 1);
                sleepUntil(target);
                int[] point = points.get(i);
                inject(injectMethod, manager, MotionEvent.obtain(
                        start, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                        point[0], point[1], 0));
            }

            sleepUntil(start + durationMs);
            int[] last = points.get(points.size() - 1);
            inject(injectMethod, manager, MotionEvent.obtain(
                    start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                    last[0], last[1], 0));
            System.out.println("path-ok");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void inject(Method method, Object manager, MotionEvent event)
            throws Exception {
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            Object result = method.invoke(manager, event, 0);
            if (Boolean.FALSE.equals(result)) {
                throw new IllegalStateException("injectInputEvent returned false");
            }
        } finally {
            event.recycle();
        }
    }

    private static void sleepUntil(long targetMs) {
        long remaining = targetMs - SystemClock.uptimeMillis();
        if (remaining <= 0) {
            return;
        }
        try {
            Thread.sleep(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
