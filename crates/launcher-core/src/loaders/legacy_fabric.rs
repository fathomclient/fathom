//! Legacy Fabric.
//!
//! The primary loader target. Its whole stack is permissively licensed and
//! actively maintained (`mod-policies.md` §2: Fabric Loader Apache-2.0, Mixin
//! MIT, the 1.8.9 intermediaries CC0-1.0), which is why it is the loader we
//! build on rather than Forge.
//!
//! # Where the metadata comes from
//!
//! Legacy Fabric publishes a metadata service and a Maven repository. Those are
//! the only two places this module looks:
//!
//! ```text
//! GET meta.legacyfabric.net/v2/versions/game
//!     -> which Minecraft versions the project supports
//! GET meta.legacyfabric.net/v2/versions/loader/<mc>
//!     -> every loader build for that version, newest first
//! GET meta.legacyfabric.net/v2/versions/loader/<mc>/<loader>
//!     -> the launch profile: main class, libraries, mappings
//! ```
//!
//! The profile names its artifacts by Maven coordinate plus a repository URL,
//! and every one carries a SHA-1. Those URLs are **not trusted**: each goes
//! through `descriptor_from_url`, so an artifact pointed at an unlisted host is
//! refused rather than fetched. `maven.legacyfabric.net` redirects artifact
//! downloads to `repo.legacyfabric.net`, and because redirects are followed by
//! hand with every hop re-checked, both hosts are on the allowlist by name.
//!
//! # Nothing unverified reaches the classpath
//!
//! A loader library becomes part of the classpath — it is code that runs with
//! the game's privileges — so every artifact must have a hash before it is
//! fetched. Two sources, in order:
//!
//! 1. **The metadata**, which declares a SHA-1 for each shared library.
//! 2. **The Maven `.sha1` sidecar**, for the loader jar and the mappings, which
//!    the profile names by coordinate only. Both repositories publish one, and
//!    fetching it is two hundred bytes.
//!
//! If neither yields a hash, this module refuses. That is stricter than "warn
//! and continue" and it is the right way round: the alternative is running bytes
//! nobody checked. It is also why `Expectation` was not given an "unverified"
//! variant to accommodate this — weakening the download manager's guarantee to
//! fit one caller would have undone the install engine's central property.

use serde::Deserialize;

use super::{
    LoaderError, LoaderInstallation, LoaderProvider, LoaderVersion, ModDirectorySemantics,
};
use crate::install::download::Expectation;
use crate::install::{ArtifactCache, DownloadRequest};
use crate::network::policy::descriptor_from_url;
use crate::network::{Destination, Feature, HttpClient, Purpose, RequestDescriptor};
use crate::profiles::Loader;

/// Cap on a metadata document. The loader list for 1.8.9 is around 230 KB;
/// anything vastly larger is not something we should be parsing.
const MAX_METADATA_BYTES: usize = 8 * 1024 * 1024;

#[derive(Debug, Clone, Copy, Default)]
pub struct LegacyFabricProvider;

// ---------------------------------------------------------------------------
// The shapes Legacy Fabric publishes
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct GameVersion {
    version: String,
}

#[derive(Debug, Deserialize)]
struct LoaderEntry {
    loader: LoaderInfo,
    intermediary: IntermediaryInfo,
    #[serde(rename = "launcherMeta")]
    launcher_meta: Option<LauncherMeta>,
}

#[derive(Debug, Deserialize)]
struct LoaderInfo {
    version: String,
    maven: String,
    #[serde(default)]
    stable: bool,
}

#[derive(Debug, Deserialize)]
struct IntermediaryInfo {
    maven: String,
}

#[derive(Debug, Deserialize)]
struct LauncherMeta {
    #[serde(rename = "mainClass")]
    main_class: MainClass,
    #[serde(default)]
    libraries: Libraries,
    #[serde(rename = "min_java_version")]
    min_java_version: Option<u32>,
}

/// The client and server main classes.
///
/// Older profiles put a bare string here and newer ones an object. Both are
/// accepted, because a metadata format that changed once can change again and
/// refusing to parse would be a worse failure than handling both.
#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum MainClass {
    Both { client: String },
    Single(String),
}

