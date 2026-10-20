package com.pebblemirror.companion.inject;

/** Classifies a stream of watch touch events into tap/long-press/swipe. */
public final class TouchGesture {
    public static final int TYPE_TAP = 1;
    public static final int TYPE_LONG_PRESS = 2;
    public static final int TYPE_SWIPE = 3;

    public static final class Result {
        public final int type;
        public final int x1;
        public final int y1;
        public final int x2;
        public final int y2;
        public final long durationMs;

        Result(int type, int x1, int y1, int x2, int y2, long durationMs) {
            this.type = type;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.durationMs = durationMs;
        }
    }

    private boolean active;
    private long downTime;
    private int firstX;
    private int firstY;
    private int lastX;
    private int lastY;

    public void down(int x, int y, long timeMs) {
        active = true;
        downTime = timeMs;
        firstX = x;
        firstY = y;
        lastX = x;
        lastY = y;
    }

    public void move(int x, int y) {
        if (!active) {
            return;
        }
        lastX = x;
        lastY = y;
    }

    public Result up(int x, int y, long timeMs) {
        return up(x, y, timeMs, -1);
    }

    /**
     * @param reportedDurationMs duration measured by the watch, or -1 if the
     *                           caller only has arrival times. BLE/AppMessage
     *                           latency can add hundreds of ms between the DOWN
     *                           and UP messages; the watch-reported duration is
     *                           authoritative when present.
     */
    public Result up(int x, int y, long timeMs, long reportedDurationMs) {
        if (!active) {
            return null;
        }
        active = false;
        lastX = x;
        lastY = y;
        long duration = reportedDurationMs > 0
                ? reportedDurationMs
                : Math.max(1, timeMs - downTime);
        double distance = Math.hypot(lastX - firstX, lastY - firstY);
        if (distance < 14 && duration < 500) {
            return new Result(TYPE_TAP, firstX, firstY, lastX, lastY, duration);
        }
        if (distance < 14) {
            return new Result(TYPE_LONG_PRESS, firstX, firstY, lastX, lastY,
                    Math.min(1500, duration));
        }
        return new Result(TYPE_SWIPE, firstX, firstY, lastX, lastY,
                Math.max(80, Math.min(2000, duration)));
    }

    public void reset() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }
}
