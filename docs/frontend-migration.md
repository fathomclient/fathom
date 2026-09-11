# The frontend migration

The presentation layer moved from `iced` to Tauri 2 + Svelte 5 + TypeScript.
`launcher-core` did not move, and nothing in it was rewritten.

## What is where now

| | |
| --- | --- |
| `crates/launcher-core` | **Unchanged.** Auth, installer, Java, profiles, mods, downloads, network policy, recovery, updater, paths, credentials, launch pipeline. |
| `crates/launcher-desktop` | The new shell. A window, a WebView, four typed commands. |
| `desktop/` | Svelte frontend. Renders; decides nothing. |
| `crates/launcher-ui` | The old iced interface, still building and still passing its tests. |

The iced crate is deliberately still there. It is a working reference for the
pages that have not been rebuilt, and deleting it before its replacements exist
would trade a dated interface for a missing one.

## The boundary

`tauri` is depended on with **no features and no plugins** — no filesystem,
shell, HTTP or dialog plugin. Every capability those would add is either
something the frontend must not have or something the core already does behind a
command. The CSP is `default-src 'self'`, so the WebView cannot load a remote
page or reach a remote API even if something tried to make it.

Four commands, all plain data: `home_view`, `shell_status`, `background`,
`set_background`. No token, no path the user has no reason to see, no handle
that would let the caller do something not on that list. `set_background`
validates against a fixed list, because its value ends up inside a URL and "the
WebView asked for it" is not a reason to trust a string.

`launch_action` deserves a note: the frontend is handed a *decision* — one label,
one detail, one command — rather than the blockers behind it. Otherwise launch
judgement would start being made in TypeScript.

## The background, and three wrong diagnoses

The background is `<video>` playing VP9/WebM, GPU-composited by the WebView:
1280×720, 30 fps, 10-second seamless loop, 0.1–0.5 MB per clip.

Getting there took four attempts, and the first three were wrong:

1. **"WebView2 cannot decode H.264."** The MP4 failed with
   `DEMUXER_ERROR_COULD_NOT_OPEN`. Re-encoded everything to VP9/WebM.
2. **"WebView2 has no media pipeline at all."** The WebM failed the same way,
   while `canPlayType` answered `"probably"` for both. Rebuilt the whole
   component around animated WebP to use the image decoder instead.
3. **"The `<img>` lifecycle is cancelling the decode."** The image reported
   `naturalWidth 0` while an identical URL elsewhere on the page loaded fine.
   Rewrote it again to use CSS `background-image`.
4. **The actual cause.** Printing the computed URL showed
   `/backgrounds/bg1.webp` — no underscore. The asset files are `bg_1`. The
   preference came from the old iced build, whose enum serialised through
   serde's `snake_case` rename as `"bg1"`.

Every failure was a 404 being handed to a decoder. "Open context failed" was an
HTML error page reaching the demuxer. The media pipeline had been fine the whole
time, and the code is back on `<video>`.

The lesson worth keeping: `canPlayType` is a guess, decoder errors describe the
bytes they were given rather than the request that produced them, and the first
thing to check is what URL was actually requested. `normalise()` in
`main.rs` now migrates the old spelling, and a test covers it.

Two real bugs were found along the way and both are fixed:

- **A transition started in a hidden document never completes.** Chromium
  freezes it at `currentTime 0` and does not resume it when the document becomes
  visible. The background fade fires at startup, so launching into an unfocused
  or minimised window left the background black permanently. Opacity is now an
  inline style and the transition duration is zero while hidden.
- **A media element with `src=""` is not empty.** The empty string resolves
  against the document URL and the element tries to decode the HTML page. Slots
  with no clip now render no element.

## Typography

**Inter**, bundled as a variable font — never fetched, so there is no font-CDN
request on start. Chosen over Geist, Manrope and DM Sans for its small-size
rendering: most of this interface is 11–14 px, and Inter is the one designed for
exactly that.

