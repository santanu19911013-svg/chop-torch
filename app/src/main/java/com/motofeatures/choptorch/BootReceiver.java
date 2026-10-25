package com.motofeatures.choptorch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts detection after a reboot or an app update, if it was switched on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (Prefs.enabled(c)) ChopService.sync(c);
    }
}
