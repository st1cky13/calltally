package com.calltally.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * The messaging app must offer a way to start a new message (an android.intent.action.SENDTO handler). Call Tally never
 * sends messages, so this does nothing of its own: it hands the same intent to Google Messages when it is installed, or
 * says plainly that Call Tally does not send messages, then closes.
 */
public class SendActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent incoming = getIntent();
        if (incoming != null && incoming.getData() != null) {
            try {
                Intent toMessages = new Intent(incoming.getAction(), incoming.getData())
                    .setPackage(Roles.GOOGLE_MESSAGES)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (getPackageManager().resolveActivity(toMessages, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    startActivity(toMessages);
                    finish();
                    return;
                }
            } catch (RuntimeException error) {
                // Fall through to the toast below.
            }
        }
        Toast.makeText(this, "Call Tally does not send messages", Toast.LENGTH_LONG).show();
        finish();
    }
}
