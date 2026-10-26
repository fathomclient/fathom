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

--forge runs it the way the user's Forge profile does instead: LaunchWrapper with
FML's and Mixin's tweakers, OptiFine as a Forge coremod, the remapped Fathom jar
(fathom-forge-*.jar) first on the classpath, in run-mp/forge, with that profile's
video, OptiFine and Fathom settings. The classpath is the one the launcher last used
for the Forge profile, saved in run-mp/forge/forge-classpath.txt.
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
SAVE = "mpbench"
FORGE_RUN = os.path.join(RUN, "forge")
FORGE_PROFILE = os.path.join(DATA, "profiles", "fathom-1-8-9-2")


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
    run = FORGE_RUN if args.forge else RUN
    profile = FORGE_PROFILE if args.forge else PROFILE
    os.makedirs(os.path.join(run, "mods"), exist_ok=True)
    for old in glob.glob(os.path.join(run, "mods", "*.jar")):
        os.remove(old)
    if args.forge:
        jars = [path for path in glob.glob(os.path.join(MODULE, "build", "libs", "fathom-forge-*.jar"))
                if not path.endswith("-sources.jar")]
        jar = args.jar or (max(jars, key=os.path.getmtime) if jars else None)
        if not jar:
            raise SystemExit("Build the module first (gradle build makes the Forge jar).")
    elif args.jar:
        # A specific build, for comparing two builds under the same conditions.
        jar = args.jar
    else:
        jars = [path for path in glob.glob(os.path.join(MODULE, "build", "libs", "fathom-optimizations-*.jar"))
                if not path.endswith("-sources.jar")]
        if not jars:
            raise SystemExit("Build the module first (gradle build).")
        jar = max(jars, key=os.path.getmtime)
    staged = os.path.join(run, "mods", "fathom-forge-0.3.0-hud.jar" if args.forge else "fathom-optimizations.jar")
    shutil.copy(jar, staged)
    if not args.no_optifine:
        for pattern in ("optifabric-*.jar", "OptiFine_*.jar"):
            for path in glob.glob(os.path.join(profile, "minecraft", "mods", pattern)):
                shutil.copy(path, os.path.join(run, "mods"))
    game = os.path.join(profile, "minecraft")
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
    with open(os.path.join(run, "options.txt"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(out) + "\n")
    if os.path.isfile(os.path.join(game, "optionsof.txt")):
        shutil.copy(os.path.join(game, "optionsof.txt"), run)
    os.makedirs(os.path.join(run, "config"), exist_ok=True)
    for pattern in ("*.properties", "*.cfg"):
        for path in glob.glob(os.path.join(game, "config", pattern)):
            shutil.copy(path, os.path.join(run, "config"))
    if args.forge and not os.path.isdir(os.path.join(run, "saves", SAVE)):
        # The world the singleplayer scene loads.
        shutil.copytree(os.path.join(RUN, "saves", SAVE), os.path.join(run, "saves", SAVE))
    profiles = os.path.join(run, "fathom-profiles")
    if not os.path.isdir(profiles):
        os.makedirs(profiles)
        for name in ("default.json", "client-state.json"):
            source = os.path.join(DATA, "profiles", name)
            if os.path.isfile(source):
                shutil.copy(source, profiles)
    return profiles, staged


def forge_classpath(jar):
    """The Forge profile's last launch classpath, with the Fathom jar swapped for the one under test."""
    path = os.path.join(FORGE_RUN, "forge-classpath.txt")
    if not os.path.isfile(path):
        raise SystemExit("No %s: save the -cp of the Forge profile's last launch there." % path)
    entries = open(path, encoding="utf-8").read().strip().split(";")
    return ";".join(jar if re.search(r"fathom-forge-[^\\/]*\.jar$", entry) else entry for entry in entries)


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
    parser.add_argument("--forge", action="store_true", help="launch as the user's Forge profile does")
    args = parser.parse_args()
    # Entities that reach the 1.8 client before their chunk does are filed in an
    # empty placeholder chunk and never drawn; a populated scene measured straight
    # after connecting can draw none of its players. mp-empty first lets the world
    # load before any scene's entities arrive.
    scenes = args.scenes.split(",")
    if scenes[0] not in ("sp", "mp-empty"):
        args.scenes = ",".join(["mp-empty"] + [scene for scene in scenes if scene != "mp-empty"])
        print("[client] measuring mp-empty first so the world loads before the scene's entities")

    profiles, staged = stage(args)
    run = FORGE_RUN if args.forge else RUN
    natives = os.path.join(FORGE_PROFILE if args.forge else PROFILE, "natives")
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
    if args.forge:
        main_class = ["-cp", forge_classpath(staged), "net.minecraft.launchwrapper.Launch"]
        tweakers = ["--tweakClass", "net.minecraftforge.fml.common.launcher.FMLTweaker",
                    "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker"]
    else:
        main_class = ["-cp", classpath(), "net.fabricmc.loader.impl.launch.knot.KnotClient"]
        tweakers = []
    command = [JAVA8] + jvm + main_class + [
        "--username", USERNAME, "--version", "1.8.9", "--gameDir", run,
        "--assetsDir", os.path.join(DATA, "shared", "assets"), "--assetIndex", "1.8",
        "--uuid", "00000000000000000000000000000b3c", "--accessToken", "0", "--userProperties", "{}",
        "--userType", "legacy", "--width", "1920", "--height", "1080",
    ] + tweakers
    log = open(os.path.join(run, "client-console.log"), "w", encoding="utf-8")
    print("[client] starting; console in %s" % log.name)
    process = subprocess.Popen(command, cwd=run, stdout=log, stderr=subprocess.STDOUT)
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
