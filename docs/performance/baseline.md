# Baseline

The environment every measurement is taken against, and where results go.

**No game measurements exist yet.** The instrumentation is written and tested;
the Fabric mod that would carry it into the game has never been built. §4 is
empty on purpose and stays empty until a run produces it. §5 records the one
thing that *was* measured this milestone, which is a JVM capability rather than a
frame time.

---

## 1. What must be recorded for every run

A benchmark with no recorded environment is a number with no meaning. Every one
of these travels with every result.

| Field | Why it matters |
|---|---|
| Java runtime | Vendor and exact build. `performance.md` §5.1: most modern JVM tuning advice does not apply to Java 8. |
| Heap | `-Xms` and `-Xmx` separately — the `jvm.equal-min-max-heap` experiment turns on the difference. |
| JVM flags | The full argument vector, verbatim. `fathom-cli plan` prints it. |
| GC in use | Read back from `GarbageCollectorMXBean`, not assumed from the flags. |
| Resolution | Fill rate scales with it. |
| Graphics options | Render distance, graphics setting, smooth lighting, clouds, particles, entity shadows, mipmaps, anisotropic and antialiasing if a pack sets them. |
| Render distance | Called out separately — it dominates the chunk scenarios. |
| VSync | **Must be off.** See §2. |
| FPS cap | **Must be unlimited**, except when measuring the frame limiter itself. |
| Resource pack | Name and version, or "default". |
| Hardware | CPU, physical and logical cores, RAM, GPU, driver version, display refresh. |
| Mods present | Everything loaded, including the loader version. Conflicts change what is on. |
| Scenario | Which of the seven, and its reproducibility class. |
| Warm-up | Frames discarded. Non-default values must be stated. |
| Runs | How many, and which one is reported. Median, never best. |

---

## 2. Fixed conditions

These are not preferences. Each one exists because leaving it variable makes the
measurement meaningless.

**VSync off.** With VSync on, frame time is quantised to the display's refresh
interval and every measurement becomes a measurement of the monitor. The
frame-timing Mixin deliberately times the render call rather than the buffer swap
for the same reason, but that is not sufficient on its own.

**FPS cap unlimited.** A cap hides exactly the headroom a rendering optimization
frees. The one exception is `input.frame-limiter-accuracy`, which *is* the cap —
that runs capped, and says so.

**Fullscreen, or a fixed windowed size.** A resized window changes fill rate
mid-run.

**Same machine for before-and-after.** No cross-machine comparisons. Ever. The
scenarios that depend on disk speed (chunk movement) or GPU (everything else)
produce numbers that are not portable.

**Nothing else running.** Browsers in particular: a background tab compositing
video will move a 1% low more than most optimizations will.

**Interleave arms.** A, B, A, B — never A×5 then B×5. Thermal state drifts over
minutes and batching turns that drift into an apparent result.

---

## 3. The reference machine

The machine this project is being developed on. Recorded because it is where the
first measurements will be taken, not because it is representative — it is a
fast machine with a fast GPU, and 1.8.9's problems are mostly CPU-bound and
therefore *less* visible here than on the hardware most 1.8.9 players use.

Gathered 2026-08-26 from `Win32_*` and `java -XshowSettings:properties`.

| | |
|---|---|
| OS | Windows 11 Home 10.0.26200 |
| CPU | 12th Gen Intel Core i5-12400F |
| Cores | 6 physical / 12 logical |
| RAM | 16 GB |
| GPU | NVIDIA GeForce RTX 4060, driver 32.0.16.1088 |
| Display | 1920×1080 @ 200 Hz |

**A 200 Hz display is worth noting.** Frame times below 5 ms are common on this
setup, which makes percentile differences small in absolute terms and easy to
lose in noise. The noise-floor step in `benchmark-methodology.md` §5.1 matters
more here than it would on a 60 Hz machine.

### 3.1 Java runtime

| | |
|---|---|
| Vendor | Temurin (Adoptium) |
| Version | 1.8.0_502 |
| VM | OpenJDK 64-Bit Server VM, 25.502-b07 |
| Architecture | amd64, 64-bit |

### 3.2 This JVM's own defaults

Read from `-XX:+PrintFlagsFinal` on the reference machine, with no flags passed.
These are what the game would get if the launcher passed nothing, and they are
the control arm for every JVM experiment.

| Flag | Default here |
|---|---|
| `InitialHeapSize` | 266,338,304 (254 MB) |
| `MaxHeapSize` | 4,238,344,192 (4.0 GB — one quarter of 16 GB) |
| `UseParallelGC` | **true** |
| `UseG1GC` | false |
| `UseConcMarkSweepGC` | false |
| `UseSerialGC` | false |
| `ReservedCodeCacheSize` | 251,658,240 (240 MB) |
| `DontCompileHugeMethods` | **true** |

Two of these are worth reading carefully:

- **ParallelGC is the default.** `performance.md` §5.3 calls it "the source of
  the classic 1.8 freeze". Every unmodded 1.8.9 player is running it, which makes
  it the honest control arm for `jvm.collector` rather than a straw man.
- **`DontCompileHugeMethods` is on by default**, which is the state
  `performance.md` §5.4 describes: Minecraft methods over the 8000-byte limit
  never compile.

### 3.3 What the launcher currently passes

Verbatim from `fathom-cli plan` on this machine:

