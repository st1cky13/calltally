#!/usr/bin/env python3
"""calltally: a private calendar of work calls and texts.

The phone uploads the time, contact, direction and call length of work calls and texts. It never uploads what a text
said. Standard library only. Listens on 127.0.0.1; reach it through Tailscale.
"""
import hashlib
import json
import os
import re
import sqlite3
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

VERSION = "0.4.0"
HERE = Path(__file__).resolve().parent
MAX_BODY = 4 * 1024 * 1024
MAX_ENTRIES = 2000
BACKUPS_KEPT = 14
DIRECTIONS = {"call": {"in", "out", "missed", "rejected"}, "text": {"in", "out"}}
DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
AT_RE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")
# Only a call that connected counts. Phones can log ringing time on a missed call, so its duration means nothing.
CONNECTED = "(kind='call' AND direction IN ('in','out') AND duration_s>0)"
# Counted the same way as each day on the month view: connected calls only, deleted entries never.
TOTALS = (f"COALESCE(SUM(CASE WHEN {CONNECTED} AND deleted_at IS NULL THEN duration_s END),0) AS call_s,"
          f" COALESCE(SUM({CONNECTED} AND deleted_at IS NULL),0) AS calls,"
          " COALESCE(SUM(kind='text' AND deleted_at IS NULL),0) AS texts")
SETTINGS_DEFAULT = {"pay_period_start": "2026-10-25", "pay_period_days": "14"}
MAX_STATUS = 200

# Lets a phone install the page with its own icon. No service worker, on purpose: this page is only ever live data.
MANIFEST = {
    "id": "/", "name": "Call Tally", "short_name": "Call Tally", "start_url": "/", "scope": "/",
    "display": "standalone", "background_color": "#0f6b5c", "theme_color": "#0f6b5c",
    "icons": [{"src": "/static/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any"},
              {"src": "/static/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any"},
              {"src": "/static/icon-512-maskable.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"}],
}
ICONS = {"/static/icon-192.png", "/static/icon-512.png", "/static/icon-512-maskable.png"}

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
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""


class BadRequest(Exception):
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


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def is_real_day(value):
    if not isinstance(value, str) or not DAY_RE.match(value):
        return False
    try:
        datetime.strptime(value, "%Y-%m-%d")
    except ValueError:
        return False
    return True


