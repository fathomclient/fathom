# Update security: research, design, and the complete trust chain

Status: **implemented**, with the trust anchor deliberately absent. See §10.

An update channel is the highest-value target in this program. Everything else
an attacker might reach — a profile, a mod list, a session token — is one
machine. The update channel is *every* machine, with code execution, delivered
by software the user already trusts. It is designed accordingly.

---

## 1. The one-sentence version

**HTTPS is transport, not trust.** Every gate that decides whether bytes get
executed is an Ed25519 signature check or a SHA-256 check, and none of them
depends on the connection being honest.

---

## 2. Why TLS is not the trust model

TLS answers "am I talking to the host this name resolves to, and is anyone
listening". It does not answer "is that host telling me the truth".

Three of the attacks in the compromise model produce a **perfectly valid TLS
session**:

| Attack | What TLS sees |
|---|---|
| Compromised CDN | A valid certificate for the real name, serving the attacker's bytes. |
| Compromised release host | The same. The host is genuinely the host. |
| Compromised DNS + a certificate | A valid certificate for a name the attacker now controls. |

A design that stops at "we fetched it over HTTPS from our own domain" has no
answer to any of them. The signature does, because the release host never holds
a signing key — it serves files it could not have produced.

TLS and the host allowlist still earn their place. They stop a passive observer
and a casual man-in-the-middle, and they mean an attacker must at least be
somewhere in the path of a name we already know. They are the outer layer, not
the model.

---

## 3. Prior art, and what was taken from each

| System | What it does | Taken |
|---|---|---|
| **The Update Framework (TUF)** | Role separation, thresholds, monotonic versions, expiry, key rotation through a signed root | The shape of the whole design |
| **Sparkle** (macOS) | Ed25519 signature over the artifact, public key in the app | Ed25519 over an offline key; the simplicity of one anchor in the binary |
| **minisign / signify** | Minimal signature format, no PKI, no X.509 | The refusal to build a certificate hierarchy for a single publisher |
| **Chrome / Omaha** | Staged rollout, differential updates, a background service | **Rejected**: a background service is explicitly out of scope, and the rest is scale we do not have |
| **rustup** | Hash-bound artifacts listed in a manifest | Hash-binding via signed metadata rather than per-artifact signatures |

### 3.1 What was taken from TUF, and what was not

**Taken:**

- **Two roles**, root and release, with different key handling.
- **Thresholds** — more than one key must agree.
- **Monotonic version numbers** on metadata, as rollback protection.
- **Expiry** on metadata, as freeze protection.
- **Key rotation through a signed root**, so a stolen release key is
  recoverable.

**Not taken, deliberately:**

- **Separate `snapshot` and `timestamp` roles.** They exist to stop mix-and-match
  attacks across a repository with many independently-versioned targets. We
  publish one release document describing every component at once, so there is
  no mixing to do: the document *is* the snapshot.
- **Delegated targets.** For delegating parts of a repository to other
  publishers. There is one publisher.
- **Consistent snapshots.** For a repository large enough that a client may read
  it mid-update. Two small files are fetched in order.
- **Canonical JSON.** See §5 — we avoid needing it rather than implementing it.

Taking those would be copying TUF's answers to problems this project does not
have, at the cost of code that has to be correct.

---

## 4. The trust chain

```
  ROOT_KEYS                      compiled into the binary
     |                           2-of-N, offline, used a few times a year
     |  Ed25519, threshold, domain-separated
     v
  root.json                      version, expires, release_keys, thresholds
     |  authorises
     v
  release.json                   version, expires, components[]
     |                           each: version, os, arch, sha256, size, path
     |  binds by SHA-256
     v
  artifact bytes                 from a CDN we do not trust
     |  staged, re-hashed from disk
     v
  swap                           previous copy moved aside, not deleted
     |  validated: does it run?
     v
  installed                      backup retained until the next update
```

Every link is checked by the link above it. The only thing not derived from
something else is `ROOT_KEYS`, which is in the binary — so replacing it requires
replacing the binary, which requires the update channel, which requires
`ROOT_KEYS`.

### 4.1 Why two roles

One role would mean the key that signs every release is also the anchor. It
could then never be replaced without shipping a new binary, and it has to be
reachable by whatever process cuts a release. Those pull in opposite directions:
an anchor wants to be offline and rare; a release key wants to be available and
frequent.

Splitting them makes a **stolen release key a recoverable incident**. The root
signs a new `root.json` that drops it; clients pick that up on their next check
and stop accepting anything the old key signed. With one role, a stolen key is
unrecoverable for everyone who has already installed — the attacker can sign a
"launcher update" that replaces the anchor with their own.

