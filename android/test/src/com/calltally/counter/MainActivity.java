package com.calltally.counter;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Shows how many messages Android's shared database holds, by month. It never reads what a message says, and never
 * shows a phone number. It has no internet permission, and the report only leaves the phone if you copy it yourself.
 */
public class MainActivity extends Activity {
    private static final int ASK = 1;
    private static final int RECENT = 12;
    private static final int MMS_ADDRESS_CAP = 3000;

    private TextView report;
    private Button copy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFitsSystemWindows(true);
        int gap = (int) (12 * getResources().getDisplayMetrics().density);
        page.setPadding(gap, gap, gap, gap);
        copy = new Button(this);
        copy.setText("Copy results");
        copy.setEnabled(false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ClipboardManager clips = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clips.setPrimaryClip(ClipData.newPlainText("Call Tally test", report.getText()));
                copy.setText("Copied");
            }
        });
        report = new TextView(this);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextSize(12);
        report.setTextIsSelectable(true);
        report.setText("Counting…");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(report);
        page.addView(copy);
        page.addView(scroll);
        setContentView(page);
        if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            count();
        } else {
            requestPermissions(new String[] {Manifest.permission.READ_SMS}, ASK);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] names, int[] results) {
        if (code != ASK) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            count();
        } else {
            report.setText("This test needs permission to read SMS.\n\nIf Android won't offer it, open App info for"
                + " Call Tally Test, tap the three-dot menu, choose Allow restricted settings, then allow SMS under"
                + " Permissions and open the app again.");
        }
    }

    private void count() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    text = build();
                } catch (RuntimeException error) {
                    text = "The test stopped: " + error.getClass().getSimpleName();
                }
                final String done = text;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        report.setText(done);
                        copy.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    /** One message's details: when, which table, and sent or received. */
    private static final class Row {
        final long millis;
        final String table;
        final boolean sent;
        final int people;
        Row(long millis, String table, boolean sent, int people) {
            this.millis = millis;
            this.table = table;
            this.sent = sent;
            this.people = people;
        }
    }

    private String build() {
        StringBuilder out = new StringBuilder("Call Tally test 1\n\n");
        List<Row> rows = new ArrayList<>();
        Map<String, Integer> creators = new TreeMap<>();
        Set<String> smsColumns = columns(Uri.parse("content://sms"));
        Set<String> mmsColumns = columns(Uri.parse("content://mms"));
        int[] smsOther = {0};
        int[] mmsOther = {0};
        try {
            readSms(rows, creators, smsColumns, smsOther);
        } catch (RuntimeException error) {
            out.append("sms could not be fully read: ").append(error.getClass().getSimpleName()).append("\n\n");
        }
        int[] people = new int[4];
        boolean capped = false;
        try {
            capped = readMms(rows, creators, mmsColumns, mmsOther, people);
        } catch (RuntimeException error) {
            out.append("mms could not be fully read: ").append(error.getClass().getSimpleName()).append("\n\n");
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return Long.compare(b.millis, a.millis);
            }
        });

        Map<String, int[]> months = new TreeMap<>(Collections.reverseOrder());
        SimpleDateFormat month = new SimpleDateFormat("yyyy-MM", Locale.US);
        for (Row row : rows) {
            String key = month.format(new Date(row.millis));
            int[] cells = months.get(key);
            if (cells == null) {
                cells = new int[5];
                months.put(key, cells);
            }
            int column = (row.table.equals("sms") ? 0 : 2) + (row.sent ? 1 : 0);
            cells[column]++;
            if (row.people >= 3) cells[4]++;
        }
        out.append("month    sms-in sms-out mms-in mms-out 3+people\n");
        int[] totals = new int[5];
        for (Map.Entry<String, int[]> entry : months.entrySet()) {
            int[] c = entry.getValue();
            out.append(String.format(Locale.US, "%s %6d %7d %6d %7d %8d%n", entry.getKey(), c[0], c[1], c[2], c[3], c[4]));
            for (int i = 0; i < totals.length; i++) totals[i] += c[i];
        }
        out.append(String.format(Locale.US, "total   %6d %7d %6d %7d %8d%n", totals[0], totals[1], totals[2], totals[3], totals[4]));
        out.append("not sent or received (drafts and so on): sms ").append(smsOther[0]).append(", mms ").append(mmsOther[0]).append('\n');
        out.append("mms people on a message: unknown ").append(people[0]).append(", 1 or 2: ").append(people[1])
            .append(", 3 or more: ").append(people[2]).append(capped ? " (newest " + MMS_ADDRESS_CAP + " only)" : "").append("\n\n");

        out.append("newest ").append(RECENT).append(" messages\n");
        SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (int i = 0; i < Math.min(RECENT, rows.size()); i++) {
            Row row = rows.get(i);
            out.append(when.format(new Date(row.millis))).append(' ').append(row.table).append(row.sent ? " sent" : " received");
            if (row.people > 0) out.append(" people=").append(row.people);
            out.append('\n');
        }
        out.append("\nsaved by\n");
        for (Map.Entry<String, Integer> entry : creators.entrySet()) {
            out.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        out.append("\nsms columns: ").append(new java.util.TreeSet<>(smsColumns)).append('\n');
        out.append("\nmms columns: ").append(new java.util.TreeSet<>(mmsColumns)).append('\n');
        return out.toString();
    }

    private Set<String> columns(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null) return Collections.emptySet();
            return new HashSet<>(Arrays.asList(cursor.getColumnNames()));
        }
    }

    private static String[] wanted(Set<String> available, String... names) {
        List<String> use = new ArrayList<>();
        for (String name : names) if (available.contains(name)) use.add(name);
        return use.toArray(new String[0]);
    }

    private static void addCreator(Map<String, Integer> creators, String table, Cursor cursor, int column) {
        String name = column >= 0 ? cursor.getString(column) : null;
        // Only an app's package name is kept, never anything else that might sit in this column.
        String key = table + " " + (name != null && name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") ? name : "(none)");
        Integer seen = creators.get(key);
        creators.put(key, seen == null ? 1 : seen + 1);
    }

    private void readSms(List<Row> rows, Map<String, Integer> creators, Set<String> available, int[] other) {
        String[] use = wanted(available, "date", "type", "creator");
        try (Cursor cursor = getContentResolver().query(Uri.parse("content://sms"), use, null, null, null)) {
            if (cursor == null) return;
            int date = cursor.getColumnIndex("date"), type = cursor.getColumnIndex("type"), creator = cursor.getColumnIndex("creator");
            if (date < 0 || type < 0) throw new IllegalStateException("missing columns");
            while (cursor.moveToNext()) {
                int kind = cursor.getInt(type);
                if (kind != 1 && kind != 2) {
                    other[0]++;
                    continue;
                }
                rows.add(new Row(cursor.getLong(date), "sms", kind == 2, 0));
                addCreator(creators, "sms", cursor, creator);
            }
        }
    }

    /** Returns true if it stopped counting people early. */
    private boolean readMms(List<Row> rows, Map<String, Integer> creators, Set<String> available, int[] other, int[] people) {
        String[] use = wanted(available, "_id", "date", "msg_box", "creator");
        boolean capped = false;
        int checked = 0;
        try (Cursor cursor = getContentResolver().query(Uri.parse("content://mms"), use, null, null, "date DESC")) {
            if (cursor == null) return false;
            int id = cursor.getColumnIndex("_id"), date = cursor.getColumnIndex("date"), box = cursor.getColumnIndex("msg_box"), creator = cursor.getColumnIndex("creator");
            if (id < 0 || date < 0 || box < 0) throw new IllegalStateException("missing columns");
            while (cursor.moveToNext()) {
                int kind = cursor.getInt(box);
                if (kind != 1 && kind != 2) {
                    other[0]++;
                    continue;
                }
                int count = 0;
                if (checked < MMS_ADDRESS_CAP) {
                    checked++;
                    try {
                        count = peopleOn(cursor.getLong(id), kind == 2);
                    } catch (RuntimeException error) {
                        count = 0;  // shows under "unknown"
                    }
                    people[count == 0 ? 0 : count <= 2 ? 1 : 2]++;
                } else {
                    capped = true;
                }
                // MMS dates are normally seconds; a phone that stores milliseconds is read as it is.
                long stamp = cursor.getLong(date);
                rows.add(new Row(stamp > 100000000000L ? stamp : stamp * 1000L, "mms", kind == 2, count));
                addCreator(creators, "mms", cursor, creator);
            }
        }
        return capped;
    }

    /**
     * How many different people are on a message, counting you. The addresses are compared here and go no further.
     * A sent message lists you as a placeholder rather than your number, so that placeholder counts as one person.
     */
    private int peopleOn(long messageId, boolean sent) {
        Set<String> seen = new HashSet<>();
        boolean you = false;
        Uri uri = Uri.parse("content://mms/" + messageId + "/addr");
        try (Cursor cursor = getContentResolver().query(uri, new String[] {"address"}, null, null, null)) {
            if (cursor == null) return 0;
            while (cursor.moveToNext()) {
                String address = cursor.getString(0);
                if ("insert-address-token".equals(address)) {
                    you = true;
                    continue;
                }
                if (address == null || address.isEmpty()) continue;
                String digits = address.replaceAll("\\D", "");
                seen.add(digits.length() > 10 ? digits.substring(digits.length() - 10) : address);
            }
        }
        return seen.size() + (sent && you ? 1 : 0);
    }
}
