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

    def test_group_does_not_match_a_short_number(self):
        self.post("/api/upload", {"entries": [entry(kind="text", duration_s=0, number="12"), entry(kind="text", duration_s=0, number="thread-12", contact="Group: Dana-Work")]})
        self.assertEqual(len(self.get("/api/day?day=2026-11-04")["entries"]), 2)

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

    def test_app_download(self):
        folder = Path(self.temp)
        with unittest.mock.patch.dict(os.environ, {"CALLTALLY_DATA": str(folder)}):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(self.base + "/phone/calltally.apk")
            self.assertEqual(caught.exception.code, 404)
            caught.exception.close()
            (folder / "calltally.apk").write_bytes(b"PK-test")
            with urllib.request.urlopen(self.base + "/phone/calltally.apk") as reply:
                self.assertEqual(reply.headers.get_content_type(), "application/vnd.android.package-archive")
                self.assertEqual(reply.read(), b"PK-test")

    def test_page_and_setup_are_served(self):
        with urllib.request.urlopen(self.base + "/") as reply:
            self.assertIn(b"Call Tally", reply.read())
        request = urllib.request.Request(self.base + "/phone/setup.sh", headers={"X-Forwarded-Proto": "https", "X-Forwarded-Host": "box.test:8444"})
        with urllib.request.urlopen(request) as reply:
            self.assertIn(b'BASE="https://box.test:8444"', reply.read())


class TotalsTests(ServerCase):
    def load(self):
        self.post("/api/upload", {"entries": [
            entry(duration_s=60),
            entry(epoch=1793000100, at="2026-11-04 09:01:40", direction="missed", duration_s=12),
            entry(epoch=1793000200, at="2026-11-04 09:03:20", kind="text", duration_s=0),
            entry(epoch=1793000300, at="2026-11-04 10:00:00", contact="Sam-Work", number="5552223333", duration_s=300),
            entry(epoch=1793000400, at="2026-11-04 11:00:00", contact="Lee-Work", number="5553334444", kind="text", duration_s=0),
            entry(epoch=1793000500, at="2026-11-04 11:01:00", contact="Lee-Work", number="5553334444", kind="text", duration_s=0),
            entry(epoch=1793000600, at="2026-11-04 12:00:00", contact="Gone-Work", number="5554445555", duration_s=900),
            entry(epoch=1792900000, at="2026-11-03 06:00:00", duration_s=40),
            entry(epoch=1790000000, at="2026-10-01 06:00:00", contact="Sam-Work", number="5552223333", duration_s=1000),
        ]})
        gone = next(r for r in self.get("/api/day?day=2026-11-04")["entries"] if r["contact"] == "Gone-Work")
        self.post("/api/delete", {"id": gone["id"]})

    def test_totals_count_connected_calls_only_and_skip_deleted(self):
        self.load()
        self.assertEqual(self.get("/api/totals?from=2026-11-04&to=2026-11-04"), {"call_s": 360, "calls": 2, "texts": 3})
        self.assertEqual(self.get("/api/totals?from=2026-11-01&to=2026-11-30"), {"call_s": 400, "calls": 3, "texts": 3})
        self.assertEqual(self.get("/api/totals"), {"call_s": 1400, "calls": 4, "texts": 3})
        self.assertEqual(self.get("/api/totals?from=2026-12-01&to=2026-12-31"), {"call_s": 0, "calls": 0, "texts": 0})

    def test_contacts_grouped_and_sorted(self):
        self.load()
        rows = self.get("/api/contacts?from=2026-11-01&to=2026-11-30")["contacts"]
        self.assertEqual(rows, [
            {"contact": "Sam-Work", "call_s": 300, "calls": 1, "texts": 0},
            {"contact": "Dana-Work", "call_s": 100, "calls": 2, "texts": 1},
            {"contact": "Lee-Work", "call_s": 0, "calls": 0, "texts": 2},
        ])
        everything = self.get("/api/contacts")["contacts"]
        self.assertEqual([r["contact"] for r in everything], ["Sam-Work", "Dana-Work", "Lee-Work"])
        self.assertEqual(everything[0]["call_s"], 1300)
        self.assertEqual(self.get("/api/contacts?from=2026-10-01&to=2026-10-31")["contacts"],
                         [{"contact": "Sam-Work", "call_s": 1000, "calls": 1, "texts": 0}])

    def test_contacts_ties_go_by_texts_then_name(self):
        self.post("/api/upload", {"entries": [
            entry(contact="Bee-Work", number="5550000001", kind="text", duration_s=0),
            entry(epoch=1793000001, contact="Ann-Work", number="5550000002", kind="text", duration_s=0),
            entry(epoch=1793000002, contact="Cat-Work", number="5550000003", kind="text", duration_s=0),
            entry(epoch=1793000003, at="2026-11-04 09:00:03", contact="Cat-Work", number="5550000003", kind="text", duration_s=0),
        ]})
        rows = self.get("/api/contacts")["contacts"]
        self.assertEqual([r["contact"] for r in rows], ["Cat-Work", "Ann-Work", "Bee-Work"])

    def test_contact_with_only_unanswered_calls_is_left_out(self):
        self.post("/api/upload", {"entries": [
            entry(),
            entry(epoch=1793000100, contact="Missed-Work", number="5556667777", direction="missed", duration_s=12),
            entry(epoch=1793000200, contact="Missed-Work", number="5556667777", direction="out", duration_s=0),
        ]})
        self.assertEqual([r["contact"] for r in self.get("/api/contacts")["contacts"]], ["Dana-Work"])

    def test_renamed_contact_is_one_line(self):
        self.post("/api/upload", {"entries": [entry(), entry(epoch=1793000100, at="2026-11-04 09:01:40", duration_s=30)]})
        self.post("/api/upload", {"entries": [entry(contact="Dana New-Work"),
                                              entry(epoch=1793000100, at="2026-11-04 09:01:40", duration_s=30, contact="Dana New-Work")]})
        self.assertEqual(self.get("/api/contacts")["contacts"], [{"contact": "Dana New-Work", "call_s": 90, "calls": 2, "texts": 0}])

    def test_bad_dates_are_refused(self):
        for query in ("?from=2026-11-01", "?to=2026-11-30", "?from=2026-02-30&to=2026-03-01", "?from=&to=", "?from=x&to=y"):
            for path in ("/api/totals", "/api/contacts"):
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    self.get(path + query)
                self.assertEqual(caught.exception.code, 400, path + query)
                caught.exception.close()


