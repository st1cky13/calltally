package com.calltally.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The first-start page, shown once before anything else: what the app does, what it reads, that nothing leaves the
 * phone, and the three permissions. Continue asks for them; then Who counts, then the pay period, then the month. It
 * shows again only when the app's data is cleared, or when Options asks for it.
 */
public class StartActivity extends Screen {
    private static final int ASK = 1;
    private static final String PREFS = "start";

    private TextView refused;
    private Button again;
    private Button appInfo;
    private boolean asked;

    /** True once the first start has been gone through. */
    static boolean done(Context context) {
        return prefs(context).getBoolean("done", false);
    }

    /** Options' "Show the first-start page again". Nothing else changes. */
    static void showAgain(Context context) {
        prefs(context).edit().putBoolean("done", false).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Once per install, before anything else is read or written: drops what 0.6.0 and earlier left behind, the list of
     * what was uploaded and the uploading app's last run, so nothing from the old app is shown or carried over.
     */
    static synchronized void forgetOldApp(Context context) {
        SharedPreferences start = prefs(context);
        if (start.getBoolean("fresh", false)) return;
        new File(context.getFilesDir(), "sent.txt").delete();
        Sync.preferences(context).edit().clear().commit();
        start.edit().putBoolean("fresh", true).commit();
    }

    /** The last step: the first start is done and the month screen opens on its own. */
    static void finishFirstStart(android.app.Activity from) {
        prefs(from).edit().putBoolean("done", true).commit();
        Intent month = new Intent(from, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        from.startActivity(month);
        from.finish();
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        forgetOldApp(this);
        asked = saved != null && saved.getBoolean("asked", false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView welcome = text("Welcome to Call Tally", 20, txt, true);
        welcome.setId(R.id.start_title);
        column.addView(welcome, wide());
        String[] lines = {
            "Call Tally shows your time on calls and your texts with the people you tick, by month and by pay period.",
            "It reads your call log, your texts and your contacts, on this phone only. It never reads what a text says.",
            "Nothing leaves the phone. There is no account and nothing is sent anywhere.",
            "The record is kept on this phone only, with no copy elsewhere. Removing the app or clearing its data erases it.",
            "It needs three permissions: your call log, your texts and your contacts. Tap Continue and Android will ask for each.",
        };
        for (int i = 0; i < lines.length; i++) {
            TextView line = text(lines[i], 15, i == lines.length - 1 ? txt : muted, false);
            line.setLineSpacing(0, 1.15f);
            if (i == 0) line.setId(R.id.start_text);
            LinearLayout.LayoutParams gap = wide();
            gap.topMargin = dp(i == 0 ? 16 : 12);
            column.addView(line, gap);
        }
        refused = problemBox();
        refused.setId(R.id.start_refused);
        LinearLayout.LayoutParams refusedGap = wide();
        refusedGap.topMargin = dp(18);
        column.addView(refused, refusedGap);
        appInfo = button("Open App info");
        appInfo.setId(R.id.start_app_info);
        appInfo.setVisibility(View.GONE);
        appInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException error) {
                    // A phone without that screen: the words above still say where to go.
                }
            }
        });
        LinearLayout.LayoutParams infoGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoGap.topMargin = dp(10);
        column.addView(appInfo, infoGap);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);

        again = button("Continue");
        again.setId(R.id.start_continue);
        filled(again);
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                proceed();
            }
        });
        footer = footer(again);
        frame("Call Tally", scroll, false);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("asked", asked);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        showRefused();
    }

    /** Asks for whatever is still missing, or goes on to Who counts when everything is allowed. */
    private void proceed() {
        if (Sync.allowed(this)) {
            startActivity(new Intent(this, WhoActivity.class).putExtra("first", true));
            return;
        }
        asked = true;
        requestPermissions(Sync.PERMISSIONS, ASK);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] names, int[] results) {
        if (code != ASK) return;
        if (Sync.allowed(this)) {
            Sync.schedule(this);
            showRefused();
            startActivity(new Intent(this, WhoActivity.class).putExtra("first", true));
        } else {
            showRefused();
        }
    }

    /** Says which permissions are still missing once Android has been asked, with a way to try again. */
    private void showRefused() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) missing.add("your call log");
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) missing.add("your texts");
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) missing.add("your contacts");
        boolean show = asked && !missing.isEmpty();
        refused.setVisibility(show ? View.VISIBLE : View.GONE);
        appInfo.setVisibility(show ? View.VISIBLE : View.GONE);
        again.setText(show ? "Try again" : "Continue");
        if (!show) return;
        refused.setText("Call Tally still needs " + list(missing) + ". Tap Try again to allow "
            + (missing.size() == 1 ? "it" : "them") + ".\n\nIf Android doesn't ask, or says the setting is restricted, open App info"
            + " for Call Tally, tap the three-dot menu and choose Allow restricted settings. Then allow "
            + (missing.size() == 1 ? "it" : "them") + " under Permissions and come back.");
    }

    private static String list(List<String> items) {
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + " and " + items.get(1);
        return items.get(0) + ", " + items.get(1) + " and " + items.get(2);
    }
}
