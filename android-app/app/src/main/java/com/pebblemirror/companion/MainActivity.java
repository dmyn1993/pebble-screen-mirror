package com.pebblemirror.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.pebblemirror.companion.inject.AccessibilityInjector;
import com.pebblemirror.companion.inject.ShizukuCompat;
import com.pebblemirror.companion.inject.ShellInjector;
import com.pebblemirror.companion.pebble.LegacyPebbleLink;

import rikka.shizuku.Shizuku;

public final class MainActivity extends AppCompatActivity implements MirrorController.Listener {
    private static final int REQ_PROJECTION = 100;
    private static final int REQ_NOTIFICATION = 101;
    private static final int REQ_SHIZUKU = 102;

    private MirrorController controller;
    private MirrorSettings settings;

    private TextView statusMirroring;
    private TextView statusWatch;
    private TextView statusInjector;
    private TextView statusHint;
    private MaterialButton startButton;
    private MaterialButton accessibilityButton;
    private MaterialButton shizukuButton;

    private Spinner modeSpinner;
    private Spinner ditherSpinner;
    private Spinner fpsSpinner;
    private Spinner injectorSpinner;
    private boolean updatingSpinners;

    private boolean pendingProjectionStart;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> runOnUiThread(this::updateStatus);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        controller = MirrorController.get();
        settings = controller.getSettings();
        controller.addListener(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.title);

        statusMirroring = findViewById(R.id.status_mirroring);
        statusWatch = findViewById(R.id.status_watch);
        statusInjector = findViewById(R.id.status_injector);
        statusHint = findViewById(R.id.status_hint);
        startButton = findViewById(R.id.button_start_stop);
        accessibilityButton = findViewById(R.id.button_accessibility);
        shizukuButton = findViewById(R.id.button_shizuku);

        modeSpinner = findViewById(R.id.spinner_mode);
        ditherSpinner = findViewById(R.id.spinner_dither);
        fpsSpinner = findViewById(R.id.spinner_fps);
        injectorSpinner = findViewById(R.id.spinner_injector);

