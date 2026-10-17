//! Turning a version document into the exact set of files an installation
//! needs.
//!
//! Pure: no I/O, no network. Given parsed metadata and a platform, it produces
//! a [`ResolvedInstall`] listing every artifact with its URL, hash, size and
//! destination. That makes the fiddly parts — which libraries apply on which
//! OS, which natives classifier, where each file lands — testable against the
//! real 1.8.9 document without downloading anything.
//!
//! Everything a URL could come from here is remote metadata, so every one is
//! routed through the network allowlist at construction. A tampered manifest
//! pointing a "library" at another host fails to resolve rather than being
//! fetched.

use std::path::PathBuf;

use crate::minecraft_runtime::version::{Os, VersionDetail};
use crate::network::policy::{descriptor_from_url, UrlRejection};
use crate::network::{Feature, Purpose, RequestDescriptor};

use super::cache::{ArtifactCache, CacheError};
use super::download::{DownloadRequest, Expectation};

#[derive(Debug, thiserror::Error)]
pub enum ResolveError {
    #[error("{artifact}: {source}")]
    Url {
        artifact: String,
        #[source]
        source: UrlRejection,
    },
    #[error(transparent)]
    Cache(#[from] CacheError),
    #[error("this version document has no client download")]
    NoClient,
    #[error("only Minecraft {supported} is supported at this stage; this is {found}")]
    UnsupportedVersion { supported: String, found: String },
}

/// The one version we support today.
///
/// Stated as a constant and checked, rather than left implicit, because the
/// product rules say 1.8.9 only and a silent attempt at another version would
/// produce confusing failures deep in the pipeline.
pub const SUPPORTED_VERSION: &str = "1.8.9";

/// What a native library contributes: an archive to fetch, and the rules for
/// unpacking it.
#[derive(Debug, Clone)]
pub struct NativeArtifact {
    pub library: String,
    pub download: DownloadRequest,
    /// Prefixes the version document says to skip (`META-INF/` for 1.8.9).
    pub exclude: Vec<String>,
}

/// Everything one version needs on disk.
#[derive(Debug, Clone)]
pub struct ResolvedInstall {
    pub version_id: String,
    pub main_class: String,
    /// The legacy `minecraftArguments` string. 1.8.9 uses this, not the 1.13+
    /// `arguments` object.
    pub argument_template: Option<String>,
    pub required_java_major: u32,

