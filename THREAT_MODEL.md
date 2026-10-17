# Threat Model

Scope: the launcher process, its data directory, and the JVM it starts.
Out of scope: the security of Minecraft itself, of the servers you join, and of
mods you choose to install.

Each threat below states what an attacker gets if it works, what stops it, where
that code lives, and — where it applies — what is still open. "Mitigated" means
there is code and a test. "Partial" means the design is right but something is
missing. "Accepted" means we cannot fix it and say so.

---

## Trust boundaries

```
   you  ->  the launcher UI                        trusted input
Microsoft/Mojang responses  ->  network module     UNTRUSTED
Mojang version metadata     ->  install planner    UNTRUSTED (contains URLs, paths, hashes)
natives jars                ->  zip extraction     UNTRUSTED (contains arbitrary paths)
settings.json / profile.json ->  config loader     UNTRUSTED (may have been edited or tampered with)
mod jars you install        ->  the JVM            UNTRUSTED, and unconstrained once running
```

The important line is the third: metadata we download is not trusted just
because it came from a Mojang hostname over TLS.

---

## 1. Stolen Microsoft refresh token

**Impact.** The refresh token is the only long-lived secret. With it an attacker
can mint Minecraft access tokens and play as you until you revoke it. It does not
grant access to your Microsoft account generally — the scopes are
`XboxLive.signin offline_access`.

**Status: partial.**

- It is the **only** thing persisted. No access token, no XSTS token, no
  entitlement JWT, no password ever touches disk. `credentials.rs`,
  `auth::remember_session`.
- It lives in the OS credential store: DPAPI-backed Credential Manager, Keychain,
  or Secret Service. A compromised `settings.json` leaks nothing.
- **No plaintext fallback.** If no credential store exists, the launcher says so
  and keeps the token in memory for that session. `EphemeralCredentialStore`,
  and `best_available()` probes with a real write before trusting the store.
- It is a `Secret`, so `Debug` and `Display` redact and it cannot reach a log
  through a derived `Debug`. Test: `nested_in_a_derived_debug_struct_it_still_redacts`.
- Sign-out deletes it. The user can also revoke it from their Microsoft account
  page, which works even if the machine is gone.

**Open.** An attacker running as you can read the credential store, because you
can. That is the OS's boundary, not ours. Token binding to a device key would
reduce this, and Microsoft's consumer OAuth does not offer it.

---

## 2. Malicious mod

**Impact.** Total. A mod jar is Java code inside the game process, with your
privileges: your files, its own network connections, your access token from the
command line.

**Status: accepted, with harm reduction.**

We cannot sandbox it. The JVM has no meaningful sandbox for this, and pretending
otherwise would be worse than saying so.

What we do instead:

- **Nothing is ever installed silently.** The only installation path is
  `install_from_file`, driven by a file picker. There is no
  browse-and-install flow, and `mod-policies.md` §7 lists the six conditions one
  would have to meet before there is.
- Every jar is listed with its size and SHA-1 so you can check it against what
  the author published. `mods::InstalledMod`.
- Disabling renames rather than deletes, so nothing is destroyed by accident.
- Removal re-checks containment rather than trusting the path on the struct:
  `mods::remove` refuses anything outside the profile's mods directory even if
  an `InstalledMod` was constructed elsewhere. Test:
  `removal_refuses_a_path_outside_the_profile`.
- `PRIVACY.md` §5 tells the user plainly that a mod they install is a mod they
  are trusting.

---

## 2a. A mod loader's own artifacts

**Impact.** A loader library goes on the classpath and runs with the game's
privileges. Substituting one is arbitrary code execution, exactly like
substituting a game library (§5), except the metadata comes from a third party
rather than from Mojang.

**Status: mitigated.**

- **Every artifact needs a hash before it is fetched.** Legacy Fabric's metadata
  declares SHA-1 for each shared library; the loader jar and mappings get theirs
  from the repository's `.sha1` sidecar. **No hash means no download** —
  `LoaderError::UnverifiableArtifact`, not a warning.
- **Repository URLs are not trusted.** They arrive inside a downloaded document
  and go through `descriptor_from_url`, so a library pointed at an unlisted host
  is refused.
- **Redirects are re-checked per hop**, which matters here specifically:
  `maven.legacyfabric.net` redirects every artifact to `repo.legacyfabric.net`.
  Both are allowlisted by name; an unlisted redirect target is a refused
  download.
- **Maven coordinates cannot become traversing paths.** A coordinate is refused
  unless it is exactly three non-empty parts, and the resulting path still goes
  through `ArtifactCache::library_path`.
- **Only client and common libraries are read.** The metadata also lists
  `server` and `development` sets; pulling development libraries onto a client
  classpath would change what runs, so those fields are not deserialised at all.
- **No installer is ever executed.** Forge ships an installer jar whose job is to
  run code and write files; Fathom downloads it, checks its SHA-1, and reads only
  `install_profile.json` out of it as a zip, then fetches each artifact itself
  with a hash (`loaders/forge.rs`).
- **Only Fathom's own Forge build goes ahead of Forge.** Under Forge the launcher
  puts one file in front of the loader's class path, and adds one tweaker
  (`org.spongepowered.asm.launch.MixinTweaker`, a constant). That file is the
  installation's `fathom-forge` entry -- placed by `lay_out_mods` and checked by
  `verify_placed` like the Fabric client -- and never another mod's jar, whatever
  it is named (`fathom_client::forge_client_entry`). Going first lets it replace
  ASM for the whole game, which is why the jar it is allowed to be is the one the
  launcher shipped and pinned by hash; its one change to ASM is recorded in
  `ATTRIBUTION.md`.

Tests: `a_library_without_a_hash_is_refused_rather_than_downloaded_unverified`,
`a_library_pointed_at_an_unlisted_host_is_refused`,
`a_malformed_coordinate_is_refused_rather_than_guessed_at`,
`a_traversing_coordinate_cannot_produce_a_traversing_path`,
`development_and_server_libraries_are_not_read`,
`legacy_fabric_resolves_against_its_own_metadata` (live).

---

## 3. Path traversal

**Impact.** Writing outside the data directory: overwriting a user's files,
dropping an executable into a startup folder.

**Status: mitigated.**

`fs_safety::contain` is the single chokepoint. It rejects, per path segment:

