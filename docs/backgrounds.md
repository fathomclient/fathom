# Launcher and in-game backgrounds

Fathom uses supplied Minecraft recordings in two places: looping video behind
the desktop launcher and a prepared looping texture sequence behind the in-game
main menu. Source recordings remain outside the repository; only runtime
derivatives are checked in.

## The library

The user chooses the background. `assets/backgrounds/manifest.json` is the one
catalogue: id, display name, asset path, type (video or image), native
resolution, thumbnail, fallback still, and whether the launcher and the game
offer it. From it:

- `tools/generate-backgrounds.mjs` writes `desktop/src/lib/backgrounds.generated.ts`
  (the launcher's picker and player) and `BackgroundCatalog.java` (the game).
  `npm run check` fails if either is stale.
- `crates/launcher-desktop/src/lib.rs` keeps its own literal allowlist -- the id
  ends up in a URL -- and a test fails if it and the manifest disagree.
- `tools/derive-background-accents.mjs` measures each background's `accent`,
  the colour the interface's light takes while it shows (a hue histogram of
  the still, weighted to its vivid, bright pixels, set at the theme's one
  lightness). Where the loudest colour is not the one the picture is about,
  the entry pins the hue with `accentHue`: Cherry Grove (blossom, not grass)
  and Red Tower (the tower, not the sky).
- `tools/prepare-background-library.mjs` builds every derived file: each video's
  full-resolution fallback still (frame 0 of the clip), every 384×216 thumbnail,
  the game's copy of every still, and Rainforest Storm's launcher video.

The default is Rainforest Storm: after the audit it is the only background with
an unblurred, full-HD moving source in the repository.

## Launcher playback

`desktop/src/lib/Background.svelte` paints the background's still at once and
never removes it, then plays the `.webm` over it with `autoplay muted loop
playsinline`, `object-fit: cover`, at its native resolution. The video stays
transparent until it reports `playing`, so the hand-over from still (frame 0)
to video is invisible; on a decode or load error the still simply stays.
Playback pauses only while the document is hidden -- never on a loss of focus,
which is what made a launcher sitting beside the game look frozen.

The only treatment on top is the light scrim (`--backdrop-scrim`) and the
Graphite vignette and grain. Nothing is re-graded per background and nothing
is blurred at runtime.

### Why it used to look frozen and soft

It was a still: the last Graphite pass replaced the video player with one
`<img>` of the Rainforest Storm poster. And the videos it had played were
1600×900 with a Gaussian blur baked in (sigma 3.5, CRF 30), with 640×360
posters stretched to full screen. Rainforest Storm is now re-encoded from the
game's unblurred 1920×1080 frame sequence at CRF 23. The other recordings'
sources live outside the repository; `tools/prepare-background-video.ps1` now
builds them unblurred at 1920×1080, CRF 23, whenever it is run.

VP9/WebM is intentional: WebView2 cannot reliably demux H.264, and every
Chromium runtime decodes VP9 (in hardware where the GPU supports it).

## In-game main-menu video

Minecraft 1.8.9 has no built-in video decoder. The in-game main menu therefore
turns `bg_5rain.mp4` into two disposable JPEG sequences without adding a native
codec dependency. The preparation script reads the source cadence, preserves it
up to 60 fps, and never invents frames. The supplied file is actually 1920×1080
at 30 fps, despite its earlier 60 fps description, so the current cache is 30 fps:

```text
minecraft/fathom-optimizations/src/main/resources/
  assets/fathom-optimizations/textures/menu/bg_5rain/
    high/frame_000.jpg ... frame_299.jpg  # 1920×1080, JPEG q2
    low/frame_000.jpg ... frame_299.jpg   # 1280×720, JPEG q4
    video.properties                     # source cadence and frame count
```

Regenerate it with:

```powershell
pwsh tools/prepare-main-menu-video.ps1
```

The script selects a ten-second segment and closes the loop with a 0.8-second
dissolve. FFmpeg performs Lanczos scaling and a mild grade; it does not bake in
the old destructive Gaussian blur. The shipped game does not bundle FFmpeg.
At runtime a low-priority Java `ImageIO` daemon decodes CPU-side
`TYPE_3BYTE_BGR` JPEGs into a five-frame pool of reusable direct buffers. The
render thread uploads them with `glTexImage2D`/`glTexSubImage2D` to one RGB
texture, samples it linearly, and advances it on an independent monotonic
source-cadence clock. Interface elements are then rendered independently at
framebuffer-native resolution.

`Menu background quality` defaults to **High**. **Auto** begins at High and
falls back to the 720p sequence only after sustained measured misses; **Low** is
an explicit 720p choice and **Off** uses the poster. Decode and presentation
pause while the Minecraft window is unfocused. None of these modes change the
UI scale, input polling, animation clock, or UI frame limit.

`main-menu-bg.png` is retained only as the first-frame and decode-failure
fallback. Regenerate that fallback with `tools/prepare-main-menu-poster.ps1`.

## In-game backgrounds

The title screen and Fathom menu draw the background chosen in the launcher
(`FathomMenuScreen.drawBackdrop`). The Hub offers two persisted presentation
modes. **Fathom background** keeps that same behavior. **Gameplay glass** keeps
the live world framebuffer, downsamples and blurs it on the GPU, then applies a
controlled dark tint before drawing the Hub. Gameplay glass falls back to the
Fathom background when no world exists.

The game reads the chosen library background from the launcher's
`appearance.json` in its data root (`BackgroundChoice`, re-checked at most once
a second). Rainforest Storm plays its frame sequence; every other background
shows its full-resolution still from `textures/menu/scenes/`, the same picture
the launcher shows. A game started without the launcher shows the default. The
HUD is gameplay and never draws a menu background.

## Tokyo Tower and Aurora

`bg_6tokyo` and `bg_6aurora` replaced the five `bg_6pull_*` screenshots, which
were stills from a walkthrough and showed the player's hand. Both are fixed-camera
recordings in the same world (`tokyo.mp4`, `aurora.mp4`), encoded by
`tools/prepare-background-video.ps1` at 1920×1080 like the others: Tokyo graded
up slightly (gamma 1.08) and Aurora down (0.82) to sit in the library's
brightness range.

## Attribution

bg_5 and bg_6 were recorded in community-made worlds whose creators allow use
with credit. Those credits, and the list of every asset derived from each
recording, are in [`ATTRIBUTION.md`](../ATTRIBUTION.md). Add any new recording
there before its assets are committed.
