#!/usr/bin/env python3
"""Regenerate the tables in docs/performance/optimization-registry.md.

The source is assets/optimizations/registry.json, which the game module writes
from OptimizationRegistry (`gradle exportRegistry`) and the launcher compiles
in. Only the text between the `generated:start` and `generated:end` markers is
replaced; everything else in the document is written by hand.

    python tools/gen-registry-doc.py
"""

import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "assets" / "optimizations" / "registry.json"
DOC = ROOT / "docs" / "performance" / "optimization-registry.md"
START = "<!-- generated:start -->"
END = "<!-- generated:end -->"

STATUS = {
    "RUNS_IN_GAME": "Runs in game",
    "IN_MODULE": "In the module, not yet seen in game",
    "CORE_WRITTEN": "Core written, not hooked up",
    "PLANNED": "Planned",
}
CONTROL = {
    "SWITCH": "Launcher switch",
    "AUTOMATIC": "Automatic",
    "IN_GAME": "Set in game (Hub)",
}


def generated(entries):
    out = []
    implemented = [e for e in entries if e["status"] in ("RUNS_IN_GAME", "IN_MODULE")]
    planned = [e for e in entries if e not in implemented]
    measured = [e for e in entries if e["evidence"]]
    in_game = [e for e in measured if e["evidence"]["level"] == "IN_GAME"]
    out.append("## 1. Where things stand\n")
    out.append(f"**{len(entries)}** entries: **{len(implemented)}** implemented and hooked into the "
               f"game, **{len(planned)}** planned. **{len(measured)}** carry a measurement, "
               f"**{len(in_game)}** of them taken in a running game.\n")
    out.append("| Status | Count |\n|---|---|")
    for key, label in STATUS.items():
        out.append(f"| {label} | {sum(1 for e in entries if e['status'] == key)} |")
    out.append("")
    out.append("## 2. The register\n")
    out.append("| ID | Name | Group | Class | Status | Control | Evidence | Stood down by |")
    out.append("|---|---|---|---|---|---|---|---|")
    for e in entries:
        evidence = (f"{e['evidence']['level'].lower().replace('_', '-')} (`{e['evidence']['runId']}`)"
                    if e["evidence"] else "**none**")
        conflicts = ", ".join(c["with"] for c in e["conflicts"]) or "—"
        out.append(f"| `{e['id']}` | {e['name']} | {e['groupLabel']} | {e['category'].title()} "
                   f"| {STATUS[e['status']]} | {CONTROL[e['control']]} | {evidence} | {conflicts} |")
    out.append("")
    out.append("## 3. Every entry in full\n")
    for e in entries:
        out.append(f"#### `{e['id']}` — {e['name']}\n")
        out.append(e["description"] + "\n")
        out.append(f"- **Status:** {STATUS[e['status']]}. **Control:** {CONTROL[e['control']]}. "
                   f"**Class:** {e['category'].title()}. **Group:** {e['groupLabel']}. "
                   f"**Default:** {'on' if e['defaultOn'] else 'off'}.")
        out.append(f"- **Expected benefit:** {e['expectedBenefit']}")
        if e["evidence"]:
            out.append(f"- **Measured** ({e['evidence']['level'].lower().replace('_', '-')}, "
                       f"`{e['evidence']['runId']}`): {e['evidence']['summary']}")
        else:
            out.append("- **Measured:** nothing yet.")
        for c in e["conflicts"]:
            out.append(f"- **Stood down by {c['with']}:** {c['detail']}")
        out.append("")
    return "\n".join(out)


def main():
    entries = json.loads(REGISTRY.read_text(encoding="utf-8"))["optimizations"]
    text = DOC.read_text(encoding="utf-8")
    before, rest = text.split(START, 1)
    _, after = rest.split(END, 1)
    DOC.write_text(before + START + "\n\n" + generated(entries) + "\n" + END + after,
                   encoding="utf-8", newline="\n")
    print(f"regenerated {DOC.relative_to(ROOT)} from {len(entries)} entries")


if __name__ == "__main__":
    main()
