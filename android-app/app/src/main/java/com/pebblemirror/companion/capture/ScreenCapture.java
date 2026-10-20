package com.pebblemirror.companion.capture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import com.pebblemirror.companion.protocol.WatchProtocol;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the MediaProjection virtual display, scales each captured frame to the
 * watch geometry and hands ARGB pixels to the controller. All image work runs
 * on a dedicated HandlerThread.
 */
public final class ScreenCapture {
    private static final String TAG = "PebbleMirrorCapture";
    private static final int MAX_CAPTURE_WIDTH = 480;

    public interface FrameCallback {
        void onScaledFrame(int[] pixels, int scaledWidth, int scaledHeight,
                           int phoneWidth, int phoneHeight);

        void onCaptureError(String message);
    }

    private final Context context;
    private final MediaProjection projection;
    private final FrameCallback callback;
    private final AtomicBoolean processing = new AtomicBoolean(false);

    private HandlerThread thread;
    private Handler handler;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private MediaProjection.Callback projectionCallback;
    private DisplayManager.DisplayListener displayListener;

    private volatile int phoneWidth;
    private volatile int phoneHeight;
    private int captureWidth;
    private int captureHeight;
    private volatile int watchWidth;
    private volatile int watchHeight;
    private volatile int mode;

    private volatile int targetFps = 2;
    private volatile long minFrameIntervalMs = 500;
    private long lastFrameMs;
    private volatile boolean stopped;
    private boolean released;
    private boolean errorReported;
    private Bitmap lastCaptureBitmap;

    public ScreenCapture(Context context, MediaProjection projection, FrameCallback callback,
                         int watchWidth, int watchHeight, int mode, int fps) {
        this.context = context.getApplicationContext();
        this.projection = projection;
        this.callback = callback;
        this.watchWidth = watchWidth;
        this.watchHeight = watchHeight;
        this.mode = mode;
        setTargetFps(fps);
    }

