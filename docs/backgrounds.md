# Launcher and in-game backgrounds

Fathom uses supplied Minecraft recordings in two places: looping video behind
the desktop launcher and a prepared looping texture sequence behind the in-game
main menu. Source recordings remain outside the repository; only runtime
derivatives are checked in.

## Launcher video pipeline

Run the preparation script from the repository root:

```powershell
pwsh tools/prepare-background-video.ps1
```

By default it reads supported `bg_*.mp4` files from the user's Downloads folder
and writes derivatives to `desktop/public/backgrounds`. Build selected clips by
name or by a legacy number:

```powershell
pwsh tools/prepare-background-video.ps1 -Only bg_5rain,bg_6clear
pwsh tools/prepare-background-video.ps1 -Only 1,2
```

The `$Clips` table in the script is the source of truth for each asset's name,
segment start, grade, scene label, and loop length. Add a row there when a new
recording arrives.

Each launcher background produces:

```text
desktop/public/backgrounds/<name>.webm  1600×900, 30 fps, silent VP9
desktop/public/backgrounds/<name>.jpg   640×360 startup poster
```

VP9/WebM is intentional. WebView2 cannot reliably demux H.264 in the launcher,
whereas Chromium runtimes include VP9 support. Blur and per-clip grading are
baked during preparation so the launcher does not run a full-screen blur every
frame. CSS applies only a lightweight contrast/saturation treatment and the
dark readability scrim.

The script builds a seamless loop by dissolving the final 24 frames into the
opening 24. Most clips use a ten-second 300-frame loop. Short recordings can
override `LoopFrames`; `bg_6midnight`, for example, uses 180 frames.

## Playback and selection

`desktop/src/lib/Background.svelte` keeps two video slots. It waits for the
incoming clip to have a decodable frame before crossfading, then pauses and
releases the outgoing decoder. The static Midnight surface always exists below
both slots, so changing backgrounds never reveals an empty black frame.

The user-facing choices are declared in `desktop/src/App.svelte`. Add the
generated asset's `id` to `BACKGROUNDS` to expose it in the picker. The ID must
match the `.webm` and `.jpg` basename.

Playback pauses while Minecraft is running or the document is hidden, avoiding
competition with the game for GPU/video-decoder resources.

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

## Screenshot pulls

The five `bg_6pull_*.jpg` images in `desktop/public/backgrounds` are stills from
`bg_6screenshotPulls.mp4`. They are 1728×972 crops: 96 pixels are removed from
each side and the lower 108 pixels are removed to exclude the hotbar while
retaining 16:9 composition. Exact source frame numbers are recorded in the
asset-folder README.

## Attribution

bg_5 and bg_6 were recorded in community-made worlds whose creators allow use
with credit. Those credits, and the list of every asset derived from each
recording, are in [`ATTRIBUTION.md`](../ATTRIBUTION.md). Add any new recording
there before its assets are committed.
