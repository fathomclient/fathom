//! Offline release signing.
//!
//! # This crate is never linked into a shipped binary
//!
//! It is the only place in the workspace that knows what a private key is. The
//! launcher depends on `launcher-core`; nothing depends on this. That direction
//! is what makes "the signing key never reaches the client" a property of the
//! dependency graph rather than a promise in a document.
//!
//! It depends on `launcher-core` the other way round, deliberately: the
//! preimage this tool signs is produced by `update::signing::preimage`, the
//! same function the launcher verifies with. A signer with its own copy of that
//! logic is a signer that can drift from the verifier and produce signatures
//! nobody can check.
//!
//! # Where the key lives
//!
//! Not here. Not in this repository, not in CI, not in an environment variable
//! that a build system logs. `sign` takes a **path** to a key file, and the
//! intended path is on removable or offline media that is present for the
//! minute a release is signed and absent the rest of the time.
//!
//! There is no `--key` that takes the key itself, and no environment variable
//! is read, because both end up in a shell history or a process list.
//!
//! # Usage
//!
//! ```text
//! fathom-release keygen --out root-1.key
//! fathom-release pubkey --key root-1.key
//! fathom-release sign --role root    --key root-1.key --in root.payload.json    --out root.json
//! fathom-release sign --role release --key rel-1.key  --in release.payload.json --out release.json
//! fathom-release add-signature --role root --key root-2.key --envelope root.json
//! fathom-release verify --role root --envelope root.json --pubkey <base64> --threshold 2
//! fathom-release hash --file fathom.exe
//! ```

use std::path::{Path, PathBuf};
use std::process::ExitCode;

use ed25519_dalek::{Signer, SigningKey};
use launcher_core::update::manifest::{SignatureEntry, SignedEnvelope};
use launcher_core::update::signing::{self, PublicKey, Role};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let refs: Vec<&str> = args.iter().map(String::as_str).collect();

    let result = match refs.split_first() {
        Some((&"keygen", rest)) => keygen(rest),
        Some((&"pubkey", rest)) => pubkey(rest),
        Some((&"sign", rest)) => sign(rest),
        Some((&"add-signature", rest)) => add_signature(rest),
        Some((&"verify", rest)) => verify(rest),
        Some((&"hash", rest)) => hash(rest),
        Some((&"help" | &"--help" | &"-h", _)) | None => {
            usage();
            return ExitCode::SUCCESS;
        }
        Some((other, _)) => Err(format!("unknown command {other:?}. Try `help`.")),
    };

    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("error: {message}");
            ExitCode::FAILURE
        }
    }
}

fn usage() {
    println!("{}", include_str!("usage.txt"));
}

// ===========================================================================
// Key handling
// ===========================================================================

/// Generate a keypair and write the private half to a file.
///
/// Refuses to overwrite: silently replacing a signing key would destroy the
/// only copy of it, and there is no undo for that.
fn keygen(args: &[&str]) -> Result<(), String> {
    let out = PathBuf::from(required(args, "--out")?);
    if out.exists() {
        return Err(format!(
            "{} already exists. Refusing to overwrite a signing key.",
            out.display()
        ));
    }

    let signing_key = SigningKey::generate(&mut rand_core::OsRng);
    let public =
        PublicKey::from_bytes(signing_key.verifying_key().as_bytes()).map_err(|e| e.to_string())?;

    // Base64 of the 32-byte seed, and nothing else. No PEM, no metadata: a key
    // file that is only key material is a key file that cannot leak anything
    // else, and it is obvious what it is.
    let encoded = signing::encode_base64(signing_key.as_bytes());
    write_private(&out, &encoded)?;

    println!("Wrote the private key to {}", out.display());
    println!();
    println!("Public key  : {}", public.to_base64());
    println!("Key id      : {}", public.id());
    println!();
    println!("Put the private key on offline media now, and do not copy it anywhere else.");
    println!("The public key above is what goes in the launcher or in root.json.");
    Ok(())
}

