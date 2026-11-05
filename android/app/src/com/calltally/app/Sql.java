package com.calltally.app;

/**
 * The record's tables and the questions the screens ask of it. Plain text with no Android parts, so the checks can run
 * the same statements on a copy of the server's database and compare the answers with calltally.py's.
 */
final class Sql {
    private Sql() {}

    static final String NAME = "calltally.db";
    static final int VERSION = 1;

    /** The entries table and its indexes, exactly as the server keeps them. deleted_at is the Ignore mark. */
    static final String[] SCHEMA = {
        "CREATE TABLE IF NOT EXISTS entries(id TEXT PRIMARY KEY, kind TEXT NOT NULL, epoch INTEGER NOT NULL, at TEXT NOT NULL,"
            + " day TEXT NOT NULL, contact TEXT NOT NULL, number TEXT NOT NULL, number_key TEXT NOT NULL, direction TEXT NOT NULL,"
            + " duration_s INTEGER NOT NULL, deleted_at TEXT)",
        "CREATE INDEX IF NOT EXISTS entries_day ON entries(day)",
        "CREATE INDEX IF NOT EXISTS entries_same ON entries(kind,number_key,at,direction)",
        // One row per person the user ever ticked. Nothing is removed from here or from numbers.
        "CREATE TABLE IF NOT EXISTS people(key TEXT PRIMARY KEY, name TEXT NOT NULL, ticked INTEGER NOT NULL,"
            + " on_phone INTEGER NOT NULL, first_ticked_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS numbers(person_key TEXT NOT NULL, number TEXT NOT NULL, number_key TEXT NOT NULL,"
            + " PRIMARY KEY(person_key, number_key))",
        "CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "INSERT OR IGNORE INTO settings(key,value) VALUES('pay_period_start','2026-10-25')",
        "INSERT OR IGNORE INTO settings(key,value) VALUES('pay_period_days','14')",
    };

    // Only a call that connected counts. Phones can log ringing time on a missed call, so its length means nothing.
    private static final String CONNECTED = "(kind='call' AND direction IN ('in','out') AND duration_s>0)";
    private static final String TOTALS = "COALESCE(SUM(CASE WHEN " + CONNECTED + " AND deleted_at IS NULL THEN duration_s END),0) AS call_s,"
        + " COALESCE(SUM(" + CONNECTED + " AND deleted_at IS NULL),0) AS calls,"
        + " COALESCE(SUM(kind='text' AND deleted_at IS NULL),0) AS texts";

    /** A row this one repeats: the same id, or the same kind, number key, clock time and direction. */
    static final String FIND = "SELECT id FROM entries WHERE id=? OR (kind=? AND number_key=? AND at=? AND direction=?) LIMIT 1";

    /** Each day from one date to another: call_s, calls, unanswered, texts, ignored. Ignored entries count only as ignored. */
    static final String DAYS = "SELECT day,"
        + " COALESCE(SUM(CASE WHEN " + CONNECTED + " AND deleted_at IS NULL THEN duration_s END),0) AS call_s,"
        + " COALESCE(SUM(" + CONNECTED + " AND deleted_at IS NULL),0) AS calls,"
        + " COALESCE(SUM(kind='call' AND NOT " + CONNECTED + " AND deleted_at IS NULL),0) AS unanswered,"
        + " COALESCE(SUM(kind='text' AND deleted_at IS NULL),0) AS texts,"
        + " COALESCE(SUM(deleted_at IS NOT NULL),0) AS ignored"
        + " FROM entries WHERE day>=? AND day<=? GROUP BY day ORDER BY day";

    /** One day's entries in order of time. */
    static final String DAY = "SELECT id,kind,epoch,at,contact,number,direction,duration_s,deleted_at FROM entries"
        + " WHERE day=? ORDER BY epoch,id";

    static final String TOTALS_ALL = "SELECT " + TOTALS + " FROM entries";
    static final String TOTALS_RANGE = TOTALS_ALL + " WHERE day>=? AND day<=?";

    /** Totals by contact name, with nothing that counts left out, largest call time first. */
    static final String CONTACTS_ALL = "SELECT contact, " + TOTALS + " FROM entries WHERE deleted_at IS NULL GROUP BY contact"
        + " HAVING calls>0 OR texts>0 ORDER BY call_s DESC, texts DESC, contact";
    static final String CONTACTS_RANGE = "SELECT contact, " + TOTALS + " FROM entries WHERE deleted_at IS NULL AND day>=? AND day<=?"
        + " GROUP BY contact HAVING calls>0 OR texts>0 ORDER BY call_s DESC, texts DESC, contact";

    static final String EARLIEST = "SELECT MIN(day) FROM entries";
}
