# Benchmark runs

Every measurement this project has actually taken. Nothing is quoted anywhere else
in the documentation that does not have a run here behind it.

**Read §1 before reading any number below.** Every run on this page is either a
microbenchmark of one function or a synthetic JVM workload. **Not one of them is a
measurement of Minecraft.** That is not a hedge; it changes what each result is
allowed to conclude, and §1 says how.

---

## 1. Why none of this is a Minecraft measurement

The Fabric layer of `minecraft/fathom-optimizations` has never been compiled. There
is no Gradle-capable environment here, no Minecraft jar, no LWJGL, and therefore no
frame. `benchmark-methodology.md` describes how the game will be measured; this page
is what could be measured without it.

That leaves two honest kinds of run:

| Scope | What it is | What it can conclude |
|---|---|---|
| **Isolated function** | One pure function in a loop, both implementations, interleaved | This implementation is faster/slower than that one, *for this function, on this machine* |
| **Synthetic JVM workload** | A real JVM doing real allocation and real collection, under a workload we wrote | Properties of the JVM — that a flag has an effect, how a cost scales |

Neither concludes anything about frame time. A function ten times faster in a loop
can be invisible in a frame, because a frame is dominated by other things and
because a microbenchmark runs with the whole cache to itself.

This distinction is enforced in code rather than left to discipline.
`Evidence.Level.ISOLATED` does not satisfy `Optimization.defaultEnabled()`, so
**nothing on this page has turned anything on**, and a future change that tries to
promote something on the strength of a microbenchmark fails a test rather than
shipping.

### 1.1 Harness limitations

`MicroBench` is not JMH. This module has no dependencies outside the JDK on purpose —
it has to be checkable with nothing but a JDK — so the choice was between this and
nothing. What it does properly: warms up, interleaves arms (A, B, A, B, alternating
which leads), reports medians of eleven runs, and measures a noise floor by running
the baseline against itself before comparing anything.

What it does not do: fork per arm, guard against dead-code elimination beyond
consuming results into a volatile field, or profile. **Treat any difference under
about 10% as no difference**, regardless of what the noise floor says.

---

## 2. Environment

Every run on this page, unless a row says otherwise. Full detail in `baseline.md` §3.

| | |
|---|---|
| Machine | Windows 11 Home 10.0.26200, i5-12400F (6c/12t), 16 GB, RTX 4060 |
| Java | Temurin 1.8.0_502, OpenJDK 64-Bit Server VM 25.502-b07, amd64 |
| Date | 2026-08-26 |
| Module build | `minecraft/fathom-optimizations`, `core` only, compiled with `javac -encoding UTF-8` |

---

## 3. `bench-2026-08-26-text-metrics` — font metrics caching

**Optimization:** `render.font.metrics-cache`
**Scope:** isolated function
**Command:** `java -Xms2048M -Xmx2048M -cp build/classes net.fathom.optimizations.bench.TextMetricsBench`

### 3.1 What one iteration is

One frame's worth of text measurement on a busy minigame server: **235 strings,
11,528 characters** — 15 scoreboard lines, 80 tab entries, 100 chat lines, 40
nametags, format codes throughout. That shape was chosen so the number means
something; a per-call figure for `getStringWidth` would be true and useless.

Frames are pre-built, not generated inside the timed loop. The first version of this
benchmark built the churned strings during measurement, which put `Random` and string
concatenation into both arms, inflated the noise floor past the effect, and made
every result read as "no measurable difference". That was a bug in the benchmark, not
a finding.

### 3.2 Results

11 runs per arm, interleaved, median reported. 2000 iterations per run.

| Comparison | Recompute | Cached | Difference | Noise floor | Significant |
|---|---|---|---|---|---|
| Static corpus | 12,922 ns | 1,795 ns | **86.1% faster** | 21.3% | yes |
| 5% of strings new each frame | 15,607 ns | 5,238 ns | **66.4% faster** | 20.9% | yes |
| 50% new each frame | 18,054 ns | 16,026 ns | 11.2% | 18.0% | **no** |
| 100% new each frame | 14,590 ns | 21,386 ns | **46.6% slower** | 12.5% | yes |

