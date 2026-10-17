//! Forge 1.8.9.
//!
//! A separate, opt-in loader alongside Legacy Fabric: a profile chooses it, and
//! nothing about a Fabric profile changes because Forge exists. Fathom's own
//! in-game client is a Fabric mod and does not load under Forge; a Forge
//! profile is Minecraft, Forge and the user's own mods.
//!
//! # Forge's installer is read, never run
//!
//! Forge distributes an installer jar whose job is to execute code and write
//! files. The launcher never executes a third-party installer (`THREAT_MODEL.md`,
//! `mod-policies.md`). Instead:
//!
//! ```text
//! GET maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml
//!     -> every Forge build; the ones for this Minecraft version
//! GET files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json
//!     -> which build Forge recommends (marked stable)
//! GET .../forge/<v>/forge-<v>-installer.jar(.sha1)
//!     -> verified against its sidecar, then opened as a zip and only
//!        `install_profile.json` read out of it: the launch profile
//!        (main class, game arguments) and the library list
//! GET .../forge/<v>/forge-<v>-universal.jar(.sha1)
//!     -> Forge itself, the jar the installer would have extracted
//! ```
//!
//! The resolved build -- every artifact with its URL, cache path and hash -- is
//! kept in the metadata cache, so a launch after the first contacts nobody.
//!
//! # Nothing unverified reaches the classpath
//!
//! As for Legacy Fabric, every artifact has a SHA-1 before it is fetched:
//!
//! 1. the repository's `.sha1` sidecar; when the install profile also lists
//!    checksums for that library, the sidecar must be one of them;
//! 2. failing a sidecar, the install profile's own first checksum, which is the
//!    jar's (checked against Forge 1.8.9's two libraries that have no sidecar);
//! 3. failing both, the build is refused.
//!
//! Library URLs come from a downloaded document, so each goes through the
//! allowlist like any other untrusted URL.
//!
//! # Licence
//!
//! Forge is LGPL-2.1 with additional terms. Fathom downloads Forge's own
//! published files from Forge's own repository when a user chooses Forge for a
//! profile, and never bundles or redistributes them (`mod-policies.md` §2, the
//! decision recorded 2026-10-13).

use std::io::Read;

use serde::{Deserialize, Serialize};

use super::legacy_fabric::{encode, maven_path, sidecar_sha1};
use super::{
    LoaderError, LoaderInstallation, LoaderProvider, LoaderVersion, ModDirectorySemantics,
};
use crate::install::download::{sha1_hex, Expectation};
use crate::install::{ArtifactCache, DownloadRequest};
use crate::network::policy::descriptor_from_url;
use crate::network::{Destination, Feature, HttpClient, Purpose, RequestDescriptor};
use crate::profiles::Loader;

/// The format of the resolved build kept in the metadata cache.
const SCHEMA: u32 = 1;

/// Cap on a metadata document. Forge's version list is around 150 KB.
const MAX_METADATA_BYTES: usize = 4 * 1024 * 1024;

/// Cap on `install_profile.json` once unzipped. 1.8.9's is about 6 KB.
const MAX_PROFILE_BYTES: u64 = 1024 * 1024;

const MAVEN: &str = "https://maven.minecraftforge.net/";

/// Forge 1.8.9 runs on Java 8 and fails on later runtimes: launchwrapper casts
/// the system class loader to a `URLClassLoader`, which it stopped being in 9.
const JAVA: u32 = 8;

#[derive(Debug, Clone, Copy, Default)]
pub struct ForgeProvider;

impl LoaderProvider for ForgeProvider {
    fn kind(&self) -> Loader {
        Loader::Forge
    }