    public void start() {
        thread = new HandlerThread("pebble-mirror-capture");
        thread.start();
        handler = new Handler(thread.getLooper());

        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (!stopped) {
                    reportError("录屏已停止");
                }
                releaseInternal(false);
            }
        };
        projection.registerCallback(projectionCallback, handler);

        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY && handler != null) {
                    handler.removeCallbacks(recreateRunnable);
                    handler.postDelayed(recreateRunnable, 700);
                }
            }
        };
        DisplayManager displayManager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(displayListener, handler);

        handler.post(() -> {
            readDisplaySize();
            configureDimensions();
            createVirtualDisplay();
        });
    }

    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        Handler h = handler;
        if (h != null) {
            h.post(() -> releaseInternal(true));
        } else {
            releaseInternal(true);
        }
    }

    public void setTargetFps(int fps) {
        targetFps = Math.max(1, Math.min(5, fps));
        minFrameIntervalMs = 1000L / targetFps;
    }

    public void setWatchConfig(int width, int height, int newMode) {
        boolean changed = width != watchWidth || height != watchHeight || newMode != mode;
        watchWidth = width;
        watchHeight = height;
        mode = newMode;
        if (changed) {
            requestRerender();
        }
    }

    public void requestRerender() {
        Handler h = handler;
        if (h == null || stopped) {
            return;
        }
        h.post(() -> {
            Bitmap bitmap = lastCaptureBitmap;
            if (bitmap != null && !bitmap.isRecycled()) {
                renderFromCapture(bitmap);
            }
        });
    }

    public int getPhoneWidth() {
        return phoneWidth;
    }

    public int getPhoneHeight() {
        return phoneHeight;
    }

    private final Runnable recreateRunnable = () -> {
        if (stopped) {
            return;
        }
        releaseVirtualDisplay();
        readDisplaySize();
        configureDimensions();
        createVirtualDisplay();
    };

    private void readDisplaySize() {
        try {
            DisplayManager displayManager =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            phoneWidth = Math.max(1, metrics.widthPixels);
            phoneHeight = Math.max(1, metrics.heightPixels);
        } catch (Throwable t) {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(metrics);
            phoneWidth = Math.max(1, metrics.widthPixels);
            phoneHeight = Math.max(1, metrics.heightPixels);
        }
    }

    private void configureDimensions() {
        captureWidth = Math.min(phoneWidth, MAX_CAPTURE_WIDTH);
        captureHeight = Math.max(1,
                Math.round(phoneHeight * (captureWidth / (float) phoneWidth)));
    }

    private void createVirtualDisplay() {
        if (stopped || virtualDisplay != null) {
            return;
        }
        try {
            imageReader = ImageReader.newInstance(captureWidth, captureHeight,
                    PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, handler);

            int density = context.getResources().getDisplayMetrics().densityDpi;
            virtualDisplay = projection.createVirtualDisplay(
                    "PebbleMirror",
                    captureWidth,
                    captureHeight,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler);
            if (virtualDisplay == null) {
                reportError("无法创建录屏虚拟显示");
            }
        } catch (Throwable t) {
            Log.e(TAG, "createVirtualDisplay failed", t);
            reportError("创建录屏失败：" + t.getMessage());
        }
    }

    private void onImageAvailable(ImageReader reader) {
        if (stopped) {
            return;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastFrameMs < minFrameIntervalMs) {
                return;
            }
            if (!processing.compareAndSet(false, true)) {
                return;
            }
            try {
                Bitmap bitmap = imageToBitmap(image);
                lastFrameMs = now;
                if (lastCaptureBitmap != null && lastCaptureBitmap != bitmap) {
                    lastCaptureBitmap.recycle();
                }
                lastCaptureBitmap = bitmap;
                renderFromCapture(bitmap);
            } finally {
                processing.set(false);
            }
        } catch (Throwable t) {
            Log.e(TAG, "frame processing failed", t);
            reportError("处理屏幕帧失败：" + t.getMessage());
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private Bitmap imageToBitmap(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (pixelStride == 4 && rowStride == width * 4) {
            bitmap.copyPixelsFromBuffer(buffer);
            return bitmap;
        }

        byte[] row = new byte[rowStride];
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int rowStart = y * rowStride;
            if (rowStart + rowStride > buffer.limit()) {
                break;
            }
            buffer.position(rowStart);
            buffer.get(row, 0, rowStride);
            int out = y * width;
            for (int x = 0; x < width; x++) {
                int offset = x * pixelStride;
                if (offset + 3 >= row.length) {
                    break;
                }
                int r = row[offset] & 0xff;
                int g = row[offset + 1] & 0xff;
                int b = row[offset + 2] & 0xff;
                int a = row[offset + 3] & 0xff;
                pixels[out + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private void renderFromCapture(Bitmap bitmap) {
        if (stopped || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        ViewportMapper.Scale geometry = ViewportMapper.compute(
                phoneWidth, phoneHeight, watchWidth, watchHeight, mode);
        int scaledWidth = geometry.scaledWidth;
        int scaledHeight = geometry.scaledHeight;

        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
        int[] pixels = new int[scaledWidth * scaledHeight];
        scaled.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight);
        if (scaled != bitmap) {
            scaled.recycle();
        }
        callback.onScaledFrame(pixels, scaledWidth, scaledHeight, phoneWidth, phoneHeight);
    }

    private void releaseVirtualDisplay() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Throwable ignored) {
            }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Throwable ignored) {
            }
            imageReader = null;
        }
    }

    private void releaseInternal(boolean stopProjection) {
        if (released) {
            return;
        }
        released = true;
        if (handler != null) {
            handler.removeCallbacks(recreateRunnable);
        }
        DisplayManager displayManager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null && displayListener != null) {
            try {
                displayManager.unregisterDisplayListener(displayListener);
            } catch (Throwable ignored) {
            }
        }
        releaseVirtualDisplay();
        if (lastCaptureBitmap != null) {
            lastCaptureBitmap.recycle();
            lastCaptureBitmap = null;
        }
        if (projectionCallback != null) {
            try {
                projection.unregisterCallback(projectionCallback);
            } catch (Throwable ignored) {
            }
        }
        if (stopProjection) {
            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }

    private void reportError(String message) {
        if (errorReported) {
            return;
        }
        errorReported = true;
        callback.onCaptureError(message);
    }
}
