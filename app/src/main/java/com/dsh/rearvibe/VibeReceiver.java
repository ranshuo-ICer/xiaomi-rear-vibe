package com.dsh.rearvibe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * adb-facing remote control. Broadcasts are NOT routed through
 * ActivityStarter, so they reach the app even while it lives on the
 * Xiaomi rear display (where MIUI blocks third-party activity starts).
 */
public class VibeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        MainActivity host = MainActivity.instance;
        if (host != null) {
            host.postCommand(intent);
            return;
        }
        // App not running: cold-start it with the command attached.
        Intent launch = new Intent(intent);
        launch.setClass(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(launch);
        } catch (Exception ignored) {
            // MIUI may refuse a cold start onto the rear display; next launch works.
        }
    }
}