    fn name(&self) -> &'static str {
        "Forge"
    }

    fn is_implemented(&self) -> bool {
        true
    }

    /// Jars in `mods/`, some of them coremods that FML loads earlier by its own
    /// rules. Both kinds live in the same directory in 1.8.9.
    fn mod_directory(&self) -> ModDirectorySemantics {
        ModDirectorySemantics::FlatDirectoryWithCoremods
    }

    fn available_versions(
        &self,
        minecraft_version: &str,
        http: &HttpClient,
        feature: Feature,
    ) -> Result<Vec<LoaderVersion>, LoaderError> {
        let builds = builds_for(minecraft_version, &maven_versions(http, feature)?);
        if builds.is_empty() {
            return Err(LoaderError::UnsupportedMinecraftVersion {
                loader: "Forge",
                minecraft_version: minecraft_version.to_owned(),
            });
        }
        // The recommended build is marked stable. Asked, not assumed; when the
        // file host cannot answer, the list is still offered, with nothing
        // marked, rather than refused.
        let recommended = promoted(minecraft_version, http, feature).unwrap_or_else(|error| {
            tracing::warn!(%error, "could not read Forge's recommended build");
            None
        });
        Ok(builds
            .into_iter()
            .map(|build| LoaderVersion {
                stable: recommended.as_deref() == Some(build.forge.as_str()),
                version: build.forge,
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
        let build = resolved_build(minecraft_version, loader_version, cache, http, feature)?;
        let mut libraries = Vec::with_capacity(build.libraries.len());
        for library in &build.libraries {
            // Revalidated on every use, including when read back from the
            // cache: the cache is a file on disk, and a URL or path in it is no
            // more trusted than the document it came from.
            let request =
                descriptor_from_url(&library.url, Purpose::DownloadLoaderArtifact, feature)?;
            libraries.push(DownloadRequest {
                name: library.name.clone(),
                request,
                expect: Expectation::new(&library.sha1, None),
                target: cache.library_path(&library.path)?,
            });
        }
        let mut game_arguments = Vec::new();
        for tweaker in &build.tweak_classes {
            game_arguments.push("--tweakClass".to_owned());
            game_arguments.push(tweaker.clone());
        }
        Ok(LoaderInstallation {
            loader: Loader::Forge,
            loader_version: build.forge.clone(),
            minecraft_version: minecraft_version.to_owned(),
            main_class: Some(build.main_class.clone()),
            libraries,
            jvm_arguments: Vec::new(),
            game_arguments,
            minimum_java_major: Some(JAVA),
            mod_directory: ModDirectorySemantics::FlatDirectoryWithCoremods,
        })
    }
}

// ---------------------------------------------------------------------------
// Versions
// ---------------------------------------------------------------------------

/// One Forge build for a Minecraft version.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Build {
    /// Forge's own version, as people name it: `11.15.1.2318`.
    forge: String,
    /// The Maven version: `1.8.9-11.15.1.2318-1.8.9` (some builds omit the
    /// trailing Minecraft version).
    maven: String,
}

/// Every version in Forge's `maven-metadata.xml`.
fn maven_versions(http: &HttpClient, feature: Feature) -> Result<Vec<String>, LoaderError> {
    let body = fetch(
        Destination::ForgeMaven,
        "/net/minecraftforge/forge/maven-metadata.xml",
        http,
        feature,
    )?;
    Ok(versions_in(&String::from_utf8_lossy(&body)))
}

/// The `<version>` elements of a Maven metadata document.
///
/// Read by hand rather than with an XML parser: the document is one flat list,
/// and a dependency to read one element name is not worth carrying.
fn versions_in(document: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut rest = document;
    while let Some(start) = rest.find("<version>") {
        rest = &rest[start + "<version>".len()..];
        let Some(end) = rest.find("</version>") else {
            break;
        };
        let value = rest[..end].trim();
        if !value.is_empty() && value.len() <= 64 && value.chars().all(version_char) {
            out.push(value.to_owned());
        }
        rest = &rest[end..];
    }
    out
}

fn version_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_')
}

