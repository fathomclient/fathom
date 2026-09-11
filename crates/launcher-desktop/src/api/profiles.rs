//! Profiles, as the interface sees them.
//!
//! The frontend gets what a person needs to choose between setups: a name, a
//! version, a loader, a preset, a mod count, health, and when it was last
//! touched. It does not get the profile id, the game directory, or anything
//! else that is a filename rather than a fact about the setup — those are the
//! model showing through, and showing them is what made the old page read as a
//! form over a database.

use serde::Serialize;

use launcher_core::profiles::{Profile, ProfileId};
use launcher_core::Launcher;

/// One row in the profile browser.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileRow {
    /// Opaque to the frontend: it round-trips this back to name a profile, and
    /// never displays it.
    pub id: String,
    pub name: String,
    pub version: String,
    pub loader: String,
    /// Whether this loader can actually launch. Vanilla can; the others are
    /// catalogued and not implemented.
    pub loader_available: bool,
    pub preset: String,
    pub mod_count: usize,
    pub memory_mb: u32,
    /// `vanilla` / `fathom` / `custom`. What the profile was set up as, which
    /// is not the same question as which loader it ended up with.
    pub kind: &'static str,
    pub kind_label: &'static str,
    /// The name of the profile this one derives from, when it derives from one.
    /// A name and not an id: the interface never shows an internal id.
    pub parent: Option<String>,
    pub selected: bool,
    /// `healthy` / `attention` / `unknown`. A tone, not a sentence.
    pub health: &'static str,
    /// "3 days ago", or "Never" — already humanised, because formatting a
    /// duration is presentation and the frontend should not own two of them.
    pub last_played: String,
}

fn health_of(profile: &Profile) -> &'static str {
    if !profile.loader_kind().is_implemented() {
        return "attention";
    }
    "healthy"
}

/// How long ago, in words.
///
/// Deliberately coarse. Nobody needs "2 hours and 14 minutes ago" to pick which
/// setup to launch, and the precision would only make the row noisier.
fn humanise(stamp: u64) -> String {
    if stamp == 0 {
        return "Never".to_owned();
    }
    let now = launcher_core::profiles::model::now();
    let elapsed = now.saturating_sub(stamp);
    match elapsed {
        0..=120 => "Just now".to_owned(),
        121..=5_400 => format!("{} min ago", elapsed / 60),
        5_401..=172_800 => format!("{} hr ago", elapsed / 3_600),
        _ => format!("{} days ago", elapsed / 86_400),
    }
}

pub fn list(core: &Launcher) -> Vec<ProfileRow> {
    let selected = core.settings().selected_profile.clone();
    let mut profiles = core.profiles();
    // Built once rather than looked up per row: a derived profile shows its
    // parent by name, and asking the store for each one would be a directory
    // read per row.
    let parent_names: std::collections::BTreeMap<ProfileId, String> = profiles
        .iter()
        .map(|profile| (profile.id.clone(), profile.display_name.clone()))
        .collect();
    // Most recently touched first: the one you want is nearly always the one
    // you used last.
    profiles.sort_by_key(|profile| std::cmp::Reverse(profile.updated_at));

    profiles
        .into_iter()
        .map(|profile| {
            // A derived profile's row describes what it will actually launch
            // with, not what its own file happens to store. Without this, a
            // variant of a Fathom profile reported "0 mods" and "Custom" while
            // it was going to start with its parent's module and preset -- the
            // row and the launch disagreeing about the same profile.
            //
            // Resolved only for profiles that have a parent, so the common case
            // costs nothing.
            let effective = if profile.is_derived() {
                core.resolve_profile(&profile).unwrap_or_else(|_| profile.clone())
            } else {
                profile.clone()
            };
            (profile, effective)
        })
        .map(|(profile, effective)| ProfileRow {
            selected: selected.as_ref() == Some(&profile.id),
            id: profile.id.as_str().to_owned(),
            name: profile.display_name.clone(),
            version: effective.version_id.clone(),
            loader: effective.loader_kind().label().to_owned(),
            loader_available: effective.loader_kind().is_implemented(),
            preset: effective.optimizations.preset.label().to_owned(),
            mod_count: effective.mods.enabled().count(),
            memory_mb: effective.memory.heap_mb,
            kind: profile.kind.id(),
            kind_label: profile.kind.label(),
            parent: profile
                .parent
                .as_ref()
                .and_then(|id| parent_names.get(id).cloned()),
            health: health_of(&effective),
            last_played: humanise(profile.updated_at),
        })
        .collect()
}

