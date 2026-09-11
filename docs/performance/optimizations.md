# Optimizations — first batch

What was evaluated, what was implemented, what was rejected, and what the evidence
actually supports.

**Outcome first: nothing is on by default.** Five optimizations were implemented and
tested, four carry real measurements, and none of the measurements is of Minecraft —
because the Fabric layer has never been compiled and no frame has ever been rendered
by this code. §7 says exactly what that means and what would change it.

---

## 1. How candidates were chosen

The brief named eight areas. Each was assessed against five criteria, and a candidate
had to pass all five to be implemented in this batch:

| Criterion | What it rules out |
|---|---|
| Strong technical justification | "Everyone does it" |
| Low compatibility risk | Anything that changes what a server sees, or fights another mod |
| Negligible visual change | Anything the player would notice, however fast |
| Measurable benefit | Anything we could not put a number against, even an isolated one |
| Legally clean basis | Anything requiring someone else's source to be read |

### 1.1 What was implemented

| # | Optimization | Why it passed |
|---|---|---|
| 1 | `memory.world-change-gc` | Removing a call. No output at all, let alone different output. |
| 2 | `render.font.metrics-cache` | Caching a pure function. Output-identical by construction. |
| 3 | `render.font.batching` | Same vertices, moved draw boundaries. Verified element by element. |
| 4 | `resources.pack-icon-downscale` | Pixels genuinely not sampled. Arithmetic, not judgement. |
| 5 | `render.entity.occlusion-culling` — **refusal rules only** | The dangerous half, made testable and shipped first. |

### 1.2 What was rejected, and why

Rejecting is the point of an evaluation stage. Each of these was considered
seriously, and each failed a specific criterion rather than being dropped for time.

**`render.tile-entity.double-render-fix` — precondition unverifiable.**
The research says some special tile entities render twice per frame, and
deduplicating them would be free. But the second pass may not be the same pass: 1.8.9
walks a destroy-progress list separately, and that pass draws the block-breaking
overlay. Deduplicating two passes that differ would delete the crack overlay from
every block being mined. **Without the decompiled source in front of us we cannot
establish that the passes are equivalent**, and an optimization whose safety rests on
an unchecked assumption is not a safe optimization. Deferred until the source can be
read.

**`render.hud.quad-batching`, `render.entity.model-batching`,
`render.tile-entity.batching` — the mechanism exists, the wiring does not.**
`QuadBatcher` is general and would serve all three. Each needs a Mixin against a
different vanilla call site, and each has its own ordering hazards — the HUD blends
over itself, entity models push and pop matrices between draws. Claiming them on the
strength of the font work would be claiming work not done. The batcher is written and
tested; only the font entry claims it.

**`input.mc-67665-look-vector` — nothing to implement in `core`.**
The fix is a reordering of two operations inside the frame loop: compute the look
vector after processing mouse input rather than before. There is no logic to extract,
test, or benchmark — it is a Mixin and nothing else. It also cannot be measured by
this project's own method: `benchmark-methodology.md` §4.2 records that frame times
are identical before and after, because the change is perceptual. It stays registered,
unimplemented, and off.

**`cpu.allocation.hot-path-reduction` — too broad to be one thing.**
"Reduce allocation on hot paths" is a programme, not an optimization. The specific
sites the research names — `World.getCollidingBoundingBoxes` allocating a fresh list
and fresh immutable AABBs per entity per tick, `Vec3` in ray tracing, `BlockPos`
instead of `MutableBlockPos` — each need their own entry, their own patch and their
own measurement. Splitting them is future work; implementing a "general allocation
reducer" would be implementing nothing.

**`render.particle.brightness-cache` — a visual change.**
Caching a particle's brightness for its lifetime means a particle drifting across a
light boundary does not re-light. Essentially invisible in motion, and still a
different image. That makes it Optional, which means the user chooses, which means it
is not part of a batch of safe defaults.

**BetterFps-style `MathHelper` replacement — the research says no.**
`performance.md` §3.3 calls it the most over-rated optimization in the 1.8.9 space:
real, small, hardware-dependent, and *unsigned* — faster on some machines and slower
on others, which is why BetterFps ships a per-machine benchmark rather than a winner.
It belongs in Experimental with an opt-in benchmark, and it is not a default-batch
candidate.

**Whole-HUD frame caching — Experimental for a reason.**
Rendering the HUD to a texture and reusing it for N frames makes animated elements
choppy. The judgement is correct and worth copying; the code is not ours to copy.

---

## 2. `memory.world-change-gc` — remove `System.gc()` on world change

