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
 * to their own messaging app (another dialog they approve). When Call Tally is already the messaging app, because the owner set
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
    private TextView report;
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

        // What the phone reports about the messaging app, plainly, so the owner can screenshot it when the detection and
        // Settings disagree. Small and muted, below "Last caught up"; set fresh whenever the screen is shown.
        report = text("", 13, muted, false);
        report.setId(R.id.catch_report);
        report.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams reportGap = wide();
        reportGap.topMargin = dp(6);
        column.addView(report, reportGap);

        holding = problemBox();
        holding.setId(R.id.catch_holding);
        holding.setText(Passthrough.HOLDS_ROLE);
        LinearLayout.LayoutParams holdGap = wide();
        holdGap.topMargin = dp(16);
        column.addView(holding, holdGap);

        switchBack = button("Switch back");
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
        if (isFinishing()) return;
        // The captured previous app only matters while Call Tally holds the role. If it no longer holds it (handed back
        // here, or switched back by hand in Settings), forget the fresh flag so a later Switch back can't reuse a stale
        // app. While the role is still held, leave it: the normal flow must survive a recreate or process kill before the
        // hand-back and still return to the previous app.
        if (!Roles.holdsSmsRole(this)) {
            Sync.preferences(this).edit().putBoolean("prev_sms_fresh", false).commit();
        }
        refresh();
    }

    /** Shows the last catch-up time, and whether Call Tally still holds the role, with the switch-back button. */
    private void refresh() {
        boolean holds = Roles.holdsSmsRole(this);
        holding.setVisibility(holds ? View.VISIBLE : View.GONE);
        switchBack.setVisibility(holds ? View.VISIBLE : View.GONE);
        last.setText(Passthrough.lastCaughtUp(Sync.preferences(this).getLong("caught_up_at", 0), Locale.US));
        report.setText("Messaging app: " + Roles.report(this));
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
            // Call Tally already holds the role (the owner set it in Settings), so the real previous app can't be known
            // now. Clear any stored prev_sms and its fresh flag so the hand-back can't open a stale app and falls back to
            // the Google Messages / default-apps settings route. The worst case is today's behaviour, never a wrong app.
            Sync.preferences(this).edit().putString("prev_sms", null).putBoolean("prev_sms_fresh", false).commit();
            readThenHandBack();
            return;
        }
        // The in-app flow: capture the real previous app now and mark it fresh, so only this takeover's value is trusted.
        Sync.preferences(this).edit()
            .putString("prev_sms", Roles.previousMessaging(this))
            .putBoolean("prev_sms_fresh", true)
            .commit();
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
                // The request was blocked or declined, so Call Tally never took the role. Drop the app we captured and its
                // fresh flag, so a decline leaves nothing fresh for a later Switch back to open. Then offer the Settings
                // route, which works where the request doesn't, and show what the phone reports for diagnosis.
                Sync.preferences(this).edit().putString("prev_sms", null).putBoolean("prev_sms_fresh", false).commit();
                result.setText(Passthrough.REFUSED + " You can set Call Tally as the messaging app in Settings."
                    + "\nMessaging app: " + Roles.report(this));
                settings.setVisibility(View.VISIBLE);
            }
        } else if (code == HAND_BACK || code == SWITCH_BACK) {
            if (!Roles.holdsSmsRole(this)) {
                // The role is handed back, so the captured previous app has been used up: drop the fresh flag so a later
                // Switch back can't reuse a stale value.
                Sync.preferences(this).edit().putBoolean("prev_sms_fresh", false).commit();
                result.setText(Passthrough.DONE_BACK);
            }
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
                final int total = entryCount(context);
                final int added = Math.max(0, total - before);
                Sync.preferences(context).edit().putLong("caught_up_at", System.currentTimeMillis()).commit();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        reading = false;
                        enable(go, true);
                        result.setText(Passthrough.readResult(total, added) + "\n" + Sync.status(context));
                        refresh();
                        // Immediately ask the owner to switch back to their own messaging app.
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

    /**
     * Hands control back to the owner to make their own messaging app the default again: opens the app that was the
     * default before Call Tally took the role, which then offers to become the default again; or, when that app is not
     * known, opens Google Messages or a Settings screen where the owner picks it. It never changes the default itself. On
     * return, onResume/refresh re-reads the role and hides the "still the messaging app" box.
     */
    private void handBack(int code) {
        boolean fresh = Sync.preferences(this).getBoolean("prev_sms_fresh", false);
        String prev = Passthrough.prevIfFresh(Sync.preferences(this).getString("prev_sms", null), fresh);
        Intent intent = Roles.handBackIntent(this, prev);
        if (intent == null) {
            refresh();
            return;
        }
        try {
            startActivityForResult(intent, code);
        } catch (RuntimeException error) {
            refresh();
        }
    }
}
