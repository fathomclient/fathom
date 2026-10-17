# The benchmark harness

How to take a Fathom performance measurement that means something, and what the
tooling does for you.

**This document contains no results.** `benchmarks/<date>/summary.md` is where
those go, written by the harness from runs that actually happened.

---

## 1. What the harness is

Three pieces, in two languages, either side of a file format.

| Piece | Where | What it does |
| --- | --- | --- |
| The probe | `core/instrument/Probe.java` | Times Fathom's own sections inside a frame |
| The run | `core/bench/Run.java`, `fabric/bench/Capture.java` | Records what the run was and refuses it if it cannot be trusted |
| The comparison | `launcher-core/src/bench`, `fathom-cli bench` | Reads runs back, refuses invalid comparisons, writes the summary |

```
fathom-cli bench plan                what to run, in order
fathom-cli bench scenarios           the scenes and their exact setup
fathom-cli bench summary <dir>       read the runs, write summary.md
```

### 1.1 What it does not do

**It does not start Minecraft.** A benchmark needs somebody to put the game into
a scenario — stand at these coordinates, face this wall, do not move — and a
harness that launched the game would still need that person. So it does
everything either side: says precisely what to set up, then reads the runs back
and refuses the comparisons that are not valid.

**It cannot measure two of the six configurations.** Configuration A is vanilla
with no loader, so no mod can be running to time it. Configuration B is the
loader with an empty mods folder, and a mod that measured it would make it "the
loader plus a mod". Both need an external frame-time capture, written into the
same directory in the same shape with `"source"` naming the tool. The summary
lists them as not run rather than estimating them.

---

## 2. The profiler

### 2.1 What it records

Everything below comes out of one run. Frame statistics, memory and GC were
already in `core/instrument`; the Fathom sections are what this milestone added.

| Metric | Where from | Notes |
| --- | --- | --- |
| FPS, frame time | `FrameLog` | Every frame, not sampled |
| Median, p95, p99 | `FrameStatistics` | From the full sorted set |
| 1% low, 0.1% low | `FrameStatistics` | 0.1% needs ≥1000 frames, and the run is refused below that |
| Heap used, peak | `MemorySampler` | Sampled on an interval |
| Allocation rate | `MemorySampler` | Where the JVM exposes it; the report says which method was used, or that it could not be measured |
| GC collections, pause time | `GcLog` | Deltas across the run, per collector |
| Rendered entities, tile entities | `Counters` | **Deep only.** No Mixin feeds these yet — see §2.4 |
| Particles | `Counters` | Deep only, same |
| Chunk rebuilds, rebuild time | `Counters` | Deep only, same |
| **Fathom HUD time** | `Probe.Section.HUD` | Per frame, always available |
| **Fathom module dispatch time** | `Probe.Section.MODULES` | The ownership question the HUD loop asks per widget |
| **Fathom hook time** | `Probe.Section.HOOKS` | The frame-cap policy |
| **Fathom screen time** | `Probe.Section.SCREEN` | The Hub, the menu, the editor — zero when none is open |
| **Fathom worst frame** | `Probe` | The most Fathom cost any single frame in the run |

### 2.2 What it costs

Off: one static boolean read per call site. The branch body folds away.

On: two `System.nanoTime()` calls and one array add per section per frame. On the
machine this was written on `nanoTime` is about 25 ns, so five sections cost
roughly 250 ns of the frame they are measuring — **about 0.005% of a 5 ms
frame**.

That number is the whole reason the probe is shaped the way it is. An instrument
that moves what it measures is not an instrument. A flat `long[]` indexed by enum
ordinal, no allocation on any path, and no map lookup, because a map lookup is
comparable in cost to the thing being timed.

### 2.3 The Hub when closed

`Probe.Section.SCREEN` reads zero when no Fathom screen is open, and that is a
measurement rather than an assumption: the Hub is a `Screen`, and a screen that
is not current is not instantiated. If it ever stops reading zero, something is
drawing that should not be.

### 2.4 Deep instrumentation

Per-entity, per-particle and per-glyph counting is behind
`-Dfathom.profile=deep`. Counting every entity considered is genuinely useful and
genuinely changes the frame it is counting; it belongs in a profiling run, not in
a client somebody is playing on.

**The deep counters have no Mixins feeding them yet.** `Counters` has the fields
and `Scenario` names which ones each scene cares about, but nothing increments
them, because the Mixins that would are the same ones the optimizations
themselves need and those are not written. A run reports zero for them, and zero
is honest.

### 2.5 One thing deliberately not timed

`TextRendererWidthMixin` — the font metrics cache — is called once per text run,
which is hundreds of times a frame. Wrapping it in two `nanoTime` calls would
cost more than the method does. It is **counted** (hits and misses, in
`WidthMemo`) and not timed, which is the honest trade: an instrument that
invalidates its own result answers nothing.

---

## 3. Configurations

