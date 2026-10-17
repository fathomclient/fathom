# Security and Privacy Model

Status: **research / pre-implementation**.
Date of research: 2026-08-23.

---

## 1. Principles, stated as enforceable rules

My product spec's principles are good, but principles that cannot be checked are marketing. Each
one below is restated as something a reviewer can verify against the code.

| Principle | Enforceable rule |
|---|---|
| No launcher account | The codebase contains no user database, no server-side session, and no first-party auth endpoint. Identity comes from Microsoft and nowhere else. |
| No telemetry / analytics | The set of hosts the launcher may contact is an explicit compile-time allowlist (§5). Any addition is a reviewable diff. |
| No ads | No third-party script, image, or iframe loads. Trivially true given we ship no WebView. |
| No tracking | No persistent random identifier is generated, stored, or transmitted. No cookie jar is persisted. |
| No HWID / fingerprinting | The launcher never reads CPU serial, disk serial, MAC address, motherboard UUID, or Windows MachineGuid. Hardware info gathered for JVM sizing (core count, physical RAM) stays on the machine and is never transmitted. |
| No invasive anti-cheat | No process enumeration, no memory scanning of other processes, no injection, no driver. |
| No kernel drivers | No `.sys`, no service install, no driver signing. |
| No background services | Exactly one process, started by the user, exited when they close it. No scheduled task, no `Run` key, no launch agent, no systemd unit. |
| No unrelated network traffic | See §5. |
| No system-wide tweaks | The launcher writes only inside its own data directory and the game directory. It never writes to `HKLM`, never modifies network stack settings, never changes power plans. |
| No dangerous JVM settings | Rejected flag list is compiled in and enforced even on user-supplied argument strings (§8). |
| No hidden processes | The only child process is the JVM, spawned with a visible, user-inspectable command line. |

Because the project is open source, these are auditable claims rather than promises. That is the
point.

---

## 2. Authentication and ownership verification

### 2.1 The chain

Ownership verification is the whole reason the auth code exists, so it is worth spelling out
completely.

```
1. Microsoft OAuth 2.0
   Tenant:  consumers   (NOT "common", NOT an AAD tenant ID)
   Scope:   XboxLive.signin offline_access
   Flow:    device code, or authorization code + PKCE on a loopback redirect
   ->  microsoft_access_token, microsoft_refresh_token

2. POST https://user.auth.xboxlive.com/user/authenticate
   { Properties: { AuthMethod: "RPS",
                   SiteName:   "user.auth.xboxlive.com",
                   RpsTicket:  "d=<microsoft_access_token>" },
     RelyingParty: "http://auth.xboxlive.com",
     TokenType: "JWT" }
   ->  xbl_token, DisplayClaims.xui[0].uhs        (userhash)   [~14 day lifetime]

3. POST https://xsts.auth.xboxlive.com/xsts/authorize
   { Properties: { SandboxId: "RETAIL", UserTokens: [xbl_token] },
     RelyingParty: "rp://api.minecraftservices.com/",
     TokenType: "JWT" }
   ->  xsts_token                                              [~24 hour lifetime]

4. POST https://api.minecraftservices.com/authentication/login_with_xbox
   { identityToken: "XBL3.0 x=<uhs>;<xsts_token>" }
   ->  minecraft_access_token                                  [expires_in 86400]

5. GET  https://api.minecraftservices.com/entitlements/mcstore
   Authorization: Bearer <minecraft_access_token>
   ->  { items: [...], signature: <JWT>, keyId: "1" }          <-- OWNERSHIP GATE

6. GET  https://api.minecraftservices.com/minecraft/profile
   Authorization: Bearer <minecraft_access_token>
   ->  { id, name, skins, capes }
```

The `XboxLive.signin` scope is mandatory; without it the chain fails at step 2. `offline_access` is
what yields the refresh token, which is the only thing we persist.

### 2.2 The ownership gate -- do it properly

The naive check is "does `items` contain `product_minecraft` and `game_minecraft`". That is
necessary but **not sufficient**, because an attacker who controls the local network path or the
machine's trust store could return a forged JSON body.

The entitlements response is **signed**. The top-level `signature` field is an RS256 JWT whose
payload contains the entitlement list plus `signerId`, `nbf`, `exp`, `iat`, and `platform`. Mojang
publishes the RSA public key used to sign it.

**Rule:** we verify the JWT signature against Mojang's public key, check `exp`/`nbf`, and only then
read the entitlement list from **the verified JWT payload** -- never from the unsigned outer JSON.
The public key is compiled into the binary, not fetched at runtime.

