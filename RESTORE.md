# Restoring Fathom on another PC

Snapshot taken 2026-11-02. Everything below comes from this repository, except
the passphrase, which is deliberately not stored here.

## 1. Source

```bash
git clone https://github.com/fathomclient/fathom.git client
cd client
git checkout -b main-restored origin/backup/2026-11-02/main
git branch experiment/entity-occlusion origin/experiment/entity-occlusion
```

`backup/2026-11-02/main` is the local `main` at the time of the snapshot,
including work not yet on the public `main`.

## 2. Build tools

Rust comes from `rust-toolchain.toml` (install rustup first). Also needed: JDK 8
and 17 (see `.github/workflows/ci.yml` for the versions CI uses) and Node.

Gradle is not in the repository, and `tools/build-all.ps1` looks for it in one
fixed place. From the repository root:

```powershell
curl.exe -LO https://services.gradle.org/distributions/gradle-8.10.2-bin.zip
Expand-Archive gradle-8.10.2-bin.zip .tmp-gradle-8.10.2
cd desktop; npm ci; cd ..
powershell -ExecutionPolicy Bypass -File tools\build-all.ps1
```

`Fathom.exe`, `resources\`, `Fathom-Dev\` and `Fathom-Forge-Test\` are build
output, so they are recreated by the build rather than restored. Pass
`-OutputName <name>.exe` for the test launchers.

## 3. Private files (encrypted)

`fathom-private.tar.gz.gpg` holds the local-only development files, the old git
history and Claude's memory notes. It needs the passphrase saved from
`fathom-backup-passphrase.txt`. `gpg` ships with Git for Windows; run this in Git
Bash, with the passphrase in a file outside the repository:

```bash
git fetch origin backup/2026-11-02/private-encrypted
git show FETCH_HEAD:fathom-private.tar.gz.gpg > fathom-private.tar.gz.gpg
gpg --batch --pinentry-mode loopback --passphrase-file /path/to/passphrase.txt \
    -o fathom-private.tar.gz -d fathom-private.tar.gz.gpg
tar -xzf fathom-private.tar.gz -C ~
```

To check the download, compare `sha256sum fathom-private.tar.gz.gpg` with
`SHA256SUMS` on this branch.

That creates three folders in your home directory:

- `fathom-private-archive/`: copy `dev-run/`, `run-ab/` and `run-mp/` over
  `minecraft/fathom-optimizations/run`, `run-ab` and `run-mp`.
- `fathom-old-history-backup/`: history from before the repository was reset.
  Keep it as is; do not push it.
- `claude-memory/`: copy to
  `~/.claude/projects/<project-slug>/memory/`, where the slug is the checkout
  path with separators replaced by `-` (`C--Users-jb-client` for
  `C:\Users\jb\client`).

## Not in this backup

Third-party jars (Minecraft, Forge, OptiFine) are downloaded again; OptiFine has
to be fetched by hand, see `START-HERE.md`. Launcher accounts and tokens are not
included: sign in again. Release signing keys are kept offline, never here.
