# The in-game interface, measured

What Fathom's own renderer costs, taken from the client rather than estimated.
The decision it belongs to is in
[`docs/ingame-ui-renderer-decision.md`](../ingame-ui-renderer-decision.md).

---

## 1. Method

Every number here was read off the client's own instruments, which are the same
code the user can look at: **Diagnostics → Interface renderer**, and the four
meters at the top of **Diagnostics → Renderer spike**.

- **Frame time** is the interval between two Fathom frames — the whole game, not
  just the interface — measured with `System.nanoTime` over a ring of the last
  240 frames. Mean, 95th and 99th percentiles.
- **Fathom draw** is the time inside `Gfx.begin` … `Gfx.end`: taking the GL
  state, building the geometry, uploading it, and putting the state back.
- **Geometry** is the quads emitted and the draw calls made in the *last
  completed* frame.
- **Allocation** is the JVM heap's growth across one frame. Approximate by
  construction — a collection between two readings shows as zero, not as a
  negative — and reported as such on the screen that shows it.

Machine: Windows 11, 1280×720 window, Minecraft 1.8.9 through Legacy Fabric with
this module and nothing else. Interface scale 1.00×, i.e. one logical unit is
one device pixel. `run/options.txt` frame limit 260.

Because the harness that drives the client has to take the foreground to send a
keystroke, the game spends most of a scripted run *unfocused*, which is why the
frame-time figures below sit at the unfocused cap rather than at the frame limit.
That does not affect the Fathom-draw figures, which are the point.

---

## 2. What the renderer costs

> **These figures are from before the hub was rebuilt.** The renderer is the
> same code — the same batching, the same atlases, the same glyph path — but the
> hub it was measured drawing has since been replaced: a larger panel, more
> rows on screen, and a control set that draws more geometry per control. The
> per-frame cost of *this* hub has not been measured, because the machine it
> would have to be measured on was not to have a game window opened on it.
> Expect the quad count to be higher and the millisecond figure to be in the
> same place; do not quote the numbers below as current.

| | Hub (Mods) | Hub (Diagnostics) | Renderer spike |
|---|---|---|---|
| Fathom draw, mean | 0.56 ms | 0.84 ms | 1.04 ms |
| Fathom draw, p99 | 0.87 ms | 1.24 ms | 1.72 ms |
| Quads | 588 | 581 | 295 |
| Draw calls | 25 | 24 | — |
| Glyph pages cached | 7 | 5 | — |
| Icon rasters cached | 22 | 22 | — |
| Frame time, mean | 102.12 ms | 32.80 ms | 32.89 ms |
| Frame time, p95 / p99 | 101.61 / 104.72 ms | 34.79 / 35.03 ms | 34.89 / 34.97 ms |

**About a millisecond.** At 60 fps that is 6% of the frame budget for a menu
covering most of the screen; at 144 fps it is 15%, for a screen you only see
while you are looking at a menu. The spike costs the most because it is the
densest screen in the client — four meters, four paragraphs of type at four
sizes, six controls, a 24-row list and a panel of radius samples.

The two frame-time columns are the frame caps working, not the renderer:
**102 ms is the old 10 fps unfocused cap** (§4), and **32.8 ms is the new 30 fps
one**. With the window focused and the hub closed the same client runs at
**279–379 fps**, which is the answer to "does the frame rate come back when the
menu closes".

---

## 3. Closed, it costs nothing

Not "very little": nothing, by construction.

The renderer, the atlases and the animation clock are created the first time a
Fathom screen opens and are touched only from a Fathom screen's `render`. There
is no tick handler, no timer and no thread. With the hub closed the entire
interface layer is one field holding a reference and one null check when a
screen opens.

The one thing that does run while the hub is closed is the HUD — which is a
different feature, draws in the game's own font in the game's own pass, and is
measured separately in [`benchmarks.md`](benchmarks.md).

---

## 4. The frame caps, after this milestone

| Situation | Before | Now |
|---|---|---|
| Playing, focused | user's limit | user's limit |
| Vanilla screen over a world | user's limit | **user's limit** |
| A Fathom screen over a world | user's limit | **user's limit** |
| Fathom screen with no world | vanilla's hard-coded 30 | **user's limit** |
| Window unfocused | 10 | **30**, configurable 10/30/60/Unlimited |

Two changes matter and both came out of the report that "menus cap at
approximately 10 FPS":

1. **The 10 was the unfocused cap.** Focus is not a clean
   signal — a screenshot tool, an overlay, a notification or a window manager
   handing the foreground around all take it briefly — and at ten frames a
   second the client visibly stutters every time one does. Thirty is still most
   of the saving and it looks alive.
2. **The in-world menu cap was removed.** It made inventory, container and
   pause screens feel worse than gameplay and coupled interface latency to a
   power-saving decision. Those screens now retain the user cap. The only
   exception raises Fathom's interactive no-world screens out of vanilla's
   title-screen 30; the background artwork still updates at 30 Hz independently.

### What was not separated, and why

The spec asked whether the world behind an open menu could be throttled
independently of the interface in front of it — world at 30, interface at the
display's rate. **It could, and this milestone did not do it.** The honest
version of why:

1.8.9 renders the world and the interface into the same target in one pass:
`GameRenderer.render` calls `renderWorld`, and the colour and depth clear lives
*inside* `renderWorld`. Cancelling the world render therefore does not leave the
previous world frame on screen — it leaves the previous *whole* frame, interface
included, and drawing a translucent hub over that compounds it every frame into
a smear.

Doing it properly means owning a framebuffer: render the world into our own FBO
at the world rate, blit that texture at the interface rate, and draw the hub over
the blit. That is perhaps eighty lines and a real risk surface — FBO
availability, resizes, other mods' expectations about what is bound — for a
saving that only applies while a menu is open. It is written down here as the
next step rather than half-done now.

The interim position is defensible on its own terms: the interface the user is
touching runs at full speed, and the throttling applies to the menus that are
genuinely static.

---

## 5. Allocation

The spike reported **~1.0 MB per frame** when it was first measured. Most of that
was two lookups on the hottest paths building a `String` key per call — one per
string drawn, one per icon drawn. Both now scan a small array of ints instead:

- `FontBook.page` — 8 slots, covering every size and weight a screen uses.
- `Icons.entry` — 16 slots.

A third came out with the hub rebuild: the feature search lower-cased five
strings per feature on every frame somebody was typing, and now builds one
lower-cased haystack per feature, once.

What remains is the honest cost of a measurement screen: `String.format` for
every number it displays, and the `String` concatenation behind every label. The
hub's own screens do far less of it. This is worth revisiting if it ever shows up
as a pause; it has not, and a figure that includes the code printing the figure
is not a figure to optimise against blindly.

---

## 6. What is not measured here

- **GPU time.** There is no timer query on this path, so "Fathom draw" is CPU
  time: building and submitting the geometry. The draw itself is a few dozen
  textured quads and is not plausibly the bottleneck, but that is an argument
  rather than a measurement.
- **1440p and 4K.** Measured at 1280×720 only. The scale is linear in the
  framebuffer height and the glyph atlas is rebuilt per scale, so the expected
  change is a larger atlas and the same quad count; unverified.
- **The HUD editor and the HUD widgets**, which are a separate pass in the
  game's own font.