- `..` in any form, including `..\` and mixed separators — zip entries use `/`
  but a hostile archive can use `\`, so both are normalised first;
- drive-relative references and NTFS alternate data streams (anything with `:`);
- Windows device names (`CON`, `NUL`, `COM1`, `LPT9`, and with any extension) on
  **every** platform, because an archive is portable and so should our refusal
  be;
- NUL bytes and empty names.

After per-segment filtering it re-checks the result lexically with
`is_lexically_inside`, which also rejects the sibling-prefix case
(`/data/main` must not contain `/data/main-evil`).

Applied at: zip extraction, mod filenames, library paths from Mojang metadata,
asset hashes (`asset_object_relative` validates 40 hex characters, so
`../../etc/passwd` cannot become a path), and profile ids.

For the install engine specifically, `ArtifactCache` is the only thing that
decides where an artifact may land, and every accessor runs the untrusted name
through `contain`. Because the resolver builds the full target set before any
request is made, a traversing path is refused at resolution time rather than at
write time — the download is never attempted at all. Asset names may contain
spaces and non-ASCII characters; those affect only the display name, since
storage is keyed by hash.

Tests: `parent_traversal_is_rejected_in_every_form`,
`absolute_and_drive_relative_paths_are_rejected`,
`windows_device_names_are_rejected`, `lexical_containment_matches_expectations`,
`a_library_path_that_traverses_is_refused`,
`an_asset_with_a_malformed_hash_is_skipped_not_fetched`,
`a_traversing_library_path_cannot_escape_the_cache`,
`an_asset_hash_that_is_a_path_is_refused`,
`every_resolved_target_is_inside_the_shared_cache`,
`asset_names_with_spaces_and_unicode_do_not_affect_storage`,
`the_whole_lifecycle_works_under_a_path_with_spaces_and_unicode`.

---

## 4. Malicious archive contents

**Impact.** Zip-slip writing outside the extraction root; a decompression bomb
filling the disk; a natives jar dropping an executable next to the `.dll`.

**Status: mitigated.**

`fs_safety::extract_zip`:

- every entry path goes through `contain`, **and** we honour zip-rs's own
  `enclosed_name` refusal;
- symlink entries are **refused, not followed** — detected via `S_IFLNK` in the
  entry's unix mode;
- limits on entry count, per-entry size and total size;
- entries are read through a `take(limit)` rather than trusting the declared
  size, because the central directory is attacker-controlled and can understate
  the real stream length;
- a caller-supplied filter decides what is wanted at all. For natives,
  `Library::should_extract` honours the version JSON's `extract.exclude` **and**
  applies our own allowlist: only `.dll`, `.so`, `.dylib`, `.jnilib`. A tampered
  natives jar cannot deliver a `.bat`, a `.cmd` or a second `.jar`.

Tests: `zip_slip_entry_is_refused`, `entry_count_limit_is_enforced`,
`total_size_limit_stops_a_decompression_bomb`,
`extraction_writes_only_filtered_entries_inside_dest`,
`extraction_honours_exclude_and_our_own_allowlist`,
`archive_traversal_during_native_extraction_is_refused`,
`only_shared_libraries_come_out_of_a_natives_archive`.

---

## 4a. Windows device names, trailing dots and trailing spaces

**Found by the security review of 2026-08-27.**

**Impact.** An archive entry or a mod file name that resolves to a hardware
device (`CON`, `NUL`, `COM1`) rather than to a file. Writing to one can hang or
misbehave; it is a denial of service rather than an escape.

**Status: mitigated.**

Windows strips trailing spaces and dots from a path component before resolving
it, so `CON `, `CON.` and `CON . . ` all name the console device. The check took
the text before the first `.` and compared that against the reserved list, which
caught `CON.txt` and `CON.` but **not `CON `** — a trailing space is not a dot.

`is_reserved_device_name` now strips trailing spaces and dots before comparing,
and the check runs on every platform: an archive is portable, so extracting a
`CON ` entry on Linux and copying the tree to Windows later should not be how
this surfaces.

### What was measured rather than assumed

The obvious follow-up worry is that the same stripping turns `.. ` or `...` into
`..` and walks out of the root, which `contain`'s exact match on `".."` would not
catch.

**It does not.** Measured on Windows 11 26200 with `std::fs`, writing through
each candidate component and checking where the bytes landed: only the exact
`..` traverses. `".. "`, `"..."`, `".. ."`, `"..  "` and `"...."` are all
rejected as names that do not exist — the stripping happens, but the result is
not re-interpreted as a relative directory reference.

That is a fact about the current Windows implementation, not a guarantee, so
`trailing_dots_and_spaces_do_not_turn_into_a_traversal` pins it. If it ever
changes, it fails there rather than in someone's profile directory.

Tests: `device_names_dressed_up_with_trailing_spaces_and_dots_are_rejected`,
`trailing_dots_and_spaces_do_not_turn_into_a_traversal`,
`contain_never_returns_a_path_outside_its_root` (generative, 200,000 inputs).

---

## 5. Tampered downloads

**Impact.** Substituting a modified client jar or library — arbitrary code
execution inside the game.

**Status: mitigated.**

- Every artifact is verified against the SHA-1 Mojang publishes **before it is
  written anywhere**. A mismatch discards the bytes; nothing lands in the shared
  cache. `downloads::fetch`.
- Declared size is checked too, so a truncated or padded response fails early.
- The version JSON is verified against the SHA-1 in the version manifest before
  being cached.
- Writes are atomic: temp file, fsync, rename. A crash leaves the old file or
  the new one, never half of either.
- **Metadata URLs are not trusted.** Absolute URLs inside version JSON go
  through `descriptor_from_url` and are refused unless the host is on the
  allowlist. This is the mitigation that matters most, because it means a
  tampered manifest cannot even point us at an attacker's host to begin with.
- **Redirects cannot launder a host.** The HTTP client follows no redirects on
  its own; the network module resolves each `Location` itself and re-checks the
  next hop against the allowlist. Previously a tampered document could name an
  allowlisted host that 302s to an attacker's, and only the first hop would have
  been checked. That hole is closed. See `NETWORK.md` §6.
- **A bad hash is fatal and is never retried.** Transient network failures are
  retried up to three times; `HashMismatch` and `SizeMismatch` are explicitly
  not transient. This matters because retrying corrupt bytes is the usual way a
  launcher ends up accepting them — an attacker who can intermittently tamper
  with a response would otherwise just need to win once.
- **Nothing partial is ever mistaken for verified.** Downloads are written to
  `<name>.part` and renamed only after the hash matches, so an artifact present
  at its real path is proof that it verified. Leftover partials from a killed
  process are swept before a repair.
- **Repair discards before it refetches.** Corrupt bytes are deleted rather than
  left in place, so a failed repair cannot leave a file that a later run counts
  as present.

Tests: `a_tampered_manifest_cannot_redirect_a_download_off_the_allowlist`,
`verification_recognises_a_good_file_and_rejects_a_tampered_one`,
`every_planned_download_targets_an_allowlisted_host`,
`corrupting_one_artifact_is_detected_and_named`,
`a_partial_is_never_counted_as_the_artifact`,
`an_interrupted_download_leaves_no_usable_artifact`,
`repair_discards_corrupt_bytes_so_they_cannot_be_mistaken_for_present`.

**Open.** SHA-1 is collision-broken. We use it because it is what Mojang
publishes; there is no stronger hash available for these artifacts. The
practical exposure is low — a chosen-prefix collision against a specific jar
whose contents an attacker does not control is a research-grade attack — but it
is a real limitation, not a footnote.

---

## 6. Compromised update infrastructure

**Impact.** The worst case in this document. An update channel is a code
execution channel: whoever controls it controls every installation.

**Status: mitigated by design and implemented, with the trust anchor
deliberately absent.** See the last subsection — that absence is a considered
state, not an unfinished one.

Full design and rationale: `docs/research/update-security.md`.

### The chain

```
  ROOT_KEYS        compiled into the binary; 2-of-N, offline
     | Ed25519, threshold, domain-separated
  root.json        names the release keys and their threshold
     | authorises
  release.json     per component: version, os, arch, sha256, size, path
     | binds by SHA-256
  artifact         from a CDN we do not trust
     | staged, re-hashed from disk, swapped, validated
  installed        with the previous copy still on disk
