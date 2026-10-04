# The in-game UI renderer — original evaluation and decision

> Historical document. The current architecture and measurements are in
> [`ingame-ui-compositor.md`](ingame-ui-compositor.md).

> **Being revisited (2026-09-12).** The project has chosen to move the in-game UI to
> NanoVG. The foundation — an isolated LWJGL 3 backend behind the `Renderer` seam this
> document created — is proven but not yet used by any production screen. Measurements
> that update §3.2 and §3.5 (native size, stencil buffer, GL state) are in
> [`ingame-ui-nanovg.md`](ingame-ui-nanovg.md). The analysis below is kept as written.

**Outcome first: Fathom draws its in-game interface with its own GPU renderer —
batched geometry through Minecraft's own vertex pipeline, glyphs rasterised from the
launcher's Geist at device resolution, anti-aliased corners and edges from a coverage
texture. No native libraries, no second GL binding, no browser.**

The renderer sits behind a `Renderer` interface with one implementation today, so a
NanoVG backend can be added later for a version of the game that already ships
LWJGL 3 — without any of the code above it changing.

This document is the research the brief asked for: eight candidates, the criteria they
were judged against, why seven were rejected, and what the surviving one had to prove.

---

## 1. Why the old renderer had to go, precisely

The hub before this milestone was not badly drawn. It was drawn in the wrong
coordinate system, and every visible defect follows from that one decision.

Minecraft hands a `Screen` a projection matrix in **scaled resolution** units. At GUI
scale 3 on a 1280×720 window, that space is 427×240. Everything the old renderer did —
every rectangle, every glyph, every corner — was quantised to that 427×240 grid and
then magnified 3× by the projection. The interface was, quite literally, drawn at 240
lines and blown up.

| Symptom the brief lists | What actually caused it |
|---|---|
| Extremely pixelated typography | 3× magnification of a 240-line raster |
| Minecraft bitmap font | `TextRenderer`, a 1-bit atlas designed for Minecraft 1.0 |
| Oversized text | That font's 8px cell magnified to 24 real pixels |
| Text clipping | Integer `getStringWidth` for a font that was then scaled |
| Inconsistent scaling | Minecraft's `scaleFactor` is an integer: 1, 2, 3, 4 and nothing between |
| Fixed/small content pane | Panel sized in a coordinate space only 240 units tall |
| Aliased corners | Corners built from integer row spans, in a space that is then magnified |
| Delayed hover and click | Screen input is drained once per **tick** — 20 Hz, up to 50 ms late |
| Laggy animation | 20 Hz input, against animation that only looks smooth on the frame clock |
| Corrupt quadrants on theme change | A texture bound underneath geometry already queued against another one |
| Menus at ~10 fps | `UNFOCUSED_CAP` of 10, which applies whenever the window loses focus |

None of that is fixable by polishing. A renderer whose smallest addressable unit is
three physical pixels cannot draw a 14px UI font, and no amount of care with quads
changes that.

---

## 2. The criteria

Sixteen, from the brief, grouped by what they actually decide.

**Can it look right** — visual quality, anti-aliased fonts, rounded rectangles,
clipping, animation.
**Does it feel right** — input latency, and whether hover can be decoupled from the
game's tick.
**What does it cost** — CPU, GPU, memory, allocation pressure per frame.
**Can we ship it** — native dependencies, loader compatibility, 1.8.9 stability,
Java 8, licence, distribution size and complexity.
**Where does it leave us** — portability to 1.16+/LWJGL 3 when Fathom supports more
than one version.

---

## 3. The eight candidates

### 3.1 Custom OpenGL 2D renderer — **chosen**

Our own batched 2D renderer, drawing through the GL context the game already has.

1.8.9 ships LWJGL 2.9.4, whose bindings run to `GL30` and include `GLContext`
capability queries, so this is not the fixed-function-only environment it is often
assumed to be. It is also not necessary to use them: everything Fathom's interface
needs — anti-aliased text, rounded rectangles, borders, gradients, icons, clipping,
opacity, transforms — is reachable with textured, vertex-coloured triangles, which is
what `Tessellator`/`BufferBuilder` already batches and uploads in one call.

The two things people assume require shaders, and how they are done without:

- **Text.** Glyphs are rasterised by `java.awt.Font` with anti-aliasing and fractional
  metrics into a texture atlas **at the exact device pixel size they will be drawn at**,
  and cached. Not scaled up from a small raster, which is the defect being fixed. When
  the effective scale changes the atlas is rebuilt — an event that happens on window
  resize, not per frame.