### 3.3 Hit rate, 600 frames

| Churn per frame | Hit rate | Entries held |
|---|---|---|
| 0% | 99.8% | 219 |
| 1% | 99.0% | 1,414 |
| 5% | 94.9% | 4,096 (capacity) |
| 25% | 77.7% | 4,096 |
| 50% | 60.4% | 4,096 |
| 100% | 0.0% | 4,096 |

### 3.4 What this shows

The cache is worth having at realistic churn and **costs 47% when it never hits**.
That last row is the important one and is the reason the worst case was measured at
all: a cache that misses does strictly more work than no cache, and publishing only
the 86% figure would have hidden it.

Realistic churn is low. 235 strings are on screen; one or two chat lines arrive per
second and a handful of scoreboard values change. That is single-digit percent, where
the measured hit rate is 95-99%. **100% churn does not occur** — it would mean every
name, every scoreboard line and every chat message changing every frame.

The 50% row is a genuine "no measurable difference", not a small win. It is inside
the noise floor and is reported as inside the noise floor.

### 3.5 What this does not show

Whether a frame gets faster. 11 µs saved per frame is 0.07% of a 16 ms frame and
1.4% of a 0.8 ms one — a range wide enough to be worthless as a prediction. Only an
in-game run settles it.

---

## 4. `bench-2026-08-26-explicit-gc` — what one `System.gc()` costs

**Optimizations:** `memory.world-change-gc`
**Experiments:** `jvm.explicit-gc`, `jvm.heap-size`, `jvm.equal-min-max-heap`, `jvm.collector`
**Scope:** synthetic JVM workload
**Command:** `java <flags> -cp build/classes net.fathom.optimizations.bench.ExplicitGcBench "<label>"`

### 4.1 The workload

A retained graph of small objects with references between them — the shape chunk
sections, block states, entities and texture metadata make on the heap — plus
allocation churn so the young generation is not empty when the collection lands. Then
five `System.gc()` calls, timed. Shape matters more than size: a single large array
would be cheap to trace and would understate every pause badly.

**This is not Minecraft's heap.** It is a heap of roughly the right size and shape.
What it can speak to is JVM behaviour: whether a flag has an effect, and how a cost
scales.

### 4.2 Does `-XX:+DisableExplicitGC` work? — `jvm.explicit-gc`

One variable: the flag. G1, `-Xms2048M -Xmx2048M`, live set half the heap.

| Arm | Median forced-collection cost | Collections performed |
|---|---|---|
| Explicit GC allowed (control) | **446.7 ms** | 5 of 5 requested |
| `-XX:+DisableExplicitGC` | **0.0 ms** | **0 of 5 requested** |

**Answer: yes, completely.** It does not shorten the collection; the JVM does not
perform one. The hypothesis is confirmed and the falsification condition — that the
flag might merely defer the cost — did not occur: the collection count is zero, not
delayed.

**Consequence for the launcher: none.** It already passes
`-XX:+DisableExplicitGC`. What changed is that the flag is now backed by our own
measurement instead of inherited advice.

### 4.3 Does a bigger heap make collections slower? — `jvm.heap-size`

This is where the folk wisdom turned out to be **wrong as usually stated**, and the
experiment earned its keep.

**First sweep — live set scaled with the heap (half of `-Xmx`):**

| `-Xmx` | Live set | Median collection |
|---|---|---|
| 1 GB | 326 MB | 220.9 ms |
| 2 GB | 631 MB | 449.8 ms |
| 4 GB | 1,249 MB | 909.3 ms |
| 8 GB | 2,492 MB | **2,270.8 ms** |

Read alone, that says "8 GB makes world changes take 2.3 seconds" — which is the
claim everyone repeats. But two things varied at once, so it establishes nothing.

**Second sweep — live set pinned at 512 MB, only `-Xmx` varying:**

| `-Xmx` | Live set | Median collection |
|---|---|---|
| 1 GB | 512 MB | 220.0 ms |
| 2 GB | 512 MB | 229.0 ms |
| 4 GB | 512 MB | 238.3 ms |
| 8 GB | 512 MB | 241.0 ms |