/// Write with owner-only permissions where the platform has them.
fn write_private(path: &Path, contents: &str) -> Result<(), String> {
    #[cfg(unix)]
    {
        use std::io::Write as _;
        use std::os::unix::fs::OpenOptionsExt as _;
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(path)
            .map_err(|e| format!("could not create {}: {e}", path.display()))?;
        file.write_all(contents.as_bytes())
            .map_err(|e| format!("could not write {}: {e}", path.display()))?;
        return Ok(());
    }
    #[cfg(not(unix))]
    {
        // Windows inherits the directory ACL. The intended directory is on
        // offline media that is not shared, and saying so is more honest than
        // implying a permission model this does not set.
        std::fs::write(path, contents)
            .map_err(|e| format!("could not write {}: {e}", path.display()))?;
        eprintln!(
            "note: file permissions were not restricted. On Windows the key inherits the \
             directory's ACL; keep it on media that is not shared."
        );
        Ok(())
    }
}

fn load_signing_key(path: &Path) -> Result<SigningKey, String> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| format!("could not read the key at {}: {e}", path.display()))?;
    let bytes = signing::decode_base64(text.trim(), "private key").map_err(|e| e.to_string())?;
    let seed: [u8; 32] = bytes
        .as_slice()
        .try_into()
        .map_err(|_| format!("a private key must be 32 bytes, got {}", bytes.len()))?;
    Ok(SigningKey::from_bytes(&seed))
}

fn pubkey(args: &[&str]) -> Result<(), String> {
    let key = load_signing_key(Path::new(required(args, "--key")?))?;
    let public =
        PublicKey::from_bytes(key.verifying_key().as_bytes()).map_err(|e| e.to_string())?;
    println!("{}", public.to_base64());
    eprintln!("key id: {}", public.id());
    Ok(())
}

// ===========================================================================
// Signing
// ===========================================================================

fn parse_role(text: &str) -> Result<Role, String> {
    match text {
        "root" => Ok(Role::Root),
        "release" => Ok(Role::Release),
        other => Err(format!("unknown role {other:?}; expected root or release")),
    }
}

/// Wrap a payload file in a signed envelope.
fn sign(args: &[&str]) -> Result<(), String> {
    let role = parse_role(required(args, "--role")?)?;
    let key = load_signing_key(Path::new(required(args, "--key")?))?;
    let input = PathBuf::from(required(args, "--in")?);
    let output = PathBuf::from(required(args, "--out")?);

    let payload =
        std::fs::read(&input).map_err(|e| format!("could not read {}: {e}", input.display()))?;

    // Refuse to sign something that is not the document it claims to be. A
    // signature is a statement, and signing a payload whose `role` field
    // disagrees with the position it is being signed for is a statement nobody
    // meant to make.
    check_payload_role(role, &payload)?;

    let public =
        PublicKey::from_bytes(key.verifying_key().as_bytes()).map_err(|e| e.to_string())?;
    let signature = key.sign(&signing::preimage(role, &payload));

    let envelope = SignedEnvelope {
        payload: signing::encode_base64(&payload),
        signatures: vec![SignatureEntry {
            keyid: public.id().to_string(),
            sig: signing::encode_base64(&signature.to_bytes()),
        }],
    };

    write_envelope(&output, &envelope)?;
    println!(
        "Signed {} as {role} with key {} -> {}",
        input.display(),
        public.id(),
        output.display()
    );
    Ok(())
}

/// Add a second (or third) signature to an existing envelope.
///
/// This is how a threshold is met without any machine ever holding two keys.
/// The envelope travels; the keys do not.
fn add_signature(args: &[&str]) -> Result<(), String> {
    let role = parse_role(required(args, "--role")?)?;
    let key = load_signing_key(Path::new(required(args, "--key")?))?;
    let path = PathBuf::from(required(args, "--envelope")?);

    let bytes =
        std::fs::read(&path).map_err(|e| format!("could not read {}: {e}", path.display()))?;
    let mut envelope: SignedEnvelope = serde_json::from_slice(&bytes)
        .map_err(|e| format!("{} is not a signed envelope: {e}", path.display()))?;

    let payload =
        signing::decode_base64(&envelope.payload, "payload").map_err(|e| e.to_string())?;
    check_payload_role(role, &payload)?;

    let public =
        PublicKey::from_bytes(key.verifying_key().as_bytes()).map_err(|e| e.to_string())?;
    let id = public.id().to_string();

    if envelope.signatures.iter().any(|s| s.keyid == id) {
        return Err(format!(
            "{} is already signed by {id}. A threshold counts distinct keys, so a second \
             signature from the same key would add nothing.",
            path.display()
        ));
    }

    let signature = key.sign(&signing::preimage(role, &payload));
    envelope.signatures.push(SignatureEntry {
        keyid: id.clone(),
        sig: signing::encode_base64(&signature.to_bytes()),
    });

    write_envelope(&path, &envelope)?;
    println!(
        "Added a {role} signature from {id}; {} now carries {} signature(s).",
        path.display(),
        envelope.signatures.len()
    );
    Ok(())
}

