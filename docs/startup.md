# Start-up

What happens between double-clicking Fathom and Home being usable, and the
rules that keep it fast.

## The sequence

1. **Before the window exists** (`run` in `crates/launcher-desktop/src/lib.rs`):
   logging, `stage_embedded_client`, and `Launcher::discover` (settings, the
   credential store, profiles, Fathom components). Everything here delays the
   first frame, so nothing here may read or hash large files.
2. **Setup** starts two background threads and returns at once: the Java scan
   (`JavaScan`) and the saved-session restore (`signin::restore`).
3. **The WebView loads `index.html`**, which paints a static splash (the mark
   and wordmark on the icon's ground, and a sweeping line) in plain HTML and
   CSS before any script runs.
4. **`main.ts` shows the window** immediately, on that splash. The window is
   created hidden only so the OS never shows an empty frame before the WebView
   has drawn; its native background is the splash colour.
5. **`App.svelte` asks for everything Home needs at once** — home, status,
   background, accounts, settings, sign-in and launch state — behind
   `LoadingScreen.svelte`, which continues the splash exactly and names what is
   still outstanding. It fades out when the answers are in.

## What used to make it slow

| Cost | Where | Now |
| --- | --- | --- |
| Byte-comparing the ~35 MB embedded client jar with the installed copy, and paging all of it in from the executable | `stage_embedded_client`, before the window | A stamp file with the build's fingerprint (`FATHOM_CLIENT_JAR_ID`); only a new build or a missing stamp reads the jar |
| Reading and SHA-1 hashing the same jar once **per Fathom profile** | `components::ensure` → `ModStore::adopt`, before the window | `ModStore::adopt_remembered`: remembered by path, length and mtime |
| `java -version` for every Java on PATH, one after another, holding the launcher lock; repeated on every Home/Settings visit when none was found | `home_view`, `settings_view`, `recovery_view` | One scan on a background thread from setup, lock released; screens wait for it (10 s cap) |
| The window hidden until four calls, then settings, then sign-in, then launch state had answered in turn | `App.svelte` | Shown on the splash at once; all calls in parallel behind the loading screen |

Measured on a Linux dev machine with a warm disk cache (a lower bound —
Windows with a cold cache and real-time scanning is slower): adopting the
jar 50 ms → 0.02 ms per Fathom profile; the embedded-jar check 22 ms → 0.01 ms.
Each `java -version` is 50–100 ms there and commonly several hundred on
Windows.

## Restoring a saved session

The restore runs on its own thread and never holds up the loading screen: the
shell draws the remembered account as restoring. If it has not finished after
`signin::RESTORE_LIMIT` (20 seconds), a watchdog settles it as
"Sign in again" — the drawer and the top bar both say so and the Sign In button
is live — and cancels it. A restore that answers after that is discarded rather
than adopted, so the launcher never contradicts what it just told the user.
Each sign-in attempt has a generation number, so a late result can never
overwrite a newer attempt.