The scale is compact by default (11 / 12 / 13 / 14 / 16), with 22 for page
titles and a `clamp()`-scaled hero for the launch composition only. The
composition scales with the window because a fixed-pixel one is sized for a
single monitor: at 1366 it fills the canvas and at 1920 it strands the launch
control in an empty field.

## Motion

Three durations (120 / 170 / 220 ms) and one curve — a cubic ease-out, not
ease-in-out, because a transition the user *caused* should begin at full speed.
Wired to: page transitions, nav selection (a marker that grows from the window
edge), hover and press states, the picker popover, and the background crossfade.
Nothing bounces and nothing animates continuously.

## Icons

**Lucide**, one family throughout. Routes reference an icon through a table in
`App.svelte`, so replacing them with custom Fathom artwork means editing that
table and no screen code.

## The pages

All six product screens are built: Profiles, Mods, Performance, Settings,
Privacy, Recovery. `Placeholder.svelte` is deleted — there is no route that
renders "not yet rebuilt".

Account is not a screen. It is the first row of Settings, and both the rail and
the top-bar avatar route there. A separate Account page would be a second place
showing one row.

### Mutations return views, not acknowledgements

Every command that changes something returns the whole refreshed view:
`set_optimization` returns a `PerformanceView`, `set_mod_enabled` returns a
`ModsView`. The page assigns it and re-renders.

The alternative — update local state optimistically, fire the command, ignore
the result — is how an interface ends up showing a switch as on after the core
declined to turn it on. Round-tripping costs a frame and makes that class of
bug impossible rather than unlikely.

### The file picker is in Rust

`Add a JAR` calls a Rust command that opens the OS dialog with `rfd`, reads the
file, and copies it into the content-addressed store. The WebView never learns
the path and cannot open a picker of its own.

This is why the crate depends on `rfd` and *not* on Tauri's dialog plugin: a
plugin would put a general "open a dialog" capability in the WebView's hands,
which is exactly the kind of capability the boundary exists to withhold. `tauri`
itself still has no features and no plugins.

## Running it in development

```bash
pwsh tools/dev.ps1
```

Starts Vite and runs a debug build against it. Edit anything under
`desktop/src` and the window updates immediately — no cargo build, no
packaging. Only a Rust change needs a rebuild.

`-DevAuth` additionally compiles in the fake development identity and sets the
environment variable it requires.

### Why a debug build loads a dev server at all

The same mechanism packaging uses, running the other way. Tauri picks its source
from a cargo feature rather than from debug-vs-release:

```rust
dev: cfg!(not(feature = "custom-protocol")),
```

`package-portable.ps1` passes `--features custom-protocol` and gets an embedded
bundle; `dev.ps1` omits it and gets `devUrl`. A release build that forgets the
flag is the bug that once shipped a launcher showing "localhost refused to
connect".

### One trap worth knowing

Vite here binds to **`::1` only** unless told otherwise, while `devUrl` is
`http://localhost:5173` — so whether the WebView reaches it depends on which
address its resolver tries first. `dev.ps1` pins `--host 127.0.0.1`. The first
version of the script also probed the port over IPv4 and concluded, wrongly,
that Vite had failed to start.

## What is not done

**The launch pipeline is not wired to a command.** Pressing PLAY still routes to
a screen rather than starting Minecraft. The core can now launch — including
with Legacy Fabric — but the Tauri command bridge is request/response, and
install and launch are long-running operations with progress and cancellation.
They need an event channel. That is the next piece of plumbing, and it is the
single largest thing standing between this build and a launcher you can play
from.

Recovery's `Check game files` is the exception and is real: it asks the same
readiness question Home asks, needs no network, and returns a sentence.

**No boot-failure window.** If `Launcher::discover` fails, the Tauri binary logs
and exits with a message on stderr. The iced build opened a window explaining
what happened; that has no equivalent yet.

**Sign-in is not wired.** Settings shows account state and routes to itself; the
device-code flow is not reachable from the new frontend.

**The iced crate still exists** and still compiles. It is no longer the product,
and nothing in this milestone was added to it.
