# Fathom public beta — readiness report

Date: 2026-10-12. Build: launcher and installer 0.3.0 (0.1.0 before P1-5 was
fixed), client 0.3.0-hud (commit 6a3302d, dirty working tree). Machine: Windows 11, RTX 4060, 12 threads.

## Recommendation: **NO-GO** for a public beta today

Not because the client is unstable — every automated suite and every in-game
self-test passes, and the installer installs, updates in place and uninstalls
cleanly. It is NO-GO because **a tester who installs the beta cannot sign in**:
no build made for other people carries a Microsoft application ID (P0-1 below).
Fix that one item, confirm a real sign-in on a second machine (§7), and the
recommendation becomes **GO for a closed beta of invited testers**, with the
P1 items stated to them up front.

---

## 1. P0 — release blockers

| # | Blocker | Evidence | What clears it |
| --- | --- | --- | --- |
| P0-1 | **No Microsoft application ID in distributable builds.** Sign-in refuses with "this build has no Microsoft application id". Today it works only on a machine where `FATHOM_AZURE_CLIENT_ID` is set by hand — yours. Without sign-in nobody can launch online or join a server. | `AzureApp::from_env`; the installer built today; `signin.rs` refusal | Decide which approved registration ships, set `FATHOM_RELEASE_AZURE_CLIENT_ID` when running `tools/build-installer.ps1` (the build now embeds that variable and warns loudly when it is absent), and complete §7 step A on a machine that has never run Fathom. A personal `FATHOM_AZURE_CLIENT_ID` is deliberately **not** embedded. |

Nothing else found is a blocker.

## 2. P1 — defects that should be fixed or disclosed before the beta

| # | Issue | Impact | Recommendation |
| --- | --- | --- | --- |
| P1-1 | **Installer and launcher are not code-signed.** | SmartScreen shows "Windows protected your PC"; some antivirus will flag or quarantine an unsigned installer. Testers must choose *Run anyway*. | Acceptable for an invited beta if stated (the tester checklist does). Sign before any open release. |
| P1-2 | **No in-app updates.** The update mechanism is implemented and tested but refuses to run: there is no release signing key (`ROOT_KEYS` is empty, by design). | Every beta build has to be downloaded and run over the old one. Verified to work (§5), but testers will drift onto old builds. | Generate the offline root key and stand up the release host before an open beta; for a closed beta, announce each build. |
| P1-3 | **Microsoft sign-in, session restore, account switching and logout were not exercised end to end in this pass.** | These are the first things a tester does. The code paths exist and have contract tests, and the restore path was observed working on the existing install, but no fresh sign-in was performed. | §7 step A, by a person, on a clean machine, before invitations go out. |
| P1-4 | **No clean-machine run of the whole product.** | Install, first sign-in and the first download of Minecraft/Java/loader were verified separately (§5) but never in one run on a machine without Fathom data. | §7 step A. |
| ~~P1-5~~ | **Fixed.** ~~Installer metadata: version 0.1.0 against client 0.3.0-hud; empty LegalCopyright and installer CompanyName.~~ | Now one product version, **0.3.0**, across the workspace, `tauri.conf.json` and the frontend; LegalCopyright set via `bundle.copyright`; the setup executable's CompanyName added by `crates/launcher-desktop/installer/hooks.nsh` (Tauri's NSIS template omits it). Verified on the built files' version resources and on a sandboxed install's Apps entry (Fathom Client · 0.3.0 · Fathom). | — |

## 3. Known non-blocking issues

- Real public servers were not tested; servers were a local 1.8.9 server with
  bots. Big-server sidebars and clickable chat were reproduced locally.
- Fullbright with OptiFine shaders or custom-colour lightmaps is unverified.
- Only Fabric Loader 0.19.3 (what the launcher installs) is tested; the mod
  declares `>= 0.15`.
- With every HUD widget on at the smallest common GUI size (426×240), a widget a
  player has placed by hand can still overlap another when there is no room to
  move it; the editor outlines it in orange.
- An upgrade between two *different* versions was not performed (only a
  reinstall of the same version over a running copy).
- Older launcher logs on existing installs contain the account's profile UUID;
  new logs do not (fixed in this pass), and diagnostic reports now remove it
  either way.