class SettingsTests(ServerCase):
    def test_default(self):
        self.assertEqual(self.get("/api/settings"), {"pay_period_start": "2026-10-25", "pay_period_days": 14})

    def test_set_and_keep(self):
        status, reply = self.post("/api/settings", {"pay_period_start": "2026-11-01"})
        self.assertEqual((status, reply), (200, {"pay_period_start": "2026-11-01", "pay_period_days": 14}))
        self.assertEqual(self.get("/api/settings"), reply)
        again = calltally.Store(Path(self.temp) / "t.db")
        self.assertEqual(again.settings(), reply)

    def test_edge_years_allowed(self):
        for good in ("2000-01-01", "2100-12-31"):
            self.assertEqual(self.post("/api/settings", {"pay_period_start": good})[1]["pay_period_start"], good)

    def test_days_cannot_be_changed(self):
        status, reply = self.post("/api/settings", {"pay_period_start": "2026-11-01", "pay_period_days": 7})
        self.assertEqual((status, reply["pay_period_days"]), (200, 14))

    def test_bad_date_refused(self):
        for bad in ("2026-02-30", "11/01/2026", "", None, 20261101, "0001-01-01", "1999-12-31", "2101-01-01"):
            status, _ = self.post("/api/settings", {"pay_period_start": bad})
            self.assertEqual(status, 400, bad)
        self.assertEqual(self.get("/api/settings")["pay_period_start"], "2026-10-25")

    def test_other_site_and_plain_form_refused(self):
        status, _ = self.post("/api/settings", {"pay_period_start": "2026-11-01"}, {"Origin": "https://evil.example"})
        self.assertEqual(status, 403)
        status, _ = self.post("/api/settings", None, {"Content-Type": "text/plain"}, raw=b'{"pay_period_start":"2026-11-01"}')
        self.assertEqual(status, 400)
        self.assertEqual(self.get("/api/settings")["pay_period_start"], "2026-10-25")

    def test_health_reports_version(self):
        self.assertEqual(self.get("/api/health"), {"ok": True, "version": "0.3.0"})


class AndroidRulesTests(ServerCase):
    """The Android app's rules, run on a real JVM and sent to a real server."""
    TOOLS = Path.home() / "local" / "calltally-build" / "jdk" / "bin"

    def run_rules(self):
        if not (self.TOOLS / "javac").exists():
            self.skipTest("no JDK")
        out = Path(self.temp) / "classes"
        source = ROOT / "android" / "app"
        subprocess.run([str(self.TOOLS / "javac"), "-nowarn", "-d", str(out), str(source / "src/com/calltally/app/Rules.java"),
                        str(source / "check/RulesCheck.java")], check=True, capture_output=True)
        done = subprocess.run([str(self.TOOLS / "java"), "-cp", str(out), "com.calltally.app.RulesCheck"],
                              check=True, capture_output=True, text=True, env={**os.environ, "TZ": "America/Los_Angeles"})
        return dict(line.split(" ", 1) for line in done.stdout.splitlines())

    def test_who_counts(self):
        rules = self.run_rules()
        self.assertEqual(rules["one"], "Dana-Work | +1 (555) 123-4567")
        self.assertEqual(rules["personal"], "none")
        self.assertEqual(rules["mixed"], "Group: Dana-Work +2 | thread-3")
        self.assertEqual(rules["two-work"], 'Group: Dana-Work, Sam "Q" - Work | thread-4')
        self.assertEqual(rules["one-extra"], "Dana-Work | 5551234567")
        self.assertEqual(rules["names"], "truetruefalsefalsefalse")
        self.assertEqual(rules["acceptable"], "falsefalsefalsetrue")
        self.assertEqual(rules["seconds"], "1793000000 1793000000 1793000000")

    def test_upload_is_accepted_and_matches_earlier_termux_entries(self):
        rules = self.run_rules()
        # The same call as the Termux script would have uploaded, deleted before the app takes over.
        sent = json.loads(rules["body"])["entries"]
        self.assertEqual((sent[0]["epoch"], sent[1]["epoch"]), (1793000000, 1793000001))
        self.post("/api/upload", {"entries": [entry(epoch=sent[0]["epoch"], at=sent[0]["at"])]})
        day = sent[0]["at"][:10]
        first = self.get(f"/api/day?day={day}")["entries"][0]["id"]
        self.post("/api/delete", {"id": first})
        status, result = self.post("/api/upload", None, raw=rules["body"].encode())
        self.assertEqual(status, 200, result)
        self.assertEqual(result, {"received": 2, "added": 1})
        rows = self.get(f"/api/day?day={day}")["entries"]
        self.assertIsNotNone(next(r for r in rows if r["id"] == first)["deleted_at"])
        self.assertTrue(any(r["contact"] == 'Sam "Q" - Work' and r["number"] == "thread-4" for r in rows))


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
