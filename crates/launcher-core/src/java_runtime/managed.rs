//! Fathom-managed Java runtimes.
//!
//! # What this does
//!
//! Downloads a Java 8 runtime from Adoptium (Eclipse Temurin) and unpacks it
//! **inside the launcher's own data directory**. Nothing else on the machine
//! changes: no `PATH`, no `JAVA_HOME`, no registry, no system Java, no
//! uninstalling of anything. A managed runtime is a directory Fathom owns and
//! can delete.
//!
//! # Why Adoptium
//!
//! `docs/research/` settled this: Temurin is GPLv2-with-Classpath-Exception,
//! freely redistributable, published with checksums over HTTPS, and available
//! for exactly the platforms 1.8.9 runs on. `api.adoptium.net` is already on the
//! network allowlist as [`Destination::JavaVendor`], which is the same list the
//! Privacy screen renders — so this feature was declared to users before it
//! existed, and now does what the entry says.
//!
//! # Nothing unverified is executed
//!
//! The API returns a SHA-256 for the archive *before* the archive is fetched.
//! That hash is checked against the downloaded bytes, and the archive is only
//! unpacked if it matches. This is the same rule the install engine applies to
//! game artifacts and the loader applies to its libraries: a file that will be
//! executed must have had its hash stated by the publisher first.
//!
//! Note the hash algorithm differs from the rest of the launcher. Mojang
//! publishes SHA-1 and Adoptium publishes SHA-256, so this module carries its
//! own digest rather than reusing [`crate::install::download`]'s — using the
//! shared downloader would have meant either weakening its SHA-1 expectation to
//! "some hash" or lying about which algorithm was checked.
//!
//! # Consent
//!
//! Governed by `PrivacySettings::managed_java`, which is off by default. With it
//! off, [`Destination::JavaVendor`] is refused by the network policy and this
//! module cannot reach anything — the check is in the policy layer, not here, so
//! it cannot be forgotten at a call site.

use std::io::Read;
use std::path::{Path, PathBuf};

use serde::Deserialize;
use sha2::{Digest, Sha256};

use crate::network::policy::Destination;
use crate::network::{Feature, HttpClient, NetworkError, Purpose, RequestDescriptor};
use crate::paths::DataPaths;

use super::JavaInstallation;

/// How large a Java runtime archive may be.
///
/// A Temurin 8 JRE for Windows x64 is around 45 MB. The cap is generous enough
/// for every platform and small enough that a redirect to something unexpected
/// does not fill a disk.
const MAX_ARCHIVE_BYTES: u64 = 256 * 1024 * 1024;

