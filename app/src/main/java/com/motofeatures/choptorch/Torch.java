package com.motofeatures.choptorch;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.util.Log;

/** The rear flashlight via CameraManager.setTorchMode, which needs no CAMERA permission. */
final class Torch extends CameraManager.TorchCallback {
    private static final String TAG = "ChopTorch";
    private final CameraManager cm;
    private final String id;
    private volatile boolean on;

    Torch(Context c, Handler handler) {
        cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        id = findFlash(cm);
        // Tracks changes made elsewhere too (quick settings, Motorola's own gesture), so a toggle is always right
        cm.registerTorchCallback(this, handler);
    }

    boolean available() {
        return id != null;
    }

    /** @return the new state: {@link Boolean#TRUE} on, {@link Boolean#FALSE} off, null if it could not switch */
    Boolean toggle() {
        if (id == null) return null;
        boolean want = !on;
        try {
            cm.setTorchMode(id, want);
            return want;
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.w(TAG, "torch: " + e); // e.g. the camera app holds the camera
            return null;
        }
    }

    void close() {
        cm.unregisterTorchCallback(this);
    }

    @Override
    public void onTorchModeChanged(String cameraId, boolean enabled) {
        if (cameraId.equals(id)) on = enabled;
    }

    private static String findFlash(CameraManager cm) {
        String any = null;
        try {
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(cid);
                if (!Boolean.TRUE.equals(ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) continue;
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return cid;
                if (any == null) any = cid;
            }
        } catch (CameraAccessException e) {
            Log.w(TAG, "camera list: " + e);
        }
        return any;
    }
}