def clean_entry(raw):
    if not isinstance(raw, dict):
        raise BadRequest("an entry is not an object")
    kind = raw.get("kind")
    if kind not in DIRECTIONS:
        raise BadRequest("unknown kind")
    direction = raw.get("direction")
    if direction not in DIRECTIONS[kind]:
        raise BadRequest("unknown direction")
    at = raw.get("at")
    if not isinstance(at, str) or not AT_RE.match(at) or not is_real_day(at[:10]):
        raise BadRequest("bad time")
    epoch = raw.get("epoch")
    if type(epoch) is not int or epoch <= 0:
        raise BadRequest("bad epoch")
    duration = raw.get("duration_s", 0)
    if type(duration) is not int or not 0 <= duration <= 7 * 86400:
        raise BadRequest("bad duration")
    if kind == "text":
        duration = 0
    contact = raw.get("contact")
    number = raw.get("number")
    if not isinstance(contact, str) or not contact.strip() or len(contact) > 200:
        raise BadRequest("bad contact")
    if not isinstance(number, str) or not number.strip() or len(number) > 60:
        raise BadRequest("bad number")
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
            raise BadRequest("bad entries list")
        entries = [clean_entry(raw) for raw in raw_entries]
        conn = self.connect()
        try:
            with conn:
                added = 0
                for entry in entries:
                    # A repeat only refreshes the contact's name. It never brings back a deleted entry, and it keeps
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
                conn.execute(
                    "INSERT INTO meta(key,value) VALUES('last_upload',?) "
                    "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                    (now_iso(),),
                )
        finally:
            conn.close()
        return {"received": len(entries), "added": added}

    def set_deleted(self, entry_key, deleted):
        if not isinstance(entry_key, str) or not re.fullmatch(r"[0-9a-f]{32}", entry_key):
            raise BadRequest("bad id")
        conn = self.connect()
        try:
            with conn:
                value = now_iso() if deleted else None
                cursor = conn.execute("UPDATE entries SET deleted_at=? WHERE id=?", (value, entry_key))
                if cursor.rowcount != 1:
                    raise LookupError("no such entry")
        finally:
            conn.close()

    def range(self, start, end):
        if not is_real_day(start) or not is_real_day(end):
            raise BadRequest("bad date")
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
            meta = dict(conn.execute(
                "SELECT key,value FROM meta WHERE key IN ('last_upload','phone_status','phone_status_at')").fetchall())
        finally:
            conn.close()
        return {
            "days": {row["day"]: {k: row[k] for k in ("call_s", "calls", "unanswered", "texts", "deleted")} for row in rows},
            "earliest": earliest,
            "last_upload": meta.get("last_upload"),
            "phone_status": meta.get("phone_status"),
            "phone_status_at": meta.get("phone_status_at"),
        }

    def set_status(self, payload):
        # The phone's last problem, in one plain sentence, or empty when its run was fine. It is not an upload, so it
        # never moves last_upload: a phone that can talk to the server but can't read its records still shows as late.
        text = payload.get("text")
        if not isinstance(text, str) or len(text) > MAX_STATUS or any(ord(c) < 0x20 or c == "\x7f" for c in text):
            raise BadRequest("bad status")
        text = text.strip()
        conn = self.connect()
        try:
            with conn:
                if text:
                    conn.executemany(
                        "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                        (("phone_status", text), ("phone_status_at", now_iso())),
                    )
                else:
                    conn.execute("DELETE FROM meta WHERE key IN ('phone_status','phone_status_at')")
        finally:
            conn.close()
        return {"ok": True}

    @staticmethod
    def span(start, end):
        # No dates means all time. Either one alone is refused, so a typo can't quietly widen the range.
        if start is None and end is None:
            return "", ()
        if not is_real_day(start) or not is_real_day(end):
            raise BadRequest("bad date")
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
        # Grouped by name: a repeat upload refreshes old rows to the current name, so a renamed contact stays one line.
        # A contact with nothing that counts, only deleted entries or unanswered calls, is left out.
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

    def settings(self):
        conn = self.connect()
        try:
            rows = conn.execute(
                "SELECT key,value FROM meta WHERE key IN ('pay_period_start','pay_period_days')").fetchall()
        finally:
            conn.close()
        found = {**SETTINGS_DEFAULT, **{row["key"]: row["value"] for row in rows}}
        return {"pay_period_start": found["pay_period_start"], "pay_period_days": int(found["pay_period_days"])}

    def set_settings(self, payload):
        start = payload.get("pay_period_start")
        if not is_real_day(start) or not "2000-01-01" <= start <= "2100-12-31":
            raise BadRequest("bad date")
        conn = self.connect()
        try:
            with conn:
                conn.execute(
                    "INSERT INTO meta(key,value) VALUES('pay_period_start',?) "
                    "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                    (start,),
                )
                conn.execute("INSERT OR IGNORE INTO meta(key,value) VALUES('pay_period_days',?)",
                             (SETTINGS_DEFAULT["pay_period_days"],))
        finally:
            conn.close()
        return self.settings()

    def day(self, day):
        if not is_real_day(day):
            raise BadRequest("bad date")
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

    def backup(self, folder):
        folder = Path(folder)
        folder.mkdir(parents=True, exist_ok=True)
        target = folder / f"calltally-{datetime.now():%Y-%m-%d}.db"
        partial = target.with_suffix(".partial")
        source = self.connect()
        copy = sqlite3.connect(partial)
        try:
            source.backup(copy)
        finally:
            copy.close()
            source.close()
        partial.replace(target)
        for old in sorted(folder.glob("calltally-*.db"))[:-BACKUPS_KEPT]:
            old.unlink()
        return target