    pub client: DownloadRequest,
    /// Libraries that go on the classpath, in metadata order.
    pub libraries: Vec<DownloadRequest>,
    /// Native archives for this platform.
    pub natives: Vec<NativeArtifact>,
    /// The asset index document itself.
    pub asset_index: Option<DownloadRequest>,
    pub asset_index_id: Option<String>,
    /// Individual asset objects. Empty until the index has been fetched and
    /// parsed; see [`crate::install::assets`].
    pub assets: Vec<DownloadRequest>,
    /// log4j2 configuration, and the JVM argument that points at it.
    pub logging: Option<LoggingConfig>,
}

/// The logging configuration a version asks for.
#[derive(Debug, Clone)]
pub struct LoggingConfig {
    pub download: DownloadRequest,
    /// Where it will live once installed.
    pub path: PathBuf,
    /// The argument template, e.g. `-Dlog4j.configurationFile=${path}`.
    pub argument: String,
}

/// The only property this launcher will pass on a version document's say-so.
///
/// The version document is fetched over TLS from an allowlisted host and is
/// itself hash-checked, so reaching this field means an attacker already holds
/// Mojang's metadata or our transport. That is exactly the situation defence in
/// depth is for, and this is the **one** place where a remote document gets to
/// put a flag on the JVM command line.
const LOG4J_CONFIG_PROPERTY: &str = "-Dlog4j.configurationFile=";

impl LoggingConfig {
    /// The JVM argument with `${path}` substituted, if it is one we will pass.
    ///
    /// Returns `None` when the document asked for something other than a log4j
    /// configuration property. The security pass added the check: the field is
    /// a free-form template that was pushed onto the JVM argument list without
    /// going through `jvm_args::validate_arg`, so a document declaring
    /// `"argument": "-XX:OnOutOfMemoryError=calc.exe"` would have had it passed
    /// verbatim — the one route into the argument vector that skipped every
    /// refusal in that module.
    ///
    /// Two conditions, both required:
    ///
    /// 1. it names the log4j configuration property and nothing else;
    /// 2. it still passes the ordinary argument validation afterwards, so the
    ///    substituted path cannot smuggle in a metacharacter or a control
    ///    character.
    ///
    /// Refusing costs the user very little: without it the game falls back to
    /// log4j's built-in configuration and logs slightly differently. Accepting
    /// a flag we did not recognise costs considerably more.
    pub fn jvm_argument(&self) -> Option<String> {
        // Trimmed before anything looks at it. `validate_arg` inspects
        // `arg.trim()` and `validate_args` stores the trimmed form, so
        // returning the untrimmed string here would mean validating one value
        // and passing another — a small gap, and exactly the shape of gap that
        // turns into a real one when someone later widens what the validator
        // considers. Found by the generative tests, which fed it a template
        // ending in a carriage return.
        let substituted = self
            .argument
            .replace("${path}", &self.path.display().to_string())
            .trim()
            .to_owned();

        if !substituted.starts_with(LOG4J_CONFIG_PROPERTY) {
            tracing::warn!(
                argument = %self.argument,
                "the version document asked for a JVM argument that is not a log4j                  configuration property; refusing to pass it"
            );
            return None;
        }
        if let Err(rejection) = crate::minecraft_runtime::jvm_args::validate_arg(&substituted) {
            tracing::warn!(
                %rejection,
                "the version document's logging argument did not pass validation"
            );
            return None;
        }
        Some(substituted)
    }
}

impl ResolvedInstall {
    /// Every artifact, in the order they should be fetched.
    ///
    /// Small metadata-ish files first so a failure surfaces quickly, then the
    /// bulk.
    pub fn all_downloads(&self) -> Vec<&DownloadRequest> {
        let mut out =
            Vec::with_capacity(2 + self.libraries.len() + self.natives.len() + self.assets.len());
        if let Some(index) = &self.asset_index {
            out.push(index);
        }
        if let Some(logging) = &self.logging {
            out.push(&logging.download);
        }
        out.push(&self.client);
        out.extend(self.libraries.iter());
        out.extend(self.natives.iter().map(|n| &n.download));
        out.extend(self.assets.iter());
        out
    }

    /// Total declared bytes. A lower bound: artifacts without a declared size
    /// contribute nothing.
    pub fn declared_bytes(&self) -> u64 {
        self.all_downloads()
            .iter()
            .filter_map(|d| d.expect.size)
            .sum()
    }

    /// The classpath, libraries first and the client jar last.
    ///
    /// Order matters: the client jar goes last so a library cannot shadow a
    /// game class.
    pub fn classpath(&self) -> Vec<PathBuf> {
        let mut entries: Vec<PathBuf> = self.libraries.iter().map(|l| l.target.clone()).collect();
        entries.push(self.client.target.clone());
        entries
    }
}

/// Turns metadata into a plan.
#[derive(Debug, Clone)]
pub struct ArtifactResolver {
    cache: ArtifactCache,
}

impl ArtifactResolver {
    pub fn new(cache: ArtifactCache) -> Self {
        Self { cache }
    }

    /// Classpath libraries, in document order so the classpath is stable
    /// between runs. Determinism matters: a reordered classpath can change
    /// which duplicate class wins.
    fn resolve_libraries(
        &self,
        version: &VersionDetail,
        os: Os,
        feature: Feature,
    ) -> Result<Vec<DownloadRequest>, ResolveError> {
        let mut libraries = Vec::new();
        for library in version.classpath_libraries(os) {
            let Some(artifact) = &library.downloads.artifact else {
                continue;
            };
            let Some(relative) = artifact.path.as_deref() else {
                continue;
            };
            libraries.push(self.request(
                library.name.clone(),
                &artifact.url,
                Expectation::new(&artifact.sha1, Some(artifact.size)),
                self.cache.library_path(relative)?,
                Purpose::DownloadGameArtifact,
                feature,
            )?);
        }
        Ok(libraries)
    }