impl MainClass {
    fn client(&self) -> &str {
        match self {
            MainClass::Both { client } => client,
            MainClass::Single(v) => v,
        }
    }
}

#[derive(Debug, Default, Deserialize)]
struct Libraries {
    #[serde(default)]
    common: Vec<Library>,
    #[serde(default)]
    client: Vec<Library>,
    // `server` and `development` are deliberately not read: we launch a client,
    // and pulling development-only libraries onto the classpath would change
    // what runs.
}

#[derive(Debug, Deserialize)]
struct Library {
    name: String,
    url: Option<String>,
    sha1: Option<String>,
    size: Option<u64>,
}

// ---------------------------------------------------------------------------
// Provider
// ---------------------------------------------------------------------------

impl LoaderProvider for LegacyFabricProvider {
    fn kind(&self) -> Loader {
        Loader::LegacyFabric
    }

    fn name(&self) -> &'static str {
        "Legacy Fabric"
    }

    fn is_implemented(&self) -> bool {
        // Kept in step with `Loader::is_implemented` by a test in `super`.
        Loader::LegacyFabric.is_implemented()
    }

    fn mod_directory(&self) -> ModDirectorySemantics {
        ModDirectorySemantics::FlatDirectory
    }

    fn available_versions(
        &self,
        minecraft_version: &str,
        http: &HttpClient,
        feature: Feature,
    ) -> Result<Vec<LoaderVersion>, LoaderError> {
        if !self.supports_minecraft(minecraft_version, http, feature)? {
            return Err(LoaderError::UnsupportedMinecraftVersion {
                loader: "Legacy Fabric",
                minecraft_version: minecraft_version.to_owned(),
            });
        }

        let entries: Vec<LoaderEntry> = Self::fetch_json(
            &format!("/v2/versions/loader/{}", encode(minecraft_version)),
            http,
            feature,
        )?;

        Ok(entries
            .into_iter()
            .map(|e| LoaderVersion {
                version: e.loader.version,
                stable: e.loader.stable,
            })
            .collect())
    }

    fn resolve(
        &self,
        minecraft_version: &str,
        loader_version: &str,
        cache: &ArtifactCache,
        http: &HttpClient,
        feature: Feature,
    ) -> Result<LoaderInstallation, LoaderError> {
        let entry = Self::loader_profile(minecraft_version, loader_version, cache, http, feature)?;

        let meta = entry.launcher_meta.ok_or_else(|| {
            LoaderError::Malformed(
                "the loader profile carried no launcherMeta, so there is nothing to launch with"
                    .to_owned(),
            )
        })?;

        // Classpath order matters. The loader and the mappings intercept class
        // loading, so they go first; the shared libraries follow.
        let mut libraries = Vec::new();
        libraries.push(Self::maven_artifact(
            &entry.loader.maven,
            Destination::FabricMaven,
            cache,
            http,
            feature,
        )?);
        libraries.push(Self::maven_artifact(
            &entry.intermediary.maven,
            Destination::LegacyFabricMaven,
            cache,
            http,
            feature,
        )?);

        for library in meta.libraries.common.iter().chain(&meta.libraries.client) {
            libraries.push(Self::declared_library(library, cache, feature)?);
        }

        Ok(LoaderInstallation {
            loader: Loader::LegacyFabric,
            loader_version: entry.loader.version,
            minecraft_version: minecraft_version.to_owned(),
            main_class: Some(meta.main_class.client().to_owned()),
            libraries,
            // Fabric needs none. Listed explicitly so the absence is a decision
            // rather than an oversight.
            jvm_arguments: Vec::new(),
            game_arguments: Vec::new(),
            minimum_java_major: meta.min_java_version,
            mod_directory: ModDirectorySemantics::FlatDirectory,
        })
    }
}

