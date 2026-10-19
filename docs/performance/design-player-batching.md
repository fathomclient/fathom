# Design: drawing players in batches

Status: **design, not built.** The largest multiplayer cost Fathom does not yet
touch. This document is the gate before any code: what it would change, what it
must not change, how it would be proved, and why it is not small.

## The cost

From `multiplayer-2026-10-13.md`, microseconds per player drawn (OptiFine M5,
RTX 4060):

| | Lobby (compass in hand) | Combat (iron, half enchanted) |
|---|---:|---:|
| Whole player | 26.5 | 54–62 |
| Body model + skin | 10.2 | 10–11 |
| Feature layers | 7.0 | 33–38 |
| — armour (+ glint) | 0.1 | 19–22 |
| — held item | 5.3 | 10–12 |
| Label decision + label | 6.9 | 7.5 |

Forty players are 1.0–1.1 ms; twenty armoured fighters 1.2 ms. That is most of
the gap between a 1,500 fps singleplayer frame and a 450 fps lobby frame.

## Why it costs that

Not arithmetic. Each player is drawn in immediate style, part by part: for every
box of the model, `ModelPart.render` either compiles or calls a display list
(vanilla compiles each part once), between matrix pushes, rotations and
translations that are recomputed per player and per part; then each feature
layer binds its own texture, sets colour and lighting, and draws again; armour
adds a second pass for its glint with blend and texture-matrix changes. The
driver is the bottleneck: a few hundred GL calls per player, each cheap, many of
them state changes that force validation. The `drain` arm shows the GPU itself
is not the limit (0.8–1.0 ms queued at frame end in these scenes, against a
1.9–2.7 ms CPU frame).

## What batching would mean

Group the work across players instead of finishing one player before the next:

1. **Per texture, not per player.** Every player with the default skin, every
   iron chestplate, every diamond sword shares a texture. Draw all bodies for one
   skin, then all chestplates, then all glints, binding each texture once.
2. **Matrices as data.** Compute each part's final matrix on the CPU (the same
   rotations `ModelPart` applies) and submit transformed vertices, or per-instance
   matrices, instead of `glPushMatrix`/`glRotatef` per part.
3. **One upload per batch.** A single vertex buffer per batch, streamed per frame.

The expected saving is a large fraction of the 26–60 µs per player: the
geometry is a few hundred vertices; it is the call count that costs.

## What it must not change

- **Pixels.** Draw order matters wherever blending does: the armour glint, the
  translucent hat layer, capes, the label. Opaque parts can be reordered freely;
  translucent ones only within their own pass, back to front as vanilla's entity
  order happens to give them. The gate is a pixel comparison of whole frames in
  the `lobby`, `combat` and `nametags` scenes, batched against unbatched, with
  the camera held and players frozen for the comparison frames.
- **Animation.** Limb swing, sneaking, hurt tint, the hurt-time red overlay,
  invisibility, spectators, sleeping players, riding, the elytra-less 1.8
  arm poses. Every input `PlayerEntityRenderer` reads must reach the batch.
- **Other mods and OptiFine.** OptiFine's Custom Entity Models, random entity
  textures, emissive textures, dynamic lights and shaders all hook player drawing.
  Batching must step aside whenever any of them is active for that player (as
  Fathom's existing fast paths step aside under shaders), and the step-aside has
  to be decided per player, cheaply.
- **Skins and capes loading late.** A skin that finishes downloading mid-frame
  moves a player from one texture batch to another; never a stale texture.

## Why it is not small

It replaces `PlayerEntityRenderer` and its feature layers for the common case,
while keeping them for everything else, against OptiFine's patched versions of
those classes. The correctness surface is every pose and every layer. A partial
version that batches only the body (10 µs of 26) is possible first and would
prove the pixel-comparison harness.

## Proposed order

1. Pixel-comparison harness for whole frames with frozen players (needed anyway).
2. Body-only batching for players with no OptiFine entity features active.
3. Armour and held items.
4. Glint and translucent layers, last, because ordering is hardest there.

Each step ships only with zero differing pixels in the gate scenes and a paired
win in the multiplayer profile.