### Current vanilla behaviour
1.8.9 calls `System.gc()` explicitly when the world changes: joining a server,
returning to the menu, going through a portal. On a default JVM that is a full,
stop-the-world collection of the entire heap, requested at the exact moment the client
is also loading chunks.

### The hot path
There is no hot path. This is a single call with a large fixed cost, on a path a
lobby-hopping player crosses every few seconds. The call does nothing the collector
would not do on its own — it brings a collection forward and forces it to be a full
one. What the client needs at that moment is for the old world's objects to become
collectable, which happens when the references are dropped, with or without it.

### Isolated baseline
`bench-2026-08-26-explicit-gc`. A forced full collection under a synthetic 512 MB live
set: **226 ms median with G1**, **926 ms with ParallelGC** — the collector every
unmodded 1.8.9 player is running, because it is Temurin 8's default.

### The patch
`core/opt/gc/ExplicitGcPolicy`. Not an empty method: silently making `System.gc()` a
no-op everywhere, forever, is a bigger change than it looks. A mod, a heap-dump tool
or a debugger may call it for a reason we cannot see.

- Only calls identified as the world-change one are suppressed. `Site.OTHER` always
  passes through.
- A **rate limit**, not a ban — one collection per 60 seconds — so something that
  genuinely wanted one still gets it.
- The first world change of a session passes through, so that a user running both this
  and `-XX:+DisableExplicitGC` can tell which is in effect.
- Every decision is counted, so a benchmark can state collections actually avoided
  rather than assuming.

### Correctness
Six assertions in `OptTests`. Twenty lobby hops collect once, not twenty-one times.
Another caller's collection is never suppressed. The interval elapses.
**Nothing visual is involved**; there is no output to compare.

### Benchmark
`-XX:+DisableExplicitGC` takes the cost from **446.7 ms to 0.0 ms**, with the
collection counter confirming **zero collections performed** rather than deferred.

### Interaction with the supported loader configuration
None. No Mixin, no vanilla method modified, no interaction with Legacy Fabric.

### Result and default decision
**Off.** The measurement is synthetic and is not a measurement of Minecraft.

**And the launcher already passes `-XX:+DisableExplicitGC`**, which is the same fix by
a better route — one flag, no bytecode. What changed is that the flag is now backed by
our own measurement instead of inherited advice. The mod-side policy exists for users
running this mod under someone else's launcher.

---

## 3. `render.font.metrics-cache` — remember string widths

### Current vanilla behaviour
`FontRenderer.getStringWidth` walks a string character by character, consulting the
width tables and parsing formatting codes, and returns an integer. It is invoked from
the centring, wrapping and trimming paths and from scoreboard, chat, tab-list and
nametag layout — **every frame, over a corpus that is almost entirely unchanged since
the last frame**.

### The hot path
The work is O(length) and cheap per call. The problem is the call count against a
nearly-static corpus. A busy server frame measures **235 strings and 11,528
characters**, nearly all identical to the previous frame's.

### Isolated baseline
`bench-2026-08-26-text-metrics`. One frame of that corpus, recomputed: **12.9 µs**.

### The patch
`core/opt/text/TextMetricsCache`, wrapping `core/opt/text/TextMeasurer`. Width is a
pure function of the string and the loaded font, so remembering the answer is
output-identical by construction — there is no approximation here to be wrong about.

Three failure modes, each closed:

- **Stale font.** Every entry is keyed on `GlyphSource.generation()`, checked on every
  lookup rather than hooked to a reload event. A missed event would be a silent
  correctness failure surviving until restart; an integer comparison per lookup buys
  certainty for nothing.
- **Unbounded growth.** Chat is adversarial: a server can emit a distinct string every
  tick forever. Fixed-capacity LRU at 4,096 entries. The worst case is a cache that
  never hits, not a client that runs out of heap — a memory leak disqualifies an
  optimization however fast it is.
- **Wrong thread.** 1.8.9 lays out text on the render thread only. Deliberately
  unsynchronised, with the assumption stated and assertable rather than implied.

`TextMeasurer` reimplements vanilla's rule, which is short enough to state in prose:
formatting codes contribute no width and set or clear bold; every other character
contributes its advance plus one pixel if bold. That is a **compatibility requirement**
— text that measures differently from vanilla renders in the wrong place — not a
design choice, and it exists so the cache can be differentially tested outside the
game.

### Correctness
The central test feeds **10,000 randomly generated strings** — letters, digits,
spaces, characters above the ASCII page, formatting codes in every position including
adjacent, invalid and trailing ones — through both paths and compares every answer,
then asks again to catch a cache that is right on the miss and wrong on the hit.

