package com.pebblemirror.companion.inject;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional accessibility service used to synthesize gestures without root or
 * Shizuku. It does not read window content.
 */
public final class MirrorAccessibilityService extends AccessibilityService {
    private static volatile MirrorAccessibilityService instance;

    public static MirrorAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No window content is inspected.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
