# Feature audit

Every user-facing control in the launcher, classified. The point of this
document is that a control which looks finished and does nothing is worse than
an absent one, because it costs the user their trust rather than just their
time.

**WORKING** — does what it appears to do.
**PARTIAL** — does something real, but less than the label implies.
**PLACEHOLDER** — appears functional, is not.

The rule this build follows: **there are no PLACEHOLDER controls.** Anything
that cannot act is either absent, or present as a state label rather than as a
control. The Performance page is the clearest case — 18 of its 20 optimizations
cannot do anything yet, so they render as text with a status, not as toggles.

Last audited against the working tree at the milestone that added Performance,
Recovery, the config bridge and the four page rebuilds.

---

## Home

| Control | State | Notes |
| --- | --- | --- |
| PLAY | WORKING | Launches through `services::launch`, which claims the profile, preflights, checks files, extracts natives, writes the optimization config and spawns the JVM. |
| PLAY as blocker action | WORKING | Becomes `SIGN IN TO PLAY`, `INSTALL GAME FILES`, `REPAIR REQUIRED` etc., each dispatching the real fix. |
| Secondary action under PLAY | WORKING | Verify / Cancel / Scan for Java, depending on state. |
| STOP MINECRAFT | WORKING | Terminates the process this launcher started. |
| Vitals row (Files / Java / Heap / Session) | WORKING | Read from the verification report, Java scan and profile. |

## Profiles

| Control | State | Notes |
| --- | --- | --- |
| Select / Play per row | WORKING | |
| Edit disclosure | WORKING | |
| Loader chips | PARTIAL | Sets the profile's loader. Only Vanilla can launch; Legacy Fabric and Forge are catalogued and marked "soon", and `readiness()` reports a blocker. Setting is stored, not fake. |
| Memory, extra JVM args | WORKING | Applied to the launch command; covered by `jvm_args` tests. |
| Keep launcher open | WORKING | |
| Save | WORKING | |
| Check | WORKING | Runs `validate`, findings shown inline. |
| Repair | WORKING | Rebuilds missing directories and configuration. |
| Duplicate | WORKING | |
| Export | WORKING | Writes a portable profile archive. |
| Import | WORKING | |
| Delete | WORKING | Two-press; moves to recovery storage rather than deleting. |
| Profile artwork | WORKING | Optional, user-supplied. Only the selected profile's art is decoded. |
| Health badge | PARTIAL | Honest about its limits: says "Not checked" for unselected profiles rather than claiming healthy, because only the selected one has a report. |

## Mods

| Control | State | Notes |
| --- | --- | --- |
| Search | WORKING | |
| Filters (All / Enabled / Disabled / You added / Issues) | WORKING | Live counts. |
| Per-mod toggle | WORKING | Writes to the profile's mod configuration. |
| Expand detail | WORKING | File, hash, provenance, location. |
| Remove from profile | WORKING | |
| Add mod jar | WORKING | Native file picker, adopted into the content-addressed store. |
| Rebuild folder | WORKING | Materializes the effective set into the game directory. |
| Open folder | WORKING | |
| Loader warning banner | WORKING | Says plainly that a Vanilla profile loads none of the mods listed. |
| Browse / install from a repository | **ABSENT** | Deliberately. There is no source integration, so there is no grid. See "Not built" below. |

## Performance

| Control | State | Notes |
| --- | --- | --- |
| Preset (None / Safe defaults / Custom) | WORKING | Rewrites the switch map; toggling anything moves the profile to Custom. |
| `jvm.disable-explicit-gc` toggle | WORKING | Emits `-XX:+DisableExplicitGC`. Tested. |
| `jvm.compile-huge-methods` toggle | WORKING, **but inert on 1.8.9** | Emits `-XX:-DontCompileHugeMethods` plus a larger code cache. Tested. The flags are passed correctly; they just have nothing to act on — one method in the game exceeds the 8000-byte limit and it runs once at startup. Measured 2026-09-17; see `docs/performance/benchmark-methodology.md` §3. |
| 18 game-side optimizations | **NOT CONTROLS** | Rendered as rows with a status (`Core written` / `Planned`) and the word "Unavailable" where a toggle would be. They cannot act until the module ships, so they are not offered as switches. |
| Memory / Java summary | WORKING | Read-only; edits link to Profiles. |

