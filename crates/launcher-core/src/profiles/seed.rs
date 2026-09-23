//! The profiles Fathom ships with.
//!
//! # Why this exists
//!
//! A launcher that opens on an empty profile list has handed the user a setup
//! task before it has done anything for them. Worse, in this product the setup
//! task is not obvious: to get the optimizations you would have to know that
//! they need Legacy Fabric, that Legacy Fabric needs to be selected rather than
//! Vanilla, that the module is a jar, and where that jar is. Every one of those
//! is a thing we know and the user should not have to.
//!
//! So Fathom ships with a profile that already has all of it: Legacy Fabric,
//! the Balanced preset, and the optimization module installed. Pressing Play is
//! the whole flow.
//!
//! # The rules this seeding follows
//!
//! 1. **Once.** A marker file records that seeding ran. Deleting the seeded
//!    profile must not bring it back on next launch — that would be the
//!    launcher overruling a deliberate choice, repeatedly, with no way to stop
//!    it.
//! 2. **Never overwrite.** If a profile with the same id already exists, seeding
//!    does nothing to it. Someone else's "optimized" is theirs.
//! 3. **Never fatal.** Every failure here is logged and swallowed. A launcher
//!    that refuses to open because it could not create a convenience profile has
//!    turned a nicety into an outage.
//! 4. **No network.** Seeding copies a file that shipped in the installation
//!    directory. It resolves nothing and downloads nothing, so it cannot hang
//!    startup or fail because somebody is offline.

use crate::components;
use crate::mods::ModEngine;
use crate::paths::DataPaths;
use crate::profiles::{Blueprint, ProfileId, ProfileStore};

/// Records that seeding has run, so it runs exactly once per installation.
const MARKER: &str = "profiles-seeded";

/// The id of the profile we ship. Stable, because the marker is not the only
/// thing that must not create a second copy.
///
/// Still `optimized` rather than `fathom`: installations that already have this
/// profile must keep it, and renaming the id would seed a second copy beside
/// the one people have been using. The *display* name is "Fathom 1.8.9"; the id
/// is a directory name nobody sees.
const FATHOM_ID: &str = "optimized";

/// Every profile this build ships, as (id, display name).
///
/// A list rather than a single constant, because [`run`] has to be able to ask
/// "which of these has this installation never seen" -- see the marker note
/// below. There is one entry today; the mechanism is what matters, because the
/// alternative is that a profile added in a later build reaches new
/// installations only.
const SHIPPED: &[(&str, &str)] = &[(FATHOM_ID, "Fathom 1.8.9")];

/// Create the shipped profiles, if this installation has never done so.
///
/// Returns the ids actually created, which is empty on every run after the
/// first. Errors are logged rather than returned: see rule 3 above.
pub fn run(paths: &DataPaths, store: &ProfileStore) -> Vec<ProfileId> {
    run_shipping(paths, store, SHIPPED)
}

/// [`run`], against an explicit list of shipped profiles.
///
/// Split out so the behaviour that matters here -- a profile this build ships
/// and the installation has never seen gets created exactly once -- can be
/// tested with more than one entry. With a single shipped profile the two
/// branches are indistinguishable from outside, and an untested mechanism is
/// the reason this needed fixing in the first place.
fn run_shipping(
    paths: &DataPaths,
    store: &ProfileStore,
    shipped: &[(&str, &str)],
) -> Vec<ProfileId> {
    let marker = paths.root().join(MARKER);

    // The marker records *which* profiles have been seeded, one id per line,
    // rather than merely that seeding happened.
    //
    // # Why it changed shape
    //
    // It used to hold "1", and `run` returned early whenever it existed. That
    // enforced rule 1 correctly but made it impossible ever to ship a second
    // profile: every installation that had already seeded would refuse to look
    // again, so a new shipped profile would reach new installations only.
    //
    // Recording the ids keeps rule 1 exactly as strong -- a deleted profile's
    // id is still listed, so it does not come back -- while letting a profile
    // this build ships and the installation has never seen be created once.
    //
    // A marker in the old "1" format names no ids, so on the next launch it
    // reads as "nothing has been seeded". Rule 2 covers that: `optimized`
    // already exists, so it is not touched, and only the genuinely new profile
    // is created.
    let already: Vec<String> = std::fs::read_to_string(&marker)
        .unwrap_or_default()
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && *line != "1")
        .map(str::to_owned)
        .collect();

    let mut created = Vec::new();
    let mut recorded = already.clone();
    for (id, name) in shipped {
        if already.iter().any(|seen| seen == id) {
            continue;
        }
        recorded.push((*id).to_owned());
        // Recorded whether or not it was created, for the same reason the old
        // marker was written even on failure: retrying every launch would give
        // a user who deletes the profile it back the moment the jar appears.
        created.extend(seed_one(paths, store, id, name));
    }

    if recorded != already {
        if let Err(error) = std::fs::write(&marker, recorded.join("
") + "
") {
            tracing::warn!(%error, "could not record that profiles were seeded");
        }
    }

    created
}