impl LegacyFabricProvider {
    /// Whether Legacy Fabric publishes support for a Minecraft version.
    ///
    /// Asked rather than assumed: the project's supported set is theirs to
    /// change, and hardcoding "1.8.9 works" would be a claim we cannot keep.
    pub fn supports_minecraft(
        &self,
        minecraft_version: &str,
        http: &HttpClient,
        feature: Feature,
    ) -> Result<bool, LoaderError> {
        let versions: Vec<GameVersion> = Self::fetch_json("/v2/versions/game", http, feature)?;
        Ok(versions.iter().any(|v| v.version == minecraft_version))
    }

    /// The launch profile of one loader build, from the metadata cache when a
    /// valid copy is there, otherwise from the service -- kept for next time.
    ///
    /// A build's profile never changes once published, so there is no reason
    /// for every launch to ask for it again. It used to: each press of Play
    /// fetched it before anything else, so a slow, down or misbehaving
    /// metadata service -- one answer came back without its `loader` field --
    /// stopped an installation that was already complete from starting at all.
    fn loader_profile(
        minecraft_version: &str,
        loader_version: &str,
        cache: &ArtifactCache,
        http: &HttpClient,
        feature: Feature,
    ) -> Result<LoaderEntry, LoaderError> {
        let file = cache
            .paths()
            .metadata_cache_dir()
            .join("legacy-fabric")
            .join(format!(
                "{}-{}.json",
                encode(minecraft_version),
                encode(loader_version)
            ));
        // Why the cached copy was not used, for the error if fetching fails
        // too: "the service is down" alone does not say why the copy that
        // should have made that irrelevant was passed over.
        let passed_over = match std::fs::read(&file) {
            Ok(bytes) => match serde_json::from_slice::<LoaderEntry>(&bytes) {
                Ok(entry) if entry.launcher_meta.is_some() => return Ok(entry),
                Ok(_) => format!("the copy at {} has no launcherMeta", file.display()),
                Err(error) => format!("the copy at {} does not parse ({error})", file.display()),
            },
            Err(error) => format!("no usable copy at {} ({error})", file.display()),
        };
        tracing::info!(reason = %passed_over, "fetching the loader profile");

        let path = format!(
            "/v2/versions/loader/{}/{}",
            encode(minecraft_version),
            encode(loader_version)
        );
        let with_reason = |error: LoaderError| match error {
            LoaderError::Malformed(detail) => {
                LoaderError::Malformed(format!("{detail}; {passed_over}"))
            }
            other => other,
        };
        let body = Self::fetch_bytes(&path, http, feature).map_err(with_reason)?;
        let entry: LoaderEntry = Self::parse(&path, &body).map_err(with_reason)?;
        if entry.launcher_meta.is_some() {
            // Best effort: a copy that cannot be written costs one fetch next
            // time, not this launch. Written beside and renamed over, so a
            // crash mid-write never leaves half a document to be read.
            let written = file
                .parent()
                .map_or(Ok(()), std::fs::create_dir_all)
                .and_then(|()| {
                    let partial = file.with_extension("json.partial");
                    std::fs::write(&partial, &body)?;
                    std::fs::rename(&partial, &file)
                });
            if let Err(error) = written {
                tracing::warn!(%error, file = %file.display(), "could not keep the loader profile");
            }
        }
        Ok(entry)
    }

    fn fetch_json<T: serde::de::DeserializeOwned>(
        path: &str,
        http: &HttpClient,
        feature: Feature,
    ) -> Result<T, LoaderError> {
        let body = Self::fetch_bytes(path, http, feature)?;
        Self::parse(path, &body)
    }

    /// A document that does not parse is reported with the start of what
    /// actually came back: "missing field `loader`" alone cannot tell a
    /// changed format from an error page or a rate limit.
    fn parse<T: serde::de::DeserializeOwned>(path: &str, body: &[u8]) -> Result<T, LoaderError> {
        serde_json::from_slice(body).map_err(|e| {
            let text = String::from_utf8_lossy(body);
            let start: String = text.chars().take(160).collect();
            LoaderError::Malformed(format!("{path}: {e} (the service sent: {start})"))
        })
    }

