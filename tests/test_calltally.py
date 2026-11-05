"""Run with: python -m unittest discover -s tests (from the repo). Uses fake termux commands and a real local server."""
import importlib.util
import json
import os
import shutil
import sqlite3
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import unittest.mock
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
import calltally  # noqa: E402

PHONE_SCRIPT = ROOT / "phone" / "calltally-phone.py"
spec = importlib.util.spec_from_file_location("calltally_phone", PHONE_SCRIPT)
phone = importlib.util.module_from_spec(spec)
spec.loader.exec_module(phone)

SECRET_WORDS = "the secret words of a text"
CONTACTS = [
    {"name": "Dana-Work", "number": "+1 (555) 123-4567"},
    {"name": "Sam - Work", "number": "555-222-3333"},
    {"name": "Mom", "number": "5559998888"},
    {"name": "Work Plumbing", "number": "5557776666"},
]
CALLS = [
    {"name": "Dana-Work", "phone_number": "15551234567", "type": "INCOMING", "date": "2026-11-04 09:12:00", "duration": "14:32", "sim_id": "1"},
    {"name": "UNKNOWN_CALLER", "phone_number": "5552223333", "type": "OUTGOING", "date": "2026-11-04 13:05:00", "duration": "01:06:08", "sim_id": "1"},
    {"name": "Dana-Work", "phone_number": "5551234567", "type": "MISSED", "date": "2026-11-04 15:00:00", "duration": "00:00", "sim_id": "1"},
    {"name": "Dana-Work", "phone_number": "5551234567", "type": "BLOCKED", "date": "2026-11-04 15:01:00", "duration": "00:00", "sim_id": "1"},
    {"name": "Mom", "phone_number": "5559998888", "type": "INCOMING", "date": "2026-11-04 18:00:00", "duration": "30:00", "sim_id": "1"},
    {"name": "Work Plumbing", "phone_number": "5557776666", "type": "OUTGOING", "date": "2026-11-04 18:30:00", "duration": "03:00", "sim_id": "1"},
    # A second number for a work contact: the contact list doesn't show it, but the name on the call does.
    {"name": "Dana-Work", "phone_number": "5550001111", "type": "OUTGOING", "date": "2026-11-03 10:00:00", "duration": "02:00", "sim_id": "1"},
]
TEXTS = [
    {"threadid": 1, "type": "inbox", "read": True, "sender": "Dana-Work", "address": "+15551234567", "number": "+15551234567", "received": "2026-11-04 09:40:00", "body": SECRET_WORDS, "_id": 1},
    {"threadid": 1, "type": "sent", "read": True, "sender": "You", "address": "+15551234567", "number": "+15551234567", "received": "2026-11-04 09:41:00", "body": SECRET_WORDS, "_id": 2},
    {"threadid": 2, "type": "inbox", "read": True, "sender": "Mom", "address": "5559998888", "number": "5559998888", "received": "2026-11-04 12:00:00", "body": SECRET_WORDS, "_id": 3},
    {"threadid": 1, "type": "draft", "read": True, "sender": "You", "address": "+15551234567", "number": "+15551234567", "received": "2026-11-04 12:30:00", "body": SECRET_WORDS, "_id": 4},
    {"threadid": 3, "type": "sent", "read": True, "sender": "You", "address": "5550001111", "number": "5550001111", "received": "2026-11-03 10:05:00", "body": SECRET_WORDS, "_id": 5},
]


def entry(**changes):
    base = {"kind": "call", "at": "2026-11-04 09:00:00", "epoch": 1793000000, "contact": "Dana-Work",
            "number": "5551234567", "direction": "in", "duration_s": 60}
    base.update(changes)
    return base


class ServerCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.mkdtemp(dir=os.environ.get("TMPDIR"))
        self.addCleanup(shutil.rmtree, self.temp)
        self.store = calltally.Store(Path(self.temp) / "t.db")
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), calltally.make_handler(self.store))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def get(self, path):
        with urllib.request.urlopen(self.base + path) as reply:
            return json.load(reply)

    def post(self, path, payload, headers=None, raw=None):
        all_headers = {"Content-Type": "application/json", **(headers or {})}
        data = raw if raw is not None else json.dumps(payload).encode()
        request = urllib.request.Request(self.base + path, data=data, headers=all_headers, method="POST")
        try:
            with urllib.request.urlopen(request) as reply:
                return reply.status, json.load(reply)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)


