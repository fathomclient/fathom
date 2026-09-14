# In-game UI on NanoVG — foundation

Status: **renderer layer built (§9); Fathom Hub and the custom main menu use it with the GL renderer as fallback.**
Phase dates: foundation and renderer layer, 2026-09-12.

This revisits [`ingame-ui-renderer-decision.md`](ingame-ui-renderer-decision.md), which
rejected NanoVG on 1.8.9 (§3.2, §3.5) and built the current custom GL renderer behind
the `Renderer` interface precisely so a NanoVG backend could be added later. The
project has now chosen to move to NanoVG. This document records what was verified,
what was built, and what the decision doc's objections look like with measurements.

---

## 1. Verified facts

Everything here was checked against the actual artefacts, not assumed.

| Fact | How it was verified |
|---|---|
| The game ships **LWJGL 2.9.4-nightly-20150209** | Gradle cache; no LWJGL 3 present anywhere |
| LWJGL 2 has **no NanoVG** | Package listing |
| LWJGL 3 **cannot share a classloader** with it | `lwjgl-3.3.6.jar` redefines `org.lwjgl.BufferUtils` and `org.lwjgl.PointerBuffer`; `lwjgl-opengl` redefines the `org.lwjgl.opengl.GL*` classes |
| NanoVG's GL backend **requires LWJGL 3's OpenGL module** | `NanoVGGLConfig` bytecode: `Class.forName("org.lwjgl.opengl.GL")` → `getCapabilities()` → `getFunctionProvider()` |
| LWJGL **3.3.6 and 3.4.3 are both Java 8** | Every base class in `lwjgl`, `lwjgl-nanovg` scanned: class-file major 52 |
| **No stencil buffer** in 1.8.9 | `MinecraftClient` creates the display with `PixelFormat.withDepthBits` only; `Framebuffer`'s renderbuffer is `GL_DEPTH_COMPONENT24` (33190) |
| Natives, all 7 platform targets | **2.7 MB** compressed (`lwjgl` + `lwjgl-opengl` + `lwjgl-nanovg`, 3.3.6) — not the ~15 MB the earlier doc estimated |
| Natives on disk, Windows x64 | `lwjgl.dll` 482 KB, `lwjgl_nanovg.dll` 516 KB, `lwjgl_opengl.dll` 352 KB |
| NanoVG initialises on the game's context | Spike log: `NanoVG (LWJGL 3.3.6+1, GL2 backend) on 4.6.0 NVIDIA 616.92` |
| GL state survives a NanoVG frame | Raw-driver fingerprint before and after: identical, at 1280×720 and 2560×1440 |
| Nothing leaks into the shipped jar | `build/libs/*.jar`: 0 `org/lwjgl`, 0 backend, 0 native entries |

---

## 2. Architecture

```
    Fathom UI code (menu, hub sections, HUD editor, HUD widgets set to Fathom font)
        |
    Layout / Animation       core/ui/layout, core/ui/anim        (unchanged)
        |
    Renderer                 core/ui/render/Renderer             (unchanged; the seam)
    FrameRenderer            core/ui/render/FrameRenderer        (new: + beginFrame/endFrame)
        |
    +-- GlRenderer           fabric/gfx          compatibility fallback
    +-- NanoVgRenderer       src/nanovg          preferred screen backend through Canvas
        |
    OpenGL context created by LWJGL 2 / Minecraft's framebuffer
```

`FrameRenderer` is the "FathomRenderer" abstraction: interface code keeps drawing
through `Renderer`; only the code that opens a frame sees `FrameRenderer`. It lives in
`core` because it is the one type both sides of the classloader boundary must agree on.

---

## 3. Integration

- **Build.** A `lwjgl3` configuration (never on the game classpath) and a `nanovg`
  source set compiled against LWJGL 3 and `core` only. `writeNanoVgClasspath` lists
  both for the development client. Normal builds never resolve LWJGL 3.
- **Runtime.** `fabric/gfx/nanovg/NanoVg` builds a `URLClassLoader` whose parent is
  Fathom's loader, **child-first for `org.lwjgl.` and `net.fathom.optimizations.nanovg.`**
  and parent-first for everything else. The backend is cast back to the game's
  `FrameRenderer`; fonts resolve from the mod.
