package com.pebblemirror.companion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe touch-trail state shared by the mirror controller and the
 * phone-side overlay window. Points are in phone-screen coordinates.
 */
public final class TouchTrailOverlay {
    public static final long TRAIL_MS = 3000;
    private static final int MAX_POINTS = 300;

    public interface Listener {
        void onTrailChanged();
    }

    public static final class Point {
        public final int x;
        public final int y;
        public final long timeMs;

        Point(int x, int y, long timeMs) {
            this.x = x;
            this.y = y;
            this.timeMs = timeMs;
        }
    }

    private static final Object LOCK = new Object();
    private static final ArrayDeque<Point> POINTS = new ArrayDeque<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();

    private TouchTrailOverlay() {
    }

    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void add(int x, int y) {
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (LOCK) {
            POINTS.addLast(new Point(x, y, now));
            trimLocked(now);
        }
        notifyListeners();
    }

    public static void clear() {
        synchronized (LOCK) {
            POINTS.clear();
        }
        notifyListeners();
    }

    /** Returns a copy containing only points that are still inside TRAIL_MS. */
    public static List<Point> snapshot() {
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (LOCK) {
            trimLocked(now);
            return new ArrayList<>(POINTS);
        }
    }

    private static void trimLocked(long now) {
        while (!POINTS.isEmpty()
                && (now - POINTS.peekFirst().timeMs > TRAIL_MS
                || POINTS.size() > MAX_POINTS)) {
            POINTS.removeFirst();
        }
    }

    /**
     * MediaProjection on some OEM builds captures application overlays even when
     * they are FLAG_SECURE. Erase the known trail shape from the scaled mirrored
     * frame so it only remains visible on the phone screen.
     */
    public static void eraseFromFrame(int[] pixels, int scaledWidth, int scaledHeight,
                                      int phoneWidth, int phoneHeight) {
        List<Point> points = snapshot();
        if (pixels == null || points.isEmpty() || scaledWidth <= 0 || scaledHeight <= 0
                || phoneWidth <= 0 || phoneHeight <= 0) {
            return;
        }

        boolean[] mask = new boolean[scaledWidth * scaledHeight];
        Point previous = null;
        for (Point point : points) {
            int sx = map(point.x, phoneWidth, scaledWidth);
            int sy = map(point.y, phoneHeight, scaledHeight);
            if (previous != null) {
                int psx = map(previous.x, phoneWidth, scaledWidth);
                int psy = map(previous.y, phoneHeight, scaledHeight);
                markLine(mask, scaledWidth, scaledHeight, psx, psy, sx, sy, 5);
            }
            markDot(mask, scaledWidth, scaledHeight, sx, sy, 7);
            previous = point;
        }
        inpaint(pixels, scaledWidth, scaledHeight, mask, 10);
    }

    private static int map(int value, int sourceSize, int targetSize) {
        int mapped = (int) ((long) value * targetSize / Math.max(1, sourceSize));
        return Math.max(0, Math.min(targetSize - 1, mapped));
    }

    private static void markLine(boolean[] mask, int width, int height,
                                 int x1, int y1, int x2, int y2, int radius) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        for (int i = 0; i <= steps; i++) {
            int x = x1 + dx * i / Math.max(1, steps);
            int y = y1 + dy * i / Math.max(1, steps);
            markDot(mask, width, height, x, y, radius);
        }
    }

    private static void markDot(boolean[] mask, int width, int height,
                                int centerX, int centerY, int radius) {
        for (int dy = -radius; dy <= radius; dy++) {
            int y = centerY + dy;
            if (y < 0 || y >= height) {
                continue;
            }
            for (int dx = -radius; dx <= radius; dx++) {
                int x = centerX + dx;
                if (x < 0 || x >= width || dx * dx + dy * dy > radius * radius) {
                    continue;
                }
                mask[y * width + x] = true;
            }
        }
    }

    private static void inpaint(int[] pixels, int width, int height,
                                boolean[] mask, int sampleDistance) {
        int[] offsets = {
                -sampleDistance, 0, sampleDistance, 0,
                0, -sampleDistance, 0, sampleDistance,
                -sampleDistance, -sampleDistance, sampleDistance, sampleDistance,
                -sampleDistance, sampleDistance, sampleDistance, -sampleDistance
        };
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (!mask[index]) {
                    continue;
                }
                int red = 0;
                int green = 0;
                int blue = 0;
                int count = 0;
                for (int i = 0; i < offsets.length; i += 2) {
                    int sx = x + offsets[i];
                    int sy = y + offsets[i + 1];
                    if (sx < 0 || sx >= width || sy < 0 || sy >= height) {
                        continue;
                    }
                    int sampleIndex = sy * width + sx;
                    if (mask[sampleIndex]) {
                        continue;
                    }
                    int color = pixels[sampleIndex];
                    red += (color >> 16) & 0xff;
                    green += (color >> 8) & 0xff;
                    blue += color & 0xff;
                    count++;
                }
                if (count > 0) {
                    pixels[index] = 0xff000000
                            | ((red / count) << 16)
                            | ((green / count) << 8)
                            | (blue / count);
                }
            }
        }
    }

    private static void notifyListeners() {
        for (Listener listener : LISTENERS) {
            try {
                listener.onTrailChanged();
            } catch (Throwable ignored) {
                // A destroyed overlay must not break touch handling.
            }
        }
    }
}