```
-Xms2048M -Xmx2048M -XX:+DisableExplicitGC -XX:+UseG1GC -XX:MaxGCPauseMillis=50
-Djava.library.path=<profile>/natives -Dfile.encoding=UTF-8
-Dlog4j.configurationFile=<shared>/logging/client-1.7.xml
```

Three of those four now have our own measurement behind them, taken on 2026-08-26
and recorded in `benchmarks.md` §4:

| Flag | What we measured | Verdict |
|---|---|---|
| `-XX:+DisableExplicitGC` | Removes the forced collection entirely: 446.7 ms → 0.0 ms, with the collection counter confirming zero collections rather than deferred ones | Keep |
| `-Xms` = `-Xmx` | ~23% faster through the heap-growth phase, no effect on collection cost; non-overlapping ranges over five interleaved repeats | Keep |
| `-XX:+UseG1GC` | Pauses **4.1× shorter** than ParallelGC — this JVM's default, and what every unmodded 1.8.9 player runs — at the cost of a **3.4× slower** growth phase | Keep, with the cost stated |
| `-XX:MaxGCPauseMillis=50` | **Still unmeasured.** Nothing has isolated the pause target from the choice of G1 itself | Unchanged, unjustified |

**No default changed.** The measurements confirmed the existing choices rather than
overturning them, which is a real outcome and not a formality: before this they rested
on `performance.md`'s reasoning alone.

**And the 2 GB heap is not justified by what we measured.** The usual argument — that
a larger heap makes collections slower — turned out to be wrong as stated. See
`benchmarks.md` §4.3: with the live set held constant, collection cost is flat across
an eightfold heap increase. The number stands on the separate grounds that 1.8.9 does
not need more.

---

## 4. Measurements

### 4.1 In-game

**Empty.** No frame time, FPS, or in-game heap figure has been produced, because the
mod's Fabric layer has not been compiled and no scenario harness exists.

When the first run happens it goes here as a table with the full §1 record attached,
and `Optimization.evidence()` may then carry an `Evidence.inGame(...)` for whatever it
covered — not before. That is the only kind of evidence that turns anything on.

### 4.2 Isolated

Not empty. Microbenchmarks of individual functions and synthetic JVM workloads are in
**`benchmarks.md`**, kept there rather than here because they are measurements of a
function or of a JVM and not of this machine running Minecraft.

They do not promote anything, by design. They are why we know the implementations
work and are not slower than what they replace.

---

## 5. What *was* measured: Shenandoah availability

`performance.md` open question 1 asks whether Shenandoah is present in the
OpenJDK 8 builds we would actually encounter, or only in Red Hat's. It is cheap
to answer, so it was answered.

**Method.** Start the JVM with the flag and observe. This is the
`jvm.shenandoah-availability` experiment's probe arm.

```bash
java -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -version
```

**Result on Temurin 1.8.0_502, amd64:**

```
Unrecognized VM option 'UseShenandoahGC'
Error: Could not create the Java Virtual Machine.
```

The control (`java -version` with no flags) starts normally, so the probe is
measuring the flag and not something else.

The full collector set on this build, from `-XX:+PrintFlagsFinal`:
`UseSerialGC`, `UseParallelGC`, `UseParallelOldGC`, `UseParNewGC`,
`UseConcMarkSweepGC`, `UseG1GC`. No Shenandoah, no ZGC.

**Conclusion.** Shenandoah is **not available** on Temurin 8. The hypothesis in
`performance.md` §5.3 — that it is a Red Hat backport absent from mainstream
Java 8 builds — is confirmed for Temurin, which is the vendor the reference
machine and the launcher's Java detection both find.

**Scope of this result.** One vendor, one version, one architecture. It does not
establish anything about Corretto 8, Zulu 8, or Red Hat's own OpenJDK 8, and it
should not be quoted as though it did. The `jvm.collector` experiment's arms are
limited to what shipped with Java 8 until someone checks the others.

---

## 6. Recording template

Copy this per run. Fields left blank invalidate the run rather than being assumed.

```
Run:            <id>
Date:           <iso8601>
Scenario:       <id>          Reproducibility: <class>
Build:          <git sha>     Mod version: <version>
Arm:            <what varies>

Java:           <vendor> <version> (<vm name> <vm version>)
Heap:           -Xms<n> -Xmx<n>
JVM flags:      <verbatim>
GC in use:      <from GarbageCollectorMXBean, not from the flags>

Resolution:     <w>x<h>        Fullscreen: <y/n>
Render dist:    <chunks>       Graphics: <fast/fancy>
VSync:          off            FPS cap: unlimited
Resource pack:  <name or "default">
Mods:           <every loaded mod and version>

Hardware:       <cpu> / <cores> / <ram> / <gpu> <driver> / <refresh>

Warm-up:        <frames>       Runs: <n>, reporting median
Optimizations:  <every id and its state, from SwitchBoard.snapshot()>

--- results ---
Frames:                 <n>          Wrapped: <y/n>
Average FPS:
Median frame time:
p95 / p99 frame time:
1% low FPS:                          (>= 10 frames in slice? y/n)
0.1% low FPS:                        (>= 10 frames in slice? y/n)
Frame time CoV:
Peak / mean heap:
Allocation rate:                     (measured / ESTIMATED)
GC collections / total pause:
Counters:               <the ones this scenario targets>

Caveats:        <verbatim from Report.caveats()>
```

---

## 7. References

- `docs/performance/benchmark-methodology.md` — how to measure
- `docs/performance/optimization-registry.md` — what is registered, and the experiments
- `docs/research/performance.md` §5 — the JVM analysis these experiments test