/// The builds for one Minecraft version, newest first.
fn builds_for(minecraft_version: &str, versions: &[String]) -> Vec<Build> {
    let prefix = format!("{minecraft_version}-");
    let suffix = format!("-{minecraft_version}");
    let mut builds: Vec<Build> = versions
        .iter()
        .filter_map(|maven| {
            let forge = maven.strip_prefix(&prefix)?;
            let forge = forge.strip_suffix(&suffix).unwrap_or(forge);
            // Forge's own versions are dotted numbers. Anything else in the
            // list (a branch name) is not offered.
            if forge.is_empty() || !forge.split('.').all(|part| part.parse::<u32>().is_ok()) {
                return None;
            }
            Some(Build {
                forge: forge.to_owned(),
                maven: maven.clone(),
            })
        })
        .collect();
    builds.sort_by(|a, b| numeric(&b.forge).cmp(&numeric(&a.forge)));
    builds.dedup_by(|a, b| a.forge == b.forge);
    builds
}

fn numeric(version: &str) -> Vec<u32> {
    version
        .split('.')
        .filter_map(|part| part.parse().ok())
        .collect()
}

#[derive(Deserialize)]
struct Promotions {
    promos: std::collections::HashMap<String, String>,
}

/// The build Forge recommends for a Minecraft version, if it names one.
fn promoted(
    minecraft_version: &str,
    http: &HttpClient,
    feature: Feature,
) -> Result<Option<String>, LoaderError> {
    let body = fetch(
        Destination::ForgeFiles,
        "/net/minecraftforge/forge/promotions_slim.json",
        http,
        feature,
    )?;
    let promotions: Promotions = serde_json::from_slice(&body)
        .map_err(|e| LoaderError::Malformed(format!("Forge's promotions: {e}")))?;
    Ok(promotions
        .promos
        .get(&format!("{minecraft_version}-recommended"))
        .or_else(|| {
            promotions
                .promos
                .get(&format!("{minecraft_version}-latest"))
        })
        .cloned())
}

// ---------------------------------------------------------------------------
// One build, resolved
// ---------------------------------------------------------------------------

/// Everything a build needs, with every hash, as kept in the metadata cache.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ResolvedBuild {
    schema: u32,
    minecraft: String,
    forge: String,
    maven: String,
    main_class: String,
    tweak_classes: Vec<String>,
    libraries: Vec<ResolvedLibrary>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
struct ResolvedLibrary {
    name: String,
    url: String,
    /// Relative to the shared library directory.
    path: String,
    sha1: String,
}

/// The resolved build, from the metadata cache when a valid copy is there,
/// otherwise from Forge's repository -- kept for next time. A build never
/// changes once published, so a launch after the first contacts nobody.
fn resolved_build(
    minecraft_version: &str,
    forge: &str,
    cache: &ArtifactCache,
    http: &HttpClient,
    feature: Feature,
) -> Result<ResolvedBuild, LoaderError> {
    let file = cache
        .paths()
        .metadata_cache_dir()
        .join("forge")
        .join(format!(
            "{}-{}.json",
            encode(minecraft_version),
            encode(forge)
        ));
    if let Ok(bytes) = std::fs::read(&file) {
        match serde_json::from_slice::<ResolvedBuild>(&bytes) {
            Ok(build)
                if build.schema == SCHEMA
                    && build.minecraft == minecraft_version
                    && build.forge == forge =>
            {
                return Ok(build);
            }
            _ => {
                tracing::info!(file = %file.display(), "the kept Forge build is not usable; resolving again")
            }
        }
    }

    let build = builds_for(minecraft_version, &maven_versions(http, feature)?)
        .into_iter()
        .find(|build| build.forge == forge)
        .ok_or_else(|| LoaderError::UnknownLoaderVersion {
            loader: "Forge",
            version: forge.to_owned(),
            minecraft_version: minecraft_version.to_owned(),
        })?;
    let profile = install_profile(&build, http, feature)?;
    let resolved = resolve_profile(minecraft_version, &build, &profile, http, feature)?;

    // Best effort, written beside and renamed over: a copy that cannot be kept
    // costs a resolution next time, not this launch.
    let written = serde_json::to_vec_pretty(&resolved)
        .map_err(std::io::Error::other)
        .and_then(|bytes| {
            if let Some(parent) = file.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let partial = file.with_extension("json.partial");
            std::fs::write(&partial, bytes)?;
            std::fs::rename(&partial, &file)
        });
    if let Err(error) = written {
        tracing::warn!(%error, file = %file.display(), "could not keep the resolved Forge build");
    }
    Ok(resolved)
}

