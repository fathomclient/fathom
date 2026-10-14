# Published benchmark reports

Raw reports from Fathom's frame profiler (`-Dfathom.benchmark=view`), committed
as the input to `gradle exportWebData`. Every `.txt`/`.csv` pair here is read;
nothing else is. See `docs/web-export.md`.

A report belongs here only if it is the evidence for something Fathom states,
and only as the profiler wrote it — never edited. The exporter re-checks each
one and leaves out any scene or arm without full context or with rejected or
unfocused frames, so a bad run cannot reach the website, but it is better not
to add one.

| Report | What it shows | Written up in |
|---|---|---|
| `fathom-mp-2026-10-06-18-41-41` | Shipped defaults against the three multiplayer optimizations switched off, every scene | `docs/performance/multiplayer-2026-10.md` |
| `fathom-mp-2026-10-06-18-18-23` | Each multiplayer optimization switched off (or on) on its own | same |
| `fathom-view-2026-10-05-21-02-26` | Singleplayer, 48 armoured players: the nametag and armour caches on against the build of the day | same |

To add one: copy the pair from the game directory's `logs/` folder, add a row
here, run `gradle exportWebData`.