```

**HTTPS is transport, not trust.** Every gate that decides whether bytes get
executed is a signature or a hash, and none depends on the connection.

### The compromise model, and what each attack meets

| Attack | What stops it |
|---|---|
| **Compromised CDN** | Artifacts are bound by SHA-256 inside signed metadata. Different bytes fail the hash. |
| **Compromised DNS** | The imposter holds no signing key. The allowlist means it must at least be a name we know. |
| **Compromised release host** | The same: the host serves files it could not have produced. It never holds a key. |
| **Replayed old release** | Monotonic version floors on both documents, plus expiry, plus a component-version floor at the installed version. |
| **Partial download** | Declared size checked before the hash, so a truncation is reported as one. |
| **Corrupted artifact** | SHA-256 over the received bytes, then **again from disk** before the swap. |
| **Interrupted replacement** | A journal, a retained backup, and a recovery pass that decides from what is on disk. |
| **Stolen release key** | Recoverable: the offline root signs a new document dropping it. Replaying the old root to reinstate it is a rollback, and refused. |

### Design decisions worth naming

- **Two roles.** Root keys are offline and used a few times a year; release keys
  do the daily work. One role would make a stolen key unrecoverable for everyone
  already installed.
- **Thresholds count distinct keys**, not signatures, or one compromised key
  satisfies a threshold of three by signing three times.
- **The signed bytes are the received bytes.** The payload is an opaque base64
  blob, verified before it is parsed, so there is no canonical-JSON problem to
  get wrong. The only parser touching unverified input is a two-field envelope.
- **Signatures are domain-separated by role**, so a signature made in one
  position cannot be presented in the other.
- **The known-good copy is never destroyed before the replacement is validated.**
  The old process supervises its own replacement and puts the previous version
  back if the new one will not start.
- **`ComponentId` has no `Other(String)`.** A third-party mod cannot be installed
  through this channel however the metadata is written.

### Privacy

A fixed-path `GET`. No hardware id, install id, username, account details, OS
build or query string. `User-Agent: Fathom/<version>` and nothing else — a fact
about the software, not the machine. Every client on a version sends identical
bytes.

### The anchor is empty, on purpose

`ROOT_KEYS` is empty, `update_channel_is_configured()` is false, and `check()`
refuses **before touching the network**: a launcher that cannot verify a
manifest must not fetch one, because displaying an unverified manifest is
presenting an attacker's text as ours.

There is no key because there is no key. The private half must be generated
offline and never touch this repository, and that has not happened. A
placeholder whose private half lives here would be indistinguishable from no
signature while looking like one; a placeholder whose private half does not
exist would make every update fail as if the network were down.

Nothing about the mechanism is stubbed — every path is implemented and tested
against injected keys, and `fathom-release` signs documents the real verifier
accepts.

### Remaining risk

**Power loss between the swap and a successful validation, where the replacement
also fails to start.** Nothing is running to notice, and the broken binary
cannot recover itself. The backup survives and `fathom-cli update restore` puts
it back, but that needs a person. Closing it needs a second binary or a service,
and a service is out of scope. The window requires a crash inside a few seconds
*and* a replacement that does not start; either alone is handled.

**The trust state file is unsigned** and a local attacker can reset the rollback
floors. Accepted: that attacker can already replace the binary, which is easier.

Tests: `crates/launcher-core/tests/update_chain.rs` (22), plus the `update`
module's own (33). `docs/research/update-security.md` §14 maps them to the
required cases.

---

## 7. DLL / native library loading

**Impact.** Hijacking the JVM's library search path to load an attacker's
native code.

**Status: partial.**

- `-Djava.library.path` points at the profile's own `natives/` directory, inside
  the data root.
- Only shared libraries are extracted there (§4), so the directory cannot
  accumulate scripts or jars.
- **Stale natives cannot survive a version change.** A stamp beside the
  extracted set records the version, platform and archive hashes; a launch
  re-extracts on any disagreement, and a missing stamp counts as disagreement.
  Without this, "a `.dll` is present" was the only available check, and that is
  equally true of a directory left over from a different version.
- **Eight variables are removed from the child's environment**, in three groups.
  The first was there already; the other two were added by the review of
  2026-08-27.
  - *JVM option injection*: `_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS`,
    `JDK_JAVA_OPTIONS`. The JVM prepends these to its own argument list, so
    `_JAVA_OPTIONS=-agentpath:...` is the §8a code-execution primitive arriving
    by a route that never passes validation.
  - *Dynamic-loader injection*: `LD_PRELOAD`, `LD_LIBRARY_PATH`, `LD_AUDIT`,
    `DYLD_INSERT_LIBRARIES`. Each loads a shared object into the process before
    `main`. These are unix mechanisms and this launcher's first target is
    Windows, but the code is cross-platform and clearing them costs a line each.
  - *Belt and braces*: `CLASSPATH`, which `-cp` already overrides. It is cleared
    so that the answer to "what classpath does the game get" is the plan and
    nothing else.
- **`PATH` is deliberately left alone.** The game and the JVM need a working
  environment, and a launcher that curates `PATH` breaks more than it protects.
  An attacker who can write to a directory on the user's `PATH` already has code
  execution without involving us.
- The launcher is invoked by its full path from a per-user directory.

**Open.** Windows' DLL search order still includes the application directory. If
an attacker can already write to your `%LOCALAPPDATA%`, they can plant a DLL —
but at that point they can also replace the launcher binary, so this is not the
weakest link. Not attempted: `SetDefaultDllDirectories`, or verifying signatures
on extracted natives (Mojang does not sign them).

---

## 7a. Stale native libraries surviving a repair

**Found by the security review of 2026-08-27.**

**Impact.** An unverified `.dll` left inside a profile that the launcher has
reported as clean.

**Status: mitigated.**

`NativeLibraryManager::clear` swept only the top level of the natives directory,
but the extractor writes whatever path an entry names — and `nested/OpenAL64.dll`
is a real entry in a real natives jar. A library from a previous version, or from
an archive that later failed verification, survived every repair in a
subdirectory.

The sweep is now recursive, depth-bounded, and uses `symlink_metadata` rather
than `is_dir`, so a symlink or junction placed in the natives directory is
skipped rather than descended into and emptied. Directories are removed only if
the sweep left them empty, so a foreign file is never taken with one.

**Not an exploitable hijack on its own.** The JVM's `java.library.path` search is
not recursive, so a stale nested `.dll` is not loaded. It is still an unverified
binary inside a profile the launcher called clean, which is reason enough.

Tests: `a_stale_library_in_a_subdirectory_is_removed_too`,
`the_sweep_does_not_follow_a_symlink_out_of_the_profile` (unix).

---

## 8. Arbitrary command injection

**Impact.** Turning a username, a path, or a JVM argument into extra
command-line arguments or shell commands.

**Status: mitigated.**

- **There is no shell.** `std::process::Command` with an argument vector. No
  string is ever handed to `cmd.exe` or `/bin/sh`.
- **Tokenise before substituting.** 1.8.9's legacy `minecraftArguments` template
  is split on whitespace *first*, then each token has `${...}` replaced. A
  player name of `evil --accessToken stolen --gameDir /etc` therefore becomes
  one argument. Doing it the other way round — substitute, then split — is the
  bug this ordering exists to prevent.
- Unknown placeholders are an **error**, not an empty string, so a malformed
  template fails loudly rather than launching with a half-built command line.
- User-supplied JVM arguments are rejected if they contain shell metacharacters
  (`& | ; \` $ > <` or a newline). We never invoke a shell, so their presence
  means either a misunderstanding or an attempt.