Four more pin the vanilla rule itself, including the one a naive implementation gets
wrong: **a colour code clears bold**, so `§lab§ccd` is not two pixels wider than it
should be for the rest of the line.

### Benchmark

| Churn per frame | Recompute | Cached | Difference |
|---|---|---|---|
| 0% (static) | 12,922 ns | 1,795 ns | **86.1% faster** |
| 5% | 15,607 ns | 5,238 ns | **66.4% faster** |
| 50% | 18,054 ns | 16,026 ns | inside the noise floor |
| 100% | 14,590 ns | 21,386 ns | **46.6% slower** |

Hit rate at 5% churn over 600 frames: **94.9%**.

**The 100% row is reported because it is the honest cost.** A cache that never hits
does strictly more work than no cache. It does not occur in practice — it would mean
every name, scoreboard line and chat message changing every frame — but publishing
only the 86% figure would have hidden it.

### Interaction with the supported loader configuration
Conflicts with **OptiFine**, which replaces the font paths. Registered as
`DISABLE_OURS`: the user chose to install OptiFine and it is not our place to
overrule that.

### Result and default decision
**Off.** 11 µs saved per frame is between 0.07% and 1.4% of a frame depending on
frame rate — a range too wide to be a prediction. Only a `text-heavy` scenario run
settles it.

---

## 4. `render.font.batching` — one draw per text run, not per glyph

### Current vanilla behaviour
`FontRenderer.renderChar` begins a tessellator, emits one glyph's four vertices, and
calls `Tessellator.draw()`. **Per character.** A frame carrying a scoreboard, tab
list, chat backlog and a screenful of nametags is several thousand characters and
therefore several thousand draw calls, each a driver round trip that draws two
triangles. The glyphs on a line overwhelmingly share one texture — the ASCII page is
one atlas — so nearly all of them could have been one draw.

### The hot path
Driver round trips, not CPU work.

### Isolated baseline
A 240-glyph line spanning two font pages: **240 draw calls**, 1.0 quads per draw.

### The patch
`core/opt/text/QuadBatcher` writing to a `QuadSink`. Accumulate; flush when the
texture is about to change or when told.

**Two boundaries the implementation is careful about**, both because immediate-mode 2D
drawing is order-dependent — a later quad blends over an earlier one:

- It may **defer** a draw. It may never **reorder** to group textures together.
  Sorting by texture would be a larger win and would put chat behind the vignette.
- The caller must flush before any state change the batcher cannot see: blend mode,
  scissor, matrix push, depth test, or handing control to code outside this mod.
  `pendingQuads()` exists so a Mixin can assert it flushed rather than hope, and
  `flush()` is free when empty so the defensive call survives review.

### Correctness
The vertex streams from both paths are compared **element by element** — same quads,
same coordinates, same colours, same order. Only the draw boundaries moved. A
separate test pins the ordering hazard directly: the flush happens *before* the
rebind, or the first quad would be drawn with the second quad's texture.

### Benchmark

| Path | Texture binds | Draw calls | Quads per draw |
|---|---|---|---|
| Vanilla | 3 | **240** | 1.0 |
| Batched | 3 | **3** | 80.0 |

**Texture binds are unchanged and the benchmark says so.** An earlier version of this
test modelled vanilla as re-binding per glyph and reported saving 237 binds. That was
wrong — `GlStateManager` caches the binding and suppresses redundant binds already
(research §2.8) — and it would have credited this optimization with a saving it does
not make. The draw calls are the saving.

### Interaction with the supported loader configuration
Conflicts with **OptiFine** and **PolyPatcher**, both of which replace font rendering.
`DISABLE_OURS` for both.

### Result and default decision
**Off.** A draw-call count is not a timing. `benchmark-methodology.md` §6 is explicit
that "counter moved, frame time did not" licenses only "does what it says; no
measurable frame-time effect" — and we have not even measured the frame time yet.

---

## 5. `resources.pack-icon-downscale` — stop holding 4 MB for a fingernail

### Current vanilla behaviour
Every installed pack's `pack.png` is uploaded as a GPU texture at whatever resolution
its author saved it, and kept for the session. The resource pack screen draws each
into a **32×32 slot**. A pack shipping a 1024×1024 icon holds **4 MB of video
memory** to fill a square the size of a fingernail, per pack, paid at startup before
the user has entered a world.

### The hot path
Not a frame cost at all. This is memory, and it is one of the three defects MemoryFix
names for 1.8.9 — the one with the cleanest fix, because the pixels are genuinely not
needed.

### Isolated baseline
Arithmetic, stated as arithmetic. A plausible 40-pack folder holds **12 MB** above
what the screen samples.

