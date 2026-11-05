package com.calltally.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the phone's contacts, calls and messages. Never asks for what a message says, so the words can't leave. */
final class Collector {
    private static final Uri SMS = Uri.parse("content://sms");
    private static final Uri MMS = Uri.parse("content://mms");
    private static final Uri THREADS = Uri.parse("content://mms-sms/conversations?simple=true");
    private static final Uri CANONICAL = Uri.parse("content://mms-sms/canonical-addresses");
    private static final int BOX_RECEIVED = 1;
    private static final int BOX_SENT = 2;
    // Delivery and read reports sit in the same table, but they aren't messages. 130 is a picture message still waiting
    // to download: Android swaps it for a new row with a different time once it arrives, so counting both would double it.
    private static final Set<Integer> NOT_MESSAGES = new HashSet<>(Arrays.asList(130, 134, 135, 136));
    private static final int FALLBACK_MESSAGES = 5;

    private final ContentResolver resolver;
    private final Map<String, String> workNames = new HashMap<>();
    private final Map<Long, List<String>> threadPeople = new HashMap<>();
    private final Map<Long, Rules.Who> threadWho = new HashMap<>();
    private boolean conversationList = true;
    private String callsProblem;
    private String textsProblem;
    private int otherCalls;

    Collector(Context context) {
        resolver = context.getContentResolver();
    }

    static final class Problem extends RuntimeException {
        Problem(String message) {
            super(message);
        }
    }

    private Cursor open(Uri uri, String[] columns, String what) {
        Cursor cursor = resolver.query(uri, columns, null, null, null);
        if (cursor == null) throw new Problem("Android didn't return your " + what + ".");
        return cursor;
    }

    private static int need(Cursor cursor, String column, String what) {
        int index = cursor.getColumnIndex(column);
        if (index < 0) throw new Problem("This phone stores " + what + " in a way this app doesn't know.");
        return index;
    }

    /**
     * Without contacts nothing can be matched, so that stops the run. Calls and texts are read apart: when one can't be
     * read the other still goes up, and problem() says which part is missing. Each part is all or nothing.
     */
    List<Rules.Entry> collect() {
        readContacts();
        List<Rules.Entry> entries = new ArrayList<>();
        try {
            List<Rules.Entry> calls = new ArrayList<>();
            otherCalls = readCalls(calls);
            entries.addAll(calls);
        } catch (RuntimeException error) {
            otherCalls = 0;
            callsProblem = reason(error, "calls");
        }
        try {
            List<Rules.Entry> texts = new ArrayList<>();
            readThreads();
            threadPeople.putAll(peopleFromMessages());
            int messages = readSms(texts) + readMms(texts);
            // A phone that suddenly shows no messages is having a problem. Reporting that keeps the page from looking healthy.
            if (messages == 0) throw new Problem("Android returned no messages.");
            entries.addAll(texts);
        } catch (RuntimeException error) {
            threadWho.clear();
            textsProblem = reason(error, "texts");
        }
        if (callsProblem != null && textsProblem != null) throw new Problem(callsProblem + " " + textsProblem);
        return entries;
    }

    /** A refused permission stops the whole run as before. Anything else is a part of the phone this app can't read. */
    private static String reason(RuntimeException error, String what) {
        if (error instanceof SecurityException) throw error;
        if (error instanceof Problem) return error.getMessage();
        return "This phone stores " + what + " in a way this app doesn't know.";
    }

    /** The part that couldn't be read, in one plain sentence for the screen and the page, or empty when all was read. */
    String problem() {
        if (callsProblem != null) return "Calls couldn't be read. " + callsProblem;
        if (textsProblem != null) return "Texts couldn't be read. " + textsProblem;
        return "";
    }

    /** Calls with work contacts that were left out for their kind, such as voicemail or blocked calls. */
    int otherCalls() {
        return otherCalls;
    }

    boolean conversationListWorked() {
        return conversationList;
    }

    int groups() {
        int count = 0;
        for (Rules.Who who : threadWho.values()) {
            if (who != null && who.number.startsWith("thread-")) count++;
        }
        return count;
    }

