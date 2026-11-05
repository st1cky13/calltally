#!/usr/bin/env python3
"""Checks the Android app end to end on the emulator, by reading the record it keeps on the phone.

Starts the emulator, builds and installs a test copy of the app (CALLTALLY_DEBUG=true: debuggable, with the test hook),
seeds contacts, calls and messages, ticks Dana-Work and Sam - Work through the test hook, runs the app and copies its record
off the emulator to check it with sqlite3 against what calltally.py would have kept. Then it unticks Dana, ticks her again
and deletes her contact, checking that nothing is ever lost, and takes the app's permissions away. Prints one pass or fail
line per check, exits 1 on any fail, and always stops the emulator and adb.

Usage: python3 tools/emulator_check.py [--out FOLDER]   (default: a new folder under $TMPDIR)
Needs the Android tools in ~/local/calltally-build (see android/app/build.sh) and the AVD "ct". Takes a few minutes.
"""
import argparse
import json
import os
import shlex
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ElementTree
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
import calltally  # noqa: E402

TOOLS = Path.home() / "local" / "calltally-build"
SDK = TOOLS / "android-sdk"
ADB = SDK / "platform-tools" / "adb"
EMULATOR = SDK / "emulator" / "emulator"
APP = "com.calltally.app"
DATABASE = "calltally.db"
PERMISSIONS = ("android.permission.READ_CALL_LOG", "android.permission.READ_SMS", "android.permission.READ_CONTACTS")
MARKER = "zebra marmalade 4417"
NOT_ALLOWED = "Needs permission. Open this app to allow it."
T = 1793000000  # 2026-10-26 in the morning, Pacific time. Every seeded time is fixed, so two runs keep the same rows.

DANA1, DANA2, SAM, MOM, PLUMBING = "+15551234567", "5550001111", "5552223333", "5559998888", "5557776666"
CONTACTS = [("Dana-Work", ["+1 (555) 123-4567", "555-000-1111"]), ("Sam - Work", ["555-222-3333"]), ("Mom", [MOM]),
            ("Work Plumbing", [PLUMBING])]
# number, seconds after T, call log type, duration. Types: 1 in, 2 out, 3 missed, 5 declined, 6 blocked, 7 answered elsewhere.
CALLS = [("15551234567", 0, 1, 872), (SAM, 100, 2, 3968), (DANA2, 200, 3, 7), (DANA1, 300, 5, 3), (DANA1, 400, 6, 0),
         (MOM, 500, 1, 600), (SAM, 600, 7, 45), (PLUMBING, 700, 2, 180)]
# address, milliseconds after T, SMS type (1 inbox, 2 sent, 3 draft).
TEXTS = [(DANA1, 1000_000, 1), (DANA1, 1060_000, 2), (MOM, 1100_000, 1), (DANA1, 1200_000, 3),
         (DANA2, 1300_100, 1), (DANA2, 1300_700, 1), (PLUMBING, 1400_000, 1)]


class Check:
    def __init__(self):
        self.failed = 0
        self.lines = []

    def __call__(self, name, ok, detail=""):
        line = f"{'pass' if ok else 'FAIL'}  {name}" + ("" if ok or not detail else f"  ({detail})")
        print(line, flush=True)
        self.lines.append(line)
        if not ok:
            self.failed += 1
        return ok


