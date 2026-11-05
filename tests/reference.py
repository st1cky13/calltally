"""The independent reference for what the app's record must hold, kept from calltally.py (the retired Pi server, 0.6.0).

The app's ids, repeat rule and counting were written to match that server, and the unit tests and tools/emulator_check.py
still check the app against it. This module keeps only those pieces, unchanged: the entry id rule, the repeat rule,
CONNECTED, the entries table and the add, range, totals, contacts and day questions over an SQLite file. The server, the
page, settings, status, uploads, ignoring and restoring, and backups are in ~/local/archive/calltally-server.
ReferenceTests in tests/test_calltally.py pin its values to the ones calltally.py gave.

Standard library only. Not a test module itself: unittest discovery only collects test*.py.
"""
import hashlib
import re
import sqlite3
from datetime import datetime
from pathlib import Path

DIRECTIONS = {"call": {"in", "out", "missed", "rejected"}, "text": {"in", "out"}}
DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
AT_RE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")
MAX_ENTRIES = 2000
# Only a call that connected counts. Phones can log ringing time on a missed call, so its duration means nothing.
CONNECTED = "(kind='call' AND direction IN ('in','out') AND duration_s>0)"
# Counted the same way as each day on the month view: connected calls only, ignored entries never.
TOTALS = (f"COALESCE(SUM(CASE WHEN {CONNECTED} AND deleted_at IS NULL THEN duration_s END),0) AS call_s,"
          f" COALESCE(SUM({CONNECTED} AND deleted_at IS NULL),0) AS calls,"
          " COALESCE(SUM(kind='text' AND deleted_at IS NULL),0) AS texts")

# deleted_at is set when an entry is ignored; the app's column of the same name means the same.
SCHEMA = """
CREATE TABLE IF NOT EXISTS entries(
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  epoch INTEGER NOT NULL,
  at TEXT NOT NULL,
  day TEXT NOT NULL,
  contact TEXT NOT NULL,
  number TEXT NOT NULL,
  number_key TEXT NOT NULL,
  direction TEXT NOT NULL,
  duration_s INTEGER NOT NULL,
  deleted_at TEXT
);
CREATE INDEX IF NOT EXISTS entries_day ON entries(day);
CREATE INDEX IF NOT EXISTS entries_same ON entries(kind,number_key,at,direction);
"""


class BadEntry(Exception):
    pass


def number_key(number):
    # A group is known by its conversation, which must not match a short phone number with the same digits.
    if number.startswith("thread-"):
        return number
    digits = re.sub(r"\D", "", number)
    return digits[-10:] if digits else number.strip().lower()


def entry_id(kind, number, epoch, direction):
    # Built from the true moment, not the local clock text, so a change of time zone can't create a second copy.
    raw = f"{kind}|{number_key(number)}|{epoch}|{direction}"
    return hashlib.sha256(raw.encode()).hexdigest()[:32]


def is_real_day(value):
    if not isinstance(value, str) or not DAY_RE.match(value):
        return False
    try:
        datetime.strptime(value, "%Y-%m-%d")
    except ValueError:
        return False
    return True


def clean_entry(raw):
    """The row an entry becomes, or BadEntry when the record can't keep it."""
    if not isinstance(raw, dict):
        raise BadEntry("an entry is not an object")
    kind = raw.get("kind")
    if kind not in DIRECTIONS:
        raise BadEntry("unknown kind")
    direction = raw.get("direction")
    if direction not in DIRECTIONS[kind]:
        raise BadEntry("unknown direction")
    at = raw.get("at")
    if not isinstance(at, str) or not AT_RE.match(at) or not is_real_day(at[:10]):
        raise BadEntry("bad time")
    epoch = raw.get("epoch")
    if type(epoch) is not int or epoch <= 0:
        raise BadEntry("bad epoch")
    duration = raw.get("duration_s", 0)
    if type(duration) is not int or not 0 <= duration <= 7 * 86400:
        raise BadEntry("bad duration")
    if kind == "text":
        duration = 0
    contact = raw.get("contact")
    number = raw.get("number")
    if not isinstance(contact, str) or not contact.strip() or len(contact) > 200:
        raise BadEntry("bad contact")
    if not isinstance(number, str) or not number.strip() or len(number) > 60:
        raise BadEntry("bad number")
    return {
        "id": entry_id(kind, number, epoch, direction),
        "kind": kind,
        "epoch": epoch,
        "at": at,
        "day": at[:10],
        "contact": contact.strip(),
        "number": number.strip(),
        "number_key": number_key(number),
        "direction": direction,
        "duration_s": duration,
    }


