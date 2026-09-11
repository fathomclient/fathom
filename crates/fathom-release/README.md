# fathom-release

Offline release signing. **Never linked into a shipped binary.**

## The dependency direction is the security property

```
launcher-ui ──┐
              ├──> launcher-core          (verification only; no private key type)
launcher-cli ─┘         ^
                        │
                 fathom-release           (the only crate that knows what a private key is)
```

Nothing depends on this crate. `cargo tree -i fathom-release` returns only
itself. That is what makes "the signing key never reaches the client" a fact
about the build graph rather than a promise in a document.

It depends on `launcher-core` in the other direction on purpose: the preimage it
signs comes from `update::signing::preimage`, the same function the launcher
verifies with. A signer carrying its own copy of that logic can drift from the
verifier and produce signatures nobody can check.

## The key is a path, never a value

There is no `--key` that takes key material, and no environment variable is
read. Both end up in a shell history, a process list, or a CI log. The intended
path is on offline or removable media that is present for the minute a release
is signed and absent the rest of the time.

`keygen` refuses to overwrite an existing file: silently replacing a signing key
destroys the only copy of it, and there is no undo.

## Meeting a threshold without co-locating keys

`sign` creates an envelope with one signature. `add-signature` adds another to
an existing envelope. **The envelope travels between signers; the keys stay
put.** No machine ever holds two of them, which is the entire point of a
threshold.

A second signature from a key already present is refused — a threshold counts
distinct keys.

## Commands

Run `fathom-release help`. In short:

```bash
fathom-release keygen --out root-1.key
fathom-release pubkey --key root-1.key
fathom-release hash   --file fathom.exe
fathom-release sign          --role release --key rel-a.key --in release.payload.json --out release.json
fathom-release add-signature --role release --key rel-b.key --envelope release.json
fathom-release verify        --role release --envelope release.json --pubkey <A> --pubkey <B> --threshold 2
```

`verify` runs the client's own verification code, so a release can be checked
before it is published using the path a user will take.

## See also

- `docs/research/update-security.md` — the trust chain and why it is shaped this way
- `crates/launcher-core/src/update/keys.rs` — where the public anchor goes
- `THREAT_MODEL.md` §6
