package com.calltally.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Asks for permission, shows the last run and the size of the record, and opens Who counts. Runs happen without this screen. */
public class MainActivity extends Activity {
    private static final int ASK = 1;
    private boolean asked;
    private boolean busy;
    private TextView status;
    private Button now;
    private Button who;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Sync.noteCrashes(this);
        int gap = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFitsSystemWindows(true);
        page.setPadding(gap, gap, gap, gap);
        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 0, 0, gap);
        who = new Button(this);
        who.setText("Who counts");
        who.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, WhoActivity.class));
            }
        });
        now = new Button(this);
        now.setText("Run now");
        now.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                start();
            }
        });
        page.addView(status);
        page.addView(who);
        page.addView(now);
        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Sync.allowed(this)) {
            Sync.schedule(this);
            // Covers access granted in Settings, where the permission dialog's answer never arrives.
            if (Sync.neverRun(this)) start();
            else if (!busy) show();
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
            start();
        }
    }

    /** The last run, then what the record holds. A record from a newer version shows only that. */
    private void show() {
        status.setText(summary(getApplicationContext()));
    }

    private static String summary(Context context) {
        String text = Sync.status(context);
        try {
            Record record = Record.get(context);
            int people = 0;
            for (Rules.Person person : record.people()) if (person.ticked) people++;
            int entries = record.count();
            text += "\n\nThe record holds " + entries + (entries == 1 ? " entry" : " entries") + ". "
                + people + (people == 1 ? " person is" : " people are") + " ticked.";
        } catch (Record.TooNew error) {
            text = error.getMessage();
        }
        return text;
    }

    private void start() {
        if (busy) return;
        if (!Sync.allowed(this)) {
            requestPermissions(Sync.PERMISSIONS, ASK);
            return;
        }
        busy = true;
        now.setEnabled(false);
        status.setText("Reading the phone…");
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ran = Sync.run(context);
                final String text = ran ? summary(context) : "A run is already going. Try again in a minute.";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        status.setText(text);
                        now.setEnabled(true);
                    }
                });
            }
        }).start();
    }
}
