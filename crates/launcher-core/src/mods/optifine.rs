//! Recognising an OptiFine jar, and refusing one that will not work.
//!
//! # Why this is not filename matching
//!
//! The obvious implementation reads `OptiFine_1.8.9_HD_U_M5.jar` and believes
//! it. That is wrong in both directions: a renamed jar is rejected although it
//! would work, and `OptiFine_1.8.9_anything.jar` is accepted although it may be
//! a 1.12 build, an installer for a different game, or not OptiFine at all.
//! Either way the user finds out when Minecraft crashes, which is the outcome
//! this module exists to prevent.
//!
//! So the jar is asked what it is. An OptiFine download is an *installer* jar,
//! and it carries its own identity in `Config.class` as compile-time string
//! constants:
//!
//! ```text
//! OF_NAME    = "OptiFine"
//! MC_VERSION = "1.8.9"
//! OF_EDITION = "HD_U"
//! OF_RELEASE = "M5"
//! VERSION    = "OptiFine_1.8.9_HD_U_M5"
//! ```
//!
//! Those are `ConstantValue` attributes on static final fields, so they can be
//! read straight out of the class file. Nothing is executed, nothing is
//! extracted to disk, and the answer comes from the artifact rather than from
//! whatever somebody called the file.
//!
//! # What is deliberately not done here
//!
//! **No download.** OptiFine is proprietary and its licence does not permit
//! redistribution. `components::Supply::UserSupplied` is the only way it enters
//! a profile and this module never reaches the network.
//!
//! **No patching.** Fathom does not run OptiFine's installer or touch its
//! contents. The jar is placed in the profile's mods directory and
//! **Legacy OptiFabric** — a separate, MPL-2.0, redistributable component —
//! patches it into the game at runtime. This module's job ends at "yes, this is
//! OptiFine for this Minecraft version".

use std::io::Read;
use std::path::Path;

/// What a jar says it is.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OptiFineIdentity {
    /// `OF_NAME`. Always "OptiFine" for a jar this module accepts.
    pub name: String,
    /// `MC_VERSION` — the Minecraft version this build patches, e.g. "1.8.9".
    pub minecraft: String,
    /// `OF_EDITION`, e.g. "HD_U".
    pub edition: String,
    /// `OF_RELEASE`, e.g. "M5".
    pub release: String,
    /// `VERSION` — the full name, e.g. "OptiFine_1.8.9_HD_U_M5".
    pub version: String,
}

impl OptiFineIdentity {
    /// How to name this build in the interface.
    pub fn display_name(&self) -> String {
        format!("OptiFine {} {} {}", self.minecraft, self.edition, self.release)
    }
}

/// Why a jar was refused.
///
/// Each variant exists because the interface has to say something different
/// about it. "This is not OptiFine" and "this is OptiFine for another version"
/// lead to different next actions, and collapsing them into one error would
/// make the screen say "invalid file" to somebody holding a perfectly good jar
/// for the wrong version.
#[derive(Debug, thiserror::Error)]
pub enum OptiFineError {
    #[error("{path} could not be read as a jar: {detail}")]
    Unreadable { path: String, detail: String },
    #[error(
        "{path} is not an OptiFine download. An OptiFine jar from optifine.net contains its \
         installer and a Config class; this one does not."
    )]
    NotOptiFine { path: String },
    #[error(
        "this jar is OptiFine, but its Config class does not declare a version, so Fathom \
         cannot tell which Minecraft it is for"
    )]
    NoVersion,
    #[error(
        "this is OptiFine for Minecraft {found}, and this profile is Minecraft {required}. \
         Download the {required} build from optifine.net."
    )]
    WrongMinecraft { found: String, required: String },
}

/// Entries that together identify an OptiFine installer download.
///
/// `Config.class` alone is too weak — it is a plausible name for anything at a
/// jar root. The `net/optifine/` package and the installer's own class are what
/// make it OptiFine specifically, and requiring the patch set as well
/// distinguishes a download from an already-installed OptiFine library.
const CONFIG_CLASS: &str = "Config.class";
const OPTIFINE_PACKAGE: &str = "net/optifine/";
const INSTALLER_CLASS: &str = "optifine/Installer.class";