- **Rounded corners and hairlines.** One 128×128 texture holding a quarter disc of
  coverage values, sampled bilinearly. A corner of any radius is that texture on a
  quad; a border is two of them; an edge that lands between pixels gets a real coverage
  value rather than a hard step.

| | |
|---|---|
| Visual quality | High. Real AA on text and shapes, fractional positioning, no upscale |
| Fonts | Geist — the launcher's own — at device resolution |
| Input latency | One frame; the renderer polls the mouse itself (§6) |
| CPU | One `BufferBuilder` fill per batch; no per-frame allocation after warm-up |
| GPU | Tens of draw calls for the whole hub; one texture bind per atlas |
| Memory | ~2 MB of atlases at 1440p |
| Native dependencies | **None** |
| Loader compatibility | Legacy Fabric, and anything else — it is ordinary Java |
| 1.8.9 stability | Uses the game's own vertex pipeline and `GlStateManager` |
| Java 8 | Yes, `java.awt.Font` included |
| Licence | Ours |
| Distribution | +98 KB of font, +0 native bytes |
| Portability | The `Renderer` interface is the port; the backend is ~700 lines |

The honest costs: we own the bugs, there is no SVG or CSS to lean on, and text is
rasterised per size rather than scaled freely from a distance field. §8 says what would
change that.

### 3.2 NanoVG (on LWJGL 3, alongside the game's LWJGL 2)

The right answer for a game that already ships LWJGL 3. On 1.8.9 it is not available
without also solving 3.5, because LWJGL **2** has no NanoVG bindings at all — NanoVG
arrived with LWJGL 3. So "use NanoVG" on this version is exactly "ship a second,
isolated GL binding and its natives", which is a distribution and stability question
before it is a rendering one.

Quality would be excellent: a mature vector rasteriser with proper AA, path filling and
font rendering. Cost is per-platform natives (Windows, macOS x64 + arm64, Linux x64 +
arm64) and a second capability table bound to the same context. Licence is zlib, which
is fine.

**Rejected for 1.8.9; kept as the second backend** if Fathom targets a version where
LWJGL 3 is already loaded.

### 3.3 PolyUI (Polyfrost)

A genuinely good declarative UI framework for the JVM: Kotlin, rendering through
NanoVG. Its architecture is close to the target architecture in the brief, which is no
coincidence — a UI tree, a layout pass, an animation system and a swappable renderer is
the shape this problem has.

Rejected as a dependency for three reasons, in order of weight:

1. It inherits 3.2 — it needs NanoVG, therefore LWJGL 3 on 1.8.9.
2. **LGPL-3.0** (plus an attribution request). Not a blocker in principle, but it puts
   relinking obligations on a shipped launcher artefact, and this repository has kept
   its dependencies to licences that ask nothing of the distribution.
3. The Kotlin runtime, for a mod whose entire jar is 463 KB.

Its *architecture* is another matter, and the brief explicitly permits taking the
concepts. The layering in §5 is that shape. No PolyUI source was read while writing
this renderer; the pattern is general and pre-dates it.

### 3.4 Elementa (Essential)

The most widely deployed option on 1.8.9 and the most battle-tested: a constraint-based
component tree drawing through Minecraft's own pipeline, so no natives. Rejected on:

- **Licence.** LGPL-3.0, with the same distribution consequence as 3.3.
- **Coupling.** In practice it arrives with Essential's UniversalCraft compatibility
  layer — a second abstraction of the game to build on.
- **Typography.** Its default text is still Minecraft's font; good text is an add-on.
  The single most important requirement in this milestone would be the part we control
  least.
- Kotlin again.

It would have been a defensible choice. The deciding factor is that what it saves us —
a component tree and a layout pass — is the *cheap* part of this problem, and what it
does not save us is the expensive part and the reason the milestone exists.

### 3.5 Isolated LWJGL 3 + NanoVG

Ship LWJGL 3 relocated into our own package namespace so it cannot collide with the
game's LWJGL 2, extract its natives, and create a second set of GL capabilities on the
same context. OneConfig does this, so it demonstrably works.

It also means ~15 MB of natives across five platform targets, native extraction on
first run (a supply-chain surface a privacy-first launcher has to justify), two
function-pointer tables against one context, and a class of bug — a crash inside a
relocated native on somebody's Intel driver — that we would be very poorly placed to
debug remotely.

