package com.motofeatures.choptorch;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

/** On/off switch, battery-optimisation shortcut, and the calibration recorder. */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button record, battery;
    private TextView batteryHint;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            String service = ChopService.running ? getString(R.string.on) : getString(R.string.off);
            if (BuildConfig.CALIBRATION) {
                status.setText(getString(R.string.status_fmt, service,
                        ChopService.detections, ChopService.bursts, ChopService.motoChops, ChopService.samples,
                        ChopService.recording ? ChopService.recordPath : "—"));
                record.setText(ChopService.recording ? R.string.record_stop : R.string.record_start);
            } else {
                status.setText(getString(R.string.status_prod_fmt, service, ChopService.detections));
            }
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = (TextView) findViewById(R.id.status);
        record = (Button) findViewById(R.id.record);

        Switch enable = (Switch) findViewById(R.id.enable);
        enable.setChecked(Prefs.enabled(this));
        enable.setOnCheckedChangeListener((b, on) -> {
            Prefs.setEnabled(this, on);
            ChopService.sync(this);
        });

        Switch sound = (Switch) findViewById(R.id.sound);
        sound.setChecked(Prefs.sound(this));
        sound.setOnCheckedChangeListener((b, on) -> Prefs.setSound(this, on)); // the service reads it per toggle

        battery = (Button) findViewById(R.id.battery);
        batteryHint = (TextView) findViewById(R.id.battery_hint);
        battery.setOnClickListener(v -> {
            if (backgroundAllowed()) {
                // Already exempt: open this app's info page, where battery and background settings live
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } else if (!ignoringOptimizations()) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } else {
                // Exempt from Doze but "Background restriction" is on (Android 9+): only the app info page can lift it
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });

        ((TextView) findViewById(R.id.version)).setText(getString(R.string.version_fmt,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        if (BuildConfig.CALIBRATION) setUpCalibration();
        else findViewById(R.id.calibration_group).setVisibility(View.GONE);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /** Dev flavor only: recorder, upload to the PC and the adb record intents. */
    private void setUpCalibration() {
        EditText server = (EditText) findViewById(R.id.server);
        server.setText(Prefs.server(this));
        TextView upload = (TextView) findViewById(R.id.upload_status);
        findViewById(R.id.upload).setOnClickListener(v -> {
            String url = server.getText().toString();
            Prefs.setServer(this, url);
            upload.setText(R.string.sending);
            new Thread(() -> {
                StringBuilder log = new StringBuilder();
                int n = Uploader.sendAll(getApplicationContext(), url, line -> {
                    log.append(line).append('\n');
                    main.post(() -> upload.setText(log));
                });
                log.append(getString(R.string.sent_fmt, n));
                main.post(() -> upload.setText(log));
            }, "upload").start();
        });

        record.setOnClickListener(v -> startForegroundService(new Intent(this, ChopService.class)
                .setAction(ChopService.recording ? ChopService.ACTION_RECORD_STOP : ChopService.ACTION_RECORD_START)));

        handleRecordIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleRecordIntent(intent); // the activity was already on top
    }

    /** adb shell am start -n com.motofeatures.choptorch/.MainActivity -a com.motofeatures.choptorch.RECORD_START */
    private void handleRecordIntent(Intent intent) {
        if (!BuildConfig.CALIBRATION) return;
        String action = intent.getAction();
        if (ChopService.ACTION_RECORD_START.equals(action) || ChopService.ACTION_RECORD_STOP.equals(action)) {
            startForegroundService(new Intent(this, ChopService.class).setAction(action));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        showBatteryState(); // the user may have just come back from the settings screen
        main.post(refresh);
    }

    private boolean ignoringOptimizations() {
        return ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName());
    }

    /** Android 9+ "Background restriction" (Samsung: "Restrict background activity"), separate from Doze. */
    private boolean backgroundRestricted() {
        return Build.VERSION.SDK_INT >= 28 && ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).isBackgroundRestricted();
    }

    private boolean backgroundAllowed() {
        return ignoringOptimizations() && !backgroundRestricted();
    }

    private void showBatteryState() {
        boolean allowed = backgroundAllowed();
        battery.setText(allowed ? R.string.battery_allowed
                : backgroundRestricted() ? R.string.battery_bg_restricted : R.string.battery_restricted);
        battery.setTextColor(allowed ? 0xFF66BB6A : 0xFFFFA726);
        // Vendor battery managers have no API; say where to look on the brands we tested
        String maker = Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        int hint = maker.contains("samsung") ? R.string.hint_samsung
                : maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco") ? R.string.hint_xiaomi
                : 0;
        batteryHint.setVisibility(hint != 0 ? View.VISIBLE : View.GONE);
        if (hint != 0) batteryHint.setText(hint);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(refresh);
        super.onPause();
    }
}