/// How large the metadata document may be.
const MAX_METADATA_BYTES: usize = 4 * 1024 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum JavaInstallError {
    #[error("managed Java downloads are turned off in Settings")]
    NotPermitted,

    #[error("network: {0}")]
    Network(#[from] NetworkError),

    #[error("Adoptium published no Java {major} build for this platform")]
    NoBuild { major: u32 },

    #[error("the download did not match the checksum Adoptium published")]
    ChecksumMismatch,

    #[error("the archive is {size} bytes, which is larger than this launcher will accept")]
    TooLarge { size: u64 },

    #[error("the archive did not contain a Java executable")]
    NoExecutable,

    #[error("the downloaded Java did not run: {0}")]
    Unusable(String),

    #[error("{path}: {source}")]
    Io {
        path: String,
        #[source]
        source: std::io::Error,
    },

    #[error("archive: {0}")]
    Archive(String),
}

// ---------------------------------------------------------------------------
// What Adoptium publishes
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct Asset {
    binary: Binary,
}

#[derive(Debug, Deserialize)]
struct Binary {
    package: Package,
}

#[derive(Debug, Deserialize)]
struct Package {
    /// Absolute URL. **Not trusted**: checked against the allowlist before use.
    link: String,
    /// Hex SHA-256.
    checksum: String,
    #[serde(default)]
    size: Option<u64>,
    #[serde(default)]
    name: Option<String>,
}

/// The platform triple Adoptium names, for this build.
///
/// A `const` rather than a runtime lookup so an unsupported platform is a
/// compile error in the making rather than a runtime surprise, and so the test
/// below can assert the mapping.
const fn platform() -> (&'static str, &'static str) {
    #[cfg(target_os = "windows")]
    let os = "windows";
    #[cfg(target_os = "macos")]
    let os = "mac";
    #[cfg(all(unix, not(target_os = "macos")))]
    let os = "linux";

    #[cfg(target_arch = "x86_64")]
    let arch = "x64";
    #[cfg(target_arch = "aarch64")]
    let arch = "aarch64";
    #[cfg(all(not(target_arch = "x86_64"), not(target_arch = "aarch64")))]
    let arch = "x32";

    (os, arch)
}

/// The API path for the newest release of a major version.
///
/// `image_type=jre` rather than `jdk`: running Minecraft needs a runtime, not a
/// compiler, and the JRE is roughly half the download.
fn query_path(major: u32) -> String {
    let (os, arch) = platform();
    format!(
        "/v3/assets/latest/{major}/hotspot?os={os}&architecture={arch}&image_type=jre&vendor=eclipse"
    )
}

/// Where a managed runtime for a major version lives.
pub fn install_root(paths: &DataPaths, major: u32) -> PathBuf {
    paths.java_dir().join(format!("temurin-{major}"))
}

/// Is a managed runtime already installed and working?
pub fn installed(paths: &DataPaths, major: u32) -> Option<JavaInstallation> {
    let executable = find_executable(&install_root(paths, major)).ok()?;
    let probed = super::probe(&executable).ok()?;
    (probed.major == major).then_some(probed)
}

/// Download, verify, unpack and probe a Java runtime.
///
/// Returns the installation only after it has been **executed** and reported
/// the expected major version. An archive that unpacks but does not run is a
/// failure, not a success: the whole point is to end up with a Java that
/// launches Minecraft.
pub fn install(
    http: &HttpClient,
    paths: &DataPaths,
    major: u32,
) -> Result<JavaInstallation, JavaInstallError> {
    // Already there and working. Re-downloading 45 MB because somebody pressed
    // the button twice would be rude.
    if let Some(existing) = installed(paths, major) {
        tracing::info!(major, "a managed Java is already installed");
        return Ok(existing);
    }

    let package = resolve(http, major)?;

    if let Some(size) = package.size {
        if size > MAX_ARCHIVE_BYTES {
            return Err(JavaInstallError::TooLarge { size });
        }
    }

    // The publisher's own URL, put through the same check every other URL in
    // this launcher goes through. Adoptium redirects downloads to a CDN host,
    // and a redirect target is exactly the kind of thing that must not be
    // followed on trust.
    let request = crate::network::policy::descriptor_from_url(
        &package.link,
        Purpose::DownloadGameArtifact,
        Feature::JavaManagement,
    )
    .map_err(|rejection| JavaInstallError::Archive(format!("refused download URL: {rejection}")))?;

    tracing::info!(major, url = %package.link, "downloading a Java runtime");
    let response = http.get(&request)?;
    if !response.is_success() {
        return Err(JavaInstallError::Network(NetworkError::Status {
            host: Destination::JavaVendor.host(),
            status: response.status,
            body: String::new(),
        }));
    }

    let actual = hex(&Sha256::digest(&response.body));
    if !actual.eq_ignore_ascii_case(package.checksum.trim()) {
        // Deliberately not logged with both hashes at info level: a mismatch is
        // a security event, and the useful reaction is to stop.
        tracing::error!(
            major,
            "the Java download did not match its published checksum"
        );
        return Err(JavaInstallError::ChecksumMismatch);
    }

    let root = install_root(paths, major);
    // A previous half-finished attempt must not be mistaken for a good install.
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(&root).map_err(|source| JavaInstallError::Io {
        path: root.display().to_string(),
        source,
    })?;

    let name = package.name.as_deref().unwrap_or_default();
    if name.ends_with(".tar.gz") {
        return Err(JavaInstallError::Archive(
            "this build can only unpack .zip runtimes".to_owned(),
        ));
    }
    unzip(&response.body, &root)?;

    let executable = find_executable(&root)?;
    let probed =
        super::probe(&executable).map_err(|e| JavaInstallError::Unusable(e.to_string()))?;
    if probed.major != major {
        return Err(JavaInstallError::Unusable(format!(
            "expected Java {major} but the runtime reports {}",
            probed.major
        )));
    }

    tracing::info!(major, path = %executable.display(), "managed Java installed");
    Ok(probed)
}

/// Ask Adoptium what the newest build is.
fn resolve(http: &HttpClient, major: u32) -> Result<Package, JavaInstallError> {
    let request = RequestDescriptor::new(
        Destination::JavaVendor,
        Purpose::ResolveGameMetadata,
        Feature::JavaManagement,
        query_path(major),
    );

    let response = http.get(&request)?;
    if !response.is_success() {
        return Err(JavaInstallError::Network(NetworkError::Status {
            host: Destination::JavaVendor.host(),
            status: response.status,
            body: String::new(),
        }));
    }
    if response.body.len() > MAX_METADATA_BYTES {
        return Err(JavaInstallError::NoBuild { major });
    }

    let assets: Vec<Asset> = response.json(Destination::JavaVendor.host())?;
    assets
        .into_iter()
        .map(|asset| asset.binary.package)
        .find(|package| !package.checksum.trim().is_empty())
        .ok_or(JavaInstallError::NoBuild { major })
}

/// Unpack a zip into a directory, refusing anything that escapes it.
///
/// Zip entries carry attacker-controlled paths, and `../` in one is the
/// classic way an archive writes outside its destination. Every entry is
/// resolved and checked to be inside the root before a byte is written.
fn unzip(bytes: &[u8], root: &Path) -> Result<(), JavaInstallError> {
    let reader = std::io::Cursor::new(bytes);
    let mut archive =
        zip::ZipArchive::new(reader).map_err(|e| JavaInstallError::Archive(e.to_string()))?;

    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|e| JavaInstallError::Archive(e.to_string()))?;

        // `enclosed_name` returns None for anything with `..` or an absolute
        // component -- the check, rather than our own string inspection.
        let Some(relative) = entry.enclosed_name() else {
            return Err(JavaInstallError::Archive(format!(
                "archive entry {:?} tries to escape the destination",
                entry.name()
            )));
        };
        let target = root.join(relative);

        if entry.is_dir() {
            std::fs::create_dir_all(&target).map_err(|source| JavaInstallError::Io {
                path: target.display().to_string(),
                source,
            })?;
            continue;
        }
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent).map_err(|source| JavaInstallError::Io {
                path: parent.display().to_string(),
                source,
            })?;
        }

        let mut buffer = Vec::with_capacity(usize::try_from(entry.size()).unwrap_or(0));
        entry
            .read_to_end(&mut buffer)
            .map_err(|source| JavaInstallError::Io {
                path: target.display().to_string(),
                source,
            })?;
        std::fs::write(&target, &buffer).map_err(|source| JavaInstallError::Io {
            path: target.display().to_string(),
            source,
        })?;

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            if let Some(mode) = entry.unix_mode() {
                let _ = std::fs::set_permissions(&target, std::fs::Permissions::from_mode(mode));
            }
        }
    }
    Ok(())
}

