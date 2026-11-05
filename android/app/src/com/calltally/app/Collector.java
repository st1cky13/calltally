package com.calltally.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the phone's contacts, calls and messages into plain values and hands each row to Rules, which decides what it
 * becomes. Never asks for what a message says, so the words are never read.
 */
final class Collector {
    private static final Uri SMS = Uri.parse("content://sms");
    private static final Uri MMS = Uri.parse("content://mms");
    private static final Uri THREADS = Uri.parse("content://mms-sms/conversations?simple=true");
    private static final Uri CANONICAL = Uri.parse("content://mms-sms/canonical-addresses");

    private final ContentResolver resolver;
    private final Map<String, String> workNames;
    private final Map<Long, List<String>> threadPeople = new HashMap<>();
    private Rules.Texts texts;
    private boolean conversationList = true;
    private String callsProblem;
    private String textsProblem;
    private int otherCalls;

    /** workNames: the numbers that count, by their last ten digits, with the name each counts under. */
    Collector(Context context, Map<String, String> workNames) {
        resolver = context.getContentResolver();
        this.workNames = workNames;
    }

    static final class Problem extends RuntimeException {
        Problem(String message) {
            super(message);
        }
    }

    private Cursor open(Uri uri, String[] columns, String what) {
        return open(resolver, uri, columns, what);
    }

    private static Cursor open(ContentResolver resolver, Uri uri, String[] columns, String what) {
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
     * Calls and texts are read apart: when one can't be read the other is still kept, and problem() says which part is
     * missing. Each part is all or nothing.
     */
    List<Rules.Entry> collect() {
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
            List<Rules.Entry> found = new ArrayList<>();
            readThreads();
            threadPeople.putAll(peopleFromMessages());
            texts = new Rules.Texts(workNames, threadPeople);
            int messages = readSms(found) + readMms(found);
            // A phone that suddenly shows no messages is having a problem. Saying so keeps the screen from looking healthy.
            if (messages == 0) throw new Problem("Android returned no messages.");
            entries.addAll(found);
        } catch (RuntimeException error) {
            texts = null;
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

    /** The part that couldn't be read, in one plain sentence for the screen, or empty when all was read. */
    String problem() {
        if (callsProblem != null) return "Calls couldn't be read. " + callsProblem;
        if (textsProblem != null) return "Texts couldn't be read. " + textsProblem;
        return "";
    }

    /** Calls with ticked people that were left out for their kind, such as voicemail or blocked calls. */
    int otherCalls() {
        return otherCalls;
    }

    boolean conversationListWorked() {
        return conversationList;
    }

    int groups() {
        return texts == null ? 0 : texts.groups();
    }

    /**
     * Every contact on the phone with its saved numbers, in the order Android gives them, one Person per contact. A
     * contact is known by its lookup key, which stays the same while the contact exists. Throws Problem when Android
     * returns nothing at all, and SecurityException without permission.
     */
    static List<Rules.Person> readContacts(ContentResolver resolver) {
        String[] columns = {Phone.LOOKUP_KEY, Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER};
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<String>> numbers = new HashMap<>();
        try (Cursor cursor = open(resolver, Phone.CONTENT_URI, columns, "contacts")) {
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                if (key == null || key.isEmpty()) key = "contact-" + cursor.getLong(1);
                String number = cursor.getString(3);
                if (!names.containsKey(key)) {
                    names.put(key, cursor.getString(2));
                    numbers.put(key, new ArrayList<String>());
                }
                if (number != null && !number.trim().isEmpty()) numbers.get(key).add(number);
            }
        }
        List<Rules.Person> out = new ArrayList<>();
        for (Map.Entry<String, String> contact : names.entrySet()) {
            List<String> list = Rules.joinNumbers(new ArrayList<String>(), numbers.get(contact.getKey()));
            if (!list.isEmpty()) out.add(new Rules.Person(contact.getKey(), contact.getValue(), list, false, true));
        }
        return out;
    }

    /** Who is in each conversation, from Android's conversation list. */
    private void readThreads() {
        Map<String, String> addresses = new HashMap<>();
        Map<Long, String> recipientIds = new HashMap<>();
        try {
            try (Cursor cursor = open(CANONICAL, new String[] {"_id", "address"}, "conversations")) {
                while (cursor.moveToNext()) addresses.put(cursor.getString(0), cursor.getString(1));
            }
            try (Cursor cursor = open(THREADS, new String[] {"_id", "recipient_ids"}, "conversations")) {
                while (cursor.moveToNext()) recipientIds.put(cursor.getLong(0), cursor.getString(1));
            }
            threadPeople.putAll(Rules.threadPeople(addresses, recipientIds));
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
                Rules.Decision decision = Rules.call(workNames, cursor.getString(0), cursor.getLong(1), cursor.getInt(2), cursor.getLong(3));
                if (decision.entry != null) entries.add(decision.entry);
                else if (Rules.SKIP_KIND.equals(decision.skipped)) other++;
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
                Rules.Decision decision = texts.sms(cursor.getLong(thread), cursor.getString(address), cursor.getLong(date), cursor.getInt(type));
                if (decision.entry != null) entries.add(decision.entry);
            }
        }
        return rows;
    }

    /** Without the conversation list, several messages per conversation give a fuller and steadier list of people. */
    private Map<Long, List<String>> peopleFromMessages() {
        List<long[]> messages = new ArrayList<>();
        try (Cursor cursor = resolver.query(MMS, new String[] {"_id", "thread_id"}, null, null, "date DESC")) {
            if (cursor == null) throw new Problem("Android didn't return your messages.");
            while (cursor.moveToNext()) messages.add(new long[] {cursor.getLong(0), cursor.getLong(1)});
        }
        Map<Long, List<String>> people = new HashMap<>();
        for (Map.Entry<Long, List<Long>> entry : Rules.fallbackMessages(messages, threadPeople.keySet()).entrySet()) {
            List<List<String>> addresses = new ArrayList<>();
            for (long id : entry.getValue()) addresses.add(addressesOn(id));
            people.put(entry.getKey(), Rules.peopleOnMessages(addresses));
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
                int messageType = typeColumn >= 0 ? cursor.getInt(typeColumn) : -1;
                Rules.Decision decision = texts.mms(cursor.getLong(threadColumn), cursor.getLong(date), cursor.getInt(boxColumn), messageType);
                if (decision.entry != null) entries.add(decision.entry);
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
                if (Rules.usableAddress(address)) found.add(address);
            }
        } catch (RuntimeException error) {
            return found;
        }
        return found;
    }
}