class StoreTests(ServerCase):
    def test_totals_add_up(self):
        self.post("/api/upload", {"entries": [
            entry(), entry(epoch=1793000100, at="2026-11-04 09:01:40", duration_s=30),
            entry(kind="text", epoch=1793000200, at="2026-11-04 09:03:20", duration_s=0),
        ]})
        day = self.get("/api/range?from=2026-11-01&to=2026-11-30")["days"]["2026-11-04"]
        self.assertEqual(day, {"call_s": 90, "calls": 2, "unanswered": 0, "texts": 1, "deleted": 0})

    def test_only_connected_calls_count(self):
        self.post("/api/upload", {"entries": [
            entry(duration_s=60),
            entry(epoch=1793000100, at="2026-11-04 09:01:40", direction="missed", duration_s=12),
            entry(epoch=1793000200, at="2026-11-04 09:03:20", direction="rejected", duration_s=5),
            entry(epoch=1793000300, at="2026-11-04 09:05:00", direction="out", duration_s=0),
        ]})
        day = self.get("/api/range?from=2026-11-04&to=2026-11-04")["days"]["2026-11-04"]
        self.assertEqual(day, {"call_s": 60, "calls": 1, "unanswered": 3, "texts": 0, "deleted": 0})
        self.assertEqual(len(self.get("/api/day?day=2026-11-04")["entries"]), 4)

    def test_upload_twice_adds_once(self):
        first = self.post("/api/upload", {"entries": [entry()]})[1]
        second = self.post("/api/upload", {"entries": [entry()]})[1]
        self.assertEqual((first["added"], second["added"]), (1, 0))
        self.assertEqual(len(self.get("/api/day?day=2026-11-04")["entries"]), 1)

    def test_deleted_stays_deleted_after_new_upload(self):
        self.post("/api/upload", {"entries": [entry()]})
        entry_key = self.get("/api/day?day=2026-11-04")["entries"][0]["id"]
        self.assertEqual(self.post("/api/delete", {"id": entry_key})[0], 200)
        self.post("/api/upload", {"entries": [entry(contact="New Name-Work")]})
        row = self.get("/api/day?day=2026-11-04")["entries"][0]
        self.assertIsNotNone(row["deleted_at"])
        day = self.get("/api/range?from=2026-11-04&to=2026-11-04")["days"]["2026-11-04"]
        self.assertEqual(day, {"call_s": 0, "calls": 0, "unanswered": 0, "texts": 0, "deleted": 1})

    def test_restore_counts_again(self):
        self.post("/api/upload", {"entries": [entry()]})
        entry_key = self.get("/api/day?day=2026-11-04")["entries"][0]["id"]
        self.post("/api/delete", {"id": entry_key})
        self.post("/api/restore", {"id": entry_key})
        self.assertEqual(self.get("/api/range?from=2026-11-04&to=2026-11-04")["days"]["2026-11-04"]["call_s"], 60)

    def test_time_zone_change_makes_no_copy_and_keeps_the_day(self):
        self.post("/api/upload", {"entries": [entry(at="2026-11-04 23:30:00")]})
        result = self.post("/api/upload", {"entries": [entry(at="2026-11-05 02:30:00")]})[1]
        self.assertEqual(result["added"], 0)
        self.assertEqual(len(self.get("/api/day?day=2026-11-04")["entries"]), 1)
        self.assertEqual(self.get("/api/day?day=2026-11-05")["entries"], [])

    def test_wrong_zone_reading_makes_no_copy_and_keeps_delete(self):
        self.post("/api/upload", {"entries": [entry()]})
        entry_key = self.get("/api/day?day=2026-11-04")["entries"][0]["id"]
        self.post("/api/delete", {"id": entry_key})
        result = self.post("/api/upload", {"entries": [entry(epoch=1793000000 + 8 * 3600)]})[1]
        self.assertEqual(result["added"], 0)
        rows = self.get("/api/day?day=2026-11-04")["entries"]
        self.assertEqual(len(rows), 1)
        self.assertIsNotNone(rows[0]["deleted_at"])

    def test_number_formats_are_one_entry(self):
        self.post("/api/upload", {"entries": [entry(number="+1 (555) 123-4567"), entry(number="5551234567")]})
        self.assertEqual(len(self.get("/api/day?day=2026-11-04")["entries"]), 1)

    def test_bad_upload_saves_nothing(self):
        for bad in (entry(kind="fax"), entry(direction="sideways"), entry(at="yesterday"), entry(at="2026-02-30 09:00:00"),
                    entry(epoch="1"), entry(epoch=True), entry(duration_s=-1), entry(duration_s=True), entry(contact=" "),
                    entry(number=""), "nope"):
            status, _ = self.post("/api/upload", {"entries": [entry(epoch=1793000999), bad]})
            self.assertEqual(status, 400, bad)
        self.assertEqual(self.get("/api/range?from=2026-01-01&to=2026-12-31")["days"], {})

    def test_text_has_no_duration(self):
        self.post("/api/upload", {"entries": [entry(kind="text", duration_s=500)]})
        self.assertEqual(self.get("/api/day?day=2026-11-04")["entries"][0]["duration_s"], 0)

    def test_extra_fields_are_not_stored(self):
        self.post("/api/upload", {"entries": [entry(kind="text", body=SECRET_WORDS)]})
        self.store.connect().close()
        for name in Path(self.temp).glob("t.db*"):
            self.assertNotIn(SECRET_WORDS.encode(), name.read_bytes())

    def test_other_site_is_refused(self):
        self.post("/api/upload", {"entries": [entry()]})
        entry_key = self.get("/api/day?day=2026-11-04")["entries"][0]["id"]
        status, _ = self.post("/api/delete", {"id": entry_key}, {"Origin": "https://evil.example"})
        self.assertEqual(status, 403)
        self.assertIsNone(self.get("/api/day?day=2026-11-04")["entries"][0]["deleted_at"])
        status, _ = self.post("/api/delete", {"id": entry_key}, {"Origin": self.base})
        self.assertEqual(status, 200)

    def test_plain_form_post_is_refused(self):
        status, _ = self.post("/api/delete", None, {"Content-Type": "text/plain"}, raw=b'{"id":"x"}')
        self.assertEqual(status, 400)

    def test_missing_id_and_bad_dates(self):
        self.assertEqual(self.post("/api/delete", {"id": "0" * 32})[0], 404)
        self.assertEqual(self.post("/api/delete", {"id": "../x"})[0], 400)
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.get("/api/day?day=2026-13-45")
        self.assertEqual(caught.exception.code, 400)
        caught.exception.close()

    def test_backup_is_readable_and_old_ones_go(self):
        self.post("/api/upload", {"entries": [entry()]})
        folder = Path(self.temp) / "backups"
        folder.mkdir()
        for n in range(20):
            (folder / f"calltally-2020-01-{n + 1:02d}.db").write_bytes(b"")
        target = self.store.backup(folder)
        self.assertEqual(sqlite3.connect(target).execute("SELECT COUNT(*) FROM entries").fetchone()[0], 1)
        self.assertEqual(len(list(folder.glob("calltally-*.db"))), calltally.BACKUPS_KEPT)

    def test_app_install_files_are_served(self):
        with urllib.request.urlopen(self.base + "/manifest.webmanifest") as reply:
            self.assertEqual(reply.headers.get_content_type(), "application/manifest+json")
            manifest = json.load(reply)
        self.assertEqual((manifest["name"], manifest["display"], manifest["start_url"]), ("Call Tally", "standalone", "/"))
        sizes = {icon["sizes"] for icon in manifest["icons"]}
        self.assertEqual(sizes, {"192x192", "512x512"})
        self.assertIn("maskable", {icon["purpose"] for icon in manifest["icons"]})
        for icon in manifest["icons"]:
            with urllib.request.urlopen(self.base + icon["src"]) as reply:
                self.assertEqual(reply.headers.get_content_type(), "image/png")
                self.assertEqual(reply.read(8), b"\x89PNG\r\n\x1a\n")
        with urllib.request.urlopen(self.base + "/") as reply:
            policy = reply.headers["Content-Security-Policy"]
        self.assertIn("manifest-src 'self'", policy)
        self.assertIn("img-src 'self'", policy)

    def test_only_the_icons_are_served_from_static(self):
        for path in ("/static/../calltally.py", "/static/", "/static/icon.png", "/data/calltally.db"):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(self.base + path)
            self.assertEqual(caught.exception.code, 404, path)
            caught.exception.close()

    def test_test_app_download(self):
        folder = Path(self.temp)
        with unittest.mock.patch.dict(os.environ, {"CALLTALLY_DATA": str(folder)}):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(self.base + "/phone/calltally-test.apk")
            self.assertEqual(caught.exception.code, 404)
            caught.exception.close()
            (folder / "calltally-test.apk").write_bytes(b"PK-test")
            with urllib.request.urlopen(self.base + "/phone/calltally-test.apk") as reply:
                self.assertEqual(reply.headers.get_content_type(), "application/vnd.android.package-archive")
                self.assertEqual(reply.read(), b"PK-test")

    def test_page_and_setup_are_served(self):
        with urllib.request.urlopen(self.base + "/") as reply:
            self.assertIn(b"Call Tally", reply.read())
        request = urllib.request.Request(self.base + "/phone/setup.sh", headers={"X-Forwarded-Proto": "https", "X-Forwarded-Host": "box.test:8444"})
        with urllib.request.urlopen(request) as reply:
            self.assertIn(b'BASE="https://box.test:8444"', reply.read())