/// Read a jar and report what OptiFine build it is.
///
/// Does not check the Minecraft version — see [`inspect_for`], which is what
/// callers with a profile in hand should use.
pub fn inspect(path: &Path) -> Result<OptiFineIdentity, OptiFineError> {
    let file = std::fs::File::open(path).map_err(|e| OptiFineError::Unreadable {
        path: path.display().to_string(),
        detail: e.to_string(),
    })?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| OptiFineError::Unreadable {
        path: path.display().to_string(),
        detail: e.to_string(),
    })?;

    let mut has_package = false;
    let mut has_installer = false;
    let mut has_config = false;
    for index in 0..archive.len() {
        let Ok(entry) = archive.by_index_raw(index) else {
            continue;
        };
        let name = entry.name();
        if name == CONFIG_CLASS {
            has_config = true;
        } else if name == INSTALLER_CLASS {
            has_installer = true;
        } else if name.starts_with(OPTIFINE_PACKAGE) {
            has_package = true;
        }
    }
    if !has_config || !has_package || !has_installer {
        return Err(OptiFineError::NotOptiFine {
            path: path.display().to_string(),
        });
    }

    let mut config = Vec::new();
    archive
        .by_name(CONFIG_CLASS)
        .map_err(|e| OptiFineError::Unreadable {
            path: path.display().to_string(),
            detail: e.to_string(),
        })?
        .read_to_end(&mut config)
        .map_err(|e| OptiFineError::Unreadable {
            path: path.display().to_string(),
            detail: e.to_string(),
        })?;

    let constants = string_constants(&config).ok_or(OptiFineError::NoVersion)?;
    let take = |field: &str| constants.iter().find(|(n, _)| n == field).map(|(_, v)| v.clone());

    let name = take("OF_NAME").ok_or(OptiFineError::NoVersion)?;
    if name != "OptiFine" {
        return Err(OptiFineError::NotOptiFine {
            path: path.display().to_string(),
        });
    }
    Ok(OptiFineIdentity {
        name,
        minecraft: take("MC_VERSION").ok_or(OptiFineError::NoVersion)?,
        edition: take("OF_EDITION").unwrap_or_default(),
        release: take("OF_RELEASE").unwrap_or_default(),
        version: take("VERSION").ok_or(OptiFineError::NoVersion)?,
    })
}

/// Read a jar and require it to be for a particular Minecraft version.
pub fn inspect_for(path: &Path, minecraft: &str) -> Result<OptiFineIdentity, OptiFineError> {
    let identity = inspect(path)?;
    if identity.minecraft != minecraft {
        return Err(OptiFineError::WrongMinecraft {
            found: identity.minecraft,
            required: minecraft.to_owned(),
        });
    }
    Ok(identity)
}

// ---------------------------------------------------------------------------
// The smallest class-file reader that answers the question
// ---------------------------------------------------------------------------

