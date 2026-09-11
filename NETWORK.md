# Network

Every outbound request this program can make, why it exists, and how the code
prevents any other request from happening.

---

## 1. The rule

**A caller cannot express a hostname.**

Reaching the network requires a `RequestDescriptor`, and a `RequestDescriptor`
requires a `Destination` — a Rust enum with fourteen variants and no
`Other(String)`. The URL is built inside the network module by concatenating
`https://` with the variant's compiled-in host:

```rust
pub struct RequestDescriptor {
    pub destination: Destination,   // an enum variant, not a string
    pub purpose: Purpose,           // why
    pub feature: Feature,           // what the user did to cause it
    pub path: String,               // absolute path, validated
}
```

So the set of reachable hosts is fixed at compile time. Adding one is a diff
against `crates/launcher-core/src/network/policy.rs` that a reviewer sees.

This is not only a code-hygiene point. Mojang's version metadata contains
absolute URLs for the client jar, every library and every asset — documents we
download and therefore do not trust. Those URLs go through
`descriptor_from_url`, which parses the host and looks it up in the allowlist. A
tampered manifest pointing a "library" at another host is refused rather than
fetched. There are tests for `https://evil.example/...`,
`https://libraries.minecraft.net.evil.example/...`,
`https://user:pass@libraries.minecraft.net/...` and
`https://libraries.minecraft.net:8443/...`.

---

## 2. The allowlist

Run `fathom-cli allowlist` to print this from the binary itself. It is also the
first thing on the Privacy screen.

| Host | Purpose | Consent |
|---|---|---|
| `login.microsoftonline.com` | Device-code sign-in and token exchange | core |
| `user.auth.xboxlive.com` | Xbox Live user authentication | core |
| `xsts.auth.xboxlive.com` | XSTS authorisation | core |
| `api.minecraftservices.com` | Minecraft login, **entitlement check**, profile | core |
| `piston-meta.mojang.com` | Version manifest and version JSON | core |
| `launchermeta.mojang.com` | Mojang's older metadata host; 1.8.9's asset index still points here | core |
| `piston-data.mojang.com` | Client jar and libraries | core |
| `launcher.mojang.com` | Older Mojang download host, still referenced by 1.8.9 | core |
| `libraries.minecraft.net` | Libraries referenced by 1.8.9 metadata | core |
| `resources.download.minecraft.net` | Asset objects | core |
| `releases.invalid` *(placeholder)* | Launcher update check | on unless disabled |
| `meta.legacyfabric.net` | Legacy Fabric loader metadata | core |
| `maven.legacyfabric.net` | Legacy Fabric mappings; redirects to the host below | core |
| `repo.legacyfabric.net` | Where `maven.legacyfabric.net` redirects artifacts | core |
| `maven.fabricmc.net` | Fabric loader jar and its libraries | core |
| `api.adoptium.net` | Java runtime download | opt-in |
| `api.modrinth.com` | Mod metadata | per action |
| `cdn.modrinth.com` | Mod download | per action |

"core" means the request is implied by something the user just did: pressing
Sign in, pressing Play, installing a profile. Nothing marked core happens on
its own.

`releases.invalid` is deliberately unresolvable, and an unresolvable name is a
safer placeholder than a real one somebody else could register. There is no
update endpoint
yet, and pointing at a real host we do not operate would be worse than pointing
at nothing.

`launchermeta.mojang.com` was **not** in the original research allowlist. The
live integration test (`tests/live_mojang.rs`) found it by walking every URL in
Mojang's real 1.8.9 document and asserting each resolves to an allowlisted host.
Without it, asset installation would have failed. That is the test earning its
keep, and it is why it exists.

**Not in this table, and worth stating:** skin and cape textures come from
`textures.minecraft.net`, and they are fetched **by the game, not by the
launcher**. The launcher never requests them.

---

## 3. Consent

Four levels, enforced in `NetworkPolicy::evaluate`:

| Level | Meaning | Default |
|---|---|---|
| `CoreFlow` | Implied by the action the user already took | allowed |
| `OptOut` | Happens unless turned off — the update check, and only that | allowed |
| `OptIn` | Only if switched on — managed Java | **off** |
| `ExplicitPerAction` | Needs a fresh decision every single time — mods | **off** |

`ExplicitPerAction` needs both the setting *and* a `PerActionGrant`, a
capability token that is deliberately not `Clone` and not constructible from
deserialised data. Remote content cannot mint one; only user-facing code can.