- `fathom-cli`'s doc-tests cannot run on this toolchain (`rustdoc` missing);
  unrelated to the product.

## 4. Fixed in this pass

| Area | Change |
| --- | --- |
| Sign-in for releases | A release build can embed the approved application ID from `FATHOM_RELEASE_AZURE_CLIENT_ID`; `build-installer.ps1` reports whether it did and warns if not. A developer's personal variable is never baked in. |
| Privacy | The launcher no longer logs the account's profile UUID on start-up. Diagnostic reports replace the Minecraft name after `Setting user:` with `<player>` and any UUID with `<uuid>` (new test). |
| Network transparency | `NETWORK.md` now lists the CurseForge hosts (used only with a user-supplied key) and the launcher's own skin fetch, which it wrongly said never happened. |
| Launcher → game handoff | A shared fixture (`src/test/resources/contract/session.json`): Rust asserts it writes exactly that file, Java parses that file — quotes, backslashes, non-ASCII and a Windows path included. |
| Keybind conflicts | The conflict rule moved from the Mods page into `core/input/KeyConflicts`, which the page now calls and a test now covers (modules, the Hub key, Minecraft's bindings, mouse buttons). |
| Benchmark harness | The benchmark server crashed on a Windows file-sharing race while rewriting its control file; it now retries. |
| Test hygiene | Six duplicate test runs removed from `FeatureTests`' list. |

Earlier in this release cycle (and verified again here): resource-pack reloads
had never refreshed Fathom's caches because the reload hook was never installed;
`-Dfathom.benchmark=none` armed the benchmark; corrupt widget values failed whole
profiles in the game and the launcher.

## 5. Installer and distribution — what was verified

`tools/build-installer.ps1` built `dist/installer/Fathom Client_0.1.0_x64-setup.exe`
(the first installer ever produced from this repository; since rebuilt as
`Fathom Client_0.3.0_x64-setup.exe` with the mod browser and mod management, SHA-256
`B443EEFF3CEA30A700DFE4D44F627FC5B84EAE0DFCCD828E11191C37F5582EA4`,
which correctly warns that it carries no application ID). Then, in a sandboxed
per-user location (this tool's processes write to a private copy of
`%LOCALAPPDATA%`, so the real install and data were never touched):

| Check | Result |
| --- | --- |
| Silent per-user install, no elevation | exit 0; `%LOCALAPPDATA%\Fathom Client\` with `fathom-desktop.exe`, `uninstall.exe`, `resources\fathom-optimizations.jar` |
| Start Menu entry, Apps & features entry | present; publisher Fathom; Fathom icon |
| Launcher starts from the installed copy | window opens; UI from the embedded bundle, no localhost server; bundled client applied to existing profiles |
| User data untouched by install | 1,800 files byte-identical before and after |
| Install over a **running** launcher (the beta's update path) | installer closes the launcher itself and completes, exit 0 |
| Uninstall | program folder, Start Menu entry and registration removed; 1,800 data files — every profile's saves, screenshots, resource packs and options — byte-identical |
| Uninstaller's "delete app data" box | deletes only the WebView cache (`net.fathom.launcher`), never `Fathom\data` (read from the generated NSIS script) |
| Build-machine paths in the release binary | none (`C:\Users\…`, cargo registry); the one `localhost:5173` string is Tauri's unused dev setting |
| Code signature | **not signed** (P1-1) |
| Clean download pipeline from the internet, into an empty temporary root | live tests pass: Mojang's real 1.8.9 document verified, a complete 1.8.9 resolution, Legacy Fabric's metadata, a real Modrinth download verified by hash |
| Interrupted downloads and updates | covered by the existing failure-injection and update-chain suites (all pass): truncation, corruption, journaled swap with retained backup |

## 6. Security and privacy review

Reviewed against `THREAT_MODEL.md` and `NETWORK.md`, concentrating on code added
since the last recorded review (2026-08-27).

| Area | Finding |
| --- | --- |
| Token storage | Windows Credential Manager; secret types redact themselves in `Debug`; unchanged and tested. |
| Game-side mod | Opens exactly one connection: loopback to the launcher's bridge, with a handshake token. Its startup trace drops the value after every credential argument. No telemetry. |
| Logs | Game logs contain the Minecraft name (vanilla's own line) and, in one third-party Mixin line, the Windows username in a library path. Launcher logs no longer contain the profile UUID. Rotation at 4 MB. |
| Diagnostic exports | The desktop report carries version, OS, Java presence and profile count only. The CLI report adds a scrubbed log tail: paths rewritten to `<HOME>`, tokens and JWTs removed, and now the player name and UUIDs. |
| Network | Compile-time allowlist (an enum with no "other host"); CurseForge only with the user's own key; Modrinth per action; the update check refuses before opening a socket. No telemetry anywhere. |
| Downloads | Hash-verified against the source's published digest (SHA-1 for Mojang and Modrinth); stored by content hash. |
| Paths and archives | Downloaded mods are stored by hash and placed through a containment check that rejects unsafe names; archive extraction and path handling covered by the fuzz and failure-injection suites. |
| Third-party mods | A Fabric mod runs with the game's full permissions — inherent to modding, stated in the threat model. OptiFine is never downloaded or bundled (enforced by a test). |

No token, credential or identifying value was printed, exported or committed in
this pass.

## 7. Manual procedures (cannot be automated here)

**A. Clean machine, first sign-in (clears P0-1, P1-3, P1-4).** On a Windows PC
or VM that has never run Fathom: install the release build (built with
`FATHOM_RELEASE_AZURE_CLIENT_ID`); sign in with a Microsoft account that owns
Minecraft; close and reopen (still signed in); add a second account and switch;
sign out and reopen (signed out); sign in; press Play (first download completes,
Fathom's title screen appears); join a singleplayer world and a public server.
Record each step pass/fail.

**B. Version upgrade.** Build N, install, play, then build N+1 with a higher
version and run its installer over N while the launcher is open. Expect: N+1
running, still signed in, profiles and worlds intact. Then try N's installer
over N+1: it should refuse (downgrades disabled).

**C. Interrupted first download.** During the first Play, disconnect the
network halfway through the asset download; reconnect and press Play again.
Expect it to resume and verify, not restart from zero or fail permanently.

**D. Real servers.** On two or three popular 1.8.9 servers: Scoreboard module
on and off (lines identical), clickable chat with timestamps and folding,
server switching (nothing from the first server shown on the second), Ping.

The external tester checklist is `docs/beta-tester-checklist.md`.

## 8. Automated regression suite

| Requested area | Coverage |
| --- | --- |
| Module configuration persistence | `EditorTests` (restart, profile switch, repair), `BaselineTests`, `ProfileTests`; in game: `ModuleSelfTest` restart pair (8 checks), `EditorSelfTest` restart pair |
| Module registry lifecycle | `ModuleTests`, `RegistryTests`, `FeatureTests` |
| Keybind conflicts | `EditorTests.aSharedKeyNamesEverythingElseOnItOnce` (new — the page's real rule) |
| HUD position and scaling | `EditorTests` (flow, layout at four GUI sizes), `HudTests`; in game: editor drag/resize/anchor/undo |
| Profile isolation | `EditorTests.switchingProfilesBackAndForthLosesNothing`, profile contract (Rust ↔ Java) |
| Resource cleanup | in game: GL state checked after every HUD pass, across resource reloads, with every module on |
| Settings migration | Rust `loadout::migrate` tests; Java `ProfileTests`; widget-value repair on both sides |
| Installer / update integrity | `update_chain` (22), update module (33), `failure_injection`; installer verified by hand (§5) |
| Launcher → game handoff | `session.json` shared-fixture contract (new), profile contract, `launch_pipeline` |

Results this pass (final build): 17 Java core suites, 421 checks, all pass; the
Rust workspace, 1,236 tests in 19 binaries, all pass (8 ignored by design because
they reach the network or start a real game; 5 of those were run separately and
pass, and the Microsoft sign-in one needs an application ID). In game:
`ModuleSelfTest` 67/67 and restart 8/8 with and without OptiFine on the local
server, `EditorSelfTest` 19/19 and 3/3 — these on the 2026-10-11 game build;
the game-side changes since (the Mods page's conflict warning now calls
`KeyConflicts`) are covered by the core suites, not re-run in game.

## 9. Performance regression

**Method.** The repository's multiplayer frame profiler (`tools/mp-bench`,
`-Dfathom.benchmark=view`): production launch with OptiFine HD U M5, a local
1.8.9 server with forty bots, nine scripted scenes, four rounds, one 3-second
window per arm per round with the arm order alternating, settle checks before
every window. Two arms interleaved in the same run: **normal** (the shipped
build) and **fathom-off** (Fathom's hooks bypassed, the same game otherwise).
Report: `minecraft/fathom-optimizations/run-mp/logs/fathom-mp-2026-10-12-14-52-15.txt`.
Every window valid: 0 frames rejected, 0 unfocused, 0 GC pauses.

| Scene | normal mean | p99 | fathom-off mean | p99 | Fathom's cost (mean) | p99 better with |
| --- | --- | --- | --- | --- | --- | --- |
| Singleplayer | 0.894 | 1.778 | 0.819 | 1.743 | +0.075 ms | off |
| Server, alone | 0.860 | 1.888 | 0.806 | 1.911 | +0.054 ms | Fathom |
| 5 players | 0.937 | 1.654 | 0.957 | 1.909 | −0.021 ms | Fathom |
| 40-player lobby | 2.409 | 3.972 | 2.404 | 3.904 | +0.005 ms | off |
| 20-player combat | 2.193 | 3.458 | 2.135 | 3.480 | +0.059 ms | Fathom |
| Combat + particles | 2.628 | 4.470 | 2.512 | 4.551 | +0.116 ms | Fathom |
| 40 nametags | 1.830 | 2.898 | 1.861 | 2.991 | −0.031 ms | Fathom |
| HUD-heavy (sidebar, tab, chat) | 2.989 | 4.426 | 3.019 | 4.626 | −0.030 ms | Fathom |
| Block changes | 1.062 | 1.961 | 0.997 | 1.883 | +0.065 ms | off |

ms per frame. Client-thread allocation is lower with Fathom in the crowded
scenes (nametags 18.6 vs 27.9 MB/s, lobby 19.6 vs 25.9) and similar elsewhere.

**Reading.** No regression found. In light scenes Fathom costs 0.05–0.08 ms a
frame — its HUD widgets and hooks doing their work — which is about 7% at the
1,100 frames a second those scenes run at here and under 2% at 144–240. In
crowded scenes it is neutral to slightly faster, and its 99th-percentile frame
is better in six of the nine scenes. Fathom's in-game self-test adds: the HUD
pass allocates 140–260 bytes a frame, every widget draws in under 0.006 ms, and
GL state is clean after every pass, across resource reloads and with every
module on.

**What this cannot show.** Absolute frame times today are 30–60% higher than
the committed baseline of 2026-10-06 (`benchmarks/reports/fathom-mp-2026-10-06-18-41-41`)
in **both** arms, so the machine was in a slower state today (the bypassed arm
moved as much as the normal one). A regression that affected both arms equally
— a change on the vanilla path — cannot be ruled out without re-running the
10-06 build, which is not kept. Memory: the run is configured with a fixed
4 GB heap and no GC pause occurred in any measured window; resident memory was
not tracked. The overhead threshold in `docs/performance/regression-thresholds.md`
(Fathom with every optional feature off against Legacy Fabric alone, the
`cpu-ceiling` scenario) was not re-measured in this pass.

The benchmark server crashed on the first attempt (a Windows file-sharing race
in the harness's control file, not in Fathom); fixed and re-run.

## 10. Ready to announce, and not yet implemented

**Ready:** the twenty modules in `docs/v1-modules.md` with the feature lists
there; the HUD editor; Fathom Profiles shared between launcher and game;
OptiFine compatibility (user-installed); the performance layer.

**Ready, new in this pass:** the third-party mod browser (Mods → Browse) for
Modrinth, with per-build compatibility verdicts; see `docs/mod-browser.md`. Its
CurseForge half is implemented but off: Fathom holds no CurseForge API key.
Installed-mod management — earlier builds and offline restore, launch history,
"What changed?" recovery, a launch without external mods, and launch-time
dependency checks — is described in `docs/mod-management.md`.

**Not implemented:** automatic updates (mechanism done, signing key absent);
code signing; Forge profiles (planned as a separate opt-in later); and the
features `docs/v1-modules.md` lists as deliberately left out.