- Java is probed with a single `-version` argument; nothing user-controlled
  reaches that command line.

Tests: `a_hostile_player_name_cannot_inject_extra_arguments`,
`a_hostile_game_directory_cannot_inject_arguments_either`,
`an_unknown_placeholder_is_an_error_not_an_empty_string`,
`shell_metacharacters_are_refused`.

---

## 8a. JVM options as a code-execution channel

**Found by the security review of 2026-08-27. This was the most serious issue in
the codebase, and it was live.**

**Impact.** Arbitrary native code execution on the user's machine, triggered by
importing a profile someone else wrote. Full user-level compromise.

**Status: mitigated, two independent layers.**

### What was wrong

§8 above says there is no shell, and that is true of everything the launcher
does. **It is not true of the JVM the launcher starts.** HotSpot will:

- start a shell of its own for `-XX:OnError=<cmd>` and
  `-XX:OnOutOfMemoryError=<cmd>` (`cmd /c` on Windows, `/bin/sh -c` elsewhere);
- load an arbitrary native library for `-agentpath:<path>`, **unconditionally,
  at startup, before a single Minecraft class is touched**;
- load arbitrary Java code for `-javaagent:` and `-Xbootclasspath/a:`;
- resolve native libraries from a directory of the caller's choosing if a later
  `-Djava.library.path=` overrides ours.

`jvm_args::validate_arg` refused six specific flags — large pages, `-noverify`,
`AggressiveOpts`, a non-loopback JDWP listener, heap flags, and anything with a
shell metacharacter. **None of the families above matched any of those rules.**
`-XX:OnOutOfMemoryError=calc.exe` contains no metacharacter, so the one check
that looked like it might have caught it did not.

The delivery mechanism is what makes it serious. A profile is a **document
designed to be shared** — `ProfileOps::export_json` exists so a user can send a
setup to a friend — and `sanitise_import` reset the Java path, the game
directory, the heap, the resolution and every mod file name, but **carried
`jvm.extra_args` across untouched**. Opening a shared `.json` file was therefore
enough to get `-agentpath:` onto the next launch's command line.

### What now stops it

**Layer 1 — the families are refused.** `validate_arg` now rejects seven
families with a specific explanation each: commands run by the JVM, code loading,
native-library redirection, classpath override, arbitrary file writes, TLS
weakening, and traffic redirection. Matching is case-insensitive and stops at a
flag boundary, so `-cp` is refused while `-Dcpu.count=4` is not.

**Layer 2 — an import carries no arguments at all.** `sanitise_import` now drops
`jvm.extra_args` wholesale and reports the dropped list to the user in full.

Layer 2 exists because **layer 1 cannot be complete**. The JVM has hundreds of
options and gains more every release; a denylist is a guess about what is
dangerous, made in advance, by us. Dropping the list entirely on import does not
require that guess to be right. It costs a user who exports a profile with
`-XX:+AlwaysPreTouch` the trouble of retyping it, and an existing round-trip test
was updated to record that this is deliberate.

**Remaining risk.** A user can still type a dangerous-but-unlisted flag into
their own profile on their own machine. That is their machine and their choice,
it is not reachable by anyone else, and refusing every flag we have not
enumerated would make the field useless. The denylist should grow as the JVM
does.

Tests: `flags_that_execute_code_are_refused`,
`the_refusals_are_case_insensitive`, `the_family_check_stops_at_a_flag_boundary`,
`an_imported_profile_cannot_smuggle_in_jvm_arguments`,
`no_generated_argument_that_loads_code_is_ever_accepted` (generative).

---

## 8b. A JVM argument supplied by remote metadata

**Found by the security review of 2026-08-27.**

**Impact.** Same as §8a, reached by controlling Mojang's version metadata or our
transport rather than by handing the user a file.

**Status: mitigated.**

A version document's `logging.client.argument` is a free-form template — for
1.8.9 it is `-Dlog4j.configurationFile=${path}` — and the launcher substituted
the path and pushed the result onto the JVM argument list. **It was the only
route into the argument vector that did not pass through `jvm_args`.** A document
declaring `"argument": "-agentpath:..."` would have had it passed verbatim.

`LoggingConfig::jvm_argument` now returns `Option<String>`, and returns `None`
unless the result both names the log4j configuration property and passes
`validate_arg`. Refusing costs almost nothing: without the flag the game falls
back to log4j's built-in configuration.