/// What `install_profile.json` says, the parts the launcher uses.
#[derive(Debug, Deserialize)]
struct InstallProfile {
    install: InstallSection,
    #[serde(rename = "versionInfo")]
    version_info: VersionInfo,
}

#[derive(Debug, Deserialize)]
struct InstallSection {
    minecraft: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct VersionInfo {
    main_class: String,
    minecraft_arguments: String,
    libraries: Vec<ProfileLibrary>,
}

#[derive(Debug, Deserialize)]
struct ProfileLibrary {
    name: String,
    url: Option<String>,
    clientreq: Option<bool>,
    #[serde(default)]
    checksums: Vec<String>,
}

/// Download the installer, check it against its sidecar, and read
/// `install_profile.json` out of it. Nothing in the jar is executed or
/// extracted to disk.
fn install_profile(
    build: &Build,
    http: &HttpClient,
    feature: Feature,
) -> Result<InstallProfile, LoaderError> {
    let relative = format!(
        "net/minecraftforge/forge/{0}/forge-{0}-installer.jar",
        encode(&build.maven)
    );
    let expected =
        sidecar_sha1(&relative, Destination::ForgeMaven, http, feature)?.ok_or_else(|| {
            LoaderError::UnverifiableArtifact {
                name: format!("forge-{}-installer.jar", build.maven),
            }
        })?;
    let request = RequestDescriptor::new(
        Destination::ForgeMaven,
        Purpose::ResolveLoaderMetadata,
        feature,
        format!("/{relative}"),
    );
    let response = http.get(&request)?;
    if !response.is_success() {
        return Err(LoaderError::Malformed(format!(
            "Forge's installer for {} could not be fetched (status {})",
            build.forge, response.status
        )));
    }
    if sha1_hex(&response.body) != expected {
        return Err(LoaderError::UnverifiableArtifact {
            name: format!(
                "forge-{}-installer.jar (does not match its published SHA-1)",
                build.maven
            ),
        });
    }
    read_install_profile(&response.body)
}

/// `install_profile.json` from an installer's bytes.
fn read_install_profile(jar: &[u8]) -> Result<InstallProfile, LoaderError> {
    let mut archive = zip::ZipArchive::new(std::io::Cursor::new(jar))
        .map_err(|e| LoaderError::Malformed(format!("Forge's installer is not a zip: {e}")))?;
    let entry = archive.by_name("install_profile.json").map_err(|_| {
        LoaderError::Malformed("Forge's installer has no install_profile.json".to_owned())
    })?;
    if entry.size() > MAX_PROFILE_BYTES {
        return Err(LoaderError::Malformed(
            "Forge's install_profile.json is far larger than expected".to_owned(),
        ));
    }
    let mut text = String::new();
    entry
        .take(MAX_PROFILE_BYTES)
        .read_to_string(&mut text)
        .map_err(|e| LoaderError::Malformed(format!("Forge's install_profile.json: {e}")))?;
    serde_json::from_str(&text)
        .map_err(|e| LoaderError::Malformed(format!("Forge's install_profile.json: {e}")))
}

/// Turn an install profile into a build with every artifact's hash.
fn resolve_profile(
    minecraft_version: &str,
    build: &Build,
    profile: &InstallProfile,
    http: &HttpClient,
    feature: Feature,
) -> Result<ResolvedBuild, LoaderError> {
    if profile.install.minecraft != minecraft_version {
        return Err(LoaderError::Malformed(format!(
            "Forge {} is for Minecraft {}, not {minecraft_version}",
            build.forge, profile.install.minecraft
        )));
    }
    let main_class = profile.version_info.main_class.trim().to_owned();
    if !java_class_name(&main_class) {
        return Err(LoaderError::Malformed(format!(
            "Forge's profile names an unusable main class: {main_class}"
        )));
    }
    let tweak_classes = tweakers(&profile.version_info.minecraft_arguments)?;

    let forge_coordinate = format!("net.minecraftforge:forge:{}", build.maven);
    let mut libraries = Vec::new();
    for library in &profile.version_info.libraries {
        // `clientreq: false` marks a server-only library (jline, for 1.8.9).
        // Absent or true are both the client's: the vanilla launcher treated
        // every listed library as needed.
        if library.clientreq == Some(false) {
            continue;
        }
        let path = maven_path(&library.name)
            .ok_or_else(|| LoaderError::Malformed(format!("bad coordinate {}", library.name)))?;
        if library.name == forge_coordinate {
            libraries.push(forge_itself(build, &path, http, feature)?);
            continue;
        }
        let (base, repository) = repository_for(library.url.as_deref())?;
        let sidecar = sidecar_sha1(&path, repository, http, feature)?;
        let sha1 = choose_hash(&library.name, sidecar, &library.checksums)?;
        libraries.push(ResolvedLibrary {
            name: library.name.clone(),
            url: format!("{base}{path}"),
            path,
            sha1,
        });
    }
    if !libraries
        .iter()
        .any(|library| library.name == forge_coordinate)
    {
        return Err(LoaderError::Malformed(
            "Forge's profile does not list Forge itself".to_owned(),
        ));
    }
    Ok(ResolvedBuild {
        schema: SCHEMA,
        minecraft: minecraft_version.to_owned(),
        forge: build.forge.clone(),
        maven: build.maven.clone(),
        main_class,
        tweak_classes,
        libraries,
    })
}

/// Forge's universal jar, under the classifier-less path its profile names --
/// exactly where Forge's installer would have put it.
fn forge_itself(
    build: &Build,
    path: &str,
    http: &HttpClient,
    feature: Feature,
) -> Result<ResolvedLibrary, LoaderError> {
    let universal = format!(
        "net/minecraftforge/forge/{0}/forge-{0}-universal.jar",
        encode(&build.maven)
    );
    let sha1 =
        sidecar_sha1(&universal, Destination::ForgeMaven, http, feature)?.ok_or_else(|| {
            LoaderError::UnverifiableArtifact {
                name: format!("forge-{}-universal.jar", build.maven),
            }
        })?;
    Ok(ResolvedLibrary {
        name: format!("net.minecraftforge:forge:{}", build.maven),
        url: format!("{MAVEN}{universal}"),
        path: path.to_owned(),
        sha1,
    })
}

/// Where a library comes from: the URL its profile names, or Mojang's library
/// host when it names none (the vanilla launcher's rule). Forge's old file host
/// is today's Maven.
fn repository_for(url: Option<&str>) -> Result<(String, Destination), LoaderError> {
    let Some(url) = url else {
        return Ok((
            "https://libraries.minecraft.net/".to_owned(),
            Destination::LibrariesMinecraft,
        ));
    };
    let url = url.trim();
    let normalised = if url.contains("files.minecraftforge.net/maven")
        || url.contains("maven.minecraftforge.net")
    {
        MAVEN.to_owned()
    } else {
        // Any other repository goes through the allowlist when the artifact is
        // requested; refuse here with the reason if it is not one we know.
        let descriptor = descriptor_from_url(
            url,
            Purpose::DownloadLoaderArtifact,
            Feature::ProfileInstall,
        )?;
        return Ok((
            format!("https://{}/", descriptor.destination.host()),
            descriptor.destination,
        ));
    };
    Ok((normalised, Destination::ForgeMaven))
}

/// The SHA-1 to expect for a library: its sidecar, cross-checked against the
/// profile's checksums when it lists any; else the profile's first checksum;
/// else nothing, and the build is refused.
fn choose_hash(
    name: &str,
    sidecar: Option<String>,
    listed: &[String],
) -> Result<String, LoaderError> {
    let listed: Vec<String> = listed
        .iter()
        .map(|hash| hash.trim().to_ascii_lowercase())
        .filter(|hash| hash.len() == 40 && hash.chars().all(|c| c.is_ascii_hexdigit()))
        .collect();
    match sidecar {
        Some(hash) if listed.is_empty() || listed.contains(&hash) => Ok(hash),
        Some(hash) => Err(LoaderError::UnverifiableArtifact {
            name: format!("{name} (its repository's SHA-1 {hash} is not one Forge lists)"),
        }),
        None => listed
            .into_iter()
            .next()
            .ok_or_else(|| LoaderError::UnverifiableArtifact {
                name: name.to_owned(),
            }),
    }
}

/// The `--tweakClass` values in Forge's game arguments. Everything else in that
/// string is the vanilla argument set, which the launcher already builds.
fn tweakers(arguments: &str) -> Result<Vec<String>, LoaderError> {
    let tokens: Vec<&str> = arguments.split_whitespace().collect();
    let mut out = Vec::new();
    let mut index = 0;
    while index < tokens.len() {
        if tokens[index] == "--tweakClass" {
            let Some(class) = tokens.get(index + 1) else {
                return Err(LoaderError::Malformed(
                    "Forge's profile ends with a --tweakClass and no class".to_owned(),
                ));
            };
            if !java_class_name(class) {
                return Err(LoaderError::Malformed(format!(
                    "Forge's profile names an unusable tweak class: {class}"
                )));
            }
            out.push((*class).to_owned());
            index += 2;
        } else {
            index += 1;
        }
    }
    if out.is_empty() {
        return Err(LoaderError::Malformed(
            "Forge's profile names no tweak class, so FML would never start".to_owned(),
        ));
    }
    Ok(out)
}

/// A dotted Java class name, and nothing that could be read as an option.
fn java_class_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 256
        && !name.starts_with('-')
        && name.split('.').all(|part| {
            !part.is_empty()
                && part
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '$')
                && !part.starts_with(|c: char| c.is_ascii_digit())
        })
}