    /** Every number saved for every contact. A number on both a work and a personal contact counts as work. */
    private void readContacts() {
        String[] columns = {Phone.DISPLAY_NAME, Phone.NUMBER};
        try (Cursor cursor = open(Phone.CONTENT_URI, columns, "contacts")) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                String key = Rules.numberKey(cursor.getString(1));
                if (!key.isEmpty() && Rules.isWork(name)) workNames.put(key, name.trim());
            }
        }
        // With none found, everything would look personal and the page would still show a healthy upload.
        if (workNames.isEmpty()) throw new Problem("No contacts ending in -Work were found.");
    }

    /** Who is in each conversation. Android leaves your own number out of this list. */
    private void readThreads() {
        Map<String, String> addresses = new HashMap<>();
        try {
            try (Cursor cursor = open(CANONICAL, new String[] {"_id", "address"}, "conversations")) {
                while (cursor.moveToNext()) addresses.put(cursor.getString(0), cursor.getString(1));
            }
            try (Cursor cursor = open(THREADS, new String[] {"_id", "recipient_ids"}, "conversations")) {
                while (cursor.moveToNext()) {
                    String ids = cursor.getString(1);
                    if (ids == null || ids.trim().isEmpty()) continue;
                    List<String> people = new ArrayList<>();
                    boolean complete = true;
                    for (String id : ids.trim().split("\\s+")) {
                        String address = addresses.get(id);
                        if (address == null) complete = false;
                        else people.add(address);
                    }
                    if (complete) threadPeople.put(cursor.getLong(0), people);
                }
            }
        } catch (RuntimeException error) {
            // Some phones don't offer this list. Each conversation is then read from its messages instead.
            threadPeople.clear();
        }
        conversationList = !threadPeople.isEmpty();
    }

    private int readCalls(List<Rules.Entry> entries) {
        int other = 0;
        String[] columns = {CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION};
        try (Cursor cursor = open(CallLog.Calls.CONTENT_URI, columns, "call log")) {
            while (cursor.moveToNext()) {
                String number = cursor.getString(0);
                String name = workNames.get(Rules.numberKey(number));
                if (name == null) continue;
                String direction;
                switch (cursor.getInt(2)) {
                    case CallLog.Calls.INCOMING_TYPE: direction = "in"; break;
                    case CallLog.Calls.OUTGOING_TYPE: direction = "out"; break;
                    case CallLog.Calls.MISSED_TYPE: direction = "missed"; break;
                    case CallLog.Calls.REJECTED_TYPE: direction = "rejected"; break;
                    default: other++; continue;
                }
                entries.add(new Rules.Entry("call", cursor.getLong(1) / 1000L, name, number, direction, cursor.getLong(3)));
            }
        }
        return other;
    }

    private int readSms(List<Rules.Entry> entries) {
        int rows = 0;
        try (Cursor cursor = open(SMS, new String[] {"address", "date", "type", "thread_id"}, "texts")) {
            int address = need(cursor, "address", "texts"), date = need(cursor, "date", "texts");
            int type = need(cursor, "type", "texts"), thread = need(cursor, "thread_id", "texts");
            while (cursor.moveToNext()) {
                rows++;
                int box = cursor.getInt(type);
                if (box != BOX_RECEIVED && box != BOX_SENT) continue;
                Rules.Who who = null;
                List<String> people = threadPeople.get(cursor.getLong(thread));
                if (people != null && people.size() > 1) {
                    who = whoInThread(cursor.getLong(thread), people);
                } else {
                    String number = cursor.getString(address);
                    String name = workNames.get(Rules.numberKey(number));
                    if (name != null) who = new Rules.Who(name, number);
                }
                if (who == null) continue;
                entries.add(new Rules.Entry("text", Rules.toSeconds(cursor.getLong(date), false), who.contact, who.number,
                    box == BOX_SENT ? "out" : "in", 0));
            }
        }
        return rows;
    }

    private Rules.Who whoInThread(long thread, List<String> people) {
        if (!threadWho.containsKey(thread)) threadWho.put(thread, Rules.classify(thread, people, workNames));
        return threadWho.get(thread);
    }

    /** Without the conversation list, several messages per conversation give a fuller and steadier list of people. */
    private Map<Long, List<String>> peopleFromMessages() {
        Map<Long, List<Long>> ids = new HashMap<>();
        try (Cursor cursor = resolver.query(MMS, new String[] {"_id", "thread_id"}, null, null, "date DESC")) {
            if (cursor == null) throw new Problem("Android didn't return your messages.");
            while (cursor.moveToNext()) {
                long thread = cursor.getLong(1);
                if (threadPeople.containsKey(thread)) continue;
                List<Long> list = ids.get(thread);
                if (list == null) {
                    list = new ArrayList<>();
                    ids.put(thread, list);
                }
                if (list.size() < FALLBACK_MESSAGES) list.add(cursor.getLong(0));
            }
        }
        Map<Long, List<String>> people = new HashMap<>();
        for (Map.Entry<Long, List<Long>> entry : ids.entrySet()) {
            Set<String> addresses = new java.util.TreeSet<>();
            for (long id : entry.getValue()) addresses.addAll(addressesOn(id));
            people.put(entry.getKey(), new ArrayList<>(addresses));
        }
        return people;
    }

    private int readMms(List<Rules.Entry> entries) {
        int rows = 0;
        try (Cursor cursor = open(MMS, new String[] {"_id", "date", "msg_box", "thread_id", "m_type"}, "messages")) {
            int date = need(cursor, "date", "messages");
            int boxColumn = need(cursor, "msg_box", "messages"), threadColumn = need(cursor, "thread_id", "messages");
            int typeColumn = cursor.getColumnIndex("m_type");
            while (cursor.moveToNext()) {
                rows++;
                int box = cursor.getInt(boxColumn);
                if (box != BOX_RECEIVED && box != BOX_SENT) continue;
                if (typeColumn >= 0 && NOT_MESSAGES.contains(cursor.getInt(typeColumn))) continue;
                long thread = cursor.getLong(threadColumn);
                List<String> people = threadPeople.get(thread);
                Rules.Who who = people == null ? null : whoInThread(thread, people);
                if (who == null) continue;
                entries.add(new Rules.Entry("text", Rules.toSeconds(cursor.getLong(date), true), who.contact, who.number,
                    box == BOX_SENT ? "out" : "in", 0));
            }
        }
        return rows;
    }

    private List<String> addressesOn(long messageId) {
        List<String> found = new ArrayList<>();
        Uri uri = Uri.parse("content://mms/" + messageId + "/addr");
        // One unreadable message is no reason to stop: others in the conversation name the same people.
        try (Cursor cursor = resolver.query(uri, new String[] {"address"}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String address = cursor.getString(0);
                if (address != null && !address.isEmpty() && !address.equals("insert-address-token")) found.add(address);
            }
        } catch (RuntimeException error) {
            return found;
        }
        return found;
    }
}
