package com.calltally.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test builds only: ticks or unticks the people who own the given numbers, so the emulator check can do what Who counts
 * does without the screen. It is in the manifest only when build.sh runs with CALLTALLY_DEBUG=true, and it does nothing
 * unless the build is also debuggable.
 *   am broadcast -a com.calltally.app.TEST_TICK -n com.calltally.app/.TestHook --esa numbers 5551234567 [--ez untick true]
 * It also sets the pay period start, as the first start or Options would, for the checks that need one:
 *   am broadcast -a com.calltally.app.TEST_TICK -n com.calltally.app/.TestHook --es pay_start 2026-10-25
 * And it times reads of picture messages' address rows, as a run makes them, to size what a run costs:
 *   am broadcast -a com.calltally.app.TEST_TICK -n com.calltally.app/.TestHook --ei time_addr 200
 */
public class TestHook extends BroadcastReceiver {
    static final String ACTION = "com.calltally.app.TEST_TICK";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        boolean debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!Config.TEST_HOOK || !debuggable || !ACTION.equals(intent.getAction())) return;
        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int timeAddr = intent.getIntExtra("time_addr", 0);
                    if (timeAddr > 0) {
                        result.setResult(1, timeAddresses(context, timeAddr), null);
                        return;
                    }
                    String payStart = intent.getStringExtra("pay_start");
                    if (payStart != null) {
                        if (PayPeriod.of(payStart, "14") == null) throw new IllegalArgumentException("pay_start");
                        Record record = Record.get(context);
                        record.setSetting("pay_period_start", payStart);
                        record.setSetting("pay_period_days", "14");
                        result.setResult(1, "pay period set", null);
                        return;
                    }
                    String[] given = intent.getStringArrayExtra("numbers");
                    boolean untick = intent.getBooleanExtra("untick", false);
                    Set<String> keys = new HashSet<>();
                    if (given != null) for (String number : given) if (!Rules.numberKey(number).isEmpty()) keys.add(Rules.numberKey(number));
                    Record record = Record.get(context);
                    Set<String> changed = new HashSet<>();
                    if (!untick) {
                        List<Rules.Person> tick = new ArrayList<>();
                        for (Rules.Person contact : Collector.readContacts(context.getContentResolver())) {
                            if (owns(contact, keys)) tick.add(contact);
                        }
                        record.tick(tick);
                        for (Rules.Person contact : tick) changed.add(contact.key);
                    }
                    // People no longer on the phone are ticked again from the table; unticking always works from the table.
                    for (Rules.Person person : record.people()) {
                        if (owns(person, keys) && !changed.contains(person.key)) {
                            record.setTicked(person.key, !untick);
                            changed.add(person.key);
                        }
                    }
                    result.setResult(1, (untick ? "unticked " : "ticked ") + changed.size(), null);
                } catch (Throwable error) {
                    result.setResult(0, "failed " + error.getClass().getSimpleName(), null);
                } finally {
                    result.finish();
                }
            }
        }).start();
    }

    /** Reads the address rows of the newest picture messages, up to times reads, cycling through them: "200 reads in 310 ms". */
    private static String timeAddresses(Context context, int times) {
        android.content.ContentResolver resolver = context.getContentResolver();
        List<Long> ids = new ArrayList<>();
        try (android.database.Cursor cursor = resolver.query(android.net.Uri.parse("content://mms"), new String[] {"_id"}, null, null, "date DESC")) {
            while (cursor != null && cursor.moveToNext()) ids.add(cursor.getLong(0));
        }
        if (ids.isEmpty()) return "no picture messages";
        long started = System.nanoTime();
        int rows = 0;
        for (int i = 0; i < times; i++) {
            android.net.Uri uri = android.net.Uri.parse("content://mms/" + ids.get(i % ids.size()) + "/addr");
            try (android.database.Cursor cursor = resolver.query(uri, new String[] {"address"}, null, null, null)) {
                while (cursor != null && cursor.moveToNext()) if (Rules.usableAddress(cursor.getString(0))) rows++;
            }
        }
        long millis = (System.nanoTime() - started) / 1000000L;
        return times + " reads in " + millis + " ms, " + rows + " addresses";
    }

    private static boolean owns(Rules.Person person, Set<String> keys) {
        for (String number : person.numbers) if (keys.contains(Rules.numberKey(number))) return true;
        return false;
    }
}