**Flat.** A 9.5% spread across an eightfold heap increase, which is barely outside
run-to-run variation.

**What is actually true:** full-collection cost tracks the **live set**, not `-Xmx`.
A larger heap is slower to collect only because it lets a larger live set accumulate
before anything forces a collection. "Give it 8 GB and it stutters more" is a real
effect with the wrong explanation attached, and the difference matters: the fix is not
a smaller `-Xmx`, it is not forcing full collections at all.

**Consequence for the launcher: none, and its 2 GB default is not justified by this.**
The number stands on the separate grounds that 1.8.9 does not need more, not on
"bigger heaps collect slower", which this run does not support.

### 4.4 Does `-Xms` = `-Xmx` help? — `jvm.equal-min-max-heap`

One variable: `-Xms`. `-Xmx2048M`, G1, live set pinned at 512 MB. Five repeats per
arm, interleaved.

| Arm | Heap growth phase (median) | Range | Collection cost (median) |
|---|---|---|---|
| `-Xms2048M` (launcher default) | **207.9 ms** | 205.8 – 223.6 | 226.4 ms |
| `-Xms256M` | **270.1 ms** | 260.3 – 301.0 | 223.6 ms |

The growth-phase ranges do not overlap, so the ~23% difference is real on this
machine. The collection cost is identical, which is the expected result and a useful
check that the harness is measuring what it claims: `-Xms` governs how the heap
grows, not how it is traced.

**Answer: yes, for the growth phase, by about a quarter.** The growth phase is the
closest thing this synthetic workload has to loading a world, which is suggestive and
no more — a real world load does far more than allocate.

**Consequence for the launcher: none.** It already sets `-Xms` equal to `-Xmx`.

### 4.5 Which collector? — `jvm.collector`

One variable: the collector. `-Xms2048M -Xmx2048M`, live set pinned at 512 MB.

| Collector | Growth phase (median) | Forced collection (median) | Range |
|---|---|---|---|
| G1 (launcher default) | 207.9 ms | **226.4 ms** | 223.6 – 227.0 |
| ParallelGC (**the JVM default on this machine**) | **60.7 ms** | 925.9 ms | 922.1 – 932.4 |
| CMS | 61.9 ms | 287.8 ms | single run |

Tight ranges, no overlap.

**This is a trade, not a win, and both halves have to be stated.** G1's pause is
**4.1× shorter** than ParallelGC's. G1's throughput phase is **3.4× slower**. A
collector that pauses for 926 ms is exactly the "classic 1.8 freeze" the research
describes, and it is what every unmodded 1.8.9 player is running, because ParallelGC
is this JVM's default.

For an interactive game, a 700 ms shorter freeze is worth 150 ms of slower loading —
but that is a judgement about what players notice, not something these numbers
establish. **An in-game run is what would settle it**, and until then G1 stays the
launcher default on the strength of a measured pause advantage and a stated
throughput cost.

CMS sits between the two on one run. Not enough to rank it; it is recorded so the
next person does not have to re-run it to know roughly where it lands.

### 4.6 Limitations of every run in §4

- **Synthetic.** Our object graph, not Minecraft's. Absolute figures do not transfer.
- **Wall time, not pause time.** `System.nanoTime()` around `System.gc()` measures the
  calling thread's wall time. It includes any concurrent phase the calling thread
  waits on and excludes pauses that hit other threads. `GcLog.limitations()` records
  the same restriction for the in-game path.
- **One machine, one JVM, one architecture.** Temurin 1.8.0_502 amd64.
- **`System.gc()` is not guaranteed to collect.** Verified rather than assumed: the
  collection counter is read before and after, and the control arm performed all five.

---

## 5. `opt-2026-08-26-quad-batching` — draw calls for a line of text

**Optimization:** `render.font.batching`
**Scope:** structural count, not a timing
**Source:** `OptTests.batchingEmitsTheSameVerticesInTheSameOrder`

A 240-glyph line spanning two font pages, submitted through both paths into a
recording sink.

| Path | Texture binds | Draw calls | Quads per draw |
|---|---|---|---|
| Vanilla (one draw per glyph) | 3 | **240** | 1.0 |
| Batched | 3 | **3** | 80.0 |