Offline mode is a master switch above all of this. When it is on, every request
is refused before a socket is opened, including sign-in.

---

## 4. What every request does and does not carry

**Carried:**

- `User-Agent: Fathom/0.1.0` — the product name and version, and nothing else.
- `Accept: application/json`.
- `Authorization: Bearer <token>` on the two Minecraft Services calls that need
  it, held as a `Secret` so it cannot land in a log.

**Not carried, at all:**

- No cookie. The `cookies` feature of the HTTP client is not enabled, so there is
  no cookie jar to persist and no server can establish a cross-request
  identifier through one.
- No install id, machine id, HWID, MAC address, or any other persistent
  identifier. None is generated anywhere in the codebase.
- No OS build, CPU model, GPU, locale or screen resolution.
- No query-string parameters we did not need. `descriptor_from_url` **strips
  query strings and fragments** from URLs found in Mojang metadata, so a
  tracking parameter appearing in an upstream document does not get echoed back.

Hardware facts the launcher does read — core count, physical memory, installed
Java versions — are used for JVM sizing and stay on the machine. There is no code
that transmits them.

---

## 5. Requests made without the user asking

Exactly one: the launcher update check, at most once per start, and it can be
turned off in Settings.

Two plain GETs for fixed paths:

```
GET /v1/root.json
GET /v1/release.json
```

Constants, not templates. Nothing is interpolated into them, so there is no
place for an identifier to be added by accident.

**What is not sent:** hardware id, install id, machine GUID, username,
Microsoft account details, OS build, locale, screen size, profile names, or a
count of anything. No cookie — no cookie jar exists and the feature is not
enabled. No query string — `descriptor_from_url` strips query and fragment
before a request is built.

**What is sent:** the request, and `User-Agent: Fathom/<version>`. The version
is a fact about the software rather than about the machine, and every client on
that version sends byte-identical requests.

The server learns an IP address and a timestamp, which is unavoidable for any
HTTP request and is stated here rather than glossed over.

### 5.1 The check is implemented, and refuses to run in this build

`crates/launcher-core/src/update/` is complete. It does not run here, because
`ROOT_KEYS` is empty: with no compiled-in signing key, an update could not be
verified even if one were offered, so `check()` **refuses before opening a
socket**. A launcher that cannot verify a manifest must not fetch one —
displaying an unverified manifest would be presenting an attacker's text as
ours.

`fathom-cli update status` prints that state; `fathom-cli update check` reports
the refusal rather than "up to date", because those are different facts.

`docs/research/update-security.md` covers the trust chain.

---

## 6. Transport

- **rustls only.** No OpenSSL, no `native-tls`, no system TLS. `deny.toml` bans
  all three, so an accidental second TLS stack fails the build.
- **HTTPS only.** `descriptor_from_url` rejects `http://` outright.
- Global timeout 300 s, connect timeout 30 s.
- Responses held in memory are capped at 32 MiB; streamed downloads at 512 MiB.
  A hostile or broken endpoint cannot exhaust memory.
- Non-2xx responses are returned to the caller rather than raised as transport
  errors, because the auth chain needs to read XSTS error bodies to explain
  *why* a sign-in failed.

### Redirects are followed by hand

The HTTP client is configured with `max_redirects(0)`. Redirects are not
disabled — Mojang's download hosts use them, and `maven.legacyfabric.net` 302s
every artifact request to `repo.legacyfabric.net` — but the library is not
allowed to follow them on its own.

Both `send` (buffered) and `stream` resolve each `Location` header themselves,
rebuild a `RequestDescriptor` from the resulting URL, and re-check it against
the allowlist before the next hop. The check in §1 therefore applies to **every
hop**, not just the first one.

**Only GET redirects are followed.** A redirected POST would mean replaying a
body — possibly one carrying a credential — at a host the original request did
not name. None of our POST endpoints redirect, and the client will not do it.

Without this, the allowlist would have had a hole exactly where it matters
most: a tampered version document could point a "library" at an allowlisted
host that then 302s anywhere, and the initial check would have passed. At most
five hops are followed; a missing or unparseable `Location`, a hop off the
allowlist, or a sixth redirect is an error, never a fallback.

Streaming also checks the cancel flag between 64 KiB chunks, so a cancelled
install stops transferring rather than running to completion in the background.

---

## 7. Integrity