        setupSpinners();
        setupButtons();

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
            // Shizuku is not installed or not running yet.
        }

        updateStatus();
        new Handler(Looper.getMainLooper()).postDelayed(this::updateStatus, 2000);
    }
    private void setupSpinners() {
        updatingSpinners = true;
        modeSpinner.setAdapter(adapter(R.array.mode_labels));
        ditherSpinner.setAdapter(adapter(R.array.dither_labels));
        fpsSpinner.setAdapter(adapter(R.array.fps_labels));
        injectorSpinner.setAdapter(adapter(R.array.injector_labels));

        modeSpinner.setSelection(settings.getMode() == 1 ? 1 : 0, false);
        ditherSpinner.setSelection(settings.getDither() == 1 ? 1 : 0, false);
        fpsSpinner.setSelection(fpsIndex(settings.getFps()), false);
        injectorSpinner.setSelection(settings.getInjector(), false);
        updatingSpinners = false;

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingSpinners) {
                    return;
                }
                applySettingsFromSpinners();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        modeSpinner.setOnItemSelectedListener(listener);
        ditherSpinner.setOnItemSelectedListener(listener);
        fpsSpinner.setOnItemSelectedListener(listener);
        injectorSpinner.setOnItemSelectedListener(listener);
    }

    private ArrayAdapter<CharSequence> adapter(int arrayRes) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, arrayRes, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void applySettingsFromSpinners() {
        settings.setMode(modeSpinner.getSelectedItemPosition());
        settings.setDither(ditherSpinner.getSelectedItemPosition());
        settings.setFps(fpsForIndex(fpsSpinner.getSelectedItemPosition()));
        settings.setInjector(injectorSpinner.getSelectedItemPosition());
        settings.save(this);
        if (settings.getInjector() == MirrorSettings.INJECTOR_ROOT) {
            // Ask for root only when the user explicitly selects it.
            ShellInjector.resetRootDetection();
            ShellInjector.detectRootAsync();
            new Handler(Looper.getMainLooper()).postDelayed(this::updateStatus, 2000);
        }
        controller.setSettings(settings);
        updateStatus();
    }

    private static int fpsIndex(int fps) {
        if (fps >= 5) {
            return 3;
        }
        if (fps >= 3) {
            return 2;
        }
        if (fps >= 2) {
            return 1;
        }
        return 0;
    }

    private static int fpsForIndex(int index) {
        switch (index) {
            case 1:
                return 2;
            case 2:
                return 3;
            case 3:
                return 5;
            case 0:
            default:
                return 1;
        }
    }

    private void setupButtons() {
        startButton.setOnClickListener(v -> {
            if (controller.isMirroring()) {
                MirrorService.stop(this);
                updateStatus();
            } else {
                beginMirroring();
            }
        });

        accessibilityButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, R.string.accessibility_settings, Toast.LENGTH_LONG).show();
            }
        });

        shizukuButton.setOnClickListener(v -> {
            if (!ShizukuCompat.isBinderAlive()) {
                Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_LONG).show();
                ShizukuCompat.openManager(this);
                return;
            }
            if (ShizukuCompat.hasPermission()) {
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show();
                return;
            }
            ShizukuCompat.requestPermission(REQ_SHIZUKU);
            Toast.makeText(this, R.string.shizuku_permission, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.button_test_tap).setOnClickListener(v -> {
            if (!controller.isMirroring()) {
                Toast.makeText(this, R.string.need_mirroring, Toast.LENGTH_SHORT).show();
                return;
            }
            controller.injectTestTap();
            Toast.makeText(this, R.string.test_tap_sent, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.button_open_watchapp).setOnClickListener(v -> {
            LegacyPebbleLink.launchWatchapp(this, MirrorController.WATCHAPP_UUID);
            Toast.makeText(this, R.string.watchapp_launch_sent, Toast.LENGTH_SHORT).show();
        });
    }

    private void beginMirroring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingProjectionStart = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            return;
        }
        launchProjectionConsent();
    }

    private void launchProjectionConsent() {
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ otherwise defaults to the "single app" picker on
                // some OEM builds, which cannot mirror the whole screen.
                intent = manager.createScreenCaptureIntent(
                        MediaProjectionConfig.createConfigForDefaultDisplay());
            } else {
                intent = manager.createScreenCaptureIntent();
            }
            startActivityForResult(intent, REQ_PROJECTION);
        } catch (Throwable t) {
            Toast.makeText(this, "无法启动录屏授权：" + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION) {
            if (pendingProjectionStart) {
                pendingProjectionStart = false;
                launchProjectionConsent();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                MirrorService.start(this, resultCode, data);
                Toast.makeText(this, R.string.projection_started, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.projection_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        controller.removeListener(this);
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onMirrorStateChanged() {
        runOnUiThread(this::updateStatus);
    }

    private void updateStatus() {
        if (isFinishing()) {
            return;
        }
        statusMirroring.setText(controller.isMirroring()
                ? R.string.status_mirroring_on : R.string.status_mirroring_off);
        statusWatch.setText(controller.isWatchOpen()
                ? R.string.status_watch_open : R.string.status_watch_waiting);
        statusInjector.setText(getString(R.string.status_injector,
                controller.getInjectorDescription()));

        String detail = controller.getStatusText();
        statusHint.setText(detail == null || detail.isEmpty()
                ? getString(R.string.status_hint) : detail);
        startButton.setText(controller.isMirroring()
                ? R.string.stop_mirroring : R.string.start_mirroring);

        accessibilityButton.setText(AccessibilityInjector.isServiceAvailable()
                ? "无障碍手势已开启" : getString(R.string.enable_accessibility));
        shizukuButton.setText(ShizukuCompat.hasPermission()
                ? "Shizuku 已授权" : getString(R.string.grant_shizuku));
    }
}