The generative tests also found that the function validated the trimmed string
and returned the untrimmed one. Not exploitable — an argv element is not a
shell — but validating one value and using another is the shape of a gap, and it
is closed.

**Remaining risk.** `mainClass` and `minecraftArguments` also come from remote
metadata. Neither is a JVM flag: the first is a class name, and the second is
tokenised into game arguments that sit *after* the main class, where the JVM
does not read them. Both remain trusted to the extent the allowlist and the
document's own hash make them trustworthy.

Tests: `a_logging_argument_that_is_not_a_log4j_property_is_refused`,
`a_version_documents_logging_argument_is_always_a_log4j_property_or_nothing`.

---

## 9. Sensitive log leakage

**Impact.** A token in a log file the user pastes into a bug report.

**Status: mitigated, two layers.**

**Structural.** Tokens are `Secret`. `Debug` prints `Secret(<redacted>)` and
`Display` prints `<redacted>`, so the usual cause — a `#[derive(Debug)]` on a
struct that happens to contain a token — cannot leak one. The device code is a
`Secret` too; only the short user code, which is meant to be shown, is a plain
`String`.

**Defensive.** `logging::scrub` catches values that arrived as plain strings from
somewhere we do not control, chiefly the game's own stderr. It handles
command-line flags, JSON fields, `Authorization` headers, `Bearer` tokens,
`XBL3.0` identity tokens and `RpsTicket` values, with two different value
terminators so that scrubbing `--accessToken` does not swallow the arguments
after it.

The launcher's own logs go to stderr only. No log file is written yet —
deliberately, until the scrubbing above has been exercised against real game
output.

`LaunchPlan::redacted_command_line` is the only way to render a command, and
there is no unredacted equivalent.

**Open.** `LaunchPlan`'s `args` field necessarily holds the raw token, because
that is what gets passed to the JVM. Deriving `Debug` on it and logging it would
leak. The type is documented as not-safe-to-log; making that structural (a
custom `Debug`) would be better.

---

## 9a. The game printing its own session token

**Impact.** A live Minecraft session token written to a plaintext file on disk,
readable by anything running as the user, and likely to be pasted into a bug
report.

**Status: mitigated. Was live until this milestone.**

Minecraft 1.8.9 prints, on every start:

```
(Session ID is token:<access token>:<uuid>)
```

The launcher captures the game's stdout into `profiles/<id>/logs/`, so every
launch wrote the token to disk. `logging::scrub` did not catch it — its rules
match markers like `--accessToken` and `Authorization:`, and this is
space-separated prose.

Found by launching the game and reading the log file, not by review. That is
worth recording: the argv-level protections were all in place and all correct,
and the leak was downstream of every one of them.

Two independent mitigations, because either alone is a guess:

1. `logging::scrub` gained `PHRASE_MARKERS`, which consumes
   `Session ID is token:<...>` whole. Depends on the game's wording.
2. The output pump additionally removes **this launch's own secret values** by
   exact match, using `LaunchPlan::secret_values()`. Does not depend on the
   wording at all.

Tests: `the_session_id_line_minecraft_prints_is_scrubbed`,
`a_launchs_own_secret_values_are_removed_from_captured_output`.

**Still open.** The token remains in the OS-visible argv while the game runs
(§9b). Scrubbing the log does not change that.

---

## 9b. The access token in the process list

**Impact.** Any process running as the same user can read the Minecraft session
token for as long as the game is running: `/proc/<pid>/cmdline`,
`NtQueryInformationProcess`, `KERN_PROCARGS2`.

**Status: accepted and documented. Inherent to vanilla 1.8.9.**

The game reads `--accessToken` from its argument vector. There is no
environment, stdin, or file-based alternative in that code path, and every
launcher for this version has the same exposure, including the official one.

The token is a Minecraft services token, not a Microsoft account token: it can
join servers as the account and read the profile; it cannot change a password or
make a purchase, and it expires in about 24 hours. That bounds the damage
without making it acceptable.

What is mitigated is our own handling — `Secret`, structural per-argument
redaction, a redacting `Debug` on `LaunchPlan`, no shell, and scrubbed captured
output (§9a). What is not mitigated is the argv itself.

`docs/research/token-exposure.md` records the candidate out-of-band channels for
a future Legacy Fabric bootstrap, the five questions that must be answered
first, and what we will explicitly not do. `LaunchPlan::exposes_a_secret_argument()`
reports the current state programmatically.

---

## 9c. A credential store, and three wire types, that formatted their own tokens

**Found by the security review of 2026-08-27.**

**Impact.** Refresh tokens and live Minecraft access tokens in a log the user
pastes into a bug report.

**Status: mitigated.**

§9 says the structural defence is that tokens are `Secret`, whose `Debug`
redacts. Four types held tokens as plain `String` and so were outside that
protection, each with a `#[derive(Debug)]`:

| Type | What it held |
|---|---|
| `EphemeralCredentialStore` | every refresh token in the session, in a `BTreeMap<String, String>` |
| `TokenResponse` | Microsoft's access **and** refresh token |
| `XboxAuthResponse` | the Xbox Live / XSTS token |
| `MinecraftLoginResponse` | the Minecraft access token |

No current call site formats any of them, so nothing was leaking. All four were
one `tracing::debug!(?response)` away from leaking, on the sign-in path, which is
exactly where someone reaches for a debug print when authentication misbehaves.

**The fix is structural rather than a promise not to log them.** `Secret` now
implements `serde::Deserialize`, so a token is a `Secret` **from the moment it is
parsed off the wire** rather than from the moment somebody remembers to wrap it,
and all three response types now hold `Secret`. `EphemeralCredentialStore` holds
`Secret` and has a hand-written `Debug` that reports an entry count and nothing
else — not even account names, which `security-model.md` also treats as private.

**`Secret` still does not implement `Serialize`, and must never.** That absence
is what makes it a compile error to put a token in a profile, a configuration
file, or any other JSON this program writes. It is load-bearing, so it is now
tested by a `compile_fail` doctest rather than left to review.

Tests: `a_credential_store_never_reveals_a_token_when_formatted`,
`a_secret_parsed_from_json_still_redacts`, and the `compile_fail` doctest on
`Secret`.

---

## 10. Symlink attacks

**Impact.** A symlink inside the data directory redirecting a write to somewhere
sensitive.

**Status: partial.**

- Symlink entries in archives are refused outright (§4).
- `fs_safety::resolve_existing` canonicalises a path and re-checks containment
  after following links. Used when reading something the user pointed at.

**Open.** `contain` is *lexical*, because during extraction the target does not
exist yet and cannot be canonicalised. If an attacker who can already write to
your data directory pre-creates `profiles/main/minecraft/mods` as a symlink to
`C:\Windows`, extraction would follow it. Closing this properly needs
`O_NOFOLLOW`-style directory-handle walking, which has no portable Rust
equivalent. Recorded rather than hand-waved.