class Run:
    def __init__(self, out):
        self.out = out
        self.env = {**os.environ, "ANDROID_AVD_HOME": str(TOOLS / "avd"), "ANDROID_SDK_ROOT": str(SDK)}
        self.emulator = None
        self.pulls = 0

    # adb and the emulator

    def adb(self, *args, check=True, timeout=120, binary=False):
        done = subprocess.run([str(ADB), "-e", *args], capture_output=True, text=not binary, env=self.env, timeout=timeout)
        if check and done.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args[:3])} failed: {done.stderr or done.stdout!r}"[:400])
        return done.stdout

    def sh(self, *args):
        """Runs one command on the emulator, each argument quoted for its shell."""
        out = self.adb("shell", " ".join(shlex.quote(str(a)) for a in args))
        if "Error" in out or "Exception" in out:
            raise RuntimeError(f"{args[:4]}: {out.strip()[:300]}")
        return out

    def start_emulator(self):
        log = open(self.out / "emulator.log", "wb")
        # Fresh data every run, and never the software renderer: it crashes on this laptop.
        self.emulator = subprocess.Popen(
            [str(EMULATOR), "-avd", "ct", "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-wipe-data",
             "-gpu", "host", "-memory", "2048"], stdout=log, stderr=subprocess.STDOUT, env=self.env, start_new_session=True)
        deadline = time.time() + 300
        while time.time() < deadline:
            if self.emulator.poll() is not None:
                raise RuntimeError("the emulator stopped while starting; see emulator.log")
            done = subprocess.run([str(ADB), "-e", "shell", "getprop", "sys.boot_completed"], capture_output=True,
                                  text=True, env=self.env, timeout=30)
            if done.stdout.strip() == "1":
                break
            time.sleep(3)
        else:
            raise RuntimeError("the emulator didn't finish starting in 5 minutes")
        self.adb("root")
        time.sleep(2)
        self.adb("wait-for-device", timeout=60)
        while self.adb("shell", "id", check=False).find("uid=0") < 0:
            time.sleep(1)

    def stop_emulator(self):
        if self.emulator is None:
            return
        subprocess.run([str(ADB), "-e", "emu", "kill"], capture_output=True, env=self.env, timeout=30)
        try:
            self.emulator.wait(timeout=60)
        except subprocess.TimeoutExpired:
            os.killpg(self.emulator.pid, signal.SIGKILL)
            self.emulator.wait(timeout=30)
        # The emulator's helpers share its process group.
        try:
            os.killpg(self.emulator.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        self.emulator = None

    # the app and its data

    def build_and_install(self):
        env = {**os.environ, "CALLTALLY_DEBUG": "true", "CALLTALLY_OUT": "emu"}
        done = subprocess.run(["sh", str(ROOT / "android/app/build.sh")], capture_output=True, text=True, env=env, cwd=ROOT)
        (self.out / "build.log").write_text(done.stdout + done.stderr)
        if done.returncode != 0:
            raise RuntimeError("the test app didn't build; see build.log")
        apk = done.stdout.strip().splitlines()[-1]
        self.adb("install", "-r", apk, timeout=180)
        for permission in PERMISSIONS:
            self.sh("pm", "grant", APP, permission)

    def tick(self, numbers, untick=False):
        """Ticks (or unticks) the people who own these numbers through the test hook. Returns the hook's answer."""
        args = ["am", "broadcast", "--include-stopped-packages", "-a", f"{APP}.TEST_TICK", "-n", f"{APP}/.TestHook",
                "--esa", "numbers", ",".join(numbers)]
        if untick:
            args += ["--ez", "untick", "true"]
        out = self.sh(*args)
        return out.split('data="')[1].split('"')[0] if 'data="' in out else out.strip()

    def prefs(self):
        """The app's saved status: what its screen shows, and which run finished last."""
        raw = self.adb("exec-out", "run-as", APP, "cat", "shared_prefs/status.xml", check=False, binary=True)
        try:
            root = ElementTree.fromstring(raw)
        except ElementTree.ParseError:
            return {}
        return {item.get("name"): item.get("value", item.text) for item in root}

    def device_mark(self):
        """A moment on the emulator's clock after everything done so far. A run that started later saw all of it."""
        mark = (int(self.adb("shell", "date", "+%s").strip()) + 1) * 1000
        time.sleep(1.2)
        return mark

    def run_app(self, mark, start_screen=False, seconds=240):
        """Starts a run (by opening the app the first time, by forcing the periodic job after) and waits for a run that
        started after mark to finish. A run already going makes the forced one do nothing, so it is forced again."""
        deadline = time.time() + seconds
        forced = 0.0
        if start_screen:
            self.sh("am", "start", "-n", f"{APP}/.MainActivity")
            forced = time.time()
        while time.time() < deadline:
            if int(self.prefs().get("run_done") or 0) >= mark:
                return True
            if time.time() - forced > 30:
                self.sh("cmd", "jobscheduler", "run", "-f", APP, "1")
                forced = time.time()
            time.sleep(1)
        return False

    def record(self):
        """Copies the app's record off the emulator. Returns the folder holding the copy and every file beside it."""
        self.pulls += 1
        folder = self.out / f"record-{self.pulls}"
        if folder.exists():
            shutil.rmtree(folder)
        folder.mkdir()
        names = self.adb("exec-out", "run-as", APP, "ls", "databases").split()
        for name in names:
            data = self.adb("exec-out", "run-as", APP, "cat", f"databases/{name}", binary=True)
            (folder / name).write_bytes(data)
        return folder

    def last_id(self, uri):
        out = self.sh("content", "query", "--uri", uri, "--projection", "_id", "--sort", "_id DESC")
        return int(out.split("_id=")[1].split()[0].rstrip(","))

    def thread_for(self, *numbers):
        query = "&".join(f"recipient={n}" for n in numbers)
        out = self.sh("content", "query", "--uri", f"content://mms-sms/threadID?{query}")
        return int(out.split("_id=")[1].split()[0].rstrip(","))

    def seed(self):
        self.raw = {}
        for name, numbers in CONTACTS:
            self.sh("content", "insert", "--uri", "content://com.android.contacts/raw_contacts",
                    "--bind", "account_type:n:", "--bind", "account_name:n:")
            raw = self.last_id("content://com.android.contacts/raw_contacts")
            self.raw[name] = raw
            self.sh("content", "insert", "--uri", "content://com.android.contacts/data", "--bind", f"raw_contact_id:i:{raw}",
                    "--bind", "mimetype:s:vnd.android.cursor.item/name", "--bind", f"data1:s:{name}")
            for number in numbers:
                self.sh("content", "insert", "--uri", "content://com.android.contacts/data", "--bind", f"raw_contact_id:i:{raw}",
                        "--bind", "mimetype:s:vnd.android.cursor.item/phone_v2", "--bind", f"data1:s:{number}", "--bind", "data2:i:2")
        for number, offset, kind, duration in CALLS:
            self.call(number, offset, kind, duration)
        for address, millis, box in TEXTS:
            self.sh("content", "insert", "--uri", "content://sms", "--bind", f"address:s:{address}",
                    "--bind", f"date:l:{T * 1000 + millis}", "--bind", f"type:i:{box}", "--bind", "read:i:1",
                    "--bind", f"body:s:{MARKER} {box}")
        groups = {}
        for label, members, offset in (("group", (DANA1, MOM, PLUMBING), 2000), ("pair", (DANA1, MOM), 2100)):
            try:
                thread = self.thread_for(*members)
            except RuntimeError:
                continue
            self.sh("content", "insert", "--uri", "content://mms", "--bind", f"thread_id:l:{thread}",
                    "--bind", f"date:l:{T + offset}", "--bind", "msg_box:i:1", "--bind", "m_type:i:132", "--bind", "read:i:1",
                    "--bind", f"sub:s:{MARKER}")
            message = self.last_id("content://mms")
            for i, member in enumerate(members):
                self.sh("content", "insert", "--uri", f"content://mms/{message}/addr", "--bind", f"msg_id:l:{message}",
                        "--bind", f"address:s:{member}", "--bind", f"type:i:{137 if i == 0 else 151}", "--bind", "charset:i:106")
            groups[label] = thread
        return groups

    def call(self, number, offset, kind, duration):
        self.sh("content", "insert", "--uri", "content://call_log/calls", "--bind", f"number:s:{number}",
                "--bind", f"date:l:{(T + offset) * 1000}", "--bind", f"type:i:{kind}", "--bind", f"duration:l:{duration}")

    def delete_contact(self, name):
        # As a sync adapter, so the contact is gone at once rather than only marked for deletion.
        self.sh("content", "delete", "--uri", "content://com.android.contacts/raw_contacts?caller_is_syncadapter=true",
                "--where", f"_id={self.raw[name]}")
        return self.sh("content", "query", "--uri", "content://com.android.contacts/data/phones", "--projection", "data1")

    def screen_text(self):
        """The text on the app's screen, read from the screen itself."""
        for _ in range(3):
            self.adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
            raw = self.adb("exec-out", "cat", "/sdcard/ui.xml", check=False, binary=True)
            try:
                return " ".join(n.get("text", "") for n in ElementTree.fromstring(raw).iter("node"))
            except ElementTree.ParseError:
                time.sleep(2)
        return ""

    def device_zone(self):
        return self.adb("shell", "getprop", "persist.sys.timezone").strip() or "UTC"


def rows(folder, table="entries", order="epoch,id"):
    conn = sqlite3.connect(f"file:{folder / DATABASE}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    try:
        return [dict(r) for r in conn.execute(f"SELECT * FROM {table} ORDER BY {order}")]
    finally:
        conn.close()


def people(folder):
    """The people table with each person's number keys, by name."""
    numbers = {}
    for r in rows(folder, "numbers", "person_key,number_key"):
        numbers.setdefault(r["person_key"], set()).add(r["number_key"])
    return {p["name"]: {**p, "numbers": numbers.get(p["key"], set())} for p in rows(folder, "people", "key")}


def server_rows(want, temp):
    """What calltally.py's Store keeps for the same entries: its ids, its repeat rule, its columns."""
    store = calltally.Store(temp / "server.db")
    store.add(want)
    conn = store.connect()
    try:
        return [dict(r) for r in conn.execute("SELECT * FROM entries ORDER BY epoch,id")]
    finally:
        conn.close()


def counted(folder, day, temp):
    """One day counted by calltally.py's own range on a copy of the record."""
    copy = temp / f"count-{folder.name}.db"
    shutil.copyfile(folder / DATABASE, copy)
    return calltally.Store(copy).range(day, day)["days"].get(day, {})


def expected(groups, zone):
    """What the app must find, worked out by hand from the seed: one dict per entry, in no particular order."""
    tz = ZoneInfo(zone)

    def item(kind, number, epoch, direction, contact, duration=0):
        at = datetime.fromtimestamp(epoch, tz).strftime("%Y-%m-%d %H:%M:%S")
        return {"kind": kind, "at": at, "epoch": epoch, "contact": contact, "number": number, "direction": direction,
                "duration_s": duration}

    out = [
        item("call", "15551234567", T, "in", "Dana-Work", 872),
        item("call", SAM, T + 100, "out", "Sam - Work", 3968),
        item("call", DANA2, T + 200, "missed", "Dana-Work", 7),
        item("call", DANA1, T + 300, "rejected", "Dana-Work", 3),
        item("text", DANA1, T + 1000, "in", "Dana-Work"),
        item("text", DANA1, T + 1060, "out", "Dana-Work"),
        # Two messages in the same second: both are found, the record keeps one.
        item("text", DANA2, T + 1300, "in", "Dana-Work"),
        item("text", DANA2, T + 1300, "in", "Dana-Work"),
    ]
    if "group" in groups:
        out.append(item("text", f"thread-{groups['group']}", T + 2000, "in", "Group: Dana-Work +2"))
    if "pair" in groups:
        # A chat with one work contact and one other person shows under that contact.
        out.append(item("text", DANA1, T + 2100, "in", "Dana-Work"))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", help="folder for the logs and the copies of the record")
    args = parser.parse_args()
    out = Path(args.out) if args.out else Path(tempfile.mkdtemp(prefix="calltally-emu-", dir=os.environ.get("TMPDIR")))
    out.mkdir(parents=True, exist_ok=True)
    check = Check()
    run = Run(out)
    print(f"Logs and copies of the record: {out}", flush=True)
    try:
        run.start_emulator()
        check("emulator started", True)
        run.build_and_install()
        check("test app built, installed and allowed", True)
        groups = run.seed()
        check("contacts, calls, texts and group messages seeded", True)
        zone = run.device_zone()
        want = expected(groups, zone)
        answer = run.tick([DANA1, SAM])
        check("Dana-Work and Sam - Work ticked through the test hook", answer == "ticked 2", answer)

        # Run 1: everything allowed, Dana and Sam ticked.
        if not check("app ran", run.run_app(run.device_mark(), start_screen=True)):
            raise RuntimeError("no run finished")
        first = run.record()
        got = rows(first)
        need = server_rows(want, out)
        check("exactly the expected entries are in the record, as calltally.py would keep them", got == need,
              f"extra {[r for r in got if r not in need][:2]} missing {[r for r in need if r not in got][:2]}")
        check("the record's ids are the ones calltally.py makes",
              {r["id"] for r in got} == {calltally.entry_id(e["kind"], e["number"], e["epoch"], e["direction"]) for e in want})
        blob = json.dumps(got) + json.dumps(rows(first, "people", "key")) + json.dumps(rows(first, "numbers", "person_key"))
        check("nothing from Mom or Work Plumbing", all(s not in blob for s in ("Mom", "Plumbing", MOM, PLUMBING)))
        check("no draft kept", not any(r["epoch"] == T + 1200 for r in got))
        check("no blocked or answered-elsewhere call", not any(r["epoch"] in (T + 400, T + 600) for r in got))
        check("the same-second pair is kept once", sum(r["epoch"] == T + 1300 for r in got) == 1)
        day = datetime.fromtimestamp(T, ZoneInfo(zone)).strftime("%Y-%m-%d")
        unanswered = [r for r in got if r["direction"] in ("missed", "rejected")]
        today = counted(first, day, out)
        check("missed and declined calls are kept with no time counted",
              len(unanswered) == 2 and today.get("call_s") == 872 + 3968 and today.get("calls") == 2
              and today.get("unanswered") == 2, str(today))
        database = b"".join(p.read_bytes() for p in first.iterdir())
        check("the marker phrase is not in the record", MARKER.encode() not in database and MARKER.split()[0].encode() not in database,
              str(sorted(p.name for p in first.iterdir())))
        if "group" in groups:
            group = [r for r in got if r["number"] == f"thread-{groups['group']}"]
            check("the group message shows under the group with two other people",
                  len(group) == 1 and group[0]["contact"] == "Group: Dana-Work +2", str(group))
        ticked = people(first)
        check("the people table holds the two ticked people with their numbers",
              set(ticked) == {"Dana-Work", "Sam - Work"}
              and ticked["Dana-Work"]["numbers"] == {"5551234567", "5550001111"} and ticked["Sam - Work"]["numbers"] == {"5552223333"}
              and all(p["ticked"] == 1 and p["on_phone"] == 1 and p["first_ticked_at"] for p in ticked.values()), str(ticked))
        status = run.prefs().get("status", "")
        # Scheduling the periodic job can start a second run at once, which finds the same and adds nothing new.
        check("the run's status is clean", f"{len(want)} calls and texts with 2 ticked people found, " in status
              and " new in the record." in status and "couldn't" not in status and "wrong" not in status, status)
        run.sh("am", "start", "-n", f"{APP}/.MainActivity")
        time.sleep(3)
        screen = run.screen_text()
        check("the status screen shows the record", f"The record holds {len(need)} entries. 2 people are ticked." in screen,
              screen[:300])

        # Run 2: Dana unticked, and a new call from her. Nothing goes and nothing of hers comes in.
        answer = run.tick([DANA1], untick=True)
        check("Dana unticked through the test hook", answer == "unticked 1", answer)
        run.call(DANA1, 3000, 1, 100)
        if not check("app ran with Dana unticked", run.run_app(run.device_mark())):
            raise RuntimeError("no run finished")
        second = run.record()
        after = rows(second)
        check("nothing was removed and nothing new of Dana's came in", after == got,
              f"{len(after)} rows, {len(got)} before; new {[r for r in after if r not in got][:2]}")
        dana = people(second).get("Dana-Work", {})
        check("Dana stays in the people table, unticked, with her numbers",
              dana.get("ticked") == 0 and dana.get("on_phone") == 1 and dana.get("numbers") == {"5551234567", "5550001111"}, str(dana))

        # Run 3: Dana ticked again, then her contact deleted from the phone, then a new call from her.
        answer = run.tick([DANA1])
        check("Dana ticked again through the test hook", answer == "ticked 1", answer)
        left = run.delete_contact("Dana-Work")
        check("Dana's contact is gone from the phone", "123-4567" not in left and "000-1111" not in left and "222-3333" in left, left[:300])
        run.call(DANA2, 4000, 2, 200)
        if not check("app ran with Dana's contact deleted", run.run_app(run.device_mark())):
            raise RuntimeError("no run finished")
        third = run.record()
        latest = rows(third)
        check("every earlier row is intact", all(r in latest for r in got), str([r for r in got if r not in latest][:2]))
        fresh = {r["epoch"] - T: r for r in latest if r not in got}
        check("a new call from her number counts under her remembered name, and the one made while unticked comes in",
              set(fresh) == {3000, 4000} and all(r["contact"] == "Dana-Work" for r in fresh.values())
              and fresh[4000]["direction"] == "out" and fresh[4000]["duration_s"] == 200, str(fresh))
        dana = people(third).get("Dana-Work", {})
        check("Dana's people row is intact and no longer on the phone",
              dana.get("ticked") == 1 and dana.get("on_phone") == 0 and dana.get("numbers") == {"5551234567", "5550001111"}
              and dana.get("first_ticked_at") == ticked["Dana-Work"]["first_ticked_at"], str(dana))
        if "group" in groups:
            group = [r for r in latest if r["number"] == f"thread-{groups['group']}"]
            check("the group keeps its label without her contact", len(group) == 1 and group[0]["contact"] == "Group: Dana-Work +2", str(group))

        # Run 4: texts refused. The app needs all three permissions, so it reads nothing and says so.
        run.sh("pm", "revoke", APP, "android.permission.READ_SMS")
        reported = run.run_app(run.device_mark())
        check("without SMS access the app ran", reported)
        status = run.prefs().get("status", "")
        check("without SMS access the record is unchanged and the status says the app isn't allowed",
              rows(run.record()) == latest and status.endswith(NOT_ALLOWED), status)

        # Run 5: nothing allowed.
        for permission in PERMISSIONS:
            run.sh("pm", "revoke", APP, permission)
        reported = run.run_app(run.device_mark())
        check("with no access the app ran", reported)
        status = run.prefs().get("status", "")
        check("with no access the record is unchanged and the status says the app isn't allowed",
              rows(run.record()) == latest and status.endswith(NOT_ALLOWED), status)
        check("group message seeded" if groups else "group message not seeded (provider refused)", True)
    except Exception as error:  # noqa: BLE001 - every failure is reported as a fail line, then everything stops
        check("the check ran to the end", False, f"{type(error).__name__}: {error}")
    finally:
        run.stop_emulator()
        subprocess.run([str(ADB), "kill-server"], capture_output=True, env=run.env, timeout=30)
    total = len(check.lines)
    print(f"{total - check.failed} passed, {check.failed} failed", flush=True)
    return 1 if check.failed else 0


if __name__ == "__main__":
    sys.exit(main())