**Rejected**: the cost is real, and the benefit over 3.1 for the interface Fathom
actually draws is anti-aliased *arbitrary paths*. Fathom's interface is rectangles,
rounded rectangles, lines, text and icons.

### 3.6 legacy-lwjgl3

Replaces the game's LWJGL 2 with LWJGL 3 wholesale on legacy versions. Solves 3.2
elegantly and creates a far larger problem: input, display, audio and every mod that
touches `org.lwjgl.input` now run on a compatibility shim. **Rejected** — that is a
change to the platform, made for a menu.

### 3.7 MCEF / JCEF / Chromium

A real browser, into a texture. It would give us the launcher's exact CSS, which is the
seductive part.

Against: 150–250 MB of natives per platform, a second process, 200–400 MB resident for
a menu, an IPC hop on every input event, GPU compositing competing for the same
context, and a browser engine's patch cadence inside a game client. For a launcher
whose `PRIVACY.md` opens with what it does *not* do, embedding Chromium is also a large
new statement about what runs on the user's machine.

**Rejected.** The brief's warning was right: the launcher using WebView2 is an argument
for a shared *design system*, not a shared renderer.

### 3.8 Ultralight

HTML/CSS at a fraction of Chromium's weight, and genuinely impressive.

**Rejected on licence before anything else.** Closed-source and commercially licensed —
free only below a revenue threshold, under terms that can change, for a project that
intends to be distributed and auditable. A closed native blob in a launcher whose
threat model is written down is not a trade we should make. The technical case (native
deps, a GPU driver you implement yourself) only adds to it.

---

## 4. The comparison, in one table

Scored for **this** problem: a 1.8.9 client menu, shipped by a privacy-first launcher.

| | Text quality | AA shapes | Latency | Native deps | 1.8.9 risk | Java 8 | Licence | Ship size | Portability |
|---|---|---|---|---|---|---|---|---|---|
| **Custom GL (chosen)** | High | High | 1 frame | none | low | yes | ours | +98 KB | high (interface) |
| NanoVG (LWJGL 3) | Very high | Very high | 1 frame | yes | med | yes | zlib | +15 MB | high |
| PolyUI | Very high | Very high | 1 frame | yes | med | Kotlin | LGPL-3 | +17 MB | high |
| Elementa | Medium | Medium | tick | none | low | Kotlin | LGPL-3 | +3 MB | medium |
| Isolated LWJGL 3 | Very high | Very high | 1 frame | yes | med-high | yes | BSD+zlib | +15 MB | high |
| legacy-lwjgl3 | n/a | n/a | n/a | yes | **high** | yes | MIT | +15 MB | n/a |
| MCEF/Chromium | Very high | Very high | 2+ frames | yes | med | yes | BSD-ish | +200 MB | medium |
| Ultralight | Very high | Very high | 2 frames | yes | med | yes | **proprietary** | +40 MB | medium |

---

## 5. The architecture that follows

Exactly the layering the brief asked for, split so that everything above the last line
is testable without a game.

```
    Fathom UI code (hub sections, HUD editor)
        |
    Layout         core/ui/layout  - logical units, constraints, ellipsis
        |
    Animation      core/ui/anim    - monotonic clock, deltaSeconds, easing
        |
    2D render API  core/ui/render  - Renderer: rects, text, icons, clip,
        |                            transform, opacity
    GL backend     fabric/gfx      - batching, atlases, scissor   <- only this
        |                            part needs Minecraft
    Minecraft's framebuffer
```

`core/ui/**` has no Minecraft imports, so geometry, text fitting, scale arithmetic and
the animation clock are covered by the standalone test runners. The backend under
`fabric/gfx` is the only part that needs a window.

**Coordinate system.** The interface is authored in **logical units (dp)**. Device
pixels come from the actual framebuffer:

```
    scale  = clamp(framebufferHeight / 720, 1.0, 3.0) x userPreference
    device = logical x scale        // fractional; rounded only where a pixel edge matters
```

Minecraft's own `scaleFactor` is used for exactly one thing: undoing it. The screen's
projection is left alone and a single modelview scale of
`scaledWidth / framebufferWidth` converts the game's space into device pixels for the
duration of our draw. Nothing is rasterised small and magnified, which is the whole of
§1.

---

## 6. Input

