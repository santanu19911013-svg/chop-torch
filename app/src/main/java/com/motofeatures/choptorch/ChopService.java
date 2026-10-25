package com.motofeatures.choptorch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service that watches the accelerometer and toggles the flashlight on a double chop.
 * Holds a partial wake lock so detection also works with the screen off (no wake-up accelerometer on
 * most phones). In record mode it also writes every sample to a CSV for calibration, and logs
 * Motorola's own chop sensor when the phone has one.
 */
public class ChopService extends Service implements SensorEventListener, ChopDetector.Listener {
    static final String ACTION_SYNC = "com.motofeatures.choptorch.SYNC";
    static final String ACTION_RECORD_START = "com.motofeatures.choptorch.RECORD_START";
    static final String ACTION_RECORD_STOP = "com.motofeatures.choptorch.RECORD_STOP";
    private static final String TAG = "ChopTorch";
    private static final String CHANNEL = "chop";
    private static final int NOTIFICATION_ID = 1;
    /** Motorola's sensor-hub chop gesture (dumpsys sensorservice: com.motorola.sensor.chopchop). */
    static final String MOTO_CHOP = "com.motorola.sensor.chopchop";
    private static final int DETECT_PERIOD_US = 10_000; // 100 Hz

    // Read by MainActivity's status line
    static volatile boolean running, recording;
    static volatile int motoChops, detections, bursts;
    static volatile long samples;
    static volatile String recordPath;

    private HandlerThread thread;
    private Handler handler;
    private SensorManager sm;
    private Sensor accel, gyro, motoChop;
    private ChopDetector detector;
    private Torch torch;
    private PowerManager.WakeLock wakeLock;
    private BufferedWriter out;
    private SoundPool sounds;
    private int clickId;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            boolean on = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            handler.post(() -> write("S," + SystemClock.elapsedRealtimeNanos() + "," + (on ? 1 : 0)));
        }
    };

    static void sync(Context c) {
        Intent i = new Intent(c, ChopService.class).setAction(ACTION_SYNC);
        if (Prefs.enabled(c) || recording) c.startForegroundService(i);
        else c.stopService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("chop-sensors");
        thread.start();
        handler = new Handler(thread.getLooper());
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
            if (MOTO_CHOP.equals(s.getStringType())) motoChop = s;
        }
        detector = new ChopDetector(this);
        detector.setSensorRange(accel.getMaximumRange());
        torch = new Torch(this, handler);
        sounds = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        clickId = sounds.load(this, R.raw.click, 1); // res/raw/click.wav, synthesised for this app
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChopTorch:detect");
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String action = intent != null ? intent.getAction() : ACTION_SYNC;
        if (!BuildConfig.CALIBRATION) action = ACTION_SYNC; // prod: no recorder
        if (ACTION_RECORD_START.equals(action)) handler.post(this::startRecording);
        else if (ACTION_RECORD_STOP.equals(action)) handler.post(this::stopRecording);
        handler.post(this::applyState);
        return START_STICKY;
    }

    /** Listens while detection is enabled or a recording runs; stops the service otherwise. */
    private void applyState() {
        boolean want = Prefs.enabled(this) || recording;
        sm.unregisterListener(this);
        if (!want) {
            running = false;
            stopSelf();
            return;
        }
        int period = recording ? SensorManager.SENSOR_DELAY_FASTEST : DETECT_PERIOD_US;
        sm.registerListener(this, accel, period, handler);
        if (recording) {
            if (gyro != null) sm.registerListener(this, gyro, period, handler);
            if (motoChop != null) sm.registerListener(this, motoChop, SensorManager.SENSOR_DELAY_NORMAL, handler);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
        detector.reset();
        running = true;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        Sensor s = e.sensor;
        if (s == accel) {
            samples++;
            detector.feed(e.timestamp, e.values[0], e.values[1], e.values[2]);
            bursts = detector.chops();
            if (out != null) write("A," + e.timestamp + "," + e.values[0] + "," + e.values[1] + "," + e.values[2]);
        } else if (s == gyro) {
            if (out != null) write("G," + e.timestamp + "," + e.values[0] + "," + e.values[1] + "," + e.values[2]);
        } else if (s == motoChop) {
            motoChops++;
            if (out != null) write("C," + e.timestamp + "," + (e.values.length > 0 ? e.values[0] : 0));
        }
    }

    @Override
    public void onDoubleChop(long timeNs) {
        detections++;
        if (out != null) write("D," + timeNs);
        // On a Motorola phone its own gesture also switches the torch; while recording there, only count ours
        if (recording && motoChop != null) return;
        if (torch.toggle() != null) feedback();
    }

    /** The same pulse and click for on and off, played only once the torch has really switched. */
    private void feedback() {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            // Alarm usage: still felt in silent mode and with touch feedback off, like Motorola's own gesture
            v.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        }
        // The click follows the system volume and stays quiet in silent and vibrate modes
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (clickId != 0 && Prefs.sound(this) && am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
            sounds.play(clickId, 1f, 1f, 1, 0, 1f);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void startRecording() {
        if (out != null) return;
        File dir = new File(getFilesDir(), "rec");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "rec_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv");
        try {
            out = new BufferedWriter(new FileWriter(f), 1 << 16);
            motoChops = detections = 0;
            samples = 0;
            recordPath = f.getAbsolutePath();
            recording = true;
            write("# ChopTorch recording. A=accel m/s2, G=gyro rad/s, C=Motorola chop sensor, D=our detection, S=screen 1/0");
            write("# model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " motoChopSensor=" + (motoChop != null)
                    + " accel=" + accel.getName() + " vendor=" + accel.getVendor() + " minDelayUs=" + accel.getMinDelay()
                    + " maxRange=" + accel.getMaximumRange() + " resolution=" + accel.getResolution());
            write("M," + SystemClock.elapsedRealtimeNanos() + "," + System.currentTimeMillis());
        } catch (IOException e) {
            Log.w(TAG, "record: " + e);
        }
    }

    private void stopRecording() {
        recording = false;
        if (out == null) return;
        try {
            out.close();
        } catch (IOException e) {
            Log.w(TAG, "record close: " + e);
        }
        out = null;
    }

    private void write(String line) {
        if (out == null) return;
        try {
            out.write(line);
            out.write('\n');
        } catch (IOException e) {
            Log.w(TAG, "write: " + e);
        }
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(getString(recording ? R.string.notif_recording : R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        sm.unregisterListener(this);
        unregisterReceiver(screen);
        handler.post(() -> {
            stopRecording();
            torch.close();
            sounds.release();
        });
        thread.quitSafely();
        if (wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
