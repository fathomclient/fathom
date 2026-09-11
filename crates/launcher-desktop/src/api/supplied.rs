//! The Add OptiFine flow, as the window sees it.
//!
//! # Why it is two steps and not one
//!
//! The existing `import_mod` command answers "put this jar in this profile",
//! which is right when somebody is already looking at a profile's mod list. The
//! `OptiFine` flow starts somewhere else — the user has just come back from
//! optifine.net with a file and does not necessarily know which profile it
//! belongs in.
//!
//! So the file is accepted first, on its own: verified, hashed, copied into the
//! local store, and identified. Only then is the user asked where it goes, and
//! by that point the launcher can tell them which of their profiles it actually
//! suits and why the others do not.
//!
//! # What crosses the boundary
//!
//! A description, never a path. The window learns the jar's name, version,
//! Minecraft version, size, entry count and short hash — all facts about a file
//! it caused to be read, none of them a location on disk. The accepted jar
//! itself is held on this side between the two steps, keyed by nothing: there is
//! at most one, because there is one window and one drop target.
//!
//! # What never happens
//!
//! No upload, no mirror, no telemetry, no automatic retrieval. `OptiFine` is
//! proprietary and its licence forbids redistribution; the launcher's entire
//! contribution is opening the author's own download page and then handling the
//! file the user came back with.

use serde::Serialize;

use launcher_core::mods::supplied::{AcceptedJar, ProfileFit};
use launcher_core::Launcher;

/// A jar that has been accepted, and where it could go.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SuppliedView {
    /// False when the user cancelled the file picker. Not an error: closing a
    /// dialog is a decision, and reporting it as a failure would put a red
    /// message on screen for a thing that went exactly as intended.
    pub chosen: bool,
    pub name: String,
    /// "OptiFine HD U M5 for Minecraft 1.8.9".
    pub description: String,
    pub version: Option<String>,
    pub minecraft: Option<String>,
    pub file_name: String,
    /// The first twelve characters of the SHA-1, which is what people quote.
    pub hash: String,
    pub size: u64,
    pub entries: u16,
    /// Always "You added this". Shown so the screen states it rather than
    /// implying it.
    pub source: String,
    /// Whether the catalogue recognised the file.
    pub recognised: bool,
    /// Whether it is `OptiFine` specifically, which changes the wording on the
    /// screen and nothing else.
    pub is_optifine: bool,
    pub profiles: Vec<ProfileFitView>,
}

impl SuppliedView {
    pub fn cancelled() -> Self {
        Self {
            chosen: false,
            name: String::new(),
            description: String::new(),
            version: None,
            minecraft: None,
            file_name: String::new(),
            hash: String::new(),
            size: 0,
            entries: 0,
            source: String::new(),
            recognised: false,
            is_optifine: false,
            profiles: Vec::new(),
        }
    }

    pub fn of(accepted: &AcceptedJar, fits: &[ProfileFit]) -> Self {
        Self {
            chosen: true,
            name: accepted.name.clone(),
            description: accepted.describe(),
            version: accepted.version.clone(),
            minecraft: accepted.minecraft.clone(),
            file_name: accepted.file_name.clone(),
            hash: accepted.short_hash(),
            size: accepted.size,
            entries: accepted.entries,
            source: accepted.source.label().to_owned(),
            recognised: accepted.recognised,
            is_optifine: accepted.id == "optifine",
            profiles: fits.iter().map(ProfileFitView::of).collect(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileFitView {
    pub id: String,
    pub name: String,
    pub compatible: bool,
    pub already_present: bool,
    pub note: String,
}

impl ProfileFitView {
    fn of(fit: &ProfileFit) -> Self {
        Self {
            id: fit.profile_id.clone(),
            name: fit.profile_name.clone(),
            compatible: fit.compatible,
            already_present: fit.already_present,
            note: fit.note.clone(),
        }
    }
}

/// Accept a file and work out where it could go.
pub fn accept(core: &Launcher, source: &std::path::Path) -> Result<(AcceptedJar, SuppliedView), String> {
    let accepted = launcher_core::mods::supplied::accept(&core.mod_engine(), source)
        .map_err(|error| error.to_string())?;
    let profiles = core.profiles();
    let fits = launcher_core::mods::supplied::compatible_profiles(&accepted, &profiles);
    let view = SuppliedView::of(&accepted, &fits);
    Ok((accepted, view))
}

/// What changed after adding it to a profile.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AddedView {
    pub profile_name: String,
    /// The sentence to show: what was added, and to what.
    pub message: String,
    /// Optimizations Fathom will stand down for it on the next launch, already
    /// worded for the screen.
    ///
    /// Computed and shown here rather than discovered at launch, because the
    /// moment somebody adds `OptiFine` is the moment the trade is theirs to
    /// understand. Finding out afterwards that a setting they chose is inert is
    /// the failure mode this whole area is written against.
    pub standdowns: Vec<String>,
}

impl AddedView {
    pub fn of(accepted: &AcceptedJar, profile: &launcher_core::profiles::Profile) -> Self {
        let standdowns = launcher_core::optimizations::conflicts::for_profile(profile)
            .iter()
            .map(|standdown| {
                let name = launcher_core::optimizations::find(&standdown.optimization)
                    .map(|optimization| optimization.name.to_owned())
                    .unwrap_or_else(|| standdown.optimization.clone());
                standdown.headline(&name)
            })
            .collect();
        Self {
            profile_name: profile.display_name.clone(),
            message: format!("{} added to {}.", accepted.name, profile.display_name),
            standdowns,
        }
    }
}