### The patch
`core/opt/resource/PackIconPolicy`. Downscale to 64 pixels on the longest edge on
load; drop the full-size image.

- **64, not 32**, though 32 would be exactly enough and save four times as much again.
  64 leaves headroom for a GUI scale or a screen that draws the icon larger, and the
  difference is 12 KB per pack — not worth being clever about, and being wrong here
  means a blurry icon rather than a saved megabyte.
- **Never upscales.** A 16×16 icon is what the author chose; enlarging it would cost
  memory to change nothing.
- **Only `pack.png`.** The class takes an icon and nothing else, so it cannot grow
  into a texture-quality setting by accident. Downscaling any other texture is a
  visible change to the game.

### Correctness
Aspect ratio is preserved and neither edge may round to zero — pack icons are square
in practice, but "in practice" is not a guarantee about files supplied by strangers,
and a zero-width texture upload is a crash rather than a blurry icon. A 4096×1 icon
produces 64×1, not 64×0.

Visually: a 64×64 texture drawn into a 32×32 slot has a 2× margin over the size it is
sampled at, which is more than a bilinear filter needs.

### Benchmark
See `benchmarks.md` §6. Exact, because RGBA8 texture size is a calculation.

### Interaction with the supported loader configuration
None registered. It touches resource loading, not rendering.

### Result and default decision
**Off.** Two things are unmeasured: whether 1.8.9 in fact uploads pack icons at full
resolution and keeps them — the research says so and we have not confirmed it against
a running client — and what a real pack folder frees. **This should never be expected
to move FPS**; it is a memory optimization and the registry entry says so, so that it
is not later judged against the wrong metric.

---

## 6. `render.entity.occlusion-culling` — the refusal rules, and only those

### What was built, and what deliberately was not
The brief is conditional: *"If implementing culling: be conservative. Never blindly
cull based only on occlusion assumptions."* So this batch implements the
**refusal half** — the rules that say an entity must be drawn regardless of what any
occlusion test concluded — and **no occlusion test at all**.

That split is the point. The dangerous part of entity culling is not the geometry, it
is the exception list, and the exception list is the part that can be written down,
argued about and tested without a GPU. Shipping it first means the occlusion test,
when it is written, arrives with its safety rails already checked. On its own this
cannot make the game faster, and it is not claimed to.

### Current vanilla behaviour
1.8.9 culls entities by view frustum and render distance and by nothing else. An
armour stand sealed inside a wall a hundred blocks away goes through the full render
path. A lobby holds hundreds of them.

### The rules, and the regression each prevents

| Rule | The regression it prevents |
|---|---|
| Stale or absent occlusion result → render | **The rule that makes every other failure degrade into "we drew too much" rather than "the player could not see an enemy."** Occlusion data is computed asynchronously and is stale the moment the camera moves. |
| Not occluded → render | The contract, stated rather than left as a fall-through |
| The viewer, or anything they ride → render | — |
| Any player → render | Hiding a player for a frame in a PvP client would be reported as cheating by one side and as a bug by the other |
| Visible nametag → render | Nametags draw through terrain in vanilla. On servers using armour stands as holograms, this is the difference between an optimization and a lobby with the text deleted |
| Boss or world-effect source → render | Ender dragon, wither, beacon, end gateway, guardian |
| Renders beyond its own bounds → render | Beacon beams, guardian lasers, fishing lines — culling the source deletes the beam, which is visible far outside the box the test looked at |
| Drawing its own effects → render | On fire, in a portal |
| Larger than 4 blocks → render | A single-point occlusion test does not speak for a box that size |
| Closer than 16 blocks → render | Highest cost of being wrong, lowest saving |

### Compatibility hooks
Other software gets a vote, and **the vote is one-directional**: a `Hook` may insist
an entity is rendered and has no way to ask for one to be hidden. The interface has a
single method returning "must render", so no amount of third-party code can remove
one of our safety rules — a shader pack rendering shadows needs geometry outside the
camera's view entirely, and OptiFine and user mods have reasons we cannot anticipate.

A hook that **throws** is a vote to render. `Throwable`, not `Exception`: a mod
compiled against a different version throws `NoSuchMethodError`, and the safe reading
of an answer we did not get is "draw it".

### Correctness
Nine tests. Each rule is checked in isolation, the ordinary case is checked to cull,
and the hook asymmetry is asserted structurally against the interface itself.

### Result and default decision
**Off, and it is Optional rather than Safe** — it may never default on whatever the
measurements say, because it changes what is drawn. Nothing to benchmark yet: there
is no occlusion test.