    /// Native archives for this platform only.
    fn resolve_natives(
        &self,
        version: &VersionDetail,
        os: Os,
        feature: Feature,
    ) -> Result<Vec<NativeArtifact>, ResolveError> {
        let mut natives = Vec::new();
        for (library, download) in version.native_libraries(os) {
            let relative = download
                .path
                .clone()
                .unwrap_or_else(|| format!("natives/{}.jar", sanitise(&library.name)));
            let request = self.request(
                format!("{} (natives)", library.name),
                &download.url,
                Expectation::new(&download.sha1, Some(download.size)),
                self.cache.library_path(&relative)?,
                Purpose::DownloadGameArtifact,
                feature,
            )?;
            natives.push(NativeArtifact {
                library: library.name.clone(),
                download: request,
                exclude: library
                    .extract
                    .as_ref()
                    .map(|e| e.exclude.clone())
                    .unwrap_or_default(),
            });
        }
        Ok(natives)
    }

    /// log4j2 configuration. 1.8.9 declares one; without it the game logs
    /// through a default config and the launcher cannot pass
    /// `-Dlog4j.configurationFile`.
    fn resolve_logging(
        &self,
        version: &VersionDetail,
        feature: Feature,
    ) -> Result<Option<LoggingConfig>, ResolveError> {
        let Some(client_logging) = version.logging.as_ref().and_then(|l| l.client.as_ref()) else {
            return Ok(None);
        };
        let path = self.cache.logging_config(&client_logging.file.id)?;
        Ok(Some(LoggingConfig {
            download: self.request(
                format!("logging config {}", client_logging.file.id),
                &client_logging.file.url,
                Expectation::new(&client_logging.file.sha1, Some(client_logging.file.size)),
                path.clone(),
                Purpose::DownloadGameArtifact,
                feature,
            )?,
            path,
            argument: client_logging.argument.clone(),
        }))
    }

    /// Resolve everything except individual asset objects.
    ///
    /// Assets need the index, which has to be downloaded first; use
    /// [`crate::install::assets::AssetManager`] to add them once it is
    /// available.
    pub fn resolve(
        &self,
        version: &VersionDetail,
        os: Os,
        feature: Feature,
    ) -> Result<ResolvedInstall, ResolveError> {
        if version.id != SUPPORTED_VERSION {
            return Err(ResolveError::UnsupportedVersion {
                supported: SUPPORTED_VERSION.to_owned(),
                found: version.id.clone(),
            });
        }

        let client_download = version
            .downloads
            .client
            .as_ref()
            .ok_or(ResolveError::NoClient)?;
        let client = self.request(
            format!("{}.jar", version.id),
            &client_download.url,
            Expectation::new(&client_download.sha1, Some(client_download.size)),
            self.cache.client_jar(&version.id)?,
            Purpose::DownloadGameArtifact,
            feature,
        )?;

        let libraries = self.resolve_libraries(version, os, feature)?;
        let natives = self.resolve_natives(version, os, feature)?;

        // The asset index document.
        let (asset_index, asset_index_id) = match &version.asset_index {
            Some(index) => (
                Some(self.request(
                    format!("asset index {}", index.id),
                    &index.url,
                    Expectation::new(&index.sha1, Some(index.size)),
                    self.cache.asset_index(&index.id)?,
                    Purpose::ResolveGameMetadata,
                    feature,
                )?),
                Some(index.id.clone()),
            ),
            None => (None, None),
        };

        let logging = self.resolve_logging(version, feature)?;

        Ok(ResolvedInstall {
            version_id: version.id.clone(),
            main_class: version.main_class.clone(),
            argument_template: version.minecraft_arguments.clone(),
            required_java_major: version.required_java_major(),
            client,
            libraries,
            natives,
            asset_index,
            asset_index_id,
            assets: Vec::new(),
            logging,
        })
    }

    /// Build one request, validating the URL against the allowlist and the
    /// target against the cache root.
    pub(super) fn request(
        &self,
        name: String,
        url: &str,
        expect: Expectation,
        target: PathBuf,
        purpose: Purpose,
        feature: Feature,
    ) -> Result<DownloadRequest, ResolveError> {
        let request: RequestDescriptor =
            descriptor_from_url(url, purpose, feature).map_err(|source| ResolveError::Url {
                artifact: name.clone(),
                source,
            })?;

        debug_assert!(
            self.cache.contains(&target),
            "resolver produced a target outside the cache: {}",
            target.display()
        );

        Ok(DownloadRequest {
            name,
            request,
            expect,
            target,
        })
    }

