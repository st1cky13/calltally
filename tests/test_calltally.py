"""Run with: python -m unittest discover -s tests (from the repo). Checks the Android app's rules on a real JVM, when the
build tools are present, against tests/reference.py: the id rule, repeat rule and counting kept from the retired server."""
import json
import os
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path

import reference

ROOT = Path(__file__).resolve().parent.parent

SECRET_WORDS = "the secret words of a text"
# What the old Termux script uploaded for its test calls and texts, recorded by running it once on 2026-11-04 before it
# was retired (see ~/local/archive/calltally-termux). Rows like these were in the server's database. The app gives
# the same entry id for the same call or text, by the rule tests/reference.py keeps.
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
# Each row the server stored when the Termux script ran once, with its id (see that file's header).
RECORDED_IDS = Path.home() / "local" / "archive" / "calltally-termux" / "recorded-ids.txt"


def entry(**changes):
    base = {"kind": "call", "at": "2026-11-04 09:00:00", "epoch": 1793000000, "contact": "Dana-Work",
            "number": "5551234567", "direction": "in", "duration_s": 60}
    base.update(changes)
    return base


class RecordCase(unittest.TestCase):
    """A fresh reference record in a temporary folder."""

    def setUp(self):
        self.temp = tempfile.mkdtemp(dir=os.environ.get("TMPDIR"))
        self.addCleanup(shutil.rmtree, self.temp)
        self.store = reference.Store(Path(self.temp) / "t.db")

    def ignore(self, entry_key):
        """Marks one entry ignored, as the server's delete did."""
        conn = self.store.connect()
        try:
            with conn:
                changed = conn.execute("UPDATE entries SET deleted_at='2026-11-05T00:00:00Z' WHERE id=?", (entry_key,)).rowcount
        finally:
            conn.close()
        self.assertEqual(changed, 1)