Then:

- `game_minecraft` / `product_minecraft` present -> **Java Edition entitlement confirmed, launch permitted.**
- `items` empty -> no entitlement -> **refuse to launch**, with a clear message pointing at
  minecraft.net.
- Only `*_bedrock`, `*_dungeons`, `*_legends` entitlements -> owns other Minecraft products but not
  Java Edition -> **refuse to launch**, and say exactly that, because this is a genuinely confusing
  case for users.
- Game Pass entitlement present but `/minecraft/profile` returns `NOT_FOUND` -> the documented case
  of a Game Pass user who has never signed into the official launcher and therefore has no username
  yet. **Refuse to launch and tell them to run the official launcher once.** Do not attempt to work
  around it.

### 2.3 XSTS error handling

Step 3 fails with a 401 and an `XErr` code for several legitimate account states. We should surface
each with a real explanation rather than "login failed":

| XErr | Meaning | What we tell the user |
|---|---|---|
| 2148916227 | Account banned from Xbox | Account is banned from Xbox services |
| 2148916233 | No Xbox account exists | Sign in at minecraft.net once to create an Xbox profile |
| 2148916235 | Region where Xbox Live is unavailable | Not available in this region |
| 2148916236 / 2148916237 | Adult verification required (South Korea) | Complete verification on Xbox |
| 2148916238 | Child account not in a family group | An adult must add this account to a Microsoft family |

The `Redirect` URL Microsoft returns should be shown to the user as a link, **not** auto-opened.

### 2.4 What we explicitly will not build

- No offline mode, no "cracked" mode, no username-only login.
- No option to skip, cache-around, or fail-open the entitlement check. If the check cannot be
  completed, we do not launch. (A short grace period for a genuinely offline user with a recently
  verified, signed, unexpired entitlement JWT is defensible -- but it must be gated on the JWT's own
  `exp`, not on a flag we set. **Open question**, see §11.)
- No support for third-party "auth servers" or `authlib-injector`.
- No shipping, mirroring, or patching of Minecraft binaries.

### 2.5 Honest limitation

We are open source and we do not obfuscate or anti-tamper. Anyone can fork this and delete the
ownership check. **That is fine and it is not our problem to solve.** Our obligation is that the
software *we* publish verifies entitlement and does not ship an affordance for bypassing it. Building
DRM into an open-source privacy tool would be both futile and self-contradictory. We should say this
plainly in the README rather than implying a guarantee we cannot make.

### 2.6 Azure application approval -- schedule-critical

New Azure app registrations are **not** permitted to call `api.minecraftservices.com` by default;
unapproved apps receive **403**. Approval is requested through a Microsoft form and community reports
put the turnaround at weeks to months. Prism Launcher, MultiMC and similar projects have been through
this process.

**This must be submitted at the very start of the project, before implementation begins.** Everything
downstream of step 4 is untestable without it.

---

## 3. Credential storage

### What we store

| Item | Stored? | Where |
|---|---|---|
| Microsoft password | **Never.** Never seen -- the OAuth flow happens in the user's browser or via device code. | -- |
| Microsoft refresh token | Yes | OS credential store |
| Microsoft access token | No -- memory only, short-lived | -- |
| Xbox / XSTS tokens | No -- memory only | -- |
| Minecraft access token | No -- memory only, 24 h lifetime | -- |
| Entitlement JWT | Memory only (unless the offline-grace question in §11 is answered yes) | -- |
| Profile (UUID, username, skin URL) | Yes, plaintext | Launcher config -- it is public information |

Storing only the refresh token means a compromised config file leaks nothing, and a compromised
credential store leaks a token the user can revoke from their Microsoft account page.

### Where

| OS | Mechanism |
|---|---|
| Windows | Windows Credential Manager (`CredWrite`/`CredRead`, `CRED_TYPE_GENERIC`), which is DPAPI-backed per-user |
| macOS | Keychain |
| Linux | Secret Service (GNOME Keyring / KWallet) via D-Bus |

The Rust `keyring` crate abstracts all three; on Windows we may prefer `windows-rs` directly for a
smaller dependency surface. **Fallback when no secret store is available (headless Linux, some
minimal desktops): do not silently write the token to a file.** Ask the user whether to persist it in
a DPAPI/`gpg`-protected file or re-authenticate each session. Silently degrading to plaintext is how
launchers leak tokens.

### The access token on the command line -- a real, mostly-unavoidable leak