`update_chain::a_new_root_revokes_a_compromised_release_key` is that scenario,
end to end, including the attempt to reinstate the stolen key by replaying the
old root.

### 4.2 Why a threshold

One key means one machine, one disk, one person, one mistake. 2-of-3 means an
attacker needs two independent compromises, and losing one key is an accident
rather than the end of the channel.

The threshold counts **distinct keys**, not signatures. Counting signatures
would let one compromised key satisfy a threshold of three by signing three
times, which is decoration rather than a control.
`one_key_cannot_satisfy_a_threshold_of_two_by_signing_twice` pins it.

---

## 5. Two decisions most signed-update designs get wrong

### 5.1 We verify the bytes we received, not a re-serialization

The common design signs a JSON object and the verifier re-serializes the parsed
object to reconstruct what was signed. That needs a **canonical form** — key
ordering, number formatting, string escaping, duplicate-key handling, Unicode
normalisation — and each is somewhere the verifier's idea of the document can
differ from the signer's. TUF needs canonical JSON for exactly this and has had
implementation bugs in it.

We avoid the problem rather than solving it. A signed document is:

```json
{ "payload": "<base64 of the UTF-8 JSON>", "signatures": [ { "keyid": …, "sig": … } ] }
```

Signatures are verified over the **decoded bytes, exactly as they arrived**.
Only after the threshold is met are those bytes parsed as JSON. There is no
canonical form to get wrong, and the only parser that ever runs on unverified
input is `serde_json` on a two-string-field envelope.

### 5.2 Every signature is bound to its role

A signature over a bare payload says "somebody with this key signed these
bytes". It does not say what those bytes were *for*. If both roles signed the
same way, a signature made in one position could be presented in the other.

So the signed message is domain-separated:

```
"fathom-update-v1" 0x00 <role> 0x00 <payload>
```

NUL cannot occur in the domain constant or in a role label, so the encoding is
injective: no two distinct `(role, payload)` pairs share a preimage.
`no_two_role_and_payload_pairs_share_a_preimage` asserts this against payloads
constructed specifically to try to forge a role boundary.

The version prefix means a future format revision cannot be confused with this
one — changing it invalidates every signature ever made, which is the point.

---

## 6. Rollback protection

A signature says "we published this". It does **not** say "this is current".
Every old, correctly-signed document stays valid forever, so an attacker who can
answer a request can serve last year's release — with a real signature — and get
a client to install a version whose vulnerabilities are public.

The only defence is remembering. `update/trust.rs` records:

| Floor | Stops |
|---|---|
| Highest root version accepted | Replaying an old root to reinstate a revoked key |
| Highest release version accepted | Replaying an old release document |
| Installed version per component | A current document offering an old artifact |

Plus **expiry** on both documents, which bounds a freeze attack: an attacker who
can block traffic can stop a client learning about a new version, but cannot
keep it trusting the old metadata indefinitely.

`>=` is accepted, `<` is refused. Re-fetching the current document is normal and
must not fail; only a strictly lower version is an attack.

### 6.1 The trust state is not tamper-proof, and does not claim to be

It is an unsigned file in the user's own data directory. Anyone who can write to
it can reset our memory.

Accepted, because that attacker can already replace the launcher binary — which
is strictly easier than constructing a downgrade. Signing the state with a key
that lives on the same disk would be theatre. What it defends against is the
entire network-side model, which is what this subsystem is for.

---

## 7. Artifacts: hash-bound rather than individually signed

The brief allows "signed **or** hash-bound artifacts". We hash-bind, and the
reason is worth stating: a SHA-256 inside a signed document is exactly as strong
as a signature on the artifact, and it is **cheaper to verify and harder to get
wrong**. One signature check covers every artifact in the release, and there is
no second signature format to parse.

It is strictly better in one way: an artifact and its signature can be separated
— served, cached, or replaced independently. An artifact and the document naming
its hash cannot be, because the hash is inside the thing that is signed.

Both size and hash are checked, size first. A truncated download is then
reported as "expected N bytes, received M" rather than as an opaque hash
mismatch, which is a materially better message for the most common failure.

---

## 8. Atomic replacement, and never destroying the only good copy

```
  staged        the download, hash-verified in memory
    |  1. re-verify from disk  (the staged file is an ordinary file in between)
    |  2. write the journal
    v
  backup <- target   3. the known-good copy moves aside. NOT deleted.
    |
    v
  target <- staged   4. the replacement takes its place
    |
    |  5. validate: does it actually run?
    |
    +-- yes --> journal removed; backup kept until the next update
    +-- no  --> target removed, backup moved back, journal removed
```

Each of 3, 4 and 5 records its completion in a journal before proceeding, so a
process that dies anywhere leaves enough to finish or undo.

### 8.1 The launcher supervises its own replacement

