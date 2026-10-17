# Fathom v1 — supported modules

The release-facing list: every module here is implemented, switchable in the
Hub's Mods page, saved in Fathom Profiles, and checked in game by the
self-tests described in `docs/modules.md` §4 and §8. Copy for the website and
the launcher should claim nothing beyond this page. The website's
`web-export/product.json` (`gradle exportWebData`) lists the same twenty
modules, generated from the module registry, which only exports modules marked
Working.

Every module is client-side. None sends a packet, automates input, or reads
anything the server has not already sent to the client.

## HUD

| Module | What it does | Verified |
| --- | --- | --- |
| **FPS** | Frames per second: formats or a custom prefix and suffix, smoothing, update interval (1 s to 100 ms) | Draws and updates; allocation and GL state checked every self-test run |
| **Coordinates** | X / Y / Z (each optional, decimal places, three formats, one line or several), facing (North, N, +Z, or both), biome, dimension, chunk | Rebuilt only when your block, facing or a setting changes |
| **Direction** | A compass strip that turns with you: width, field of view, intercardinal letters, heading in degrees, marker colour | Compass maths unit-tested; drawn every run |
| **Clock** | Real-world time: 24 or 12 hour, seconds, date formats, a custom pattern, system or UTC time zone | Drawn every run |
| **Ping** | Your latency as the tab list reports it: number, bars or both, format, update interval, colour by quality with two thresholds | Multiplayer only; no probe of its own; checked on a local server |
| **Pack Display** | The resource pack(s) you are using, with the pack's own icon | Drawn every run |
| **Scoreboard** | The server's sidebar, moved, scaled and restyled: title, score numbers, row background, text colour and shadow, plate, hide entirely | Draws exactly the lines vanilla would — split team prefixes and suffixes joined, 15-line limit, `#` entries hidden — and hands back to vanilla when off; costs within noise of vanilla's own sidebar |
| **Chat** | Timestamps (24 / 12 hour, seconds), folding identical repeats into a count, background darkness, text shadow | Folded lines keep click and hover targets; differently clickable lines never fold; 120 messages keep vanilla's newest 100; off, messages are stored untouched |

## PvP

| Module | What it does | Verified |
| --- | --- | --- |
| **Keystrokes** | W A S D, mouse buttons, space and sneak, lit while held; labels follow your key bindings; CPS on the mouse keys | Clicks counted in game, never behind a menu |
| **CPS** | Clicks per second, left, right, both or combined, over a chosen window | As above |
| **Armour HUD** | Armour and held item: durability as percent, remaining or remaining / max, a durability bar, colour by condition with two thresholds, labels (slot or item name), arrow count, vertical or horizontal, reverse order, icon size | Drawn with real items in captures |
| **Potion HUD** | Active effects with level and time left (`**:**` for permanent), icons and icon size, compact mode, vertical or horizontal, order by applied, time left or name, effect colours, a warning when ending | Drawn with timed and permanent effects in captures |

## Utility

| Module | What it does | Verified |
| --- | --- | --- |
| **Sprint and sneak** | Toggle (or hold) sprint and sneak independently, with an on-screen state label | Latches, sprints, releases, and clears on disconnect and in a new world |
| **Zoom** | Hold or toggle, amount with a minimum and maximum, scroll to zoom further (step), reset or remember on release, smooth zoom and its duration, sensitivity compensation, cinematic camera, hide hand; steps aside for OptiFine's zoom | Never writes the field of view, sensitivity or smooth-camera options |

## Visual

| Module | What it does | Verified |
| --- | --- | --- |
| **Crosshair** | Presets (Default, Classic, Dot, Small, Competitive, Plus, T-shape, Circle) or Vanilla; length, thickness, gap, centre dot, outline, colours, opacity; dynamic gap while moving; target colour; hide in third person or behind menus | Centred on the screen to within half a pixel at GUI scales 1–4 and at 1280×720, 1366×768, 1279×719, 800×600 and 2560×1080 — closer than vanilla's at fractional GUI sizes |
| **Fullbright** | See in the dark, adjustable strength, optional toggle key | Lights the darkest lightmap cell; off, the light is your own again; never writes the brightness option |
| **Time Changer** | The sky at a chosen time of day, or a custom hour, optional toggle key | Client sky only: the world's time stays the server's, and turning it off follows the server again |
| **Fathom main menu** | Fathom's title screen in place of Minecraft's | — |

## Performance

| Module | What it does |
| --- | --- |
| **Unfocused FPS limit** | Fewer frames while the window is in the background |
| **Performance layer** | Fathom's in-game rendering optimizations (see `docs/performance-module.md`) |

## Every HUD module also has

Position, anchor, scale and opacity in the HUD editor and on its own page;
font (Minecraft or Fathom), text colour and shadow, plate material, colour,
opacity, border and corner radius; Hide with F3 and Hide behind menus; Reset to
defaults. Widgets never overlap each other by accident: they stack instead.

## Compatibility checked

- **Minecraft 1.8.9 with Fabric Loader 0.19.3** (what the launcher installs),
  development and production launches. The mod declares `fabricloader >= 0.15`
  but only 0.19.3 is tested.
- **OptiFine HD U M5 through OptiFabric 2.3.6**, and without OptiFine.
  OptiFine is never downloaded or bundled by Fathom; the player installs it.
- **A local 1.8.9 server with twenty players**, and singleplayer.
- **Resource packs**: a pack that changes glyph widths is picked up on reload
  and dropped on removal; GL state stays clean across reloads.
- **Resolutions** 800×600 to 2560×1080, including odd sizes, at GUI scales 1–4.

## Not in v1

Deliberately left out, and not to be claimed: free look, hit boxes, reach or
combo displays, auto-text and other automation, minimaps, waypoints, motion
blur and shaders of Fathom's own. Chat's text opacity, scale, width and height
stay in vanilla's Chat Settings, which already have them.

Not yet verified: real public servers (the server-style sidebar and chat
components are reproduced locally), OptiFine shaders or custom-colour
lightmaps with Fullbright, and Fabric Loader versions other than 0.19.3.
