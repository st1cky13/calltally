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
        self.assertEqual(self.get("/api/health"), {"ok": True, "version": "0.6.0"})


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

    def test_empty_upload_still_checks_in(self):
        # The app sends an empty upload when nothing is new, so the page can show the phone checked in.
        self.assertIsNone(self.status()[2])
        self.assertEqual(self.post("/api/upload", {"entries": []}), (200, {"received": 0, "added": 0}))
        self.assertRegex(self.status()[2], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
        self.assertEqual(self.get("/api/range?from=2026-01-01&to=2026-12-31")["days"], {})

    def test_upload_size_limit(self):
        many = [entry(number=f"555{i:07d}") for i in range(calltally.MAX_ENTRIES + 1)]
        self.assertEqual(self.post("/api/upload", {"entries": many})[0], 400)
        self.assertEqual(self.post("/api/upload", {"entries": many[:-1]}), (200, {"received": 2000, "added": 2000}))

    def test_record_is_off_unless_asked(self):
        target = Path(self.temp) / "record.txt"
        with unittest.mock.patch.dict(os.environ, {"CALLTALLY_RECORD": ""}):
            self.post("/api/upload", {"entries": [entry()]})
        self.assertFalse(target.exists())
        with unittest.mock.patch.dict(os.environ, {"CALLTALLY_RECORD": str(target)}):
            self.post("/api/upload", None, raw=b'{"entries":[]}')
            self.post("/api/status", None, raw=b'{"text":""}')
            self.post("/api/settings", {"pay_period_start": "2026-11-01"})
        self.assertEqual(target.read_bytes(), b'/api/upload {"entries":[]}\n/api/status {"text":""}\n')

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
    """The Android app's rules, id rule and record statements, run on a real JVM and compared with calltally.py."""
    TOOLS = Path.home() / "local" / "calltally-build" / "jdk" / "bin"
    _rules = None

    def run_rules(self):
        if not (self.TOOLS / "javac").exists():
            self.skipTest("no JDK")
        if AndroidRulesTests._rules is None:
            out = Path(self.temp) / "classes"
            source = ROOT / "android" / "app"
            app = source / "src" / "com" / "calltally" / "app"
            subprocess.run([str(self.TOOLS / "javac"), "-nowarn", "-d", str(out), str(app / "Rules.java"), str(app / "Ledger.java"),
                            str(app / "Sql.java"), str(app / "PayPeriod.java"), str(app / "Words.java"),
                            str(source / "check/RulesCheck.java")], check=True, capture_output=True)
            done = subprocess.run([str(self.TOOLS / "java"), "-cp", str(out), "com.calltally.app.RulesCheck"],
                                  check=True, capture_output=True, text=True, env={**os.environ, "TZ": "America/Los_Angeles"})
            AndroidRulesTests._rules = dict(line.split(" ", 1) for line in done.stdout.splitlines())
        return AndroidRulesTests._rules

    def test_who_counts(self):
        rules = self.run_rules()
        self.assertEqual(rules["one"], "Dana-Work | +1 (555) 123-4567")
        self.assertEqual(rules["personal"], "none")
        self.assertEqual(rules["mixed"], "Group: Dana-Work +2 | thread-3")
        self.assertEqual(rules["two-work"], 'Group: Dana-Work, Sam "Q" - Work | thread-4')
        self.assertEqual(rules["one-extra"], "Dana-Work | 5551234567")
        self.assertEqual(rules["acceptable"], "falsefalsefalsetrue")
        self.assertEqual(rules["seconds"], "1793000000 1793000000 1793000000")
        # Unticked, a name ending in -Work no longer counts: ticks alone decide.
        self.assertEqual((rules["unticked"], rules["unticked-call"], rules["unticked-text"]), ("none", "skip:personal", "skip:personal"))

    def fixture(self, rules=None):
        rules = rules or self.run_rules()
        return json.loads(rules["fixture-body"])["entries"], json.loads(rules["fixture-group"])["entries"]

    def test_app_gives_the_ids_the_termux_script_and_the_server_gave(self):
        rules = self.run_rules()
        sent, group = self.fixture(rules)
        server_ids = [calltally.entry_id(e["kind"], e["number"], e["epoch"], e["direction"]) for e in sent]
        self.assertEqual(json.loads(rules["fixture-ids"]), server_ids)
        self.assertEqual(sorted(server_ids), sorted(TERMUX_IDS))
        by_id = dict(TERMUX_UPLOAD)
        for key, e in zip(server_ids, sent):
            # Same moment and clock time too, so the second check for a repeat also matches.
            self.assertEqual((e["at"], e["epoch"], e["direction"]), (by_id[key]["at"], by_id[key]["epoch"], by_id[key]["direction"]))
        # A group of three or more is known by its conversation, so it can't match what the script sent for it.
        self.assertEqual(json.loads(rules["fixture-group-ids"]), [APP_GROUP_ID])
        self.assertEqual([calltally.entry_id(e["kind"], e["number"], e["epoch"], e["direction"]) for e in group], [APP_GROUP_ID])

    def test_pay_periods_cover_every_day_once(self):
        rules = self.run_rules()
        checked, wrong = rules["pay-check"].split()
        self.assertEqual((int(checked) > 13000, wrong), (True, "0"))
        self.assertEqual(rules["pay-now"], "0 2026-10-25 2026-11-07 | Pay period Oct 25 – Nov 7")
        self.assertEqual(rules["pay-next"], "Pay period Nov 8 – Nov 21 | Pay period Jan 3 – Jan 16, 2027 | Pay period Aug 31 – Sep 13, 2025")
        self.assertEqual(rules["pay-before"], "-1 2026-10-11 2026-10-24")
        self.assertEqual(rules["pay-describe"], "Pay periods start Sun Oct 25 and run two weeks | Pay periods start Sun Jan 3, 2027 and run one week")
        self.assertEqual(rules["pay-bad"], "null null null null")

    def test_conversations_keep_their_first_filing(self):
        rules = self.run_rules()
        chat = rules["filing-chat"].split(" ; ")
        # Dana's chat stays her chat, under the same id, when Mom is ticked too or instead; with nobody ticked it counts nothing.
        self.assertEqual(chat[:4], ["Dana-Work | 5551234567"] * 3 + ["personal"])
        self.assertEqual(chat[4], "Group: Dana-Work, Mom | thread-7")
        self.assertEqual(chat[5], "same-id true")
        self.assertEqual(rules["filing-group"], "Group: Dana-Work, Mom | thread-8 ; Group: Dana-Work +1 | thread-8")
        self.assertEqual(rules["filing-decided"], "1 0")

    def test_contact_with_a_new_key_keeps_its_person(self):
        self.assertEqual(self.run_rules()["moved"], "{old-dana=new-dana} {} {}")

    def test_search_looks_up_numbers_only_for_digits(self):
        self.assertEqual(self.run_rules()["search"].split(" | "),
                         ["null", "555", "5551234567", "1555123", "null", "9", "null", "null", "null", "null"])

    def test_record_keeps_conversation_filings(self):
        rules = self.run_rules()
        app = sqlite3.connect(Path(self.temp) / "app2.db")
        try:
            for statement in json.loads(rules["sql-schema"]):
                app.execute(statement)
            self.assertEqual([r[1] for r in app.execute("PRAGMA table_info(conversations)")], ["thread", "number", "contact"])
        finally:
            app.close()

    def test_screen_words(self):
        rules = self.run_rules()
        self.assertEqual(rules["words-clock"], "17:06 1:08:17 0:59 0:00 10:00:00")
        self.assertEqual(rules["words-full"], "19m 43s | 1h 09m 39s | 47s | 1h 00m 00s")
        self.assertEqual(rules["words-summary"], "19m 14s on 16 calls · 32 texts | 0s on 1 call · 1 text")
        self.assertEqual(rules["words-at"], "12:05 AM 12:30 PM 3:08 PM")
        self.assertEqual(rules["words-day"], "Tuesday, November 3, 2026 | Sun Oct 25 | Sun Jan 3, 2027")
        self.assertEqual(rules["words-status"].split(" | "), [
            "3:08 PM · outgoing call", "3:08 PM · outgoing call, no answer", "3:08 PM · missed call", "3:08 PM · declined call",
            "4:16 PM · text received", "4:16 PM · text sent", "11:50 PM · incoming call · ended 12:10 AM",
            "11:00 PM · incoming call · ended Jan 2, 2027, 12:00 AM"])
        self.assertEqual(rules["words-updated"],
                         "Not updated yet | Updated just now | Updated 4 min ago | Updated 3 hr ago | Updated 3 days ago | 0121")

    def test_record_adds_and_repeats_like_the_server(self):
        rules = self.run_rules()
        steps = json.loads(rules["ledger-steps"])
        rows = json.loads(rules["ledger-in"])["entries"]
        self.assertEqual(len(steps), 2 * len(rows))
        for i, raw in enumerate(rows):
            key, decision = steps[2 * i], steps[2 * i + 1]
            self.assertEqual(key, calltally.entry_id(raw["kind"], raw["number"], raw["epoch"], raw["direction"]), raw)
            try:
                calltally.clean_entry(raw)
            except calltally.BadRequest:
                self.assertEqual(decision, "unusable", raw)
                continue
            added = self.store.add([raw])["added"]
            self.assertEqual(decision, "added" if added else "repeat", raw)
        self.assertEqual(sorted(steps[1::2]), ["added"] * 7 + ["repeat"] * 4 + ["unusable"])
        conn = self.store.connect()
        try:
            kept = [dict(r) for r in conn.execute("SELECT * FROM entries ORDER BY rowid")]
        finally:
            conn.close()
        for row in kept:
            self.assertIsNone(row.pop("deleted_at"))
        self.assertEqual(json.loads(rules["ledger-rows"]), kept)
        for number, key in json.loads(rules["number-keys"]):
            self.assertEqual(key, calltally.number_key(number), repr(number))

    def test_same_second_counts_once(self):
        rules = self.run_rules()
        self.assertEqual(rules["same-second"], "true")
        # Added once, then a repeat; an entry outside the limits is left out.
        self.assertEqual(rules["same-second-added"], "1 1 1")

    def test_record_tables_match_the_server(self):
        rules = self.run_rules()
        app = sqlite3.connect(Path(self.temp) / "app.db")
        try:
            for statement in json.loads(rules["sql-schema"]):
                app.execute(statement)
            server = sqlite3.connect(Path(self.temp) / "t.db")
            try:
                for table in ("entries",):
                    self.assertEqual(app.execute(f"PRAGMA table_info({table})").fetchall(),
                                     server.execute(f"PRAGMA table_info({table})").fetchall())
                for index in ("entries_day", "entries_same"):
                    self.assertEqual(app.execute(f"PRAGMA index_info({index})").fetchall(),
                                     server.execute(f"PRAGMA index_info({index})").fetchall())
            finally:
                server.close()
            # No pay period until the first start or Options sets one, so its row stays hidden.
            self.assertEqual(dict(app.execute("SELECT key,value FROM settings")), {})
            self.assertEqual([r[1] for r in app.execute("PRAGMA table_info(people)")],
                             ["key", "name", "ticked", "on_phone", "first_ticked_at"])
            self.assertEqual([r[1] for r in app.execute("PRAGMA table_info(numbers)")], ["person_key", "number", "number_key"])
        finally:
            app.close()

    def test_record_questions_answer_like_the_server(self):
        rules = self.run_rules()
        sent, group = self.fixture(rules)
        self.store.add(sent + group)
        self.store.add([
            entry(duration_s=60),
            entry(epoch=1793000100, at="2026-11-04 09:01:40", direction="missed", duration_s=12),
            entry(epoch=1793000200, at="2026-11-04 09:03:20", kind="text", duration_s=0),
            entry(epoch=1793000300, at="2026-11-04 10:00:00", direction="out", duration_s=0),
            entry(epoch=1793000600, at="2026-11-04 12:00:00", contact="Gone-Work", number="5554445555", duration_s=900),
            entry(epoch=1790000000, at="2026-10-01 06:00:00", contact="Sam-Work", number="5552223333", duration_s=1000),
        ])
        for r in self.store.day("2026-11-04")["entries"]:
            if r["contact"] in ("Gone-Work", "Sam - Work"):
                self.store.set_deleted(r["id"], True)
        conn = sqlite3.connect(Path(self.temp) / "t.db")
        conn.row_factory = sqlite3.Row
        try:
            for start, end in (("2026-10-01", "2026-10-31"), ("2026-11-01", "2026-11-30"), ("2026-11-04", "2026-11-04")):
                days = {r["day"]: {k: r[k] for k in ("call_s", "calls", "unanswered", "texts", "ignored")}
                        for r in conn.execute(rules["sql-days"], (start, end))}
                server = self.store.range(start, end)["days"]
                self.assertEqual(days, {d: {**{k: v for k, v in t.items() if k != "deleted"}, "ignored": t["deleted"]}
                                        for d, t in server.items()})
                self.assertEqual(dict(conn.execute(rules["sql-totals-range"], (start, end)).fetchone()), self.store.totals(start, end))
                self.assertEqual([dict(r) for r in conn.execute(rules["sql-contacts-range"], (start, end))],
                                 self.store.contacts(start, end)["contacts"])
            self.assertEqual(dict(conn.execute(rules["sql-totals-all"]).fetchone()), self.store.totals())
            self.assertEqual([dict(r) for r in conn.execute(rules["sql-contacts-all"])], self.store.contacts()["contacts"])
            for day in ("2026-11-03", "2026-11-04", "2026-10-01"):
                got = [dict(r) for r in conn.execute(rules["sql-day"], (day,))]
                for r in got:
                    r.pop("epoch")
                self.assertEqual(got, self.store.day(day)["entries"])
            self.assertEqual(conn.execute(rules["sql-earliest"]).fetchone()[0], "2026-10-01")
            # Missed calls and ignored entries never add time; the day counts two connected calls before ignores.
            nov4 = dict(conn.execute(rules["sql-days"], ("2026-11-04", "2026-11-04")).fetchone())
            self.assertEqual(nov4, {"day": "2026-11-04", "call_s": 872 + 60, "calls": 2, "unanswered": 3, "texts": 5, "ignored": 2})
        finally:
            conn.close()

    def test_only_ticked_entries_and_never_the_words(self):
        sent, group = self.fixture()
        for e in sent + group:
            self.assertEqual(set(e), {"kind", "at", "epoch", "contact", "number", "direction", "duration_s"})
        blob = json.dumps(sent + group)
        for personal in ("Mom", "Plumbing", "5559998888", "5557776666", "body", SECRET_WORDS):
            self.assertNotIn(personal, blob)
        self.assertEqual((sum(e["kind"] == "call" for e in sent), sum(e["kind"] == "text" for e in sent)), (4, 4))
        # The call log's 15551234567 matches the contact saved as +1 (555) 123-4567.
        self.assertEqual(sent[0]["contact"], "Dana-Work")

    def test_rows_left_out_and_why(self):
        rules = self.run_rules()
        # Voicemail, blocked and answered elsewhere are another kind; missed and declined stay listed.
        self.assertEqual(rules["call-kinds"], "1=in 2=out 3=missed 4=skip:other-kind 5=rejected 6=skip:other-kind 7=skip:other-kind")
        self.assertEqual(rules["call-personal"], "skip:personal")
        # Drafts (3), outbox (4), failed (5) and queued (6) messages are never kept.
        self.assertEqual(rules["sms-boxes"], "1=in 2=out 3=skip:not-received-or-sent 4=skip:not-received-or-sent "
                                             "5=skip:not-received-or-sent 6=skip:not-received-or-sent")
        self.assertEqual(rules["sms-personal"], "skip:personal")
        self.assertEqual(rules["mms-boxes"], "1=in 2=out 3=skip:not-received-or-sent 4=skip:not-received-or-sent")
        self.assertEqual(rules["mms-kinds"], "-1=in 128=in 130=skip:not-a-message 132=in 134=skip:not-a-message "
                                             "135=skip:not-a-message 136=skip:not-a-message")
        self.assertEqual(rules["mms-unknown-thread"], "skip:people-unknown")
        # A missed call keeps its ringing time in the record, and it never counts.
        missed = json.loads(rules["call-missed"])
        self.assertEqual((missed["direction"], missed["duration_s"], missed["epoch"]), ("missed", 7, 1793000000))
        self.store.add([missed])
        day = self.store.range(missed["at"][:10], missed["at"][:10])["days"][missed["at"][:10]]
        self.assertEqual((day["call_s"], day["calls"], day["unanswered"]), (0, 0, 1))

    def test_people_table(self):
        rules = self.run_rules()
        # Every number of every ticked person, by its last ten digits; unticked people's numbers don't count.
        self.assertEqual(rules["counting"], "{5550001111=Dana-Work, 5551234567=Dana-Work, 5554443333=Old Name, 5556667777=no digits}")
        self.assertEqual(rules["counting-shared"], "{5551112222=On Phone}")
        self.assertEqual(rules["fixture-work"], "{5550001111=Dana-Work, 5551234567=Dana-Work, 5552223333=Sam - Work}")
        # An empty or failed read of the contacts changes nothing.
        known = ("dana=Dana-Work[(555) 123-4567;+1 555 000 1111]+ticked+on gone=Old Name[5554443333]+ticked+off "
                 "lee=Lee[555-777-0000]+unticked+on noname=[no digits;15556667777]+ticked+on")
        self.assertEqual((rules["refresh-null"], rules["refresh-empty"]), (known, known))
        # New names and numbers are taken, nothing is removed, ticks stay, and on_phone follows the read.
        self.assertEqual(rules["refresh"], "dana=Dana New-Work[(555) 123-4567;+1 555 000 1111;555-888-9999]+ticked+on "
                                           "gone=Old Name[5554443333]+ticked+on lee=Lee[555-777-0000]+unticked+off "
                                           "noname=[no digits;15556667777]+ticked+off")
        self.assertEqual(rules["join"], "[+1 555 123 4567, 555 000 1111]")

    def test_contacts_and_conversation_people(self):
        rules = self.run_rules()
        # A conversation with a recipient that can't be looked up, or none, is left out.
        self.assertEqual(rules["thread-people"], "{10=[+15551234567], 11=[+15551234567, 5559998888]}")
        # Without the list: the five newest messages of each conversation not already known.
        self.assertEqual(rules["fallback"], "{20=[1, 2, 3, 4, 5], 21=[10]}")
        self.assertEqual(rules["people-on-messages"], "[+15551234567, 5559998888]")
        self.assertEqual(rules["usable"], "truefalsefalsefalse")
        self.assertEqual((rules["groups"], rules["fixture-groups"]), ("1", "1"))
        # A picture message time stored in milliseconds is read as seconds.
        self.assertEqual(json.loads(rules["mms-millis"])["epoch"], 1793000000)


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
