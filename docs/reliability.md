# Building, running, and recovering

Developer documentation for the reliability milestone: how to build the thing a
user double-clicks, where its data lives, and what happens when something goes
wrong.

---

## 1. Building

### Debug

```bash
cargo run -p launcher-ui
```

This compatibility command starts the canonical Tauri/Svelte launcher and
embeds the current `desktop/dist`, just like a packaged build. For hot reload,
run `tools/dev.ps1`. It asks Windows for an available ephemeral loopback port,
starts Vite with strict port ownership, and lets Tauri wait for an HTTP
readiness response before creating WebView2. Ordinary Cargo runs use embedded
assets and therefore cannot show a localhost error.

### The release executable

```bash
cargo build --workspace --release
```

Produces, in `target/release/`:

| File | What it is |
|---|---|
| **`fathom-desktop.exe`** | The canonical launcher binary; packaging copies it as `fathom.exe`. |
| `fathom-ui.exe` | Compatibility binary built from the same canonical application. |
| `fathom-cli.exe` | Headless companion: `doctor`, `recover`, `diagnostics`. |
| `fathom-release.exe` | Offline release signing. Never shipped to users. |

`fathom.exe` is a `WINDOWS_GUI` subsystem binary, so double-clicking it opens a
window and **no console appears behind it**. Verified by reading the PE header
rather than by looking at it:

```powershell
$b = [IO.File]::ReadAllBytes("target\release\fathom.exe")
$pe = [BitConverter]::ToInt32($b, 0x3C)
[BitConverter]::ToUInt16($b, $pe + 0x5C)   # 2 = GUI, 3 = console
```

### The Windows installer

```powershell
pwsh tools/build-installer.ps1
```

This is the sole distributable release pipeline. It installs locked frontend
dependencies on the build machine, builds the current frontend and Minecraft
client, embeds the frontend into the Tauri executable, bundles the current
client jar, and creates a per-user NSIS installer in `dist/installer`.

The installer registers a normal uninstall entry, creates a Start Menu
shortcut, and bootstraps WebView2 when Windows does not already provide it. It
does not require elevation. `tools/package-portable.ps1` remains a developer
diagnostic artifact and is not the distribution format.

### What a user needs installed

Nothing. No Rust, Cargo, Node, npm, Gradle, or source checkout. Java is only
needed to run Minecraft, and the launcher finds or fetches it. No Visual C++
redistributable: the MSVC runtime is statically linked by default for this
target.

---

## 2. Where things live

**Nothing is relative to the working directory.** A shortcut, a Start-menu entry
and a taskbar pin each hand the process a different one, and a shortcut with a
blank "Start in" can get `C:\Windows\System32`. `DataPaths::discover` resolves an
absolute per-user directory and everything hangs off that.

| Concept | Windows | Deletable? |
|---|---|---|
| Install directory | `%LOCALAPPDATA%\Fathom Client` | Replaced by update/uninstall |
| **Application data** | `%LOCALAPPDATA%\Fathom\data` | **No** |
| Profiles | `…\data\profiles\<id>` | No — worlds live here |
| Shared artifacts | `…\data\shared` | Re-downloadable, expensive |
| Mod store | `…\data\mods` | No — holds user-supplied jars |
| Trash | `…\data\trash` | Only by explicit purge |
| **Cache** | `…\data\cache` | **Yes** |
| **Temporary** | `…\data\tmp` | **Yes** |
| Logs | `…\data\logs` | Yes, at a cost to diagnosis |

The different `Fathom Client` and `Fathom` roots are intentional: Tauri removes
the former during uninstall, while worlds, profiles, accounts, and downloads in
the latter survive updates and uninstall.

The distinction that earns its keep is **shared artifacts vs cache**. Both are
re-downloadable, so an early version had them as one thing — but deleting
`shared/` costs 139 MB and several minutes, and deleting `cache/` costs nothing.
Conflating them is how a launcher ends up with a "clear cache" button that
re-downloads the game.

`install_dir()` exists so that nothing writes there. Installed files are
read-only application resources; state goes in application data.

### Logs

`%LOCALAPPDATA%\Fathom\data\logs\launcher.log`, rotated at 4 MB with one
previous kept.

A release build is windowed, so `stderr` is a handle nobody reads: without a
file, every `tracing::warn!` would go nowhere. Debug builds get **both**.

Third-party crates log at `warn`, ours at `info`. That is not tidiness — the
first version used a global `info` and `winit` wrote its entire
`WindowAttributes` struct, forty lines per window, into the file users are meant
to attach to bug reports. `FATHOM_LOG` overrides everything.

---

## 3. Startup recovery

`recovery::startup::scan`, run by `Boot::run` before the window opens.

**It must be fast**, because it runs on every launch of a double-clicked
application. Verifying the installation properly means hashing 139 MB across 800
files; doing that every start would make the launcher feel broken in order to
check whether it is. So the scan uses `stat` and directory listings only, and
full verification happens when there is evidence of a problem or when asked. A
test asserts it stays under 3 seconds over ~55 MB.

Every finding declares its own disposition:

