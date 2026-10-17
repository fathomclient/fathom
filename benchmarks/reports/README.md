# Benchmark reports

Raw output from Fathom's in-game frame profiler (`-Dfathom.benchmark=view`),
kept as the evidence behind published numbers. `gradle exportWebData` reads
every `.txt`/`.csv` pair here and skips any scene with incomplete data or
unfocused frames.

Reports are added as the profiler wrote them and never edited.

| Report | What it shows | Written up in |
|---|---|---|
| `fathom-mp-2026-10-06-18-41-41` | Default settings against the three multiplayer optimizations switched off, every scene | `docs/performance/multiplayer-2026-10.md` |
| `fathom-mp-2026-10-06-18-18-23` | Each multiplayer optimization switched off (or on) on its own | same |
| `fathom-view-2026-10-05-21-02-26` | Singleplayer with 48 armoured players: the nametag and armour caches on and off | same |

To add one, copy the pair from the game's `logs/` folder, add a row here, and
run `gradle exportWebData`.