    pub fn cache(&self) -> &ArtifactCache {
        &self.cache
    }
}

fn sanitise(name: &str) -> String {
    name.chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect()
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
    use crate::minecraft_runtime::version::OsFamily;
    use crate::network::Destination;
    use crate::paths::DataPaths;

    const WINDOWS: Os = Os {
        family: OsFamily::Windows,
        is_64bit: true,
    };
    const LINUX: Os = Os {
        family: OsFamily::Linux,
        is_64bit: true,
    };
    const MACOS: Os = Os {
        family: OsFamily::MacOs,
        is_64bit: true,
    };

    fn resolver() -> (tempfile::TempDir, ArtifactResolver) {
        let tmp = tempfile::tempdir().expect("tempdir");
        let paths = DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let cache = ArtifactCache::new(paths);
        cache.ensure().unwrap();
        (tmp, ArtifactResolver::new(cache))
    }

    /// A faithful excerpt of the real 1.8.9 document, including the pieces that
    /// are easy to get wrong: a rule-gated library, the natives entry whose
    /// rules exclude macOS, and the `logging` block.
    fn version() -> VersionDetail {
        serde_json::from_str(
            r#"{
              "id": "1.8.9",
              "mainClass": "net.minecraft.client.main.Main",
              "assets": "1.8",
              "minecraftArguments": "--username ${auth_player_name} --version ${version_name}",
              "javaVersion": { "component": "jre-legacy", "majorVersion": 8 },
              "assetIndex": {
                "id": "1.8",
                "sha1": "f6ad102bcaa53b1a58358f16e376d548d44933ec",
                "size": 78494,
                "totalSize": 114885064,
                "url": "https://launchermeta.mojang.com/v1/packages/f6ad102bcaa53b1a58358f16e376d548d44933ec/1.8.json"
              },
              "logging": {
                "client": {
                  "argument": "-Dlog4j.configurationFile=${path}",
                  "file": {
                    "id": "client-1.7.xml",
                    "sha1": "50c9cc4af6d853d9fc137c84bcd153e2bd3a9a82",
                    "size": 966,
                    "url": "https://launcher.mojang.com/v1/objects/50c9cc4af6d853d9fc137c84bcd153e2bd3a9a82/client-1.7.xml"
                  },
                  "type": "log4j2-xml"
                }
              },
              "downloads": {
                "client": {
                  "sha1": "b58b2ceb36e01bcd8dbf49c8fb66c55a9f0676cd",
                  "size": 8461484,
                  "url": "https://launcher.mojang.com/v1/objects/b58b2ceb36e01bcd8dbf49c8fb66c55a9f0676cd/client.jar"
                }
              },
              "libraries": [
                {
                  "name": "com.google.guava:guava:17.0",
                  "downloads": { "artifact": {
                    "sha1": "9c6ef172e8de35fd8d4d8783e4821e57cdef7445",
                    "size": 2251809,
                    "path": "com/google/guava/guava/17.0/guava-17.0.jar",
                    "url": "https://libraries.minecraft.net/com/google/guava/guava/17.0/guava-17.0.jar"
                  }}
                },
                {
                  "name": "org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209",
                  "downloads": { "classifiers": {
                    "natives-windows": {
                      "sha1": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "size": 100,
                      "path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-windows.jar",
                      "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-windows.jar"
                    },
                    "natives-linux": {
                      "sha1": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "size": 101,
                      "path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-linux.jar",
                      "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-linux.jar"
                    },
                    "natives-osx": {
                      "sha1": "cccccccccccccccccccccccccccccccccccccccc", "size": 102,
                      "path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-osx.jar",
                      "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-osx.jar"
                    }
                  }},
                  "natives": { "windows": "natives-windows", "linux": "natives-linux", "osx": "natives-osx" },
                  "extract": { "exclude": ["META-INF/"] },
                  "rules": [{ "action": "allow" }, { "action": "disallow", "os": { "name": "osx" } }]
                },
                {
                  "name": "osx.only:lib:1.0",
                  "downloads": { "artifact": {
                    "sha1": "dddddddddddddddddddddddddddddddddddddddd", "size": 30,
                    "path": "osx/only/1.0/only-1.0.jar",
                    "url": "https://libraries.minecraft.net/osx/only/1.0/only-1.0.jar"
                  }},
                  "rules": [{ "action": "allow", "os": { "name": "osx" } }]
                }
              ]
            }"#,
        )
        .expect("sample 1.8.9 document should parse")
    }

