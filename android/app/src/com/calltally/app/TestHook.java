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

    private static boolean owns(Rules.Person person, Set<String> keys) {
        for (String number : person.numbers) if (keys.contains(Rules.numberKey(number))) return true;
        return false;
    }
}