fn fetch(
    destination: Destination,
    path: &str,
    http: &HttpClient,
    feature: Feature,
) -> Result<Vec<u8>, LoaderError> {
    let request =
        RequestDescriptor::new(destination, Purpose::ResolveLoaderMetadata, feature, path);
    let response = http.get(&request)?;
    if !response.is_success() {
        return Err(LoaderError::Malformed(format!(
            "{}{path} answered with status {}",
            destination.host(),
            response.status
        )));
    }
    if response.body.len() > MAX_METADATA_BYTES {
        return Err(LoaderError::Malformed(format!(
            "{}{path} is {} bytes, far larger than expected",
            destination.host(),
            response.body.len()
        )));
    }
    Ok(response.body)
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]
mod tests {
    use super::*;
    use crate::network::{NetworkJournal, NetworkPolicy};
    use crate::paths::DataPaths;

    const METADATA: &str = "<metadata><versioning><versions>\
        <version>1.8.8-11.15.0.1655</version>\
        <version>1.8.9-11.15.0.1656</version>\
        <version>1.8.9-11.15.1.2318-1.8.9</version>\
        <version>1.8.9-11.15.1.1722</version>\
        <version>1.8.9-nonsense</version>\
        <version>1.10-12.18.0.2000</version>\
        </versions></versioning></metadata>";

