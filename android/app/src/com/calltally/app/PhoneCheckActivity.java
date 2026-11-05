package com.calltally.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Options > Phone check: what the phone hands the app, read only. The chats and the newest rows of the text and the
 * picture and group tables, with how the app would count each. Never asks for a message's words, and changes nothing.
 */
public class PhoneCheckActivity extends Screen {
    private static final Uri SMS = Uri.parse("content://sms");
    private static final Uri MMS = Uri.parse("content://mms");
    private static final Uri THREADS = Uri.parse("content://mms-sms/conversations?simple=true");
    private static final Uri CANONICAL = Uri.parse("content://mms-sms/canonical-addresses");
    private static final String MESSAGES_APP = "com.google.android.apps.messaging";
    private static final int CHATS = 40;
    private static final int NEWEST = 80;

    private LinearLayout rows;
    private Button share;
    private List<PhoneCheck.Line> shown;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(card);
        TextView note = text(PhoneCheck.NOTE, 13, muted, false);
        note.setPadding(dp(16), dp(10), dp(16), dp(10));
        list.addView(note, wide());
        list.addView(divider());
        rows = new LinearLayout(this);
        rows.setId(R.id.rows);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.addView(row(new PhoneCheck.Line(PhoneCheck.Line.NOTE, "Reading the phone…")));
        list.addView(rows, wide());
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(card);
        scroll.setFillViewport(true);
        scroll.addView(list);

