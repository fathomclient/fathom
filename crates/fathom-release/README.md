# fathom-release

Offline signing for launcher updates. It is never linked into anything that
ships.

```
launcher-desktop ──┐
                   ├──> launcher-core     (verifies; has no private-key type)
launcher-cli ──────┘         ^
                             │
                      fathom-release      (the only crate that handles private keys)
```

Nothing depends on this crate (`cargo tree -i fathom-release` shows only
itself), so a signing key can't end up in the client through the build. It
depends on `launcher-core` for `update::signing::preimage`, so it signs exactly
what the launcher verifies.

## Keys

Keys are passed as file paths, never as values or environment variables, which
would end up in shell history or logs. Keep them on offline or removable media.
`keygen` won't overwrite an existing key.

For a multi-signer threshold, `sign` creates an envelope with one signature and
`add-signature` adds the next. The envelope moves between signers and the keys
stay where they are. Signing twice with the same key is refused.

## Commands

```powershell
fathom-release keygen --out root-1.key
fathom-release pubkey --key root-1.key
fathom-release hash   --file fathom.exe
fathom-release sign          --role release --key rel-a.key --in release.payload.json --out release.json
fathom-release add-signature --role release --key rel-b.key --envelope release.json
fathom-release verify        --role release --envelope release.json --pubkey <A> --pubkey <B> --threshold 2
```

`verify` uses the launcher's own verification code, so you can check a release
the same way a user's launcher will.

See also `docs/research/update-security.md`, `crates/launcher-core/src/update/keys.rs`
and section 6 of `THREAT_MODEL.md`.