    #[test]
    fn builds_are_this_minecraft_versions_newest_first() {
        let builds = builds_for("1.8.9", &versions_in(METADATA));
        let forge: Vec<&str> = builds.iter().map(|b| b.forge.as_str()).collect();
        assert_eq!(forge, ["11.15.1.2318", "11.15.1.1722", "11.15.0.1656"]);
        assert_eq!(builds[0].maven, "1.8.9-11.15.1.2318-1.8.9");
        assert_eq!(
            builds[1].maven, "1.8.9-11.15.1.1722",
            "the suffix is optional"
        );
    }

    #[test]
    fn a_version_element_that_could_be_anything_is_dropped() {
        let versions =
            versions_in("<version>1.8.9-1/../x</version><version>1.8.9-11.15.1.2318</version>");
        assert_eq!(versions, ["1.8.9-11.15.1.2318"]);
    }

    /// Forge 1.8.9's own arguments, as its install profile carries them.
    const ARGUMENTS: &str = "--username ${auth_player_name} --version ${version_name} --gameDir \
        ${game_directory} --assetsDir ${assets_root} --assetIndex ${assets_index_name} --uuid \
        ${auth_uuid} --accessToken ${auth_access_token} --userProperties ${user_properties} \
        --userType ${user_type} --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker";