- **Natives.** Extracted by LWJGL into `<gameDir>/.fathom/natives/lwjgl3`, not the
  system temp folder.
- **Context.** `GL.createCapabilities()` on the render thread against the current
  LWJGL 2 context, then `NanoVGGL2.nvgCreate(NVG_ANTIALIAS)`. GL2 backend, because the
  context is a compatibility context and GL2 needs no VAOs or GLSL 150.

Run it: `gradle runClient -PcaptureUi=nanovg` (add `-PclientWidth/-PclientHeight` to
change the framebuffer).

---

## 4. GL state

`GlStateSnapshot` restores through `GlStateManager`'s setters, which skip when the
cache already matches. That is correct for `GlRenderer`, which only changes state
through `GlStateManager`. NanoVG changes blend, cull, depth, scissor, stencil,
program, texture and buffer bindings and unpack alignment **directly**, so the cache
and the driver would disagree and later `enableBlend()` calls would be skipped.

`NanoVgGlGuard` therefore:

1. takes a `GlStateSnapshot` (matrices, framebuffer, program, textures);
2. `glPushAttrib(GL_ALL_ATTRIB_BITS)` + `glPushClientAttrib(GL_ALL_CLIENT_ATTRIB_BITS)`;
3. saves the program and array/element buffer bindings, which the attribute stack
   does not cover;
4. sets the viewport to the framebuffer;
5. after `nvgEndFrame`: restores 3, pops 2, then restores 1 — whose `GlStateManager`
   calls are now no-ops that confirm the driver matches the cache.

`NanoVgGlGuard.fingerprint()` is the evidence: raw driver state before and after.

---

## 5. Coordinates and scaling

- `nvgBeginFrame(vg, framebufferW / scale, framebufferH / scale, scale)`, with `scale`
  from the existing `UiScale` (framebuffer height / 720, clamped, × user preference).
- Everything is authored in logical units, exactly as with `GlRenderer`; NanoVG
  tessellates and rasterises glyphs at `scale`, so there is no draw-small-and-magnify.
- Minecraft's integer GUI scale and scaled resolution are **not used**. NanoVG's GL2
  backend projects from its own `viewSize` uniform, so the game's projection matrix is
  irrelevant to it.
- Fullscreen/windowed and resizes: the frame reads `client.width/height` every frame.
- HiDPI: 1.8.9's LWJGL 2 display is not Retina-aware, so framebuffer = window pixels
  on macOS; if that ever changes, `scale` absorbs it because it derives from the
  framebuffer, not the window.

Verified at 1280×720 (scale 1.0) and 2560×1440 (scale 2.0): same logical layout,
glyphs at device resolution, no scaling artefacts.

---

## 6. Rendering rules

| Concern | Approach |
|---|---|
| Fonts | The mod's Geist Regular/Medium/SemiBold via `nvgCreateFontMem` (buffers kept alive). NanoVG's fontstash rasterises at size × scale. Minecraft's bitmap font stays for vanilla and for HUD widgets set to it. |
| Clipping | `nvgScissor` under an identity transform with rectangles already transformed; our own intersecting stack, so clip/alpha/transform pushes may interleave as `Renderer` permits. |
| Alpha | Product stack → `nvgGlobalAlpha`. Colours are straight-alpha `NVGColor`. |
| Gradients | `nvgLinearGradient` on a rounded rect. |
| Shadows | One `nvgBoxGradient`. Cut out under the shape without a stencil by filling the ring as convex quads (§9). |
| Rounded corners | `nvgRoundedRect`; outlines stroked on a rect inset by half the thickness so a bordered box is its stated size. |
| Images | `ImageRef` + `NanoVgImages` (§9). |
| Icons | Shared vector path geometry, stroked by the active backend. |

### Frame lifecycle

`guard.open` → `beginFrame` → draw → `endFrame` (or `cancelFrame` on failure) →
`guard.close`, all inside `Screen.render` after the world and vanilla background, on
the render thread. A NanoVG frame must never be open at the same time as a
`GlRenderer` frame; if a screen mixes them, the first must end (and flush) before the
second begins.

---

## 7. Risks

1. **Distribution.** Natives must ship for every platform and be extracted at runtime.
   For a privacy-first client that needs checksums, a visible extraction location,
   and a clear failure path — not yet built.