class PhoneTests(ServerCase):
    def test_only_work_entries_and_never_the_words(self):
        entries = phone.build_entries(CONTACTS, CALLS, TEXTS)
        blob = json.dumps(entries)
        self.assertNotIn(SECRET_WORDS, blob)
        self.assertNotIn("Mom", blob)
        self.assertNotIn("Plumbing", blob)
        self.assertNotIn("body", blob)
        self.assertEqual(sum(e["kind"] == "call" for e in entries), 4)
        self.assertEqual(sum(e["kind"] == "text" for e in entries), 3)

    def test_renamed_contact_stops_uploading(self):
        calls = [{"name": "Bob-Work", "phone_number": "5554443333", "type": "INCOMING", "date": "2026-11-04 09:00:00", "duration": "01:00"}]
        self.assertEqual(phone.build_entries([{"name": "Bob", "number": "5554443333"}], calls, []), [])
        texts = [{"type": "inbox", "sender": "Bob", "address": "5554443333", "received": "2026-11-04 09:00:00", "body": "x"}]
        self.assertEqual(phone.build_entries([], calls, texts), [])

    def test_sender_that_is_only_a_number_is_not_a_current_name(self):
        calls = [{"name": "Dana-Work", "phone_number": "5550001111", "type": "OUTGOING", "date": "2026-11-04 09:00:00", "duration": "01:00"}]
        for sender in ("+15550001111", "", " "):
            texts = [{"type": "inbox", "sender": sender, "address": "5550001111", "received": "2026-11-04 09:05:00", "body": "x"}]
            self.assertEqual(len(phone.build_entries([], calls, texts)), 2, sender)

    def test_sent_text_name_you_is_not_a_current_name(self):
        calls = [{"name": "Dana-Work", "phone_number": "5550001111", "type": "OUTGOING", "date": "2026-11-04 09:00:00", "duration": "01:00"}]
        texts = [{"type": "sent", "sender": "You", "address": "5550001111", "received": "2026-11-04 09:05:00", "body": "x"}]
        self.assertEqual(len(phone.build_entries([], calls, texts)), 2)

    def test_durations(self):
        self.assertEqual(phone.parse_duration("14:32"), 872)
        self.assertEqual(phone.parse_duration("01:06:08"), 3968)
        self.assertEqual(phone.parse_duration("00:00"), 0)
        self.assertEqual(phone.parse_duration("junk"), 0)

    def test_work_names(self):
        for yes in ("Dana-Work", "Sam - Work", "Lee -Work ", "Kim- Work"):
            self.assertTrue(phone.is_work(yes), yes)
        for no in ("Work Plumbing", "Mom", "Dana-work", "Bets", "Dana-Work Jr", None):
            self.assertFalse(phone.is_work(no), no)

    def fake_termux(self, folder, name, output, code=0):
        path = folder / name
        path.write_text(f"#!/bin/sh\ncat <<'EOF'\n{output}\nEOF\nexit {code}\n")
        path.chmod(path.stat().st_mode | stat.S_IEXEC)

    def run_phone(self, contacts=CONTACTS, calls=CALLS, texts=TEXTS):
        folder = Path(tempfile.mkdtemp(dir=self.temp))
        self.fake_termux(folder, "termux-contact-list", json.dumps(contacts))
        self.fake_termux(folder, "termux-call-log", json.dumps(calls))
        self.fake_termux(folder, "termux-sms-list", json.dumps(texts))
        env = {**os.environ, "PATH": f"{folder}:{os.environ['PATH']}", "CALLTALLY_SERVER": self.base}
        return subprocess.run([sys.executable, str(PHONE_SCRIPT)], env=env, capture_output=True, text=True, timeout=60)

    def test_full_run_then_delete_survives_the_next_run(self):
        done = self.run_phone()
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("7 work entries found, 7 new", done.stdout)
        day = self.get("/api/range?from=2026-11-04&to=2026-11-04")["days"]["2026-11-04"]
        self.assertEqual(day, {"call_s": 872 + 3968, "calls": 2, "unanswered": 1, "texts": 2, "deleted": 0})
        rows = self.get("/api/day?day=2026-11-04")["entries"]
        self.assertEqual([r["at"] for r in rows], sorted(r["at"] for r in rows))
        target = next(r for r in rows if r["direction"] == "out" and r["kind"] == "call")
        self.post("/api/delete", {"id": target["id"]})
        again = self.run_phone()
        self.assertIn("7 work entries found, 0 new", again.stdout)
        self.assertEqual(self.get("/api/range?from=2026-11-04&to=2026-11-04")["days"]["2026-11-04"]["call_s"], 872)

    def test_refused_permission_uploads_nothing(self):
        done = self.run_phone(calls={"error": "Please grant the following permission: READ_CALL_LOG"})
        self.assertEqual(done.returncode, 1)
        self.assertIn("READ_CALL_LOG", done.stderr)
        self.assertIsNone(self.get("/api/range?from=2026-11-04&to=2026-11-04")["last_upload"])

    def test_no_reply_counts_as_a_failure(self):
        folder = Path(tempfile.mkdtemp(dir=self.temp))
        for name in ("termux-contact-list", "termux-call-log", "termux-sms-list"):
            path = folder / name
            path.write_text("#!/bin/sh\nexit 0\n")
            path.chmod(0o755)
        env = {**os.environ, "PATH": f"{folder}:{os.environ['PATH']}", "CALLTALLY_SERVER": self.base}
        done = subprocess.run([sys.executable, str(PHONE_SCRIPT)], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(done.returncode, 1)
        self.assertIn("permissions", done.stderr)
        self.assertIsNone(self.get("/api/range?from=2026-11-04&to=2026-11-04")["last_upload"])

    def test_no_work_entries_still_checks_in(self):
        done = self.run_phone(contacts=[], calls=[], texts=[])
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIsNotNone(self.get("/api/range?from=2026-11-04&to=2026-11-04")["last_upload"])

    def test_large_history_uploads_in_batches(self):
        calls = [{"name": "Dana-Work", "phone_number": "5551234567", "type": "INCOMING",
                  "date": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(1700000000 + i * 3600)), "duration": "01:00"}
                 for i in range(1234)]
        done = self.run_phone(contacts=[], calls=calls, texts=[])
        self.assertIn("1234 work entries found, 1234 new", done.stdout, done.stderr)


if __name__ == "__main__":
    unittest.main()