    #[test]
    fn only_the_tweaker_is_taken_from_forges_arguments() {
        assert_eq!(
            tweakers(ARGUMENTS).unwrap(),
            ["net.minecraftforge.fml.common.launcher.FMLTweaker"]
        );
    }

    #[test]
    fn a_tweaker_that_is_not_a_class_is_refused() {
        assert!(tweakers("--tweakClass --accessToken").is_err());
        assert!(tweakers("--tweakClass a.b;calc").is_err());
        assert!(tweakers("--username x").is_err(), "no tweaker, no FML");
    }

    #[test]
    fn hashes_come_from_the_sidecar_cross_checked_or_the_profile() {
        let jar = "f05d7345bf5a58924f2837c6c1f4d73a938e1ff0".to_owned();
        let pack = "a1cbbcbde1dcc614f4253ed1aa0b320bc78d8f1d".to_owned();
        assert_eq!(
            choose_hash("x", Some(jar.clone()), &[jar.clone(), pack.clone()]).unwrap(),
            jar
        );
        assert_eq!(choose_hash("x", Some(jar.clone()), &[]).unwrap(), jar);
        assert_eq!(
            choose_hash("x", None, &[jar.clone(), pack.clone()]).unwrap(),
            jar
        );
        assert!(
            choose_hash("x", Some("0".repeat(40)), &[jar, pack]).is_err(),
            "a sidecar Forge does not list"
        );
        assert!(
            choose_hash("x", None, &[]).is_err(),
            "nothing to verify against"
        );
    }

    #[test]
    fn libraries_without_a_url_come_from_mojang_and_forges_old_host_is_its_maven() {
        assert_eq!(
            repository_for(None).unwrap().1,
            Destination::LibrariesMinecraft
        );
        assert_eq!(
            repository_for(Some("http://files.minecraftforge.net/maven/")).unwrap(),
            (MAVEN.to_owned(), Destination::ForgeMaven)
        );
        assert_eq!(
            repository_for(Some("https://maven.minecraftforge.net/"))
                .unwrap()
                .1,
            Destination::ForgeMaven
        );
        assert!(repository_for(Some("https://evil.example/")).is_err());
    }

    fn profile(json: &str) -> InstallProfile {
        serde_json::from_str(json).unwrap()
    }

    #[test]
    fn a_profile_for_another_minecraft_is_refused() {
        let http = HttpClient::new(NetworkPolicy::default(), NetworkJournal::new(), "test/0");
        let build = Build {
            forge: "11.15.1.2318".into(),
            maven: "1.8.9-11.15.1.2318-1.8.9".into(),
        };
        let wrong = profile(&format!(
            r#"{{"install":{{"minecraft":"1.8.8"}},"versionInfo":{{"mainClass":"net.minecraft.launchwrapper.Launch","minecraftArguments":"{ARGUMENTS}","libraries":[]}}}}"#
        ));
        assert!(resolve_profile("1.8.9", &build, &wrong, &http, Feature::ProfileInstall).is_err());
    }

