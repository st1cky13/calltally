package com.calltally.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The record on the phone: entries, the people the user ticked with their numbers, and the settings. It lives in the
 * app's private storage only. The table layouts and the questions asked of them are in Sql; ids and repeats in Ledger.
 */
final class Record {
    /** The record was made by a newer version of the app. Nothing is read or changed; the screen says so. */
    static final class TooNew extends RuntimeException {
        TooNew() {
            super("This record was made by a newer Call Tally. Install the newer version to open it.");
        }
    }

    /** What a screen says when the record can't be read for any other reason. The file is left as it is. */
    static final String UNREADABLE = "The record couldn't be read.";

    private static Record shared;
    private final Helper helper;

    private Record(Context context) {
        helper = new Helper(context.getApplicationContext());
    }

    static synchronized Record get(Context context) {
        if (shared == null) shared = new Record(context);
        return shared;
    }

    /**
     * When SQLite says the record is damaged, Android's own handler deletes the file and starts an empty one. This one
     * moves the file aside under a new name instead, so nothing is erased, and leaves a note for the month screen.
     */
    private static final class SetAside implements DatabaseErrorHandler {
        private final Context context;

        SetAside(Context context) {
            this.context = context;
        }

        @Override
        public void onCorruption(SQLiteDatabase db) {
            String path = db.getPath();
            try {
                db.close();
            } catch (RuntimeException ignored) {
                // Closing a damaged file can fail; moving it is what matters.
            }
            if (path == null || path.equals(":memory:")) return;
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File file = new File(path);
            File aside = new File(path + ".damaged-" + stamp);
            boolean moved = file.renameTo(aside);
            for (String extra : new String[] {"-journal", "-wal", "-shm"}) {
                File beside = new File(path + extra);
                if (beside.exists()) beside.renameTo(new File(aside.getPath() + extra));
            }
            Sync.noteDamaged(context, moved ? aside.getName() : null);
        }
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            super(context, Sql.NAME, null, Sql.VERSION, new SetAside(context));
            // One file with nothing beside it, so a copy of it is the whole record.
            setWriteAheadLoggingEnabled(false);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            for (String statement : Sql.SCHEMA) db.execSQL(statement);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Every statement only adds what is missing, so running them all brings any older record up to date.
            for (String statement : Sql.SCHEMA) db.execSQL(statement);
        }

