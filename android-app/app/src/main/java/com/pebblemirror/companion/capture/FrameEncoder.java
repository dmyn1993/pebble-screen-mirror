package com.pebblemirror.companion.capture;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a watch-scaled phone image into the watch's 1-bit tile format.
 *
 * The source image is already scaled so that, in viewport mode, its width is
 * exactly the watch width and its height is the full phone height at that
 * scale. In fit mode it is the whole phone screen scaled into the watch.
 *
 * Pebble 1-bit bitmaps are LSB-first within each byte: bit 0 is the leftmost
 * pixel of a tile row.
 */
public final class FrameEncoder {
    public static final int TILE = 8;

    public enum Dither {
        ATKINSON,
        THRESHOLD
    }

    public static final class TileRecord {
        public final int index;
        public final byte[] data;

        TileRecord(int index, byte[] data) {
            this.index = index;
            this.data = data;
        }
    }

    public static final class TileFrame {
        public final int frameId;
        public final int width;
        public final int height;
        public final int maxOffset;
        public final int mode;
        public final boolean keyframe;
        public final List<TileRecord> tiles;

        TileFrame(int frameId, int width, int height, int maxOffset, int mode,
                  boolean keyframe, List<TileRecord> tiles) {
            this.frameId = frameId;
            this.width = width;
            this.height = height;
            this.maxOffset = maxOffset;
            this.mode = mode;
            this.keyframe = keyframe;
            this.tiles = tiles;
        }
    }

    private byte[] previous;
    private int lastWidth;
    private int lastHeight;
    private int lastMode;
    private int lastOffset;
    private int nextFrameId = 1;

    public void reset() {
        previous = null;
        lastWidth = 0;
        lastHeight = 0;
        lastMode = -1;
        lastOffset = -1;
    }

    public TileFrame encode(int[] argb, int srcWidth, int srcHeight,
                            int watchWidth, int watchHeight,
                            int mode, int offset, int maxOffset,
                            boolean forceKeyframe, Dither dither) {
        if (argb == null || srcWidth <= 0 || srcHeight <= 0
                || watchWidth <= 0 || watchHeight <= 0) {
            return new TileFrame(nextFrameId, watchWidth, watchHeight, maxOffset,
                    mode, false, new ArrayList<>());
        }

        int width = watchWidth;
        int height = watchHeight;
        int[] gray = new int[width * height];

        if (mode == com.pebblemirror.companion.protocol.WatchProtocol.MODE_FIT) {
            int x0 = (width - srcWidth) / 2;
            int y0 = (height - srcHeight) / 2;
            for (int y = 0; y < height; y++) {
                int sy = y - y0;
                int row = y * width;
                if (sy < 0 || sy >= srcHeight) {
                    continue;
                }
                for (int x = 0; x < width; x++) {
                    int sx = x - x0;
                    if (sx < 0 || sx >= srcWidth) {
                        continue;
                    }
                    gray[row + x] = luminance(argb[sy * srcWidth + sx]);
                }
            }
        } else {
            int clampOffset = Math.max(0, Math.min(offset, Math.max(0, maxOffset)));
            for (int y = 0; y < height; y++) {
                int sy = clampOffset + y;
                int row = y * width;
                if (sy < 0 || sy >= srcHeight) {
                    continue;
                }
                int srcRow = sy * srcWidth;
                for (int x = 0; x < width; x++) {
                    int sx = srcWidth == width ? x : (int) ((long) x * srcWidth / width);
                    if (sx < 0 || sx >= srcWidth) {
                        continue;
                    }
                    gray[row + x] = luminance(argb[srcRow + sx]);
                }
            }
        }

        if (dither == Dither.THRESHOLD) {
            threshold(gray);
        } else {
            atkinson(gray, width, height);
        }

        int stride = (width + TILE - 1) / TILE;
        byte[] canvas = new byte[stride * height];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int base = y * stride;
            for (int x = 0; x < width; x++) {
                if (gray[row + x] >= 128) {
                    canvas[base + (x >> 3)] |= (byte) (1 << (x & 7));
                }
            }
        }

        boolean geometryChanged = width != lastWidth || height != lastHeight
                || mode != lastMode || offset != lastOffset;
        boolean keyframe = forceKeyframe || previous == null
                || previous.length != canvas.length || geometryChanged;

        int tilesX = (width + TILE - 1) / TILE;
        int tilesY = (height + TILE - 1) / TILE;
        int totalTiles = tilesX * tilesY;
        List<TileRecord> changed = new ArrayList<>();

        if (!keyframe) {
            for (int ty = 0; ty < tilesY; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    byte[] data = new byte[TILE];
                    boolean differs = false;
                    for (int r = 0; r < TILE; r++) {
                        int y = ty * TILE + r;
                        if (y >= height) {
                            break;
                        }
                        int pos = y * stride + tx;
                        byte value = canvas[pos];
                        data[r] = value;
                        if (previous[pos] != value) {
                            differs = true;
                        }
                    }
                    if (differs) {
                        changed.add(new TileRecord(ty * tilesX + tx, data));
                    }
                }
            }
            // Large changes are cheaper and more reliable as a keyframe.
            if (changed.size() * 5 > totalTiles * 3) {
                keyframe = true;
                changed.clear();
            }
        }

        if (keyframe) {
            changed = new ArrayList<>(totalTiles);
            for (int ty = 0; ty < tilesY; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    byte[] data = new byte[TILE];
                    for (int r = 0; r < TILE; r++) {
                        int y = ty * TILE + r;
                        if (y >= height) {
                            break;
                        }
                        data[r] = canvas[y * stride + tx];
                    }
                    changed.add(new TileRecord(ty * tilesX + tx, data));
                }
            }
        }

        previous = canvas;
        lastWidth = width;
        lastHeight = height;
        lastMode = mode;
        lastOffset = offset;

        int frameId = nextFrameId;
        nextFrameId = (nextFrameId + 1) & 0xffff;
        if (nextFrameId == 0) {
            nextFrameId = 1;
        }

        return new TileFrame(frameId, width, height, maxOffset, mode, keyframe, changed);
    }

    private static int luminance(int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static void threshold(int[] gray) {
        for (int i = 0; i < gray.length; i++) {
            gray[i] = gray[i] >= 128 ? 255 : 0;
        }
    }

    private static void atkinson(int[] gray, int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int old = gray[i];
                int value = old >= 128 ? 255 : 0;
                gray[i] = value;
                int error = (old - value) / 8;
                if (error == 0) {
                    continue;
                }
                add(gray, width, height, x + 1, y, error);
                add(gray, width, height, x + 2, y, error);
                add(gray, width, height, x - 1, y + 1, error);
                add(gray, width, height, x, y + 1, error);
                add(gray, width, height, x + 1, y + 1, error);
                add(gray, width, height, x, y + 2, error);
            }
        }
    }

    private static void add(int[] gray, int width, int height, int x, int y, int error) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return;
        }
        int i = y * width + x;
        int value = gray[i] + error;
        if (value < 0) {
            value = 0;
        } else if (value > 255) {
            value = 255;
        }
        gray[i] = value;
    }
}
