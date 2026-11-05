#!/usr/bin/env python3
"""Checks the Android app end to end on the emulator, by reading the record it keeps on the phone.

Starts the emulator, builds and installs a test copy of the app (CALLTALLY_DEBUG=true: debuggable, with the test hook),
seeds contacts, calls and messages (among them an RCS chat that the conversation list names only by its chat id), then goes through the first start on the screen: the page, Continue and Android's
permission questions, Who counts with a tick, picking days on the pay period calendar, Skip, and the month screen with no pay
period row. It sets the pay period through the test hook and turns it off again with Reset from Options. Then it
sets it again, ticks Dana-Work and Sam - Work through it, runs the app and copies its record
off the emulator to check it with sqlite3 against what tests/reference.py would have kept. Then it unticks Dana, ticks her again
and deletes her contact, checking that nothing is ever lost, ticks Mom to check that conversations keep their identity,
and takes the app's permissions away. Last it opens the month screen and reads it with uiautomator: the title, the three
totals rows and the grid, counted as tests/reference.py counts the record, with every button at least 40dp tall; then Who
counts with its ticks and search; then a damaged record, which must be moved aside, with an OK that clears the note.
Last, an update over what the uploading app (0.6.0) leaves: a fresh install given its sent.txt and its last run's words by
hand, then the same build installed over it, which must open on the first-start page with the old files gone. Prints one
pass or fail line per check, exits 1 on any fail, and always stops the emulator and adb. Screenshots of the first start
go into the output folder's shots/ unless --shots names another.

It also opens Catch up encrypted and checks its screen and wording, but skips the steps that need the Android system role
dialogs (becoming the default messaging app through the in-app request, the mid-hold text pass-through, and handing the
role back): those are not reliably drivable on this emulator, so it prints a "skipped" line and leaves them for a check on
a real phone. It does drive the path 0.9.2 adds: it sets Call Tally as the default messaging app from adb, then checks
that Continue reads straight away with no role dialog (the fix), and restores the default afterwards. If neither adb
mechanism can set the default on this AVD, that path is skipped too. The plain pieces behind all of this are covered by
tests/test_calltally.py.

Usage: python3 tools/emulator_check.py [--out FOLDER] [--shots FOLDER]   (default: a new folder under $TMPDIR)
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
import zlib
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tests"))
import reference  # noqa: E402  (tests/reference.py: the id rule, repeat rule and counting kept from the retired server)

TOOLS = Path.home() / "local" / "calltally-build"
SDK = TOOLS / "android-sdk"
ADB = SDK / "platform-tools" / "adb"
EMULATOR = SDK / "emulator" / "emulator"
APP = "com.calltally.app"
DATABASE = "calltally.db"
PERMISSIONS = ("android.permission.READ_CALL_LOG", "android.permission.READ_SMS", "android.permission.READ_CONTACTS")
MARKER = "zebra marmalade 4417"
NOT_ALLOWED = "Needs permission. Open this app to allow it."
GOOGLE_SMS = "com.android.messaging"  # the emulator image's default messaging app
REFUSED_LINE = "The messaging app wasn't changed, so nothing was read."
T = 1793000000  # 2026-10-26 in the morning, Pacific time. Every seeded time is fixed, so two runs keep the same rows.

DANA1, DANA2, SAM, MOM, PLUMBING = "+15551234567", "5550001111", "5552223333", "5559998888", "5557776666"
CONTACTS = [("Dana-Work", ["+1 (555) 123-4567", "555-000-1111"]), ("Sam - Work", ["555-222-3333"]), ("Mom", [MOM]),
            ("Work Plumbing", [PLUMBING])]
# number, seconds after T, call log type, duration. Types: 1 in, 2 out, 3 missed, 5 declined, 6 blocked, 7 answered elsewhere.
CALLS = [("15551234567", 0, 1, 872), (SAM, 100, 2, 3968), (DANA2, 200, 3, 7), (DANA1, 300, 5, 3), (DANA1, 400, 6, 0),
         (MOM, 500, 1, 600), (SAM, 600, 7, 45), (PLUMBING, 700, 2, 180)]
# address, milliseconds after T, SMS type (1 inbox, 2 sent, 3 draft).
# An RCS chat as Google Messages keeps it: the conversation list names it by the chat's id, its picture and group rows name
# the people. OWN is a made-up number for the phone itself, which the rows name and the conversation list leaves out. The
# app learns it from the sender row of the sent message and leaves it out, so Mom is the group's one other person.
RCS_ID = "d4vtcmrqheztgmjwge4tah3emi2dondfga4dmzdgmi2gizbumfrtoodcmy3gmyrtgfswintgmm======@rcs.google.com"
OWN = "+12065550199"
RCS_LABEL = "Group: Dana-Work, Sam - Work +1"
# seconds after T, box (1 received, 2 sent), m_type, then address and address type (130 cc, 137 from, 151 to).
RCS_MESSAGES = [(2200, 1, 132, [(DANA1, 130), (SAM, 130), (MOM, 130), (DANA1, 137), (OWN, 151)]),
                (2300, 1, 132, [(DANA1, 130), (SAM, 130), (MOM, 130), (DANA1, 137), (OWN, 151)]),
                (2400, 2, 128, [(OWN, 137), (DANA1, 151), (SAM, 151), (MOM, 151)])]
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

    def build_and_install(self, grant=True):
        env = {**os.environ, "CALLTALLY_DEBUG": "true", "CALLTALLY_OUT": "emu"}
        done = subprocess.run(["sh", str(ROOT / "android/app/build.sh")], capture_output=True, text=True, env=env, cwd=ROOT)
        (self.out / "build.log").write_text(done.stdout + done.stderr)
        if done.returncode != 0:
            raise RuntimeError("the test app didn't build; see build.log")
        self.apk = done.stdout.strip().splitlines()[-1]
        self.adb("install", "-r", self.apk, timeout=180)
        if grant:
            self.grant()

    def grant(self):
        for permission in PERMISSIONS:
            self.sh("pm", "grant", APP, permission)

    def granted(self):
        """The app's permissions among the three it needs that Android shows as granted."""
        out = self.adb("shell", "dumpsys", "package", APP)
        return {p for p in PERMISSIONS if f"{p}: granted=true" in out}

    def pay(self, start):
        """Sets the pay period start through the test hook, as the first start or Options would."""
        out = self.sh("am", "broadcast", "--include-stopped-packages", "-a", f"{APP}.TEST_TICK", "-n", f"{APP}/.TestHook",
                      "--es", "pay_start", start)
        return out.split('data="')[1].split('"')[0] if 'data="' in out else out.strip()

    def put(self, path, data):
        """Writes a file into the app's own folder as the app would, with run-as."""
        folder = path.rsplit("/", 1)[0]
        done = subprocess.run([str(ADB), "-e", "exec-in", f"run-as {APP} sh -c 'mkdir -p {folder} && cat > {path}'"],
                              input=data, capture_output=True, env=self.env, timeout=60)
        if done.returncode != 0:
            raise RuntimeError(f"couldn't write {path}: {done.stderr!r}")

    def app_files(self, folder):
        """The names in one of the app's own folders, or [] when the folder isn't there."""
        out = self.adb("exec-out", "run-as", APP, "ls", folder, check=False)
        return [] if "No such file" in out else out.split()

    def shot(self, name):
        """A screenshot of the emulator's screen into the shots folder."""
        data = self.adb("exec-out", "screencap", "-p", binary=True)
        (self.shots / f"{name}.png").write_bytes(data)

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
        groups["rcs"] = self.thread_for(RCS_ID)
        for offset, box, kind, addresses in RCS_MESSAGES:
            self.sh("content", "insert", "--uri", "content://mms", "--bind", f"thread_id:l:{groups['rcs']}",
                    "--bind", f"date:l:{T + offset}", "--bind", f"msg_box:i:{box}", "--bind", f"m_type:i:{kind}", "--bind", "read:i:1",
                    "--bind", f"sub:s:{MARKER}")
            message = self.last_id("content://mms")
            for address, kind_of in addresses:
                self.sh("content", "insert", "--uri", f"content://mms/{message}/addr", "--bind", f"msg_id:l:{message}",
                        "--bind", f"address:s:{address}", "--bind", f"type:i:{kind_of}", "--bind", "charset:i:106")
        return groups

    def canonical(self, thread):
        """The conversation list's addresses for one chat, as the phone keeps them."""
        out = self.sh("content", "query", "--uri", "content://mms-sms/conversations?simple=true", "--projection", "_id:recipient_ids")
        ids = next((line.split("recipient_ids=")[1].split() for line in out.splitlines() if f"_id={thread}," in line), [])
        listed = self.sh("content", "query", "--uri", "content://mms-sms/canonical-addresses")
        return [line.split("address=")[1].strip() for line in listed.splitlines()
                if "address=" in line and any(f"_id={i}," in line for i in ids)]

    def time_addr(self, times):
        """How long the app takes to read picture messages' address rows, through the test hook."""
        out = self.sh("am", "broadcast", "--include-stopped-packages", "-a", f"{APP}.TEST_TICK", "-n", f"{APP}/.TestHook",
                      "--ei", "time_addr", times)
        return out.split('data="')[1].split('"')[0] if 'data="' in out else out.strip()

    def call(self, number, offset, kind, duration):
        self.sh("content", "insert", "--uri", "content://call_log/calls", "--bind", f"number:s:{number}",
                "--bind", f"date:l:{(T + offset) * 1000}", "--bind", f"type:i:{kind}", "--bind", f"duration:l:{duration}")

    def delete_contact(self, name):
        # As a sync adapter, so the contact is gone at once rather than only marked for deletion.
        self.sh("content", "delete", "--uri", "content://com.android.contacts/raw_contacts?caller_is_syncadapter=true",
                "--where", f"_id={self.raw[name]}")
        return self.sh("content", "query", "--uri", "content://com.android.contacts/data/phones", "--projection", "data1")

    def nodes(self):
        """The screen's parts from uiautomator, or [] when it couldn't be read."""
        for _ in range(4):
            self.adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
            raw = self.adb("exec-out", "cat", "/sdcard/ui.xml", check=False, binary=True)
            try:
                return list(ElementTree.fromstring(raw).iter("node"))
            except ElementTree.ParseError:
                time.sleep(2)
        return []

    def wait_for(self, ready, seconds=60):
        """Reads the screen until ready(nodes) holds. Returns the last nodes read."""
        deadline = time.time() + seconds
        nodes = []
        while time.time() < deadline:
            nodes = self.nodes()
            if ready(nodes):
                break
            time.sleep(1)
        return nodes

    def tap_text(self, nodes, text):
        found = [n for n in nodes if n.get("text") == text]
        if not found:
            raise RuntimeError(f"nothing reads {text!r} on the screen")
        self.tap(found[0])

    def swipe_up(self):
        """Scrolls the screen's middle up by about half a screen."""
        size = self.adb("shell", "wm", "size").strip().splitlines()[-1].split(":")[-1].strip()
        width, height = (int(v) for v in size.split("x"))
        self.sh("input", "swipe", width // 2, height * 3 // 4, width // 2, height // 4, 400)
        time.sleep(1)

    def tap(self, node):
        (left, top), (right, bottom) = bounds(node)
        self.sh("input", "tap", (left + right) // 2, (top + bottom) // 2)

    def density(self):
        out = self.adb("shell", "wm", "density")
        values = [int(part.split(":")[1]) for part in out.strip().splitlines() if ":" in part]
        return values[-1] / 160

    def device_zone(self):
        return self.adb("shell", "getprop", "persist.sys.timezone").strip() or "UTC"


def bounds(node):
    left, top, right, bottom = (int(v) for v in node.get("bounds").replace("][", ",").strip("[]").split(","))
    return (left, top), (right, bottom)


def by_id(nodes, name):
    return [n for n in nodes if n.get("resource-id") == f"{APP}:id/{name}"]


def text_of(nodes, name):
    found = by_id(nodes, name)
    return spaced(found[0].get("text", "")) if found else None


def spaced(text):
    """Text with the non-breaking spaces the screens use to keep a date whole read as plain spaces, for comparing."""
    return text.replace("\u00a0", " ")


def boxes(ns):
    """The app's check boxes on screen, by the first line of their words (Who counts puts the numbers on the next line):
    "true" or "false"."""
    return {(n.get("text") or "").split("\n")[0]: n.get("checked") for n in ns
            if n.get("class", "").endswith("CheckBox") and n.get("package") == APP}


def full_time(s):
    """The screens' full form of a call time, as Words.fullTime writes it."""
    h, m, sec = s // 3600, s % 3600 // 60, s % 60
    return f"{h}h {m:02d}m {sec:02d}s" if h else f"{m}m {sec:02d}s" if m else f"{sec}s"


def plural(n, word):
    return f"{n} {word}{'' if n == 1 else 's'}"


def row_value(totals):
    return f"{full_time(totals['call_s'])} · {plural(totals['calls'], 'call')} · {plural(totals['texts'], 'text')}"


def two_weeks(start, today, days=14):
    """The days from start, two weeks unless told, as PayPeriod.dates writes them: "Oct 25 – Nov 7", with years when not
    all this year."""
    first = start
    last = first + timedelta(days=days - 1)
    show = first.year != today.year or last.year != today.year

    def short(day, year):
        return f"{day.strftime('%b')} {day.day}" + (f", {day.year}" if year else "")
    return f"{short(first, show and first.year != last.year)} – {short(last, show)}"


def png_rows(data, last_row):
    """The pixel rows 0..last_row of a PNG screenshot (8-bit RGB or RGBA, not interlaced), as bytes, and the bytes per pixel."""
    pos, idat, width, step = 8, b"", 0, 4
    while pos < len(data):
        length = int.from_bytes(data[pos:pos + 4], "big")
        kind, body = data[pos + 4:pos + 8], data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width = int.from_bytes(body[0:4], "big")
            step = {6: 4, 2: 3}[body[9]]
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    stride = width * step
    raw = zlib.decompressobj().decompress(idat, (last_row + 1) * (stride + 1))
    rows, prev = [], bytearray(stride)
    for r in range(last_row + 1):
        kind, line = raw[r * (stride + 1)], bytearray(raw[r * (stride + 1) + 1:(r + 1) * (stride + 1)])
        if kind == 1:
            for i in range(step, stride):
                line[i] = (line[i] + line[i - step]) & 255
        elif kind == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif kind == 3:
            for i in range(stride):
                line[i] = (line[i] + ((line[i - step] if i >= step else 0) + prev[i]) // 2) & 255
        elif kind == 4:
            for i in range(stride):
                a = line[i - step] if i >= step else 0
                b, c = prev[i], prev[i - step] if i >= step else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(bytes(line))
        prev = line
    return rows, step


def shaded_days(nodes, shot):
    """The calendar days drawn with the shade, read from the screenshot: the unselected days whose colour, a little in from
    the corner, is not the plain day colour most of them have."""
    days = [(datetime.strptime(n.get("content-desc"), "%A, %B %d, %Y").date(), n) for n in by_id(nodes, "cal_day")]
    if not days:
        return []
    spots = {}
    for day, n in days:
        if n.get("selected") == "true":
            continue
        (left, top), (right, bottom) = bounds(n)
        spots[day] = (left + (right - left) // 5, top + (bottom - top) // 4)
    rows, step = png_rows(Path(shot).read_bytes(), max(y for x, y in spots.values()))
    colour = {day: tuple(rows[y][x * step:x * step + 3]) for day, (x, y) in spots.items()}
    counts = {}
    for c in colour.values():
        counts[c] = counts.get(c, 0) + 1
    plain = max(counts, key=counts.get)
    return sorted(str(day) for day, c in colour.items() if sum(abs(a - b) for a, b in zip(c, plain)) > 24)


def shade_wanted(first, last, month):
    """The days the calendar should shade between the first and last day when it shows the month of `month`."""
    days = [first + timedelta(days=i) for i in range(1, (last - first).days)]
    return sorted(str(d) for d in days if (d.year, d.month) == (month.year, month.month))


def screen_problems(nodes, density):
    """Buttons under 40dp tall, and parts reaching past the screen's sides."""
    width = max(bounds(n)[1][0] for n in nodes) if nodes else 0
    small, outside = [], []
    for n in nodes:
        if n.get("package") != APP:
            continue
        (left, top), (right, bottom) = bounds(n)
        if n.get("clickable") == "true" and bottom - top < 40 * density - 1:
            small.append(f"{n.get('class').split('.')[-1]} {n.get('text') or n.get('content-desc')!r} {bottom - top}px")
        if left < 0 or right > width:
            outside.append(f"{n.get('text') or n.get('resource-id')} {n.get('bounds')}")
    return small, outside


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


def reference_rows(want, temp):
    """What the reference Store keeps for the same entries: its ids, its repeat rule, its columns."""
    store = reference.Store(temp / "reference.db")
    store.add(want)
    conn = store.connect()
    try:
        return [dict(r) for r in conn.execute("SELECT * FROM entries ORDER BY epoch,id")]
    finally:
        conn.close()


def counted(folder, day, temp):
    """One day counted by the reference's range on a copy of the record."""
    copy = temp / f"count-{folder.name}.db"
    shutil.copyfile(folder / DATABASE, copy)
    return reference.Store(copy).range(day, day)["days"].get(day, {})


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
    # The RCS chat, named by its messages: a group of Dana and Sam with Mom as the one other; the phone's own number is left out.
    for offset, box, _, _ in RCS_MESSAGES:
        out.append(item("text", f"thread-{groups['rcs']}", T + offset, "in" if box == 1 else "out", RCS_LABEL))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", help="folder for the logs and the copies of the record")
    parser.add_argument("--shots", help="folder for the screenshots (default: shots/ in the output folder)")
    args = parser.parse_args()
    out = Path(args.out) if args.out else Path(tempfile.mkdtemp(prefix="calltally-emu-", dir=os.environ.get("TMPDIR")))
    out.mkdir(parents=True, exist_ok=True)
    check = Check()
    run = Run(out)
    run.shots = Path(args.shots) if args.shots else out / "shots"
    run.shots.mkdir(parents=True, exist_ok=True)
    print(f"Logs and copies of the record: {out}", flush=True)
    try:
        run.start_emulator()
        check("emulator started", True)
        run.build_and_install(grant=False)
        check("test app built and installed", True)
        groups = run.seed()
        check("contacts, calls, texts and group messages seeded", True)
        listed = run.canonical(groups["rcs"])
        check("the RCS chat is in the conversation list under its chat id only", listed == [RCS_ID], str(listed))
        zone = run.device_zone()
        want = expected(groups, zone)
        density = run.density()

        # The first start, on the screen: a fresh install opens on the page, Continue asks Android for the permissions,
        # Who counts opens with its hint, then the pay period question with Skip, then the month screen.
        run.adb("logcat", "-b", "crash", "-c", check=False)
        run.sh("am", "start", "-W", "-n", f"{APP}/.MainActivity")
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "start_continue")), 30)
        run.shot("01-first-start")
        small, outside = screen_problems(nodes, density)
        check("a fresh install opens on the first-start page, not the month",
              bool(by_id(nodes, "start_continue")) and not by_id(nodes, "month_title")
              and (text_of(nodes, "start_text") or "").startswith("Call Tally shows your time on calls")
              and not small and not outside, f"{text_of(nodes, 'start_text')!r} small {small[:3]} outside {outside[:3]}")
        run.tap(by_id(nodes, "start_continue")[0])

        def asking(ns):
            return any(n.get("package") == "com.android.permissioncontroller" for n in ns)
        nodes = run.wait_for(asking, 20)
        run.shot("02-permission-question")
        check("Continue asks Android for the permissions", asking(nodes), str({n.get("package") for n in nodes}))
        for _ in range(8):
            allow = [n for n in nodes if (n.get("resource-id") or "").endswith("permission_allow_button")]
            if not allow:
                allow = [n for n in nodes if n.get("package") == "com.android.permissioncontroller" and n.get("text") in ("Allow", "ALLOW")]
            if allow:
                run.tap(allow[0])
                time.sleep(1.5)
            elif by_id(nodes, "who_next"):
                break
            else:
                time.sleep(1)
            nodes = run.nodes()
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "who_next")) and "Dana-Work" in boxes(ns), 30)
        run.shot("03-who-counts-first")
        check("allowing all three opens Who counts with the hint and nobody ticked",
              run.granted() == set(PERMISSIONS) and bool(by_id(nodes, "who_next")) and not by_id(nodes, "close")
              and (text_of(nodes, "who_note") or "").startswith("Tick the people whose calls and texts should count")
              and "true" not in boxes(nodes).values(), f"{sorted(run.granted())} {text_of(nodes, 'who_note')!r} {boxes(nodes)}")
        run.tap([n for n in nodes if n.get("class", "").endswith("CheckBox") and (n.get("text") or "").split("\n")[0] == "Dana-Work"][0])
        nodes = run.wait_for(lambda ns: boxes(ns).get("Dana-Work") == "true", 15)
        run.tap(by_id(nodes, "who_next")[0])
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "pay_skip")), 20)
        run.shot("04-pay-period-first")
        check("Next asks for the pay period with a calendar and Skip",
              text_of(nodes, "pay_question") == "Pick the first day of your current pay period, then its last day."
              and len(by_id(nodes, "cal_day")) >= 28 and not by_id(nodes, "confirm"), repr(text_of(nodes, "pay_question")))
        # The calendar opens on this month. The first tap picks the first day and asks for the last; the second picks the
        # last day, shades the days between and shows the review; tapping the first day again clears both.
        today = datetime.now(ZoneInfo(zone)).date()

        def cal_days(ns):
            return [(datetime.strptime(n.get("content-desc"), "%A, %B %d, %Y").date(), n) for n in by_id(ns, "cal_day")]

        def marked(ns):
            return [str(day) for day, n in cal_days(ns) if n.get("selected") == "true"]
        pick, pick_last = today.replace(day=1), today.replace(day=14)
        check("before a day is picked there is no review and no preview",
              not by_id(nodes, "review") and not by_id(nodes, "period_preview"), repr(text_of(nodes, "period_preview")))
        run.tap([n for day, n in cal_days(nodes) if day == pick][0])
        nodes = run.wait_for(lambda ns: text_of(ns, "period_preview") == "Now pick the last day.", 15)
        run.shot("04b-pay-period-first-day")
        shade = shaded_days(nodes, run.shots / "04b-pay-period-first-day.png")
        check("picking the 1st highlights it and asks for the last day, with no review, no Confirm and Skip still there",
              text_of(nodes, "period_preview") == "Now pick the last day." and marked(nodes) == [str(pick)]
              and not by_id(nodes, "review") and not by_id(nodes, "confirm") and bool(by_id(nodes, "pay_skip")) and not shade,
              f"{text_of(nodes, 'period_preview')!r} highlighted {marked(nodes)} review {text_of(nodes, 'review')!r} shaded {shade}")
        run.tap([n for day, n in cal_days(nodes) if day == pick_last][0])
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "review")) and bool(by_id(ns, "confirm")), 15)
        run.shot("04c-pay-period-first-last-day")
        want_review = f"Pay periods start: not set → {pick.strftime('%a %b')} {pick.day}"
        want_preview = f"Pay period: none → {two_weeks(pick, today)}, 14 days"
        shade = shaded_days(nodes, run.shots / "04c-pay-period-first-last-day.png")
        check("picking the 14th as the last day shows the review with 14 days, both days highlighted and the days between shaded",
              text_of(nodes, "review") == want_review and text_of(nodes, "period_preview") == want_preview
              and marked(nodes) == [str(pick), str(pick_last)] and bool(by_id(nodes, "pay_skip"))
              and shade == shade_wanted(pick, pick_last, pick),
              f"{text_of(nodes, 'review')!r} {text_of(nodes, 'period_preview')!r} want {want_preview!r} "
              f"highlighted {marked(nodes)} shaded {shade}")
        run.tap([n for day, n in cal_days(nodes) if day == pick][0])
        nodes = run.wait_for(lambda ns: not by_id(ns, "review") and not by_id(ns, "period_preview") and bool(by_id(ns, "cal_day")), 15)
        run.shot("04d-pay-period-first-unpicked")
        shade = shaded_days(nodes, run.shots / "04d-pay-period-first-unpicked.png")
        check("picking the first day again clears both days, the review, the preview and the shade",
              not by_id(nodes, "review") and not by_id(nodes, "period_preview") and not by_id(nodes, "confirm")
              and bool(by_id(nodes, "pay_skip")) and not marked(nodes) and not shade,
              f"review {text_of(nodes, 'review')!r} preview {text_of(nodes, 'period_preview')!r} "
              f"highlighted {marked(nodes)} shaded {shade}")
        enabled = [str(day) for day, n in cal_days(nodes) if n.get("enabled") != "true"]
        check("every day of the month can be picked", bool(cal_days(nodes)) and not enabled, f"not enabled {enabled}")
        run.tap(by_id(nodes, "pay_skip")[0])
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "month_title")) and bool(by_id(ns, "total_all")), 30)
        run.shot("05-month-after-first-start")
        settings = dict(sqlite3.connect(f"file:{run.record() / DATABASE}?mode=ro", uri=True).execute("SELECT key,value FROM settings").fetchall())
        ticked = people(out / f"record-{run.pulls}")
        check("after a tick and Skip the month screen opens with no pay period row, and Dana is ticked",
              bool(by_id(nodes, "month_title")) and not by_id(nodes, "total_period") and "pay_period_start" not in settings
              and ticked.get("Dana-Work", {}).get("ticked") == 1, f"{settings} {list(ticked)} period row {bool(by_id(nodes, 'total_period'))}")
        run.sh("am", "force-stop", APP)
        run.sh("am", "start", "-W", "-n", f"{APP}/.MainActivity")
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "month_title")), 30)
        check("opened again, the app goes straight to the month", bool(by_id(nodes, "month_title")) and not by_id(nodes, "start_continue"))
        answer = run.pay("2026-10-25")
        check("pay period set through the test hook for the checks below", answer == "pay period set", answer)

        # Reset from Options: the screen shows the two weeks from the Oct 25 start; Reset makes the change "none",
        # and Confirm removes the pay period, so the month screen has no pay period row. Then it is set again.
        run.sh("am", "force-stop", APP)
        run.sh("am", "start", "-W", "-n", f"{APP}/.MainActivity")
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "gear")) and bool(by_id(ns, "total_period")), 30)
        run.tap(by_id(nodes, "gear")[0])
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "pay_change")), 30)
        run.tap(by_id(nodes, "pay_change")[0])
        today = datetime.now(ZoneInfo(zone)).date()
        # The hook saved Oct 25; the screen shows the period holding today, which moves with the host's date.
        saved_day = date(2026, 10, 25)
        cur_first = saved_day + timedelta(days=(today - saved_day).days // 14 * 14)
        cur_last = cur_first + timedelta(days=13)
        saved_two = two_weeks(cur_first, today)
        set_preview = f"Pay period {saved_two}, 14 days"
        nodes = run.wait_for(lambda ns: text_of(ns, "period_preview") == set_preview, 20)
        run.shot("07-pay-periods-from-options")
        reset_node = by_id(nodes, "pay_reset")
        check("Pay periods from Options says the start, shows the current period, and has Close and Reset",
              text_of(nodes, "pay_question") == "Pay periods start Sun Oct 25 and run two weeks. Pick the first day to change it."
              and text_of(nodes, "period_preview") == set_preview and bool(by_id(nodes, "close")) and not by_id(nodes, "confirm")
              and bool(reset_node) and reset_node[0].get("enabled") == "true" and not by_id(nodes, "review"),
              f"{text_of(nodes, 'pay_question')!r} {text_of(nodes, 'period_preview')!r} want {set_preview!r}")
        # The current period's first and last day are highlighted and the days between shaded, across months. With
        # nothing pending any other day, even one after the period, starts a new pick; a later day is its last day; the
        # last day again clears it; the first day again turns the pay period off, as Reset does; picking the current
        # period's two days again is no change.
        def cal_days(ns):
            return [(datetime.strptime(n.get("content-desc"), "%A, %B %d, %Y").date(), n) for n in by_id(ns, "cal_day")]

        def marked(ns):
            return [str(day) for day, n in cal_days(ns) if n.get("selected") == "true"]

        def month_of(ns):
            days = cal_days(ns)
            return (days[0][0].year, days[0][0].month) if days else None

        def visit(ns, day):
            """Pages the calendar to the month holding day."""
            for _ in range(6):
                shown = month_of(ns)
                if shown == (day.year, day.month):
                    return ns
                button = "cal_next" if shown < (day.year, day.month) else "cal_prev"
                run.tap(by_id(ns, button)[0])
                ns = run.wait_for(lambda n2, was=shown: month_of(n2) not in (None, was), 15)
            return ns

        def tap_day(ns, day):
            ns = visit(ns, day)
            run.tap([n for d, n in cal_days(ns) if d == day][0])
        opened = month_of(nodes)
        seen_marked, seen_shade, step = [], [], cur_first.replace(day=1)
        while step <= cur_last:
            nodes = visit(nodes, step)
            name = f"07a-pay-periods-from-options-{step.strftime('%Y-%m')}"
            run.shot(name)
            seen_marked += marked(nodes)
            seen_shade += shaded_days(nodes, run.shots / f"{name}.png")
            step = (step + timedelta(days=32)).replace(day=1)
        check("the calendar opens on the current period, its first and last day highlighted and the days between shaded",
              opened == (cur_first.year, cur_first.month) and seen_marked == [str(cur_first), str(cur_last)]
              and sorted(seen_shade) == sorted(str(cur_first + timedelta(days=i)) for i in range(1, 13)),
              f"opened {opened} highlighted {seen_marked} shaded {sorted(seen_shade)}")
        new_first, new_last = cur_last + timedelta(days=3), cur_last + timedelta(days=9)
        tap_day(nodes, new_first)
        nodes = run.wait_for(lambda ns: text_of(ns, "period_preview") == "Now pick the last day.", 15)
        run.shot("07b-pay-periods-new-first-day")
        step1 = (marked(nodes), shaded_days(nodes, run.shots / "07b-pay-periods-new-first-day.png"), bool(by_id(nodes, "review")),
                 bool(by_id(nodes, "confirm")), bool(by_id(nodes, "revert")))
        want_review = f"Pay periods start: Sun Oct 25 → {new_first.strftime('%a %b')} {new_first.day}"
        tap_day(nodes, new_last)
        nodes = run.wait_for(lambda ns: text_of(ns, "review") == want_review, 15)
        tap_day(nodes, new_last)
        nodes = run.wait_for(lambda ns: text_of(ns, "period_preview") == "Now pick the last day.", 15)
        step3 = (marked(nodes), bool(by_id(nodes, "review")))
        tap_day(nodes, new_last)
        nodes = run.wait_for(lambda ns: text_of(ns, "review") == want_review, 15)
        nodes = visit(nodes, new_first)
        run.shot("07c-pay-periods-new-period")
        shade = shaded_days(nodes, run.shots / "07c-pay-periods-new-period.png")
        want_preview = f"Pay period: {saved_two} → {two_weeks(new_first, today, 7)}, 7 days"
        in_month = [str(d) for d in (new_first, new_last) if (d.year, d.month) == (new_first.year, new_first.month)]
        check("a day after the current period starts a new pick, a later day is its last day, and the last day again clears it",
              step1[1:] == ([], False, False, True) and str(new_first) in step1[0] and str(cur_last) not in step1[0]
              and str(new_first) in step3[0] and not step3[1]
              and text_of(nodes, "period_preview") == want_preview and marked(nodes) == in_month
              and shade == shade_wanted(new_first, new_last, new_first) and bool(by_id(nodes, "confirm")),
              f"{step1} {step3} {text_of(nodes, 'period_preview')!r} want {want_preview!r} highlighted {marked(nodes)} shaded {shade}")
        run.tap([n for day, n in cal_days(nodes) if day == new_first][0])
        nodes = run.wait_for(lambda ns: (text_of(ns, "review") or "").endswith("→ none"), 15)
        run.shot("07d-pay-periods-off")
        shade = shaded_days(nodes, run.shots / "07d-pay-periods-off.png")
        check("tapping the first day again turns the pay period off, as Reset does, with nothing highlighted or shaded",
              text_of(nodes, "review") == "Pay periods start: Sun Oct 25 → none"
              and text_of(nodes, "period_preview") == f"Pay period: {saved_two} → none" and not marked(nodes)
              and bool(by_id(nodes, "confirm")) and not shade,
              f"{text_of(nodes, 'review')!r} {text_of(nodes, 'period_preview')!r} highlighted {marked(nodes)} shaded {shade}")
        tap_day(nodes, cur_first)
        nodes = run.wait_for(lambda ns: text_of(ns, "period_preview") == "Now pick the last day.", 15)
        tap_day(nodes, cur_last)
        nodes = run.wait_for(lambda ns: not by_id(ns, "review") and bool(by_id(ns, "close")), 15)
        last_marked = marked(nodes)
        nodes = visit(nodes, cur_first)
        check("picking the current period's first and last day again is no change: Close, no review, both highlighted",
              not by_id(nodes, "review") and not by_id(nodes, "confirm") and text_of(nodes, "period_preview") == set_preview
              and str(cur_first) in marked(nodes) and str(cur_last) in last_marked,
              f"{text_of(nodes, 'review')!r} highlighted {marked(nodes)} then {last_marked}")
        reset_node = by_id(nodes, "pay_reset")
        run.tap(reset_node[0])
        nodes = run.wait_for(lambda ns: (text_of(ns, "review") or "").endswith("→ none"), 15)
        run.shot("08-pay-periods-reset")
        small, outside = screen_problems(nodes, density)
        reset_node = by_id(nodes, "pay_reset")
        footer = [n.get("text") for n in nodes if n.get("resource-id") in (f"{APP}:id/revert", f"{APP}:id/pay_reset", f"{APP}:id/confirm")]
        shade = shaded_days(nodes, run.shots / "08-pay-periods-reset.png")
        check("Reset makes the change none: the review and the preview end in none, with Revert, Reset and Confirm",
              text_of(nodes, "review") == "Pay periods start: Sun Oct 25 → none"
              and text_of(nodes, "period_preview") == f"Pay period: {saved_two} → none" and footer == ["Revert", "Reset", "Confirm"]
              and bool(reset_node) and reset_node[0].get("enabled") == "false" and not by_id(nodes, "close")
              and not marked(nodes) and not shade,
              f"{text_of(nodes, 'review')!r} {text_of(nodes, 'period_preview')!r} footer {footer} shaded {shade}")
        check("every button on the pay period screen is at least 40dp tall and nothing reaches past the screen",
              not small and not outside, f"small {small[:3]} outside {outside[:3]}")
        run.tap(by_id(nodes, "confirm")[0])
        nodes = run.wait_for(lambda ns: text_of(ns, "pay_line") == "No pay period is set.", 20)
        check("after Confirm, Options says no pay period is set", text_of(nodes, "pay_line") == "No pay period is set.",
              repr(text_of(nodes, "pay_line")))
        run.sh("input", "keyevent", "KEYCODE_BACK")
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "month_title")) and bool(by_id(ns, "total_all")) and not by_id(ns, "total_period"), 30)
        settings = dict(sqlite3.connect(f"file:{run.record() / DATABASE}?mode=ro", uri=True).execute("SELECT key,value FROM settings").fetchall())
        check("Reset and Confirm turn pay periods off: no pay period row on the month screen and no pay period settings",
              bool(by_id(nodes, "month_title")) and not by_id(nodes, "total_period")
              and "pay_period_start" not in settings and "pay_period_days" not in settings,
              f"{settings} period row {bool(by_id(nodes, 'total_period'))}")
        answer = run.pay("2026-10-25")
        check("pay period set again through the test hook for the checks below", answer == "pay period set", answer)

        answer = run.tick([DANA1, SAM])
        check("Dana-Work and Sam - Work ticked through the test hook", answer == "ticked 2", answer)

        # Run 1: everything allowed, Dana and Sam ticked.
        if not check("app ran", run.run_app(run.device_mark())):
            raise RuntimeError("no run finished")
        first = run.record()
        got = rows(first)
        need = reference_rows(want, out)
        check("exactly the expected entries are in the record, as the reference would keep them", got == need,
              f"extra {[r for r in got if r not in need][:2]} missing {[r for r in need if r not in got][:2]}")
        check("the record's ids are the ones the reference makes",
              {r["id"] for r in got} == {reference.entry_id(e["kind"], e["number"], e["epoch"], e["direction"]) for e in want})
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
        rcs = [r for r in got if r["number"] == f"thread-{groups['rcs']}"]
        check("the RCS chat's three messages count under the group of Dana and Sam, read from their address rows",
              sorted((r["epoch"] - T, r["direction"], r["contact"]) for r in rcs)
              == [(2200, "in", RCS_LABEL), (2300, "in", RCS_LABEL), (2400, "out", RCS_LABEL)], str(rcs))
        # Its rows name the phone's own number, Dana, Sam and Mom: counting the own number too would make it "+2".
        named = {reference.number_key(a) for _, _, _, rows_of in RCS_MESSAGES for a, _ in rows_of}
        check("the RCS group's label leaves the phone's own number out: Mom is its one other person",
              named == {reference.number_key(n) for n in (OWN, DANA1, SAM, MOM)} and RCS_LABEL.endswith(" +1")
              and {r["contact"] for r in rcs} == {RCS_LABEL}, f"{named} {sorted({r['contact'] for r in rcs})}")
        print(f"info  address rows read by the app: {run.time_addr(500)}", flush=True)
        status = run.prefs().get("status", "")
        # Scheduling the periodic job can start a second run at once, which finds the same and adds nothing new.
        check("the run's status is clean", f"{len(want)} calls and texts with 2 ticked people found, " in status
              and " new in the record." in status and "couldn't" not in status and "wrong" not in status, status)

        own_saved = dict(sqlite3.connect(f"file:{first / DATABASE}?mode=ro", uri=True).execute("SELECT key,value FROM settings").fetchall())
        check("the record keeps the phone's own number, learned from the sent message",
              own_saved.get("own_numbers") == reference.number_key(OWN), repr(own_saved.get("own_numbers")))

        # Catch up encrypted: Call Tally briefly becomes the phone's messaging app, reads once, then hands the role back,
        # with the owner approving each change in a system dialog. Dana-Work and Sam - Work are ticked and the record matches
        # `got`. The default messaging app is com.android.messaging. A text that arrives while Call Tally holds the role
        # must be passed through into the phone's inbox, unread, by its SMS receiver.
        def sms_default():
            """The phone's messaging app exactly as the app reads it (Telephony.Sms.getDefaultSmsPackage), through the
            test hook. The role holder in dumpsys role doesn't reflect the live default during a brief hold on this image,
            so the app's own reading is the ground truth."""
            out = run.sh("am", "broadcast", "--include-stopped-packages", "-a", f"{APP}.TEST_TICK",
                         "-n", f"{APP}/.TestHook", "--ez", "default_sms", "true")
            return out.split('data="')[1].split('"')[0] if 'data="' in out else out.strip()

        def open_options(want):
            """Opens Options fresh and scrolls until the part named `want` is on screen."""
            run.sh("am", "force-stop", APP)
            ns = []
            for _ in range(3):
                run.sh("am", "start", "-W", "-n", f"{APP}/.MainActivity")
                ns = run.wait_for(lambda n2: bool(by_id(n2, "gear")), 20)
                if by_id(ns, "gear"):
                    break
                run.sh("input", "keyevent", "KEYCODE_BACK")
            if not by_id(ns, "gear"):
                run.shot("options-no-gear")
                raise RuntimeError("the month screen's gear wasn't found to open Options")
            run.tap(by_id(ns, "gear")[0])
            ns = run.wait_for(lambda n2: bool(by_id(n2, "who_open")), 30)
            for _ in range(10):
                if by_id(ns, want):
                    break
                run.swipe_up()
                ns = run.nodes()
            return ns

        def open_catch_up():
            ns = open_options("catch_up")
            if not by_id(ns, "catch_up"):
                raise RuntimeError("the Catch up encrypted button wasn't found in Options")
            run.tap(by_id(ns, "catch_up")[0])
            return run.wait_for(lambda n2: bool(by_id(n2, "catch_continue")), 30)

        def set_default_sms(pkg):
            """Makes pkg the default messaging app from adb, preferring the role manager. Only falls back to the secure
            setting when the role command is unavailable on this image, because writing that setting for an app the role
            manager won't accept leaves the default in a stale state. Returns which mechanism made Telephony report pkg
            ('cmd role' or 'settings'), or '' when neither could."""
            out = run.adb("shell", "cmd", "role", "add-role-holder", "--user", "0", "android.app.role.SMS", pkg,
                          check=False) or ""
            cmd_role_missing = any(s in out for s in ("Unknown command", "Can't find service", "No shell command", "not found"))
            if not cmd_role_missing:
                for _ in range(5):
                    if sms_default() == pkg:
                        return "cmd role"
                    time.sleep(1)
                return ""  # the role command exists but wouldn't take; don't write the secure setting and strand it
            run.adb("shell", "settings", "put", "secure", "sms_default_application", pkg, check=False)
            for _ in range(5):
                if sms_default() == pkg:
                    return "settings"
                time.sleep(1)
            return ""

        def restore_default_sms(pkg):
            """Puts the default messaging app back, resetting BOTH the role and the secure setting, so no subsystem keeps a
            stale default SMS app (which would hand that app implicit SMS access and defeat the permission checks below)."""
            run.adb("shell", "cmd", "role", "add-role-holder", "--user", "0", "android.app.role.SMS", pkg, check=False)
            run.adb("shell", "settings", "put", "secure", "sms_default_application", pkg, check=False)
            for _ in range(10):
                if sms_default() == pkg:
                    return True
                time.sleep(1)
            return False

        check("before catching up, Call Tally is not the messaging app and Google's app is",
              sms_default() == "com.android.messaging", sms_default())
        # The feature's own screen is driven here: its explanation, Continue and Cancel, and that it has not caught up
        # yet. Opening and cancelling it changes nothing. The steps past Continue are skipped (see below).
        nodes = open_catch_up()
        run.shot("09-catch-up-explain")
        check("Catch up encrypted opens with the explanation, Continue, Cancel and 'Not yet'",
              bool(by_id(nodes, "catch_continue")) and bool(by_id(nodes, "catch_cancel"))
              and "only be read by the phone's messaging app" in (text_of(nodes, "catch_explain") or "")
              and (text_of(nodes, "catch_line") or "") == "Not yet", repr(text_of(nodes, "catch_explain")))
        # 0.9.3: the screen shows what the phone reports about the messaging app, plainly, so the owner can screenshot it
        # when the detection and Settings disagree. On the fresh emulator the default is Google's app (com.android.messaging).
        catch_report = text_of(nodes, "catch_report") or ""
        check("Catch up encrypted shows the 'Messaging app:' report line with the emulator's default messaging app",
              catch_report.startswith("Messaging app:") and f"default={GOOGLE_SMS}" in catch_report, repr(catch_report))
        run.tap(by_id(nodes, "catch_cancel")[0])
        # Everything past Continue needs the Android system role dialogs: becoming the default messaging app, a text that
        # arrives while it holds the role being passed through to the inbox unread, handing the role back, and the
        # still-the-messaging-app warning. Approving those on-device dialogs through uiautomator, and injecting a text
        # mid-hold, are not reliably drivable on this emulator (the role UI and adb drop out), so they are skipped. The
        # plain pieces they rely on — the inbox row values for an arriving text, and the catch-up wording — are covered by
        # tests/test_calltally.py (test_catch_up_passthrough_pieces). The two role dialogs need a check on a real phone.
        print("info  skipped: role dialogs not drivable on this emulator — becoming and handing back the default "
              "messaging app, and the mid-hold text pass-through, are not exercised here (the row values and wording are "
              "covered by the unit tests; verify the two role dialogs on a real phone)", flush=True)
        check("Catch up encrypted's screen and wording are in place; the role-change steps are skipped (see the info line above)", True)
        # Nothing was changed, so the runs below are unaffected: the messaging app and the record are as before.
        run.sh("am", "force-stop", APP)
        check("catching up changed nothing: Google's app is still the messaging app and the record is unchanged",
              sms_default() == "com.android.messaging" and rows(run.record()) == got,
              f"default {sms_default()} new {[r for r in rows(run.record()) if r not in got][:2]}")

        # 0.9.2: the fix makes Continue read straight away when Call Tally is ALREADY the default messaging app, instead
        # of re-requesting the role (the request is what Play Protect blocks on the owner's phone). The role dialogs still
        # aren't drivable here, but the default itself can usually be set from adb, which lets this drive exactly that
        # path: make Call Tally the default, confirm Telephony reports it, open Catch up, and tap Continue, which must
        # read with no role dialog and not say REFUSED. The default is restored to Google's app afterwards so the later
        # checks are unaffected. If neither adb mechanism can set the default on this AVD, the path is skipped.
        run.sh("am", "force-stop", APP)
        how = set_default_sms(APP)
        if how:
            check(f"Call Tally can be made the default messaging app from adb ({how})", sms_default() == APP, sms_default())
            nodes = open_catch_up()
            nodes = run.wait_for(lambda ns: (text_of(ns, "catch_result") or "").startswith("Call Tally is the messaging app"), 15)
            run.shot("09b-catch-up-already-default")
            check("already the default: the screen shows the ready line prompting Continue",
                  (text_of(nodes, "catch_result") or "").startswith("Call Tally is the messaging app"),
                  repr(text_of(nodes, "catch_result")))
            run.tap(by_id(nodes, "catch_continue")[0])

            def read_done(ns):
                t = text_of(ns, "catch_result") or ""
                return bool(t) and not t.startswith("Reading") and not t.startswith("Call Tally is the messaging app")
            nodes = run.wait_for(read_done, 60)
            line = text_of(nodes, "catch_result") or ""
            first = line.split("\n")[0]
            check("already the default: Continue reads with no role dialog, not REFUSED, and shows a count",
                  REFUSED_LINE not in line and first.startswith("Read while Call Tally was the messaging app.")
                  and "counted," in first and first.endswith("new since the last run."), repr(line))
            # 0.9.4: the report line stays on the screen after the read, with no new dependency on driving the role.
            rep = text_of(nodes, "catch_report") or ""
            check("already the default: the 'Messaging app:' report line is still shown after the read",
                  rep.startswith("Messaging app:"), repr(rep))
            check("already the default: nothing is lost by catching up, the record still matches",
                  rows(run.record()) == got, f"new {[r for r in rows(run.record()) if r not in got][:2]}")
            # Dismiss any hand-back dialog the read then raised (not drivable here).
            run.sh("input", "keyevent", "KEYCODE_BACK")
        else:
            print("info  skipped: the role manager on this AVD would not make Call Tally the default messaging app, so "
                  "the already-default read path is not exercised here (its logic is covered by the unit pieces and the "
                  "code changes); verify on a real phone", flush=True)
            check("already-default read path skipped on this AVD (see the info line above)", True)
        # Always put the default back and reset both subsystems, since the attempts above can leave it changed: a stale
        # default SMS app would keep implicit SMS access and defeat the permission checks that follow.
        run.sh("am", "force-stop", APP)
        restored = restore_default_sms(GOOGLE_SMS)
        check("the default messaging app is restored to Google's app after the check",
              restored and sms_default() == GOOGLE_SMS, sms_default())

        # The app's own version at the very bottom of Options, so the owner can confirm which build is installed.
        manifest = (ROOT / "android/app/AndroidManifest.template.xml").read_text()
        app_version = manifest.split('android:versionName="')[1].split('"')[0]
        nodes = open_options("version_line")
        version_text = text_of(nodes, "version_line")
        check("Options shows the app version at the bottom",
              (version_text or "").startswith("Call Tally ") and app_version in (version_text or ""),
              f"{version_text!r} want version {app_version!r}")

        # Run 2: Dana unticked, and a new call from her. Nothing goes and nothing of hers comes in.
        answer = run.tick([DANA1], untick=True)
        check("Dana unticked through the test hook", answer == "unticked 1", answer)
        run.call(DANA1, 3000, 1, 100)
        if not check("app ran with Dana unticked", run.run_app(run.device_mark())):
            raise RuntimeError("no run finished")
        second = run.record()
        after = rows(second)
        # The RCS group still has Sam ticked, so its rows stay under a label without Dana; nothing else changes.
        rcs_thread = f"thread-{groups['rcs']}"

        def unlabelled_rcs(found):
            return [{k: v for k, v in r.items() if k != "contact" or r["number"] != rcs_thread} for r in found]
        check("nothing was removed and nothing new of Dana's came in", unlabelled_rcs(after) == unlabelled_rcs(got)
              and {r["contact"] for r in after if r["number"] == rcs_thread} == {"Group: Sam - Work +2"},
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

        # Run 3b: Mom ticked as well. The conversations already filed keep their identity: the chat of Dana and Mom stays
        # Dana's chat and the group stays the same group under a new label, so none of their texts come in twice.
        answer = run.tick([MOM])
        check("Mom ticked through the test hook", answer == "ticked 1", answer)
        if not check("app ran with Mom ticked", run.run_app(run.device_mark())):
            raise RuntimeError("no run finished")
        with_mom = rows(run.record())
        # Only a group's label may change, since its people's names are in it.
        def unlabelled(found):
            return [{k: v for k, v in r.items() if k != "contact" or not r["number"].startswith("thread-")} for r in found]
        check("every earlier row is intact after ticking Mom", all(r in unlabelled(with_mom) for r in unlabelled(latest)),
              str([r for r in unlabelled(latest) if r not in unlabelled(with_mom)][:2]))
        if "pair" in groups:
            pair = [r for r in with_mom if r["epoch"] == T + 2100]
            check("the chat of Dana and Mom stays Dana's chat, once", len(pair) == 1 and pair[0]["number"] == DANA1
                  and pair[0]["contact"] == "Dana-Work", str(pair))
        if "group" in groups:
            group = [r for r in with_mom if r["epoch"] == T + 2000]
            check("the group stays one group, now labelled with Mom", len(group) == 1
                  and group[0]["number"] == f"thread-{groups['group']}" and group[0]["contact"] == "Group: Dana-Work, Mom +1", str(group))
        latest = with_mom
        # Dana unticked while her contact is gone, for Who counts to show further on.
        answer = run.tick([DANA1], untick=True)
        check("Dana unticked again through the test hook", answer == "unticked 1", answer)

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

        # The month screen, opened with everything allowed again: the month's title, its three totals rows and its grid,
        # counted as the reference counts the same record.
        for permission in PERMISSIONS:
            run.sh("pm", "grant", APP, permission)
        run.sh("am", "force-stop", APP)
        run.sh("am", "start", "-W", "-n", f"{APP}/.MainActivity")
        time.sleep(3)
        copy = out / "screen.db"
        shutil.copyfile(run.record() / DATABASE, copy)
        store = reference.Store(copy)
        everything = store.totals()
        nodes = run.wait_for(lambda ns: (text_of(ns, "total_all") or "").endswith(row_value(everything)))
        month = run.adb("shell", "date", "+%Y-%m").strip()
        year, number = int(month[:4]), int(month[5:])
        name = datetime(year, number, 1).strftime("%B")
        last = (datetime(year + number // 12, number % 12 + 1, 1) - datetime(year, number, 1)).days
        check("the month screen opens on this month", text_of(nodes, "month_title") == f"{name} {year}", str(text_of(nodes, "month_title")))
        check("the month row shows this month's totals",
              text_of(nodes, "total_month") == f"Month\n{row_value(store.totals(f'{month}-01', f'{month}-{last:02d}'))}",
              repr(text_of(nodes, "total_month")))
        start = datetime.strptime("2026-10-25", "%Y-%m-%d")
        today = datetime.strptime(run.adb("shell", "date", "+%Y-%m-%d").strip(), "%Y-%m-%d")
        first = start + timedelta(days=(today - start).days // 14 * 14)
        period_to = first + timedelta(days=13)
        period = store.totals(first.strftime("%Y-%m-%d"), period_to.strftime("%Y-%m-%d"))
        check("the pay period row shows the current pay period's totals",
              (text_of(nodes, "total_period") or "").startswith("Pay period ")
              and (text_of(nodes, "total_period") or "").endswith("\n" + row_value(period)), repr(text_of(nodes, "total_period")))
        check("the All time row shows the record's totals", text_of(nodes, "total_all") == f"All time\n{row_value(everything)}",
              repr(text_of(nodes, "total_all")))
        check("the grid holds every day of the month", len(by_id(nodes, "grid")) == 1 and len(by_id(nodes, "day_cell")) == last,
              f"{len(by_id(nodes, 'day_cell'))} cells")
        small, outside = screen_problems(nodes, density)
        check("every button on the month screen is at least 40dp tall and nothing reaches past the screen",
              not small and not outside, f"small {small[:3]} outside {outside[:3]}")
        # 0.9.5: >> comes back to this month, and is off while this month is shown.
        check(">> is there and off on this month", [n.get("enabled") for n in by_id(nodes, "month_now")] == ["false"],
              str([n.get("enabled") for n in by_id(nodes, "month_now")]))
        # Back to the seeded month: its day shows the call time and texts the reference counts for it.
        seeded = day[:7]
        seeded_title = datetime.strptime(seeded, "%Y-%m").strftime("%B %Y")

        def back_to_seeded(nodes):
            for _ in range(24):
                if text_of(nodes, "month_title") == seeded_title:
                    break
                run.tap(by_id(nodes, "month_prev")[0])
                time.sleep(1)
                nodes = run.nodes()
            return nodes

        nodes = back_to_seeded(nodes)
        if seeded != month:
            check(">> is on away from this month", [n.get("enabled") for n in by_id(nodes, "month_now")] == ["true"],
                  str([n.get("enabled") for n in by_id(nodes, "month_now")]))
            run.tap(by_id(nodes, "month_now")[0])
            nodes = run.wait_for(lambda ns: text_of(ns, "month_title") == f"{name} {year}", 15)
            check(">> comes back to this month", text_of(nodes, "month_title") == f"{name} {year}", str(text_of(nodes, "month_title")))
            # Tapping the month's name opens the picker on this year; the seeded month's button lands on it.
            run.shot("10-month-screen")
            run.tap(by_id(nodes, "month_title")[0])
            nodes = run.wait_for(lambda ns: bool(by_id(ns, "pick_year")), 15)
            run.shot("10b-month-picker")
            short = seeded_title[:3]
            picks = {n.get("text"): n for n in by_id(nodes, "month_pick")}
            check("the picker opens on the shown year with twelve months",
                  text_of(nodes, "pick_year") == str(year) and len(picks) == 12, f"{text_of(nodes, 'pick_year')} {sorted(picks)}")
            later = datetime(year + number // 12, number % 12 + 1, 1).strftime("%b")
            check("the picker has next month off" if number < 12 else "the picker's next year is off",
                  (picks.get(later) is not None and picks[later].get("enabled") == "false") if number < 12
                  else [n.get("enabled") for n in by_id(nodes, "pick_next")] == ["false"],
                  str({k: v.get("enabled") for k, v in picks.items()}))
            small, outside = screen_problems(nodes, density)
            check("every button in the picker is at least 40dp tall and nothing reaches past the screen",
                  not small and not outside, f"small {small[:3]} outside {outside[:3]}")
            if seeded[:4] != str(year):
                for _ in range(int(year) - int(seeded[:4])):
                    run.tap(by_id(nodes, "pick_prev")[0])
                    time.sleep(1)
                nodes = run.wait_for(lambda ns: text_of(ns, "pick_year") == seeded[:4], 15)
                picks = {n.get("text"): n for n in by_id(nodes, "month_pick")}
            check("the seeded month can be picked", picks.get(short) is not None and picks[short].get("enabled") == "true",
                  str({k: v.get("enabled") for k, v in picks.items()}))
            if picks.get(short) is not None:
                run.tap(picks[short])
            nodes = run.wait_for(lambda ns: text_of(ns, "month_title") == seeded_title and not by_id(ns, "pick_year"), 15)
            check("picking the seeded month shows it", text_of(nodes, "month_title") == seeded_title and not by_id(nodes, "pick_year"),
                  str(text_of(nodes, "month_title")))
            nodes = back_to_seeded(nodes)
        else:
            print("info  skipped: the emulator's date is in the seeded month, so the >> and month picker checks need "
                  "another month to start from")
        following = (datetime.strptime(seeded, "%Y-%m").replace(day=28) + timedelta(days=4)).replace(day=1)
        seeded_totals = store.totals(f"{seeded}-01", (following - timedelta(days=1)).strftime("%Y-%m-%d"))
        nodes = run.wait_for(lambda ns: (text_of(ns, "total_month") or "").endswith(row_value(seeded_totals)), 20)
        busy = store.range(day, day)["days"][day]
        words = f"{datetime.strptime(day, '%Y-%m-%d').strftime('%B')} {int(day[8:])}, call time {full_time(busy['call_s'])}, {plural(busy['texts'], 'text')}"
        cells = [n.get("content-desc") for n in by_id(nodes, "day_cell")]
        check("the seeded month's total and its busy day match the reference", words in cells
              and text_of(nodes, "total_month").endswith(row_value(seeded_totals)), f"{words!r} in {cells[20:30]}")
        run.shot("10c-seeded-month")
        # 0.9.12: a sideways swipe on the calendar moves one month, within the arrows' limits: right for the month before,
        # left for the next. Each swipe runs across the grid from 20 to 80 percent of its width, starting on a day.
        def swipe(ns, rightward):
            (left, top), (right, bottom) = bounds(by_id(ns, "grid")[0])
            y = (top + bottom) // 2
            near, far = left + (right - left) // 5, left + (right - left) * 4 // 5
            run.sh("input", "swipe", *((near, y, far, y) if rightward else (far, y, near, y)), 200)

        before_title = (datetime.strptime(seeded, "%Y-%m") - timedelta(days=1)).strftime("%B %Y")
        after_title = following.strftime("%B %Y")
        can_back = [n.get("enabled") for n in by_id(nodes, "month_prev")] == ["true"]
        can_on = [n.get("enabled") for n in by_id(nodes, "month_next")] == ["true"]
        if by_id(nodes, "grid") and (can_back or can_on):
            # Right then left when there is a month before the seeded one; otherwise left then right.
            away, there = (True, before_title) if can_back else (False, after_title)
            swipe(nodes, away)
            nodes = run.wait_for(lambda ns: text_of(ns, "month_title") == there, 15)
            moved = text_of(nodes, "month_title")
            check(f"a swipe {'right' if away else 'left'} on the calendar shows {'the month before' if away else 'the next month'}",
                  moved == there, f"{moved!r} want {there!r}")
            if by_id(nodes, "grid"):
                swipe(nodes, not away)
            nodes = run.wait_for(lambda ns: text_of(ns, "month_title") == seeded_title
                                 and (text_of(ns, "total_month") or "").endswith(row_value(seeded_totals)), 15)
            check(f"a swipe {'left' if away else 'right'} brings the seeded month back with its totals",
                  text_of(nodes, "month_title") == seeded_title and text_of(nodes, "total_month").endswith(row_value(seeded_totals)),
                  f"{text_of(nodes, 'month_title')!r} {text_of(nodes, 'total_month')!r}")
        else:
            print("info  skipped: the swipe checks, as the seeded month has no month on either side to swipe to")
        nodes = back_to_seeded(nodes)

        # 0.9.12: time per text, a rule set in Options. At 15 seconds the month row gets a third line, the seeded month's
        # texts times 15 seconds, with its call time unchanged; it goes back to 0 after the day view and by-contact shots.
        def set_text_time(seconds, want_line, shoot=False):
            ns = run.wait_for(lambda n: bool(by_id(n, "gear")), 15)
            run.tap(by_id(ns, "gear")[0])
            ready = lambda n: [b.get("enabled") for b in by_id(n, "text_time_change")] == ["true"]
            ns = run.wait_for(ready, 15)
            if not ready(ns):
                run.swipe_up()
                ns = run.wait_for(ready, 15)
            run.tap(by_id(ns, "text_time_change")[0])
            ns = run.wait_for(lambda n: bool(by_id(n, "text_time_value")), 15)
            run.tap(by_id(ns, "text_time_value")[0])
            run.sh("input", "keyevent", "KEYCODE_MOVE_END", *["KEYCODE_DEL"] * 6)
            run.sh("input", "text", str(seconds))
            time.sleep(1)
            # Hides the on-screen keyboard if it shows, so it covers nothing; Back with no keyboard would close the window.
            if "mInputShown=true" in run.adb("shell", "dumpsys", "input_method", check=False):
                run.sh("input", "keyevent", "KEYCODE_BACK")
                time.sleep(1)
            ns = run.wait_for(lambda n: [b.get("enabled") for b in by_id(n, "confirm")] == ["true"], 15)
            said = text_of(ns, "text_time_review")
            small, outside = screen_problems(ns, density)
            if by_id(ns, "confirm"):
                run.tap(by_id(ns, "confirm")[0])
            ns = run.wait_for(lambda n: text_of(n, "text_time_line") == want_line and not by_id(n, "text_time_value"), 15)
            shown = text_of(ns, "text_time_line")
            if shoot:
                # 0.9.13: the whole Options screen, scrolled to the top, and its bottom when it is taller than the screen.
                size = run.adb("shell", "wm", "size").strip().splitlines()[-1].split(":")[-1].strip()
                width, height = (int(v) for v in size.split("x"))
                for _ in range(2):
                    run.sh("input", "swipe", width // 2, height // 4, width // 2, height * 3 // 4, 400)
                time.sleep(1)
                run.shot("14-options")
                if not by_id(run.nodes(), "version_line"):
                    for _ in range(4):
                        run.swipe_up()
                    run.shot("14b-options-bottom")
            run.sh("input", "keyevent", "KEYCODE_BACK")
            return said, shown, small, outside

        said, shown, small, outside = set_text_time(15, "Time per text: 15 seconds", shoot=True)
        check("Time per text set to 15 in Options: the window reads off → 15 seconds and Options shows the new rule",
              said == "Time per text: off → 15 seconds" and shown == "Time per text: 15 seconds" and not small and not outside,
              f"{said!r} {shown!r} small {small[:3]} outside {outside[:3]}")
        with_text = f"Month\n{row_value(seeded_totals)}\nText time {full_time(seeded_totals['texts'] * 15)}"
        nodes = run.wait_for(lambda ns: text_of(ns, "month_title") == seeded_title and text_of(ns, "total_month") == with_text, 20)
        check("at 15 seconds per text the month row adds the text time on its own line, its call time unchanged",
              text_of(nodes, "total_month") == with_text, f"{text_of(nodes, 'total_month')!r} want {with_text!r}")
        run.shot("13-text-time")
        # 0.9.8: a look at the busy day's own screen and at the month's people, for the screenshots only.
        cell = next((n for n in by_id(nodes, "day_cell") if n.get("content-desc") == words), None)
        if cell is not None:
            run.tap(cell)
            day_nodes = run.wait_for(lambda ns: bool(by_id(ns, "tab_calls")) or bool(by_id(ns, "entry_action")), 15)
            time.sleep(1)
            # 0.9.12: the day's text time, its texts times 15 seconds, only when the day has a text.
            want_day = f"Text time {full_time(busy['texts'] * 15)}" if busy["texts"] > 0 else None
            day_nodes = run.wait_for(lambda ns: text_of(ns, "text_time") == want_day, 10)
            check("at 15 seconds per text the day view shows the day's text time",
                  text_of(day_nodes, "text_time") == want_day, f"{text_of(day_nodes, 'text_time')!r} want {want_day!r}")
            run.shot("11-day-view")
            run.sh("input", "keyevent", "KEYCODE_BACK")
            nodes = run.wait_for(lambda ns: text_of(ns, "month_title") == seeded_title, 15)
        else:
            print("info  skipped: the day view shot, as the seeded busy day's cell was not found")
        if by_id(nodes, "total_month"):
            run.tap(by_id(nodes, "total_month")[0])
            run.wait_for(lambda ns: bool(by_id(ns, "contact_row")), 15)
            time.sleep(1)
            # 0.9.12: the sheet's header has the month's text time, its texts times 15 seconds.
            want_month = f"Text time {full_time(seeded_totals['texts'] * 15)}"
            sheet = run.wait_for(lambda ns: text_of(ns, "text_time") == want_month, 10)
            check("at 15 seconds per text the by-contact header shows the month's text time",
                  text_of(sheet, "text_time") == want_month, f"{text_of(sheet, 'text_time')!r} want {want_month!r}")
            run.shot("12-by-contact")
            run.sh("input", "keyevent", "KEYCODE_BACK")
            run.wait_for(lambda ns: text_of(ns, "month_title") == seeded_title, 15)
        else:
            print("info  skipped: the by-contact shot, as the month row was not found")
        said, shown, _, _ = set_text_time(0, "Time per text: off")
        nodes = run.wait_for(lambda ns: text_of(ns, "month_title") == seeded_title
                             and text_of(ns, "total_month") == f"Month\n{row_value(seeded_totals)}", 20)
        settings = dict(sqlite3.connect(f"file:{run.record() / DATABASE}?mode=ro", uri=True).execute("SELECT key,value FROM settings").fetchall())
        check("Time per text back to 0: the window reads 15 seconds → off, and the month row loses its text time line",
              said == "Time per text: 15 seconds → off" and shown == "Time per text: off"
              and text_of(nodes, "total_month") == f"Month\n{row_value(seeded_totals)}" and settings.get("text_seconds") == "0",
              f"{said!r} {shown!r} {text_of(nodes, 'total_month')!r} {settings.get('text_seconds')!r}")
        nodes = run.nodes()
        # The same month in dark, for a look at the busy day's tile; the screen is drawn again and keeps its month.
        try:
            run.adb("shell", "cmd", "uimode", "night", "yes")
            time.sleep(3)
            dark = run.wait_for(lambda ns: text_of(ns, "month_title") == seeded_title, 10)
            if text_of(dark, "month_title") == seeded_title:
                run.shot("10d-seeded-month-dark")
            else:
                print(f"info  skipped: the dark shot of the seeded month, as the screen came back on "
                      f"{text_of(dark, 'month_title')!r}")
        finally:
            run.adb("shell", "cmd", "uimode", "night", "no", check=False)
            time.sleep(3)
        nodes = run.nodes()

        # 0.9.12: a sideways swipe that starts on the pay period line steps the pay period, within its arrows' limits.
        # Periods starting the day after the seeded day leave the seeded day in the period before the current one, so
        # there is a period to swipe back to; the Oct 25 start comes back afterwards.
        after_seed = (datetime.strptime(day, "%Y-%m-%d") + timedelta(days=1)).strftime("%Y-%m-%d")
        answer = run.pay(after_seed)
        run.sh("am", "force-stop", APP)
        run.sh("am", "start", "-W", "-n", f"{APP}/.MainActivity")
        nodes = run.wait_for(lambda ns: (text_of(ns, "total_period") or "").startswith("Pay period ")
                             and [n.get("enabled") for n in by_id(ns, "period_prev")] == ["true"], 30)
        if answer == "pay period set" and [n.get("enabled") for n in by_id(nodes, "period_prev")] == ["true"]:
            def period_label(ns):
                return (text_of(ns, "total_period") or "").split("\n")[0]

            def swipe_period(ns, rightward):
                (left, top), (right, bottom) = bounds(by_id(ns, "total_period")[0])
                y = (top + bottom) // 2
                near, far = left + (right - left) // 5, left + (right - left) * 4 // 5
                run.sh("input", "swipe", *((near, y, far, y) if rightward else (far, y, near, y)), 200)

            current_label, month_shown = period_label(nodes), text_of(nodes, "month_title")
            swipe_period(nodes, True)
            nodes = run.wait_for(lambda ns: period_label(ns).startswith("Pay period ") and period_label(ns) != current_label, 15)
            earlier_label = period_label(nodes)
            check("a swipe right on the pay period line shows the period before, and the month stays",
                  earlier_label.startswith("Pay period ") and earlier_label != current_label and text_of(nodes, "month_title") == month_shown,
                  f"{current_label!r} then {earlier_label!r}, month {text_of(nodes, 'month_title')!r} was {month_shown!r}")
            swipe_period(nodes, False)
            nodes = run.wait_for(lambda ns: period_label(ns) == current_label, 15)
            check("a swipe left on the pay period line brings the current period back",
                  period_label(nodes) == current_label and text_of(nodes, "month_title") == month_shown,
                  f"{period_label(nodes)!r} want {current_label!r}")
        else:
            print(f"info  skipped: the pay period swipe checks, as no earlier period holds entries ({answer})")
        answer = run.pay("2026-10-25")
        check("pay period set back to the Oct 25 start through the test hook", answer == "pay period set", answer)

        # Who counts: Dana, unticked and no longer on the phone, shows unticked; a search with letters looks up names only.
        run.sh("input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)
        run.sh("am", "force-stop", APP)
        run.sh("am", "start", "-W", "-n", f"{APP}/.MainActivity")
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "gear")), 30)
        run.tap(by_id(nodes, "gear")[0])
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "who_open")), 30)
        run.tap(by_id(nodes, "who_open")[0])
        nodes = run.wait_for(lambda ns: "Dana-Work" in boxes(ns), 30)
        shown = boxes(nodes)
        check("Who counts shows Dana unticked though she is no longer on the phone",
              shown.get("Dana-Work") == "false" and shown.get("Sam - Work") == "true" and shown.get("Mom") == "true", str(shown))
        run.tap(by_id(nodes, "who_search")[0])
        run.sh("input", "text", "zz9")
        letters = boxes(run.wait_for(lambda ns: "Mom" not in boxes(ns), 15))
        run.sh("input", "keyevent", "KEYCODE_MOVE_END", "KEYCODE_DEL", "KEYCODE_DEL", "KEYCODE_DEL")
        run.sh("input", "text", "999")
        digits = boxes(run.wait_for(lambda ns: list(boxes(ns)) == ["Mom"], 15))
        check("Who counts' search: zz9 finds nobody, 999 finds Mom by her number", letters == {} and list(digits) == ["Mom"],
              f"zz9 {letters} 999 {digits}")

        # A damaged record is moved aside, never deleted, and the month screen says so.
        run.sh("am", "force-stop", APP)
        base = f"/data/data/{APP}/databases/{DATABASE}"
        size = int(run.adb("shell", "stat", "-c", "%s", base).strip())
        run.adb("shell", f"dd if=/dev/zero of={base} bs=100 count=1 conv=notrunc")
        run.sh("am", "start", "-W", "-n", f"{APP}/.MainActivity")
        nodes = run.wait_for(lambda ns: "damaged" in (text_of(ns, "damaged") or ""), 40)
        listing = run.adb("shell", "ls", "-l", f"/data/data/{APP}/databases/")
        aside = [line for line in listing.splitlines() if ".damaged-" in line and not line.rstrip().endswith(("-journal", "-wal", "-shm"))]
        check("a damaged record is kept under a new name and the month screen says so",
              len(aside) == 1 and str(size) in aside[0].split() and "kept as calltally.db.damaged-" in (text_of(nodes, "damaged") or ""),
              f"{listing.strip()[:300]} | {text_of(nodes, 'damaged')!r}")
        if by_id(nodes, "damaged_ok"):
            run.tap(by_id(nodes, "damaged_ok")[0])
        gone = run.wait_for(lambda ns: bool(by_id(ns, "month_title")) and not by_id(ns, "damaged"), 15)
        run.sh("am", "force-stop", APP)
        run.sh("am", "start", "-W", "-n", f"{APP}/.MainActivity")
        again = run.wait_for(lambda ns: bool(by_id(ns, "month_title")), 30)
        time.sleep(2)
        again = run.nodes()
        check("OK hides the damaged-record note, and it stays hidden when the app opens again",
              not by_id(gone, "damaged") and bool(by_id(again, "month_title")) and not by_id(again, "damaged"),
              repr(text_of(again, "damaged")))

        # An update over the uploading app: what 0.6.0 leaves is its list of what it sent, its last run's words and its
        # permissions, and no record. The same build is installed over that and must open on the first-start page with
        # the old files gone and nothing of the old run shown.
        run.sh("am", "force-stop", APP)
        run.adb("uninstall", APP, timeout=120)
        run.adb("install", run.apk, timeout=180)
        run.grant()
        old_words = "Nov 4, 9:41 PM\n1210 calls and texts found, 3 uploaded."
        run.put("files/sent.txt", b"abc123\ndef456\n")
        run.put("shared_prefs/status.xml", (
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            f"    <string name=\"status\">{old_words}</string>\n"
            "    <string name=\"problem\">Upload failed: server unreachable</string>\n"
            "    <string name=\"crash\">IOException at Sync.java:212</string>\n"
            "</map>\n").encode())
        before = (run.app_files("files"), run.app_files("shared_prefs"), run.app_files("databases"))
        run.adb("install", "-r", run.apk, timeout=180)
        run.sh("am", "start", "-W", "-n", f"{APP}/.MainActivity")
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "start_continue")), 30)
        run.shot("06-first-start-after-update")
        status = run.adb("exec-out", "run-as", APP, "cat", "shared_prefs/status.xml", check=False)
        check("an update over 0.6.0's files opens on the first-start page, with sent.txt and the old run's words gone",
              "sent.txt" in before[0] and "status.xml" in before[1] and not before[2] and bool(by_id(nodes, "start_continue"))
              and "sent.txt" not in run.app_files("files") and "uploaded" not in status and "Upload" not in status and "crash" not in status,
              f"before {before} after {run.app_files('files')} status {status[:200]!r}")
        run.tap(by_id(nodes, "start_continue")[0])
        nodes = run.wait_for(lambda ns: bool(by_id(ns, "who_next")), 20)
        check("with the old app's permissions kept, Continue goes straight to Who counts", bool(by_id(nodes, "who_next")))
        crashes = run.adb("logcat", "-d", "-b", "crash", check=False)
        check("the app never crashed during the check", APP not in crashes, crashes.strip()[-400:])
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