2. **No stencil buffer.** NanoVG fills anything other than a single convex path
   through the stencil. Rectangles, rounded rectangles and text are fine; concave
   icon fills, holes and `NVG_STENCIL_STROKES` are not. Options: triangulate on our
   side, or render UI into our own FBO with a `DEPTH24_STENCIL8` attachment and
   composite it.
3. **Translucent panels over shadows** show the shadow through (no hole). Visible only
   on panels below ~90% opacity.
4. **Driver coverage.** Verified on one NVIDIA 4.6 driver on Windows. Intel/AMD,
   macOS (legacy 2.1 context) and Linux Mesa are untested. `GL.createCapabilities` on
   an old Intel driver is the most likely failure.
5. **Antivirus.** Freshly extracted unsigned DLLs are the kind of file that got a test
   binary flagged by Malwarebytes on this machine earlier.
6. **Two bindings, one context.** A native crash inside NanoVG takes the game down with
   no Java stack. Needs a kill switch and a fallback to `GlRenderer`.
7. **Mixing backends in one frame** remains forbidden. `Gfx.beginScreen` selects one
   backend for the complete screen frame and `Gfx.endScreen` closes that same backend.
8. **CI.** `check` compiles the isolated `nanovg` source set as well as the ordinary
   game source set.

---

## 8. Remaining hardening

The renderer, images, icons, fallback selection, test compilation, and production
screen integration are complete. Remaining work is platform coverage and distribution:
package the native set with checksums, validate Intel/AMD/macOS/Mesa drivers, and decide
whether any future concave artwork warrants a stencil-backed FBO. None of those items
changes the current rounded UI paths.

---

## 9. Renderer layer (phase 2)

```
Fathom UI -> layout / animation -> FathomRenderer (core) -> NanoVgRenderer (src/nanovg) -> OpenGL
                                         ^
                        fabric/gfx/Canvas opens every frame
```

| Piece | Where | Role |
|---|---|---|
| `FathomRenderer` | core/ui/render | Public API: `Renderer` + per-corner rounds, circle, ring, line, paths, paints, horizontal gradient, `Shadow`s, glow, aligned/fitted text, bounds, scissor push/replace/reset, images |
| `Paint`, `Shadow`, `ImageRef`, `TextAlign`, `FontFaces` | core/ui/render | Value types shared across the classloader boundary |
| `CanvasState` | core/ui/render | Clip/alpha/transform stacks, culling, imbalance counting; unit-tested |
| `FrameScale` | core/ui/render | Pixel ratio: `FATHOM` (UiScale) or `MINECRAFT` (GUI scale factor) |
| `Canvas` | fabric/gfx | The only frame opener: framebuffer size, scale, `NanoVgGlGuard`, exclusivity, failure handling, first-frame crash marker |
| `NanoVgRenderer`, `NanoVgFonts`, `NanoVgImages` | src/nanovg | Backend, fonts once per context with weight fallback, async-decoded LRU-by-idle image cache |
| `RendererShowcaseScreen` | fabric/gfx/nanovg | Development only: `-PcaptureUi=nanovg` (capture) / `nanovg-live` |

**Stencil-free cutout shadows.** The ring between the shape and a concentric larger
rounded rectangle is sampled at matching angles; each pair of neighbours bounds a convex
quad, filled with shape anti-aliasing off so shared edges are watertight. 4×(n+1) fills
per shadow, n ≤ 10. Verified seam-free at 2× with a 5× levels stretch.

**Measured** (RTX 4060, the full showcase: ~250 primitives, 11 cutout shadows, 3 images):
0.97–1.05 ms per frame including the GL guard, at 1280×720 and 2560×1440. GL
fingerprint identical before/after at both. `gradle check` now compiles `src/nanovg`.

### Production integration notes

1. **Gfx/Canvas mixing**: a screen uses one backend for an entire frame; initialization
   failure falls back to `GlRenderer` before component drawing starts.
2. **Packaging** natives (risk 1), with checksums and a visible extraction path.
3. **Driver matrix**: Intel/AMD/macOS/Mesa (risk 4).
4. **Text-fit caching**: truncated strings call `nvgTextGlyphPositions`; ordinary
   measurements are bounded and cached.
5. **Cutout shadow cost** on screens with many translucent cards: keep card glow to
   the single-pass box-gradient helpers documented in `ingame-ui-effects.md`.