A running executable cannot be overwritten on Windows, but it **can be renamed**
— which is what step 3 relies on. The harder problem is step 5: the thing that
decides whether the replacement works cannot be the replacement.

So the **old process supervises**. `apply` runs in the launcher that is already
running, does the swap, spawns the new binary with `--self-check`, and waits. If
the new binary will not start, the old process — still alive, still knowing
where the backup is — puts it back.

This is why there is no background service and no separate updater executable:
the supervisor is the process the user already started.

### 8.2 Recovering an interruption

`Installer::recover` reads the journal and decides from **what is on disk**,
not from an assumption about which side of the crash it is on:

| Stage reached | Recovery |
|---|---|
| `Staged` | Nothing moved. Discard the download. |
| `BackedUp` | The target may be missing entirely. Restore the backup. |
| `Swapped` | Hash the target. If it matches and — for the launcher — the running version *is* the new version, keep it. Otherwise roll back. |
| Unreadable journal | Report it and **leave it alone**. A record we cannot read is the only trace of what happened. |

The `Swapped` case is the interesting one. For the launcher, "the new version is
what is running" is *better* evidence than the self-check would have produced,
because it is the real thing rather than a probe.

---

## 9. What this cannot recover from

**Power loss between the swap and a successful validation, where the new binary
also fails to start.**

Nothing is running to notice: the next start is the broken binary, which cannot
recover itself. The backup is on disk and `fathom-cli update restore launcher
<path>` will put it back, but that needs a person or a second executable.

Closing it properly needs either a second small binary that runs first, or a
platform service — and a service is out of scope by instruction, correctly.
Recorded here rather than papered over.

The window is narrow: it requires a crash inside the few seconds between the
rename and the self-check returning, *and* a replacement that does not start.
Either alone is handled.

---

## 10. The trust anchor is deliberately absent

`ROOT_KEYS` is empty. `update_channel_is_configured()` is false. `check()`
refuses **before touching the network**, because a launcher that cannot verify a
manifest must not fetch one — displaying an unverified manifest would be
presenting an attacker's text as ours.

There is no key because **there is no key**. Generating a keypair takes a
second; the hard part is that the private half must be created on an offline
machine, stored on hardware, and never touch this repository, this filesystem,
or CI. That has not been done, so there is nothing honest to put there.

Both alternatives were worse:

- **A placeholder whose private half is in the repository** — the channel is
  then signed by a key every reader has, which is indistinguishable from no
  signature while looking like one.
- **A placeholder whose private half does not exist** — updates silently never
  verify, and the failure looks like a network problem.

Nothing about the mechanism is stubbed. Every verification path is implemented
and tested against injected keys, and `crates/fathom-release` signs real
documents that the real verifier accepts. What is missing is a secret that must
not be created here.

### 10.1 Adding the anchor

1. Offline: `fathom-release keygen --out root-1.key`. Three times, on three
   machines, if the threshold is to mean anything.
2. Each private key onto separate hardware. Never copied.
3. Paste the printed **public** keys into `ROOT_KEYS`; set `ROOT_THRESHOLD`.
4. Sign a `root.json` naming the release keys; publish it.

Only step 3 touches this repository, and it adds public material only.
`this_build_ships_without_a_trust_anchor` fails when a key is added, forcing
whoever does it to read why the list was empty.

---

## 11. The signing key never reaches the client

`crates/fathom-release` is the only crate in the workspace that knows what a
private key is. The launcher depends on `launcher-core`; **nothing depends on
`fathom-release`**. That direction makes it a property of the dependency graph
rather than a promise in a document.

It depends on `launcher-core` the other way round, deliberately: the preimage it
signs comes from `update::signing::preimage`, the same function the launcher
verifies with. A signer with its own copy of that logic can drift from the
verifier and produce signatures nobody can check.

The key is passed as a **path**, never as a value. There is no `--key` taking
the key itself and no environment variable is read, because both end up in a
shell history, a process list, or a CI log.

`keys.rs` carries a test that scans its own source for private-key markers.

---

## 12. Privacy

An update check is a `GET` of a fixed path. `/v1/root.json` and
`/v1/release.json` — constants, not templates.

**Not sent:** hardware id, install id, machine GUID, username, Microsoft account
details, OS build, locale, screen resolution, profile names, or a count of
anything.

**Sent:** the HTTP request itself, and `User-Agent: Fathom/<version>`. The
version is a fact about the software rather than about the machine, and every
client on that version sends exactly the same bytes.

There is no telemetry endpoint, no "update check succeeded" callback, and no
query string — `network::policy::descriptor_from_url` strips query and fragment
before a request is built, so one cannot be added by accident.

A passive observer learns that a Fathom user checked for updates, which is
inherent to fetching a file over the network at all.

