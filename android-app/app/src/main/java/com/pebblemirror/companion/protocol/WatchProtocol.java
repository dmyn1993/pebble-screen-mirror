package com.pebblemirror.companion.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire protocol shared by the C watchapp and this companion. Keys are numeric on
 * purpose; the C app uses the same integers directly, so there is no generated
 * message_keys header to keep in sync.
 */
public final class WatchProtocol {
    public static final int KEY_KIND = 1;
    public static final int KEY_SEQ = 2;
    public static final int KEY_FRAME = 3;
    public static final int KEY_TILE_START = 4;
    public static final int KEY_TILE_COUNT = 5;
    public static final int KEY_FLAGS = 6;
    public static final int KEY_DATA = 7;
    public static final int KEY_CMD = 8;
    public static final int KEY_X = 9;
    public static final int KEY_Y = 10;
    public static final int KEY_WIDTH = 11;
    public static final int KEY_HEIGHT = 12;
    public static final int KEY_MAX_OFF = 13;
    public static final int KEY_MODE = 14;
    public static final int KEY_TEXT = 15;
    public static final int KEY_VERSION = 16;
    public static final int KEY_MAX_INBOX = 17;
    public static final int KEY_DURATION = 18;
    public static final int KEY_POINT_COUNT = 19;

    public static final int KIND_CONFIG = 1;
    public static final int KIND_FRAME = 2;
    public static final int KIND_STATUS = 3;

    public static final int KIND_HELLO = 10;
    public static final int KIND_TOUCH = 11;
    public static final int KIND_PAN = 12;
    public static final int KIND_KEY = 13;
    public static final int KIND_REQUEST = 14;
    public static final int KIND_PATH = 15;
    public static final int KIND_DEBUG = 99;

    public static final int FRAME_FINAL = 1;
    public static final int FRAME_KEYFRAME = 1 << 1;

    public static final int TOUCH_DOWN = 1;
    public static final int TOUCH_MOVE = 2;
    public static final int TOUCH_UP = 3;
    public static final int TOUCH_TAP = 4;
    public static final int TOUCH_LONG = 5;

    public static final int KEY_BACK = 1;
    public static final int KEY_HOME = 2;
    public static final int KEY_RECENTS = 3;

    public static final int MODE_VIEWPORT = 0;
    public static final int MODE_FIT = 1;

    private WatchProtocol() {
    }

    public static Map<Integer, WatchValue> config(
            int watchWidth, int watchHeight, int maxOffset, int mode,
            boolean mirroring, String statusText) {
        Map<Integer, WatchValue> map = new HashMap<>();
        map.put(KEY_KIND, WatchValue.uint(1, KIND_CONFIG));
        map.put(KEY_WIDTH, WatchValue.uint(2, watchWidth));
        map.put(KEY_HEIGHT, WatchValue.uint(2, watchHeight));
        map.put(KEY_MAX_OFF, WatchValue.uint(2, maxOffset));
        map.put(KEY_MODE, WatchValue.uint(1, mode));
        map.put(KEY_FLAGS, WatchValue.uint(1, mirroring ? 1 : 0));
        map.put(KEY_TEXT, WatchValue.text(truncate(statusText, 80)));
        return map;
    }

    public static Map<Integer, WatchValue> status(boolean mirroring, String statusText) {
        Map<Integer, WatchValue> map = new HashMap<>();
        map.put(KEY_KIND, WatchValue.uint(1, KIND_STATUS));
        map.put(KEY_FLAGS, WatchValue.uint(1, mirroring ? 1 : 0));
        map.put(KEY_TEXT, WatchValue.text(truncate(statusText, 80)));
        return map;
    }

    /**
     * Split a tile frame into AppMessage-sized chunks. Each tile record is
     * {@code uint16 index + 8 data bytes}; the watch applies a chunk in order.
     */
    public static List<Map<Integer, WatchValue>> frameMessages(
            com.pebblemirror.companion.capture.FrameEncoder.TileFrame frame,
            int maxInboxBytes) {
        List<Map<Integer, WatchValue>> out = new ArrayList<>();
        int total = frame.tiles.size();
        if (total == 0) {
            return out;
        }

        int usable = Math.max(64, Math.min(8000, maxInboxBytes - 320));
        int maxRecords = Math.max(1, Math.min(725, usable / 10));

        for (int start = 0; start < total; start += maxRecords) {
            int count = Math.min(maxRecords, total - start);
            byte[] data = new byte[count * 10];
            int off = 0;
            for (int i = 0; i < count; i++) {
                com.pebblemirror.companion.capture.FrameEncoder.TileRecord record =
                        frame.tiles.get(start + i);
                data[off++] = (byte) (record.index & 0xff);
                data[off++] = (byte) ((record.index >> 8) & 0xff);
                System.arraycopy(record.data, 0, data, off, 8);
                off += 8;
            }

            int flags = (start + count == total ? FRAME_FINAL : 0);
            if (frame.keyframe && start == 0) {
                flags |= FRAME_KEYFRAME;
            }

            Map<Integer, WatchValue> map = new HashMap<>();
            map.put(KEY_KIND, WatchValue.uint(1, KIND_FRAME));
            map.put(KEY_FRAME, WatchValue.uint(2, frame.frameId));
            map.put(KEY_TILE_START, WatchValue.uint(2, frame.tiles.get(start).index));
            map.put(KEY_TILE_COUNT, WatchValue.uint(2, count));
            map.put(KEY_FLAGS, WatchValue.uint(1, flags));
            map.put(KEY_DATA, WatchValue.bytes(data));
            out.add(map);
        }
        return out;
    }

    public static int intValue(Map<Integer, WatchValue> map, int key, int fallback) {
        WatchValue value = map.get(key);
        if (value == null || (value.type != WatchValue.TYPE_UINT
                && value.type != WatchValue.TYPE_INT)) {
            return fallback;
        }
        return (int) value.number;
    }

    public static byte[] bytesValue(Map<Integer, WatchValue> map, int key) {
        WatchValue value = map.get(key);
        return value != null && value.type == WatchValue.TYPE_BYTES ? value.bytes : null;
    }

    public static String textValue(Map<Integer, WatchValue> map, int key) {
        WatchValue value = map.get(key);
        return value != null && value.type == WatchValue.TYPE_STRING ? value.text : null;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