    #[test]
    fn the_real_1_8_9_shape_resolves_completely() {
        let (_tmp, r) = resolver();
        let plan = r
            .resolve(&version(), WINDOWS, Feature::ProfileInstall)
            .unwrap();

        assert_eq!(plan.version_id, "1.8.9");
        assert_eq!(plan.main_class, "net.minecraft.client.main.Main");
        assert_eq!(plan.required_java_major, 8);
        assert!(
            plan.argument_template.is_some(),
            "1.8.9 uses the legacy string"
        );
        assert_eq!(plan.asset_index_id.as_deref(), Some("1.8"));
        assert!(plan.asset_index.is_some());
        assert!(plan.logging.is_some(), "1.8.9 declares a log4j2 config");
        assert_eq!(plan.client.expect.size, Some(8_461_484));
    }

    #[test]
    fn only_the_supported_version_resolves() {
        let (_tmp, r) = resolver();
        let mut other = version();
        other.id = "1.20.4".into();
        assert!(matches!(
            r.resolve(&other, WINDOWS, Feature::ProfileInstall),
            Err(ResolveError::UnsupportedVersion { .. })
        ));
    }

    #[test]
    fn library_rules_are_evaluated_per_platform() {
        let (_tmp, r) = resolver();
        let v = version();

        let win = r.resolve(&v, WINDOWS, Feature::ProfileInstall).unwrap();
        let mac = r.resolve(&v, MACOS, Feature::ProfileInstall).unwrap();

        let win_names: Vec<&str> = win.libraries.iter().map(|l| l.name.as_str()).collect();
        let mac_names: Vec<&str> = mac.libraries.iter().map(|l| l.name.as_str()).collect();

        assert!(win_names.contains(&"com.google.guava:guava:17.0"));
        assert!(!win_names.contains(&"osx.only:lib:1.0"));
        assert!(mac_names.contains(&"osx.only:lib:1.0"));
    }

    #[test]
    fn native_classifiers_are_selected_per_platform() {
        let (_tmp, r) = resolver();
        let v = version();

        let win = r.resolve(&v, WINDOWS, Feature::ProfileInstall).unwrap();
        assert_eq!(win.natives.len(), 1);
        assert_eq!(win.natives[0].download.expect.sha1, "a".repeat(40));
        assert_eq!(win.natives[0].exclude, vec!["META-INF/".to_owned()]);

        let linux = r.resolve(&v, LINUX, Feature::ProfileInstall).unwrap();
        assert_eq!(linux.natives[0].download.expect.sha1, "b".repeat(40));

        // The rules exclude this library entirely on macOS, so despite the
        // `natives-osx` classifier existing there is nothing to fetch.
        let mac = r.resolve(&v, MACOS, Feature::ProfileInstall).unwrap();
        assert!(
            mac.natives.is_empty(),
            "rules must be applied before the classifier is chosen"
        );
    }

    #[test]
    fn natives_never_appear_on_the_classpath() {
        let (_tmp, r) = resolver();
        let plan = r
            .resolve(&version(), WINDOWS, Feature::ProfileInstall)
            .unwrap();
        let cp = plan.classpath();

        assert!(!cp
            .iter()
            .any(|p| p.to_string_lossy().contains("natives-windows")));
        assert!(
            cp.last().unwrap().ends_with("1.8.9.jar"),
            "the client jar goes last"
        );
    }

    #[test]
    fn every_url_resolves_to_an_allowlisted_host() {
        let (_tmp, r) = resolver();
        let plan = r
            .resolve(&version(), WINDOWS, Feature::ProfileInstall)
            .unwrap();

        for item in plan.all_downloads() {
            assert!(
                Destination::ALL.contains(&item.request.destination),
                "{} targets an unlisted host",
                item.name
            );
        }
        // The asset index really does come from the older metadata host.
        assert_eq!(
            plan.asset_index.as_ref().unwrap().request.destination,
            Destination::LauncherMeta
        );
    }