The vertex streams were compared **element by element** and are identical: same
quads, same coordinates, same colours, same order. Only the draw boundaries moved.
That is what makes this entry "produces identical output" rather than a visual
trade-off, and it is checked rather than argued.

**Texture binds are unchanged, and that is deliberate.** The vanilla path here is
modelled the way `GlStateManager` actually behaves — it caches the binding and
suppresses redundant binds already. An earlier version of this test re-bound per
glyph and reported 480 events against 243, which would have credited this
optimization with saving 237 texture binds it does not save. The draw calls are the
saving.

**This is not a timing.** It counts calls. Whether 237 fewer driver round trips
matters on a given GPU is a question for an in-game run.

---

## 6. `opt-2026-08-26-pack-icons` — video memory held by pack icons

**Optimization:** `resources.pack-icon-downscale`
**Scope:** exact arithmetic, not a measurement
**Source:** `OptTests.anOversizedIconIsDownscaled`, `OptTests.theSavingIsWorthHavingAcrossAPackFolder`

RGBA8 texture size is a calculation, so this is stated as arithmetic rather than
dressed up as a benchmark.

| Icon as shipped | Video memory | After downscale to 64×64 | Freed |
|---|---|---|---|
| 1024×1024 | 4,194,304 B (4.0 MB) | 16,384 B | **4,177,920 B** |
| 512×512 | 1,048,576 B | 16,384 B | 1,032,192 B |
| 256×256 | 262,144 B | 16,384 B | 245,760 B |
| 128×128 | 65,536 B | 16,384 B | 49,152 B |
| 64×64 or smaller | unchanged | unchanged | **0** |

A plausible 40-pack folder — 24 at 128², 10 at 256², 5 at 512², one at 1024² —
holds **12 MB** above what the resource pack screen samples. The screen never draws
one larger than 32 pixels.

**What this is not.** It is not a frame-time measurement and must never be quoted as
one; this is a memory optimization and should not be expected to move FPS. It is also
not measured *in the game*: it assumes 1.8.9 uploads pack icons at full resolution as
RGBA8 and keeps them for the session, which is what the research describes and what
we have not personally confirmed against the running client.

---

## 7. What would change a default

Nothing here promotes anything. For each entry, the specific run that would:

| Optimization | The run that would settle it |
|---|---|
| `render.font.metrics-cache` | `text-heavy` scenario, median of five, comparing p99 frame time and the `TEXT_RUNS` counter. Needs the Fabric layer compiled. |
| `render.font.batching` | Same scenario, comparing `DRAW_CALLS_APPROX` and p99. The draw-call fall is already known; whether it reaches frame time is not. |
| `memory.world-change-gc` | A world-change timing in the game with and without the mod-side suppression, on a JVM that was **not** given `-XX:+DisableExplicitGC` — with the flag there is nothing left to remove. |
| `resources.pack-icon-downscale` | Video memory before and after with a real pack folder installed. A memory figure, not a frame figure. |
| `render.entity.occlusion-culling` | Not applicable: there is no occlusion test yet, only the refusal rules. |

---

## 8. Reproducing

```bash
cd minecraft/fathom-optimizations
javac -encoding UTF-8 -d build/classes $(find src/main/java/net/fathom/optimizations/core -name '*.java')
javac -encoding UTF-8 -cp build/classes -d build/classes $(find src/test/java -name '*.java')
java -cp build/classes net.fathom.optimizations.core.CoreTests
java -cp build/classes net.fathom.optimizations.core.OptTests
java -Xms2048M -Xmx2048M -cp build/classes net.fathom.optimizations.bench.TextMetricsBench
java -Dfathom.bench.liveMb=512 -Xms2048M -Xmx2048M -XX:+UseG1GC -cp build/classes net.fathom.optimizations.bench.ExplicitGcBench "label"
```

Numbers will differ. The comparisons should not.

---

## 9. References

- `docs/performance/benchmark-methodology.md` — the method these follow
- `docs/performance/optimizations.md` — what was implemented and why
- `docs/performance/optimization-registry.md` — the register and the experiments
- `docs/performance/baseline.md` — the environment record
