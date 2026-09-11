# Launcher and game, joined up

What crosses between the Fathom launcher and the game it starts, in which
direction, and what deliberately does not.

---

## 1. Three files and one socket

| Channel | Direction | Carries a secret | What it is for |
| --- | --- | --- | --- |
| `fathom/session.json` | launcher → game | **no** | What this launch is: profile, version, loader, preset, component version |
| `config/fathom-performance.properties` | launcher → game | no | Which optimizations are on, and which were stood down for another mod |
| `config/fathom-features.properties` | game ↔ itself | no | Module switches, settings, keybinds. The launcher never reads or writes it |
| `fathom/bridge.json` + loopback socket | game → launcher | **yes** | Four verbs: ping, focus, diagnostics, next-profile, restart-into |

The split between the first and the last is the point. The bridge exists to let
the game *do* something to the launcher, so it is authenticated and its command
set is a closed enum. The session descriptor exists to let the game *say* what it
is, so it carries no capability at all — and can therefore be shown in
Diagnostics, pasted into a bug report, and read when the bridge never came up.

### 1.1 What the session descriptor never contains

No access token, no refresh token, no Microsoft identity, no username, no UUID,
no session id. `session::tests::a_descriptor_carries_nothing_about_the_account`
reads the written bytes back and fails if any of those words appear, which is a
stronger guarantee than a review comment.

It is written before the process starts and removed when the game exits. A
descriptor left behind would tell a game somebody later started by hand in the
same directory that Fathom launched it into a profile, and the main menu would
then display a profile name for a session the launcher had nothing to do with.

---

## 2. The main menu

Fathom replaces Minecraft's title screen with its own, drawn by the same
renderer and the same `Controls` as the Hub.

```
        F  Fathom
           1.8.9 · Legacy Fabric
             ( Bedwars )

        [ Singleplayer                    ]
        [ Multiplayer                     ]
        [ Options                         ]

        [ ⊞ Fathom  Right Shift ] [ Quit  ]
```

- **Background**: near-black, with one of Minecraft's own panorama faces under an
  88% scrim, drifting about a pixel a second. Not vanilla's rotating skybox — see
  `Panorama` for why — and not a video.
- **Session line**: `1.8.9 · Legacy Fabric` from the descriptor, and the profile
  name in a pill under it. When the launcher did not start the game it says
  **External Session**, never "Unknown Profile"; see `SessionName`.
- **Buttons**: Singleplayer, Multiplayer and Options hand off to Minecraft's own
  screens. Fathom opens the Hub. Quit stops the game.
- **No accounts button.** The game process holds a session it was handed at
  launch and has no way to sign in or out. A button that turned out to mean
  "open the launcher" would be a decorative control, in the one place where the
  subject is somebody's Microsoft login.

### 2.1 Why the Fathom button exists

Right Shift is a thing you are told once and can then forget. A client whose only
entrance is a keystroke is a client half its users think is not installed. The
button shows the current binding beside it, so somebody who reads it once does
not need the button again.

### 2.2 It can be switched off

`main-menu` is a module like any other, with the `main-menu` behaviour. Off means
vanilla's screen, unchanged — nothing is patched into it. The replacement is done
by rewriting the argument to `MinecraftClient.setScreen`, which means vanilla's
own method runs exactly once and every caller keeps working without knowing.

Another mod's `TitleScreen` subclass is left alone: the check is
`getClass() == TitleScreen.class`, not `instanceof`.

---

## 3. OptiFine

**OptiFine is user-supplied. Fathom does not download, host, mirror or
redistribute it, under any circumstance.** `mod-policies.md` §2, §4 and §7:
proprietary, all rights reserved, redistribution prohibited.

What the launcher does instead is make the manual path pleasant.

### 3.1 The flow

Reached from **Mods → Add OptiFine…** or from the components step of profile
setup.

1. **Open OptiFine Downloads** opens the author's own page, by id — the WebView
   never names a URL.
2. **Choose JAR**, or drop the file anywhere on the window.
3. The file is **verified, hashed, copied and identified**, in that order:

   | Step | Where | What it means |
   | --- | --- | --- |
   | Integrity | `mods::jar` | The envelope is a zip with entries in it: a local header, an end-of-directory record, offsets inside the file. **Nothing inside the archive is read.** |
   | Hash | `mods::store` | SHA-1, which is what names it in the content-addressed store. The same jar dropped twice is one file. |
   | Identity | `mods::identify` | From the filename, never from inside the archive. A filename is a string; a zip is a parser. |

4. The screen shows what arrived — name, version, Minecraft version, size, entry
   count, short hash, and **You added this** as the source — and then **which of
   your profiles it suits**, with a reason for each that does not.
5. **Add to profile** records it, and immediately says what Fathom will stand
   down for it.

Identification is never a gate: an unrecognised jar is still accepted, still
hashed, still stored. It simply carries its filename as its name.

### 3.2 Why integrity is checked at all

Because the alternative is a launcher that accepts an HTML error page saved as
`OptiFine_1.8.9_HD_U_M5.jar`, stores it, adds it to a profile, and produces a
game that refuses to start with a stack trace naming a class loader. That failure
lands a long way from its cause. OptiFine's download page has several buttons on
it and not all of them are the file.