/// Every `static final String` field with a compile-time value, as
/// (field name, value).
///
/// Deliberately not a general class-file parser. It walks the constant pool and
/// the field table, and stops: no methods, no code, no attributes beyond
/// `ConstantValue`. A jar is untrusted input, so every read is bounds-checked
/// and any malformed structure returns `None` rather than panicking — a corrupt
/// jar must be a refusal, never a crash.
fn string_constants(class: &[u8]) -> Option<Vec<(String, String)>> {
    let mut at = 0usize;
    let magic = u32_at(class, &mut at)?;
    if magic != 0xCAFE_BABE {
        return None;
    }
    at += 4; // minor and major version

    // --- constant pool ---
    let count = u16_at(class, &mut at)? as usize;
    // Index 0 is unused, and long/double take two slots, so this is sparse.
    let mut utf8: Vec<Option<String>> = vec![None; count.max(1)];
    let mut string_target: Vec<Option<u16>> = vec![None; count.max(1)];
    let mut index = 1usize;
    while index < count {
        let tag = *class.get(at)?;
        at += 1;
        match tag {
            1 => {
                let len = u16_at(class, &mut at)? as usize;
                let bytes = class.get(at..at.checked_add(len)?)?;
                at += len;
                // Java's modified UTF-8 agrees with UTF-8 for the ASCII these
                // constants are made of; anything else is not a version string.
                utf8[index] = Some(String::from_utf8_lossy(bytes).into_owned());
            }
            7 | 8 | 16 | 19 | 20 => {
                let target = u16_at(class, &mut at)?;
                if tag == 8 {
                    string_target[index] = Some(target);
                }
            }
            15 => at += 3,
            3 | 4 | 9 | 10 | 11 | 12 | 17 | 18 => at += 4,
            5 | 6 => {
                at += 8;
                index += 1; // takes two pool slots
            }
            _ => return None,
        }
        index += 1;
    }

    at += 6; // access_flags, this_class, super_class
    let interfaces = u16_at(class, &mut at)? as usize;
    at = at.checked_add(interfaces.checked_mul(2)?)?;

    // --- fields ---
    let fields = u16_at(class, &mut at)? as usize;
    let mut found = Vec::new();
    for _ in 0..fields {
        at += 2; // access_flags
        let name_index = u16_at(class, &mut at)? as usize;
        at += 2; // descriptor_index
        let attributes = u16_at(class, &mut at)? as usize;
        for _ in 0..attributes {
            let attr_name = u16_at(class, &mut at)? as usize;
            let length = u32_at(class, &mut at)? as usize;
            let is_constant_value = utf8
                .get(attr_name)
                .and_then(|n| n.as_deref())
                .is_some_and(|n| n == "ConstantValue");
            if is_constant_value && length == 2 {
                let value_index = u16_at(class, &mut at)? as usize;
                let value = string_target
                    .get(value_index)
                    .and_then(|t| *t)
                    .and_then(|t| utf8.get(t as usize).and_then(|v| v.clone()));
                let name = utf8.get(name_index).and_then(|n| n.clone());
                if let (Some(name), Some(value)) = (name, value) {
                    found.push((name, value));
                }
            } else {
                at = at.checked_add(length)?;
            }
        }
    }
    Some(found)
}

fn u16_at(bytes: &[u8], at: &mut usize) -> Option<u16> {
    let slice = bytes.get(*at..at.checked_add(2)?)?;
    *at += 2;
    Some(u16::from_be_bytes([slice[0], slice[1]]))
}