---

## 10a. Insecure temporary files

**Found by the security review of 2026-08-27.**

**Impact.** Overwriting an arbitrary file with launcher-controlled content, for
anyone who can create a file in the launcher's data directory before we do.

**Status: mitigated.**

`atomic_write` — which writes every profile, every configuration file and every
extracted archive entry — created its intermediate with `File::create`.
`File::create` **truncates whatever it finds and follows a symlink to wherever it
points**. The name was `.{name}.{pid}.{counter}.tmp`: entirely predictable.

Plant a symlink at the name the launcher is about to use, and its content lands
on the link's target.

`atomic_write` now opens with `create_new` — `O_EXCL` on unix, `CREATE_NEW` on
Windows — so anything already at that path makes the open **fail** rather than be
followed or truncated. The name also carries a nanosecond stamp, but that is only
to avoid collisions between concurrent writers: `create_new` is the security
control, and unpredictability alone would be a race rather than a fix.

**Remaining risk.** A hostile squatter can still make a write *fail* by
occupying the names, which is a denial of service. That is the safe direction to
fail in, and it is bounded: eight attempts, then an error the caller reports.

The premise throughout is that the data directory is user-owned. On Windows it
is under `%LOCALAPPDATA%` and on unix under `$XDG_DATA_HOME`, so an attacker who
can write there is usually already running as the user. This is defence in
depth, not a boundary we rely on.

Tests: `atomic_write_refuses_to_write_through_a_squatted_temp_name`,
`atomic_write_never_follows_a_symlink_left_at_the_temp_name` (unix).

---

## 11. Profile escape into unrelated directories

**Impact.** One profile reading or writing another's files, or reaching outside
the data root entirely.

**Status: mitigated.**

`ProfileId` is the only way to name a profile, and it validates:

- lowercase ASCII letters, digits and hyphens only;
- must start and end alphanumerically — which also rejects `-`, `--`, and
  anything that could be read as a command-line flag;
- length capped at 64;
- reserved ids refused (`shared`, `logs`, `java`, `profiles`, `tmp`, `temp`),
  because they collide with the layout.

Crucially, **`Deserialize` validates too.** A hand-edited `settings.json`
containing `"selected_profile": "../../../etc"` fails to parse; it does not
become a path. Test: `a_tampered_profile_id_in_settings_is_rejected`.

Every profile path derives from `ProfileDirs`, which hangs off a validated id.
`ProfileDirs::contain` handles untrusted relative names within a profile.
`DataPaths::assert_inside` is a second assertion point for anything about to be
written.

Tests: `ids_that_could_escape_the_profiles_directory_are_rejected`,
`profiles_are_isolated_from_each_other`,
`every_profile_directory_is_inside_the_profile_root`,
`contain_rejects_traversal_out_of_a_profile`,
`deserialising_an_id_validates_it`.

---

## 11a. Destroying the user's own data

**Impact.** The worst *non-security* outcome this program has: a recursive
delete driven by a path the launcher did not choose. Worlds are irreplaceable
and most people have no backup.

**Status: mitigated.**

`profile.json` is an ordinary file. A user can edit it, a bad merge can corrupt
it, and an imported one arrives from somewhere else entirely. Its
`game_directory` field is a path, and a naive delete would follow it.

- **Deleting moves, it does not destroy.** The instance goes to
  `<data>/trash/<id>-<stamp>/` and can be restored. Purging is a separate act.
- **One gate before any recursive removal.** `ProfileOps::removable_root`
  canonicalises both the candidate and the profiles root and refuses anything
  that is not strictly inside it — including the profiles root itself, paths
  that will not resolve, and symlinks pointing outward. It fails closed.
- **An external game directory is never deleted.** `is_launcher_owned` answers
  no for `External`, always, and the deletion receipt tells the user the
  directory was left alone.
- **Shared storage is out of reach** of every profile operation.
- **A running profile cannot be deleted**, because moving a directory out from
  under a live JVM corrupts whatever it was writing.
- **Repair never deletes.** It resets settings and drops entries that name
  nothing; a mod jar the user placed stays where they put it.

Tests: `no_path_outside_launcher_storage_can_ever_be_removed`,
`a_profile_pointing_its_game_directory_at_someone_elses_data_cannot_delete_it`,
`deleting_a_profile_never_touches_shared_storage`,
`purging_is_a_separate_step_and_only_reaches_the_recovery_area`,
`a_symlinked_profile_directory_pointing_outward_is_not_followed_into_deletion`,
`repairing_an_unsafe_mod_entry_removes_the_entry_not_a_file`.

**Open.** The recovery area is never emptied automatically, so deleted profiles
accumulate until purged by hand. That is the safe direction to fail, but it is
not free disk space.

---

## 11b. A hostile or corrupt profile file

**Impact.** A profile document is parsed at start-up and can arrive by import.
A malformed one should not crash the launcher, hide other profiles, or become a
path.

**Status: mitigated.**

- Size-capped before parsing (1 MB), so an untrusted file cannot demand an
  unbounded allocation.
- `ProfileId` validates on **deserialisation**, so an id read from a file is
  checked exactly like one typed in — including Windows device names, which are
  refused on every platform so a profile made on Linux is not unopenable on
  Windows.
- A file whose recorded id disagrees with its directory is refused, rather than
  letting a profile impersonate another.
- A schema from the future is refused, not reinterpreted.
- One unreadable profile does not hide the others: `list` skips it and
  `list_detailed` reports it, so it is visible rather than silently absent.
- Import re-derives the id, drops machine-specific paths, clamps out-of-range
  values and discards unsafe mod names — reporting every adjustment.

Tests: `malformed_import_documents_are_refused_without_creating_anything`,
`an_import_with_out_of_range_values_is_corrected_and_the_user_is_told`,
`a_corrupt_profile_does_not_prevent_the_others_being_used`,
`a_profile_claiming_a_different_id_than_its_directory_is_refused`,
`an_absurdly_large_profile_file_is_refused_before_parsing`.

---

## 12. Forged entitlement

**Impact.** Playing without owning the game — the thing the product exists to
prevent.

**Status: mitigated against a network attacker.**

An attacker with a local proxy, a hosts-file entry, or a planted CA can return
any JSON they like from `api.minecraftservices.com`. So the unsigned `items`
array in the response is **ignored**. The RS256 `signature` JWT is verified
against Mojang's public key, compiled into the binary, with `exp` and `nbf`
enforced; the entitlement list is read from the verified payload. Anything
unrecognised fails closed.

