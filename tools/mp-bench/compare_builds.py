#!/usr/bin/env python3
"""Run the multiplayer profile against several module builds, back to back.

WHY THIS EXISTS

Two runs made days apart differ by more than any change worth measuring: the
machine's own state (background programs, driver, clocks) moves a whole run by
tens of percent. A build is therefore only compared with another build measured
in the same sitting, in an order that shows drift: A, B, A. If the two A runs
disagree, the sitting was not stable and nothing between them can be read.

  python tools/mp-bench/compare_builds.py --jar new=path/to/new.jar --jar old=path/to/old.jar \\
      --order new,old,new --scenes sp,mp-empty,lobby,hud-heavy --arms normal,fathom-off --rounds 3

Each step starts a fresh local server (mpbench.py serve), runs the client
(run_client.py) with that jar, waits for both to finish, and renames the
client's report to fathom-mp-<label>-<n>.txt/.csv in run-mp/logs. Only the
processes this script started are ever stopped, by their own handles.
"""

import argparse
import glob
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RUN = os.path.join(ROOT, "minecraft", "fathom-optimizations", "run-mp")
LOGS = os.path.join(RUN, "logs")


def newest_report():
    reports = glob.glob(os.path.join(LOGS, "fathom-mp-20*.txt"))
    return max(reports, key=os.path.getmtime) if reports else None


def wait_for(path, text, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                if text in handle.read():
                    return True
        except OSError:
            pass
        time.sleep(0.5)
    return False


def step(label, index, jar, args):
    stamp = "%s-%d" % (label, index)
    serve_log = os.path.join(RUN, "serve-%s.log" % stamp)
    print("[compare] %s: starting the server" % stamp, flush=True)
    with open(serve_log, "w", encoding="utf-8") as out:
        server = subprocess.Popen([sys.executable, "-u", os.path.join(HERE, "mpbench.py"), "serve",
                                   "--bots", str(args.bots)],
                                  cwd=ROOT, stdout=out, stderr=subprocess.STDOUT)
    try:
        if not wait_for(serve_log, "[serve] server up", 180):
            raise SystemExit("the server did not come up; see %s" % serve_log)
        before = newest_report()
        client = [sys.executable, os.path.join(HERE, "run_client.py"), "--jar", jar,
                  "--scenes", args.scenes, "--arms", args.arms, "--rounds", str(args.rounds),
                  "--seconds", str(args.seconds)]
        for extra in args.extra:
            client += ["--extra", extra]
        if args.jfr:
            client += ["--jfr", os.path.abspath(args.jfr.replace("{step}", stamp))]
        print("[compare] %s: client with %s" % (stamp, os.path.basename(jar)), flush=True)
        code = subprocess.call(client, cwd=ROOT)
        print("[compare] %s: client exited %d" % (stamp, code), flush=True)
        try:
            server.wait(timeout=60)
        except subprocess.TimeoutExpired:
            pass
        report = newest_report()
        if report and report != before:
            for ext in (".txt", ".csv"):
                source = report[:-4] + ext
                if os.path.exists(source):
                    target = os.path.join(LOGS, "fathom-mp-%s-%s%s" % (args.tag, stamp, ext))
                    shutil.copy(source, target)
            print("[compare] %s: report %s" % (stamp, os.path.basename(report)), flush=True)
        else:
            print("[compare] %s: no new report" % stamp, flush=True)
    finally:
        if server.poll() is None:
            # Ask it to finish the way a finished client does, so mpbench stops its own
            # java server on the way out. A hard stop would leave that child running.
            with open(os.path.join(RUN, "control", "done.txt"), "w", encoding="utf-8") as handle:
                handle.write("compare_builds" + chr(10))
            try:
                server.wait(timeout=60)
            except subprocess.TimeoutExpired:
                print("[compare] the server did not stop; it is pid %d" % server.pid, flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", action="append", required=True, help="label=path")
    parser.add_argument("--order", required=True, help="labels, comma-separated, e.g. new,old,new")
    parser.add_argument("--scenes", default="sp,mp-empty,mp-few,lobby,combat,combat-particles,nametags,hud-heavy,chunks")
    parser.add_argument("--arms", default="normal,fathom-off")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--seconds", type=float, default=3.0)
    parser.add_argument("--bots", type=int, default=40)
    parser.add_argument("--tag", default=time.strftime("%Y%m%d-%H%M"))
    parser.add_argument("--extra", action="append", default=[])
    parser.add_argument("--jfr", default="", help="record a flight recording; {step} becomes the step's label")
    args = parser.parse_args()

    jars = dict(item.split("=", 1) for item in args.jar)
    counts = {}
    for label in args.order.split(","):
        if label not in jars:
            raise SystemExit("no jar labelled %s" % label)
        counts[label] = counts.get(label, 0) + 1
        step(label, counts[label], os.path.abspath(jars[label]), args)


if __name__ == "__main__":
    main()
