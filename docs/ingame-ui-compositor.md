# Fathom in-game UI compositor

Status: **implemented, migrated, visually verified and profiled**  
Completed: 2026-10-04

This report closes the renderer replacement requested after the in-game interface
failed to match the launcher. The result is not another styling pass: production
screens and the live HUD now use a retained layout tree feeding a layer-sorted,
batched GLSL 1.20 compositor with signed-distance shapes, an offline MSDF font
atlas and framebuffer blur.

## 1. Old rendering architecture

The shipped path was `GlRenderer`, a custom immediate-mode renderer on Minecraft
1.8.9's LWJGL 2.9.4 OpenGL compatibility context.

| Concern | Previous implementation |
|---|---|
| Drawing | Components computed rectangles and immediately appended geometry to a texture batch. Texture changes and intervening game draws split batches. |
| Rounded shapes | Rectangular spans plus four pre-rasterised corner masks from `Atlas`; borders repeated the same construction as a ring. Scaling and mask sampling made curvature and border weight inconsistent. |
| Text | Geist was loaded once through Java2D. `GlyphPage` was created for each face/size/scale and lazily rasterised each glyph at four subpixel phases. Glyph objects and advances were cached, but strings were measured repeatedly by immediate layouts. JDK 8 shaping did not reproduce Geist's GPOS kerning and ligatures. |
| Clipping | A nested CPU rectangle stack drove integer `glScissor` calls. It was balanced at frame end, but ordering still depended on immediate draw order. |
| Glass | One framebuffer copy plus the texture mip chain approximated blur. It could not reproduce the launcher's Gaussian blur, saturation and brightness. |
| Layers | Function-call order. Popovers and tooltips had to be drawn late manually. |
| Layout | Screens and sections independently constructed `Rect` values every frame. Drawing and hit testing could duplicate the same arithmetic. |
| Animation | A monotonic clock existed, but components used several timings and state paths; some interaction arrived through Minecraft's 20 Hz screen input callbacks. |
| GUI scale | Logical units were converted from the real framebuffer through `UiScale`; this useful convention was retained. |
| Framebuffer/state | `GlStateSnapshot` captured and restored Minecraft's GL state. The old frost path copied the current framebuffer into one texture. |

The baseline scripted RShift Hub capture at 1920×1080, Fathom scale 1, recorded
91 draw calls, 3,034 quads, 0.69 ms mean UI CPU, 0.19 ms mean GPU and about
17 KB allocated per frame. The main menu recorded 29 calls, about 1,600 quads,
0.43 ms UI CPU and about 7 KB allocated per frame.

## 2. Repository-history audit

The previous replacement experiment was NanoVG. It used an isolated child-first
classloader because Minecraft 1.8.9 ships LWJGL 2 while NanoVG's Java binding is
part of LWJGL 3. The experiment successfully opened NanoVG's GL2 backend on the
existing context and preserved driver state through a dedicated guard.

It was not the production renderer: normal launcher builds never supplied the
isolated classpath or native libraries. Shipping it would have meant maintaining
two conflicting LWJGL bindings, extracting native binaries, accepting a native
driver crash surface and handling NanoVG's stencil assumptions on a Minecraft
framebuffer without stencil. It also provided a much larger path API than Fathom
uses.

Useful ideas were recovered rather than the implementation: a renderer-neutral
API, framebuffer-derived logical coordinates, explicit state restoration and a
representative proof screen. The retired NanoVG source set, LWJGL 3 dependencies,
classloader, state guard and showcase screen have now been removed. The historical
investigation remains in `docs/ingame-ui-nanovg.md`.

`GlRenderer` remains only as a compatibility fallback when GLSL 1.20 or framebuffer
objects are unavailable. No production Fathom screen or HUD deliberately selects it.
`-Dfathom.forceGlRenderer=true` remains available for A/B diagnostics.

## 3. New rendering architecture

```
screen / HUD components
        |
retained Box tree + TextLeaf metrics + frame-polled input + Motion
        |
Renderer API: primitives, clips, transforms, opacity, explicit Layer
        |
LayerRecorder: reusable per-layer arrays and texture-compatible draw runs
        |
SdfRenderer: one GLSL 1.20 program + one VBO upload
        |                         |
SDF shapes / MSDF text           SdfBackdrop framebuffer blur
        |                         |
Minecraft framebuffer on the existing LWJGL 2 OpenGL context
```

