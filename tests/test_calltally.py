"""Run with: python -m unittest discover -s tests (from the repo). Uses a real local server and, when the build tools
are present, the Android app's rules on a real JVM."""
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
import unittest.mock
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
import calltally  # noqa: E402

SECRET_WORDS = "the secret words of a text"
# What the old Termux script uploaded for its test calls and texts, recorded by running it once on 2026-11-04 before it
# was retired (see ~/local/archive/calltally-termux). Rows like these are in the live database. The app must give the
# same entry id for the same call or text, or a re-upload would add them twice.
TERMUX_UPLOAD = [
    ("9454935875ececdb4b48b590af16afab", {"kind": "call", "at": "2026-11-03 10:00:00", "epoch": 1793728800, "contact": "Dana-Work", "number": "5550001111", "direction": "out", "duration_s": 120}),
    ("0931a9b5fd4ddbc6280df13036f2ad7c", {"kind": "text", "at": "2026-11-03 10:05:00", "epoch": 1793729100, "contact": "Dana-Work", "number": "5550001111", "direction": "out", "duration_s": 0}),
    ("37146c6b6951da96f67ee3da54709b03", {"kind": "call", "at": "2026-11-04 09:12:00", "epoch": 1793812320, "contact": "Dana-Work", "number": "15551234567", "direction": "in", "duration_s": 872}),
    ("48dde50e35c1b79d8b24c47478df58b2", {"kind": "text", "at": "2026-11-04 09:40:00", "epoch": 1793814000, "contact": "Dana-Work", "number": "+15551234567", "direction": "in", "duration_s": 0}),
    ("deab11e0efda6505a9ca797a44306884", {"kind": "text", "at": "2026-11-04 09:41:00", "epoch": 1793814060, "contact": "Dana-Work", "number": "+15551234567", "direction": "out", "duration_s": 0}),
    ("2f92856e0196f426999b1b708834bfc3", {"kind": "call", "at": "2026-11-04 13:05:00", "epoch": 1793826300, "contact": "Sam - Work", "number": "5552223333", "direction": "out", "duration_s": 3968}),
    ("e9991aba09467e3768bb7281a02f41bd", {"kind": "call", "at": "2026-11-04 15:00:00", "epoch": 1793833200, "contact": "Dana-Work", "number": "5551234567", "direction": "missed", "duration_s": 0}),
    # A group message: the script saw only the sender's number, so it uploaded it as a text from Dana.
    ("9933ae32592a2c37b7b36f09288f743e", {"kind": "text", "at": "2026-11-04 16:00:00", "epoch": 1793836800, "contact": "Dana-Work", "number": "+15551234567", "direction": "in", "duration_s": 0}),
]
TERMUX_IDS = {key for key, _ in TERMUX_UPLOAD}
# The same message as the app sends it from a group with three other people: known by its conversation, so a new id.
APP_GROUP_ID = "2f807a60727693bb6c98bc098b86a193"


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

    def test_page_is_served_and_the_termux_files_are_gone(self):
        with urllib.request.urlopen(self.base + "/") as reply:
            self.assertIn(b"Call Tally", reply.read())
        for path in ("/phone/setup.sh", "/phone/calltally-phone.py"):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(self.base + path)
            self.assertEqual(caught.exception.code, 404, path)
            caught.exception.close()


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
        self.assertEqual(self.get("/api/health"), {"ok": True, "version": "0.5.2"})