Everything downloaded is verified before it is used.

- Mojang publishes SHA-1 for the client jar, every library, the asset index and
  every asset object. Each is checked, and a mismatch means the bytes are
  **discarded, not quarantined** — nothing unverified is ever written into the
  shared cache.
- The version JSON is checked against the SHA-1 in the version manifest before
  it is parsed as anything meaningful. So is the asset index, before it is
  expanded into 734 object requests.
- Bytes are hashed **as they are written**, not re-read afterwards, and land at
  a `.part` path that is renamed into place only once the hash matches. A file
  sitting at its real path is therefore proof that it verified.
- **A hash mismatch is never retried.** Network errors and timeouts are
  transient and are retried up to three times; a wrong hash or a wrong size is
  not, because retrying corrupt bytes is how a launcher eventually accepts
  them. The install fails and names the artifact.
- Streamed artifacts are capped at 512 MiB each, so a hostile or broken
  response cannot fill the disk before the declared-size check would catch it.
- The entitlement response is checked by RS256 signature against a key compiled
  into the binary, so the ownership gate holds even against an attacker who
  fully controls the transport.

---

## 8. The request journal

Every request — allowed, refused, or failed — is recorded in memory with its
destination, purpose, initiating feature, path and outcome. The Privacy screen
renders it; `fathom-cli` can print the summary.

It is **never written to disk and never transmitted.** It is bounded at 500
entries. It exists so the user can audit the launcher, which is the opposite of
telemetry: the data flows towards the person the program runs for.

---

## 8a. The mod repository

Two hosts, one repository, and the strictest consent class in the table.

`api.modrinth.com` and `cdn.modrinth.com` are `ExplicitPerAction`: they are
unreachable unless **both** the mod-repository setting is on in Privacy settings
**and** the specific request carries a `PerActionGrant` naming what the user
asked for. A grant is not `Clone` and cannot be deserialised, so nothing that
arrives over a network can mint one. There is no code path that searches, checks
for updates, or installs anything on a timer.

**Why Modrinth and not CurseForge.** Modrinth's terms grant third-party
applications an API licence to query and download the content it indexes.
CurseForge's API carries a per-project third-party-distribution flag that can
forbid automated delivery regardless of the software licence; honouring it
properly means refusing a large part of its catalogue, and a source we would
have to refuse half the time is worse than a link to the official page. So the
catalogue links, and does not fetch.

**What is checked before bytes are written.**

1. The **host** never comes from a response. Search and version requests are
   `RequestDescriptor`s naming a compile-time `Destination`. A download URL,
   which does arrive in a response body, goes through `descriptor_from_url`,
   which refuses anything that is not HTTPS on an allowlisted host with a safe
   path.
2. The **query** is built here and percent-encoded, so a search for `a&b=c` is
   one parameter rather than two.
3. The **compatibility** filter is applied to the query *and* re-applied to the
   response, because what a server returns is its own business and a
   compatibility claim is not a compatibility check.
4. The **hash** is the publisher's. `ModStore::adopt_bytes` computes SHA-1 over
   what actually arrived and refuses to write anything that does not match what
   the API said. A version whose file carries no usable hash is dropped from the
   results rather than offered without one.
5. The **archive** must begin like one. Four bytes, not an unpack: nothing here
   opens a jar or reads its metadata.

**What is never fetched.** OptiFine, whose licence forbids redistribution;
anything the catalogue marks `UserSuppliedOnly` or `Unclear`; and any project
whose licence Fathom has not established. Those appear in the interface with the
reason and a link to the author, which is the honest surface for a file the
launcher may not hand you.

---

## 9. How to check these claims

Do not take this document's word for it.

```bash
cargo run -p launcher-cli -- allowlist
```

That prints the allowlist out of the compiled binary, so it cannot drift from
what the code does.

```bash
cargo test -p launcher-core network
```

Those tests assert that URLs are always HTTPS on an allowlisted host, that
hostile URLs are refused, that offline mode denies everything, that mod
downloads need both the setting and a per-action grant, and that a denied
request never reaches the transport.

At runtime, point a proxy or a packet capture at the process and compare against
§2. A full sign-in, install and launch should touch nothing outside that table.

**A gap worth naming:** there is no automated test that greps the built binary
for stray hostnames, and no CI network trace. `security-model.md` §5 asks for
both. Until they exist, §2 is enforced by the type system and by review, which
is strong but not the same as proven.