    fn fetch_bytes(
        path: &str,
        http: &HttpClient,
        feature: Feature,
    ) -> Result<Vec<u8>, LoaderError> {
        let request = RequestDescriptor::new(
            Destination::LegacyFabricMeta,
            Purpose::ResolveLoaderMetadata,
            feature,
            path,
        );
        let response = http.get(&request)?;

        if response.body.len() > MAX_METADATA_BYTES {
            return Err(LoaderError::Malformed(format!(
                "the metadata document is {} bytes, which is far larger than expected",
                response.body.len()
            )));
        }

        Ok(response.body)
    }

    /// A library the metadata described, with its own repository URL and hash.
    fn declared_library(
        library: &Library,
        cache: &ArtifactCache,
        feature: Feature,
    ) -> Result<DownloadRequest, LoaderError> {
        // A library with no hash is a library we would be running unverified.
        let sha1 = library
            .sha1
            .as_ref()
            .ok_or_else(|| LoaderError::UnverifiableArtifact {
                name: library.name.clone(),
            })?;

        let relative = maven_path(&library.name)
            .ok_or_else(|| LoaderError::Malformed(format!("bad coordinate {}", library.name)))?;

        // The repository URL comes from a document we just downloaded, so it is
        // untrusted: route it through the allowlist rather than using it
        // directly. A library pointed at an unlisted host is refused.
        let base = library
            .url
            .as_deref()
            .unwrap_or("https://maven.fabricmc.net/");
        let full = format!("{}{relative}", base.trim_end_matches('/').to_owned() + "/");
        let request = descriptor_from_url(&full, Purpose::DownloadLoaderArtifact, feature)?;

        Ok(DownloadRequest {
            name: library.name.clone(),
            request,
            expect: Expectation::new(sha1, library.size),
            target: cache.library_path(&relative)?,
        })
    }

    /// The loader jar and the mappings, which the profile names by coordinate
    /// only.
    ///
    /// The metadata carries no hash for these, so the repository's own `.sha1`
    /// sidecar is fetched. Both Legacy Fabric's and Fabric's Maven publish one.
    /// No sidecar means no download.
    fn maven_artifact(
        coordinate: &str,
        repository: Destination,
        cache: &ArtifactCache,
        http: &HttpClient,
        feature: Feature,
    ) -> Result<DownloadRequest, LoaderError> {
        let relative = maven_path(coordinate)
            .ok_or_else(|| LoaderError::Malformed(format!("bad coordinate {coordinate}")))?;

        let sha1 = Self::published_sha1(&relative, repository, http, feature, coordinate)?;

        let request = RequestDescriptor::new(
            repository,
            Purpose::DownloadLoaderArtifact,
            feature,
            format!("/{relative}"),
        );

        Ok(DownloadRequest {
            name: coordinate.to_owned(),
            request,
            // Size is not published alongside the sidecar. `None` means the
            // downloader checks the hash and skips the size check, which is the
            // honest reading of "we were not told".
            expect: Expectation::new(sha1, None),
            target: cache.library_path(&relative)?,
        })
    }

    /// Read a Maven repository's `.sha1` sidecar.
    ///
    /// The file is 40 hex characters, sometimes followed by whitespace or a
    /// filename. Anything else is refused: a sidecar we cannot read is the same
    /// situation as no sidecar, and guessing at it would defeat the point.
    fn published_sha1(
        relative: &str,
        repository: Destination,
        http: &HttpClient,
        feature: Feature,
        coordinate: &str,
    ) -> Result<String, LoaderError> {
        let request = RequestDescriptor::new(
            repository,
            Purpose::ResolveLoaderMetadata,
            feature,
            format!("/{relative}.sha1"),
        );
        let response = http.get(&request)?;

        let unverifiable = || LoaderError::UnverifiableArtifact {
            name: coordinate.to_owned(),
        };

        if !response.is_success() {
            return Err(unverifiable());
        }

        let text = std::str::from_utf8(&response.body).map_err(|_| unverifiable())?;
        let hash = text.split_whitespace().next().ok_or_else(unverifiable)?;

        if hash.len() != 40 || !hash.chars().all(|c| c.is_ascii_hexdigit()) {
            return Err(unverifiable());
        }
        Ok(hash.to_ascii_lowercase())
    }
}

