package com.pebblemirror.companion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.pebblemirror.companion.capture.FrameEncoder;
import com.pebblemirror.companion.capture.ScreenCapture;
import com.pebblemirror.companion.capture.ViewportMapper;
import com.pebblemirror.companion.inject.GestureInjector;
import com.pebblemirror.companion.inject.InjectorFactory;
import com.pebblemirror.companion.inject.TouchGesture;
import com.pebblemirror.companion.pebble.LegacyPebbleLink;
import com.pebblemirror.companion.protocol.WatchProtocol;
import com.pebblemirror.companion.protocol.WatchValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for the mirror session. The capture service and the
 * legacy PebbleKit bridge both talk to this object; UI code only reads status.
 */
public final class MirrorController {
    private static final String TAG = "PebbleMirrorController";
    public static final UUID WATCHAPP_UUID =
            UUID.fromString("be518248-51cb-4b4f-aa58-8c4718411da5");
    private static volatile MirrorController instance;

    public interface Listener {
        void onMirrorStateChanged();
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object pixelLock = new Object();

    private final FrameEncoder encoder = new FrameEncoder();
    private final TouchGesture touchGesture = new TouchGesture();

    private MirrorSettings settings;
    private LegacyPebbleLink link;
    private ScreenCapture capture;

    private int[] latestPixels;
    private int latestScaledWidth;
    private int latestScaledHeight;
    private int latestPhoneWidth;
    private int latestPhoneHeight;
    private boolean frameDirty;

    private volatile int watchWidth = 200;
    private volatile int watchHeight = 228;
    private volatile int maxInbox = 8192;
    private volatile int phoneWidth;
    private volatile int phoneHeight;
    private volatile int maxOffset;
    private volatile int viewportOffset;
    private volatile int mode;