Minecraft 1.8.9's `minecraftArguments` string contains `--accessToken ${auth_access_token}`. The
resulting command line is visible to **any process on the machine**: `/proc/<pid>/cmdline` on Linux
(world-readable by default), `Get-CimInstance Win32_Process` on Windows, `ps` on macOS.

Every mainstream launcher has this exposure and it is inherent to how vanilla Minecraft accepts
credentials.

**[proposal]** On the Legacy Fabric profile we control the loader, so we can do better: pass a
placeholder on the command line and deliver the real token to the JVM out-of-band -- via an
environment variable (better) or a pipe on stdin (best) -- with a tiny early-init component that
substitutes it before `net.minecraft.client.main.Main` parses arguments. This would be a genuine,
demonstrable privacy improvement over every existing launcher, and it costs very little. It cannot be
done on the Forge compatibility profile without more invasive patching, so that profile keeps the
standard exposure and we should document that difference.

---

## 4. Threat model

| Threat | Mitigation |
|---|---|
| Malicious mod jar the user installs | We cannot prevent this -- a mod runs with full JVM privileges. We can refuse to auto-install mods, show provenance and hashes, and never install anything silently. |
| Malicious/compromised update to the launcher | ed25519-signed manifest, key compiled into the binary, signature verified before parsing; SHA-256 on the artifact; download-verify-rename; per-user install so no elevation is ever needed. |
| MITM on Mojang/Microsoft endpoints | TLS via `rustls` with the platform or bundled root store; **entitlement JWT signature verification** defends the ownership gate even against a fully compromised transport. |
| Malicious version JSON / asset index | Verify every downloaded file against the SHA-1 Mojang publishes. Reject any file whose hash does not match. Treat all metadata as untrusted input; strict deserialisation, bounded sizes. |
| Zip-slip in natives extraction | Natives jars are extracted to disk. **Canonicalise every entry path, reject any that escapes the target directory, reject absolute paths and symlinks, cap the decompressed size** (zip bomb). |
| Token theft from disk | Only the refresh token is persisted, and only in the OS credential store. |
| Token theft from process list | Documented above; mitigated on the Fabric profile by the out-of-band proposal. |
| Local privilege escalation via the launcher | Per-user install directory; never request elevation; never write to `Program Files` or `HKLM`; no service. |
| Supply-chain attack via a Rust dependency | `cargo-deny` + `cargo-audit` in CI; a small, deliberately-chosen dependency list; `Cargo.lock` committed; reproducible builds as a goal. |
| Argument injection into the JVM command line | Never build the command line through a shell. Use `std::process::Command` with an argument vector. Validate user-supplied JVM args against the rejected-flag list (§8) and reject shell metacharacters. |
| Concurrent launcher instances corrupting the game directory | Advisory lock file on the instance directory; all writes atomic (write-temp-then-rename). |

---

## 5. Network egress allowlist

The launcher may contact **only** these hosts, and every one of them has a stated purpose:

| Host | Purpose |
|---|---|
| `login.microsoftonline.com` / `login.live.com` | Microsoft OAuth |
| `user.auth.xboxlive.com` | Xbox Live user authentication |
| `xsts.auth.xboxlive.com` | XSTS authorization |
| `api.minecraftservices.com` | Minecraft login, entitlements, profile |
| `textures.minecraft.net` | The signed-in account's own skin, once after sign-in, to show their head |
| `piston-meta.mojang.com` | Version manifest and version JSON |
| `piston-data.mojang.com` / `launcher.mojang.com` | Client jar and libraries |
| `libraries.minecraft.net` | Libraries |
| `resources.download.minecraft.net` | Assets |
| `<our static release host>` | Launcher updates only |
| Java vendor host (e.g. Adoptium API) | JRE provisioning, **only if** the user chooses managed Java |
| `api.modrinth.com` / `cdn.modrinth.com` | **Only** on an explicit per-mod user action (see `mod-policies.md` §7) |
| `api.curseforge.com` / `edge.forgecdn.net` / `mediafilez.forgecdn.net` / `media.forgecdn.net` | Same rule, and **only** once the user has supplied their own CurseForge API key (see `docs/mod-browser.md`) |

Rules:

- **No request carries a unique identifier, cookie, or user-agent string containing an install ID.**
  A plain `UserAgent: <product>/<version>` is acceptable and honest.
- **No request is made before the user acts**, with the single exception of the update check, which
  is user-visible and disableable.
- Skin and cape textures for other players come from `textures.minecraft.net` **and are fetched by
  the game.** The launcher fetches exactly one: the signed-in account's own skin, after sign-in, to
  show the player's head. No credential is sent with it.