    #[test]
    fn a_tampered_url_fails_to_resolve_rather_than_being_fetched() {
        let (_tmp, r) = resolver();
        let mut v = version();
        v.downloads.client.as_mut().unwrap().url = "https://evil.example/client.jar".into();

        assert!(matches!(
            r.resolve(&v, WINDOWS, Feature::ProfileInstall),
            Err(ResolveError::Url { .. })
        ));
    }

    #[test]
    fn a_tampered_library_path_fails_to_resolve() {
        let (_tmp, r) = resolver();
        let mut v = version();
        v.libraries[0].downloads.artifact.as_mut().unwrap().path =
            Some("../../../../evil.jar".into());

        assert!(matches!(
            r.resolve(&v, WINDOWS, Feature::ProfileInstall),
            Err(ResolveError::Cache(_))
        ));
    }

    #[test]
    fn every_target_lands_inside_the_shared_cache() {
        let (_tmp, r) = resolver();
        let plan = r
            .resolve(&version(), WINDOWS, Feature::ProfileInstall)
            .unwrap();
        for item in plan.all_downloads() {
            assert!(
                r.cache().contains(&item.target),
                "{} would be written outside the cache",
                item.name
            );
        }
    }

    #[test]
    fn the_logging_argument_substitutes_the_installed_path() {
        let (_tmp, r) = resolver();
        let plan = r
            .resolve(&version(), WINDOWS, Feature::ProfileInstall)
            .unwrap();
        let logging = plan.logging.as_ref().unwrap();

        let arg = logging.jvm_argument();
        let arg = arg.expect("a real 1.8.9 logging argument is accepted");
        assert!(arg.starts_with("-Dlog4j.configurationFile="));
        assert!(!arg.contains("${path}"), "the placeholder must be replaced");
        assert!(arg.contains("client-1.7.xml"));
    }

    /// The security pass found this: `logging.client.argument` is a free-form
    /// template from the version document, and it was pushed onto the JVM
    /// argument list without passing `jvm_args::validate_arg` — the one route
    /// into the argument vector that skipped every refusal in that module.
    ///
    /// Reaching this field means an attacker already holds Mojang's metadata or
    /// our transport, which is precisely when defence in depth is the only
    /// thing left.
    #[test]
    fn a_logging_argument_that_is_not_a_log4j_property_is_refused() {
        let (_tmp, r) = resolver();
        let plan = r
            .resolve(&version(), WINDOWS, Feature::ProfileInstall)
            .unwrap();
        let real = plan.logging.as_ref().unwrap();

        for argument in [
            "-XX:OnOutOfMemoryError=calc.exe",
            "-agentpath:C:/Users/Public/evil.dll",
            "-javaagent:${path}",
            "-Djava.library.path=${path}",
            "-Dlog4j.configurationFileButNot=${path}",
            "",
        ] {
            let hostile = LoggingConfig {
                download: real.download.clone(),
                path: real.path.clone(),
                argument: argument.to_owned(),
            };
            assert!(
                hostile.jvm_argument().is_none(),
                "{argument:?} must not reach the JVM command line"
            );
        }
    }

    #[test]
    fn declared_bytes_counts_what_the_metadata_declares() {
        let (_tmp, r) = resolver();
        let plan = r
            .resolve(&version(), WINDOWS, Feature::ProfileInstall)
            .unwrap();
        // client + guava + natives + asset index + logging config
        assert_eq!(
            plan.declared_bytes(),
            8_461_484 + 2_251_809 + 100 + 78_494 + 966
        );
    }

    #[test]
    fn the_download_order_puts_small_metadata_first() {
        let (_tmp, r) = resolver();
        let plan = r
            .resolve(&version(), WINDOWS, Feature::ProfileInstall)
            .unwrap();
        let names: Vec<&str> = plan
            .all_downloads()
            .iter()
            .map(|d| d.name.as_str())
            .collect();

        let index_at = names
            .iter()
            .position(|n| n.contains("asset index"))
            .unwrap();
        let client_at = names.iter().position(|n| *n == "1.8.9.jar").unwrap();
        assert!(
            index_at < client_at,
            "cheap metadata should fail fast before the 8 MB client jar"
        );
    }
}
