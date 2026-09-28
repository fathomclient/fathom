use std::path::{Path, PathBuf};

fn main() {
    let frontend = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../desktop/dist");
    println!("cargo:rerun-if-changed={}", frontend.display());
    if std::env::var_os("CARGO_FEATURE_CUSTOM_PROTOCOL").is_some()
        && !frontend.join("index.html").is_file()
    {
        panic!(
            "embedded launcher assets are missing; run `npm --prefix desktop run build` before building Fathom"
        );
    }
    let fingerprint = fingerprint_tree(&frontend).unwrap_or_else(|| "missing".to_owned());
    println!("cargo:rustc-env=FATHOM_FRONTEND_ID={fingerprint}");

    // A single-file launcher must remain a complete Fathom build when it is
    // moved away from the checkout. Embed the already-built first-party client
    // jar; startup stages it into the per-user resource directory only when it
    // is missing. The packaging scripts build this module before Cargo.
    let module = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../minecraft/fathom-optimizations");
    let libs = module.join("build/libs");
    println!("cargo:rerun-if-changed={}", libs.display());
    let client = std::fs::read_dir(&libs)
        .unwrap_or_else(|error| panic!("Fathom client output is missing ({}): {error}", libs.display()))
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            let name = path.file_name().and_then(|name| name.to_str()).unwrap_or_default();
            name.starts_with("fathom-optimizations-")
                && name.ends_with(".jar")
                && !name.ends_with("-sources.jar")
                && !name.ends_with("-core.jar")
        })
        .max_by_key(|path| std::fs::metadata(path).and_then(|meta| meta.modified()).ok())
        .unwrap_or_else(|| panic!("no loadable Fathom client jar in {}", libs.display()));
    println!("cargo:rerun-if-changed={}", client.display());
    println!("cargo:rustc-env=FATHOM_CLIENT_JAR_PATH={}", client.display());
    println!(
        "cargo:rustc-env=FATHOM_CLIENT_JAR_NAME={}",
        client.file_name().and_then(|name| name.to_str()).expect("client jar filename")
    );
    // A fingerprint of the jar's bytes, so start-up can tell "the installed copy
    // is this build's" from a stamp file instead of re-reading tens of
    // megabytes every time the launcher opens.
    let jar = std::fs::read(&client).expect("read the client jar");
    let mut hash = 0xcbf29ce484222325_u64;
    for byte in &jar {
        hash = (hash ^ u64::from(*byte)).wrapping_mul(0x100000001b3);
    }
    println!("cargo:rustc-env=FATHOM_CLIENT_JAR_ID={hash:016x}-{}", jar.len());
    tauri_build::build();
}

/// A deterministic, diagnostic-only FNV-1a fingerprint. Security still comes
/// from Tauri embedding the bytes at compile time; this gives logs a short id
/// that distinguishes a current bundle from a stale one.
fn fingerprint_tree(root: &Path) -> Option<String> {
    let mut files = Vec::new();
    collect(root, root, &mut files).ok()?;
    files.sort();
    let mut hash = 0xcbf29ce484222325_u64;
    for relative in files {
        for byte in relative.to_string_lossy().as_bytes() {
            hash = (hash ^ u64::from(*byte)).wrapping_mul(0x100000001b3);
        }
        for byte in std::fs::read(root.join(&relative)).ok()? {
            hash = (hash ^ u64::from(byte)).wrapping_mul(0x100000001b3);
        }
    }
    Some(format!("{hash:016x}"))
}

fn collect(root: &Path, directory: &Path, out: &mut Vec<PathBuf>) -> std::io::Result<()> {
    for entry in std::fs::read_dir(directory)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            collect(root, &path, out)?;
        } else if path.is_file() {
            if let Ok(relative) = path.strip_prefix(root) {
                out.push(relative.to_path_buf());
            }
        }
    }
    Ok(())
}