class Store:
    """The record as calltally.py kept it, in one SQLite file."""

    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        conn = self.connect()
        try:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(SCHEMA)
        finally:
            conn.close()

    def connect(self):
        conn = sqlite3.connect(self.path, timeout=15)
        conn.row_factory = sqlite3.Row
        return conn

    def add(self, raw_entries):
        if not isinstance(raw_entries, list) or len(raw_entries) > MAX_ENTRIES:
            raise BadEntry("bad entries list")
        entries = [clean_entry(raw) for raw in raw_entries]
        conn = self.connect()
        try:
            with conn:
                added = 0
                for entry in entries:
                    # A repeat only refreshes the contact's name. It never brings back an ignored entry, and it keeps
                    # the time and day first recorded. Same moment means a repeat, and so does the same clock time:
                    # the first covers a change of time zone, the second a run that read the phone's zone wrongly.
                    found = conn.execute(
                        "SELECT id FROM entries WHERE id=:id "
                        "OR (kind=:kind AND number_key=:number_key AND at=:at AND direction=:direction) LIMIT 1",
                        entry,
                    ).fetchone()
                    if found:
                        conn.execute("UPDATE entries SET contact=? WHERE id=?", (entry["contact"], found["id"]))
                        continue
                    conn.execute(
                        "INSERT INTO entries(id,kind,epoch,at,day,contact,number,number_key,direction,duration_s) "
                        "VALUES(:id,:kind,:epoch,:at,:day,:contact,:number,:number_key,:direction,:duration_s)",
                        entry,
                    )
                    added += 1
        finally:
            conn.close()
        return {"received": len(entries), "added": added}

    def range(self, start, end):
        if not is_real_day(start) or not is_real_day(end):
            raise BadEntry("bad date")
        conn = self.connect()
        try:
            rows = conn.execute(
                "SELECT day,"
                f" COALESCE(SUM(CASE WHEN {CONNECTED} AND deleted_at IS NULL THEN duration_s END),0) AS call_s,"
                f" COALESCE(SUM({CONNECTED} AND deleted_at IS NULL),0) AS calls,"
                f" COALESCE(SUM(kind='call' AND NOT {CONNECTED} AND deleted_at IS NULL),0) AS unanswered,"
                " COALESCE(SUM(kind='text' AND deleted_at IS NULL),0) AS texts,"
                " COALESCE(SUM(deleted_at IS NOT NULL),0) AS deleted"
                " FROM entries WHERE day>=? AND day<=? GROUP BY day",
                (start, end),
            ).fetchall()
            earliest = conn.execute("SELECT MIN(day) FROM entries").fetchone()[0]
        finally:
            conn.close()
        return {
            "days": {row["day"]: {k: row[k] for k in ("call_s", "calls", "unanswered", "texts", "deleted")} for row in rows},
            "earliest": earliest,
        }

    @staticmethod
    def span(start, end):
        # No dates means all time. Either one alone is refused, so a typo can't quietly widen the range.
        if start is None and end is None:
            return "", ()
        if not is_real_day(start) or not is_real_day(end):
            raise BadEntry("bad date")
        return " AND day>=? AND day<=?", (start, end)

    def totals(self, start=None, end=None):
        where, args = self.span(start, end)
        conn = self.connect()
        try:
            row = conn.execute(f"SELECT {TOTALS} FROM entries WHERE 1=1{where}", args).fetchone()
        finally:
            conn.close()
        return {k: row[k] for k in ("call_s", "calls", "texts")}

    def contacts(self, start=None, end=None):
        # Grouped by name: a repeat refreshes old rows to the current name, so a renamed contact stays one line.
        # A contact with nothing that counts, only ignored entries or unanswered calls, is left out.
        where, args = self.span(start, end)
        conn = self.connect()
        try:
            rows = conn.execute(
                f"SELECT contact, {TOTALS} FROM entries WHERE deleted_at IS NULL{where} GROUP BY contact "
                "HAVING calls>0 OR texts>0 ORDER BY call_s DESC, texts DESC, contact",
                args,
            ).fetchall()
        finally:
            conn.close()
        return {"contacts": [{k: row[k] for k in ("contact", "call_s", "calls", "texts")} for row in rows]}

    def day(self, day):
        if not is_real_day(day):
            raise BadEntry("bad date")
        conn = self.connect()
        try:
            rows = conn.execute(
                "SELECT id,kind,at,contact,number,direction,duration_s,deleted_at FROM entries "
                "WHERE day=? ORDER BY epoch,id",
                (day,),
            ).fetchall()
        finally:
            conn.close()
        return {"day": day, "entries": [dict(row) for row in rows]}
