#!/usr/bin/env python3
"""Start the Fathom client for the multiplayer profile, against tools/mp-bench's server.

The client runs as a production launch would -- Legacy Fabric's Knot, the
remapped Fathom jar from build/libs, OptiFabric and OptiFine -- in its own game
directory (run-mp), with copies of the `optimized` profile's video settings,
OptiFine settings and Fathom profile. Nothing of the user's is written: the
copies are taken once and the run writes only under run-mp.

It is an offline launch (`--userType legacy`, no token): the only server it
can join is the local offline-mode one, which is all this is for.

  python tools/mp-bench/run_client.py [--scenes ...] [--arms ...] [--rounds 3] [--jfr out.jfr]
"""

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MODULE = os.path.join(ROOT, "minecraft", "fathom-optimizations")
RUN = os.path.join(MODULE, "run-mp")
DATA = os.path.join(os.environ.get("USERPROFILE", os.path.expanduser("~")), "AppData", "Local", "Fathom", "data")
PROFILE = os.path.join(DATA, "profiles", "optimized")
JAVA8 = r"C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot\bin\java.exe"
USERNAME = "FathomBench"


def classpath():
    log = os.path.join(DATA, "logs", "launcher.log")
    found = None
    with open(log, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            match = re.search(r"-cp (\S+) net\.fabricmc", line)
            if match:
                found = match.group(1)
    if not found:
        raise SystemExit("No Fabric launch in %s: launch a Legacy Fabric profile from Fathom once." % log)
    return found


def stage(args):
    os.makedirs(os.path.join(RUN, "mods"), exist_ok=True)
    for old in glob.glob(os.path.join(RUN, "mods", "*.jar")):
        os.remove(old)
    if args.jar:
        # A specific build, for comparing two builds under the same conditions.
        jar = args.jar
    else:
        jars = [path for path in glob.glob(os.path.join(MODULE, "build", "libs", "fathom-optimizations-*.jar"))
                if not path.endswith("-sources.jar")]
        if not jars:
            raise SystemExit("Build the module first (gradle build).")
        jar = max(jars, key=os.path.getmtime)
    shutil.copy(jar, os.path.join(RUN, "mods", "fathom-optimizations.jar"))
    if not args.no_optifine:
        for pattern in ("optifabric-*.jar", "OptiFine_*.jar"):
            for path in glob.glob(os.path.join(PROFILE, "minecraft", "mods", pattern)):
                shutil.copy(path, os.path.join(RUN, "mods"))
    game = os.path.join(PROFILE, "minecraft")
    # Settings: copied every launch, because the game rewrites them on exit.
    options = open(os.path.join(game, "options.txt"), encoding="utf-8").read().splitlines()
    forced = {"fullscreen": "false", "pauseOnLostFocus": "false", "maxFps": "260", "enableVsync": "false"}
    out = []
    for line in options:
        key = line.split(":", 1)[0]
        if key in forced:
            line = "%s:%s" % (key, forced.pop(key))
        out.append(line)
    out += ["%s:%s" % item for item in forced.items()]
    with open(os.path.join(RUN, "options.txt"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(out) + "\n")
    if os.path.isfile(os.path.join(game, "optionsof.txt")):
        shutil.copy(os.path.join(game, "optionsof.txt"), RUN)
    os.makedirs(os.path.join(RUN, "config"), exist_ok=True)
    for path in glob.glob(os.path.join(game, "config", "*.properties")):
        shutil.copy(path, os.path.join(RUN, "config"))
    profiles = os.path.join(RUN, "fathom-profiles")
    if not os.path.isdir(profiles):
        os.makedirs(profiles)
        for name in ("default.json", "client-state.json"):
            source = os.path.join(DATA, "profiles", name)
            if os.path.isfile(source):
                shutil.copy(source, profiles)
    return profiles


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenes", default="sp,mp-empty,mp-few,lobby,combat,combat-particles,nametags,hud-heavy,chunks")
    parser.add_argument("--arms", default="normal,probes-off,fathom-off")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--seconds", type=float, default=3.0)
    parser.add_argument("--heap", default="4096")
    parser.add_argument("--jfr", default="")
    parser.add_argument("--no-optifine", action="store_true")
    parser.add_argument("--jar", default="", help="a specific Fathom module jar (default: the newest in build/libs)")
    parser.add_argument("--extra", action="append", default=[])
    args = parser.parse_args()
    # Entities that reach the 1.8 client before their chunk does are filed in an
    # empty placeholder chunk and never drawn; a populated scene measured straight
    # after connecting can draw none of its players. mp-empty first lets the world
    # load before any scene's entities arrive.
    scenes = args.scenes.split(",")
    if scenes[0] not in ("sp", "mp-empty"):
        args.scenes = ",".join(["mp-empty"] + [scene for scene in scenes if scene != "mp-empty"])
        print("[client] measuring mp-empty first so the world loads before the scene's entities")

    profiles = stage(args)
    natives = os.path.join(PROFILE, "natives")
    jvm = [
        "-Xms%sM" % args.heap, "-Xmx%sM" % args.heap, "-XX:+DisableExplicitGC", "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=50", "-Djava.library.path=" + natives, "-Dfile.encoding=UTF-8",
        "-Dfathom.profilesDir=" + profiles,
        "-Dfathom.benchmark=view",
        "-Dfathom.view.world=mpbench",
        "-Dfathom.view.server=127.0.0.1:25599",
        "-Dfathom.view.control=" + os.path.join(RUN, "control"),
        "-Dfathom.view.pos=0.5,101,-4.5",
        "-Dfathom.view.yaw=0",
        "-Dfathom.view.scenes=" + args.scenes,
        "-Dfathom.view.arms=" + args.arms,
        "-Dfathom.view.rounds=%d" % args.rounds,
        "-Dfathom.view.seconds=%s" % args.seconds,
        "-Dfathom.view.joinSettle=15",
    ] + ["-D" + extra for extra in args.extra]
    if args.jfr:
        jvm.append("-XX:StartFlightRecording=settings=profile,filename=%s" % args.jfr)
    command = [JAVA8] + jvm + [
        "-cp", classpath(), "net.fabricmc.loader.impl.launch.knot.KnotClient",
        "--username", USERNAME, "--version", "1.8.9", "--gameDir", RUN,
        "--assetsDir", os.path.join(DATA, "shared", "assets"), "--assetIndex", "1.8",
        "--uuid", "00000000000000000000000000000b3c", "--accessToken", "0", "--userProperties", "{}",
        "--userType", "legacy", "--width", "1920", "--height", "1080",
    ]
    log = open(os.path.join(RUN, "client-console.log"), "w", encoding="utf-8")
    print("[client] starting; console in %s" % log.name)
    process = subprocess.Popen(command, cwd=RUN, stdout=log, stderr=subprocess.STDOUT)
    # The client sometimes hangs on its way out after writing its report (sound
    # system shutdown). Once the report is written the run is complete, so give it a
    # minute to exit and then stop this process -- the one started here, by handle.
    import time
    reported_at = None
    while process.poll() is None:
        time.sleep(2)
        if reported_at is None:
            try:
                with open(log.name, encoding="utf-8", errors="replace") as text:
                    if "[FATHOM VIEW] data:" in text.read():
                        reported_at = time.time()
            except OSError:
                pass
        elif time.time() - reported_at > 60:
            print("[client] report written but the game did not exit; stopping it")
            process.kill()
            process.wait()
            sys.exit(0)
    sys.exit(process.returncode)


if __name__ == "__main__":
    main()
