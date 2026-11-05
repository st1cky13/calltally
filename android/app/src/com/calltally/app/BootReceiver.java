package com.calltally.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The wake-on-change job doesn't survive a restart, so it's set up again here. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) Sync.schedule(context);
    }
}