---

## 7. Why nothing is enabled by default

The brief says every enabled-by-default optimization must have evidence. Nothing is
enabled, so the requirement is met vacuously — which is not a satisfying answer, so
here is the real one.

**The Fabric layer has never been compiled.** There is no Gradle-capable environment
here, no Minecraft jar, no LWJGL, no frame. Everything above was built in `core`,
which has no game dependencies precisely so that it could be written and verified
anyway.

That leaves only isolated evidence, and this project's own method says isolated
evidence does not promote anything. That rule is now enforced in code rather than
trusted to discipline:

```java
public boolean defaultEnabled() {
    return measuredInGame() && defaultOnWhenMeasured;
}
```

`Evidence.Level.ISOLATED` does not satisfy `measuredInGame()`. The old boolean
`measured` flag could not tell "we timed this function in a loop" from "we ran the
game and the frame times moved", and collapsing the two is how a 66% microbenchmark
win becomes a "66% more FPS" line in a changelog. A test asserts that no entry claims
in-game evidence, so a future change that promotes something on the strength of a
microbenchmark fails the build.

**What this batch did produce:** five implementations with 30 correctness tests, four
real measurements, and — the part with immediate practical value — **three of the
launcher's existing JVM defaults moved from inherited folk wisdom to measured fact**,
and one piece of folk wisdom corrected. See §8.

`benchmarks.md` §7 lists, per optimization, the specific run that would change its
default.

---

## 8. The JVM experiments

Benchmarked separately, one variable at a time, as the brief requires. Full data in
`benchmarks.md` §4.

| Experiment | Result | Launcher default | Changed? |
|---|---|---|---|
| `jvm.explicit-gc` | `-XX:+DisableExplicitGC` removes the collection entirely: 446.7 ms → **0.0 ms**, zero collections performed | already passes it | No — now measured rather than assumed |
| `jvm.equal-min-max-heap` | `-Xms` = `-Xmx` is **~23% faster** through the heap-growth phase; no effect on collection cost. Non-overlapping ranges over five interleaved repeats | already sets them equal | No — now measured |
| `jvm.collector` | G1 pauses **4.1× shorter** than ParallelGC (226 ms vs 926 ms) and is **3.4× slower** through the growth phase (208 ms vs 61 ms) | already G1 | No — now measured, **with its cost stated** |
| `jvm.heap-size` | **The common claim is wrong as usually stated.** See below | 2 GB | No, and this run does not justify it |

### 8.1 The heap-size finding

Sweeping `-Xmx` with the live set scaled to half the heap gave 221 / 450 / 909 /
2,271 ms for 1 / 2 / 4 / 8 GB — which reads as "8 GB makes world changes take 2.3
seconds", the claim everyone repeats. **Two things varied at once**, so it establishes
nothing.

Pinning the live set at 512 MB and varying only `-Xmx` gave **220 / 229 / 238 / 241
ms**: flat across an eightfold heap increase.

Full-collection cost tracks the **live set**, not `-Xmx`. A larger heap is slower to
collect only because it lets a larger live set accumulate before anything forces a
collection. "Give it 8 GB and it stutters more" is a real effect with the wrong
explanation attached, and the difference matters: the fix is not a smaller `-Xmx`, it
is not forcing full collections at all.

This is exactly what the instruction not to hardcode JVM claims was protecting
against, and it is the single most useful thing this batch produced.

---

## 9. Source discipline

No source was read from Patcher, PolyPatcher, OptiFine, MemoryFix or any other
restricted-licence project. Where one of them told us a problem exists — the pack icon
waste, the tile-entity double render — that is recorded as
`THIRD_PARTY_PROBLEM_STATEMENT` on the registry entry, and the implementation is our
own.

Knowing *that a problem exists* is a fact about Minecraft. Knowing *how someone else
solved it* is their work. `mod-policies.md` §3 establishes that Patcher and
PolyPatcher are CC BY-NC-SA and their code is not reusable by us.

The one place vanilla's own algorithm is reproduced — `TextMeasurer` — reproduces a
**rule stated in prose**, and it is there because text that measures differently from
vanilla renders in the wrong place. That is a compatibility requirement, not a design
we chose.

---

## 10. References

- `docs/performance/benchmarks.md` — the runs, with full environment and limitations
- `docs/performance/optimization-registry.md` — the register and the experiments
- `docs/performance/benchmark-methodology.md` — how anything gets measured
- `docs/performance/baseline.md` — the reference machine
- `docs/research/performance.md` — the problem analysis
- `docs/research/mod-policies.md` §3, §7, §8 — licensing and conflicts