/// Everything the edit drawer needs, which is more than the row shows.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileDetail {
    pub id: String,
    pub name: String,
    pub version: String,
    pub loader: String,
    pub memory_mb: u32,
    pub extra_args: String,
    pub keep_launcher_open: bool,
    pub kind: &'static str,
    pub kind_label: &'static str,
    /// The Java policy id this profile uses.
    pub java: &'static str,
    pub java_label: &'static str,
    /// Set only for a custom policy.
    pub java_path: Option<String>,
    pub preset: String,
    /// Everything the profile has, technical and cosmetic, as rows the page can
    /// render without knowing what any of them mean.
    pub identity: Vec<IdentityRow>,
    /// Components this profile has, with their state.
    pub components: Vec<ComponentState>,
    pub servers: Vec<ServerRow>,
    /// The name of the parent, when derived.
    pub parent: Option<String>,
    /// Which fields a derived profile may not change, because it inherits them.
    pub inherited: Vec<&'static str>,
    /// Every loader, so the drawer can offer them and mark the unavailable
    /// ones rather than hiding them.
    pub loaders: Vec<LoaderOption>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LoaderOption {
    pub id: String,
    pub label: String,
    pub available: bool,
}


/// One line of a profile's identity: a label and what it is set to.
///
/// A list rather than named fields because the page renders them uniformly and
/// the set will grow. Anything not set says so in words rather than being
/// omitted, so the page shows what a profile *could* carry, not only what this
/// one happens to.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct IdentityRow {
    pub label: &'static str,
    pub value: String,
    /// True when nothing is set, so the page can render it quietly.
    pub unset: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ComponentState {
    pub id: &'static str,
    pub name: &'static str,
    pub summary: &'static str,
    /// `installed` / `missing` / `unavailable` / `user-supplied`.
    pub state: &'static str,
    pub detail: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerRow {
    pub name: String,
    pub address: String,
}

fn identity_rows(profile: &Profile) -> Vec<IdentityRow> {
    let presentation = &profile.presentation;
    let row = |label: &'static str, value: Option<&String>, fallback: &'static str| IdentityRow {
        label,
        value: value.cloned().unwrap_or_else(|| fallback.to_owned()),
        unset: value.is_none(),
    };
    vec![
        row("Theme", presentation.theme.as_ref(), "Launcher default"),
        row("Resource pack", presentation.resource_pack.as_ref(), "None"),
        row("HUD layout", presentation.hud_layout.as_ref(), "Default"),
        row("Artwork", presentation.artwork.as_ref(), "None"),
    ]
}

/// Fathom's components and where this profile stands with each.
///
/// Empty for a loader that reads no `mods/` directory: four rows of "does not
/// apply" is worse than no section, and a Vanilla profile has no component
/// story to tell.
fn component_states(profile: &Profile) -> Vec<ComponentState> {
    let loader = profile.loader_kind();
    if !loader.supports_mods() {
        return Vec::new();
    }

    let installed = |component: &launcher_core::components::Component| {
        component
            .parsed_mod_id()
            .is_some_and(|id| profile.mods.contains(&id))
    };

    launcher_core::components::ALL
        .iter()
        .filter(|component| component.supports(loader))
        .map(|component| {
            let here = installed(component);
            let (state, detail) = if here {
                ("installed", "In this profile.".to_owned())
            } else if let Some(blocker) = component.supply.blocker() {
                ("unavailable", blocker.to_owned())
            } else if profile.owns_components() {
                ("missing", "Installed on next launch.".to_owned())
            } else {
                ("optional", "Not added.".to_owned())
            };
            ComponentState {
                id: component.id,
                name: component.name,
                summary: component.summary,
                state,
                detail,
            }
        })
        .collect()
}

pub fn detail(core: &Launcher, id: &str) -> Option<ProfileDetail> {
    let profile = find(core, id)?;
    Some(ProfileDetail {
        id: profile.id.as_str().to_owned(),
        name: profile.display_name.clone(),
        version: profile.version_id.clone(),
        loader: loader_id(profile.loader_kind()).to_owned(),
        memory_mb: profile.memory.heap_mb,
        extra_args: profile.jvm.extra_args.join(" "),
        keep_launcher_open: profile.keep_launcher_open,
        kind: profile.kind.id(),
        kind_label: profile.kind.label(),
        java: profile.java.id(),
        java_label: profile.java.label(),
        java_path: profile
            .java
            .explicit_path()
            .map(|path| path.display().to_string()),
        preset: profile.optimizations.preset.label().to_owned(),
        identity: identity_rows(&profile),
        components: component_states(&profile),
        servers: profile
            .servers
            .iter()
            .map(|server| ServerRow {
                name: server.name.clone(),
                address: server.address.clone(),
            })
            .collect(),
        parent: profile.parent.as_ref().and_then(|parent| {
            core.profiles()
                .into_iter()
                .find(|candidate| &candidate.id == parent)
                .map(|found| found.display_name)
        }),
        // Named here so the page can grey exactly these and say why, instead of
        // letting somebody edit a field that will be overwritten at launch.
        inherited: if profile.is_derived() {
            vec!["version", "loader", "preset", "memory", "java"]
        } else {
            Vec::new()
        },
        loaders: launcher_core::profiles::Loader::ALL
            .iter()
            .map(|loader| LoaderOption {
                id: loader_id(*loader).to_owned(),
                label: loader.label().to_owned(),
                available: loader.is_implemented(),
            })
            .collect(),
    })
}

pub fn find(core: &Launcher, id: &str) -> Option<Profile> {
    core.profiles()
        .into_iter()
        .find(|profile| profile.id.as_str() == id)
}

/// A profile's artwork, as a `data:` URI, if the user supplied one.
///
/// `docs/profile-art.md` defines the hook: an image at
/// `profile-art/<profile-id>.png|jpg|jpeg` in the launcher's data directory.
/// It has existed since the previous interface and had no way to reach this
/// one, because the WebView cannot read the filesystem.
///
/// # Why a data URI rather than a file path
///
/// Handing the frontend a path would mean handing it a filesystem location and
/// then needing something able to load from it — which is the capability the
/// whole boundary exists to withhold. The bytes come back inline instead, so
/// the interface receives a picture rather than the means to read pictures.
///
/// # Why the size cap
///
/// A data URI crosses the bridge as a string in a JSON message, and base64
/// inflates by a third. Someone who drops a 40 MB PNG in that directory should
/// get no artwork, not a launcher that stalls building a 54 MB string every
/// time the Profiles page opens.
pub fn art(core: &Launcher, id: &str) -> Option<String> {
    const MAX_BYTES: u64 = 6 * 1024 * 1024;

    let directory = core.paths().root().join("profile-art");
    for (extension, mime) in [
        ("png", "image/png"),
        ("jpg", "image/jpeg"),
        ("jpeg", "image/jpeg"),
    ] {
        let candidate = directory.join(format!("{id}.{extension}"));
        let Ok(metadata) = std::fs::metadata(&candidate) else {
            continue;
        };
        if !metadata.is_file() || metadata.len() > MAX_BYTES {
            continue;
        }
        let Ok(bytes) = std::fs::read(&candidate) else {
            continue;
        };
        return Some(format!("data:{mime};base64,{}", encode_base64(&bytes)));
    }
    None
}

/// Base64, written out rather than depended on.
///
/// The alternative is adding a crate to this binary to do twenty lines of
/// arithmetic that cannot change. Every dependency in the shell is one more
/// thing shipping inside a launcher that makes a point of its supply chain.
fn encode_base64(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);

    for chunk in bytes.chunks(3) {
        let b0 = *chunk.first().unwrap_or(&0);
        let b1 = *chunk.get(1).unwrap_or(&0);
        let b2 = *chunk.get(2).unwrap_or(&0);
        let triple = (u32::from(b0) << 16) | (u32::from(b1) << 8) | u32::from(b2);

        out.push(ALPHABET[((triple >> 18) & 0x3F) as usize] as char);
        out.push(ALPHABET[((triple >> 12) & 0x3F) as usize] as char);
        // The tail is padded rather than truncated: a decoder is entitled to
        // assume a multiple of four.
        out.push(if chunk.len() > 1 {
            ALPHABET[((triple >> 6) & 0x3F) as usize] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            ALPHABET[(triple & 0x3F) as usize] as char
        } else {
            '='
        });
    }
    out
}