Tests: `a_token_signed_with_the_wrong_key_is_refused`,
`a_tampered_payload_is_refused` (swaps a Java-Edition payload onto a valid
signature), `an_expired_token_is_refused`, `a_not_yet_valid_token_is_refused`,
`an_unsigned_response_is_refused_outright`,
`an_unrecognised_entitlement_does_not_permit_launch`.

**Open, and stated plainly.** This is not DRM and cannot be. We are open source
and do not obfuscate; anyone can fork this and delete the check. Our obligation
is that the software *we* publish verifies entitlement and ships no affordance
for bypassing it.

**Also open.** The compiled-in Mojang public key came from public documentation,
not from a live response we captured. If it is wrong, every sign-in fails closed
— the safe direction, but a serious launch-day bug. It must be checked against a
real signed response before release.

---

## 12a. Launching without a verified entitlement

**Impact.** The product's central promise: the game starts for someone who has
not proven they own it.

**Status: mitigated.**

There is exactly one function that yields launch credentials —
`AuthSession::launch_credentials` — and `LaunchCredentials` has no public
constructor, so no other code path can fabricate permission. It exists only in
the `EntitlementVerified` state, and every other state returns a typed refusal.

The refusals are exhaustive by construction: `LaunchDenied` has no
"unknown, allow anyway" variant, so a timeout, a malformed response, an
unavailable service or a state nobody anticipated all land on a refusal rather
than falling through to success.

In a release build the gate additionally requires the identity to be a real
Microsoft one and the entitlement proof to be `authoritative`, meaning it came
from the signature-checking verifier. That is redundant with the `dev-auth`
compile-time exclusion, deliberately: two independent locks.

Tests: `crates/launcher-core/tests/auth_contract.rs` — every non-verified state
refuses, expired tokens refuse, unentitled proofs never reach the verified
state, and the release gate is asserted against the build profile.

---

## 13. Supply chain

**Status: partial.**

- `deny.toml`, enforced by `cargo deny check`: a licence allowlist (adding a
  GPL/LGPL/CC-NC dependency fails the build), `unknown-git` and
  `unknown-registry` denied, and explicit bans on `openssl`, `native-tls`,
  `reqwest`, `curl`, `sentry` and `opentelemetry` so a second HTTP or TLS stack
  cannot appear by accident.
- `Cargo.lock` committed.
- `unsafe_code = "forbid"` in all three crates.
- Clippy runs with `-D warnings`, including `unwrap_used`, `expect_used` and
  `panic` denied in non-test code.

**Known advisories, accepted with reasons** (recorded in `deny.toml`, not
silenced):

| Advisory | Crate | Why accepted |
|---|---|---|
| RUSTSEC-2023-0071 | `rsa` | **A real vulnerability.** Marvin is a timing sidechannel in RSA *private key* operations. We only verify RS256 signatures with a public key; we hold no private key and never decrypt or sign, so the affected paths are unreachable. Escape hatch: jsonwebtoken's `aws_lc_rs` backend drops `rsa` entirely, at the cost of needing a C toolchain. |

The three unmaintained crates the iced toolkit brought in (`ttf-parser`,
`rustybuzz`, `instant`) left with it.

### The Java module's build (added by the review of 2026-08-27)

`minecraft/fathom-optimizations` has its own toolchain, and `cargo deny` does
not see it.

| Finding | Status |
|---|---|
| `fabric-loom` is pinned to **`1.7-SNAPSHOT`** | **Open.** A snapshot is a mutable coordinate: the artifact behind it can change without the version changing, so the build is neither reproducible nor auditable. Marked in `build.gradle` as must-pin-before-distribution. Not changed here, because the build has never been run in this environment and substituting a version number nobody has checked against the repository trades a known problem for a confusing one. |
| An unused JUnit dependency | **Removed.** The tests are plain `main` methods so that `core` can be verified with a bare JDK, which is how it was verified. |
| `"license": "TBD"` in `fabric.mod.json` | **Fixed** to `Apache-2.0`. Shipping a jar with no declared licence is the mistake `mod-policies.md` §5 is about, and the module's own `build.gradle` already tries to bundle a `LICENSE` file next to it. |
| No Gradle dependency verification | **Open.** `gradle/verification-metadata.xml` would pin checksums for the loom plugin and the mappings. Worth doing before the first distributed build. |

### Mixin configuration

`fathom.mixins.json` sets `injectors.defaultRequire: 0`, which means an injector
that fails to apply is skipped rather than aborting the launch. That is right for
instrumentation — losing a frame-time counter should not stop someone playing —
and **wrong for an optimization**: a half-applied optimization leaves the game in
a state neither we nor Mojang designed, and `core/guard` cannot catch a mixin
that never applied.

**Requirement: every optimization mixin must set `require = 1` on its own
injectors.** Recorded here and in the module README rather than as a comment key
in the JSON, because that file has never been loaded by Mixin in this
environment and adding an untested field to a config I cannot run is not a
trade worth making for a comment.

Nothing is affected today: the only mixin is the frame timer, and no
optimization is enabled.

**Open.** No reproducible builds, no dependency pinning beyond `Cargo.lock` and
the Gradle gap above, and no code signing — an unsigned Windows binary will trip
SmartScreen and some AV. Code signing needs a legal entity and money, and affects
distribution more than any technical decision here.

---

## 14. Local privilege escalation

**Status: mitigated by design.**

The launcher never runs elevated and never asks to. Everything is under a
per-user directory; nothing is written to `Program Files`, `HKLM`, or any
system-wide location. No service, no scheduled task, no driver.

JVM arguments that would require a privilege grant are refused with an
explanation — `-XX:+UseLargePages` needs `SeLockMemoryPrivilege`, and a few
percent of performance is not worth a lasting privilege on the user's account.

The child JVM runs at normal priority. `security-model.md` §7 rules out realtime
or high priority: it can starve input and audio threads and destabilise the
desktop.

---

## Summary

