#!/usr/bin/env python3
"""Paired arm-vs-normal differences per round, from a client console log.

The view benchmark prints one line per measured window:

  [FATHOM VIEW] minemen/normal round 3: 9120 frames, mean 1.8315 ms | E: 69/70, ...

Each round measures every arm of a scene back to back, so an arm and the normal arm of
the same round are a pair. A window whose scene came up only partly (fewer entities
drawn than loaded) is not comparable with one that drew everything; a pair is used only
when both windows drew at least --min-drawn of the entities loaded and neither had an
unfocused frame.

  python tools/mp-bench/paired_windows.py minecraft/fathom-optimizations/run-mp/forge/client-console.log
"""

import argparse
import collections
import re
import statistics

LINE = re.compile(r"\[FATHOM VIEW\] (\S+)/(\S+) round (\d+): (\d+) frames, mean ([0-9.]+) ms(?:, (\d+) unfocused)? \| E: (\d+)/(\d+)")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("log")
    parser.add_argument("--min-drawn", type=float, default=0.9, help="fraction of loaded entities drawn")
    args = parser.parse_args()
    windows = collections.defaultdict(dict)  # (scene, round) -> arm -> (mean, drawn, loaded)
    with open(args.log, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            match = LINE.search(line)
            if match:
                scene, arm, rnd, frames, mean, unfocused, drawn, loaded = match.groups()
                windows[(scene, int(rnd))][arm] = (float(mean), int(drawn), int(loaded), int(unfocused or 0))
    scenes = sorted(set(scene for scene, _ in windows))
    for scene in scenes:
        rounds = sorted(r for s, r in windows if s == scene)
        arms = sorted(set(arm for r in rounds for arm in windows[(scene, r)] if arm != "normal"))
        normals = [windows[(scene, r)].get("normal") for r in rounds]
        print("%s: normal window means %s" % (scene, ", ".join(
            "%.3f(%d/%d%s)" % (n[0], n[1], n[2], ", %d unfocused" % n[3] if n[3] else "") if n else "-"
            for n in normals)))
        for arm in arms:
            diffs = []
            skipped = 0
            for r in rounds:
                pair = windows[(scene, r)]
                if "normal" not in pair or arm not in pair:
                    continue
                a, n = pair[arm], pair["normal"]
                # Partly drawn, or measured with the window unfocused: not comparable.
                full = all((w[2] <= 1 or w[1] >= args.min_drawn * w[2]) and w[3] == 0 for w in (a, n))
                if not full:
                    skipped += 1
                    continue
                diffs.append(a[0] - n[0])
            if diffs:
                wins = sum(1 for d in diffs if d < 0)
                base = statistics.median([windows[(scene, r)]["normal"][0] for r in rounds
                                          if "normal" in windows[(scene, r)]])
                print("  %-70s paired median %+.4f ms (%+.1f%%), mean %+.4f, faster in %d/%d%s" % (
                    arm, statistics.median(diffs), 100 * statistics.median(diffs) / base,
                    statistics.mean(diffs), wins, len(diffs),
                    (", %d partial pair(s) left out" % skipped) if skipped else ""))
            else:
                print("  %-70s no complete pairs (%d partial)" % (arm, skipped))


if __name__ == "__main__":
    main()
