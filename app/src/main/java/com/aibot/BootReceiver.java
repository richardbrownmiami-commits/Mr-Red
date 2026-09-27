package com.aibot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Boot hook kept intentionally passive. Android starts the assistant only
 * when the user opens it; this receiver simply keeps the manifest component valid.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // No automatic UI launch at boot.
    }
}
