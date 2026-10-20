package com.pebblemirror.companion.inject;

import com.pebblemirror.companion.MirrorSettings;

/** Chooses the best available input injector for the current settings. */
public final class InjectorFactory {
    private InjectorFactory() {
    }

    public static GestureInjector create(int mode) {
        switch (mode) {
            case MirrorSettings.INJECTOR_ACCESSIBILITY:
                return new AccessibilityInjector();
            case MirrorSettings.INJECTOR_SHIZUKU:
                return new ShellInjector(ShellInjector.Mode.SHIZUKU);
            case MirrorSettings.INJECTOR_ROOT:
                return new ShellInjector(ShellInjector.Mode.ROOT);
            case MirrorSettings.INJECTOR_AUTO:
            default:
                if (AccessibilityInjector.isServiceAvailable()) {
                    return new AccessibilityInjector();
                }
                if (ShizukuCompat.isAvailable()) {
                    return new ShellInjector(ShellInjector.Mode.SHIZUKU);
                }
                if (ShellInjector.isRootAvailable()) {
                    return new ShellInjector(ShellInjector.Mode.ROOT);
                }
                return new NoopInjector();
        }
    }

    public static String describe(int mode) {
        switch (mode) {
            case MirrorSettings.INJECTOR_ACCESSIBILITY:
                return "无障碍服务" + (AccessibilityInjector.isServiceAvailable()
                        ? "（已开启）" : "（未开启）");
            case MirrorSettings.INJECTOR_SHIZUKU:
                if (!ShizukuCompat.isBinderAlive()) {
                    return "Shizuku（未运行）";
                }
                return "Shizuku" + (ShizukuCompat.hasPermission() ? "（已授权）" : "（未授权）");
            case MirrorSettings.INJECTOR_ROOT:
                if (ShellInjector.isRootCheckPending()) {
                    return "Root（检测中）";
                }
                return "Root" + (ShellInjector.isRootAvailable() ? "（可用）" : "（不可用）");
            case MirrorSettings.INJECTOR_AUTO:
            default:
                if (AccessibilityInjector.isServiceAvailable()) {
                    return "自动（无障碍服务）";
                }
                if (ShizukuCompat.isAvailable()) {
                    return "自动（Shizuku）";
                }
                if (ShellInjector.isRootAvailable()) {
                    return "自动（Root）";
                }
                return "自动（不可用；可切换到 Root 模式授权）";
        }
    }

    private static final class NoopInjector implements GestureInjector {
        @Override
        public boolean tap(int x, int y) {
            return false;
        }

        @Override
        public boolean longPress(int x, int y) {
            return false;
        }

        @Override
        public boolean swipe(int x1, int y1, int x2, int y2, long durationMs) {
            return false;
        }

        @Override
        public boolean key(int key) {
            return false;
        }

        @Override
        public String describe() {
            return "不可用";
        }

        @Override
        public boolean isAvailable() {
            return false;
        }
    }
}