- This list should be asserted in an automated test that fails if any other host appears in the
  binary's string table or in a network trace of a full login-install-launch run.

---

## 6. Filesystem safety

- All writes confined to the launcher data directory (`%LOCALAPPDATA%` / `~/.local/share` /
  `~/Library/Application Support`) and the user-selected game directory.
- **Atomic writes everywhere**: write to a temp file in the same directory, fsync, rename. No
  partially written config, no half-downloaded jar treated as complete.
- **Every downloaded artifact verified by hash before use.** Mojang publishes SHA-1 for the client
  jar, every library, the asset index, and every asset object. There is no excuse for trusting an
  unverified file.
- **Never delete outside our own directories.** Note that PolyPatcher ships a "Log Optimizer" that
  deletes the contents of the logs folder with the warning *"these files are not recoverable once
  deleted."* If we offer anything similar it must be opt-in, scoped to our own instance directory,
  and preferably move to the OS recycle bin rather than unlink.
- **Never follow symlinks out of the target tree** during extraction.
- Treat the game directory as user data. Back up `options.txt` before modifying it, and never touch
  worlds, screenshots, or resource packs.

---

## 7. Process management

- Exactly one child process: the JVM.
- Spawned via an argument vector, never a shell string.
- The full command line is displayed in the UI (with the access token redacted) so the user can see
  exactly what we ran.
- Normal process priority. **Never** `REALTIME_PRIORITY_CLASS` or `HIGH_PRIORITY_CLASS` -- realtime
  priority on a game process can starve input and audio threads and destabilise the desktop.
- No CPU affinity masking by default. It is a plausible EXPERIMENTAL user preference on
  heterogeneous CPUs (P/E cores) but it is hardware-dependent and frequently harmful.
- The launcher may exit after spawning (user preference) or remain to stream logs. Either way there
  is no residual process once the user closes the launcher and the game.
- On launcher exit while the game runs, the game must survive. We are not its supervisor.

---

## 8. JVM flags: allowlist and rejected list

User-supplied JVM arguments are accepted, but validated. The following are **rejected outright**,
with an explanation rather than a silent drop:

| Flag | Why rejected |
|---|---|
| `-XX:+UseLargePages` / `-XX:+UseTransparentHugePages` (Windows) | Requires `SeLockMemoryPrivilege`, i.e. an elevation and a lasting security-relevant privilege grant, for a few percent |
| `-XX:+AggressiveOpts` | Deprecated and removed; an umbrella for unstable options |
| `-XX:+UnlockDiagnosticVMOptions` combined with unknown options | Unbounded risk surface |
| `-Xverify:none` / `-noverify` | Disables bytecode verification -- a real security control |
| `-XX:+DisableAttachMechanism` when we did not set it | Not dangerous, but it silently breaks profiling; flagged, not blocked |
| `-Xmx` above physical RAM, or above ~75% of it | Guarantees swapping and worse performance than the default |
| Anything enabling a remote debug/JMX listener on a non-loopback address | Opens a remote code execution channel |

Defaults we **do** set (see `PERFORMANCE_MATRIX.md` for the reasoning on each):
`-Xms` = `-Xmx` at a modest size, `-XX:+DisableExplicitGC`, and a pause-oriented collector
appropriate to the JVM actually in use.

---

## 9. Optimizations we refuse to implement

These are refused as a matter of policy. They are listed here so that "why doesn't your launcher do
X" has a permanent answer.

**System modification**
- Disabling or stopping Windows services.
- Registry "tweak packs" of any kind.
- Modifying TCP/IP parameters, Nagle's algorithm, `TcpAckFrequency`, QoS, or network adapter
  settings.
- Disabling Spectre/Meltdown or other CPU mitigations.
- Raising the global timer resolution (`timeBeginPeriod`) -- it is a system-wide change with battery
  cost, and the correct fix for frame pacing is a better limiter.
- Clearing the standby memory list, "RAM cleaners", or working-set trimming.
- Changing the global Windows power plan, or disabling CPU C-states / power management.
- Disabling Windows Game Bar / Game DVR via registry (a system-wide setting; if a user wants this,
  the OS settings app is the right place).
- Disabling, excluding from, or otherwise interfering with Defender or any security product,
  including "add an exclusion for your game folder" automation.

**Hardware**
- Overclocking, undervolting, fan curves, GPU driver setting overrides.
- Any BIOS/UEFI interaction.

