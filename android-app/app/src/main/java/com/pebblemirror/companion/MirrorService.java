package com.pebblemirror.companion;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.pebblemirror.companion.capture.ScreenCapture;
import com.pebblemirror.companion.pebble.LegacyPebbleLink;

/** Foreground service that owns the MediaProjection capture session. */
public final class MirrorService extends Service {
    private static final String TAG = "PebbleMirrorService";
    public static final String ACTION_START = "com.pebblemirror.companion.START";
    public static final String ACTION_STOP = "com.pebblemirror.companion.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private ScreenCapture capture;
    private LegacyPebbleLink link;
    private MirrorController controller;

    public static void start(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, MirrorService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, MirrorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.ensureChannel(this);
        controller = MirrorController.get();
        controller.setCaptureErrorListener(this::stopSelf);
        link = new LegacyPebbleLink(this, MirrorController.WATCHAPP_UUID,
                controller::onWatchMessage);
        controller.attachLink(link);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        Notification notification = NotificationHelper.buildServiceNotification(this);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Best effort: open the watchapp so the user does not have to find it
        // in the launcher after granting screen capture.
        LegacyPebbleLink.launchWatchapp(this, MirrorController.WATCHAPP_UUID);

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultData == null) {
            Log.e(TAG, "Missing MediaProjection result data");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                Log.e(TAG, "getMediaProjection returned null");
                stopSelf();
                return START_NOT_STICKY;
            }

            MirrorController current = MirrorController.get();
            if (capture != null) {
                capture.stop();
                capture = null;
                controller.detachCapture();
            }
            capture = new ScreenCapture(this, projection, new ScreenCapture.FrameCallback() {
                @Override
                public void onScaledFrame(int[] pixels, int scaledWidth, int scaledHeight,
                                          int phoneWidth, int phoneHeight) {
                    current.onScaledFrame(pixels, scaledWidth, scaledHeight,
                            phoneWidth, phoneHeight);
                }

                @Override
                public void onCaptureError(String message) {
                    current.onCaptureError(message);
                    stopSelf();
                }
            },
                    current.getWatchWidth(),
                    current.getWatchHeight(),
                    current.getSettings().getMode(),
                    current.getSettings().getFps());
            capture.start();
            controller.attachCapture(capture);
        } catch (Throwable t) {
            Log.e(TAG, "Unable to start capture", t);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (capture != null) {
            capture.stop();
            capture = null;
        }
        if (controller != null) {
            controller.detachCapture();
            controller.detachLink(link);
            controller.setCaptureErrorListener(null);
        }
        if (link != null) {
            link.close();
            link = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
