package com.pebblemirror.companion.capture;

import com.pebblemirror.companion.protocol.WatchProtocol;

/** Geometry shared by capture scaling, viewport panning and touch mapping. */
public final class ViewportMapper {
    public static final class Scale {
        public final int phoneWidth;
        public final int phoneHeight;
        public final int watchWidth;
        public final int watchHeight;
        public final int scaledWidth;
        public final int scaledHeight;
        public final int maxOffset;
        public final int letterboxX;
        public final int letterboxY;
        public final double scale;
        public final int mode;

        Scale(int phoneWidth, int phoneHeight, int watchWidth, int watchHeight,
              int scaledWidth, int scaledHeight, int maxOffset,
              int letterboxX, int letterboxY, double scale, int mode) {
            this.phoneWidth = phoneWidth;
            this.phoneHeight = phoneHeight;
            this.watchWidth = watchWidth;
            this.watchHeight = watchHeight;
            this.scaledWidth = scaledWidth;
            this.scaledHeight = scaledHeight;
            this.maxOffset = maxOffset;
            this.letterboxX = letterboxX;
            this.letterboxY = letterboxY;
            this.scale = scale;
            this.mode = mode;
        }
    }

    private ViewportMapper() {
    }

    public static Scale compute(int phoneWidth, int phoneHeight,
                                int watchWidth, int watchHeight, int mode) {
        if (phoneWidth <= 0 || phoneHeight <= 0 || watchWidth <= 0 || watchHeight <= 0) {
            return new Scale(Math.max(1, phoneWidth), Math.max(1, phoneHeight),
                    watchWidth, watchHeight, watchWidth, watchHeight,
                    0, 0, 0, 1.0, mode);
        }

        if (mode == WatchProtocol.MODE_FIT) {
            double scale = Math.min(watchWidth / (double) phoneWidth,
                    watchHeight / (double) phoneHeight);
            int scaledW = Math.max(1, (int) Math.round(phoneWidth * scale));
            int scaledH = Math.max(1, (int) Math.round(phoneHeight * scale));
            int x0 = (watchWidth - scaledW) / 2;
            int y0 = (watchHeight - scaledH) / 2;
            return new Scale(phoneWidth, phoneHeight, watchWidth, watchHeight,
                    scaledW, scaledH, 0, x0, y0, scale, mode);
        }

        double scale = watchWidth / (double) phoneWidth;
        int scaledW = watchWidth;
        int scaledH = Math.max(1, (int) Math.round(phoneHeight * scale));
        int maxOffset = Math.max(0, scaledH - watchHeight);
        return new Scale(phoneWidth, phoneHeight, watchWidth, watchHeight,
                scaledW, scaledH, maxOffset, 0, 0, scale, mode);
    }

    public static int clampOffset(int offset, int maxOffset) {
        if (offset < 0) {
            return 0;
        }
        return Math.min(offset, Math.max(0, maxOffset));
    }

    public static int[] mapToPhone(int watchX, int watchY, Scale geometry, int offset) {
        if (geometry == null) {
            return new int[]{watchX, watchY};
        }
        double phoneX;
        double phoneY;
        if (geometry.mode == WatchProtocol.MODE_FIT) {
            phoneX = (watchX - geometry.letterboxX) / geometry.scale;
            phoneY = (watchY - geometry.letterboxY) / geometry.scale;
        } else {
            phoneX = watchX / geometry.scale;
            phoneY = (offset + watchY) / geometry.scale;
        }
        int x = (int) Math.round(phoneX);
        int y = (int) Math.round(phoneY);
        x = Math.max(0, Math.min(geometry.phoneWidth - 1, x));
        y = Math.max(0, Math.min(geometry.phoneHeight - 1, y));
        return new int[]{x, y};
    }
}
