# Benchmark methodology

How we measure Minecraft 1.8.9, and what our measurements can and cannot support.

This document is a **method**, not results. It contains no performance numbers,
because none have been taken. `baseline.md` is where the first ones will go.

---

## 1. The rule this exists to enforce

**Nothing is promoted to on-by-default until it has been measured under this
method.** Not "classified SAFE", not "obviously helps", not "every other launcher
does it", **and not "the microbenchmark says it is faster"** — measured, in the
game, on 1.8.9, on Java 8.

The last of those is the one worth spelling out. A microbenchmark is real evidence
that an implementation works and is not slower than what it replaces, and it is not
evidence that a frame got faster: a function ten times faster in a loop can be
invisible in a frame. `Evidence` carries a level for exactly this reason, and only
`IN_GAME` promotes anything.

The rule is enforced in code rather than by discipline:

```java
public boolean defaultEnabled() {
    return measuredInGame() && defaultOnWhenMeasured;
}
```

A test asserts that no entry in the registry currently claims in-game evidence, so a
change that promotes something on the strength of a microbenchmark fails the build.

---

## 2. What is measured

### 2.1 Frame timing

| Metric | Definition |
|---|---|
| Average FPS | `frames / elapsed_seconds`. **Not** the mean of per-frame rates. |
| Mean frame time | Total elapsed divided by frame count. |
| Median frame time | 50th percentile, nearest-rank. |
| p95 / p99 frame time | Nearest-rank percentiles. One real frame each. |
| 1% low FPS | Mean duration of the slowest 1% of frames, expressed as FPS. |
| 0.1% low FPS | The same over the slowest 0.1%. |
| Frame time CoV | Standard deviation over mean. One number for "is it smooth". |

**"1% low FPS" and "p99 frame time" are different metrics and must never be
compared to each other.** They can move in opposite directions on the same data.
A worked example, which is a real test in the module:

> 1000 frames — 990 at 5 ms, 10 at 100 ms.
> **1% low = 10 FPS.** The worst 1% is exactly those ten frames; their mean is
> 100 ms.
> **p99 frame time = 5 ms.** Exactly 99% of frames are 5 ms, so the 99th
> percentile lands on the last fast one. p99.5 is 100 ms.

Both are true. Both describe the same stutter. Quoting one against someone else's
other is how benchmark arguments happen.

**Percentiles are nearest-rank, never interpolated.** An interpolated percentile
reports a frame time that never occurred, which is the wrong thing to say about a
discrete series of real events.

**Averages are the metric of last resort.** Almost every optimization in the
registry targets consistency rather than throughput. An accurate frame limiter,
for instance, should not change average FPS at all — if it does, something is
wrong. Judge on percentiles.

### 2.2 Memory

| Metric | How |
|---|---|
| Heap used, committed, max | `MemoryMXBean`, sampled at most 10 Hz |
| Non-heap used | Same |
| Peak and mean heap over a run | Computed from the samples |
| Allocation rate | See below — **may be an estimate** |