    #[test]
    fn an_installer_is_read_for_its_profile_and_nothing_else() {
        let mut bytes = std::io::Cursor::new(Vec::new());
        {
            let mut writer = zip::ZipWriter::new(&mut bytes);
            let options: zip::write::FileOptions<'_, ()> = zip::write::FileOptions::default();
            writer.start_file("install_profile.json", options).unwrap();
            std::io::Write::write_all(
                &mut writer,
                format!(
                    r#"{{"install":{{"minecraft":"1.8.9"}},"versionInfo":{{"mainClass":"net.minecraft.launchwrapper.Launch","minecraftArguments":"{ARGUMENTS}","libraries":[{{"name":"jline:jline:2.13","clientreq":false}}]}}}}"#
                )
                .as_bytes(),
            )
            .unwrap();
            writer.finish().unwrap();
        }
        let read = read_install_profile(bytes.get_ref()).unwrap();
        assert_eq!(read.install.minecraft, "1.8.9");
        assert_eq!(
            read.version_info.main_class,
            "net.minecraft.launchwrapper.Launch"
        );
        assert_eq!(read.version_info.libraries[0].clientreq, Some(false));
        assert!(read_install_profile(b"not a zip").is_err());
    }

    /// A build kept in the cache launches with nobody contacted, and its URLs
    /// and paths are still checked on the way out.
    #[test]
    fn a_kept_build_resolves_offline_and_is_still_checked() {
        let tmp = tempfile::tempdir().unwrap();
        let cache = ArtifactCache::new(DataPaths::rooted_at(tmp.path()));
        let http = HttpClient::new(NetworkPolicy::default(), NetworkJournal::new(), "test/0");
        let kept = ResolvedBuild {
            schema: SCHEMA,
            minecraft: "1.8.9".into(),
            forge: "11.15.1.2318".into(),
            maven: "1.8.9-11.15.1.2318-1.8.9".into(),
            main_class: "net.minecraft.launchwrapper.Launch".into(),
            tweak_classes: vec!["net.minecraftforge.fml.common.launcher.FMLTweaker".into()],
            libraries: vec![ResolvedLibrary {
                name: "net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9".into(),
                url: format!("{MAVEN}net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar"),
                path: "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9.jar".into(),
                sha1: "beda619c465af293e63952dd573c137c17c0a4cd".into(),
            }],
        };
        let file = cache
            .paths()
            .metadata_cache_dir()
            .join("forge")
            .join("1.8.9-11.15.1.2318.json");
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(&file, serde_json::to_vec(&kept).unwrap()).unwrap();

        let installed = ForgeProvider
            .resolve("1.8.9", "11.15.1.2318", &cache, &http, Feature::Play)
            .expect("resolved from the kept build");
        assert_eq!(
            installed.main_class.as_deref(),
            Some("net.minecraft.launchwrapper.Launch")
        );
        assert_eq!(
            installed.game_arguments,
            [
                "--tweakClass",
                "net.minecraftforge.fml.common.launcher.FMLTweaker"
            ]
        );
        assert_eq!(installed.minimum_java_major, Some(8));
        assert_eq!(installed.libraries.len(), 1);
        assert!(installed.libraries[0]
            .target
            .ends_with("forge-1.8.9-11.15.1.2318-1.8.9.jar"));

        let mut tampered = kept.clone();
        tampered.libraries[0].url = "https://evil.example/forge.jar".into();
        std::fs::write(&file, serde_json::to_vec(&tampered).unwrap()).unwrap();
        assert!(
            ForgeProvider
                .resolve("1.8.9", "11.15.1.2318", &cache, &http, Feature::Play)
                .is_err(),
            "a kept URL is checked against the allowlist like any other"
        );
    }

    #[test]
    fn class_names_are_class_names() {
        assert!(java_class_name("net.minecraft.launchwrapper.Launch"));
        assert!(!java_class_name("--accessToken"));
        assert!(!java_class_name("a..b"));
        assert!(!java_class_name("a.1b"));
        assert!(!java_class_name("a b"));
    }
}