| | |
|---|---|
| **Cleaned up** | Removed already. Only for things that are *definitionally* worthless: a `.partial` is an incomplete download and can never be anything else; a file in `tmp/` belongs to a run that has ended. |
| **Needs attention** | Found, not touched, reported. Anything that might be, or might contain, something the user cares about. |

When in doubt it is the second. A launcher that quietly deletes on startup is a
launcher nobody can trust with their worlds.

What it checks, in order: interrupted launcher updates (first — it can move the
executable), abandoned update staging, `.partial` downloads, the temporary
directory, then every profile's load outcome and any orphaned profile directory.

Run it by hand: `fathom-cli recover scan`.

---

## 4. Operation locking

`ops::Operations`, a coordinator rather than scattered booleans. The conflicts
it prevents span several modules and several seconds — *deleting a profile while
Minecraft is running out of it*, *clearing the cache while a download depends on
it* — and a boolean in each module cannot see the others.

Scopes, in canonical order:

```
GlobalUpdate  →  SharedInstallation  →  JavaRuntime  →  ModStore  →  Profile(id)
```

`Profile` is per-id, so two profiles never block each other.

**Deadlock is structurally impossible.** An operation declares every scope it
needs up front and `begin` takes them *all or none, without ever blocking*.
There is no moment where a caller holds part of a set and waits for the rest,
which is the only way a deadlock forms. The ordering above is still defined and
enforced by sorting, because it makes the invariant testable and keeps the
reasoning simple if a blocking variant is ever added.

A refusal is a `Busy` naming the scope, the holder, and how long it has been
running — "please try again" without saying what to wait for is not help.

**Recovery code respects these locks.** Every `RecoveryService` method takes its
lease before touching the filesystem. A repair that ran while the game was
starting would delete a jar the JVM had just been told about.

A `Lease` releases on drop, including while unwinding, so a panic mid-repair does
not leave the launcher permanently refusing to repair.

---

## 5. What "Clear cache" actually removes

Something is cache **only if deleting it costs nothing but time the launcher was
going to spend anyway.** Not "it can be re-downloaded" — the game artifacts can
be re-downloaded, and that costs a user their evening.

**Removed:**

- `.partial` downloads — incomplete by definition, never valid, never loaded
- cached remote metadata — re-fetched on the next check
- expired cached manifests — already refused as stale before this runs
- the temporary directory — scratch for one run
- obsolete update staging — unverified bytes nothing is going to install

**Never touched:** worlds, screenshots, resource packs, `options.txt`, profile
configuration and metadata, accounts and credentials (which are in the OS
credential store, not on disk here at all), user-supplied mod jars, the shared
game artifacts, and the profile trash.

### Why the mod store is not cache

`<data>/mods/<2hex>/<sha1>.jar` is content-addressed, so it looks like cache. It
is not. Most jars in it are **user-supplied** — the user picked a file off their
disk — and `mod-policies.md` §7 means there is often **no authorized source to
re-acquire from**. Deleting one destroys the only copy the launcher has, and
possibly the only copy anywhere. A jar in the store is also *referenced* by
profile configuration, so removing it turns a working profile into a broken one.
That is a change to the user's setup, not a cleanup.

`fathom-cli recover cache` shows what would go; `--yes` does it.

---

## 6. Recovery flows

All in `launcher_core::recovery`, all reachable from the CLI so they can be run
without the GUI. A recovery path reachable only from a button is a recovery path
nobody has run.

| Flow | Where | Idempotent |
|---|---|---|
| Repair Minecraft installation | `install::InstallationVerifier` + `repair` | Yes |
| Repair managed Java | `recovery::java` | Yes |
| Validate profile | `profiles::validate` | Yes (read-only) |
| Repair loader | `loaders` + install repair | Yes |
| Identify missing mods | `mods::resolve` | Yes (read-only) |
| Detect corrupt mod hashes | `mods::store` | Yes (read-only) |
| Clear caches | `recovery::cache` | Yes |
| Recover interrupted downloads | `install::download::sweep_partials` | Yes |
| Recover interrupted updates | `update::install::Installer::recover` | Yes |
| Recover profiles from trash | `profiles::ops` + `RecoveryService` | Yes |
| Export diagnostics | `recovery::diagnostics` | Yes |

Idempotence is a property of how they are written, not a claim: each asks what
is wrong *now* and fixes only that, so a second run finds nothing and does
nothing. None appends, renames in a cycle, or assumes a starting state.

### Managed Java, and the line that matters

| Kind | Who owns it | What we may do |
|---|---|---|
| Managed — under `<data>/java` | The launcher | Validate, delete, re-acquire |
| External — a system JDK, or a path the user chose | The user | Look at it |

A launcher that "helpfully" repairs a broken external Java has modified a system
JDK other programs depend on. So nothing outside `<data>/java` is ever written
to, and when a runtime the user *chose* becomes invalid it is **reported**, not
silently swapped for another one on the system.

The containment check runs twice: once on ownership, and again — canonicalising
— immediately next to the `remove_dir_all`, because a symlink at
`<data>/java/jre-8` pointing at `C:\Program Files\Java` would pass a lexical
test and take a system JDK with it.

