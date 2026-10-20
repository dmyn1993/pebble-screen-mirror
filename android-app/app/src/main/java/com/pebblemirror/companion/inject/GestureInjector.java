package com.pebblemirror.companion.inject;

import java.util.List;

/** Injects taps, swipes and global keys into the phone UI. */
public interface GestureInjector {
    boolean tap(int x, int y);

    boolean longPress(int x, int y);

    boolean swipe(int x1, int y1, int x2, int y2, long durationMs);

    /**
     * Replays a free-form polyline. The default keeps old injectors working by
     * reducing the path to one straight swipe; accessibility overrides this.
     */
    default boolean path(List<int[]> points, long durationMs) {
        if (points == null || points.size() < 2) {
            return false;
        }
        int[] first = points.get(0);
        int[] last = points.get(points.size() - 1);
        return swipe(first[0], first[1], last[0], last[1], durationMs);
    }

    boolean key(int key);

    String describe();

    boolean isAvailable();
}