The compositor is lazy. With no Fathom UI open it performs no layout, animation,
blur, atlas work, uploads or draws. GPU programs, VBOs and atlases are created on
first use and recreated only after a resource reload.

## 4. Shape rendering

Every rounded box, pill, circle, border, inset rim, shadow and simple gradient is
recorded as one quad. Each vertex carries the logical box, four corner radii,
border parameters, colours, clip rectangle and primitive kind. The fragment shader
evaluates a signed distance to the mathematical shape and converts distance to
anti-aliased coverage at device resolution.

This gives one continuous quarter-circle at every radius. Borders are evaluated
from inner and outer distances, so their thickness does not change at corners.
The launcher's panel/control/small-control/pill radii and spacing come from the
shared generated theme tokens rather than component-local corrections.

The proof screen contains a 12 px rounded corner enlarged 8×. Its curve and inset
rims match Chromium's launcher rendering without stamped masks, overlapping
rectangles or diagonal chamfers.

## 5. Text rendering

The font build reads Geist Regular, Medium, Semibold and Geist Mono directly from
OpenType tables. It generates and commits one 1024×832 multi-channel distance-field
atlas containing 876 glyphs, 39,326 GPOS kerning pairs and 33 GSUB ligatures.

At runtime `FontAtlas` loads the atlas once. Advances, ascender, descender, line
height, kerning and ligature data are immutable cached values. Glyphs are batched as
ordinary compositor quads, retain fractional horizontal placement and share the
same baseline helpers used by `TextLeaf` and controls. There is no font parsing,
disk read, glyph generation or atlas rebuild in a normal frame or after a UI-scale
change.

The atlas verification reconstructs every glyph with the same median-distance rule
as the shader and compares it with supersampled font outlines from 8–64 px.
Browser fixtures measured from Chromium differ by at most 0.00018 em per glyph.
The old "high quality UI" option is now **Backdrop effects**: it controls expensive
blur/colour processing only. MSDF text and SDF corners always remain native-resolution.

## 6. Layout, input and motion

`Box` is a deliberately small retained layout system: row, column and stack axes;
padding and gaps; fixed, intrinsic and growing sizes; alignment; clipping; scroll
viewports; and anchored floating boxes. A screen constructs its tree once. Layout
returns immediately until the viewport, scale or content invalidates it. Drawing
and hit testing read the same computed rectangles.

`TextLeaf` caches measurement and fitting until its text, width or scale changes.
Lists keep retained row boxes and rebuild only when their data or filter changes.

Mouse position and button state are polled once per rendered frame, with press and
release edges detected independently of visual animation. Motion uses monotonic
elapsed time and launcher-generated 75–215 ms timing tokens. Hover, press, page and
popover state are separate from hit testing and do not overshoot.

## 7. Layers, clipping and GL state

`Layer` defines background, backdrop, surfaces, controls, interaction overlays,
floating content, popovers, tooltips, modals and diagnostics. `LayerRecorder`
stores reusable vertex arrays per layer and submits them in semantic order, not
component call order. Floating layers begin with an unclipped scope, so a dropdown
can escape the scroll viewport that opened it.

Every recorded quad contains its effective clip rectangle. Clipping therefore
cannot leak into a later component and does not require a scissor state change per
control. Frame-end assertions report unbalanced clip, transform, opacity or layer
stacks. `GlStateSnapshot` restores Minecraft's framebuffer, viewport, program,
textures, buffers, matrices, blend/depth/cull/scissor state and colour.

## 8. Backdrop blur

`SdfBackdrop` copies the rendered scene once only if glass was requested. Two 2:1
linear downscales produce a quarter-resolution 4×4 box prefilter; a separable
Gaussian then runs horizontally and vertically. Up to three material strengths are
cached per frame, and panes using the same strength reuse a result. Saturation and
brightness are applied while the glass quad is composited.

Turning off Backdrop effects skips the copy and every blur pass. It does not reduce
shape or text quality.

## 9. Visual parity and debug proof

`desktop/proof/renderer.html` draws the reference scene with the launcher's own CSS.
`RendererProofScreen` draws the same panel, card, pill, button, segmented control,
multi-size text, icon/label row, toggle, scrollbar and popover in game. At the same
1280×720 capture size, the complete scenes differ by 1.4 levels out of 255 per colour
channel on average. Primary and secondary text ink density are within about 1%.
The same scene was checked at scale 1 and 1.5.