class AndroidRulesTests(RecordCase):
    """The Android app's rules, id rule and record statements, run on a real JVM and compared with tests/reference.py."""
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
                            str(app / "Sql.java"), str(app / "PayPeriod.java"), str(app / "Words.java"), str(app / "PhoneCheck.java"),
                            str(app / "Passthrough.java"), str(source / "check/RulesCheck.java")], check=True, capture_output=True)
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
        server_ids = [reference.entry_id(e["kind"], e["number"], e["epoch"], e["direction"]) for e in sent]
        self.assertEqual(json.loads(rules["fixture-ids"]), server_ids)
        self.assertEqual(sorted(server_ids), sorted(TERMUX_IDS))
        by_id = dict(TERMUX_UPLOAD)
        for key, e in zip(server_ids, sent):
            # Same moment and clock time too, so the second check for a repeat also matches.
            self.assertEqual((e["at"], e["epoch"], e["direction"]), (by_id[key]["at"], by_id[key]["epoch"], by_id[key]["direction"]))
        # A group of three or more is known by its conversation, so it can't match what the script sent for it.
        self.assertEqual(json.loads(rules["fixture-group-ids"]), [APP_GROUP_ID])
        self.assertEqual([reference.entry_id(e["kind"], e["number"], e["epoch"], e["direction"]) for e in group], [APP_GROUP_ID])

    def test_pay_periods_cover_every_day_once(self):
        rules = self.run_rules()
        checked, wrong = rules["pay-check"].split()
        self.assertEqual((int(checked) > 13000, wrong), (True, "0"))
        self.assertEqual(rules["pay-now"], "0 2026-10-25 2026-11-07 | Pay period Oct 25 – Nov 7")
        self.assertEqual(rules["pay-next"], "Pay period Nov 8 – Nov 21 | Pay period Jan 3 – Jan 16, 2027 | Pay period Aug 31 – Sep 13, 2025")
        self.assertEqual(rules["pay-before"], "-1 2026-10-11 2026-10-24")
        # The current period's dates, as the pay period screen previews them: a start on a period boundary gives the same.
        self.assertEqual(rules["pay-current"], "Oct 25 – Nov 7 | Oct 25 – Nov 7 | Oct 24 – Nov 6 | Dec 27, 2026 – Jan 9, 2027")
        # The first period from a start and its length, as the pay period screen names a pick, with the year when not this year.
        self.assertEqual(rules["pay-span"], "Oct 25 – Nov 7, 14 days | Dec 28, 2026 – Jan 10, 2027, 14 days | Nov 28 – Nov 28, 1 day"
                         " | Pay periods start Sun Oct 25 and run 10 days")
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

    def test_own_number_is_learned_and_left_out(self):
        """Your own number is learned only from the sender row of a picture message you sent, kept with any learned before,
        and left out of a conversation's people, unless it is a ticked person's number."""
        rules = self.run_rules()
        # A sent message's sender is you; a received message, a placeholder and an email teach nothing.
        self.assertEqual(rules["own-learn"], "[5550009999] [] [] []")
        # Sent messages are asked newest first, never a received one, until twenty had a usable sender row; placeholder
        # rows don't count toward the twenty, and no more than sixty are asked in all.
        self.assertEqual(rules["own-asked"], "20 100 119 [] | 39 200 238 [] | 60 300 359 []")
        # The record keeps every number it learned, never fewer.
        self.assertEqual(rules["own-keep"], "5550009999 | 2065550199 5550009999 | 5550009999 | [] [] [2065550199, 5550009999]")
        self.assertEqual(rules["own-chat"], "Dana-Work | 5551234567")
        self.assertEqual(rules["own-pair"], "Dana-Work | 5551234567")
        self.assertEqual(rules["own-group"], "Group: Dana-Work +2 | thread-42")
        self.assertEqual(rules["own-without"], "Group: Dana-Work +3 | thread-42")
        # A conversation filed before keeps its identity; a group's label leaves your number out.
        self.assertEqual(rules["own-filed-group"], "Group: Dana-Work +1 | thread-45")
        self.assertEqual(rules["own-filed-chat"], "Dana-Work | 5551234567")
        self.assertEqual(rules["own-ticked"], "Group: Dana-Work, Me +1 | thread-43")
        self.assertEqual(rules["own-texts"], "Group: Dana-Work +2 / thread-44 / out")
        self.assertEqual(rules["phone-own"], "Your numbers: none learned yet | Your numbers: 2065550199, 5550009999")

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

    def test_phone_check_rows(self):
        rules = self.run_rules()
        self.assertEqual(rules["phone-when"], "Oct 26, 5:45 AM | Oct 26, 12:30 PM")
        self.assertEqual(rules["phone-top"], "Android 15, Google Messages not installed, default messaging app com.google.android.apps.messaging")
        self.assertEqual(rules["phone-counts"], "12 chats on the phone, 1 text row, picture and group rows couldn't be read | Conversation list: not readable")
        self.assertEqual(rules["phone-chat"], "chat 1003 · Dana-Work, Mom, 5557776666 · ids 3 +15551234567, 4 5559998888, 5 no address"
                         " · left out of the conversation list by the app · 1 message · newest Oct 26, 5:45 AM · filed as Group: Dana-Work +2")
        self.assertEqual(rules["phone-unknown"], "chat 1004 · people unknown to the conversation list, its messages name nobody"
                         " · no row in the conversation list · 0 messages · filed as not counted")
        self.assertEqual(rules["phone-text"], "Oct 26, 5:45 AM · received · text row · chat 1002 · address +15551234567 · creator unknown"
                         " · protocol 0 · counts under Dana-Work, by the row's address")
        self.assertEqual(rules["phone-picture"], "Oct 26, 5:45 AM · received · picture or group row [132] · chat 1005 · no addresses on the row"
                         " · creator com.google.android.apps.messaging · counts nothing: no people found, by the messages' addresses")
        self.assertEqual(rules["phone-problem"], "Text rows couldn't be read: IllegalArgumentException, Invalid column creator"
                         " | The conversation list couldn't be read: NullPointerException")
        self.assertEqual(rules["phone-other"].split(" | "), [
            "Oct 26, 5:45 AM · other [3] · text row · chat 1002 · address 5557776666 · creator unknown · skipped: not received or sent",
            "from +15551234567, to insert-address-token", "by the conversation list", "by the remembered filing", "null"])

    def test_catch_up_passthrough_pieces(self):
        rules = self.run_rules()
        # The row a text arriving during the brief window is written as: unread (read=0, seen=0), passed through, no sub.
        self.assertEqual(rules["passthrough-row"],
                         "address=+15550009999 body=hello during catch up date=1793000000000 date_sent=1793000000500"
                         " read=0 seen=0 thread_id=42")
        # A known SIM is kept; it is left out when below zero.
        self.assertEqual(rules["passthrough-sub"], "3 false")
        self.assertEqual(rules["passthrough-added"], "No new calls or texts. | 1 new call or text added. | 5 new calls and texts added.")
        self.assertEqual(rules["passthrough-last"], "Not yet | Last caught up: Oct 26, 12:33 AM")

    def test_catch_up_result_wording(self):
        # The read result names the total found and the number new, so a "0 new" is not read as a failure. No parentheses.
        rules = self.run_rules()
        self.assertEqual(rules["passthrough-result"],
                         "Read while Call Tally was the messaging app. 0 calls and texts counted, 0 new since the last run."
                         " | Read while Call Tally was the messaging app. 12 calls and texts counted, 3 new since the last run.")

    def test_screen_words(self):
        rules = self.run_rules()
        self.assertEqual(rules["words-clock"], "17:06 1:08:17 0:59 0:00 10:00:00")
        self.assertEqual(rules["words-full"], "19m 43s | 1h 09m 39s | 47s | 1h 00m 00s")
        self.assertEqual(rules["words-counts"], "5 calls · 9 texts | 1 call · 1 text")
        # 0.9.12: time per text, a rule the owner sets, shown apart from call time; texts x seconds in the full form.
        self.assertEqual(rules["words-texttime"], "2m 15s | 1m 00s | 0s | 0s | 1h 40m 00s")
        self.assertEqual(rules["words-textseconds"], "0 15 30 0 0 | off | 1 second | 15 seconds")
        self.assertEqual(rules["words-at"], "12:05 AM 12:30 PM 3:08 PM")
        self.assertEqual(rules["words-day"], "Tuesday, November 3, 2026 | Sun Oct 25 | Sun Jan 3, 2027")
        self.assertEqual(rules["words-status"].split(" | "), [
            "3:08 PM · outgoing call", "3:08 PM · outgoing call, no answer", "3:08 PM · missed call", "3:08 PM · declined call",
            "4:16 PM · text received", "4:16 PM · text sent", "11:50 PM · incoming call · ended 12:10 AM",
            "11:00 PM · incoming call · ended Jan 2, 2027, 12:00 AM"])
        self.assertEqual(rules["words-updated"],
                         "Not updated yet | Updated just now | Updated 4 min ago | Updated 3 hr ago | Updated 3 days ago | 0121")

    def test_record_adds_and_repeats_like_the_reference(self):
        rules = self.run_rules()
        steps = json.loads(rules["ledger-steps"])
        rows = json.loads(rules["ledger-in"])["entries"]
        self.assertEqual(len(steps), 2 * len(rows))
        for i, raw in enumerate(rows):
            key, decision = steps[2 * i], steps[2 * i + 1]
            self.assertEqual(key, reference.entry_id(raw["kind"], raw["number"], raw["epoch"], raw["direction"]), raw)
            try:
                reference.clean_entry(raw)
            except reference.BadEntry:
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
            self.assertEqual(key, reference.number_key(number), repr(number))

    def test_same_second_counts_once(self):
        rules = self.run_rules()
        self.assertEqual(rules["same-second"], "true")
        # Added once, then a repeat; an entry outside the limits is left out.
        self.assertEqual(rules["same-second-added"], "1 1 1")

    def test_record_tables_match_the_reference(self):
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

    def test_record_questions_answer_like_the_reference(self):
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
                self.ignore(r["id"])
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

    def test_rcs_chats(self):
        """An RCS chat is listed under its chat id, not its people's numbers: it is left out of the conversation list and
        its messages' address rows name its people, the way Google Messages' RCS chats looked on the user's phone."""
        rules = self.run_rules()
        rcs_id = "d4vtcmrqheztgmjwge4tah3emi2dondfga4dmzdgmi2gizbumfrtoodcmy3gmyrtgfswintgmm======@rcs.google.com"
        own = "+12065550111"
        # Phone-number recipients are unchanged; an RCS id, an email, an empty address or a missing one leaves the chat out.
        self.assertEqual(rules["rcs-thread-people"], "{10=[+15551234567], 11=[+15551234567, 5559998888]}")
        self.assertEqual(rules["rcs-phone"], "truefalsefalsefalsefalsefalse")
        self.assertEqual(rules["rcs-people"], "{10=[+15551234567], 11=[+15551234567, 5559998888], "
                                              f"919=[{own}, +15551234567, 5552223333, 5557776666], 930=[{own}, +15551234567], "
                                              f"931=[{own}, 5559998888], 932=[]}}")
        # One message naming two or more addresses is enough; a message naming fewer lets the next one be read.
        self.assertEqual(rules["rcs-asked"], "[1, 2, 4, 6, 5]")
        self.assertEqual(rules["rcs-texts"].split(" | "), [
            'Group: Dana-Work, Sam - Work +2 / thread-919 / in', 'Group: Dana-Work, Sam - Work +2 / thread-919 / out',
            "Dana-Work / +15551234567 / in", "Dana-Work / +15551234567 / in", "Dana-Work / 5551234567 / in", "Dana-Work / 5551234567 / in",
            "skip:personal", "Dana-Work / 5551234567 / in", "Dana-Work / +15551234567 / in"])
        self.assertEqual(rules["rcs-decided"], "[11, 919, 930] groups 1")
        self.assertEqual(rules["rcs-ids"], f"5 {rcs_id} [address is not a phone number] | truefalsetrue | "
                                           "1 +15551234567, 2 5559998888, 3 no address")
        self.assertEqual(rules["rcs-chat"], f"chat 919 · people unknown to the conversation list, its messages name {own}, Dana-Work, "
                                            f"5552223333, 5557776666 · ids 5 {rcs_id} [address is not a phone number] · left out of "
                                            "the conversation list by the app · 2 messages · filed as Group: Dana-Work +3")


