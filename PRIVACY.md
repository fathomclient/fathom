# Privacy

The short version: this launcher collects nothing about you, sends nothing about
you anywhere, and keeps exactly one secret on your machine — a Microsoft token
you can revoke yourself at any time.

The rest of this document is the detail, including the parts that are less
comfortable.

---

## 1. What we do not do

Each of these is a claim you can check, not a promise you have to take on faith.
The check is in the right-hand column.

| | How to verify |
|---|---|
| No telemetry | `NETWORK.md` §2 lists every reachable host. `deny.toml` bans `sentry` and `opentelemetry` at build time. |
| No analytics | Same. There is no analytics SDK in the dependency tree. |
| No crash reporting | Nothing sends a crash anywhere. Panics print to stderr and stop there. |
| No ads | There is no web view to put one in. |
| No launcher account | There is no user database, no server-side session, and no first-party auth endpoint. Identity comes from Microsoft and nowhere else. |
| No device identifier | No install id, GUID or random token is generated, stored or sent. `grep` the source for `Uuid::new` and you will not find one. |
| No hardware fingerprint | The launcher never reads a CPU serial, disk serial, MAC address, motherboard UUID or Windows MachineGuid. |
| No kernel driver | No `.sys`, no service, no driver signing. |
| No background service | One process, started by you, gone when you close it. No scheduled task, no `Run` key, no launch agent, no systemd unit. |
| No anti-cheat | No process enumeration, no memory scanning of other processes, no injection. We also do not scan *you* to prove your innocence to a server. |
| No system modification | No registry writes outside your own profile, no network stack tuning, no power plan changes, no Defender exclusions. |

The launcher does read your CPU core count, physical memory, and which Java
runtimes you have installed. That is for sizing the JVM and picking a runtime.
It stays on your machine; no code transmits it.

---

## 2. What is stored, and where

| Item | Stored? | Where |
|---|---|---|
| Your Microsoft password | **Never.** We never see it. | — |
| Microsoft refresh token | Yes | OS credential store |
| Microsoft access token | No — memory only | — |
| Xbox Live / XSTS tokens | No — memory only | — |
| Minecraft access token | No — memory only, ~24 h life | — |
| Entitlement JWT | No — memory only | — |
| Your username and UUID | Yes, plaintext | `settings.json` |
| Profiles, mods, settings | Yes | your data directory |
| The request journal | **No** — memory only, cleared on exit | — |
| Launcher logs | **No** — stderr only, no log file is written | — |

The credential store is Windows Credential Manager (DPAPI-backed), macOS
Keychain, or the Secret Service on Linux, whichever your system provides.

Your username and UUID are stored in plain text on purpose: they are public
information that every server you join already knows.

**If no credential store is available** — some headless or minimal Linux setups —
the launcher tells you so on the Account screen and keeps the token in memory for
that session only. It deliberately does **not** fall back to writing the token to
a file. Silently degrading to plaintext is how launchers leak tokens.

### Signing out

Sign out deletes the refresh token from the credential store and clears the
account from `settings.json`. Nothing about the account remains. You can also
revoke the token from your Microsoft account page without touching this
launcher, and it will stop working immediately.

---

## 3. Where your data lives

Everything is under one per-user directory:

- Windows: `%LOCALAPPDATA%\Fathom\data`
- Linux: `~/.local/share/Fathom`
- macOS: `~/Library/Application Support/Fathom`

Nothing is written outside it except into the game directory you chose for a
profile. The launcher never needs administrator rights and never asks for them.

Run `fathom-cli doctor` to print the exact paths on your machine.

Deleting that directory removes everything except the refresh token, which lives
in the OS credential store — sign out first, or delete the "Fathom Launcher"
entry from Credential Manager / Keychain yourself.

---

## 4. What leaves your machine

Only what is needed to sign in, prove you own the game, and download it — all of
it to Microsoft and Mojang, none of it to us. We run no server that your copy of
the launcher talks to, apart from a static file host for updates.

Every request carries a `User-Agent` of `Fathom/0.1.0` and nothing else. No
cookie, no identifier, no query parameters we did not need.

### The update check

