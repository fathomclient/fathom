# RShift presentation and motion validation

## Result

RShift has two profile-scoped presentation modes in the dedicated
**Appearance** page:

- **Fathom background** draws the selected library background and its existing
  legibility treatment.
- **Gameplay glass** (the default) preserves the visible world, blurs it live,
  applies a neutral dark tint, and draws the same Fathom surfaces above it. When
  no world exists, the Hub automatically uses the Fathom background.

The restrained companion controls are **Glass blur** (Soft 12 px, Balanced
18 px, Strong 26 px) and **Glass dim** (Light 24%, Balanced 34%, Deep 44%). Both
default to Balanced. The mode and both strengths persist in profiles and in the
legacy preference store.

## Blur pipeline and resource lifetime

The existing SDF compositor owns the blur. One full-screen glass primitive asks
`SdfBackdrop` to:

1. copy/resolve the current world framebuffer into a full-size texture;
2. perform two linear 2:1 downsamples (full → half → quarter resolution);
3. run a horizontal and vertical separable Gaussian at quarter resolution, with
   paired bilinear taps; and
4. upscale while composing the full-screen glass, then apply the dim/tint pass.

This is five GPU operations for one blur strength and avoids a large
full-resolution convolution. The full-, half-, scratch-, and result-size
textures/framebuffers plus the fullscreen triangle buffer are cached. They are
created lazily and recreated only when framebuffer dimensions change; opening or
rendering the Hub does not allocate framebuffer resources per frame. Unsupported
framebuffer hardware continues through the compositor's established fallback.

## Live versus frozen benchmark

The two modes were profiled at 1280×720, scale 1, in the same world and Hub
section after warm-up:

| Mode | UI CPU mean | GPU mean | GPU p95 | Draws | Quads | Frames |
|---|---:|---:|---:|---:|---:|---:|
| Fathom background | 0.280 ms | 0.147 ms | 0.157 ms | 3 | 689 | 3,409 |
| Live gameplay glass | 0.421 ms | 0.197 ms | 0.582 ms | 2 | 684 | 1,487 |

The measured live-glass GPU delta was **0.050 ms mean**. That is negligible in
this workload, so Gameplay glass stays live rather than freezing the opening
frame. A snapshot path would add state and stale-world behavior without a useful
performance win. This decision is based on this desktop/GPU run; unusually slow
or unsupported drivers remain the practical limitation.

### Appearance-page regression pass

After adding the real-thumbnail grid and its caches, the finished Appearance
page was measured again on the same machine, resolution, world, scale, and
four-second idle harness:

| Mode | UI CPU mean | GPU mean | GPU p95 | Draws | Quads | Frames |
|---|---:|---:|---:|---:|---:|---:|
| Fathom background | 0.244 ms | 0.149 ms | 0.154 ms | 11 | 506 | 3,043 |
| Live gameplay glass | 0.231 ms | 0.165 ms | 0.164 ms | 10 | 504 | 3,201 |

The new same-page live-glass delta is **0.016 ms GPU mean**. Thumbnail textures
account for the extra texture runs but are uploaded once; steady-state GPU and
CPU cost stayed below the earlier presentation benchmark. With RShift closed,
the section is not rendered, polled, or asked for a selected scene, so the new
picker contributes no recurring closed-menu work.

## Motion and input

All primary UI motion uses monotonic time, not ticks or frame counts. Controls
and state changes use `cubic-bezier(0.2, 0.8, 0.2, 1)`. Hub entry, close, and
page motion use Graphite's sharper `cubic-bezier(0.2, 0, 0, 1)`; ordinary
popover dismissals use the shorter quadratic exit ease-out.

| Intent | Duration |
|---|---:|
| Press | 90 ms |
| Hover | 120 ms |
| Toggle, segmented selection, navigation | 160 ms |
| RShift open and page change | 180 ms |
| Popover/account surface | 180 ms |
| Other screen/detail entry | 200 ms |
| RShift close and dismiss | 120 ms |
| Item stagger | 24 ms |

RShift close and reopen sample the exact currently visible value before
retargeting. Rapid toggles therefore do not reverse a canned clip or reset
opacity/position. Page changes retain the old page for a brief crossfade while
the destination becomes interactive immediately. Popovers and the account panel
likewise keep their visual state during exit while disabling only the departing
surface.

Pointer and keyboard edges are read once per rendered frame rather than waiting
for Minecraft's 20 Hz screen callbacks. Hit testing always uses final control
bounds, click/press feedback begins on that same rendered-frame edge, and input
does not wait for unrelated animation completion. At the 327–505 fps observed
during capture, the polling bound was roughly 2–3 ms rather than the old
up-to-50 ms tick bound (exclusive of device/display latency).

## Frame-by-frame validation

The development motion tour captured half-resolution PNG sequences at a 60 fps
target for open, close, rapid open/close, tab changes, button hover, popover, and
scroll. The recorder is warmed before the measured sequence and reported zero
dropped capture frames. The warmed opening sequence contains 19 frames with
16–20 ms spacing.

Contact sheets live under
`minecraft/fathom-optimizations/run/screenshots/motion-*/contact-sheet.png` and
can be regenerated with `tools/motion-contact-sheets.ps1`. Frame-by-frame review
found no opacity jumps, clipping-bound changes, text movement, cursor/hover lag,
or reverse-toggle snapping. The visible tab overlap is the intentional retained
crossfade; departing content is non-interactive.

Remaining limitations: capture is a development-only visual harness rather than
automated optical-flow judgement; GPU timing represents the tested machine and
resolution; and Fathom background video remains a separate decode path from live
world glass.