| # | Id | What it is | Measured by |
| --- | --- | --- | --- |
| A | `a-vanilla` | Vanilla 1.8.9, no loader | External tool |
| B | `b-loader` | Legacy Fabric, empty mods | External tool |
| C | `c-core` | + Fathom, preset Vanilla, default modules | Fathom |
| D | `d-performance-off` | + everything optional off | Fathom |
| E | `e-balanced` | Balanced preset | Fathom |
| F | `f-competitive` | Competitive preset | Fathom |

**The number this milestone is gated on is D − B.** Not D − A: that would fold
the loader's cost into Fathom's and make the result unactionable. The pair is
named in code — `Configuration.overheadPair()` — with a test, rather than left to
whoever writes the summary.

The client infers which configuration it is in from what is switched on and the
preset the launcher wrote, so a run cannot be filed under the wrong one by
mistake.

---

## 4. Scenarios

Nine, and the first is the important one.

| Id | Scene | Seconds | Why |
| --- | --- | --- | --- |
| `cpu-ceiling` | A flat wall filling the view | 60 | **The overhead scene.** Trivial to draw, so frame time is CPU work — which is where a mod's overhead lives |
| `singleplayer-still` | Ordinary outdoor scene, fixed camera | 120 | What a real frame looks like. An overhead that is 4% of a trivial frame may be 0.5% of this one |
| `chunk-movement` | Repeatable traversal | 120 | Chunk meshing, which is where 1.8.9 actually hurts |
| `entity-lobby` | Hundreds of armour stands | 90 | Entity rendering and culling |
| `text-heavy` | Scoreboard, tab list, nametags, Fathom HUD | 90 | Font rendering and the HUD loop together |
| `particles` | Sustained particle load | 60 | Particle rendering |
| `pvp-map` | Player-shaped render load | 180 | The workload the project exists for |
| `inventory-gui` | A screen over a frozen world | 60 | GUI quads at the same user limit as gameplay |
| `resource-pack` | A heavy pack loaded | 60 | Texture memory and atlas cost |

`fathom-cli bench scenarios` prints the exact setup for each — coordinates,
gamerules, render distance, what to place. Those are commands to be typed, not
descriptions to be interpreted.

---

## 5. Standardised settings

Every run records all of these. `bench summary` compares them before it computes
any delta, and **prints the differences instead of a percentage** when they do
not match.

```
resolution      display        renderDistance   graphics
smoothLighting  particles      vbo              vsync
fpsLimit        guiScale       resourcePacks    mipmaps
entityShadows   fov            java             heapMaxMb
jvmArguments    os             cpuCores         minecraft
```

They are read from the client at the moment of the run, not from a form. The
failure this prevents is not choosing the wrong settings, it is not *noticing*
that something changed them — a resource pack that survived a profile switch,
VSync that came back after a driver update, a window resized between runs.

`fathomVersion` is recorded and deliberately **not** in the must-match list: it is
expected to differ between a baseline and a candidate, because that is what the
comparison is for.

JVM arguments are filtered to `-X` and `-D` flags, and anything naming a token,
key, password or session is dropped — a benchmark file is something people paste
into bug reports.

---

## 6. What makes a run invalid

`Run.rejectionReason` refuses a run rather than saving it. Every one of these has
happened while building this milestone.

| Refusal | Why it matters |
| --- | --- |
| **The window lost focus** | The unfocused frame cap engages and every configuration reports exactly 30 fps. This is the one that bit hardest — see the audit, §7 |
| Shorter than 30 seconds | Too few frames for a 1% low to mean anything |
| Fewer than 1000 frames | A 0.1% low would be the worst single frame |
| No configuration recorded | A number nobody can place afterwards is not a number |
| A setting not recorded | Another run cannot be checked against it |

Focus is sampled every frame from the frame hook, not checked once at the end: it
can be lost and regained inside a run and both ends would look fine.

---

## 7. Running a session

1. `fathom-cli bench plan` — read the configurations and pick a scenario.
2. Set the game up exactly as `fathom-cli bench scenarios` says.
3. Open Fathom Hub → Diagnostics, choose the scenario, press **Benchmark**.
4. Close the Hub, get into position, and **do not touch anything** — including
   alt-tabbing, which will discard the run.
5. Reopen the Hub and press **Stop recording**. The run is checked and saved to
   `benchmarks/<date>/<configuration>.json`.
6. Repeat for every configuration, alternating the order between sessions so
   thermal drift does not favour whichever went first.
7. `fathom-cli bench summary <game-dir>/benchmarks/<date>` writes `summary.md`.

Three runs per configuration minimum, and report the median with the spread. One
run is an anecdote.

---

## 8. What the summary says

- **The overhead headline**: D against B, with a verdict against the threshold.
- **Every run**: frames, mean, p95, p99, 1% low, and Fathom's own ms/frame.
- **Deltas against the loader**, or "not comparable" with the settings that
  differ.
- **Where Fathom's time went**, per section, per configuration.
- **What was not run**, and for A and B, why Fathom could not run it.

Nothing in it is estimated. A configuration that was not run is listed as not
run.
