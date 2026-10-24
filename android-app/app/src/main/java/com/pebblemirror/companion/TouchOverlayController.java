package com.pebblemirror.companion;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.pebblemirror.companion.inject.MirrorAccessibilityService;

/**
 * Adds a non-touchable, non-focusable overlay window that shows the touch trail
 * on the phone screen. The mirror controller erases the same trail region from
 * the MediaProjection frame before encoding it for the watch.
 */
final class TouchOverlayController {
    private static final String TAG = "PebbleMirrorOverlay";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static TouchTrailView view;
    private static WindowManager windowManager;
    private static volatile boolean showing;

    private TouchOverlayController() {
    }

    static boolean canShow(Context context) {
        if (MirrorAccessibilityService.getInstance() != null) {
            return true;
        }
        try {
            return Settings.canDrawOverlays(context);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isShowing() {
        return showing;
    }

    static void start(Context context) {
        MirrorAccessibilityService accessibilityService = MirrorAccessibilityService.getInstance();
        boolean useAccessibilityOverlay = accessibilityService != null;
        if (!useAccessibilityOverlay && !Settings.canDrawOverlays(context)) {
            return;
        }
        Context overlayContext = useAccessibilityOverlay
                ? accessibilityService : context.getApplicationContext();
        int windowType = useAccessibilityOverlay
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        MAIN.post(() -> {
            if (view != null) {
                return;
            }
            WindowManager manager =
                    (WindowManager) overlayContext.getSystemService(Context.WINDOW_SERVICE);
            if (manager == null) {
                return;
            }
            TouchTrailView trailView = new TouchTrailView(overlayContext);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            try {
                manager.addView(trailView, params);
                view = trailView;
                windowManager = manager;
                showing = true;
            } catch (Throwable t) {
                Log.w(TAG, "could not add touch trail overlay", t);
            }
        });
    }

    static void stop() {
        MAIN.post(() -> {
            TouchTrailView current = view;
            view = null;
            WindowManager manager = windowManager;
            windowManager = null;
            showing = false;
            if (current != null && manager != null) {
                try {
                    manager.removeView(current);
                } catch (Throwable t) {
                    Log.w(TAG, "could not remove touch trail overlay", t);
                }
            }
        });
    }
}
