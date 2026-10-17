//! Mojang's version metadata, as it actually looks for 1.8.9.
//!
//! 1.8.9 is a **legacy-format** version: it carries `minecraftArguments` as a
//! single string, not the 1.13+ `arguments` object. Both shapes are modelled so
//! that adding a newer version later does not mean rewriting this, but only the
//! legacy path is exercised today.
//!
//! Everything here is untrusted input. Fields are optional wherever Mojang
//! might omit them, and nothing panics on a surprising document.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// Entry in `version_manifest_v2.json`.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ManifestEntry {
    pub id: String,
    #[serde(rename = "type")]
    pub release_type: String,
    pub url: String,
    pub sha1: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct VersionManifest {
    pub latest: BTreeMap<String, String>,
    pub versions: Vec<ManifestEntry>,
}

impl VersionManifest {
    pub fn find(&self, id: &str) -> Option<&ManifestEntry> {
        self.versions.iter().find(|v| v.id == id)
    }
}

/// A downloadable file with a publisher-supplied hash.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct Download {
    pub sha1: String,
    pub size: u64,
    pub url: String,
    /// Present on library artifacts; the path under the libraries root.
    #[serde(default)]
    pub path: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AssetIndexRef {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    pub url: String,
    #[serde(rename = "totalSize", default)]
    pub total_size: Option<u64>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
pub struct LibraryDownloads {
    #[serde(default)]
    pub artifact: Option<Download>,
    /// Natives live here, keyed by the value from the `natives` map.
    #[serde(default)]
    pub classifiers: BTreeMap<String, Download>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
pub struct ExtractRules {
    #[serde(default)]
    pub exclude: Vec<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Library {
    pub name: String,
    #[serde(default)]
    pub downloads: LibraryDownloads,
    #[serde(default)]
    pub rules: Vec<Rule>,
    /// Maps an OS name to a classifier key, e.g. `windows` -> `natives-windows`.
    #[serde(default)]
    pub natives: BTreeMap<String, String>,
    #[serde(default)]
    pub extract: Option<ExtractRules>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Rule {
    pub action: String,
    #[serde(default)]
    pub os: Option<OsRule>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct OsRule {
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub arch: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct JavaVersionRef {
    #[serde(default)]
    pub component: Option<String>,
    #[serde(rename = "majorVersion")]
    pub major_version: u32,
}

/// The log4j2 configuration a version asks the launcher to pass.
///
/// 1.8.9 declares one (`client-1.7.xml`). Without it the game falls back to a
/// built-in config and the launcher cannot pass `-Dlog4j.configurationFile`.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct LoggingSection {
    #[serde(default)]
    pub client: Option<ClientLogging>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ClientLogging {
    /// e.g. `-Dlog4j.configurationFile=${path}`
    pub argument: String,
    pub file: LoggingFile,
    #[serde(rename = "type", default)]
    pub kind: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct LoggingFile {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
pub struct VersionDownloads {
    #[serde(default)]
    pub client: Option<Download>,
}

/// A resolved version document.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct VersionDetail {
    pub id: String,
    #[serde(rename = "mainClass")]
    pub main_class: String,
    #[serde(default)]
    pub assets: Option<String>,
    #[serde(rename = "assetIndex", default)]
    pub asset_index: Option<AssetIndexRef>,
    #[serde(default)]
    pub downloads: VersionDownloads,
    #[serde(default)]
    pub libraries: Vec<Library>,
    /// Legacy (pre-1.13) argument string. This is what 1.8.9 uses.
    #[serde(rename = "minecraftArguments", default)]
    pub minecraft_arguments: Option<String>,
    #[serde(rename = "javaVersion", default)]
    pub java_version: Option<JavaVersionRef>,
    /// log4j2 configuration. Present for 1.8.9.
    #[serde(default)]
    pub logging: Option<LoggingSection>,
}

impl VersionDetail {
    /// The Java major version this build declares. 1.8.9 declares 8.
    pub fn required_java_major(&self) -> u32 {
        self.java_version.as_ref().map_or(8, |j| j.major_version)
    }

    /// Libraries that apply on this platform, natives excluded.
    pub fn classpath_libraries(&self, os: Os) -> Vec<&Library> {
        self.libraries
            .iter()
            .filter(|l| l.applies_to(os) && l.natives.is_empty())
            .collect()
    }

    /// Libraries that contribute a natives jar on this platform.
    pub fn native_libraries(&self, os: Os) -> Vec<(&Library, &Download)> {
        self.libraries
            .iter()
            .filter(|l| l.applies_to(os))
            .filter_map(|l| l.native_download(os).map(|d| (l, d)))
            .collect()
    }
}

impl Library {
    /// Evaluate the rule chain. Mojang's semantics: with no rules, allow. With
    /// rules, the last matching rule wins, starting from disallowed.
    pub fn applies_to(&self, os: Os) -> bool {
        if self.rules.is_empty() {
            return true;
        }
        let mut allowed = false;
        for rule in &self.rules {
            if !rule.matches(os) {
                continue;
            }
            allowed = rule.action == "allow";
        }
        allowed
    }

    /// The natives artifact for this OS, if any.
    pub fn native_download(&self, os: Os) -> Option<&Download> {
        let key = self.natives.get(os.mojang_name())?;
        // Mojang uses `${arch}` in some classifier keys (`natives-windows-${arch}`).
        let resolved = key.replace("${arch}", os.arch_bits());
        self.downloads.classifiers.get(&resolved)
    }

    /// Whether a file inside a natives jar should be extracted.
    ///
    /// The version JSON's `extract.exclude` list is honoured, and on top of it
    /// we apply our own allowlist in [`crate::fs_safety`] at extraction time:
    /// a natives jar may only yield shared libraries.
    pub fn should_extract(&self, entry_name: &str) -> bool {
        let excluded = self
            .extract
            .as_ref()
            .is_some_and(|e| e.exclude.iter().any(|p| entry_name.starts_with(p)));
        !excluded && is_native_library_name(entry_name)
    }
}

/// Only shared libraries come out of a natives jar. This is ours, not Mojang's:
/// it stops a tampered natives jar from dropping a `.bat`, a `.jar`, or a
/// `.cmd` next to the `.dll`.
// `lower` is already lowercased, so these suffix checks are case-insensitive;
// clippy cannot see that through the binding.
#[allow(clippy::case_sensitive_file_extension_comparisons)]
pub fn is_native_library_name(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower.ends_with(".dll") || lower.ends_with(".so") || lower.ends_with(".dylib")
        || lower.ends_with(".jnilib")
        // Versioned unix sonames such as `libfoo.so.1`.
        || lower.contains(".so.")
}

impl Rule {
    fn matches(&self, os: Os) -> bool {
        let Some(os_rule) = &self.os else {
            return true; // No OS constraint: applies everywhere.
        };
        if let Some(name) = &os_rule.name {
            if name != os.mojang_name() {
                return false;
            }
        }
        if let Some(arch) = &os_rule.arch {
            if arch != os.mojang_arch() {
                return false;
            }
        }
        true
    }
}

/// The platforms Mojang's metadata distinguishes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Os {
    pub family: OsFamily,
    pub is_64bit: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OsFamily {
    Windows,
    MacOs,
    Linux,
}

impl Os {
    pub fn current() -> Self {
        let family = if cfg!(target_os = "windows") {
            OsFamily::Windows
        } else if cfg!(target_os = "macos") {
            OsFamily::MacOs
        } else {
            OsFamily::Linux
        };
        Self {
            family,
            is_64bit: cfg!(target_pointer_width = "64"),
        }
    }

    pub fn mojang_name(self) -> &'static str {
        match self.family {
            OsFamily::Windows => "windows",
            OsFamily::MacOs => "osx",
            OsFamily::Linux => "linux",
        }
    }

    pub fn mojang_arch(self) -> &'static str {
        if self.is_64bit {
            "x64"
        } else {
            "x86"
        }
    }

    pub fn arch_bits(self) -> &'static str {
        if self.is_64bit {
            "64"
        } else {
            "32"
        }
    }

    /// A stable name for this platform, used where a platform has to be
    /// recorded on disk rather than only compared.
    pub fn label(self) -> String {
        format!(
            "{}-{}",
            match self.family {
                OsFamily::Windows => "windows",
                OsFamily::Linux => "linux",
                OsFamily::MacOs => "osx",
            },
            if self.is_64bit { "x64" } else { "x86" }
        )
    }

    /// Separator between classpath entries in the JVM's `-cp`.
    pub fn classpath_separator(self) -> &'static str {
        match self.family {
            OsFamily::Windows => ";",
            _ => ":",
        }
    }
}

/// The asset index document: logical name -> object.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AssetIndex {
    pub objects: BTreeMap<String, AssetObject>,
    /// 1.8.9's `1.8` index is not `virtual`, but older ones are; carrying the
    /// flag means we notice rather than silently mislaying files.
    #[serde(default)]
    pub virtual_assets: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct AssetObject {
    pub hash: String,
    pub size: u64,
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

    const WINDOWS_64: Os = Os {
        family: OsFamily::Windows,
        is_64bit: true,
    };
    const MACOS_64: Os = Os {
        family: OsFamily::MacOs,
        is_64bit: true,
    };
    const LINUX_64: Os = Os {
        family: OsFamily::Linux,
        is_64bit: true,
    };

    /// A trimmed but structurally faithful excerpt of the real 1.8.9 document.
    const SAMPLE: &str = r#"{
      "id": "1.8.9",
      "mainClass": "net.minecraft.client.main.Main",
      "assets": "1.8",
      "assetIndex": {
        "id": "1.8",
        "sha1": "f6ad102bcaa5a6ba0c1f2a9b41d4b1f3d6d8f8aa",
        "size": 145623,
        "totalSize": 132478,
        "url": "https://launchermeta.mojang.com/v1/packages/x/1.8.json"
      },
      "downloads": {
        "client": {
          "sha1": "b58b2ceb36e01bcd8dbf49c8fb66c55a9f0676cd",
          "size": 8461484,
          "url": "https://launcher.mojang.com/v1/objects/x/client.jar"
        }
      },
      "javaVersion": { "component": "jre-legacy", "majorVersion": 8 },
      "minecraftArguments": "--username ${auth_player_name} --version ${version_name}",
      "libraries": [
        {
          "name": "com.google.guava:guava:17.0",
          "downloads": {
            "artifact": {
              "sha1": "aaa",
              "size": 10,
              "path": "com/google/guava/guava/17.0/guava-17.0.jar",
              "url": "https://libraries.minecraft.net/com/google/guava/guava/17.0/guava-17.0.jar"
            }
          }
        },
        {
          "name": "org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209",
          "downloads": {
            "classifiers": {
              "natives-windows": {
                "sha1": "bbb", "size": 20,
                "path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-windows.jar",
                "url": "https://libraries.minecraft.net/x-windows.jar"
              },
              "natives-linux": {
                "sha1": "ccc", "size": 21,
                "path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-natives-linux.jar",
                "url": "https://libraries.minecraft.net/x-linux.jar"
              }
            }
          },
          "natives": { "windows": "natives-windows", "linux": "natives-linux", "osx": "natives-osx" },
          "extract": { "exclude": ["META-INF/"] }
        },
        {
          "name": "osx.only:lib:1.0",
          "downloads": { "artifact": { "sha1": "ddd", "size": 30, "path": "osx/only.jar", "url": "https://libraries.minecraft.net/osx.jar" } },
          "rules": [
            { "action": "allow", "os": { "name": "osx" } }
          ]
        },
        {
          "name": "not.on.osx:lib:1.0",
          "downloads": { "artifact": { "sha1": "eee", "size": 40, "path": "notosx/lib.jar", "url": "https://libraries.minecraft.net/notosx.jar" } },
          "rules": [
            { "action": "allow" },
            { "action": "disallow", "os": { "name": "osx" } }
          ]
        }
      ]
    }"#;

    fn sample() -> VersionDetail {
        serde_json::from_str(SAMPLE).expect("sample version JSON should parse")
    }

    #[test]
    fn parses_the_legacy_1_8_9_shape() {
        let v = sample();
        assert_eq!(v.id, "1.8.9");
        assert_eq!(v.main_class, "net.minecraft.client.main.Main");
        assert_eq!(v.assets.as_deref(), Some("1.8"));
        assert_eq!(v.required_java_major(), 8);
        assert!(
            v.minecraft_arguments.is_some(),
            "1.8.9 must use the legacy argument string"
        );
        assert_eq!(v.downloads.client.as_ref().unwrap().size, 8_461_484);
    }

    #[test]
    fn rules_with_no_entries_allow_everywhere() {
        let v = sample();
        let names: Vec<&str> = v
            .classpath_libraries(WINDOWS_64)
            .iter()
            .map(|l| l.name.as_str())
            .collect();
        assert!(names.contains(&"com.google.guava:guava:17.0"));
    }

    #[test]
    fn allow_only_on_one_os_excludes_the_others() {
        let v = sample();
        let on_mac: Vec<&str> = v
            .classpath_libraries(MACOS_64)
            .iter()
            .map(|l| l.name.as_str())
            .collect();
        let on_win: Vec<&str> = v
            .classpath_libraries(WINDOWS_64)
            .iter()
            .map(|l| l.name.as_str())
            .collect();
        assert!(on_mac.contains(&"osx.only:lib:1.0"));
        assert!(!on_win.contains(&"osx.only:lib:1.0"));
    }

    #[test]
    fn last_matching_rule_wins() {
        let v = sample();
        // allow, then disallow on osx: excluded on mac, present elsewhere.
        let on_mac: Vec<&str> = v
            .classpath_libraries(MACOS_64)
            .iter()
            .map(|l| l.name.as_str())
            .collect();
        let on_linux: Vec<&str> = v
            .classpath_libraries(LINUX_64)
            .iter()
            .map(|l| l.name.as_str())
            .collect();
        assert!(!on_mac.contains(&"not.on.osx:lib:1.0"));
        assert!(on_linux.contains(&"not.on.osx:lib:1.0"));
    }

    #[test]
    fn natives_are_selected_per_platform_and_kept_off_the_classpath() {
        let v = sample();

        let win_natives = v.native_libraries(WINDOWS_64);
        assert_eq!(win_natives.len(), 1);
        assert_eq!(win_natives[0].1.sha1, "bbb");

        let linux_natives = v.native_libraries(LINUX_64);
        assert_eq!(linux_natives[0].1.sha1, "ccc");

        // The osx classifier is declared in `natives` but absent from
        // `classifiers`, so there is nothing to download.
        assert!(v.native_libraries(MACOS_64).is_empty());

        // A natives library must never appear on the classpath.
        let cp: Vec<&str> = v
            .classpath_libraries(WINDOWS_64)
            .iter()
            .map(|l| l.name.as_str())
            .collect();
        assert!(!cp.iter().any(|n| n.contains("lwjgl-platform")));
    }

    #[test]
    fn extraction_honours_exclude_and_our_own_allowlist() {
        let v = sample();
        let natives = v
            .libraries
            .iter()
            .find(|l| l.name.contains("lwjgl-platform"))
            .expect("natives library");

        assert!(natives.should_extract("lwjgl64.dll"));
        assert!(natives.should_extract("libopenal.so"));
        assert!(natives.should_extract("liblwjgl.dylib"));

        // Excluded by the version JSON.
        assert!(!natives.should_extract("META-INF/MANIFEST.MF"));
        // Excluded by us: a natives jar has no business shipping these.
        assert!(!natives.should_extract("evil.bat"));
        assert!(!natives.should_extract("payload.jar"));
        assert!(!natives.should_extract("run.cmd"));
        assert!(!natives.should_extract("readme.txt"));
    }

    #[test]
    fn classpath_separator_is_platform_correct() {
        assert_eq!(WINDOWS_64.classpath_separator(), ";");
        assert_eq!(LINUX_64.classpath_separator(), ":");
        assert_eq!(MACOS_64.classpath_separator(), ":");
    }

    #[test]
    fn the_logging_section_parses() {
        // Verbatim from the real 1.8.9 document. Missing this field entirely is
        // how a launcher ends up not passing -Dlog4j.configurationFile.
        let json = r#"{
            "id": "1.8.9",
            "mainClass": "net.minecraft.client.main.Main",
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
            }
        }"#;
        let v: VersionDetail = serde_json::from_str(json).unwrap();
        let client = v.logging.unwrap().client.unwrap();
        assert_eq!(client.argument, "-Dlog4j.configurationFile=${path}");
        assert_eq!(client.file.id, "client-1.7.xml");
        assert_eq!(client.file.size, 966);
        assert_eq!(client.kind.as_deref(), Some("log4j2-xml"));
    }

    #[test]
    fn a_version_without_logging_still_parses() {
        let v: VersionDetail =
            serde_json::from_str(r#"{"id":"1.8.9","mainClass":"net.minecraft.client.main.Main"}"#)
                .unwrap();
        assert!(v.logging.is_none());
    }

    #[test]
    fn unknown_fields_do_not_break_parsing() {
        // Mojang adds fields over time; we must tolerate that rather than
        // failing to launch after an unrelated metadata change.
        let json = r#"{
            "id": "1.8.9",
            "mainClass": "net.minecraft.client.main.Main",
            "somethingNew": { "nested": true },
            "complianceLevel": 0
        }"#;
        let v: VersionDetail = serde_json::from_str(json).unwrap();
        assert_eq!(v.id, "1.8.9");
        assert!(v.libraries.is_empty());
    }
}