A test (`optimizations::tests::nothing_game_side_claims_to_be_working`) fails the
build if any game-side entry ever claims to be active while the module is not
shipped.

## Privacy

| Control | State | Notes |
| --- | --- | --- |
| Five promise rows | WORKING | Statements of fact about the build, not settings. |
| Network activity groups | WORKING | Every host the build can reach, grouped by purpose, expandable to hostnames. Derived from the `Destination` enumeration. |
| Session request log | WORKING | In-memory journal. |
| Refresh / Clear | WORKING | |

## Recovery

| Control | State | Notes |
| --- | --- | --- |
| Startup scan result | WORKING | Findings from the scan that runs on every launch, kept as data so a dismissed notice does not lose them. |
| Verify every file | WORKING | Full SHA-1 pass. |
| Repair | WORKING | Replaces only what does not match. |
| Check / Rebuild profile | WORKING | |
| Data directory | WORKING | Shown, not editable here. |

## Settings

| Control | State | Notes |
| --- | --- | --- |
| Category rail | WORKING | |
| Search | WORKING | Searches across all categories, not just the open one. |
| Update check on start | WORKING | |
| Background chooser | WORKING | Also in the bottom bar. |
| Java scan | WORKING | |
| Offline mode | WORKING | Enforced by network policy on every request. |
| Managed Java download | PARTIAL | The switch stores a real preference the policy reads. The download itself is not implemented, and the page says so. |
| Mod repository access | PARTIAL | Same shape: the policy honours it; no repository integration exists to use it. |
| Performance entry | WORKING | A pointer to the Performance destination, not a settings page. |

## Account

| Control | State | Notes |
| --- | --- | --- |
| Sign in | PARTIAL | The full Microsoft device-code flow is implemented and tested. It cannot run in a build with no Azure client id, and the screen explains exactly that rather than showing a button that fails. |
| Sign out | WORKING | |
| Development sign-in | WORKING | `dev-auth` builds only; a permanent banner makes it unmistakable. |

---

## Not built, and why

**Mod browsing and installation.** Needs a signed index and a licence to
redistribute from. Fetching a mod on a user's behalf is a distribution act; it
has to come from the author's own host, honour their opt-out, be something the
user asked for by name, and be verified against a published hash. Until that
exists the launcher adds jars the user already has. A browse grid would be the
most convincing-looking part of the launcher and the least real.

**OptiFine.** User-supplied only, permanently. Its licence is proprietary and
all rights reserved.

**The Fabric half of the optimization module.** Needs Gradle, Loom and network
access to the Legacy Fabric and Fabric maven repositories. `build.gradle` exists
and has never been executed; its `fabric-loom 1.7-SNAPSHOT` coordinate is a
mutable snapshot that must be pinned before any distributed build.

**Any frame-rate claim.** No in-game benchmark has been run against the module,
so nothing in the product claims one. There is no "Maximum FPS" preset for the
same reason.

**Servers.** No server list, browser or ping. Not started.

## Next, in order

1. **Pin `fabric-loom` and run the Gradle build once.** Everything game-side is
   blocked behind it, and the snapshot coordinate is an open finding in
   `THREAT_MODEL.md`.
2. **Benchmark in game** under `docs/performance/benchmark-methodology.md`. Until
   then every optimization stays unmeasured by definition.
3. ~~**Resolve the display font's licence**~~ Done: the unlicensed face was
   removed, and `style::FONT_DISPLAY` is the interface bold.
4. **Legacy Fabric loader**, which unblocks mods being loaded at all.
5. Visual verification pass at 1366×768 and 1920×1080.