| # | Threat | Status |
|---|---|---|
| 1 | Stolen refresh token | Partial — OS store, no plaintext fallback; readable by anything running as you |
| 2 | Malicious mod | Accepted — unsandboxable; never auto-installed, hashes shown, jars never inspected by running them |
| 2a | A mod loader's own artifacts | Mitigated — no hash, no download; every URL and redirect hop allowlisted |
| 3 | Path traversal | Mitigated |
| 4 | Malicious archive contents | Mitigated |
| 4a | Windows device names, trailing dots and spaces | Mitigated — the trailing-space form was a live bypass; the traversal near-misses were measured, not assumed |
| 5 | Tampered downloads | Mitigated — but SHA-1 is what Mojang publishes |
| 6 | Compromised update infrastructure | Mitigated — signed two-role metadata, hash-bound artifacts, rollback floors, supervised replacement. Trust anchor deliberately empty; two risks stated |
| 7 | DLL / native loading | Partial |
| 7a | Stale natives surviving a repair | Mitigated — the sweep was top-level only; now recursive and link-safe |
| 8 | Command injection | Mitigated |
| 8a | **JVM options as a code-execution channel** | **Mitigated — was live. Two independent layers.** |
| 8b | A JVM argument supplied by remote metadata | Mitigated — the only route into the argument vector that skipped validation |
| 9 | Sensitive log leakage | Mitigated — `LaunchPlan` Debug redacts |
| 9a | Game printing its own session token | Mitigated — two independent passes |
| 9b | Access token in the process list | Accepted — inherent to vanilla 1.8.9; research recorded |
| 9c | Credential store and wire types formatting their own tokens | Mitigated — `Secret` now deserializes, so a token is wrapped from the moment it is parsed |
| 10 | Symlink attacks | Partial — lexical containment cannot see pre-created symlinks |
| 10a | Insecure temporary files | Mitigated — `create_new` instead of a following, truncating open |
| 11 | Profile escape | Mitigated |
| 11a | Destroying the user's own data | Mitigated — delete moves to a recovery area; one gate before any recursive removal |
| 11b | Hostile or corrupt profile file | Mitigated — size-capped, id validated, future schema refused, arguments dropped |
| 12 | Forged entitlement | Mitigated vs. network attacker; not DRM; key needs live verification |
| 12a | Launching without a verified entitlement | Mitigated — one gate, fails closed, release build rejects non-authoritative proofs |
| 13 | Supply chain | Partial — one accepted real advisory (unreachable); Gradle plugin on a snapshot; no signing |
| 14 | Privilege escalation | Mitigated by design |

---

## Security review of 2026-08-27

A dedicated pass over auth, downloads, archives, profiles, mods, the Java
runtime, installation, process spawning, filesystem handling, logging and
configuration, looking for practical exploit paths rather than for style.

### Findings by severity

| Severity | Finding | Section |
|---|---|---|
| **CRITICAL** | JVM options are a code-execution channel, reachable by importing a shared profile. `-agentpath:` loads a native library at JVM startup; `-XX:OnError` runs a command through a shell the JVM starts. The validator was a denylist of six unrelated flags, and `sanitise_import` carried the argument list across untouched. | §8a |
| **HIGH** | A version document's `logging.client.argument` reached the JVM command line without passing argument validation — the only such route. | §8b |
| **MEDIUM** | `atomic_write` created its temp file with a following, truncating open at a predictable name: an arbitrary-file-overwrite primitive for anyone who could write to the data directory first. | §10a |
| **MEDIUM** | `EphemeralCredentialStore` and three auth wire types held tokens as `String` behind a derived `Debug`. Nothing logged them; everything was one debug print away. | §9c |
| **MEDIUM** | The reserved-device-name check missed the trailing-space form, so `CON ` extracted to the console device. | §4a |
| **MEDIUM** | `_JAVA_OPTIONS` was suppressed but `LD_PRELOAD`, `LD_LIBRARY_PATH`, `LD_AUDIT` and `DYLD_INSERT_LIBRARIES` were not, leaving a loader-injection channel into the game process. | §7 |
| **LOW** | The natives sweep was top-level only, so a stale library survived every repair in a subdirectory. Not loadable — `java.library.path` does not recurse — but present in a profile reported as clean. | §7a |
| **LOW** | `LoggingConfig::jvm_argument` validated the trimmed string and returned the untrimmed one. | §8b |
| **LOW** | The Java module declared `"license": "TBD"`. | §13 |
| **HARDENING** | `fabric-loom` on a `1.7-SNAPSHOT` coordinate; no Gradle dependency verification. | §13 |
| **HARDENING** | An unused JUnit dependency in a module whose tests do not use it. | §13 |
| **HARDENING** | `injectors.defaultRequire: 0` is right for instrumentation and wrong for an optimization. | §13 |

Every item above is fixed except the four marked **Open** in §13, which are
build-toolchain items in a module whose build has never been run here.

### Checked and found sound

Recorded because a review that lists only what it broke is not a review.

- **No shell, anywhere.** `Command::new(java).args(...)` throughout; the
  tokenise-then-substitute ordering in the argument template holds. The JVM's own
  shell was the gap (§8a), not ours.
- **Downloads.** Hash computed during the write, verified before the rename, the
  `.partial` deleted on any failure, a per-item and a global size ceiling, and a
  hash mismatch deliberately not retried.
- **Network.** HTTPS only, host allowlist with no `Other(String)` escape,
  userinfo and ports refused, query and fragment stripped, redirects followed by
  hand with **every hop re-validated**, and the agent configured with
  `max_redirects(0)` so nothing can be followed behind our back. Case-sensitive
  scheme and host matching fails closed.
- **Entitlement verification.** `Algorithm::RS256` is pinned, so `alg: none` and
  HS256 confusion are refused; `exp` and `nbf` are both required.
- **Deletion.** Both recursive-removal paths canonicalise before comparing,
  refuse the root itself, and distinguish "outside launcher storage" from "not in
  the recovery area" so the refusal is actionable.
- **Mod materialisation.** Only files this launcher placed, recorded in its own
  manifest, are ever removed; a foreign jar is reported and left alone.
- **`Secret`.** No `Serialize`, and now a `compile_fail` doctest to keep it that
  way. Every `expose()` call site is a credential store write, an HTTP header, or
  the argument vector — none is a log.
- **`unsafe_code = "forbid"`** in all three crates.

### Dependency audit

`cargo audit` (1,226 advisories, 476 crates) and `cargo deny check` both run
clean: `advisories ok, bans ok, licenses ok, sources ok`. One real advisory,
RUSTSEC-2023-0071 in `rsa`, is accepted with the reachability argument in §13 —
we verify signatures with a public key and hold no private key. The three
unmaintained crates are all in the UI toolkit's tree.

### Generative tests

`crates/launcher-core/tests/fuzz_parsers.rs` runs roughly half a million
generated inputs through the parsers that accept outside input — archive paths,
profile documents, mod file names, JVM arguments and log lines — asserting the
sandbox invariants rather than merely "does not panic".

It is **property testing with a seeded generator, not a coverage-guided fuzzer**,
and will not find the input that reaches a rare branch the way `cargo-fuzz`
would. Adding `cargo-fuzz` targets for `contain` and for profile import is the
obvious next step.

It earned its place immediately: it found the untrimmed-argument gap in §8b, and
it forced the Windows traversal question in §4a to be answered by measurement
instead of by assumption.
