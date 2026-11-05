#!/usr/bin/env python3
"""Checks the Android app end to end on the emulator, against a test server on this laptop.

Starts the emulator, starts calltally.py on 127.0.0.1:18100 with an empty data folder and a record of every upload and
status body, builds and installs a test copy of the app, seeds contacts, calls and messages, runs the app and checks what
reached the server. Then it takes the app's permissions away and checks again. Prints one pass or fail line per check,
exits 1 on any fail, and always stops the emulator, adb and the server.

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
import urllib.request
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
PORT = 18100
APP = "com.calltally.app"
PERMISSIONS = ("android.permission.READ_CALL_LOG", "android.permission.READ_SMS", "android.permission.READ_CONTACTS")
MARKER = "zebra marmalade 4417"
NOT_ALLOWED = "Needs permission. Open this app to allow it."
FIELDS = {"kind", "at", "epoch", "contact", "number", "direction", "duration_s"}
T = 1793000000  # 2026-10-26 in the morning, Pacific time. Every seeded time is fixed, so two runs send the same bytes.

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
        self.server = None
        self.record = out / "record.txt"
        self.data = out / "server-data"

    # adb and the emulator

    def adb(self, *args, check=True, timeout=120):
        done = subprocess.run([str(ADB), "-e", *args], capture_output=True, text=True, env=self.env, timeout=timeout)
        if check and done.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args[:3])} failed: {done.stderr.strip() or done.stdout.strip()}")
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

    # the test server

    def start_server(self):
        self.data.mkdir()
        self.record.write_bytes(b"")
        env = {**os.environ, "CALLTALLY_PORT": str(PORT), "CALLTALLY_DATA": str(self.data), "CALLTALLY_RECORD": str(self.record)}
        self.server = subprocess.Popen([sys.executable, str(ROOT / "calltally.py")], env=env,
                                       stdout=open(self.out / "server.log", "wb"), stderr=subprocess.STDOUT)
        for _ in range(50):
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/api/health", timeout=2) as reply:
                    if json.load(reply)["version"] == calltally.VERSION:
                        return
            except OSError:
                time.sleep(0.2)
        raise RuntimeError("the test server didn't start; see server.log")

    def stop_server(self):
        if self.server is not None:
            self.server.terminate()
            try:
                self.server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.server.kill()
            self.server = None

    def recorded(self):
        lines = []
        for line in self.record.read_bytes().splitlines():
            path, _, body = line.partition(b" ")
            lines.append((path.decode(), body))
        return lines

    def wait_for_status(self, count, seconds=240):
        """Waits until the app has sent its run's status line, the last thing a run does."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            if sum(path == "/api/status" for path, _ in self.recorded()) >= count:
                time.sleep(3)  # anything else the run sends after it
                return True
            time.sleep(1)
        return False

    def meta(self):
        conn = sqlite3.connect(f"file:{self.data / 'calltally.db'}?mode=ro", uri=True)
        try:
            return dict(conn.execute("SELECT key,value FROM meta").fetchall())
        finally:
            conn.close()

    def rows(self):
        conn = sqlite3.connect(f"file:{self.data / 'calltally.db'}?mode=ro", uri=True)
        conn.row_factory = sqlite3.Row
        try:
            return [dict(r) for r in conn.execute("SELECT * FROM entries ORDER BY epoch,id")]
        finally:
            conn.close()

    def api(self, path):
        with urllib.request.urlopen(f"http://127.0.0.1:{PORT}{path}", timeout=10) as reply:
            return json.load(reply)

    # the app and its data

    def build_and_install(self):
        env = {**os.environ, "CALLTALLY_SERVER": f"http://10.0.2.2:{PORT}", "CALLTALLY_CLEARTEXT": "true", "CALLTALLY_OUT": "emu"}
        done = subprocess.run(["sh", str(ROOT / "android/app/build.sh")], capture_output=True, text=True, env=env, cwd=ROOT)
        (self.out / "build.log").write_text(done.stdout + done.stderr)
        if done.returncode != 0:
            raise RuntimeError("the test app didn't build; see build.log")
        apk = done.stdout.strip().splitlines()[-1]
        self.adb("install", "-r", apk, timeout=180)
        for permission in PERMISSIONS:
            self.sh("pm", "grant", APP, permission)

    def last_id(self, uri):
        out = self.sh("content", "query", "--uri", uri, "--projection", "_id", "--sort", "_id DESC")
        return int(out.split("_id=")[1].split()[0].rstrip(","))

    def thread_for(self, *numbers):
        query = "&".join(f"recipient={n}" for n in numbers)
        out = self.sh("content", "query", "--uri", f"content://mms-sms/threadID?{query}")
        return int(out.split("_id=")[1].split()[0].rstrip(","))

    def seed(self):
        for name, numbers in CONTACTS:
            self.sh("content", "insert", "--uri", "content://com.android.contacts/raw_contacts",
                    "--bind", "account_type:n:", "--bind", "account_name:n:")
            raw = self.last_id("content://com.android.contacts/raw_contacts")
            self.sh("content", "insert", "--uri", "content://com.android.contacts/data", "--bind", f"raw_contact_id:i:{raw}",
                    "--bind", "mimetype:s:vnd.android.cursor.item/name", "--bind", f"data1:s:{name}")
            for number in numbers:
                self.sh("content", "insert", "--uri", "content://com.android.contacts/data", "--bind", f"raw_contact_id:i:{raw}",
                        "--bind", "mimetype:s:vnd.android.cursor.item/phone_v2", "--bind", f"data1:s:{number}", "--bind", "data2:i:2")
        for number, offset, kind, duration in CALLS:
            self.sh("content", "insert", "--uri", "content://call_log/calls", "--bind", f"number:s:{number}",
                    "--bind", f"date:l:{(T + offset) * 1000}", "--bind", f"type:i:{kind}", "--bind", f"duration:l:{duration}")
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

    def device_zone(self):
        return self.adb("shell", "getprop", "persist.sys.timezone").strip() or "UTC"