fn seed_one(
    paths: &DataPaths,
    store: &ProfileStore,
    raw_id: &str,
    name: &str,
) -> Vec<ProfileId> {
    let Ok(id) = ProfileId::new(raw_id) else {
        // The ids are constants, so this is unreachable -- but a panic during
        // launcher startup is not the way to find that out.
        tracing::error!("the seeded profile id {raw_id} is not a valid profile id");
        return Vec::new();
    };

    // Rule 2. Somebody else's profile with this id is theirs.
    if store.load(&id).is_ok() {
        tracing::debug!("a profile named {raw_id} already exists; not seeding");
        return Vec::new();
    }

    // The same blueprint the setup screen opens on, built by the same code.
    // Seeding used to assemble a profile by hand -- loader here, preset there,
    // module afterwards -- which meant the shipped profile and a
    // user-created one could drift apart without anybody noticing.
    let blueprint = Blueprint::recommended(name);
    let mut profile = blueprint.build(id.clone());

    if let Err(error) = store.save(&profile) {
        tracing::warn!(%error, "could not create the shipped profile");
        return Vec::new();
    }

    // The components are installed by the component manager, not by this file.
    // Rule 4 still holds: it copies a jar that shipped in this installation and
    // resolves nothing over the network.
    let engine = ModEngine::new(paths.clone());
    match components::ensure(paths, &engine, &mut profile) {
        Ok(report) => {
            if report.changed_anything() {
                if let Err(error) = store.save(&profile) {
                    tracing::warn!(%error, "could not record the seeded components");
                }
            }
            tracing::info!(profile = %raw_id, summary = %report.summary(),
                "seeded a Fathom profile");
        }
        Err(error) => tracing::warn!(%error, "could not install the Fathom components"),
    }

    vec![id]
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing)]
mod tests {
    use super::*;

    /// The shipped profile has to be one the launcher can actually launch, or
    /// the first thing a new user sees is a profile that refuses.

    #[test]
    fn the_shipped_profile_uses_a_loader_that_works() {
        let loader = Blueprint::recommended("x").effective_loader();
        assert!(loader.is_implemented());
        assert!(loader.supports_mods());
    }

    fn paths() -> (tempfile::TempDir, DataPaths) {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let paths = DataPaths::rooted_at(dir.path());
        paths.ensure().expect("the data directories");
        (dir, paths)
    }

