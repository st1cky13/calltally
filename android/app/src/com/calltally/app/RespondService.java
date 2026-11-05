package com.calltally.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * The messaging app must offer a service for android.intent.action.RESPOND_VIA_MESSAGE (the "reply with a message"
 * action from the call screen). Call Tally never sends messages, so this does nothing and stops at once.
 */
public class RespondService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