/// `group:artifact:version` -> `group/path/artifact/version/artifact-version.jar`.
///
/// Returns `None` for anything that is not three colon-separated parts, rather
/// than guessing. A malformed coordinate reaching the filesystem is exactly the
/// sort of thing `ArtifactCache::library_path` is there to catch, but refusing
/// earlier gives a better message.
fn maven_path(coordinate: &str) -> Option<String> {
    let mut parts = coordinate.split(':');
    let group = parts.next()?;
    let artifact = parts.next()?;
    let version = parts.next()?;
    // A classifier is a fourth part. None of Legacy Fabric's own artifacts use
    // one, and silently ignoring it would produce the wrong file.
    if parts.next().is_some() {
        return None;
    }
    if group.is_empty() || artifact.is_empty() || version.is_empty() {
        return None;
    }
    Some(format!(
        "{}/{artifact}/{version}/{artifact}-{version}.jar",
        group.replace('.', "/")
    ))
}

/// Percent-encode a path segment.
///
/// Minecraft and loader versions are tame in practice, but they arrive from a
/// profile file and end up in a URL path. Anything outside the unreserved set
/// is escaped rather than trusted.
fn encode(segment: &str) -> String {
    let mut out = String::with_capacity(segment.len());
    for byte in segment.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'.' | b'_' | b'~') {
            out.push(byte as char);
        } else {
            use std::fmt::Write as _;
            let _ = write!(out, "%{byte:02X}");
        }
    }
    out
}

#[cfg(test)]
#[allow(
    clippy::indexing_slicing,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic
)]
mod tests {
    use super::*;

    fn offline() -> (tempfile::TempDir, ArtifactCache, HttpClient) {
        use crate::network::{NetworkJournal, NetworkPolicy};
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = crate::paths::DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let http = HttpClient::new(
            NetworkPolicy {
                online: false,
                ..NetworkPolicy::default()
            },
            NetworkJournal::new(),
            "test/0.0",
        );
        (tmp, ArtifactCache::new(paths), http)
    }