class ReferenceTests(RecordCase):
    """Runs without the build tools: tests/reference.py gives the values calltally.py gave."""

    def test_termux_ids_unchanged(self):
        for key, e in TERMUX_UPLOAD:
            self.assertEqual(reference.entry_id(e["kind"], e["number"], e["epoch"], e["direction"]), key)
        self.assertEqual(reference.entry_id("text", "thread-4", 1793836800, "in"), APP_GROUP_ID)
        self.assertEqual(self.store.add([e for _, e in TERMUX_UPLOAD]), {"received": 8, "added": 8})
        ids = {r["id"] for day in ("2026-11-03", "2026-11-04") for r in self.store.day(day)["entries"]}
        self.assertEqual(ids, TERMUX_IDS)

    def test_recorded_rows_are_kept_as_recorded(self):
        if not RECORDED_IDS.is_file():
            self.skipTest(f"no {RECORDED_IDS}")
        recorded = [json.loads(line) for line in RECORDED_IDS.read_text().splitlines() if line.startswith("{")]
        self.assertEqual([(r["id"], {k: v for k, v in r.items() if k != "id"}) for r in recorded], TERMUX_UPLOAD)
        self.store.add([{k: v for k, v in r.items() if k != "id"} for r in recorded])
        columns = list(recorded[0])
        conn = self.store.connect()
        try:
            kept = [dict(r) for r in conn.execute(f"SELECT {','.join(columns)} FROM entries ORDER BY rowid")]
        finally:
            conn.close()
        self.assertEqual(kept, recorded)

    def test_counting_pinned(self):
        """The numbers calltally.py's tests pinned for its range, totals and contacts, from the same entries."""
        self.store.add([
            entry(duration_s=60),
            entry(epoch=1793000100, at="2026-11-04 09:01:40", direction="missed", duration_s=12),
            entry(epoch=1793000200, at="2026-11-04 09:03:20", kind="text", duration_s=0),
            entry(epoch=1793000300, at="2026-11-04 10:00:00", contact="Sam-Work", number="5552223333", duration_s=300),
            entry(epoch=1793000400, at="2026-11-04 11:00:00", contact="Lee-Work", number="5553334444", kind="text", duration_s=0),
            entry(epoch=1793000500, at="2026-11-04 11:01:00", contact="Lee-Work", number="5553334444", kind="text", duration_s=0),
            entry(epoch=1793000600, at="2026-11-04 12:00:00", contact="Gone-Work", number="5554445555", duration_s=900),
            entry(epoch=1792900000, at="2026-11-03 06:00:00", duration_s=40),
            entry(epoch=1790000000, at="2026-10-01 06:00:00", contact="Sam-Work", number="5552223333", duration_s=1000),
        ])
        self.ignore(next(r["id"] for r in self.store.day("2026-11-04")["entries"] if r["contact"] == "Gone-Work"))
        self.assertEqual(self.store.totals("2026-11-04", "2026-11-04"), {"call_s": 360, "calls": 2, "texts": 3})
        self.assertEqual(self.store.totals("2026-11-01", "2026-11-30"), {"call_s": 400, "calls": 3, "texts": 3})
        self.assertEqual(self.store.totals(), {"call_s": 1400, "calls": 4, "texts": 3})
        self.assertEqual(self.store.totals("2026-12-01", "2026-12-31"), {"call_s": 0, "calls": 0, "texts": 0})
        self.assertEqual(self.store.contacts("2026-11-01", "2026-11-30")["contacts"], [
            {"contact": "Sam-Work", "call_s": 300, "calls": 1, "texts": 0},
            {"contact": "Dana-Work", "call_s": 100, "calls": 2, "texts": 1},
            {"contact": "Lee-Work", "call_s": 0, "calls": 0, "texts": 2},
        ])
        self.assertEqual([r["contact"] for r in self.store.contacts()["contacts"]], ["Sam-Work", "Dana-Work", "Lee-Work"])
        self.assertEqual(self.store.contacts()["contacts"][0]["call_s"], 1300)
        self.assertEqual(self.store.range("2026-11-04", "2026-11-04"),
                         {"days": {"2026-11-04": {"call_s": 360, "calls": 2, "unanswered": 1, "texts": 3, "deleted": 1}},
                          "earliest": "2026-10-01"})
        for start, end in (("2026-11-01", None), (None, "2026-11-30"), ("2026-02-30", "2026-03-01"), ("", "")):
            with self.assertRaises(reference.BadEntry):
                self.store.totals(start, end)

    def test_repeats_and_refusals(self):
        """calltally.py's rules for a repeat and for an entry it couldn't keep."""
        self.assertEqual(self.store.add([entry()]), {"received": 1, "added": 1})
        # The same moment read in another zone, and the same clock time read in the wrong zone, are both repeats.
        self.assertEqual(self.store.add([entry(at="2026-11-05 02:30:00"), entry(epoch=1793000000 + 8 * 3600)])["added"], 0)
        self.assertEqual(self.store.add([entry(number="+1 (555) 123-4567", contact="Dana New-Work")])["added"], 0)
        # A repeat only refreshes the name: an ignored entry stays ignored and keeps its first time.
        self.ignore(self.store.day("2026-11-04")["entries"][0]["id"])
        self.store.add([entry(contact="Dana Newer-Work")])
        self.assertEqual([(r["contact"], r["at"], r["deleted_at"] is not None) for r in self.store.day("2026-11-04")["entries"]],
                         [("Dana Newer-Work", "2026-11-04 09:00:00", True)])
        self.assertEqual(self.store.day("2026-11-05")["entries"], [])
        # A group does not match a short number with the same digits, and a text has no duration.
        self.assertEqual(self.store.add([entry(kind="text", duration_s=0, number="12", epoch=5),
                                         entry(kind="text", duration_s=500, number="thread-12", epoch=5)])["added"], 2)
        texts = [r for r in self.store.day("2026-11-04")["entries"] if r["kind"] == "text"]
        self.assertEqual([r["duration_s"] for r in texts], [0, 0])
        for bad in (entry(kind="fax"), entry(direction="sideways"), entry(at="yesterday"), entry(at="2026-02-30 09:00:00"),
                    entry(epoch="1"), entry(epoch=True), entry(duration_s=-1), entry(duration_s=True), entry(contact=" "),
                    entry(number=""), "nope"):
            with self.assertRaises(reference.BadEntry):
                self.store.add([entry(epoch=1793000999, at="2026-11-06 09:00:00"), bad])
        self.assertEqual(self.store.day("2026-11-06")["entries"], [])


if __name__ == "__main__":
    unittest.main()