Screen input in 1.8.9 arrives on the **tick**: `MinecraftClient.tick` calls
`Screen.handleInput`, which drains one LWJGL queue at 20 Hz. Hover computed there is up
to 50 ms stale and every click is quantised to a tick boundary. That is the "delayed
hover" and "delayed button feedback" in the brief, and it is not fixable inside the
vanilla callbacks.

So Fathom's screens do not use them. Mouse position and button state are **polled once
per rendered frame** from `Mouse.getX/getY/isButtonDown`, which LWJGL updates on every
`Display.update`, and press/release are edge-detected from that. Hover, hover
transitions, drags and clicks now run at frame rate — and a button already held when a
screen opens can no longer be seen as a press, because that edge never happened.

Keyboard stays on the event queue: character input needs the queue's translation, and
50 ms on a keystroke is not what anyone means by input lag.

---

## 7. What the spike had to prove

A comparison screen — Diagnostics → **Renderer spike** — drawing the exact list from
the brief: the launcher's font at 12/14/18 dp, an icon, a rounded dark panel, a red
button, a toggle, a dropdown, a scrollable area, a hover transition and an open/close
transition. It reports live: frame time (mean, p95, p99), the renderer's own draw cost,
batch and draw-call counts, and bytes allocated per frame.

Measurements and method are in [`docs/performance/ingame-ui.md`](performance/ingame-ui.md).
The short version: **the renderer costs about a millisecond a frame** — 0.56 ms mean on
the hub's Mods page and 1.04 ms on the spike, which is the densest screen in the client
— for 300 to 600 quads in around 25 draw calls. Closed, it costs nothing at all, by
construction rather than by measurement: nothing ticks it.

---

## 7a. What was built on it

The renderer was approved and the hub was then rebuilt on top of it rather than
adapted to it. What changed, and why each thing changed:

| | Before | Now |
|---|---|---|
| Panel size | fixed 1020×660 logical | 88% × 82% of the screen, to 1240×780 |
| Background | blurred photograph, five themes, a picker | near-black panel over a world darkened to 18% |
| Type | one bitmap size | 11/12/13/14/19/22, three weights |
| Controls | drawn by hand per call site | eleven named controls, five states each |
| Hit-testing | a second copy of the layout arithmetic | the same rectangle that drew it |
| Section API | `render` **and** `click`, computing the same boxes twice | one `render` |
| Close | `setScreen(null)` | animated, like the open |
| Session name | "Unknown profile" for every case but one | the launcher's profile, "Development Profile", or "External Session" |
| Interface size | followed the framebuffer only | **Fathom UI Scale**: Auto, 90%, 100%, 110%, 125% |

**The themes are gone on purpose.** A menu inside a game is already sitting on
the most decorated background it will ever have. A second decorative layer —
a blurred photograph behind the content — competes with the type for no benefit,
and the corrupt magenta quadrant in the report was the poster texture failing to
be what the batch expected. There is nothing to corrupt now; the background is
`#08090C` at 94% and the world behind it is at 18%.

**Immediate mode is the structural change.** A control now draws itself and
answers whether it was used, from one rectangle:

```java
    if (controls.primary(ui, "hud.edit", editRect, "Edit HUD", true)) {
        context.editHud();
    }
```

The version this replaces had a `render` method that drew from a rectangle and a
`click` method that hit-tested a rectangle computed again by hand. Every
divergence between the two was a control that lit up in one place and responded
in another; the sections were full of them, and none of them could be caught by
anything but looking.

---

## 8. What this decision does not close

- **SDF/MSDF text.** The current atlas is a device-resolution raster per size, which is
  the highest-quality option at a fixed scale and is rebuilt when the scale changes. An
  MSDF atlas plus a GLSL 1.20 fragment shader would make arbitrary scaling free and
  animated text scaling smooth. `GL20` is available, so that is an upgrade behind the
  same interface rather than a redesign — gated on a fallback for drivers where shader
  compilation fails.
- **A NanoVG backend**, for versions that already ship LWJGL 3 (§3.2).
- **The HUD widgets** still draw in Minecraft's font, deliberately: they sit *in* the
  game beside the hotbar and the chat, and matching the game there is right. The hub is
  Fathom's own surface and matches Fathom.
- **Throttling the world behind a menu independently of the menu in front of it.** 1.8.9
  clears and draws both in one pass, so this needs the world rendered into a framebuffer
  of our own and blitted. `docs/performance/ingame-ui.md` §4 says what that would take
  and why it was not done in this milestone.
