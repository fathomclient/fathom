# Design: the particle tick

Status: **design, not built.** The largest single contributor to the slowest
frames in combat. This document is the gate before any code.

## The cost

From `multiplayer-2026-10-13.md` (`combat-particles`: 20 armoured players, crit,
magic-crit and red-dust bursts from the server, ~950 live particles):

- A client tick lands in about 1 frame in 20 at these frame rates, and in **83–95%
  of the slowest 1% of frames**.
- A tick there costs 1.6–2.0 ms, of which **particle physics is 1.1–1.4 ms**.
- `view-direction-2026-10-02.md` measured 3.2 ms a tick with 3,700 particles: a
  5 ms p99 frame at 300 fps.

The average frame barely moves (particles are 0.3 ms a frame drawn); the tail does.

## Why it costs that

Every particle, every tick, runs `Particle.move`: it builds its bounding box
expanded by its velocity, asks the world for **every block collision box** in
that region (`World.getCollisionBoxes`, which allocates a list and a box per
solid block and walks chunk sections), then clips its motion against each, on
three axes. A crit spark lives a few ticks and moves fast, so its swept box is
large. Almost none of them ever hit anything.

## What could change it

1. **Skip the collision query when nothing is there.** If every block position
   the swept box touches is air (one block-state read per position, no list, no
   boxes), the result of the full query is "no boxes" and the motion is
   unchanged. That is output-identical: the same computation with a shortcut for
   the case where its answer is known. It needs the exact set of positions vanilla
   would have examined, including its one-block margin for tall boxes (fences).
2. **Reuse what the query allocates.** A pooled list and boxes for the query that
   still runs. Output-identical, less garbage.
3. **Coarser collision for short-lived particles** (only test the end position,
   or skip collisions for particles that live fewer than N ticks). **Not
   output-identical**: particles would pass through edges they used to stop at.
   Only as an opt-in "visual" setting, never by default, and labelled as
   changing what you see.
4. **Spread the tick.** Updating half the particles each tick changes motion
   smoothness. Rejected.

## What it must not change

- Where every particle is drawn, every frame: positions after `move` must be
  bit-identical for options 1 and 2. The gate is a per-particle position log,
  vanilla against optimized, over the same seeded scene for a few hundred ticks.
- Particles from other mods, and OptiFine's particle settings (its "Particles"
  and per-type toggles): a particle class Fathom does not recognise always takes
  the original path.
- Server-sent particle bursts arriving mid-tick: unaffected, since only the
  per-particle `move` changes.

## Proposed order

1. The position-log harness (vanilla vs optimized, same seed, same ticks).
2. Option 1, empty-space shortcut, for vanilla particle classes only.
3. Option 2 if allocation still shows in the profile.
4. Option 3 only if a user-facing visual setting is wanted, separately.

Ship gate: zero position differences over the log, and a paired improvement in
the p95 and p99 of `combat-particles`.