class StatusTests(ServerCase):
    def status(self):
        reply = self.get("/api/range?from=2026-11-01&to=2026-11-30")
        return reply["phone_status"], reply["phone_status_at"], reply["last_upload"]

    def test_none_at_first(self):
        self.assertEqual(self.status(), (None, None, None))

    def test_set_shows_in_range_and_clear_removes_it(self):
        status, reply = self.post("/api/status", {"text": "No contacts ending in -Work were found."})
        self.assertEqual((status, reply), (200, {"ok": True}))
        text, at, _ = self.status()
        self.assertEqual(text, "No contacts ending in -Work were found.")
        self.assertRegex(at, r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
        self.assertEqual(self.post("/api/status", {"text": "Texts couldn't be read. Android returned no messages."})[0], 200)
        self.assertEqual(self.status()[0], "Texts couldn't be read. Android returned no messages.")
        self.assertEqual(self.post("/api/status", {"text": ""}), (200, {"ok": True}))
        self.assertEqual(self.status()[:2], (None, None))
        self.assertEqual(self.post("/api/status", {"text": "Oops."})[0], 200)
        self.assertEqual(self.post("/api/status", {"text": "   "})[0], 200)
        self.assertEqual(self.status()[:2], (None, None))

    def test_never_changes_last_upload(self):
        self.post("/api/status", {"text": "Android returned no messages."})
        self.post("/api/status", {"text": ""})
        self.assertIsNone(self.status()[2])
        self.post("/api/upload", {"entries": [entry()]})
        before = self.status()[2]
        self.assertIsNotNone(before)
        conn = self.store.connect()
        with conn:
            conn.execute("UPDATE meta SET value='2026-01-01T00:00:00Z' WHERE key='last_upload'")
        conn.close()
        self.post("/api/status", {"text": "Android returned no messages."})
        self.post("/api/status", {"text": ""})
        self.assertEqual(self.status()[2], "2026-01-01T00:00:00Z")

    def test_upload_leaves_status_alone(self):
        self.post("/api/status", {"text": "Android returned no messages."})
        self.post("/api/upload", {"entries": [entry()]})
        self.assertEqual(self.status()[0], "Android returned no messages.")

    def test_other_site_refused(self):
        status, _ = self.post("/api/status", {"text": "Hello."}, {"Origin": "https://evil.example"})
        self.assertEqual(status, 403)
        status, _ = self.post("/api/status", None, {"Content-Type": "text/plain"}, raw=b'{"text":"Hello."}')
        self.assertEqual(status, 400)
        self.assertIsNone(self.status()[0])

    def test_too_long_or_bad_text_refused(self):
        self.post("/api/status", {"text": "Kept."})
        for bad in ("x" * 201, "two\nlines", "two\rlines", "tab\there", None, 5, ["x"]):
            status, _ = self.post("/api/status", {"text": bad})
            self.assertEqual(status, 400, bad)
        status, _ = self.post("/api/status", {})
        self.assertEqual(status, 400)
        self.assertEqual(self.status()[0], "Kept.")
        self.assertEqual(self.post("/api/status", {"text": "x" * 200})[0], 200)
        self.assertEqual(self.status()[0], "x" * 200)


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
        self.assertEqual(rules["names-more"], "truetruefalsefalsefalse")
        self.assertEqual(rules["acceptable"], "falsefalsefalsetrue")
        self.assertEqual(rules["seconds"], "1793000000 1793000000 1793000000")
        # Bob-Work renamed to Bob: his number stops being a work number, so nothing of his is uploaded.
        self.assertEqual(rules["renamed"], "none")

    def test_status_line_is_one_line_and_accepted(self):
        rules = self.run_rules()
        self.assertEqual(json.loads(rules["status"]), {"text": 'Texts couldn\'t be read. Android returned "no" messages.'})
        self.assertEqual(json.loads(rules["status-empty"]), {"text": ""})
        self.assertEqual(len(json.loads(rules["status-long"])["text"]), 200)
        for key in ("status", "status-long"):
            status, _ = self.post("/api/status", None, raw=rules[key].encode())
            self.assertEqual(status, 200, key)
        self.assertEqual(len(self.get("/api/range?from=2026-11-01&to=2026-11-30")["phone_status"]), 200)
        self.assertEqual(self.post("/api/status", None, raw=rules["status-empty"].encode())[0], 200)
        self.assertIsNone(self.get("/api/range?from=2026-11-01&to=2026-11-30")["phone_status"])

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


    def fixture(self, rules=None):
        rules = rules or self.run_rules()
        return json.loads(rules["fixture-body"])["entries"], json.loads(rules["fixture-group"])["entries"]

    def test_app_gives_the_ids_the_termux_script_gave(self):
        sent, group = self.fixture()
        ids = [calltally.entry_id(e["kind"], e["number"], e["epoch"], e["direction"]) for e in sent]
        self.assertEqual(sorted(ids), sorted(TERMUX_IDS))
        by_id = dict(TERMUX_UPLOAD)
        for key, e in zip(ids, sent):
            # Same moment and clock time too, so the server's second check for a repeat also matches.
            self.assertEqual((e["at"], e["epoch"], e["direction"]), (by_id[key]["at"], by_id[key]["epoch"], by_id[key]["direction"]))
        # A group of three or more is known by its conversation, so it can't match what the script sent for it.
        self.assertEqual([calltally.entry_id(e["kind"], e["number"], e["epoch"], e["direction"]) for e in group], [APP_GROUP_ID])
        self.assertNotIn(APP_GROUP_ID, TERMUX_IDS)

    def test_app_upload_adds_nothing_over_termux_rows(self):
        rules = self.run_rules()
        self.post("/api/upload", {"entries": [e for _, e in TERMUX_UPLOAD]})
        status, result = self.post("/api/upload", None, raw=rules["fixture-body"].encode())
        self.assertEqual((status, result), (200, {"received": 8, "added": 0}))
        status, result = self.post("/api/upload", None, raw=rules["fixture-group"].encode())
        self.assertEqual((status, result), (200, {"received": 1, "added": 1}))
        ids = {r["id"] for day in ("2026-11-03", "2026-11-04") for r in self.get(f"/api/day?day={day}")["entries"]}
        self.assertEqual(ids, TERMUX_IDS | {APP_GROUP_ID})

    def test_only_work_entries_and_never_the_words(self):
        sent, group = self.fixture()
        for e in sent + group:
            self.assertEqual(set(e), {"kind", "at", "epoch", "contact", "number", "direction", "duration_s"})
        blob = json.dumps(sent + group)
        for personal in ("Mom", "Plumbing", "5559998888", "5557776666", "body", SECRET_WORDS):
            self.assertNotIn(personal, blob)
        self.assertEqual((sum(e["kind"] == "call" for e in sent), sum(e["kind"] == "text" for e in sent)), (4, 4))
        # The call log's 15551234567 matches the contact saved as +1 (555) 123-4567.
        self.assertEqual(sent[0]["contact"], "Dana-Work")

    def test_full_upload_counts_connected_calls_and_delete_survives_the_next(self):
        rules = self.run_rules()
        status, result = self.post("/api/upload", None, raw=rules["fixture-body"].encode())
        self.assertEqual((status, result), (200, {"received": 8, "added": 8}))
        day = self.get("/api/range?from=2026-11-04&to=2026-11-04")["days"]["2026-11-04"]
        self.assertEqual(day, {"call_s": 872 + 3968, "calls": 2, "unanswered": 1, "texts": 3, "deleted": 0})
        rows = self.get("/api/day?day=2026-11-04")["entries"]
        target = next(r for r in rows if r["direction"] == "out" and r["kind"] == "call")
        self.post("/api/delete", {"id": target["id"]})
        self.assertEqual(self.post("/api/upload", None, raw=rules["fixture-body"].encode())[1], {"received": 8, "added": 0})
        day = self.get("/api/range?from=2026-11-04&to=2026-11-04")["days"]["2026-11-04"]
        self.assertEqual((day["call_s"], day["deleted"]), (872, 1))
        self.assertIsNotNone(next(r for r in self.get("/api/day?day=2026-11-04")["entries"] if r["id"] == target["id"])["deleted_at"])


class TermuxIdTests(ServerCase):
    """Runs without the build tools: the server still gives the rows the Termux script uploaded the same ids."""

    def test_server_ids_unchanged(self):
        for key, e in TERMUX_UPLOAD:
            self.assertEqual(calltally.entry_id(e["kind"], e["number"], e["epoch"], e["direction"]), key)
        self.assertEqual(calltally.entry_id("text", "thread-4", 1793836800, "in"), APP_GROUP_ID)
        self.assertEqual(self.post("/api/upload", {"entries": [e for _, e in TERMUX_UPLOAD]})[1], {"received": 8, "added": 8})
        ids = {r["id"] for day in ("2026-11-03", "2026-11-04") for r in self.get(f"/api/day?day={day}")["entries"]}
        self.assertEqual(ids, TERMUX_IDS)


class TotalsToolTests(ServerCase):
    """tools/totals.py, the before-and-after check for an install, counts the same way as the page."""

    def test_matches_range_and_is_stable(self):
        self.post("/api/upload", {"entries": [
            entry(duration_s=60),
            entry(epoch=1793000100, at="2026-11-04 09:01:40", direction="missed", duration_s=12),
            entry(epoch=1793000200, at="2026-11-04 09:03:20", kind="text", duration_s=0),
            entry(epoch=1793000300, at="2026-11-04 10:00:00", direction="out", duration_s=0),
            entry(epoch=1793000600, at="2026-11-04 12:00:00", contact="Gone-Work", number="5554445555", duration_s=900),
            entry(epoch=1793000700, at="2026-11-04 12:30:00", contact="Gone-Work", number="5554445555", kind="text", duration_s=0),
            entry(epoch=1792900000, at="2026-11-03 06:00:00", duration_s=40),
            entry(epoch=1790000000, at="2026-10-01 06:00:00", contact="Sam-Work", number="5552223333", duration_s=1000),
            entry(epoch=1790000100, at="2026-10-31 23:59:59", contact="Sam-Work", number="5552223333", kind="text", duration_s=0),
        ]})
        for r in self.get("/api/day?day=2026-11-04")["entries"]:
            if r["contact"] == "Gone-Work":
                self.post("/api/delete", {"id": r["id"]})
        tool = ROOT / "tools" / "totals.py"
        database = Path(self.temp) / "t.db"
        before = database.read_bytes()
        runs = [subprocess.run([sys.executable, str(tool), str(database)], capture_output=True, text=True, check=True).stdout
                for _ in range(2)]
        self.assertEqual(runs[0], runs[1])
        self.assertEqual(database.read_bytes(), before)
        lines = runs[0].splitlines()
        self.assertEqual(lines[-1], "all rows=9 latest=2026-11-04 12:30:00")
        expected = []
        for month, last in (("2026-10", "31"), ("2026-11", "30")):
            days = self.get(f"/api/range?from={month}-01&to={month}-{last}")["days"].values()
            total = {k: sum(d[k] for d in days) for k in ("call_s", "calls", "unanswered", "texts", "deleted")}
            rows = sum(len(self.get(f"/api/day?day={month}-{n:02d}")["entries"]) for n in range(1, int(last) + 1))
            expected.append(f"{month} call_s={total['call_s']} calls={total['calls']} texts={total['texts']}"
                            f" deleted={total['deleted']} rows={rows}")
        self.assertEqual(lines[:-1], expected)
        self.assertEqual(lines[:-1], ["2026-10 call_s=1000 calls=1 texts=1 deleted=0 rows=2",
                                      "2026-11 call_s=100 calls=2 texts=1 deleted=2 rows=7"])

    def test_missing_database(self):
        done = subprocess.run([sys.executable, str(ROOT / "tools" / "totals.py"), str(Path(self.temp) / "none.db")],
                              capture_output=True, text=True)
        self.assertEqual((done.returncode, done.stdout), (1, ""))
        self.assertIn("no database", done.stderr)
        self.assertFalse((Path(self.temp) / "none.db").exists())


if __name__ == "__main__":
    unittest.main()