**Process and privilege**
- Realtime or high process priority.
- Injecting into, reading the memory of, or enumerating processes other than our own JVM.
- Kernel drivers, filter drivers, or any signed-driver component.
- Running elevated. The launcher never needs admin, so it never asks.

**Identity and data**
- HWID generation, device fingerprinting, MachineGuid or MAC reading.
- Any telemetry, crash reporting, or "anonymous usage statistics", even opt-in in v1. If we ever
  reconsider, it must be a separate, loudly documented, default-off decision -- not a quiet setting.

**Ecosystem**
- Automating around OptiFine's ad-supported download page.
- Bundling or mirroring Minecraft binaries or any mod we lack distribution rights for.
- Bypassing CurseForge's author distribution opt-out.

**Game integrity**
- Anything that changes game logic in the player's favour: reach, hit registration beyond restoring
  vanilla-correct behaviour, autoclick, aim assistance, X-ray, or seeing entities that vanilla would
  not render. Our entity-culling work must be output-*reducing* only -- it may skip drawing something
  you could not see anyway; it may never reveal something you could not.

---

## 10. Fair-play stance

We are building a client for competitive multiplayer. Two commitments:

1. **We do not ship cheats, and we do not ship the scaffolding for them.** No client-side reach, no
   input automation, no ESP-adjacent rendering.
2. **We do not ship anti-cheat either.** No process scanning, no memory inspection, no driver, no
   attestation. Server-side anti-cheat is the server's job, and a client that spies on its own user
   to prove their innocence is exactly the invasive practice this project exists to avoid.

There is a genuine tension here: servers like Hypixel may not welcome a client they cannot inspect.
**Open question (§11):** whether we should proactively contact major server operators before launch.
I think we should -- it is cheap, and being on the "known good client" list matters more than any
optimization in this document.

Note also `mod-policies.md` §8: the Minecraft Usage Guidelines require that tools do not appear
official or approved, so branding must be clearly distinct from Mojang's.

---

## 11. Open questions

1. **Offline grace period.** Should a user with a valid, unexpired, signed entitlement JWT be able to
   launch without network access? The JWT carries its own `nbf`/`exp`, so this can be done without
   trusting any flag of ours -- but it is still a weakening of the gate. Decision needed.
2. **Access token out-of-band delivery.** Is the placeholder-substitution approach in §3 workable
   under Legacy Fabric without patching `net.minecraft.client.main.Main` in a way that breaks other
   mods? Needs a prototype.
3. **Azure approval timeline.** Submit immediately; everything downstream depends on it.
4. **JRE provisioning.** If we ship an OpenJDK build we inherit CVE-patching obligations and
   redistribution terms. Adoptium's terms need review.
5. **Server operator relations.** Should we proactively disclose the client to Hypixel and other
   major networks before public release?
6. **Code signing.** An unsigned Windows binary triggers SmartScreen and will get flagged as
   malware by some AV. EV code signing is expensive and requires a legal entity. Needs a plan; it
   affects distribution more than any technical decision here.
7. **Root store.** Bundle webpki roots (reproducible, but we own the update cadence) or use the
   platform store (respects enterprise trust config, but varies)? Leaning platform store.

---

## 12. Sources

- Microsoft authentication for Minecraft: endpoints, XErr codes, entitlement JWT and Mojang public
  key -- https://minecraft.wiki/w/Microsoft_authentication
- Microsoft Authentication Scheme (wiki.vg) -- https://wiki.vg/Microsoft_Authentication_Scheme
- HeliosLauncher Microsoft auth notes -- https://github.com/dscalzi/HeliosLauncher/blob/master/docs/MicrosoftAuth.md
- Azure app approval requirement for the Minecraft API -- https://learn.microsoft.com/en-gb/answers/questions/5768276/how-to-get-xboxlive-signin-permission-for-azure-ap
- Minecraft 1.8.9 version JSON (`minecraftArguments`, `--accessToken`) -- https://piston-meta.mojang.com/v1/packages/d546f1707a3f2b7d034eece5ea2e311eda875787/1.8.9.json
- Minecraft EULA -- https://www.minecraft.net/en-us/eula
- Minecraft Usage Guidelines -- https://www.minecraft.net/en-us/usage-guidelines
- Minecraft Performance Flags Benchmarks (Large Pages privilege warning) -- https://github.com/brucethemoose/Minecraft-Performance-Flags-Benchmarks
- CurseForge project distribution toggle -- https://support.curseforge.com/support/solutions/articles/9000207877-project-distribution-toggle