Two `GET`s for fixed paths — `/v1/root.json` and `/v1/release.json` — and
nothing else. It is the only request the launcher makes that you did not ask
for, and it can be turned off.

It does **not** send a hardware id, an install id, a machine GUID, your
username, anything about your Microsoft account, your OS build, your locale, or
a count of anything you have installed. There is no query string and no
callback: the launcher asks for a file and reads it. Every copy of Fathom on a
given version sends byte-identical requests, so the request itself distinguishes
nobody.

The host learns an IP address and a timestamp. That is true of fetching any file
over the internet and there is no way to fetch one without it.

**In this build the check does not run at all.** The launcher has no update
signing key compiled in, so it could not verify a manifest, so it refuses to ask
for one. `fathom-cli update status` will tell you so.

### Browsing and installing mods

Off until you turn it on, in Privacy settings, and then still only when you ask
for something specific. There is no background sync, no "check for mod updates
on start-up", and no way to express one: every request to a mod repository needs
a fresh grant recording what you asked for, and a grant can only be made by an
action you took.

When you do search, Modrinth receives the words you typed, the Minecraft version
and the loader — because that is the query. When you install something, its CDN
receives a request for one file. Neither receives your username, your account, a
list of what you have installed, or an identifier for this copy of the launcher.
There is no account on our side to attach any of it to.

What the launcher keeps afterwards is on your disk, in the profile: the project
id, the version id, the file name, the hash, the licence as it was reported, and
the versions and loaders it claimed. That is so "what did I install, and from
where" stays answerable when you are offline or the project is taken down.

Two things this deliberately does **not** do. It does not talk to CurseForge,
because each project there carries a distribution flag that can forbid automated
delivery and Fathom will not work around one. And it never fetches OptiFine,
whose licence forbids redistribution — the Mods page gives you a link to the
author and somewhere to drop the file, and the file never leaves your machine.

`NETWORK.md` has the full table and the reasoning.
`docs/research/update-security.md` explains how an update is verified.

---

## 5. The honest parts

A privacy document that only lists good news is not worth reading. Here is what
this launcher cannot promise.

**Your access token is visible in the process list.** Minecraft 1.8.9 takes it as
`--accessToken` on the command line, so any program running as you can read it
from `/proc/<pid>/cmdline`, `Get-CimInstance Win32_Process`, or `ps`. Every
mainstream launcher has this exposure; it is inherent to how the game accepts
credentials. We keep it out of our own logs and out of the interface, but we
cannot keep it out of the process table. There is a design for fixing this on a
modded profile (`security-model.md` §3) and it is not implemented.

**Microsoft and Mojang see your requests.** Signing in tells Microsoft you signed
in. Downloading the game tells Mojang your IP address. That is unavoidable for a
launcher that refuses to distribute Minecraft itself, and it is the same
information the official launcher provides.

**Mods run with full privileges.** A mod jar you install is Java code inside the
game process. It can read your files and open its own network connections, and
nothing in this launcher can stop it. We show you the SHA-1 of every jar so you
can check it against what the author published, and we never install one without
you choosing it — but after that, you are trusting the mod author, not us.

**We are not anonymous.** Nothing here hides your IP address from Microsoft,
Mojang or the servers you join. This is a launcher, not a privacy network.

**"No telemetry" is a property of this source tree.** If you download a build
from somewhere other than the official release, you are trusting whoever built
it. The point of being open source is that you can check, or build it yourself.

---

## 6. The request journal

Open the Privacy screen while you use the launcher. Every request appears with:

- which host,
- why (verify entitlement, download game artifact, resolve metadata…),
- what you did to cause it (sign-in, install, play…),
- and what happened.

This is the inverse of telemetry. The data is collected for **you**, kept in
memory, never written to disk, and never sent anywhere. Clear it whenever you
like.

If you ever see a host on that screen that is not in `NETWORK.md` §2, that is a
bug and a serious one. Please report it.

---

## 7. If this ever changes

There is no analytics code in this project, and there are no disabled stubs
waiting to be switched on. Adding any would require:

- a new `Destination` variant, visible in the diff;
- an entry in `NETWORK.md`;
- an update to this file.

If a future version ever collects anything, it will be a loud, documented,
default-off decision with its own release note — not a quiet setting.