    #[test]
    fn seeding_creates_a_ready_to_play_profile() {
        let (_dir, paths) = paths();
        let store = ProfileStore::new(paths.clone());

        let created = run(&paths, &store);
        assert_eq!(created.len(), SHIPPED.len(), "every shipped profile should be seeded");

        let profile = store
            .load(&ProfileId::new(FATHOM_ID).unwrap())
            .expect("the seeded profile");
        // The shipped profile is a Fathom profile, not a Vanilla one with
        // extras bolted on. That distinction is the whole milestone: the
        // launcher maintains its own components here without being asked.
        assert_eq!(profile.display_name, "Fathom 1.8.9");
        assert_eq!(profile.kind, crate::profiles::ProfileKind::Fathom);
        assert!(profile.owns_components());
        assert_eq!(profile.loader.kind, crate::profiles::Loader::LegacyFabric);
        assert_eq!(
            profile.optimizations.preset,
            crate::profiles::OptimizationPreset::Balanced
        );
        // The preset must be expanded, not just named: the bridge file the game
        // reads is a switch map, and an empty one means nothing is on.
        assert!(
            profile.optimizations.rendering.values().any(|on| *on),
            "the Balanced preset should have switched something on"
        );
    }

    /// A build that ships a new profile must be able to deliver it to an
    /// installation that seeded before it existed -- without resurrecting a
    /// profile that installation deliberately deleted.
    ///
    /// This is the regression the marker format fixes. The old marker held
    /// "1" and `run` returned early whenever it existed, so a profile added in
    /// a later build could reach new installations only.
    #[test]
    fn a_newly_shipped_profile_reaches_an_installation_that_already_seeded() {
        let (_dir, paths) = paths();
        let store = ProfileStore::new(paths.clone());
        let later = &[(FATHOM_ID, "Fathom 1.8.9"), ("second", "A Later Profile")];

        // The old marker format: "seeding ran", naming nothing.
        std::fs::write(paths.root().join(MARKER), "1").unwrap();
        // And the profile that generation shipped, already present and renamed.
        let old_id = ProfileId::new(FATHOM_ID).unwrap();
        store
            .save(&crate::profiles::Profile::new(old_id.clone(), "Mine"))
            .unwrap();

        let created = run_shipping(&paths, &store, later);
        assert_eq!(created.len(), 1, "only the genuinely new profile");
        assert_eq!(created[0].as_str(), "second");
        assert_eq!(
            store.load(&old_id).unwrap().display_name,
            "Mine",
            "rule 2: the existing profile is untouched"
        );

        // And it settles: a second pass creates nothing.
        assert!(run_shipping(&paths, &store, later).is_empty());

        // Rule 1 still holds for the new one too.
        crate::profiles::ProfileOps::new(paths.clone())
            .delete(&created[0])
            .expect("delete it");
        assert!(run_shipping(&paths, &store, later).is_empty());
        assert!(store.load(&created[0]).is_err(), "it came back");
    }

    /// Rule 1. Deleting the shipped profile is a decision, and a launcher that
    /// recreated it every time you opened it would be overruling that decision
    /// with no way for the user to win.
    #[test]
    fn seeding_happens_once_even_if_the_profile_is_deleted() {
        let (_dir, paths) = paths();
        let store = ProfileStore::new(paths.clone());

        let created = run(&paths, &store);
        assert_eq!(created.len(), SHIPPED.len());

        let deleted = ProfileId::new(FATHOM_ID).unwrap();
        crate::profiles::ProfileOps::new(paths.clone())
            .delete(&deleted)
            .expect("delete the seeded profile");
        let again = run(&paths, &store);
        assert!(again.is_empty(), "seeding must not run a second time");
        assert!(store.load(&deleted).is_err(), "the profile came back");
    }

    /// Rule 2. An existing profile with the same id belongs to whoever made it.
    #[test]
    fn an_existing_profile_is_never_overwritten() {
        let (_dir, paths) = paths();
        let store = ProfileStore::new(paths.clone());

        let id = ProfileId::new(FATHOM_ID).expect("a valid id");
        let mut mine = crate::profiles::Profile::new(id.clone(), "My own optimized setup");
        mine.memory.heap_mb = 6144;
        store.save(&mine).expect("save");

        let created = run(&paths, &store);
        assert!(
            !created.iter().any(|made| *made == id),
            "the existing profile must not be re-created"
        );

        let after = store.load(&id).expect("still there");
        assert_eq!(after.display_name, "My own optimized setup");
        assert_eq!(after.memory.heap_mb, 6144);
    }
}
