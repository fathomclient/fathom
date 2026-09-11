# Comparing Fathom with Lunar Client

A method for comparing the two clients on the same machine, and a statement of
what such a comparison can and cannot support.

**No comparison has been run.** This document contains no numbers. It exists so
that when one is run, it is run the same way twice and the result means
something.

---

## 1. The rule this exists to enforce

**Do not chase raw FPS numbers.**

A higher average frame rate is the easiest number to move and one of the least
useful things to know. It can be bought by rendering fewer chunks, by capping
differently, by measuring during a different part of a world load, or by
measuring a scene that happens to suit one renderer. None of those make the game
better to play, and two of them make it worse.

What matters, in this order:

1. **Frame consistency.** The 95th and 99th percentile frame times. A client that
   averages 240 fps with a 40 ms stutter every few seconds is worse to play than
   one that holds 144 flat, and the average cannot tell them apart.
2. **Input latency.** Whether hover and click respond to the frame rather than to
   the 20 Hz tick. Fathom's interface polls per frame for this reason; see
   `docs/ingame-ui-renderer-decision.md` §6.
3. **Time to playable.** Cold start to the main menu, and menu to spawned in a
   world.
4. **Memory.** Steady-state heap after a settle period, and garbage collection
   pause count and duration over a fixed window.
5. **Average frame rate.** Last, and reported with its percentiles beside it or
   not at all.

---

## 2. What makes a comparison honest

A comparison between two clients is only meaningful when everything except the
client is identical. That is harder than it sounds, and most published
comparisons fail at least one of these.

### 2.1 Must be identical

| Thing | How to hold it constant |
| --- | --- |
| World | The same world, copied to both profiles from one source, not "a similar world". Include the level seed and the exact spawn coordinates in the report. |
| Position and view | Teleport to fixed coordinates with a fixed yaw and pitch. `/tp @s x y z yaw pitch`. Never "stand roughly here". |
| Video settings | Render distance, graphics, smooth lighting, particles, clouds, entity shadows, VSync, GUI scale, field of view, brightness. Record all of them. |
| Frame cap | Uncapped for the throughput run; capped identically for the consistency run. Fathom's menu and unfocused caps must be **off** for benchmarking or documented as on for both. |
| Resource pack | Default on both, unless the pack is the thing being tested. |
| Window | Same size, same monitor, same windowed/fullscreen state. |
| Minecraft version | 1.8.9 on both. |
| Java | The same JVM build and the same heap arguments. A different `-Xmx` is a different benchmark. |
| Machine state | Same power profile, same background processes, same wall-clock ambient temperature range. |

### 2.2 Cannot be held constant, and must be declared

- **Mod set.** Lunar bundles things Fathom does not have and vice versa. Either
  disable everything optional on both — which is the fair comparison of the
  clients themselves — or list exactly what was on. Do not compare Fathom with
  four modules on against Lunar with its defaults.
- **Optimizations.** Fathom's optimization registry ships mostly off. State which
  are on. A comparison of "Fathom with everything enabled" against "Lunar out of
  the box" is a comparison of two configurations, not two clients.
- **What each client renders.** If one draws a HUD widget the other does not, say
  so.

### 2.3 Thermal drift is the usual reason numbers do not reproduce

The second client measured is measured on a warmer machine. Alternate the order
(A, B, B, A, B, A, A, B) and discard any run whose CPU or GPU clock differs by
more than 5% from the session median. Without this, a twenty-minute session can
show a consistent five-percent advantage for whichever client went first.

---

## 3. The scenarios

Five, each run for a fixed duration, each repeated at least three times per
client with the alternating order above. All five are scripted or fixed-camera:
nothing involves a human moving a mouse, because a human is not reproducible.

| # | Scenario | Duration | What it stresses |
| --- | --- | --- | --- |
| 1 | Standing still at a fixed viewpoint over open terrain | 60 s | Steady-state render throughput, baseline noise |
| 2 | Fixed circular pan, one revolution every 20 s | 120 s | Chunk visibility churn, culling |
| 3 | Walking a fixed path (recorded input, or autowalk between two points) | 120 s | Chunk load and mesh build — where 1.8.9 stutters |
| 4 | Crowded scene: 50 entities in view at a fixed viewpoint | 60 s | Entity rendering |
| 5 | Interface: open the client's own menu and leave it open | 60 s | Menu cost, and whether the world keeps rendering behind it |

Scenario 5 is where the two clients differ most in kind, not degree. Fathom caps
its own menus at 30 fps by default and exempts its hub; measure both with the cap
off, then note the capped figure separately. Comparing Fathom's capped menu
against Lunar's uncapped one and reporting the difference as a deficit would be
measuring a deliberate choice and calling it a defect.

---

## 4. What to record

Per run:

| Metric | Source |
| --- | --- |
| Frame times, every frame | `FrameLog` in `core/instrument` |
| Mean, median, p95, p99, worst | Computed from the above, not sampled |
| Frames over 16.7 ms / 33.3 ms, as a percentage | Same |
| Heap after a 30 s settle | `MemorySampler` |
| GC pauses: count and total ms | `GcLog` |
| CPU time of the client process | OS |

Fathom can produce all of these itself (`core/instrument/Report.java`). Lunar
cannot, so the frame-time capture for it has to come from an external tool
applied identically to both — otherwise the comparison is partly a comparison of
two measuring instruments. **Use the external tool's numbers for both clients**,
and use Fathom's own instrumentation only for Fathom-versus-Fathom work.

Report every run, not the best one. A median of nine runs with the spread shown
is a result; the fastest of nine is an anecdote.

---

## 5. How to report it

A comparison is publishable when it states, in this order:

1. The machine: CPU, GPU, RAM, OS build, driver version.
2. The Java build and heap arguments.
3. Every video setting, for both.
4. Every module and optimization enabled, for both.
5. The world seed and the exact coordinates of each scenario.
6. The number of runs, the order they were run in, and any runs discarded and why.
7. Per scenario: median and spread of p95 and p99 frame time, then mean fps.
8. What it does not show.

Point 8 is not modesty. A five-scenario benchmark on one machine says nothing
about a different GPU vendor, a different driver, a laptop under thermal limits,
or a server with hundreds of entities. Claiming more than that from these numbers
would be the same error as promoting an optimization on the strength of a
microbenchmark, which `docs/performance/benchmark-methodology.md` §1 already
refuses.

---

## 6. What would count as a real finding

- A consistent difference in p99 frame time larger than the spread between runs
  of the same client. That is a stutter one client has and the other does not,
  and it is worth chasing.
- A difference in scenario 3 but not scenario 1. That points at chunk meshing,
  which is where 1.8.9 actually hurts.
- A difference in GC pause count. That points at allocation in a hot path, which
  is fixable and worth fixing.

And what would not:

- A few percent on mean fps in scenario 1, within run-to-run spread.
- Any difference in scenario 5 that turns out to be the frame cap.
- Anything measured once.