---

## 13. Component scope

Three components, as a closed enum:

| Component | What it is |
|---|---|
| `launcher` | The executable |
| `optimization-module` | Fathom's own Minecraft-side jar |
| `metadata` | The bundled mod catalogue and policy data. Data only, never code |

`ComponentId` has **no `Other(String)` variant**, so a third-party mod cannot be
installed through this channel however the metadata is written. That is the
`mod-policies.md` §7 rule expressed as a type: a third-party mod is not a
first-party component, is not signed by us, and goes through the mod engine —
where its licence, provenance and hash are the user's decision — not through the
updater.

An unrecognised component id in a release document is **skipped, not fatal**: a
newer release naming a component this build has never heard of should not stop
it updating the ones it knows.

---

## 14. Test coverage

`crates/launcher-core/tests/update_chain.rs` builds real signed documents with
real Ed25519 keys and runs them through the real verification code. The only
thing mocked is the transport — which is the part explicitly not trusted, so
simulating a hostile server is testing the thing that matters.

| Required case | Tests |
|---|---|
| Bad signature | `a_document_signed_by_the_wrong_key_is_refused`, `a_corrupted_signature_is_refused`, `a_payload_edited_after_signing_is_refused`, `one_key_cannot_satisfy_a_threshold_of_two_by_signing_twice`, `a_signature_cannot_be_replayed_into_a_different_role` |
| Modified artifact | `an_artifact_that_does_not_match_the_signed_hash_is_refused` (same-length substitution, so only the hash catches it) |
| Replayed metadata | `a_replayed_older_root_is_refused`, `a_replayed_older_release_is_refused`, `current_metadata_offering_an_old_build_is_not_an_upgrade`, `expired_metadata_is_refused_however_well_signed`, `the_same_version_is_accepted_so_a_second_check_in_a_day_works` |
| Interrupted update | `an_interruption_before_anything_moved_just_discards_the_download`, `an_interruption_with_the_target_moved_aside_restores_it`, `a_completed_swap_is_kept_when_the_new_version_is_the_one_running`, `a_completed_swap_is_rolled_back_when_the_old_version_is_still_running`, `a_swap_that_left_a_corrupt_target_is_rolled_back` |
| Wrong architecture | `a_build_for_another_architecture_is_never_offered`, `the_right_architecture_is_chosen_from_a_mixed_list`, `an_executable_may_not_be_published_for_any_platform` |
| Rollback | `a_replacement_that_fails_its_self_check_is_rolled_back`, `restore_puts_the_backup_back_on_request`, `a_good_replacement_is_installed_and_the_previous_copy_is_kept` |
| Malformed manifest | `malformed_documents_are_refused_without_panicking` (12 shapes including truncation, deep nesting and oversize), `a_verified_payload_that_is_not_the_expected_shape_is_refused`, `a_root_that_cannot_authorise_anything_is_refused`, `a_future_format_is_refused_rather_than_interpreted` |
| Partial download | `a_truncated_download_is_refused_and_named_as_such` |
| Key revocation | `a_new_root_revokes_a_compromised_release_key` |

### 14.1 The signing tool was exercised against the verifier

Not only unit-tested. A full release was cut with the real binary: three keys
generated, a root signed by two of them, a release signed by the third, both
verified through the client's own code path. Then the adversarial half — each
refused with a specific message:

```
threshold 2 with one key      → root metadata carries 1 valid signature(s) but needs 2
release envelope as root      → root metadata carries 0 valid signature(s) but needs 1
release payload signed as root→ the payload says role="release" but it is being signed as root
same key signing twice        → already signed by <id>; a threshold counts distinct keys
one byte flipped in payload   → release metadata carries 0 valid signature(s) but needs 1
```

---

## 15. Open items

| Item | Status |
|---|---|
| Real root keys | **Open by design.** §10. |
| The §9 window | **Open.** Needs a second binary or a service; a service is out of scope. |
| Automatic download | Not built. Verification is mandatory either way, so this is a UI decision, not a security one. |
| Differential updates | Not built, and unlikely to be worth it at this size. |
| Release-host infrastructure | Not built. `Destination::LauncherUpdates` resolves to `releases.invalid` until a real host exists — an unresolvable name is a safer placeholder than a real one somebody else could register. |
| Code signing (Authenticode) | Separate from this, and needs a legal entity. An unsigned binary trips SmartScreen whatever the update channel does. |

---

## 16. References

- `THREAT_MODEL.md` §6 — the threat this answers
- `crates/launcher-core/src/update/` — the implementation
- `crates/fathom-release/` — the signing tool
- `docs/research/security-model.md` §5 — the update policy this follows
- The Update Framework specification — the design this is a subset of
