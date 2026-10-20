package com.pebblemirror.companion.protocol;

/** Minimal, dependency-free representation of one AppMessage value. */
public final class WatchValue {
    public static final int TYPE_UINT = 0;
    public static final int TYPE_INT = 1;
    public static final int TYPE_BYTES = 2;
    public static final int TYPE_STRING = 3;

    public final int type;
    public final int width;
    public final long number;
    public final byte[] bytes;
    public final String text;

    private WatchValue(int type, int width, long number, byte[] bytes, String text) {
        this.type = type;
        this.width = width;
        this.number = number;
        this.bytes = bytes;
        this.text = text;
    }

    public static WatchValue uint(int width, long value) {
        return new WatchValue(TYPE_UINT, width, value, null, null);
    }

    public static WatchValue sint(int width, long value) {
        return new WatchValue(TYPE_INT, width, value, null, null);
    }

    public static WatchValue bytes(byte[] value) {
        return new WatchValue(TYPE_BYTES, 0, 0, value, null);
    }

    public static WatchValue text(String value) {
        return new WatchValue(TYPE_STRING, 0, 0, null, value == null ? "" : value);
    }
}