def expected(groups, zone):
    """What the app must send, worked out by hand from the seed: one dict per entry, in no particular order."""
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
        # Two messages in the same second: both are sent, the server keeps one.
        item("text", DANA2, T + 1300, "in", "Dana-Work"),
        item("text", DANA2, T + 1300, "in", "Dana-Work"),
    ]
    if "group" in groups:
        out.append(item("text", f"thread-{groups['group']}", T + 2000, "in", "Group: Dana-Work +2"))
    if "pair" in groups:
        # A chat with one work contact and one other person shows under that contact.
        out.append(item("text", DANA1, T + 2100, "in", "Dana-Work"))
    return out


def key(e):
    return json.dumps(e, sort_keys=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", help="folder for the logs and the record of request bodies")
    args = parser.parse_args()
    out = Path(args.out) if args.out else Path(tempfile.mkdtemp(prefix="calltally-emu-", dir=os.environ.get("TMPDIR")))
    out.mkdir(parents=True, exist_ok=True)
    for name in ("record.txt", "server-data"):
        target = out / name
        if target.is_dir():
            shutil.rmtree(target)
        elif target.exists():
            target.unlink()
    check = Check()
    run = Run(out)
    print(f"Logs and recorded bodies: {out}", flush=True)
    try:
        run.start_emulator()
        check("emulator started", True)
        run.start_server()
        check("test server started", True)
        run.build_and_install()
        check("test app built, installed and allowed", True)
        groups = run.seed()
        check("contacts, calls, texts and group messages seeded", True)
        want = expected(groups, run.device_zone())

        # Run 1: everything allowed.
        run.sh("am", "start", "-n", f"{APP}/.MainActivity")
        if not check("app ran and reported", run.wait_for_status(1)):
            raise RuntimeError("no run finished")
        first = run.recorded()
        (out / "run1.txt").write_bytes(b"".join(p.encode() + b" " + b + b"\n" for p, b in first))
        uploads = [json.loads(b) for p, b in first if p == "/api/upload"]
        statuses = [json.loads(b) for p, b in first if p == "/api/status"]
        sent = [e for u in uploads for e in u["entries"]]
        everything = b"".join(b for _, b in first)
        check("the marker phrase never left the phone", MARKER.encode() not in everything
              and MARKER.split()[0].encode() not in everything)
        check("each entry has only the seven upload fields", all(set(e) == FIELDS for e in sent),
              str([sorted(e) for e in sent if set(e) != FIELDS][:1]))
        got, need = sorted(map(key, sent)), sorted(map(key, want))
        check("exactly the expected entries were uploaded", got == need,
              f"extra {sorted(set(got) - set(need))[:3]} missing {sorted(set(need) - set(got))[:3]}")
        blob = json.dumps(sent)
        check("nothing from Mom or Work Plumbing", all(s not in blob for s in ("Mom", "Plumbing", MOM, PLUMBING)))
        check("no draft uploaded", not any(e["epoch"] == T + 1200 for e in sent))
        check("no blocked or answered-elsewhere call", not any(e["epoch"] in (T + 400, T + 600) for e in sent))
        rows = run.rows()
        ids = {calltally.entry_id(e["kind"], e["number"], e["epoch"], e["direction"]) for e in want}
        check("the server holds exactly the expected ids", {r["id"] for r in rows} == ids,
              f"{len(rows)} rows, {len(ids)} expected")
        check("the same-second pair is counted once", sum(r["epoch"] == T + 1300 for r in rows) == 1)
        unanswered = [r for r in rows if r["direction"] in ("missed", "rejected")]
        day = datetime.fromtimestamp(T, ZoneInfo(run.device_zone())).strftime("%Y-%m-%d")
        counted = run.api(f"/api/range?from={day}&to={day}")["days"].get(day, {})
        check("missed and declined calls are listed with no time counted",
              len(unanswered) == 2 and counted.get("call_s") == 872 + 3968 and counted.get("calls") == 2
              and counted.get("unanswered") == 2, str(counted))
        meta = run.meta()
        check("last_upload set", bool(meta.get("last_upload")))
        check("the run reported a clean status", statuses and statuses[-1] == {"text": ""} and "phone_status" not in meta,
              str(statuses))
        if "group" in groups:
            group = [r for r in rows if r["number"] == f"thread-{groups['group']}"]
            check("the group message shows under the group with two other people",
                  len(group) == 1 and group[0]["contact"] == "Group: Dana-Work +2", str(group))
        database = b"".join(p.read_bytes() for p in run.data.glob("calltally.db*"))
        check("the marker phrase is not in the server's database", MARKER.encode() not in database)

        # Run 2: texts refused. The app needs all three permissions, so nothing goes up and the page says so.
        last_upload = meta.get("last_upload")
        run.sh("pm", "revoke", APP, "android.permission.READ_SMS")
        run.sh("cmd", "jobscheduler", "run", "-f", APP, "1")
        reported = run.wait_for_status(len(statuses) + 1)
        second = run.recorded()[len(first):]
        check("without SMS access the app ran and reported", reported)
        check("without SMS access nothing was uploaded", not any(p == "/api/upload" for p, _ in second), str(second[:2]))
        check("without SMS access the page says the app isn't allowed",
              run.meta().get("phone_status") == NOT_ALLOWED and run.meta().get("last_upload") == last_upload,
              str(run.meta().get("phone_status")))

        # Run 3: nothing allowed.
        count = sum(p == "/api/status" for p, _ in run.recorded())
        for permission in PERMISSIONS:
            run.sh("pm", "revoke", APP, permission)
        before = len(run.recorded())
        run.sh("cmd", "jobscheduler", "run", "-f", APP, "1")
        reported = run.wait_for_status(count + 1)
        third = run.recorded()[before:]
        check("with no access the app ran and reported", reported)
        check("with no access nothing was uploaded", not any(p == "/api/upload" for p, _ in third), str(third[:2]))
        check("with no access the page says the app isn't allowed",
              [json.loads(b) for p, b in third if p == "/api/status"][-1:] == [{"text": NOT_ALLOWED}]
              and run.meta().get("phone_status") == NOT_ALLOWED and len(run.rows()) == len(rows))
        check("group message seeded" if groups else "group message not seeded (provider refused)", True)
    except Exception as error:  # noqa: BLE001 - every failure is reported as a fail line, then everything stops
        check("the check ran to the end", False, f"{type(error).__name__}: {error}")
    finally:
        run.stop_server()
        run.stop_emulator()
        subprocess.run([str(ADB), "kill-server"], capture_output=True, env=run.env, timeout=30)
    total = len(check.lines)
    print(f"{total - check.failed} passed, {check.failed} failed", flush=True)
    return 1 if check.failed else 0


if __name__ == "__main__":
    sys.exit(main())