The automated tour captured the main menu, account panel, all six Hub sections,
live HUD and HUD editor at 1280×720 after animations settled. The retained menu
tree also matches the launcher's measured card, header, rail and footer geometry.

Developer mode (`-Dfathom.uiDebug=true`, or Gradle `-PuiDebug`) is off by default.
It shows component/padding bounds, text baselines and ascender/descender lines,
clips, hit targets, radii, the pointer's inspected box and per-layer quad counts.
`-PuiTourGeometry` dumps the retained rectangles for deterministic comparison.

## 10. Performance before and after

The direct before/after run at 1920×1080 and scale 1 used the same scripted idle,
hover and scroll tour on the same machine:

| RShift Hub | Old `GlRenderer` | SDF compositor |
|---|---:|---:|
| Draw calls | 91 | 2 |
| Quads | 3,034 | 539 |
| UI CPU mean | 0.69 ms | 0.20 ms |
| GPU mean | 0.19 ms | 0.25 ms |
| Allocation/frame | about 17 KB | 4–10 KB before final retained-page migration |

The small GPU increase is the intentional cost of real framebuffer Gaussian blur.
The CPU and submission reductions are architectural rather than a quality toggle.

A final post-migration run at 1280×720 and scale 1 produced:

| Scenario | FPS in uncapped test | UI CPU mean / p95 | GPU mean / p95 | Allocation mean / p95 | Draws | Quads |
|---|---:|---:|---:|---:|---:|---:|
| Menu idle | 2,043 | 0.170 / 0.282 ms | 0.179 / 0.334 ms | 1,845 / 1,992 B | 3.0 | 207 |
| Menu hover | 2,077 | 0.166 / 0.277 ms | 0.181 / 0.336 ms | 1,899 / 1,992 B | 3.0 | 207 |
| Hub idle | 1,849 | 0.210 / 0.330 ms | 0.224 / 0.376 ms | 1,479 / 1,472 B | 3.0 | 536 |
| Hub hover | 1,899 | 0.207 / 0.327 ms | 0.218 / 0.356 ms | 1,385 / 1,472 B | 3.0 | 536 |
| Hub scroll | 1,802 | 0.224 / 0.370 ms | 0.224 / 0.374 ms | 1,380 / 1,416 B | 3.5 | 580 |
| Hub page changes | 1,598 | 0.307 / 0.600 ms | 0.182 / 0.321 ms | 7,398 / 20,720 B | 2.9 | 357 |
| Hub typing | 2,033 | 0.186 / 0.304 ms | 0.174 / 0.308 ms | 1,837 / 1,816 B | 3.0 | 326 |

The page-change number includes intentional mutations: section selection, list
rebuilds and text changes. Unchanged frames no longer rebuild layout or static rows.
With the UI closed, the profiler receives zero UI frames and the blur performs zero
passes.

## 11. Migrated surfaces

- Main menu and account popover
- RShift Hub frame and rail
- Quick settings
- Module list, filtering, scrolling and module settings controls
- Performance and interface settings
- Profiles list and actions
- Diagnostics and compositor proof scene
- Launcher connection page
- HUD editor
- Live in-game HUD, including ordered flushes around Minecraft item/sprite draws
- Shared dropdowns, tooltips, popovers, buttons, toggles, sliders, segmented controls and scrollbars

There is no remaining production-screen migration. The compatibility `GlRenderer`
is intentionally retained for unsupported GPUs; the NanoVG backend and the old GL
renderer spike are removed.

## 12. Verification and build procedure

The renderer has unit coverage for layer ordering and batch formation, allocation-
free recorder reuse, retained layout invalidation and geometry, material values,
font atlas metrics, kerning, ligatures and Chromium measurement fixtures. The full
core suite and Fabric compilation pass.

Useful repeatable commands:

```powershell
gradle test uiFontAtlasCheck
gradle runClient -PuiTour=proof,menu,account,hub:Quick,hub:Mods,hub:Performance,hub:Profiles,hub:Diagnostics,hub:Launcher,hud-editor -PuiTourScales=1,1.5 -PuiTourGeometry
gradle runClient -PuiTour=world,hud,hud-editor -PuiTourScales=1
gradle runClient '-PuiTour=menu,measure:idle:2,measure:hover:2,hub:Mods,measure:idle:2,measure:hover:2,measure:scroll:2,measure:pages:3,measure:typing:2' -PuiTourScales=1
```

The release build is produced from the repository root with
`tools/build-all.ps1`; its result is recorded in the completion handoff.
