package com.calltally.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Asks for permission, shows the last upload, and offers an upload on demand. Uploads run without this screen. */
public class MainActivity extends Activity {
    private static final int ASK = 1;
    private boolean asked;
    private boolean busy;
    private TextView status;
    private Button now;
    private Button again;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int gap = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFitsSystemWindows(true);
        page.setPadding(gap, gap, gap, gap);
        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 0, 0, gap);
        now = new Button(this);
        now.setText("Upload now");
        now.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                start(false);
            }
        });
        again = new Button(this);
        again.setText("Send everything again");
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                start(true);
            }
        });
        page.addView(status);
        page.addView(now);
        page.addView(again);
        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Sync.allowed(this)) {
            Sync.schedule(this);
            // Covers access granted in Settings, where the permission dialog's answer never arrives.
            if (Sync.neverRun(this)) start(false);
            else if (!busy) status.setText(Sync.status(this));
        } else {
            status.setText("This app needs your call log, SMS and contacts. If Android won't offer them, open App info"
                + " for Call Tally Sync, tap the three-dot menu, choose Allow restricted settings, and come back.");
            // Only once each time the app is opened: Android answers a refused request at once, which would loop.
            if (!asked) {
                asked = true;
                requestPermissions(Sync.PERMISSIONS, ASK);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] names, int[] results) {
        if (code == ASK && Sync.allowed(this)) {
            Sync.schedule(this);
            start(false);
        }
    }

    private void start(final boolean everything) {
        if (busy) return;
        if (!Sync.allowed(this)) {
            requestPermissions(Sync.PERMISSIONS, ASK);
            return;
        }
        busy = true;
        now.setEnabled(false);
        again.setEnabled(false);
        status.setText("Uploading…");
        final android.content.Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ran = Sync.run(context, everything);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        status.setText(ran ? Sync.status(context) : "An upload is already running. Try again in a minute.");
                        now.setEnabled(true);
                        again.setEnabled(true);
                    }
                });
            }
        }).start();
    }
}