def make_handler(store):
    class Handler(BaseHTTPRequestHandler):
        server_version = f"calltally/{VERSION}"
        sys_version = ""
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            sys.stderr.write(f"{self.command} {urlparse(self.path).path} {args[1] if len(args) > 1 else ''}\n")

        def send_bytes(self, status, body, content_type):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            if self.close_connection:
                self.send_header("Connection", "close")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header(
                "Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:; "
                "connect-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            )
            self.end_headers()
            self.wfile.write(body)

        def send_json(self, status, payload):
            self.send_bytes(status, json.dumps(payload).encode(), "application/json")

        def send_file(self, relative, content_type):
            self.send_bytes(200, (HERE / relative).read_bytes(), content_type)

        def send_apk(self):
            # Built separately and copied beside the database, so it stays out of git.
            path = Path(os.environ.get("CALLTALLY_DATA", HERE / "data")) / "calltally.apk"
            if not path.is_file():
                self.send_json(404, {"error": "not found"})
                return
            self.send_bytes(200, path.read_bytes(), "application/vnd.android.package-archive")

        def base_url(self):
            proto = self.headers.get("X-Forwarded-Proto", "http")
            host = self.headers.get("X-Forwarded-Host") or self.headers.get("Host", "")
            return f"{proto}://{host}"

        def do_GET(self):
            url = urlparse(self.path)
            query = parse_qs(url.query, keep_blank_values=True)
            try:
                if url.path == "/":
                    self.send_file("calltally.html", "text/html; charset=utf-8")
                elif url.path == "/manifest.webmanifest":
                    self.send_bytes(200, json.dumps(MANIFEST).encode(), "application/manifest+json")
                elif url.path in ICONS:
                    self.send_file(url.path.lstrip("/"), "image/png")
                elif url.path == "/api/range":
                    self.send_json(200, store.range(query.get("from", [""])[0], query.get("to", [""])[0]))
                elif url.path in ("/api/totals", "/api/contacts"):
                    start = query.get("from", [None])[0]
                    end = query.get("to", [None])[0]
                    method = store.totals if url.path == "/api/totals" else store.contacts
                    self.send_json(200, method(start, end))
                elif url.path == "/api/settings":
                    self.send_json(200, store.settings())
                elif url.path == "/api/day":
                    self.send_json(200, store.day(query.get("day", [""])[0]))
                elif url.path == "/api/health":
                    self.send_json(200, {"ok": True, "version": VERSION})
                elif url.path == "/phone/calltally.apk":
                    self.send_apk()
                elif url.path == "/phone/calltally-phone.py":
                    self.send_file("phone/calltally-phone.py", "text/plain; charset=utf-8")
                elif url.path == "/phone/setup.sh":
                    text = (HERE / "phone/setup.sh").read_text().replace("__BASE_URL__", self.base_url())
                    self.send_bytes(200, text.encode(), "text/plain; charset=utf-8")
                else:
                    self.send_json(404, {"error": "not found"})
            except BadRequest as error:
                self.send_json(400, {"error": str(error)})

        def same_site(self):
            # Stops a web page on another site from making the browser change anything here.
            origin = self.headers.get("Origin")
            if not origin:
                return True
            hosts = {self.headers.get("Host"), self.headers.get("X-Forwarded-Host")}
            return urlparse(origin).netloc in hosts

        def read_json(self):
            if self.headers.get_content_type() != "application/json":
                raise BadRequest("expected JSON")
            try:
                length = int(self.headers.get("Content-Length", ""))
            except ValueError:
                raise BadRequest("missing length") from None
            if not 0 <= length <= MAX_BODY:
                self.close_connection = True
                raise BadRequest("too large")
            try:
                payload = json.loads(self.rfile.read(length))
            except ValueError:
                raise BadRequest("bad JSON") from None
            if not isinstance(payload, dict):
                raise BadRequest("bad JSON")
            return payload

        def do_POST(self):
            path = urlparse(self.path).path
            if not self.same_site():
                self.close_connection = True
                self.send_json(403, {"error": "other site"})
                return
            try:
                payload = self.read_json()
                if path == "/api/upload":
                    self.send_json(200, store.add(payload.get("entries")))
                elif path in ("/api/delete", "/api/restore"):
                    store.set_deleted(payload.get("id"), path == "/api/delete")
                    self.send_json(200, {"ok": True})
                elif path == "/api/settings":
                    self.send_json(200, store.set_settings(payload))
                elif path == "/api/status":
                    self.send_json(200, store.set_status(payload))
                else:
                    self.send_json(404, {"error": "not found"})
            except BadRequest as error:
                # The body may not have been read, so this connection can't carry another request.
                self.close_connection = True
                self.send_json(400, {"error": str(error)})
            except LookupError as error:
                self.send_json(404, {"error": str(error)})

    return Handler


def backup_loop(store, folder):
    while True:
        try:
            store.backup(folder)
        except (OSError, sqlite3.Error) as error:
            print(f"backup failed: {error}", file=sys.stderr, flush=True)
        time.sleep(24 * 3600)


def main():
    port = int(os.environ.get("CALLTALLY_PORT", "8093"))
    data = Path(os.environ.get("CALLTALLY_DATA", HERE / "data"))
    store = Store(data / "calltally.db")
    threading.Thread(target=backup_loop, args=(store, data / "backups"), daemon=True).start()
    server = ThreadingHTTPServer(("127.0.0.1", port), make_handler(store))
    print(f"calltally {VERSION} on 127.0.0.1:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