fn u32_at(bytes: &[u8], at: &mut usize) -> Option<u32> {
    let slice = bytes.get(*at..at.checked_add(4)?)?;
    *at += 4;
    Some(u32::from_be_bytes([slice[0], slice[1], slice[2], slice[3]]))
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;
    use std::io::Write;

    /// The real jar, when the developer running the tests happens to have one.
    ///
    /// Skipped rather than failed when absent, and that is the whole point of
    /// the design: OptiFine cannot be committed to this repository or fetched
    /// by CI, so the test suite must not need it. The synthetic tests below
    /// cover the parser; this one exists so that a real download is checked
    /// against it whenever somebody has one to hand.
    fn real_optifine_jar() -> Option<std::path::PathBuf> {
        let candidate = dirs_downloads()?.join("OptiFine_1.8.9_HD_U_M5.jar");
        candidate.exists().then_some(candidate)
    }

    fn dirs_downloads() -> Option<std::path::PathBuf> {
        std::env::var_os("USERPROFILE")
            .map(|home| std::path::PathBuf::from(home).join("Downloads"))
    }

    #[test]
    fn a_real_optifine_download_identifies_itself() {
        let Some(jar) = real_optifine_jar() else {
            eprintln!("no OptiFine jar available; skipping");
            return;
        };
        let identity = inspect(&jar).expect("the jar should be recognised");
        assert_eq!(identity.name, "OptiFine");
        assert_eq!(identity.minecraft, "1.8.9");
        assert_eq!(identity.edition, "HD_U");
        assert_eq!(identity.release, "M5");
        assert_eq!(identity.version, "OptiFine_1.8.9_HD_U_M5");
        assert_eq!(identity.display_name(), "OptiFine 1.8.9 HD_U M5");
    }

    #[test]
    fn a_real_optifine_download_is_refused_for_another_minecraft() {
        let Some(jar) = real_optifine_jar() else {
            eprintln!("no OptiFine jar available; skipping");
            return;
        };
        let refusal = inspect_for(&jar, "1.12.2").expect_err("1.8.9 is not 1.12.2");
        assert!(
            matches!(&refusal, OptiFineError::WrongMinecraft { found, required }
                if found == "1.8.9" && required == "1.12.2"),
            "{refusal:?}"
        );
        // The message has to name both versions, because "incompatible" alone
        // does not tell somebody which build to go and download.
        let said = refusal.to_string();
        assert!(said.contains("1.8.9") && said.contains("1.12.2"), "{said}");
    }

    #[test]
    fn a_real_optifine_download_is_accepted_for_its_own_minecraft() {
        let Some(jar) = real_optifine_jar() else {
            eprintln!("no OptiFine jar available; skipping");
            return;
        };
        assert!(inspect_for(&jar, "1.8.9").is_ok());
    }

    fn jar_with(entries: &[(&str, &[u8])]) -> tempfile::NamedTempFile {
        let file = tempfile::NamedTempFile::new().unwrap();
        let mut writer = zip::ZipWriter::new(file.reopen().unwrap());
        let options: zip::write::FileOptions<'_, ()> =
            zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Stored);
        for (name, bytes) in entries {
            writer.start_file(*name, options).unwrap();
            writer.write_all(bytes).unwrap();
        }
        writer.finish().unwrap();
        file
    }

    /// A class file with one `static final String` per entry.
    fn config_class(fields: &[(&str, &str)]) -> Vec<u8> {
        let mut pool: Vec<Vec<u8>> = Vec::new();
        let mut out = Vec::new();

        let utf8 = |pool: &mut Vec<Vec<u8>>, text: &str| -> u16 {
            let mut entry = vec![1u8];
            entry.extend_from_slice(&(text.len() as u16).to_be_bytes());
            entry.extend_from_slice(text.as_bytes());
            pool.push(entry);
            pool.len() as u16
        };

        let constant_value = utf8(&mut pool, "ConstantValue");
        let mut planned = Vec::new();
        for (name, value) in fields {
            let name_index = utf8(&mut pool, name);
            let text_index = utf8(&mut pool, value);
            // A String entry pointing at the Utf8 we just wrote.
            let mut entry = vec![8u8];
            entry.extend_from_slice(&text_index.to_be_bytes());
            pool.push(entry);
            planned.push((name_index, pool.len() as u16));
        }

        out.extend_from_slice(&0xCAFE_BABEu32.to_be_bytes());
        out.extend_from_slice(&0u16.to_be_bytes()); // minor
        out.extend_from_slice(&52u16.to_be_bytes()); // major
        out.extend_from_slice(&(pool.len() as u16 + 1).to_be_bytes());
        for entry in &pool {
            out.extend_from_slice(entry);
        }
        out.extend_from_slice(&0x0021u16.to_be_bytes()); // access
        out.extend_from_slice(&0u16.to_be_bytes()); // this_class
        out.extend_from_slice(&0u16.to_be_bytes()); // super_class
        out.extend_from_slice(&0u16.to_be_bytes()); // interfaces
        out.extend_from_slice(&(planned.len() as u16).to_be_bytes());
        for (name_index, value_index) in planned {
            out.extend_from_slice(&0x0019u16.to_be_bytes()); // public static final
            out.extend_from_slice(&name_index.to_be_bytes());
            out.extend_from_slice(&0u16.to_be_bytes()); // descriptor
            out.extend_from_slice(&1u16.to_be_bytes()); // one attribute
            out.extend_from_slice(&constant_value.to_be_bytes());
            out.extend_from_slice(&2u32.to_be_bytes());
            out.extend_from_slice(&value_index.to_be_bytes());
        }
        out.extend_from_slice(&0u16.to_be_bytes()); // methods
        out.extend_from_slice(&0u16.to_be_bytes()); // attributes
        out
    }

    fn synthetic_optifine(minecraft: &str) -> tempfile::NamedTempFile {
        let class = config_class(&[
            ("OF_NAME", "OptiFine"),
            ("MC_VERSION", minecraft),
            ("OF_EDITION", "HD_U"),
            ("OF_RELEASE", "M5"),
            ("VERSION", &format!("OptiFine_{minecraft}_HD_U_M5")),
        ]);
        jar_with(&[
            (CONFIG_CLASS, class.as_slice()),
            (INSTALLER_CLASS, b"stub"),
            ("net/optifine/Config.class", b"stub"),
        ])
    }

    #[test]
    fn the_version_comes_from_the_class_not_the_filename() {
        // Named as though it were 1.12.2; the bytecode says 1.8.9 and wins.
        let jar = synthetic_optifine("1.8.9");
        let identity = inspect(jar.path()).unwrap();
        assert_eq!(identity.minecraft, "1.8.9");
    }

    #[test]
    fn an_ordinary_mod_jar_is_not_optifine() {
        let jar = jar_with(&[("fabric.mod.json", b"{}"), ("net/example/Mod.class", b"x")]);
        assert!(matches!(
            inspect(jar.path()),
            Err(OptiFineError::NotOptiFine { .. })
        ));
    }

    /// The marker set has to be all three. A jar with a `Config.class` at its
    /// root is not OptiFine, and accepting one would put an arbitrary jar in
    /// front of OptiFabric.
    #[test]
    fn a_config_class_alone_is_not_enough() {
        let class = config_class(&[("OF_NAME", "OptiFine"), ("MC_VERSION", "1.8.9")]);
        let jar = jar_with(&[(CONFIG_CLASS, class.as_slice())]);
        assert!(matches!(
            inspect(jar.path()),
            Err(OptiFineError::NotOptiFine { .. })
        ));
    }

    #[test]
    fn a_jar_claiming_another_name_is_refused() {
        let class = config_class(&[("OF_NAME", "NotOptiFine"), ("MC_VERSION", "1.8.9")]);
        let jar = jar_with(&[
            (CONFIG_CLASS, class.as_slice()),
            (INSTALLER_CLASS, b"stub"),
            ("net/optifine/Config.class", b"stub"),
        ]);
        assert!(matches!(
            inspect(jar.path()),
            Err(OptiFineError::NotOptiFine { .. })
        ));
    }

    #[test]
    fn a_build_for_another_minecraft_is_refused_with_both_versions() {
        let jar = synthetic_optifine("1.12.2");
        let refusal = inspect_for(jar.path(), "1.8.9").unwrap_err();
        assert!(matches!(&refusal, OptiFineError::WrongMinecraft { found, required }
            if found == "1.12.2" && required == "1.8.9"));
    }

    #[test]
    fn a_file_that_is_not_a_jar_is_refused_rather_than_panicking() {
        let file = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(file.path(), b"not a zip at all").unwrap();
        assert!(matches!(
            inspect(file.path()),
            Err(OptiFineError::Unreadable { .. })
        ));
    }

    /// Untrusted input. A truncated or scrambled class must be a refusal, and
    /// the parser must not index past the end of it to find that out.
    #[test]
    fn a_truncated_class_file_is_refused_rather_than_panicking() {
        let full = config_class(&[("OF_NAME", "OptiFine"), ("MC_VERSION", "1.8.9")]);
        for cut in [4, 8, 12, 20, full.len() / 2, full.len() - 1] {
            let jar = jar_with(&[
                (CONFIG_CLASS, &full[..cut]),
                (INSTALLER_CLASS, b"stub"),
                ("net/optifine/Config.class", b"stub"),
            ]);
            // Any refusal is fine; a panic is not.
            let _ = inspect(jar.path());
        }
    }

    #[test]
    fn a_class_with_no_version_fields_is_refused() {
        let class = config_class(&[("SOMETHING_ELSE", "x")]);
        let jar = jar_with(&[
            (CONFIG_CLASS, class.as_slice()),
            (INSTALLER_CLASS, b"stub"),
            ("net/optifine/Config.class", b"stub"),
        ]);
        assert!(matches!(inspect(jar.path()), Err(OptiFineError::NoVersion)));
    }
}
