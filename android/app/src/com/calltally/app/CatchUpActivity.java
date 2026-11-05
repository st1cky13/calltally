package com.calltally.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/**
 * Catch up encrypted: reads the encrypted chats the phone only lets its messaging app see. It asks the owner to make
 * Call Tally the messaging app for a moment (an Android dialog they approve), reads once, then asks them to switch back
 * to Google Messages (another dialog they approve). When Call Tally is already the messaging app, because the owner set
 * it in Settings where the in-app dialog is blocked, Continue reads straight away with no request. It never sends a
 * message, and while it holds the role it says so plainly, here and on the month screen, with a way to switch back.
 */
public class CatchUpActivity extends Screen {
    private static final int BECOME = 11;
    private static final int HAND_BACK = 12;
    private static final int SWITCH_BACK = 13;

    /** Shown when Call Tally is already the messaging app, so the next Continue reads at once. */
    private static final String READY = "Call Tally is the messaging app. Tap Continue to read.";

    private TextView result;
    private TextView holding;
    private TextView last;
    private Button go;
    private Button switchBack;
    private Button settings;
    private boolean reading;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView explain = text(Passthrough.EXPLANATION, 15, txt, false);
        explain.setId(R.id.catch_explain);
        explain.setLineSpacing(0, 1.15f);
        column.addView(explain, wide());

        result = text("", 14, muted, false);
        result.setId(R.id.catch_result);
        result.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams resultGap = wide();
        resultGap.topMargin = dp(16);
        column.addView(result, resultGap);

        // Shown only when the role request was blocked: a way to set Call Tally as the messaging app in Android Settings,
        // the route that works on phones where the in-app request is blocked.
        settings = button("Set in Settings");
        settings.setId(R.id.catch_settings);
        settings.setVisibility(View.GONE);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDefaultAppsSettings();
            }
        });
        LinearLayout.LayoutParams settingsGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        settingsGap.topMargin = dp(10);
        column.addView(settings, settingsGap);

        last = text("", 13, muted, false);
        last.setId(R.id.catch_line);
        LinearLayout.LayoutParams lastGap = wide();
        lastGap.topMargin = dp(10);
        column.addView(last, lastGap);

        holding = problemBox();
        holding.setId(R.id.catch_holding);
        holding.setText(Passthrough.HOLDS_ROLE);
        LinearLayout.LayoutParams holdGap = wide();
        holdGap.topMargin = dp(16);
        column.addView(holding, holdGap);

        switchBack = button("Switch back to Messages");
        switchBack.setId(R.id.switch_back);
        switchBack.setVisibility(View.GONE);
        switchBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handBack(SWITCH_BACK);
            }
        });
        LinearLayout.LayoutParams switchGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchGap.topMargin = dp(8);
        column.addView(switchBack, switchGap);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);

        Button cancel = button("Cancel");
        cancel.setId(R.id.catch_cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        go = button("Continue");
        go.setId(R.id.catch_continue);
        filled(go);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                become();
            }
        });
        footer = footer(cancel, go);
        frame("Catch up encrypted", scroll, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isFinishing()) refresh();
    }

    /** Shows the last catch-up time, and whether Call Tally still holds the role, with the switch-back button. */
    private void refresh() {
        boolean holds = Roles.holdsSmsRole(this);
        holding.setVisibility(holds ? View.VISIBLE : View.GONE);
        switchBack.setVisibility(holds ? View.VISIBLE : View.GONE);
        last.setText(Passthrough.lastCaughtUp(Sync.preferences(this).getLong("caught_up_at", 0), Locale.US));
        // Already the messaging app (set here or in Settings): there is nothing to request, so hide the Settings button
        // and tell the owner the next Continue reads. Never read on its own: it waits for a Continue tap.
        if (holds) {
            settings.setVisibility(View.GONE);
            if (!reading && result.getText().length() == 0) result.setText(READY);
        }
    }

    /**
     * Continue: if Call Tally is already the messaging app (the owner may have set it in Settings), read straight away.
     * Otherwise remember the current messaging app and ask Android to make Call Tally it for a moment.
     */
    private void become() {
        if (reading) return;
        result.setText("");
        settings.setVisibility(View.GONE);
        if (Roles.holdsSmsRole(this)) {
            // Make sure the hand-back still targets Google Messages when we never recorded a previous app.
            if (!Sync.preferences(this).contains("prev_sms")) {
                Sync.preferences(this).edit().putString("prev_sms", Roles.GOOGLE_MESSAGES).commit();
            }
            readThenHandBack();
            return;
        }
        Sync.preferences(this).edit().putString("prev_sms", Roles.defaultMessaging(this)).commit();
        try {
            startActivityForResult(Roles.requestIntent(this), BECOME);
        } catch (RuntimeException error) {
            result.setText("This phone can't change the messaging app from here.");
        }
    }

    @Override
    protected void onActivityResult(int code, int ignored, Intent data) {
        super.onActivityResult(code, ignored, data);
        if (code == BECOME) {
            if (Roles.holdsSmsRole(this)) {
                readThenHandBack();
            } else {
                // The request was blocked or declined. Offer the Settings route, which works where the request doesn't.
                result.setText(Passthrough.REFUSED + " You can set Call Tally as the messaging app in Settings.");
                settings.setVisibility(View.VISIBLE);
            }
        } else if (code == HAND_BACK || code == SWITCH_BACK) {
            if (!Roles.holdsSmsRole(this)) result.setText(Passthrough.DONE_BACK);
        }
        refresh();
    }

    /** Opens Android's default-apps settings, where the owner can pick Call Tally as the messaging app. */
    private void openDefaultAppsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        } catch (RuntimeException error) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) {
                result.setText("Open Android Settings and set Call Tally as the messaging app.");
            }
        }
    }

    /** One read while Call Tally is the messaging app, nothing else, then the dialog to hand the role back at once. */
    private void readThenHandBack() {
        reading = true;
        enable(go, false);
        result.setText("Reading…");
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int before = entryCount(context);
                Sync.run(context);
                final int added = Math.max(0, entryCount(context) - before);
                Sync.preferences(context).edit().putLong("caught_up_at", System.currentTimeMillis()).commit();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        reading = false;
                        enable(go, true);
                        result.setText(Passthrough.added(added) + "\n" + Sync.status(context));
                        refresh();
                        // Immediately ask the owner to make Google Messages the messaging app again.
                        handBack(HAND_BACK);
                    }
                });
            }
        }).start();
    }

    /** Calls and texts in the record now, to tell how many a catch-up added. */
    private static int entryCount(Context context) {
        try {
            Record.Totals totals = Record.get(context).totals();
            return totals.calls + totals.texts;
        } catch (RuntimeException error) {
            return 0;
        }
    }

    private void handBack(int code) {
        String previous = Sync.preferences(this).getString("prev_sms", Roles.GOOGLE_MESSAGES);
        try {
            startActivityForResult(Roles.handBackIntent(this, previous), code);
        } catch (RuntimeException error) {
            refresh();
        }
    }
}
