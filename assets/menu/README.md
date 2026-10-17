# Main menu video

The in-game main menu plays `bg_5rain`, one of the background recordings.
Minecraft 1.8.9 can't decode video, so the game ships it as a 300-frame,
30 fps JPEG sequence:

`minecraft/fathom-optimizations/src/main/resources/assets/fathom-optimizations/textures/menu/bg_5rain/frame_000.jpg`
to `frame_299.jpg`

Regenerate it with:

```powershell
pwsh tools/prepare-main-menu-video.ps1
```

The script takes ten seconds of the recording and cross-fades the end into the
start so the loop doesn't jump. In game, a background thread decodes one frame
ahead and the render thread swaps the texture at 30 Hz. The menu itself is drawn
on top at full resolution.

`main-menu-bg.png` (from `tools/prepare-main-menu-poster.ps1`) is the still used
before the first frame decodes, or if decoding fails.

The recording was made in a community-built world; its creator is credited in
[ATTRIBUTION.md](../../ATTRIBUTION.md).