/// Check an envelope the way the launcher would.
///
/// Present so a release can be verified before it is published, using the same
/// code path a client will use.
fn verify(args: &[&str]) -> Result<(), String> {
    let role = parse_role(required(args, "--role")?)?;
    let path = PathBuf::from(required(args, "--envelope")?);
    let threshold: usize = required(args, "--threshold")?
        .parse()
        .map_err(|_| "--threshold must be a number".to_owned())?;

    let mut keys = Vec::new();
    for (i, arg) in args.iter().enumerate() {
        if *arg == "--pubkey" {
            let value = args
                .get(i + 1)
                .ok_or_else(|| "--pubkey needs a value".to_owned())?;
            keys.push(PublicKey::from_base64(value).map_err(|e| e.to_string())?);
        }
    }
    if keys.is_empty() {
        return Err("at least one --pubkey is required".to_owned());
    }

    let bytes =
        std::fs::read(&path).map_err(|e| format!("could not read {}: {e}", path.display()))?;

    let platform = launcher_core::update::manifest::Platform::current();
    let context = launcher_core::update::verify::VerificationContext {
        // Far future, so `verify` here checks signatures and structure without
        // also failing on an expiry that has not happened yet on a client.
        now: 0,
        trusted_version: 0,
        platform: &platform,
    };

    match role {
        Role::Root => {
            let root =
                launcher_core::update::verify::verify_root(&bytes, &keys, threshold, context)
                    .map_err(|e| e.to_string())?;
            println!(
                "root v{} verifies: {} release key(s), threshold {}",
                root.version,
                root.release_keys.len(),
                root.release_threshold
            );
        }
        Role::Release => {
            let release =
                launcher_core::update::verify::verify_release(&bytes, &keys, threshold, context)
                    .map_err(|e| e.to_string())?;
            println!(
                "release v{} verifies: {} component(s)",
                release.version,
                release.components.len()
            );
        }
    }
    Ok(())
}

/// The SHA-256 of a file, in the form the metadata wants.
fn hash(args: &[&str]) -> Result<(), String> {
    let path = PathBuf::from(required(args, "--file")?);
    let bytes =
        std::fs::read(&path).map_err(|e| format!("could not read {}: {e}", path.display()))?;
    println!(
        "{}  {}  {} bytes",
        launcher_core::update::verify::sha256_hex(&bytes),
        path.display(),
        bytes.len()
    );
    Ok(())
}

// ===========================================================================
// Helpers
// ===========================================================================

/// Confirm the payload's own `role` field matches the position being signed.
fn check_payload_role(role: Role, payload: &[u8]) -> Result<(), String> {
    let value: serde_json::Value =
        serde_json::from_slice(payload).map_err(|e| format!("the payload is not JSON: {e}"))?;
    let found = value
        .get("role")
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| "the payload has no `role` field".to_owned())?;
    if found != role.label() {
        return Err(format!(
            "the payload says role={found:?} but it is being signed as {role}. Refusing: a \
             signature is a statement, and this one would say something nobody meant."
        ));
    }
    Ok(())
}

fn write_envelope(path: &Path, envelope: &SignedEnvelope) -> Result<(), String> {
    let json = serde_json::to_vec_pretty(envelope)
        .map_err(|e| format!("could not serialise the envelope: {e}"))?;
    std::fs::write(path, json).map_err(|e| format!("could not write {}: {e}", path.display()))
}

fn required<'a>(args: &[&'a str], flag: &str) -> Result<&'a str, String> {
    args.iter()
        .position(|a| *a == flag)
        .and_then(|i| args.get(i + 1).copied())
        .ok_or_else(|| format!("{flag} is required"))
}