/// Find the java executable inside an unpacked runtime.
///
/// Adoptium archives contain a single versioned top-level directory whose name
/// changes with every release, so the executable is searched for rather than
/// assumed. The search is bounded to the depth the layout actually uses.
fn find_executable(root: &Path) -> Result<PathBuf, JavaInstallError> {
    const EXECUTABLE: &str = if cfg!(windows) { "javaw.exe" } else { "java" };
    const FALLBACK: &str = if cfg!(windows) { "java.exe" } else { "java" };

    for name in [EXECUTABLE, FALLBACK] {
        // <root>/bin/java, and <root>/<jdk-dir>/bin/java.
        let direct = root.join("bin").join(name);
        if direct.is_file() {
            return Ok(direct);
        }
        let Ok(entries) = std::fs::read_dir(root) else {
            continue;
        };
        for entry in entries.flatten() {
            let nested = entry.path().join("bin").join(name);
            if nested.is_file() {
                return Ok(nested);
            }
        }
    }
    Err(JavaInstallError::NoExecutable)
}

fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    bytes.iter().fold(String::new(), |mut out, byte| {
        // Writing to a String cannot fail; the result is discarded rather than
        // unwrapped so this cannot panic.
        let _ = write!(out, "{byte:02x}");
        out
    })
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    #[test]
    fn the_query_asks_for_a_runtime_not_a_compiler() {
        let path = query_path(8);
        assert!(path.starts_with("/v3/assets/latest/8/hotspot"));
        assert!(
            path.contains("image_type=jre"),
            "a JDK is twice the download"
        );
        assert!(path.contains("vendor=eclipse"));
    }

    #[test]
    fn the_platform_is_one_adoptium_publishes() {
        let (os, arch) = platform();
        assert!(["windows", "mac", "linux"].contains(&os));
        assert!(["x64", "aarch64", "x32"].contains(&arch));
    }

    #[test]
    fn hex_matches_the_reference_encoding() {
        assert_eq!(hex(&[0x00, 0x0f, 0xff]), "000fff");
        assert_eq!(hex(&[]), "");
        // The digest of the empty input, which is a published constant.
        assert_eq!(
            hex(&Sha256::digest(b"")),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }

    /// A zip that writes outside its destination is the classic archive
    /// attack, and this runs as whatever user launched Fathom.
    #[test]
    fn an_escaping_entry_is_refused() {
        use std::io::Write as _;

        let mut buffer = Vec::new();
        {
            let mut writer = zip::ZipWriter::new(std::io::Cursor::new(&mut buffer));
            let options: zip::write::FileOptions<'_, ()> = zip::write::FileOptions::default()
                .compression_method(zip::CompressionMethod::Stored);
            // `start_file` with a traversal path; `enclosed_name` must reject it
            // when reading back.
            writer.start_file("../escaped.txt", options).unwrap();
            writer.write_all(b"nope").unwrap();
            writer.finish().unwrap();
        }

        let temp = tempfile::tempdir().unwrap();
        let result = unzip(&buffer, temp.path());
        assert!(
            matches!(result, Err(JavaInstallError::Archive(_))),
            "a traversal entry must be refused, got {result:?}"
        );
        assert!(
            !temp.path().parent().unwrap().join("escaped.txt").exists(),
            "the entry escaped the destination"
        );
    }

    #[test]
    fn a_normal_archive_unpacks() {
        use std::io::Write as _;

        let mut buffer = Vec::new();
        {
            let mut writer = zip::ZipWriter::new(std::io::Cursor::new(&mut buffer));
            let options: zip::write::FileOptions<'_, ()> = zip::write::FileOptions::default()
                .compression_method(zip::CompressionMethod::Stored);
            writer.start_file("jdk8u1/bin/notes.txt", options).unwrap();
            writer.write_all(b"hello").unwrap();
            writer.finish().unwrap();
        }

        let temp = tempfile::tempdir().unwrap();
        unzip(&buffer, temp.path()).expect("a well-formed archive");
        assert_eq!(
            std::fs::read_to_string(temp.path().join("jdk8u1/bin/notes.txt")).unwrap(),
            "hello"
        );
    }

    /// The executable is searched for because Adoptium's top-level directory
    /// name carries the build number and changes every release.
    #[test]
    fn the_executable_is_found_one_level_down() {
        let temp = tempfile::tempdir().unwrap();
        let bin = temp.path().join("jdk8u452-b09-jre").join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let name = if cfg!(windows) { "javaw.exe" } else { "java" };
        std::fs::write(bin.join(name), b"").unwrap();

        let found = find_executable(temp.path()).expect("the executable");
        assert!(found.ends_with(std::path::Path::new("bin").join(name)));
    }

    #[test]
    fn a_runtime_with_no_executable_is_refused() {
        let temp = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(temp.path().join("empty")).unwrap();
        assert!(matches!(
            find_executable(temp.path()),
            Err(JavaInstallError::NoExecutable)
        ));
    }

    /// Managed runtimes live under the launcher's own data directory and
    /// nowhere else. This is the property that makes the feature safe to offer:
    /// it can be undone by deleting a folder.
    #[test]
    fn a_managed_runtime_lives_inside_launcher_data() {
        let temp = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(temp.path());
        let root = install_root(&paths, 8);
        assert!(root.starts_with(paths.root()));
        assert!(root.starts_with(paths.java_dir()));
    }
}