### 3.3 What the WebView is allowed to know

A description, never a path. The file picker opens on the Rust side. The one
place a path travels the other way is a drop event — and that string did not come
from the WebView's knowledge of the disk, it came from the operating system
telling us the user pointed at a file. It is still checked as though it were
hostile: an existing file, a `.jar` extension, and then the bytes.

---

## 4. OptiFine compatibility

**Fathom does not silently run a known-conflicting patch.**

Two mods rewriting the same renderer path is not a performance question, it is a
correctness one: whichever loads second wins some frames and loses others, and
the result is a client that renders wrongly in ways neither project can
reproduce.

So when the launcher can see OptiFine in the profile, it turns Fathom's
overlapping work off before the game starts — and says which, and why:

> Fathom Entity occlusion culling was disabled for OptiFine compatibility.

That sentence appears in three places, built from the same data: on the Add
OptiFine screen at the moment the jar is added, in the module log at startup, and
in the Hub's Diagnostics page.

### 4.1 What is stood down

Twelve optimizations, listed in `optimizations::conflicts`: font batching and
metrics, HUD and entity and tile-entity batching, entity culling, the three chunk
patches, worker pool sizing, and particle brightness. All of them are things
OptiFine's renderer replaces wholesale.

What is **not** stood down: the frame caps, the memory patch, and the resource
handling. OptiFine does not touch those, so turning them off would cost the user
something for nothing. A test asserts each of those four stays on.

### 4.2 How it reaches the game

A `conflict.<id> = <reason>` line in the settings file, alongside the
`opt.<id> = false` that forces the switch. Two lines rather than one, because
they say different things: the first is "the user's choice", the second is "and
here is why it is being overridden". Collapsing them would lose the sentence,
and a switch that reads as on and does nothing is exactly the defect
`docs/modules.md` §1 exists to prevent.

The game applies it through `SwitchBoard.disableForConflict`, which records that
it is overriding a human rather than pretending they chose it.

---

## 5. Diagnostics

The Hub's Diagnostics page reports, in this order:

| Section | Contents |
| --- | --- |
| Session | Profile, Minecraft version, loader and its version, performance preset, Fathom component version, launcher version — all from the descriptor |
| Now | Frame rate, memory |
| Interface renderer | Frame time mean/p95/p99, Fathom's own draw cost, geometry, caches, scale |
| Client | Modules on, HUD widgets shown, module version |
| Performance | Optimizations on, anything switched off automatically, saved benchmarks |
| Loaded mods | Everything Fabric actually loaded, by name and version |
| Fathom module log | What Fathom itself said, in order, with nothing in between |
| Game log | The tail of `logs/latest.log` |
| Launcher | Bridge state |
| Problems | Anything either config file complained about |

Three actions: **Copy** puts all of it on the clipboard, **Save** writes it to
`fathom/diagnostics-<timestamp>.txt`, and **Launcher diagnostics** raises the
launcher and opens its Logs page — disabled when the launcher is not running,
rather than starting it, because starting a program is a bigger thing than the
button says.

### 5.1 Why the module keeps its own log

Everything in it also goes to standard output and therefore into
`logs/latest.log`. That file is the right place for it and the wrong place to
read it from: it is tens of thousands of lines of resource loading and chunk
building, and the twenty lines Fathom contributed are scattered through it in
load order. `ModuleLog` keeps those twenty, in a bounded ring — bounded because
a third-party mod using the public API can write to it, and an unbounded log
somebody else can append to is a memory leak with an owner who did not mean it.

---

## 6. Open Launcher

From the Hub's Launcher page, and from Diagnostics.

- **Running** → bring its window to the front.
- **Closed** → start it, from the path it recorded about itself in the handshake,
  checked to still exist.

**No duplicate launchers.** Two guards, because one is not enough:

1. Before starting anything, the bridge pings again. The state may have been
   decided when the screen opened, and the launcher could have been started by
   hand since.
2. A start attempt sets an eight-second cooldown. Starting takes a few seconds,
   during which the bridge still reads as closed — without this, pressing the
   button twice gets two launchers, and two launchers writing the same profile
   files is a worse problem than a button that appears not to have worked.

The note the screen shows reports what actually happened, not what it assumed.

---

## 7. What has not been run

Everything in this document compiles, and the launcher's 728 unit tests plus the
game module's 183 assertions pass. **None of it has been opened in the game or in
the launcher window.** In particular, unverified by eye:

- the main menu at any resolution, its panorama face, and the hand-off to
  vanilla's screens;
- the Add OptiFine screen, the drop target, and the profile list it produces;
- the Diagnostics page's new sections and its three buttons;
- the `fathom://show-diagnostics` round trip;
- `MinecraftClientMenuMixin` actually applying — the target signature was
  confirmed against the 1.8.9 bytecode with `javap`, and a confirmed signature is
  not an applied injector.

The parts that can be checked without either program running are: the descriptor
format (both sides, against each other), the jar integrity checks against real
malformed archives, the conflict list against the optimization registry, and the
profile-fit rules.