pub fn parse_id(id: &str) -> Result<ProfileId, String> {
    ProfileId::new(id).map_err(|error| format!("invalid profile: {error}"))
}

pub const fn loader_id(loader: launcher_core::profiles::Loader) -> &'static str {
    match loader {
        launcher_core::profiles::Loader::Vanilla => "vanilla",
        launcher_core::profiles::Loader::LegacyFabric => "legacy-fabric",
        launcher_core::profiles::Loader::Forge => "forge",
    }
}

pub fn loader_from_id(id: &str) -> Option<launcher_core::profiles::Loader> {
    launcher_core::profiles::Loader::ALL
        .iter()
        .copied()
        .find(|loader| loader_id(*loader) == id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loader_ids_round_trip() {
        for loader in launcher_core::profiles::Loader::ALL {
            assert_eq!(loader_from_id(loader_id(loader)), Some(loader));
        }
        assert_eq!(loader_from_id("not-a-loader"), None);
    }

    /// Checked against a known vector, because a base64 encoder written by
    /// hand is exactly the kind of code that looks right and is wrong in the
    /// last four bytes.
    #[test]
    fn base64_matches_the_reference_encoding() {
        assert_eq!(encode_base64(b""), "");
        assert_eq!(encode_base64(b"f"), "Zg==");
        assert_eq!(encode_base64(b"fo"), "Zm8=");
        assert_eq!(encode_base64(b"foo"), "Zm9v");
        assert_eq!(encode_base64(b"foob"), "Zm9vYg==");
        assert_eq!(encode_base64(b"fooba"), "Zm9vYmE=");
        assert_eq!(encode_base64(b"foobar"), "Zm9vYmFy");
        // Bytes above 127, where a signed/unsigned slip would show up.
        assert_eq!(encode_base64(&[0xff, 0xfe, 0xfd]), "//79");
        assert_eq!(encode_base64(&[0x00, 0x00, 0x00]), "AAAA");
    }

    /// Coarse on purpose: the row is for choosing a setup, not for auditing.
    #[test]
    fn elapsed_time_reads_as_words() {
        assert_eq!(humanise(0), "Never");
        let now = launcher_core::profiles::model::now();
        assert_eq!(humanise(now), "Just now");
        assert!(humanise(now.saturating_sub(600)).ends_with("min ago"));
        assert!(humanise(now.saturating_sub(20_000)).ends_with("hr ago"));
        assert!(humanise(now.saturating_sub(400_000)).ends_with("days ago"));
    }
}
