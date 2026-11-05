package com.calltally.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The plain pieces of Catch up encrypted, kept away from Android so they can be tested on their own: the values written
 * for a text that arrives while Call Tally briefly holds the messaging role, and the words the screens show about it.
 *
 * Call Tally never sends a message, and never reads what a message says to count it. The one time it touches a text's
 * words is here. A text that arrives while Call Tally is the phone's messaging app for a moment is delivered only to that
 * app and is not stored by Android, so Call Tally writes it straight into the phone's inbox, unread, so Google Messages
 * shows it once the role goes back. The words are passed through, not read.
 */
final class Passthrough {
    private Passthrough() {}

    // The phone's SMS inbox columns, as android.provider.Telephony.Sms names them. Plain strings, so this piece builds
    // and is tested without Android.
    static final String ADDRESS = "address";
    static final String BODY = "body";
    static final String DATE = "date";
    static final String DATE_SENT = "date_sent";
    static final String READ = "read";
    static final String SEEN = "seen";
    static final String THREAD_ID = "thread_id";
    static final String SUBSCRIPTION_ID = "sub_id";

    static final String EXPLANATION =
        "Encrypted chats can only be read by the phone's messaging app. Call Tally will ask to be that app for a moment, "
        + "read them, then ask you to switch back to your messaging app. Texts that arrive meanwhile are kept.";
    /** The persistent line shown in Options and the month screen while Call Tally still holds the messaging role. */
    static final String HOLDS_ROLE =
        "Call Tally is still the phone's messaging app. Open Options to switch back to your messaging app.";
    static final String DONE_BACK = "You're back to your messaging app.";
    static final String REFUSED = "The messaging app wasn't changed, so nothing was read.";
    static final String PICTURE_MISSING =
        "A picture message arrived while catching up and may be missing from Messages.";

    /**
     * The row for one arriving text, to write into the phone's inbox unread. read and seen are 0 so Google Messages shows
     * it as new after the role goes back. A subId below zero is left out. The words are passed through, never read.
     */
    static Map<String, Object> inboxRow(String address, String body, long date, long dateSent, long threadId, int subId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(ADDRESS, address);
        row.put(BODY, body);
        row.put(DATE, date);
        row.put(DATE_SENT, dateSent);
        row.put(READ, 0);
        row.put(SEEN, 0);
        row.put(THREAD_ID, threadId);
        if (subId >= 0) row.put(SUBSCRIPTION_ID, subId);
        return row;
    }

    /**
     * The plain result of one catch-up: how many calls and texts the record now holds in all, and how many of them were
     * new since the last run. Worded so that zero new is not read as a failure. No parentheses.
     */
    static String readResult(int total, int newSinceLastRun) {
        return "Read while Call Tally was the messaging app. " + total + " calls and texts counted, "
            + newSinceLastRun + " new since the last run.";
    }

    /** What the catch-up says it added, in plain words. */
    static String added(int added) {
        if (added <= 0) return "No new calls or texts.";
        if (added == 1) return "1 new call or text added.";
        return added + " new calls and texts added.";
    }

    /**
     * Where "Switch back" sends the owner after a catch-up: the messaging app that was the default before Call Tally took
     * the role, so they land back in their own app. prev is the remembered package, or null when none was recorded;
     * launchable says whether that package is installed and still has a screen to open; self is Call Tally's own package,
     * which is never somewhere to send them. Returns the package to open, or null to tell the caller to fall back to the
     * old way of opening Google Messages or a Settings screen.
     */
    static String handBackTarget(String prev, boolean launchable, String self) {
        if (prev == null || prev.isEmpty()) return null;
        if (prev.equals(self)) return null;
        if (!launchable) return null;
        return prev;
    }

    /** "Last caught up: Oct 26, 12:33 AM", or "Not yet" when it has not happened. at is in milliseconds, or 0. */
    static String lastCaughtUp(long at, Locale locale) {
        if (at <= 0) return "Not yet";
        return "Last caught up: " + new SimpleDateFormat("MMM d, h:mm a", locale).format(new Date(at));
    }
}