        @Override
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new TooNew();
        }
    }

    private SQLiteDatabase db() {
        return helper.getWritableDatabase();
    }

    static String nowIso() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    // Entries

    int count() {
        try (Cursor cursor = db().rawQuery("SELECT COUNT(*) FROM entries", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** Adds a run's entries in one go with the server's repeat rule. */
    Ledger.Added add(List<Rules.Entry> entries) {
        return add(entries, new HashMap<Long, Rules.Who>());
    }

    /** The same, also remembering how conversations met for the first time were filed, in the same transaction. */
    Ledger.Added add(List<Rules.Entry> entries, Map<Long, Rules.Who> filed) {
        final SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            for (Map.Entry<Long, Rules.Who> conversation : filed.entrySet()) {
                ContentValues values = new ContentValues();
                values.put("thread", conversation.getKey());
                values.put("number", conversation.getValue().number);
                values.put("contact", conversation.getValue().contact);
                // The first filing wins: a later run never changes how a conversation is filed.
                db.insertWithOnConflict("conversations", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            Ledger.Added added = Ledger.add(new Ledger.Book() {
                @Override
                public String find(Ledger.Row row) {
                    try (Cursor cursor = db.rawQuery(Sql.FIND, new String[] {row.id, row.kind, row.numberKey, row.at, row.direction})) {
                        return cursor.moveToFirst() ? cursor.getString(0) : null;
                    }
                }

                @Override
                public void insert(Ledger.Row row) {
                    ContentValues values = new ContentValues();
                    values.put("id", row.id);
                    values.put("kind", row.kind);
                    values.put("epoch", row.epoch);
                    values.put("at", row.at);
                    values.put("day", row.day);
                    values.put("contact", row.contact);
                    values.put("number", row.number);
                    values.put("number_key", row.numberKey);
                    values.put("direction", row.direction);
                    values.put("duration_s", row.durationS);
                    db.insertOrThrow("entries", null, values);
                }

                @Override
                public void rename(String id, String contact) {
                    ContentValues values = new ContentValues();
                    values.put("contact", contact);
                    db.update("entries", values, "id=?", new String[] {id});
                }
            }, entries);
            db.setTransactionSuccessful();
            return added;
        } finally {
            db.endTransaction();
        }
    }

    /** How each conversation of more than one person was first filed, by thread id. */
    Map<Long, Rules.Who> conversations() {
        Map<Long, Rules.Who> out = new HashMap<>();
        try (Cursor c = db().rawQuery("SELECT thread, number, contact FROM conversations", null)) {
            while (c.moveToNext()) out.put(c.getLong(0), new Rules.Who(c.getString(2), c.getString(1)));
        }
        return out;
    }

    /** Sets or clears an entry's Ignore mark. False when there is no such entry. */
    boolean setIgnored(String id, boolean ignored) {
        ContentValues values = new ContentValues();
        if (ignored) values.put("deleted_at", nowIso());
        else values.putNull("deleted_at");
        return db().update("entries", values, "id=?", new String[] {id}) == 1;
    }

    /** One day's totals. Ignored entries count only as ignored; missed and declined calls only as unanswered. */
    static final class DayTotals {
        final String day;
        final long callS;
        final int calls;
        final int unanswered;
        final int texts;
        final int ignored;

        DayTotals(String day, long callS, int calls, int unanswered, int texts, int ignored) {
            this.day = day;
            this.callS = callS;
            this.calls = calls;
            this.unanswered = unanswered;
            this.texts = texts;
            this.ignored = ignored;
        }
    }

    /** Each day with entries from one date to another (yyyy-MM-dd, both included), by day. */
    Map<String, DayTotals> days(String from, String to) {
        Map<String, DayTotals> out = new LinkedHashMap<>();
        try (Cursor c = db().rawQuery(Sql.DAYS, new String[] {from, to})) {
            while (c.moveToNext()) out.put(c.getString(0), new DayTotals(c.getString(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5)));
        }
        return out;
    }

    /** One entry as a day's list shows it. ignoredAt is null unless it is ignored. */
    static final class Item {
        final String id;
        final String kind;
        final long epoch;
        final String at;
        final String contact;
        final String number;
        final String direction;
        final long durationS;
        final String ignoredAt;

        Item(Cursor c) {
            id = c.getString(0);
            kind = c.getString(1);
            epoch = c.getLong(2);
            at = c.getString(3);
            contact = c.getString(4);
            number = c.getString(5);
            direction = c.getString(6);
            durationS = c.getLong(7);
            ignoredAt = c.isNull(8) ? null : c.getString(8);
        }
    }

    List<Item> day(String day) {
        List<Item> out = new ArrayList<>();
        try (Cursor c = db().rawQuery(Sql.DAY, new String[] {day})) {
            while (c.moveToNext()) out.add(new Item(c));
        }
        return out;
    }

    /** Call time, calls and texts, counted as each day is. contact is null except in totals by contact. */
    static final class Totals {
        final String contact;
        final long callS;
        final int calls;
        final int texts;

        Totals(String contact, long callS, int calls, int texts) {
            this.contact = contact;
            this.callS = callS;
            this.calls = calls;
            this.texts = texts;
        }
    }

    Totals totals() {
        return totals(Sql.TOTALS_ALL, (String[]) null);
    }

    Totals totals(String from, String to) {
        return totals(Sql.TOTALS_RANGE, new String[] {from, to});
    }

    private Totals totals(String sql, String[] args) {
        try (Cursor c = db().rawQuery(sql, args)) {
            c.moveToFirst();
            return new Totals(null, c.getLong(0), c.getInt(1), c.getInt(2));
        }
    }

    List<Totals> contacts() {
        return contacts(Sql.CONTACTS_ALL, (String[]) null);
    }

    List<Totals> contacts(String from, String to) {
        return contacts(Sql.CONTACTS_RANGE, new String[] {from, to});
    }

    private List<Totals> contacts(String sql, String[] args) {
        List<Totals> out = new ArrayList<>();
        try (Cursor c = db().rawQuery(sql, args)) {
            while (c.moveToNext()) out.add(new Totals(c.getString(0), c.getLong(1), c.getInt(2), c.getInt(3)));
        }
        return out;
    }

    /** The first day with an entry, or null with none. */
    String earliest() {
        try (Cursor c = db().rawQuery(Sql.EARLIEST, null)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : null;
        }
    }

    // People

    /** Everyone in the people table with their numbers, in order of their key. */
    List<Rules.Person> people() {
        SQLiteDatabase db = db();
        Map<String, List<String>> numbers = new LinkedHashMap<>();
        try (Cursor c = db.rawQuery("SELECT person_key, number FROM numbers ORDER BY person_key, rowid", null)) {
            while (c.moveToNext()) {
                List<String> list = numbers.get(c.getString(0));
                if (list == null) {
                    list = new ArrayList<>();
                    numbers.put(c.getString(0), list);
                }
                list.add(c.getString(1));
            }
        }
        List<Rules.Person> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT key, name, ticked, on_phone FROM people ORDER BY key", null)) {
            while (c.moveToNext()) {
                List<String> list = numbers.get(c.getString(0));
                out.add(new Rules.Person(c.getString(0), c.getString(1), list == null ? new ArrayList<String>() : list,
                    c.getInt(2) == 1, c.getInt(3) == 1));
            }
        }
        return out;
    }

    /** The numbers that count right now, with the name each counts under. */
    Map<String, String> countingNumbers() {
        return Rules.countingNumbers(people());
    }

    /** After a read of the phone's contacts: names, new numbers and whether each person is still on the phone. */
    void refreshPeople(List<Rules.Person> phone) {
        if (phone == null || phone.isEmpty()) return;
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            // A contact Android gave a new key keeps its one line: the person takes the new key with their tick and numbers.
            for (Map.Entry<String, String> moved : Rules.movedKeys(people(), phone).entrySet()) {
                String[] keys = {moved.getValue(), moved.getKey()};
                db.execSQL("UPDATE people SET key=? WHERE key=?", keys);
                db.execSQL("UPDATE numbers SET person_key=? WHERE person_key=?", keys);
            }
            for (Rules.Person person : Rules.refreshPeople(people(), phone)) {
                ContentValues values = new ContentValues();
                values.put("name", person.name);
                values.put("on_phone", person.onPhone ? 1 : 0);
                db.update("people", values, "key=?", new String[] {person.key});
                addNumbers(db, person.key, person.numbers);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Ticks contacts from the phone: adds them and their numbers, or ticks them again. */
    void tick(Collection<Rules.Person> contacts) {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            for (Rules.Person contact : contacts) {
                ContentValues values = new ContentValues();
                values.put("ticked", 1);
                values.put("on_phone", 1);
                if (!contact.name.isEmpty()) values.put("name", contact.name);
                if (db.update("people", values, "key=?", new String[] {contact.key}) == 0) {
                    values.put("key", contact.key);
                    values.put("name", contact.label());
                    values.put("first_ticked_at", nowIso());
                    db.insertOrThrow("people", null, values);
                }
                addNumbers(db, contact.key, contact.numbers);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Ticks or unticks someone already in the people table. Unticking keeps the person, their numbers and their entries. */
    void setTicked(String key, boolean ticked) {
        ContentValues values = new ContentValues();
        values.put("ticked", ticked ? 1 : 0);
        db().update("people", values, "key=?", new String[] {key});
    }

    private static void addNumbers(SQLiteDatabase db, String key, List<String> numbers) {
        for (String number : numbers) {
            String numberKey = Rules.numberKey(number);
            if (numberKey.isEmpty()) continue;
            ContentValues values = new ContentValues();
            values.put("person_key", key);
            values.put("number", number.trim());
            values.put("number_key", numberKey);
            db.insertWithOnConflict("numbers", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    // Settings

    String setting(String key) {
        try (Cursor c = db().rawQuery("SELECT value FROM settings WHERE key=?", new String[] {key})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    void setSetting(String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value);
        db().insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Removes a setting, so it reads as never set. */
    void clearSetting(String key) {
        db().delete("settings", "key=?", new String[] {key});
    }
}