        share = button("Share");
        share.setId(R.id.phone_share);
        enable(share, false);
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (shown == null) return;
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "Call Tally phone check");
                send.putExtra(Intent.EXTRA_TEXT, PhoneCheck.shareText(shown));
                try {
                    startActivity(Intent.createChooser(send, "Share"));
                } catch (RuntimeException error) {
                    // Nothing on the phone takes text: the screen stays as it is.
                }
            }
        });
        Button close = button("Close");
        close.setId(R.id.close);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        footer = footer(share, close);
        frame("Phone check", scroll, false);
        load();
    }

    /** Reads the phone away from the screen, then shows every line. */
    private void load() {
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<PhoneCheck.Line> got;
                try {
                    got = gather(context);
                } catch (SecurityException error) {
                    got = Collections.singletonList(new PhoneCheck.Line(PhoneCheck.Line.NOTE, Sync.NOT_ALLOWED));
                } catch (RuntimeException error) {
                    got = Collections.singletonList(new PhoneCheck.Line(PhoneCheck.Line.NOTE,
                        "Something went wrong: " + error.getClass().getSimpleName()));
                }
                final List<PhoneCheck.Line> lines = got;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        shown = lines;
                        rows.removeAllViews();
                        for (int i = 0; i < lines.size(); i++) {
                            if (i > 0 && lines.get(i).kind != PhoneCheck.Line.HEADING) rows.addView(divider());
                            rows.addView(row(lines.get(i)));
                        }
                        enable(share, true);
                    }
                });
            }
        }).start();
    }

    private View row(PhoneCheck.Line line) {
        TextView view;
        if (line.kind == PhoneCheck.Line.HEADING) {
            view = text(line.text, 14, muted, true);
            view.setPadding(dp(16), dp(18), dp(16), dp(6));
        } else {
            view = text(line.text, 13, line.kind == PhoneCheck.Line.NOTE ? muted : txt, false);
            view.setPadding(dp(16), dp(8), dp(16), dp(8));
            view.setId(R.id.phone_row);
        }
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    // Reading the phone

    /** One row of either message table, with what the screen shows of it. */
    private static final class Message {
        boolean text;
        long id;
        long millis;
        long stored;
        int box;
        int messageType = -1;
        long thread;
        String address;
        String creator;
        String extra = "";
    }

    /** What one message table gave: its row count, rows per chat, each chat's newest time and the newest rows. */
    private static final class Table {
        int count;
        final Map<Long, Integer> perChat = new HashMap<>();
        final Map<Long, Long> newest = new HashMap<>();
        final List<Message> rows = new ArrayList<>();
        // Picture and group rows only: message id and chat id, newest first, for the app's fallback list of people.
        final List<long[]> ids = new ArrayList<>();

        void add(Message message) {
            count++;
            Integer had = perChat.get(message.thread);
            perChat.put(message.thread, had == null ? 1 : had + 1);
            Long time = newest.get(message.thread);
            if (time == null || message.millis > time) newest.put(message.thread, message.millis);
            if (rows.size() < NEWEST) rows.add(message);
        }
    }

    static List<PhoneCheck.Line> gather(Context context) {
        List<PhoneCheck.Line> lines = new ArrayList<>();
        if (!Sync.allowed(context)) {
            lines.add(new PhoneCheck.Line(PhoneCheck.Line.NOTE, Sync.NOT_ALLOWED));
            return lines;
        }
        ContentResolver resolver = context.getContentResolver();
        ZoneId zone = ZoneId.systemDefault();

        String messagesApp = null;
        try {
            String version = context.getPackageManager().getPackageInfo(MESSAGES_APP, 0).versionName;
            messagesApp = version == null ? "of unknown version" : version;
        } catch (PackageManager.NameNotFoundException error) {
            messagesApp = null;
        } catch (RuntimeException error) {
            messagesApp = "couldn't be checked";
        }
        String defaultApp;
        try {
            defaultApp = Telephony.Sms.getDefaultSmsPackage(context);
        } catch (RuntimeException error) {
            defaultApp = "couldn't be checked";
        }
        lines.add(row(PhoneCheck.topLine(Build.VERSION.RELEASE, messagesApp, defaultApp)));

        // The people and filings the app counts with, as a run would use them.
        Map<String, String> counting = new HashMap<>();
        Map<String, String> unticked = new HashMap<>();
        Map<Long, Rules.Who> filed = new HashMap<>();
        String recordProblem = null;
        try {
            Record record = Record.get(context);
            List<Rules.Person> people = record.people();
            counting = Rules.countingNumbers(people);
            for (Rules.Person person : people) {
                if (person.ticked) continue;
                for (String number : person.numbers) {
                    String key = Rules.numberKey(number);
                    if (!key.isEmpty()) unticked.put(key, person.label());
                }
            }
            filed = record.conversations();
        } catch (Record.TooNew error) {
            recordProblem = error.getMessage();
        } catch (RuntimeException error) {
            recordProblem = Record.UNREADABLE;
        }

        // The conversation list, read as a run reads it.
        Map<Long, List<String>> listed = new HashMap<>();
        Map<String, String> addresses = new HashMap<>();
        Map<Long, String> recipientIds = new HashMap<>();
        int chats = -1;
        try {
            try (Cursor cursor = open(resolver, CANONICAL, new String[] {"_id", "address"}, null)) {
                while (cursor.moveToNext()) addresses.put(cursor.getString(0), cursor.getString(1));
            }
            try (Cursor cursor = open(resolver, THREADS, new String[] {"_id", "recipient_ids"}, null)) {
                while (cursor.moveToNext()) recipientIds.put(cursor.getLong(0), cursor.getString(1));
            }
            listed.putAll(Rules.threadPeople(addresses, recipientIds));
            chats = recipientIds.size();
        } catch (RuntimeException error) {
            if (error instanceof SecurityException) throw error;
            listed.clear();
            addresses.clear();
            recipientIds.clear();
        }
        boolean readable = !listed.isEmpty();

        String textProblem = null, pictureProblem = null;
        Table texts = new Table();
        try {
            readTexts(resolver, texts);
        } catch (RuntimeException error) {
            if (error instanceof SecurityException) throw error;
            texts = null;
            textProblem = "The text table couldn't be read.";
        }
        Table pictures = new Table();
        try {
            readPictures(resolver, pictures);
        } catch (RuntimeException error) {
            if (error instanceof SecurityException) throw error;
            pictures = null;
            pictureProblem = "The picture and group table couldn't be read.";
        }
        lines.add(row(PhoneCheck.countsLine(chats, texts == null ? -1 : texts.count, pictures == null ? -1 : pictures.count)));
        lines.add(row(PhoneCheck.listLine(readable)));
        if (recordProblem != null) lines.add(quiet(recordProblem + " Nobody is shown as counting."));
        if (textProblem != null) lines.add(quiet(textProblem));
        if (pictureProblem != null) lines.add(quiet(pictureProblem));

        // Who is in each chat as the app sees it: the conversation list, else the addresses on a few of its messages.
        Map<Long, List<String>> threadPeople = new HashMap<>(listed);
        Map<Long, List<String>> fromMessages = new HashMap<>();
        if (pictures != null) {
            for (Map.Entry<Long, List<Long>> entry : Rules.fallbackMessages(pictures.ids, threadPeople.keySet()).entrySet()) {
                List<List<String>> found = new ArrayList<>();
                for (long id : entry.getValue()) found.add(usableAddressesOn(resolver, id));
                fromMessages.put(entry.getKey(), Rules.peopleOnMessages(found));
            }
        }
        threadPeople.putAll(fromMessages);
        Rules.Texts decide = new Rules.Texts(counting, threadPeople, filed);

        // Chats, newest first.
        Set<Long> threads = new HashSet<>(recipientIds.keySet());
        final Map<Long, Long> newest = new HashMap<>();
        Map<Long, Integer> count = new HashMap<>();
        for (Table table : new Table[] {texts, pictures}) {
            if (table == null) continue;
            threads.addAll(table.perChat.keySet());
            for (Map.Entry<Long, Integer> entry : table.perChat.entrySet()) {
                Integer had = count.get(entry.getKey());
                count.put(entry.getKey(), (had == null ? 0 : had) + entry.getValue());
            }
            for (Map.Entry<Long, Long> entry : table.newest.entrySet()) {
                Long had = newest.get(entry.getKey());
                if (had == null || entry.getValue() > had) newest.put(entry.getKey(), entry.getValue());
            }
        }
        List<Long> order = new ArrayList<>(threads);
        Collections.sort(order, new Comparator<Long>() {
            @Override
            public int compare(Long a, Long b) {
                long x = newest.containsKey(a) ? newest.get(a) : 0, y = newest.containsKey(b) ? newest.get(b) : 0;
                return x != y ? Long.compare(y, x) : Long.compare(b, a);
            }
        });
        lines.add(new PhoneCheck.Line(PhoneCheck.Line.HEADING, "Chats"));
        if (order.isEmpty()) lines.add(quiet("No chats."));
        for (long thread : order.subList(0, Math.min(CHATS, order.size()))) {
            List<String> people = listed.get(thread);
            List<String> named = people != null ? people : fromMessages.get(thread);
            String filing = null;
            Rules.Who remembered = filed.get(thread);
            if (remembered != null) filing = remembered.contact;
            else if (named != null && !named.isEmpty()) {
                Rules.Who who = Rules.classify(thread, named, counting);
                if (who != null) filing = who.contact;
            }
            String raw = recipientIds.get(thread);
            lines.add(row(PhoneCheck.chatRow(thread, people, fromMessages.get(thread),
                recipientIds.containsKey(thread) ? PhoneCheck.ids(raw, addresses) : null, PhoneCheck.leftOut(raw, addresses),
                count.containsKey(thread) ? count.get(thread) : 0,
                newest.containsKey(thread) ? newest.get(thread) : 0, filing, counting, unticked, zone)));
        }

        // The newest rows of both tables, merged by time.
        List<Message> messages = new ArrayList<>();
        if (texts != null) messages.addAll(texts.rows);
        if (pictures != null) messages.addAll(pictures.rows);
        Collections.sort(messages, new Comparator<Message>() {
            @Override
            public int compare(Message a, Message b) {
                return a.millis != b.millis ? Long.compare(b.millis, a.millis) : Long.compare(b.id, a.id);
            }
        });
        lines.add(new PhoneCheck.Line(PhoneCheck.Line.HEADING, "Newest messages"));
        if (messages.isEmpty()) lines.add(quiet("No messages."));
        for (Message message : messages.subList(0, Math.min(NEWEST, messages.size()))) {
            List<String> people = threadPeople.get(message.thread);
            boolean fromList = listed.containsKey(message.thread);
            if (message.text) {
                Rules.Decision decision = decide.sms(message.thread, message.address, message.millis, message.box);
                String branch = PhoneCheck.branch(true, people, fromList, filed.containsKey(message.thread) && decision.entry != null);
                lines.add(row(PhoneCheck.textRow(message.millis, message.box, message.thread, message.address, message.creator, message.extra,
                    PhoneCheck.decision(decision, branch, false), zone)));
            } else {
                Rules.Decision decision = decide.mms(message.thread, message.stored, message.box, message.messageType);
                String branch = PhoneCheck.branch(false, people, fromList, filed.containsKey(message.thread) && decision.entry != null);
                List<String> found = new ArrayList<>();
                List<Integer> types = new ArrayList<>();
                boolean read = addressesOn(resolver, message.id, found, types);
                lines.add(row(PhoneCheck.pictureRow(message.millis, message.box, message.messageType, message.thread,
                    read ? PhoneCheck.addresses(found, types) : null, found.size(), message.creator,
                    PhoneCheck.decision(decision, branch, people != null && people.isEmpty()), zone)));
            }
        }
        return lines;
    }

    private static PhoneCheck.Line row(String text) {
        return new PhoneCheck.Line(PhoneCheck.Line.ROW, text);
    }

    private static PhoneCheck.Line quiet(String text) {
        return new PhoneCheck.Line(PhoneCheck.Line.NOTE, text);
    }

    private static Cursor open(ContentResolver resolver, Uri uri, String[] columns, String order) {
        Cursor cursor = resolver.query(uri, columns, null, null, order);
        if (cursor == null) throw new Collector.Problem("Android returned nothing.");
        return cursor;
    }

    /** Which of these columns the table has. Asking for a column a table lacks fails, so each is tried on its own. */
    private static List<String> present(ContentResolver resolver, Uri uri, String... optional) {
        List<String> out = new ArrayList<>();
        for (String column : optional) {
            try (Cursor cursor = resolver.query(uri, new String[] {"_id", column}, "_id < 0", null, null)) {
                if (cursor != null) out.add(column);
            } catch (RuntimeException error) {
                // Not on this phone.
            }
        }
        return out;
    }

    private static int need(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0) throw new Collector.Problem("No " + column + " column.");
        return index;
    }

    private static void readTexts(ContentResolver resolver, Table table) {
        List<String> columns = new ArrayList<>(java.util.Arrays.asList("_id", "thread_id", "address", "date", "type"));
        columns.addAll(present(resolver, SMS, "creator", "protocol", "sub_id"));
        try (Cursor cursor = open(resolver, SMS, columns.toArray(new String[0]), "date DESC")) {
            int id = need(cursor, "_id"), thread = need(cursor, "thread_id"), address = need(cursor, "address");
            int date = need(cursor, "date"), type = need(cursor, "type");
            int creator = cursor.getColumnIndex("creator"), protocol = cursor.getColumnIndex("protocol"), sub = cursor.getColumnIndex("sub_id");
            while (cursor.moveToNext()) {
                Message message = new Message();
                message.text = true;
                message.id = cursor.getLong(id);
                message.thread = cursor.getLong(thread);
                message.address = cursor.getString(address);
                message.millis = message.stored = cursor.getLong(date);
                message.box = cursor.getInt(type);
                if (table.rows.size() < NEWEST) {
                    message.creator = creator >= 0 ? cursor.getString(creator) : null;
                    List<String> extra = new ArrayList<>();
                    if (protocol >= 0 && !cursor.isNull(protocol)) extra.add("protocol " + cursor.getString(protocol));
                    if (sub >= 0 && !cursor.isNull(sub)) extra.add("sub_id " + cursor.getString(sub));
                    message.extra = String.join(" · ", extra);
                }
                table.add(message);
            }
        }
    }

    private static void readPictures(ContentResolver resolver, Table table) {
        List<String> columns = new ArrayList<>(java.util.Arrays.asList("_id", "thread_id", "date", "msg_box"));
        columns.addAll(present(resolver, MMS, "m_type", "creator"));
        try (Cursor cursor = open(resolver, MMS, columns.toArray(new String[0]), "date DESC")) {
            int id = need(cursor, "_id"), thread = need(cursor, "thread_id"), date = need(cursor, "date"), box = need(cursor, "msg_box");
            int type = cursor.getColumnIndex("m_type"), creator = cursor.getColumnIndex("creator");
            while (cursor.moveToNext()) {
                Message message = new Message();
                message.id = cursor.getLong(id);
                message.thread = cursor.getLong(thread);
                message.stored = cursor.getLong(date);
                message.millis = Rules.toSeconds(message.stored, true) * 1000L;
                message.box = cursor.getInt(box);
                message.messageType = type >= 0 && !cursor.isNull(type) ? cursor.getInt(type) : -1;
                if (table.rows.size() < NEWEST) message.creator = creator >= 0 ? cursor.getString(creator) : null;
                table.ids.add(new long[] {message.id, message.thread});
                table.add(message);
            }
        }
    }

    /** The addresses on one picture or group message that name a person, as a run reads them. */
    private static List<String> usableAddressesOn(ContentResolver resolver, long id) {
        List<String> found = new ArrayList<>();
        try (Cursor cursor = resolver.query(Uri.parse("content://mms/" + id + "/addr"), new String[] {"address"}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String address = cursor.getString(0);
                if (Rules.usableAddress(address)) found.add(address);
            }
        } catch (RuntimeException error) {
            return found;
        }
        return found;
    }

    /** Every address on one picture or group message with its type, exactly as stored. False when they can't be read. */
    private static boolean addressesOn(ContentResolver resolver, long id, List<String> addresses, List<Integer> types) {
        try (Cursor cursor = resolver.query(Uri.parse("content://mms/" + id + "/addr"), new String[] {"address", "type"}, null, null, null)) {
            if (cursor == null) return false;
            while (cursor.moveToNext()) {
                addresses.add(cursor.getString(0) == null ? "none" : cursor.getString(0));
                types.add(cursor.isNull(1) ? -1 : cursor.getInt(1));
            }
        } catch (RuntimeException error) {
            addresses.clear();
            types.clear();
            return false;
        }
        return true;
    }
}
