//! Working out what a jar somebody just handed us actually is.
//!
//! # Why this reads the filename and not the jar
//!
//! Because the jar is an archive supplied by whoever is running the launcher,
//! and `mods/mod.rs` already states the rule: we do not parse attacker-
//! controlled archives to have them describe themselves. A filename is a
//! string; a zip is a parser.
//!
//! So this is a small, deliberately conservative reader of naming conventions.
//! It answers "probably `OptiFine` HD U M5 for 1.8.9" or it answers nothing, and
//! nothing is a perfectly good answer -- the file is still imported, still
//! hashed, still recorded. Identification improves the label; it is never a
//! gate.
//!
//! # Why `OptiFine` specifically
//!
//! It is the one mod the launcher can never fetch and the one users will import
//! most. `mod-policies.md` §2: proprietary, all rights reserved, redistribution
//! prohibited. The launcher's whole contribution is making the manual path
//! pleasant -- which starts with recognising the file and saying so, instead of
//! showing `optifine_1_8_9_hd_u_m5` and leaving the user to wonder whether it
//! worked.

/// What a filename suggests.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Identified {
    /// Catalogue id, when the file is one we know about.
    pub catalogue_id: Option<&'static str>,
    /// Display name, if better than the filename.
    pub name: Option<String>,
    /// The mod's own version, e.g. `HD_U_M5`.
    pub version: Option<String>,
    /// The Minecraft version it is built for.
    pub minecraft: Option<String>,
}

impl Identified {
    pub fn is_empty(&self) -> bool {
        self == &Identified::default()
    }
}

/// Read what a filename is willing to say.
pub fn from_file_name(file_name: &str) -> Identified {
    let stem = file_name
        .rsplit_once('.')
        .map_or(file_name, |(stem, _)| stem);

    if let Some(optifine) = optifine(stem) {
        return optifine;
    }
    Identified::default()
}

/// `OptiFine_1.8.9_HD_U_M5`, and the preview and standalone spellings of it.
///
/// The published names are `OptiFine_<mc>_HD_<U|L>_<build>` with an optional
/// `preview_` prefix and an optional `_pre<n>` suffix, plus the older
/// `OptiFine_<mc>_HD_U_<build>` used by the 1.8.9 line. Anything that does not
/// match is not claimed.
fn optifine(stem: &str) -> Option<Identified> {
    let lower = stem.to_ascii_lowercase();
    let body = lower.strip_prefix("preview_").unwrap_or(&lower);
    if !body.starts_with("optifine") {
        return None;
    }

    // Split on both separators the naming has used over the years.
    let parts: Vec<&str> = body
        .split(['_', '-'])
        .filter(|part| !part.is_empty())
        .collect();
    // `optifine`, a Minecraft version, and at least one build token.
    if parts.len() < 3 {
        return None;
    }

    let minecraft = parts
        .get(1)
        .filter(|candidate| looks_like_a_minecraft_version(candidate))
        .map(|candidate| (*candidate).to_owned());

    // Everything after the Minecraft version is the build, upper-cased back to
    // the form OptiFine publishes.
    let version = if minecraft.is_some() && parts.len() > 2 {
        Some(
            parts
                .get(2..)
                .unwrap_or_default()
                .join("_")
                .to_ascii_uppercase(),
        )
    } else {
        None
    };

    Some(Identified {
        catalogue_id: Some("optifine"),
        name: Some("OptiFine".to_owned()),
        version,
        minecraft,
    })
}

/// `1.8.9`, `1.8`, `1.21.4` -- digits and dots, starting with a digit.
///
/// Deliberately not a version parser. It only has to be good enough to tell a
/// version token apart from `hd`, and being wrong costs a slightly worse label.
fn looks_like_a_minecraft_version(candidate: &str) -> bool {
    candidate.starts_with(|c: char| c.is_ascii_digit())
        && candidate.chars().all(|c| c.is_ascii_digit() || c == '.')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_published_optifine_names_are_recognised() {
        let cases = [
            "OptiFine_1.8.9_HD_U_M5.jar",
            "preview_OptiFine_1.8.9_HD_U_M6_pre1.jar",
            "optifine_1.8.9_hd_u_l5.jar",
        ];
        for name in cases {
            let found = from_file_name(name);
            assert_eq!(found.catalogue_id, Some("optifine"), "{name}");
            assert_eq!(found.minecraft.as_deref(), Some("1.8.9"), "{name}");
            assert!(found.version.is_some(), "{name}");
        }
    }

    #[test]
    fn the_build_is_reported_the_way_optifine_writes_it() {
        let found = from_file_name("OptiFine_1.8.9_HD_U_M5.jar");
        assert_eq!(found.version.as_deref(), Some("HD_U_M5"));
        assert_eq!(found.name.as_deref(), Some("OptiFine"));
    }

    /// A preview build is still `OptiFine`, and its suffix belongs in the
    /// version rather than being dropped -- "M6" and "M6 pre1" are different
    /// files and a user with both needs to tell them apart.
    #[test]
    fn a_preview_keeps_its_suffix() {
        let found = from_file_name("preview_OptiFine_1.8.9_HD_U_M6_pre1.jar");
        assert_eq!(found.version.as_deref(), Some("HD_U_M6_PRE1"));
    }

    /// Nothing is a fine answer. Guessing wrong is worse than not guessing:
    /// a mod mislabelled as `OptiFine` inherits its conflict rules.
    #[test]
    fn anything_else_is_not_claimed() {
        for name in [
            "sodium-fabric-0.5.jar",
            "fathom-optimizations-0.2.0.jar",
            "optifabric-1.13.0.jar",
            "notes.txt",
            "",
        ] {
            let found = from_file_name(name);
            assert!(
                found.catalogue_id != Some("optifine"),
                "{name} was wrongly claimed as OptiFine"
            );
        }
    }

    /// `optifabric` starts with neither `optifine_` nor `optifine-`, so the
    /// prefix check has to be on the token rather than the string.
    #[test]
    fn a_name_that_merely_starts_similarly_is_not_optifine() {
        assert_eq!(from_file_name("optifabric-1.13.0.jar").catalogue_id, None);
    }

    #[test]
    fn a_name_with_no_version_token_still_identifies_the_mod() {
        let found = from_file_name("OptiFine_HD_U_M5.jar");
        assert_eq!(found.catalogue_id, Some("optifine"));
        // `HD` is not a Minecraft version, so neither field is invented.
        assert_eq!(found.minecraft, None);
        assert_eq!(found.version, None);
    }

    #[test]
    fn version_tokens_are_told_apart_from_build_tokens() {
        assert!(looks_like_a_minecraft_version("1.8.9"));
        assert!(looks_like_a_minecraft_version("1.21"));
        assert!(!looks_like_a_minecraft_version("hd"));
        assert!(!looks_like_a_minecraft_version("u"));
        assert!(!looks_like_a_minecraft_version(""));
    }
}