No managed runtime exists yet; `java_runtime.rs` finds Java, it does not download
it. The managed paths are written and tested against a fabricated runtime,
because the interesting logic — incomplete extraction, wrong architecture, wrong
version — is the same whoever put the files there.

---

## 7. Crash safety

`fs_safety::atomic_write`: write to a temporary sibling, flush, `sync_all`,
rename. A crash leaves either the old content or the new, never a truncated
file.

The temporary is opened with `create_new` — `O_EXCL` / `CREATE_NEW` — so anything
already at that path makes the open *fail* rather than be followed or truncated.
That is the security control; the nanosecond stamp in the name only avoids
collisions between concurrent writers. (See `THREAT_MODEL.md` §10a for the
arbitrary-file-overwrite this closed.)

Profiles keep one **last-known-good backup**. `ProfileStore::load` falls back to
it when the live copy will not parse, and the startup scan reports when that
happened — a silent recovery is worse than a reported one, because the backup
may be slightly behind.

---

## 8. Diagnostics and what protects the user

`fathom-cli diagnostics [--out <file>]`, or the export in the application.

**Never uploaded.** There is no endpoint. `Report::to_text` returns a `String`;
writing it somewhere is an act the user takes.

Two defences, not one:

**Construction.** The report is built from a fixed set of fields — no "dump
everything" path, no environment capture, no directory walk. The question is
never "did we remember to exclude X"; X has to be added on purpose to appear.

**Scrubbing.** Every string still goes through `logging::scrub` on the way in,
and the assembled document is scrubbed again on the way out, because log
excerpts are the most useful part of a report and a log line can contain
anything.

**Path normalisation is a privacy control, not tidiness.**
`C:\Users\jane.doe\AppData\Local\Fathom\profiles\main` contains the user's real
name, and paths appear in almost every error message a launcher produces.
`Normalizer` rewrites known roots to `<APP_DATA>`, `<INSTALL>`, `<HOME>` and
`<TEMP>`, **longest first** — `<APP_DATA>` is inside `<HOME>`, and replacing the
shorter root first would leave `<HOME>/AppData/Local/Fathom/…`, which still
identifies the account on a shared machine.

Included: launcher version and build, OS and architecture, Java version and
vendor, Minecraft and loader versions, enabled mod ids, the optimization module
version, profile validation state, in-progress operations, and the tail of the
launcher log.

Never included: access, refresh, Xbox, XSTS or Minecraft tokens; `Authorization`
headers; the Microsoft email; the account UUID; machine identifiers, HWID or any
device fingerprint; credential-store contents; paths outside the normalised
roots.

`diagnostics::audit` takes a last look before the report is emitted, and the CLI
**refuses to write a report that fails it**. The audit checks what *follows* a
marker rather than the marker itself — `--accessToken <redacted>` is what a
correctly scrubbed report looks like.

---

## 9. Errors

`problem::Problem`, carrying category, a user-safe summary, technical detail,
whether retry is worthwhile, and which repair would help.

The reason it is a type and not a string: **a release build has no console**, so
an error that only exists as terminal output is one the user experiences as
nothing happening. `App::new` used to `eprintln!` and `exit(1)` when the data
directory could not be opened, which in a windowed build meant double-clicking
an icon and watching nothing occur. It now opens a small failure window that
says why, with the same `Problem` values every other path produces.

Every string is scrubbed on construction, because a `Problem` is the one type
designed to be displayed and exported.

---

## 10. Testing

```bash
cargo test --workspace
cargo test -p launcher-core --test failure_injection
```

`failure_injection.rs` breaks one thing at a time and asserts the launcher
notices, says something usable, and **does not lose the user's data** — the
third being the one that matters. A launcher that reports a problem and deletes a
world has still failed.

Covered: corrupt and truncated JSON, interrupted atomic writes, orphaned
temporaries, unwritable targets, missing and half-extracted Java, broken external
Java, cache clearing under every kind of user data, concurrent operations
including a 16-thread race, deletion outside the trash, restore conflicts,
symlink escape, stale update staging, abandoned partials, startup scan
performance, working-directory independence, and diagnostics under a broken
installation.

### Release validation

Done on Windows 11 26200 for this milestone, not assumed:

1. `cargo build --workspace --release`
2. PE subsystem read from the header: `WINDOWS_GUI`, `x64`
3. `fathom.exe` copied to `C:\Users\jb\FathomPortableTest`, outside the repository
4. launched with working directory `C:\Windows\System32`, `UseShellExecute=false`
5. window opened, titled `Fathom — Play`
6. data resolved to `%LOCALAPPDATA%\Fathom\data`; the full tree was created
7. log written to `…\data\logs\launcher.log`
8. `fathom-cli doctor`, `profiles`, `update status` and `--self-check` all run
   from `C:\Windows\System32` and resolve the same absolute paths
9. no `.bat`, `.cmd`, `.ps1`, terminal or development environment involved

**macOS and Linux are untested.** The code is cross-platform and the paths
module handles them, but nothing has been run there, so no claim is made.