**Allocation rate is not portably observable on Java 8.** There is no Java SE API
for "bytes allocated". HotSpot offers
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`, which is a Sun
extension. `MemorySampler` reaches for it reflectively:

- **Present and enabled** → allocation is a real measurement, but **per-thread**.
  Called from the render thread it measures the render thread, which is the one
  whose allocation causes frame stutter. It is not the whole process.
- **Absent** → allocation is inferred from heap-used growth between samples. That
  is an **undercount**: anything allocated and collected between two samples is
  invisible. `Report.allocationMeasured()` is false and a caveat says so.

Reporting the estimate as a measurement would make every allocation-related
conclusion untrustworthy, so the distinction travels in the data rather than in a
footnote.

### 2.3 Garbage collection

`GarbageCollectorMXBean` gives a **collection count** and a **cumulative
collection time** per collector. Two readings bracket a run.

**What it cannot give, and what we therefore do not report:**

- **Individual pause durations.** There is no p99 GC pause here, only a mean —
  and a mean pause hides exactly the outlier that ruins a fight.
- **Whether a pause was stop-the-world.** Concurrent phases are counted in the
  same total.
- **When** a collection happened, beyond the interval it fell in.

Per-pause data needs `-verbose:gc` output or a JFR recording parsed after the
run. That is a launcher-side experiment, not something the in-process
instrumentation can do, and `GcLog.limitations()` is embedded in every report so
a reader is told rather than left to assume.

**The useful thing this can do is correlate.** A frame-time spike that coincides
with the collection count rising is very likely a GC pause. That is the intended
use.

### 2.4 Render workload

Counters, drained per run: chunk rebuilds and their total time, entities
considered versus rendered, tile entities, particles, text runs, glyphs, GUI
quads, buffer uploads, and **approximate** draw calls.

**Draw calls are approximate and the name says so.** Counting real GL draw calls
means intercepting every call site, and 1.8.9 draws through several paths. The
counter covers the paths our instrumentation reaches. It is a **relative** figure
— useful for before-and-after on the same build, meaningless as an absolute.

Two derived numbers are worth naming:

- **Glyphs per text run.** What font batching is supposed to change: before, draw
  calls track glyphs; after, they should track runs.
- **Entities rendered / considered.** What occlusion culling is supposed to
  change.

Watching a counter move is stronger evidence that an optimization did what it
claims than watching a frame time move, because a frame time moves for many
reasons.

---

## 3. Warm-up

**500 frames are discarded before measurement begins** (about eight seconds at
60 FPS).

A JIT-cold Minecraft client is measurably slower than a warm one. Without a fixed
warm-up, every result depends on how long the tester waited before pressing the
key.

Minecraft needs a longer warm-up than most programs: `performance.md` §5.4 notes
that several methods exceed HotSpot's 8000-byte `HugeMethodLimit` and therefore
**never compile at all**. "Warm" is a stranger state here than usual.

**The `jvm.compile-huge-methods` experiment needs a longer warm-up than the
default**, since its entire effect is about what eventually compiles. That is
recorded in the experiment and must be recorded in any run of it.

---

## 4. Scenarios

Seven workloads, from the milestone brief. Their **reproducibility differs
enormously**, and that is the most important column.

| # | Scenario | Reproducibility | Comparable between sessions? |
|---|---|---|---|
| 1 | Multiplayer PvP map | Scripted local | Yes |
| 2 | Entity-heavy lobby | **Automatable** | Yes |
| 3 | Scoreboard / tab / nametags | **Automatable** | Yes |
| 4 | Particle-heavy | Scripted local | Yes |
| 5 | Rapid chunk movement | Scripted local | Yes |
| 6 | Inventory / GUI | **Automatable** | Yes |
| 7 | Resource-pack-heavy | **Not yet reproducible** | **No** |

- **Automatable** — the client builds the workload itself. No server, no prepared
  world, no human. Two runs differ only by noise.
- **Scripted local** — a prepared world plus a fixed camera path.
- **Not yet reproducible** — we have not worked out how.

### 4.1 Documented limitations

Every scenario that is not fully automatable states why, in
`Scenarios.java`, and a test enforces that it does. The ones worth repeating
here:

**PvP map.** Armour stands are not players: no skin fetch, no animation state
machine, no held-item variety, and no second client sending movement packets.
This reproduces a *player-shaped render load*, not a match. A real PvP match
cannot be replayed.

**Particles.** Particle lifetime involves randomness in vanilla. Emission rate is
fixed; the exact set of live particles at any instant is not. Run longer and
compare medians rather than single figures.

**Chunk movement.** Meshing happens on worker threads, so its cost reaches frame
time only indirectly — the rebuild counters measure the work, the frame
statistics measure what the player feels, and those are different questions.
Results also depend on disk speed, so this is same-machine before-and-after only.

**Resource pack — not reproducible, and honestly so.** We cannot ship a
third-party resource pack with the harness: packs are copyrighted works and
redistributing one is exactly the act `mod-policies.md` §7 governs. Measuring
against a pack the tester supplies makes results incomparable between testers.
The likely resolution is a synthetic pack generated at a chosen resolution —
honest and comparable, but measuring atlas size rather than any real pack. **Not
built.**

### 4.2 What is not measured at all

**MC-67665 (the "input lag" that is not input lag).** The fix removes a frame of
apparent aim staleness. That is perceptual and does not show up in frame timing —
frame times are identical before and after. It needs either a high-speed capture
of screen-versus-mouse, or a blind A/B with players, and we have neither. The
optimization stays unmeasured and therefore off, which is the correct
consequence of not being able to measure it.

---

## 5. Running a comparison

1. Record the environment exactly as `baseline.md` §1 specifies. A benchmark with
   no recorded environment is a number with no meaning.
2. Fix everything except the one variable. One optimization, or one JVM flag arm,
   at a time.
3. **Interleave, do not batch.** Run A, B, A, B rather than A×5 then B×5. Thermal
   throttling, background processes and driver state drift over minutes, and
   batching turns that drift into an apparent difference.
4. At least **five runs per arm**, and report the median run, not the best.
5. Compare the metric the optimization claims to move, named in advance.
6. If the difference is smaller than the spread between runs of the same arm,
   **the result is "no measurable difference"**, not a small win.

### 5.1 Before trusting any comparison

Run the same build against itself. The spread between those runs is the noise
floor, and anything smaller than it is not a result. This is the step most
easily skipped and the one that makes everything else meaningful.

---

## 6. What is honest to claim

| Evidence | Claim |
|---|---|
| Counter moved, frame time did not | "Does what it says; no measurable frame-time effect." |
| Frame time moved beyond the noise floor, across arms, median-of-five | "Measured improvement of X in scenario Y on hardware Z." |
| Frame time moved within the noise floor | "No measurable difference." |
| Measured on one machine | "On this hardware." Never "in general." |
| Measured on a live server | "Indicative." Not comparable between sessions. |

**Never** state a figure that was not produced by a run recorded in
`baseline.md` or a successor document.

---

## 7. Tooling status

| Piece | Status |
|---|---|
| `FrameStatistics`, `FrameLog`, `Counters`, `MemorySampler`, `GcLog`, `Session`, `Report` | Written, compiled, unit-tested with a bare JDK 8 |
| `Scenarios`, `Experiments`, registry, switchboard, guards | Same |
| `MicroBench`, the isolated-comparison harness | Same, and **used** — see `benchmarks.md` §3 |
| The implemented optimizations under `core/opt/` | Same, and **measured in isolation** — `benchmarks.md` §3, §5, §6 |
| `ExplicitGcBench`, the synthetic JVM workload | Same, and **used** — `benchmarks.md` §4 |
| Fabric entrypoint, `Runtime`, the frame-timing Mixin | **Written, never compiled or run** |
| Gradle build | **Written, never executed** |
| Prepared benchmark world | **Not built** |
| Scenario automation | **Not built** |
| Harness that runs an arm and writes a report | **Not built** |

The last four are what stands between the isolated measurements we have and the
in-game measurements that would promote anything.

The split is deliberate: everything carrying logic has no game dependency and is
verifiable without Minecraft. What remains unverified is the glue that needs the
game to exist.

---

## 8. Source discipline

We may read feature lists, changelogs and public bug reports from
restricted-licence projects. **We may not read or translate their source.**
`mod-policies.md` §3 establishes that Patcher and PolyPatcher are CC BY-NC-SA and
their code is not reusable by us.

Knowing *that a problem exists* is a fact about Minecraft. Knowing *how someone
else solved it* is their work.

Each registry entry records its `Basis`:

- `MOJANG_BUG` — a Mojang bug report, and where one exists, Mojang's own later
  fix. The preferred basis.
- `VANILLA_ANALYSIS` — our own reading of the decompiled game.
- `THIRD_PARTY_PROBLEM_STATEMENT` — a restricted-licence project's public
  description told us the problem exists. No code read or reused.
- `PLATFORM_LITERATURE` — general JVM or OpenGL sources.

Where an optimization corresponds to a known Mojang bug, that is the basis we
prefer and the reference we cite.

---

## 9. References

- `docs/research/performance.md` — the problem analysis
- `docs/research/PERFORMANCE_MATRIX.md` — the classification this registry encodes
- `docs/research/mod-policies.md` §3, §7, §8 — licensing and conflicts
- `docs/performance/optimization-registry.md` — the entries and the experiments
- `docs/performance/optimizations.md` — what has been implemented, and what was rejected
- `docs/performance/benchmarks.md` — every run taken so far, with its limitations
- `docs/performance/baseline.md` — the environment record, and where results go
