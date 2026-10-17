"""Pool the runs `bench-ab.ps1` produced and compare the two arms.

Reports the median across runs of each arm rather than the mean, and reports it
per scenario rather than pooled across scenarios. Both choices matter:

- The median, because one run that caught a Windows update or a browser tab
  compositing video should not move the answer, and with four runs per arm a
  mean is one bad run away from being wrong.
- Per scenario, because the arms differ by wildly different amounts depending on
  what is on screen -- averaging a scene where OptiFine replaces the terrain
  renderer with one where chunk streaming dominates produces a single number
  that describes neither.

It also prints the spread between an arm's own runs. That is the figure that
says whether a difference is worth quoting: when the arms differ by less than
the runs within an arm differ from each other, the comparison has not measured
anything.
"""

import json
import pathlib
import statistics
import sys


def load(directory: pathlib.Path, label: str):
    """Group every run by (arm, scenario)."""
    arms: dict[tuple[str, str], list[dict]] = {}
    for path in sorted(directory.glob(f"{label}-*.json")):
        # "<label>-A3-a-still.json" -> arm "A", scenario "a-still"
        rest = path.stem[len(label) + 1 :]
        arm_token, _, scenario = rest.partition("-")
        arm = arm_token[:1]
        if arm not in ("A", "B"):
            continue
        with path.open(encoding="utf-8") as handle:
            arms.setdefault((arm, scenario), []).append(json.load(handle))
    return arms


def summarise(runs: list[dict], key: str) -> tuple[float, float, float]:
    values = [run["frames"][key] for run in runs]
    return statistics.median(values), min(values), max(values)


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        print("usage: bench-ab-summary.py <fathom-bench dir> [label]")
        return 2
    directory = pathlib.Path(sys.argv[1])
    label = sys.argv[2] if len(sys.argv) > 2 else "ab"

    arms = load(directory, label)
    if not arms:
        print(f"No runs matching {label}-*.json in {directory}")
        return 1

    scenarios = sorted({scenario for _, scenario in arms})
    print(f"{'scenario':<14}{'A median':>10}{'B median':>10}{'change':>9}"
          f"{'A spread':>11}{'B spread':>11}  verdict")
    print("-" * 78)

    for scenario in scenarios:
        a_runs = arms.get(("A", scenario), [])
        b_runs = arms.get(("B", scenario), [])
        if not a_runs or not b_runs:
            print(f"{scenario:<14}  only one arm has runs; not compared")
            continue

        a_med, a_lo, a_hi = summarise(a_runs, "averageFps")
        b_med, b_lo, b_hi = summarise(b_runs, "averageFps")
        change = (b_med - a_med) / a_med * 100.0

        # The within-arm spread, as a percentage of that arm's median.
        a_spread = (a_hi - a_lo) / a_med * 100.0
        b_spread = (b_hi - b_lo) / b_med * 100.0
        noise = max(a_spread, b_spread)
        verdict = "quotable" if abs(change) > noise else "inside the noise"

        print(f"{scenario:<14}{a_med:>10.1f}{b_med:>10.1f}{change:>8.1f}%"
              f"{a_spread:>10.1f}%{b_spread:>10.1f}%  {verdict}")

    runs_per_arm = {arm: len({id(r) for r in runs}) for (arm, _), runs in arms.items()}
    print()
    print(f"A runs per scenario: {runs_per_arm.get('A', 0)}, "
          f"B runs per scenario: {runs_per_arm.get('B', 0)}")
    print("A = Fathom only.  B = Fathom + OptiFine.")
    print("'change' is B against A. A result inside the noise is not a result.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
