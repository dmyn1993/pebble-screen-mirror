package com.pebblemirror.companion;

import android.app.Application;

public final class MirrorApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        MirrorController.init(this);
        NotificationHelper.ensureChannel(this);
    }
}
