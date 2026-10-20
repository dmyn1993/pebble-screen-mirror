package com.pebblemirror.companion;

import android.content.Context;
import android.content.SharedPreferences;

import com.pebblemirror.companion.capture.FrameEncoder;
import com.pebblemirror.companion.protocol.WatchProtocol;

/** Persisted display/control preferences. */
public final class MirrorSettings {
    private static final String PREFS = "mirror_settings";
    private static final String KEY_MODE = "mode";
    private static final String KEY_DITHER = "dither";
    private static final String KEY_FPS = "fps";
    private static final String KEY_INJECTOR = "injector";

    public static final int INJECTOR_AUTO = 0;
    public static final int INJECTOR_ACCESSIBILITY = 1;
    public static final int INJECTOR_SHIZUKU = 2;
    public static final int INJECTOR_ROOT = 3;

    private int mode = WatchProtocol.MODE_VIEWPORT;
    private int dither = 1;
    private int fps = 2;
    private int injector = INJECTOR_AUTO;

    public static MirrorSettings load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        MirrorSettings settings = new MirrorSettings();
        settings.mode = prefs.getInt(KEY_MODE, WatchProtocol.MODE_VIEWPORT);
        settings.dither = prefs.getInt(KEY_DITHER, 1);
        settings.fps = Math.max(1, Math.min(5, prefs.getInt(KEY_FPS, 2)));
        settings.injector = prefs.getInt(KEY_INJECTOR, INJECTOR_AUTO);
        return settings;
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MODE, mode)
                .putInt(KEY_DITHER, dither)
                .putInt(KEY_FPS, fps)
                .putInt(KEY_INJECTOR, injector)
                .apply();
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int mode) {
        this.mode = mode == WatchProtocol.MODE_FIT
                ? WatchProtocol.MODE_FIT : WatchProtocol.MODE_VIEWPORT;
    }

    public int getDither() {
        return dither;
    }

    public void setDither(int dither) {
        this.dither = dither == 1 ? 1 : 0;
    }

    public FrameEncoder.Dither ditherMode() {
        return dither == 1 ? FrameEncoder.Dither.THRESHOLD : FrameEncoder.Dither.ATKINSON;
    }

    public int getFps() {
        return fps;
    }

    public void setFps(int fps) {
        this.fps = Math.max(1, Math.min(5, fps));
    }

    public int getInjector() {
        return injector;
    }

    public void setInjector(int injector) {
        this.injector = Math.max(0, Math.min(3, injector));
    }
}