    private boolean mirroring;
    private boolean watchOpen;
    private boolean watchConfigured;
    private int watchSession = -1;
    private boolean forceKeyframe = true;
    private boolean sendingFrame;
    private long watchLastSeenMs;
    private String statusText = "";
    private String watchStatus = "Start on phone";
    private Runnable captureErrorListener;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            if (watchOpen && now - watchLastSeenMs > 12000) {
                watchOpen = false;
                watchConfigured = false;
                touchGesture.reset();
                notifyChanged();
            }
            LegacyPebbleLink current = link;
            if (watchOpen && current != null && !current.isBusy()) {
                sendStatus();
            }
            main.postDelayed(this, 5000);
        }
    };

    private MirrorController(Context context) {
        this.appContext = context.getApplicationContext();
        this.settings = MirrorSettings.load(appContext);
        this.mode = settings.getMode();
        main.postDelayed(heartbeatRunnable, 5000);
    }

    public static void init(Context context) {
        if (instance == null) {
            synchronized (MirrorController.class) {
                if (instance == null) {
                    instance = new MirrorController(context);
                }
            }
        }
    }

    public static MirrorController get() {
        MirrorController controller = instance;
        if (controller == null) {
            throw new IllegalStateException("MirrorController.init() was not called");
        }
        return controller;
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setCaptureErrorListener(Runnable listener) {
        captureErrorListener = listener;
    }

    public MirrorSettings getSettings() {
        return settings;
    }

    public void setSettings(MirrorSettings newSettings) {
        runOnMain(() -> {
            settings = newSettings;
            mode = settings.getMode();
            encoder.reset();
            watchConfigured = false;
            forceKeyframe = true;
            viewportOffset = ViewportMapper.clampOffset(viewportOffset, maxOffset);
            if (capture != null) {
                capture.setTargetFps(settings.getFps());
                capture.setWatchConfig(watchWidth, watchHeight, mode);
            }
            if (hasLatestFrame()) {
                frameDirty = true;
            }
            sendConfig();
            trySendLatest();
            notifyChanged();
        });
    }

    public void attachLink(LegacyPebbleLink newLink) {
        runOnMain(() -> {
            link = newLink;
            newLink.setIdleListener(this::trySendLatest);
            sendingFrame = false;
            watchConfigured = false;
            watchSession = -1;
            forceKeyframe = true;
            frameDirty = hasLatestFrame();
            notifyChanged();
        });
    }

    public void detachLink(LegacyPebbleLink oldLink) {
        runOnMain(() -> {
            if (link == oldLink) {
                link = null;
                watchOpen = false;
                watchConfigured = false;
                watchSession = -1;
                sendingFrame = false;
                touchGesture.reset();
                notifyChanged();
            }
        });
    }

    public void attachCapture(ScreenCapture newCapture) {
        runOnMain(() -> {
            capture = newCapture;
            mirroring = true;
            resetTouchTrail();
            statusText = "镜像运行中";
            watchStatus = "Mirroring";
            forceKeyframe = true;
            watchConfigured = false;
            phoneWidth = newCapture.getPhoneWidth();
            phoneHeight = newCapture.getPhoneHeight();
            newCapture.setTargetFps(settings.getFps());
            newCapture.setWatchConfig(watchWidth, watchHeight, mode);
            updateMaxOffset();
            sendConfig();
            sendStatus();
            NotificationHelper.cancelWatchOpened(appContext);
            if (hasLatestFrame()) {
                frameDirty = true;
            }
            trySendLatest();
            notifyChanged();
        });
    }

    public void detachCapture() {
        runOnMain(() -> {
            capture = null;
            mirroring = false;
            resetTouchTrail();
            frameDirty = false;
            forceKeyframe = true;
            statusText = "镜像已停止";
            watchStatus = "Start on phone";
            sendStatus();
            notifyChanged();
        });
    }

    public void onCaptureError(String message) {
        runOnMain(() -> {
            Log.w(TAG, "capture error: " + message);
            capture = null;
            mirroring = false;
            resetTouchTrail();
            frameDirty = false;
            forceKeyframe = true;
            statusText = message;
            watchStatus = "Capture error";
            sendStatus();
            notifyChanged();
            Runnable listener = captureErrorListener;
            if (listener != null) {
                listener.run();
            }
        });
    }

    public void onWatchAppOpened(String watchId) {
        runOnMain(() -> {
            watchOpen = true;
            watchConfigured = false;
            watchSession = -1;
            forceKeyframe = true;
            if (hasLatestFrame()) {
                frameDirty = true;
            }
            sendConfig();
            sendStatus();
            if (mirroring) {
                trySendLatest();
            } else {
                NotificationHelper.showWatchOpened(appContext);
            }
            notifyChanged();
        });
    }

    public void onWatchAppClosed() {
        runOnMain(() -> {
            watchOpen = false;
            touchGesture.reset();
            notifyChanged();
        });
    }

    public void onWatchMessage(Map<Integer, WatchValue> message) {
        runOnMain(() -> {
            watchLastSeenMs = SystemClock.uptimeMillis();
            int kind = WatchProtocol.intValue(message, WatchProtocol.KEY_KIND, -1);
            if (kind != WatchProtocol.KIND_HELLO && kind != WatchProtocol.KIND_DEBUG && kind >= 0) {
                watchOpen = true;
                watchConfigured = true;
            }
            switch (kind) {
                case WatchProtocol.KIND_HELLO: {
                    int width = WatchProtocol.intValue(message, WatchProtocol.KEY_WIDTH, watchWidth);
                    int height = WatchProtocol.intValue(message, WatchProtocol.KEY_HEIGHT, watchHeight);
                    int inbox = WatchProtocol.intValue(message, WatchProtocol.KEY_MAX_INBOX, maxInbox);
                    int session = WatchProtocol.intValue(message, WatchProtocol.KEY_SEQ, 0);
                    onWatchHello(width, height, inbox, session);
                    break;
                }
                case WatchProtocol.KIND_TOUCH: {
                    int command = WatchProtocol.intValue(message, WatchProtocol.KEY_CMD, 0);
                    int x = WatchProtocol.intValue(message, WatchProtocol.KEY_X, 0);
                    int y = WatchProtocol.intValue(message, WatchProtocol.KEY_Y, 0);
                    int duration = WatchProtocol.intValue(
                            message, WatchProtocol.KEY_DURATION, -1);
                    handleWatchTouch(command, x, y, duration);
                    break;
                }
                case WatchProtocol.KIND_PAN: {
                    int offset = WatchProtocol.intValue(message, WatchProtocol.KEY_Y, viewportOffset);
                    handlePan(offset);
                    break;
                }
                case WatchProtocol.KIND_KEY: {
                    int key = WatchProtocol.intValue(message, WatchProtocol.KEY_CMD, 0);
                    injectKey(key);
                    break;
                }
                case WatchProtocol.KIND_PATH:
                    handleWatchPath(message);
                    break;
                case WatchProtocol.KIND_MODE:
                    handleWatchMode(WatchProtocol.intValue(
                            message, WatchProtocol.KEY_CMD, -1));
                    break;
                case WatchProtocol.KIND_PREVIEW:
                    handleWatchPreview(
                            WatchProtocol.intValue(message, WatchProtocol.KEY_CMD, 0),
                            WatchProtocol.intValue(message, WatchProtocol.KEY_X, 0),
                            WatchProtocol.intValue(message, WatchProtocol.KEY_Y, 0));
                    break;
                case WatchProtocol.KIND_REQUEST:
                    forceKeyframe = true;
                    if (hasLatestFrame()) {
                        frameDirty = true;
                    }
                    trySendLatest();
                    break;
                case WatchProtocol.KIND_DEBUG:
                    Log.i("PebbleMirrorWatch",
                            WatchProtocol.textValue(message, WatchProtocol.KEY_TEXT));
                    break;
                default:
                    break;
            }
        });
    }

    private void onWatchHello(int width, int height, int inbox, int session) {
        int newWidth = Math.max(1, Math.min(512, width));
        int newHeight = Math.max(1, Math.min(512, height));
        int newInbox = Math.max(124, Math.min(65535, inbox));
        boolean firstOpen = !watchOpen;
        boolean sessionChanged = session != watchSession;
        watchSession = session;
        boolean changed = newWidth != watchWidth || newHeight != watchHeight
                || newInbox != maxInbox || sessionChanged;

        watchWidth = newWidth;
        watchHeight = newHeight;
        maxInbox = newInbox;
        watchOpen = true;
        watchLastSeenMs = SystemClock.uptimeMillis();
        if (sessionChanged) {
            watchConfigured = false;
        }

        if (changed) {
            encoder.reset();
            forceKeyframe = true;
            if (capture != null) {
                capture.setWatchConfig(watchWidth, watchHeight, mode);
            }
            updateMaxOffset();
        }

        if (firstOpen || changed || !watchConfigured) {
            sendConfig();
            sendStatus();
            if (firstOpen && !mirroring) {
                NotificationHelper.showWatchOpened(appContext);
            }
            if (hasLatestFrame()) {
                frameDirty = true;
            }
            trySendLatest();
            notifyChanged();
        }
    }

    public void onScaledFrame(int[] pixels, int scaledWidth, int scaledHeight,
                              int phoneWidth, int phoneHeight) {
        if (pixels == null || scaledWidth <= 0 || scaledHeight <= 0) {
            return;
        }
        boolean phoneChanged;
        synchronized (pixelLock) {
            phoneChanged = this.phoneWidth != phoneWidth || this.phoneHeight != phoneHeight;
            this.phoneWidth = phoneWidth;
            this.phoneHeight = phoneHeight;
            latestPixels = pixels;
            latestScaledWidth = scaledWidth;
            latestScaledHeight = scaledHeight;
            latestPhoneWidth = phoneWidth;
            latestPhoneHeight = phoneHeight;
            frameDirty = true;
        }
        updateMaxOffset();
        if (phoneChanged) {
            main.post(this::sendConfig);
        }
        main.post(this::trySendLatest);
    }

    private void updateMaxOffset() {
        int w = phoneWidth;
        int h = phoneHeight;
        if (w <= 0 || h <= 0) {
            maxOffset = 0;
            viewportOffset = 0;
            return;
        }
        ViewportMapper.Scale geometry = ViewportMapper.compute(
                w, h, watchWidth, watchHeight, mode);
        maxOffset = geometry.maxOffset;
        viewportOffset = ViewportMapper.clampOffset(viewportOffset, maxOffset);
    }

    private void handleWatchTouch(int command, int watchX, int watchY, int durationMs) {
        if (phoneWidth <= 0 || phoneHeight <= 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (command == WatchProtocol.TOUCH_DOWN) {
            int[] point = mapToPhone(watchX, watchY);
            touchGesture.down(point[0], point[1], now);
            TouchTrailOverlay.clear();
            TouchTrailOverlay.add(point[0], point[1]);
        } else if (command == WatchProtocol.TOUCH_MOVE) {
            int[] point = mapToPhone(watchX, watchY);
            touchGesture.move(point[0], point[1]);
            TouchTrailOverlay.add(point[0], point[1]);
        } else if (command == WatchProtocol.TOUCH_UP) {
            int[] point = mapToPhone(watchX, watchY);
            TouchGesture.Result result = touchGesture.up(
                    point[0], point[1], now, durationMs);
            TouchTrailOverlay.add(point[0], point[1]);
            injectGesture(result);
        } else if (command == WatchProtocol.TOUCH_TAP) {
            // A quick tap already sent TOUCH_DOWN; discard that pending
            // gesture before injecting the direct tap.
            touchGesture.reset();
            int[] point = mapToPhone(watchX, watchY);
            Log.i(TAG, "watch tap " + watchX + "," + watchY + " -> phone "
                    + point[0] + "," + point[1]);
            TouchTrailOverlay.clear();
            TouchTrailOverlay.add(point[0], point[1]);
            injectTap(point[0], point[1]);
        } else if (command == WatchProtocol.TOUCH_LONG) {
            touchGesture.reset();
            int[] point = mapToPhone(watchX, watchY);
            Log.i(TAG, "watch long " + watchX + "," + watchY + " -> phone "
                    + point[0] + "," + point[1]);
            TouchTrailOverlay.clear();
            TouchTrailOverlay.add(point[0], point[1]);
            injectLongPress(point[0], point[1]);
        }
    }

    private void resetTouchTrail() {
        TouchTrailOverlay.clear();
    }

    private void handleWatchPreview(int command, int watchX, int watchY) {
        if (phoneWidth <= 0 || phoneHeight <= 0) {
            return;
        }
        int[] point = mapToPhone(watchX, watchY);
        if (command == WatchProtocol.TOUCH_DOWN) {
            TouchTrailOverlay.clear();
        }
        TouchTrailOverlay.add(point[0], point[1]);
    }

    private void handleWatchMode(int requested) {
        if (requested != WatchProtocol.MODE_VIEWPORT
                && requested != WatchProtocol.MODE_FIT) {
            return;
        }
        if (settings.getMode() == requested) {
            return;
        }
        Log.i(TAG, "watch mode -> " + requested);
        settings.setMode(requested);
        settings.save(appContext);
        setSettings(settings);
        sendStatus();
    }

    private void handleWatchPath(Map<Integer, WatchValue> message) {
        if (phoneWidth <= 0 || phoneHeight <= 0) {
            return;
        }
        int count = WatchProtocol.intValue(
                message, WatchProtocol.KEY_POINT_COUNT, 0);
        int duration = WatchProtocol.intValue(
                message, WatchProtocol.KEY_DURATION, 300);
        byte[] data = WatchProtocol.bytesValue(message, WatchProtocol.KEY_DATA);
        if (count < 2 || count > 512 || data == null || data.length < count * 4) {
            return;
        }

        List<int[]> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int wx = (data[i * 4] & 0xff) | ((data[i * 4 + 1] & 0xff) << 8);
            int wy = (data[i * 4 + 2] & 0xff) | ((data[i * 4 + 3] & 0xff) << 8);
            int[] point = mapToPhone(wx, wy);
            if (points.isEmpty()
                    || points.get(points.size() - 1)[0] != point[0]
                    || points.get(points.size() - 1)[1] != point[1]) {
                points.add(point);
            }
        }
        if (points.size() < 2) {
            return;
        }

        TouchTrailOverlay.clear();
        for (int[] point : points) {
            TouchTrailOverlay.add(point[0], point[1]);
        }

        touchGesture.reset();
        GestureInjector injector = currentInjector();
        Log.i(TAG, "watch path " + points.size() + " points, "
                + duration + "ms via " + injector.describe());
        if (!injector.path(points, duration)) {
            reportNoInjector();
        }
    }

    private void handlePan(int newOffset) {
        int clamped = ViewportMapper.clampOffset(newOffset, maxOffset);
        if (clamped == viewportOffset) {
            return;
        }
        viewportOffset = clamped;
        forceKeyframe = true;
        if (hasLatestFrame()) {
            frameDirty = true;
        }
        trySendLatest();
    }

    private void injectGesture(TouchGesture.Result result) {
        if (result == null) {
            return;
        }
        GestureInjector injector = currentInjector();
        boolean ok;
        if (result.type == TouchGesture.TYPE_TAP) {
            ok = injector.tap(result.x1, result.y1);
        } else if (result.type == TouchGesture.TYPE_LONG_PRESS) {
            ok = injector.longPress(result.x1, result.y1);
        } else {
            ok = injector.swipe(result.x1, result.y1, result.x2, result.y2,
                    result.durationMs);
        }
        if (!ok) {
            reportNoInjector();
        }
    }

    private void injectTap(int x, int y) {
        GestureInjector injector = currentInjector();
        Log.i(TAG, "inject tap with " + injector.describe() + " at " + x + "," + y);
        if (!injector.tap(x, y)) {
            reportNoInjector();
        }
    }

    private void injectLongPress(int x, int y) {
        GestureInjector injector = currentInjector();
        Log.i(TAG, "inject long with " + injector.describe() + " at " + x + "," + y);
        if (!injector.longPress(x, y)) {
            reportNoInjector();
        }
    }

    private void injectKey(int key) {
        GestureInjector injector = currentInjector();
        if (!injector.key(key)) {
            reportNoInjector();
        }
    }

    private GestureInjector currentInjector() {
        return InjectorFactory.create(settings.getInjector());
    }

    private void reportNoInjector() {
        statusText = "没有可用的操作注入方式，请开启无障碍服务或授权 Shizuku / Root";
        notifyChanged();
    }

    public void injectTestTap() {
        runOnMain(() -> {
            if (phoneWidth <= 0 || phoneHeight <= 0) {
                statusText = "还没有收到手机画面";
                notifyChanged();
                return;
            }
            int[] point = mapToPhone(watchWidth / 2, watchHeight / 2);
            injectTap(point[0], point[1]);
        });
    }

    private int[] mapToPhone(int watchX, int watchY) {
        ViewportMapper.Scale geometry = ViewportMapper.compute(
                Math.max(1, phoneWidth), Math.max(1, phoneHeight),
                watchWidth, watchHeight, mode);
        return ViewportMapper.mapToPhone(watchX, watchY, geometry, viewportOffset);
    }

    private void trySendLatest() {
        if (!watchOpen || link == null || !mirroring || link.isBusy() || sendingFrame) {
            return;
        }

        final int[] pixels;
        final int scaledWidth;
        final int scaledHeight;
        final int phoneW;
        final int phoneH;
        synchronized (pixelLock) {
            if (!frameDirty || latestPixels == null) {
                return;
            }
            pixels = latestPixels;
            latestPixels = null;
            scaledWidth = latestScaledWidth;
            scaledHeight = latestScaledHeight;
            phoneW = latestPhoneWidth;
            phoneH = latestPhoneHeight;
            frameDirty = false;
        }

        ViewportMapper.Scale geometry = ViewportMapper.compute(
                Math.max(1, phoneW), Math.max(1, phoneH),
                watchWidth, watchHeight, mode);
        maxOffset = geometry.maxOffset;
        viewportOffset = ViewportMapper.clampOffset(viewportOffset, maxOffset);

        if (TouchOverlayController.isShowing()) {
            TouchTrailOverlay.eraseFromFrame(pixels, scaledWidth, scaledHeight,
                    phoneW, phoneH);
        }

        final FrameEncoder.TileFrame frame;
        try {
            frame = encoder.encode(pixels, scaledWidth, scaledHeight,
                    watchWidth, watchHeight, mode, viewportOffset, maxOffset,
                    forceKeyframe, settings.ditherMode());
        } catch (Throwable t) {
            Log.e(TAG, "frame encode failed", t);
            encoder.reset();
            forceKeyframe = true;
            frameDirty = true;
            statusText = "画面编码失败";
            notifyChanged();
            return;
        }
        forceKeyframe = false;

        if (frame.tiles.isEmpty()) {
            if (hasLatestFrame()) {
                frameDirty = true;
            }
            return;
        }

        sendingFrame = true;
        try {
            link.sendFrame(frame, maxInbox, success -> main.post(() -> {
                sendingFrame = false;
                if (!success) {
                    encoder.reset();
                    forceKeyframe = true;
                    frameDirty = true;
                    statusText = "发送到手表失败";
                    notifyChanged();
                } else if (!"镜像运行中".equals(statusText)) {
                    statusText = "镜像运行中";
                    notifyChanged();
                }
                if (hasLatestFrame()) {
                    frameDirty = true;
                }
                trySendLatest();
            }));
        } catch (Throwable t) {
            sendingFrame = false;
            encoder.reset();
            forceKeyframe = true;
            frameDirty = true;
            statusText = "发送到手表失败";
            Log.e(TAG, "sendFrame failed", t);
            notifyChanged();
        }
    }

    private boolean hasLatestFrame() {
        synchronized (pixelLock) {
            return latestPixels != null;
        }
    }

    private void sendConfig() {
        LegacyPebbleLink current = link;
        if (current == null) {
            return;
        }
        current.send(WatchProtocol.config(watchWidth, watchHeight, maxOffset,
                mode, mirroring, watchStatus));
    }

    private void sendStatus() {
        LegacyPebbleLink current = link;
        if (current != null && watchOpen) {
            current.send(WatchProtocol.status(mirroring, watchStatus));
        }
    }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onMirrorStateChanged();
                } catch (Throwable t) {
                    Log.w(TAG, "listener failed", t);
                }
            }
        });
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            main.post(runnable);
        }
    }

    public boolean isMirroring() {
        return mirroring;
    }

    public boolean isWatchOpen() {
        return watchOpen;
    }

    public int getPhoneWidth() {
        return phoneWidth;
    }

    public int getPhoneHeight() {
        return phoneHeight;
    }

    public int getWatchWidth() {
        return watchWidth;
    }

    public int getWatchHeight() {
        return watchHeight;
    }

    public String getStatusText() {
        return statusText;
    }

    public String getInjectorDescription() {
        return InjectorFactory.describe(settings.getInjector());
    }
}