    const PROFILE: &str = r#"{"loader":{"maven":"net.fabricmc:fabric-loader:0.19.3","version":"0.19.3","stable":true},
        "intermediary":{"maven":"net.legacyfabric:intermediary:1.8.9"},
        "launcherMeta":{"min_java_version":8,
            "mainClass":{"client":"net.fabricmc.loader.impl.launch.knot.KnotClient"},
            "libraries":{"common":[{"name":"org.ow2.asm:asm:9.10.1","url":"https://maven.fabricmc.net/",
                "sha1":"ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236"}],"client":[]}}}"#;

    fn cached(cache: &ArtifactCache) -> std::path::PathBuf {
        let dir = cache.paths().metadata_cache_dir().join("legacy-fabric");
        std::fs::create_dir_all(&dir).unwrap();
        dir.join("1.8.9-0.19.3.json")
    }

    /// A build's profile never changes, so a launch with it cached must not
    /// depend on the metadata service being up.
    #[test]
    fn a_cached_loader_profile_launches_without_the_service() {
        let (_tmp, cache, http) = offline();
        std::fs::write(cached(&cache), PROFILE).unwrap();
        let entry =
            LegacyFabricProvider::loader_profile("1.8.9", "0.19.3", &cache, &http, Feature::Play)
                .expect("read from the cache");
        assert_eq!(entry.loader.version, "0.19.3");
        assert!(entry.launcher_meta.is_some());
        assert_eq!(http.journal().summary().total, 0);
    }

    #[test]
    fn an_unusable_cached_profile_is_fetched_again_rather_than_trusted() {
        let (_tmp, cache, http) = offline();
        std::fs::write(
            cached(&cache),
            r#"{"loader":{"maven":"x","version":"0.19.3"}}"#,
        )
        .unwrap();
        // Offline, so fetching again fails -- which is the point: it tried.
        assert!(LegacyFabricProvider::loader_profile(
            "1.8.9",
            "0.19.3",
            &cache,
            &http,
            Feature::Play
        )
        .is_err());
    }

    #[test]
    fn a_document_that_does_not_parse_says_what_came_back() {
        let error = LegacyFabricProvider::parse::<LoaderEntry>(
            "/p",
            br#"{"code":502,"message":"Bad Gateway"}"#,
        )
        .unwrap_err()
        .to_string();
        assert!(error.contains("Bad Gateway"), "{error}");
    }

    #[test]
    fn maven_coordinates_become_repository_paths() {
        assert_eq!(
            maven_path("net.fabricmc:fabric-loader:0.19.3").unwrap(),
            "net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar"
        );
        assert_eq!(
            maven_path("net.legacyfabric:intermediary:1.8.9").unwrap(),
            "net/legacyfabric/intermediary/1.8.9/intermediary-1.8.9.jar"
        );
        assert_eq!(
            maven_path("org.ow2.asm:asm:9.10.1").unwrap(),
            "org/ow2/asm/asm/9.10.1/asm-9.10.1.jar"
        );
    }

    /// Guessing at a malformed coordinate would produce a path to the wrong
    /// file, which is worse than refusing.
    #[test]
    fn a_malformed_coordinate_is_refused_rather_than_guessed_at() {
        for bad in [
            "",
            "onlyone",
            "two:parts",
            "a:b:c:classifier",
            ":b:c",
            "a::c",
            "a:b:",
        ] {
            assert!(maven_path(bad).is_none(), "{bad:?} should be refused");
        }
    }

    /// A coordinate reaches a URL path and a filesystem path. Neither may be
    /// escapable.
    #[test]
    fn a_traversing_coordinate_cannot_produce_a_traversing_path() {
        let path = maven_path("..:..:..").expect("structurally three parts");
        // It is structurally valid, so `maven_path` builds it -- and
        // `ArtifactCache::library_path` is what refuses it. Assert the shape
        // that gets handed over, so the containment test upstream is meaningful.
        assert!(path.contains(".."));

        let tmp = tempfile::tempdir().expect("tempdir");
        let cache = ArtifactCache::new(crate::paths::DataPaths::rooted_at(tmp.path()));
        assert!(
            cache.library_path(&path).is_err(),
            "the cache must refuse a traversing library path"
        );
    }

    #[test]
    fn url_segments_are_percent_encoded() {
        assert_eq!(encode("1.8.9"), "1.8.9");
        assert_eq!(encode("0.19.3"), "0.19.3");
        assert_eq!(encode("a/b"), "a%2Fb");
        assert_eq!(encode("../x"), "..%2Fx");
        assert_eq!(encode("a b"), "a%20b");
        assert_eq!(encode("a?b=c"), "a%3Fb%3Dc");
    }

    // -- parsing the real shapes ------------------------------------------

    /// Trimmed from a real response, keeping the structure exactly.
    const REAL_ENTRY: &str = r#"{
      "loader": {
        "separator": ".", "build": 3,
        "maven": "net.fabricmc:fabric-loader:0.19.3",
        "version": "0.19.3", "stable": true
      },
      "intermediary": {
        "maven": "net.legacyfabric:intermediary:1.8.9",
        "version": "1.8.9", "stable": true
      },
      "launcherMeta": {
        "version": 2,
        "min_java_version": 8,
        "libraries": {
          "client": [],
          "common": [
            {
              "name": "org.ow2.asm:asm:9.10.1",
              "url": "https://maven.fabricmc.net/",
              "sha1": "ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236",
              "size": 126151
            }
          ],
          "server": [],
          "development": [
            { "name": "io.github.llamalad7:mixinextras-fabric:0.4.1",
              "url": "https://maven.fabricmc.net/",
              "sha1": "0000000000000000000000000000000000000000", "size": 1 }
          ]
        },
        "mainClass": {
          "client": "net.fabricmc.loader.impl.launch.knot.KnotClient",
          "server": "net.fabricmc.loader.impl.launch.knot.KnotServer"
        }
      }
    }"#;

    #[test]
    fn a_real_loader_profile_parses() {
        let entry: LoaderEntry = serde_json::from_str(REAL_ENTRY).expect("should parse");
        assert_eq!(entry.loader.version, "0.19.3");
        assert!(entry.loader.stable);
        assert_eq!(
            entry.intermediary.maven,
            "net.legacyfabric:intermediary:1.8.9"
        );

        let meta = entry.launcher_meta.expect("has launcherMeta");
        assert_eq!(
            meta.main_class.client(),
            "net.fabricmc.loader.impl.launch.knot.KnotClient"
        );
        assert_eq!(meta.min_java_version, Some(8));
        assert_eq!(meta.libraries.common.len(), 1);
    }

    /// A metadata format that changed once can change again. Both shapes are
    /// accepted rather than one being a parse failure.
    #[test]
    fn a_bare_string_main_class_is_accepted_too() {
        let json = r#"{"client":"a.B","server":"c.D"}"#;
        let both: MainClass = serde_json::from_str(json).unwrap();
        assert_eq!(both.client(), "a.B");

        let single: MainClass = serde_json::from_str(r#""only.One""#).unwrap();
        assert_eq!(single.client(), "only.One");
    }

    /// We launch a client. Pulling `development` libraries onto the classpath
    /// would change what runs, so they are not read at all.
    #[test]
    fn development_and_server_libraries_are_not_read() {
        let entry: LoaderEntry = serde_json::from_str(REAL_ENTRY).unwrap();
        let meta = entry.launcher_meta.unwrap();
        // Only `common` and `client` exist on the struct; the mixinextras entry
        // in `development` has nowhere to land.
        assert_eq!(meta.libraries.common.len(), 1);
        assert!(meta.libraries.client.is_empty());
        assert_eq!(meta.libraries.common[0].name, "org.ow2.asm:asm:9.10.1");
    }

    #[test]
    fn a_library_without_a_hash_is_refused_rather_than_downloaded_unverified() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let cache = ArtifactCache::new(crate::paths::DataPaths::rooted_at(tmp.path()));

        let library = Library {
            name: "com.example:thing:1.0".into(),
            url: Some("https://maven.fabricmc.net/".into()),
            sha1: None,
            size: Some(10),
        };

        let result =
            LegacyFabricProvider::declared_library(&library, &cache, Feature::ProfileInstall);
        assert!(
            matches!(result, Err(LoaderError::UnverifiableArtifact { .. })),
            "{result:?}"
        );
    }

    /// The repository URL comes from a downloaded document. A library pointed at
    /// an unlisted host must be refused, not fetched.
    #[test]
    fn a_library_pointed_at_an_unlisted_host_is_refused() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let cache = ArtifactCache::new(crate::paths::DataPaths::rooted_at(tmp.path()));

        for hostile in [
            "https://evil.example/",
            "https://maven.fabricmc.net.evil.example/",
            "http://maven.fabricmc.net/",
        ] {
            let library = Library {
                name: "com.example:thing:1.0".into(),
                url: Some(hostile.into()),
                sha1: Some("a".repeat(40)),
                size: Some(10),
            };
            let result =
                LegacyFabricProvider::declared_library(&library, &cache, Feature::ProfileInstall);
            assert!(result.is_err(), "{hostile} should have been refused");
        }
    }

    #[test]
    fn an_allowlisted_library_resolves_into_the_shared_cache() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = crate::paths::DataPaths::rooted_at(tmp.path());
        let cache = ArtifactCache::new(paths.clone());

        let library = Library {
            name: "org.ow2.asm:asm:9.10.1".into(),
            url: Some("https://maven.fabricmc.net/".into()),
            sha1: Some("ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236".into()),
            size: Some(126_151),
        };

        let request =
            LegacyFabricProvider::declared_library(&library, &cache, Feature::ProfileInstall)
                .expect("should resolve");

        assert_eq!(request.request.destination, Destination::FabricMaven);
        assert!(request.target.starts_with(paths.libraries_dir()));
        assert!(request.target.ends_with("asm-9.10.1.jar"));
        assert_eq!(request.expect.size, Some(126_151));
    }
}
