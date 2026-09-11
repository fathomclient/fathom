//! Fathom Profiles: the user-visible loadouts.
//!
//! A profile is which modules are on, how they are configured, where the HUD
//! sits, and the client's own interface preferences. It is switched while the
//! game runs and it never contains a Minecraft version, a loader, a Java path,
//! a game directory or anything about an account — those belong to the Game
//! Instance in [`crate::profiles`], which is a different thing with a different
//! lifetime.
//!
//! See `docs/fathom-profiles.md` for the split, the on-disk format, how the
//! running game finds this collection, and what migration does.
//!
//! # The module is `loadout`, the directory is `profiles`
//!
//! Two things in this codebase were called `Profile`, which is the mistake this
//! module exists to correct. Until the Game Instance rename lands, a type called
//! `Profile` in two modules would be an excellent way to make that mistake a
//! second time, so the Rust module is `loadout` and only the on-disk directory
//! and the user-facing word are `profiles`.

pub mod migrate;
pub mod model;
pub mod store;

pub use migrate::{run as migrate, Report as MigrationReport};
pub use model::{
    ClientPrefs, ClientState, FathomProfile, HudEntry, IdError, ModuleState, Performance,
    ProfileId, ValidationError, DEFAULT_ID, DEFAULT_NAME, SCHEMA,
};
pub use store::{summarise, Complaint, Listing, ProfileStore, ProfileSummary, StoreError};

use std::path::{Path, PathBuf};

/// The collection's directory inside a data root.
///
/// One function rather than a constant so the two callers that need it — the
/// launcher's [`crate::paths::DataPaths`] and the bridge that tells the game
/// where to look — cannot spell it differently.
pub fn directory_in(root: &Path) -> PathBuf {
    root.join("profiles")
}

/// The collection the game falls back to when there is no launcher.
///
/// A copy of the mod dropped into somebody's own instance, or the Gradle
/// development client. Its profiles are local to that game directory, which is
/// correct: there is no launcher whose collection they could join. This is a
/// supported case, not a failure — see `docs/fathom-profiles.md`.
pub fn standalone_directory_in(game_directory: &Path) -> PathBuf {
    game_directory.join("fathom").join("profiles")
}

#[allow(clippy::unwrap_used)]
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_launcher_and_the_game_agree_on_the_directory_name() {
        // Not a tautology: this is the string the Java side hard-codes as
        // `ProfileStore.DIRECTORY`, and it is the whole of "one collection".
        assert!(directory_in(Path::new("/data")).ends_with("profiles"));
        assert!(standalone_directory_in(Path::new("/game")).ends_with("profiles"));
        assert!(standalone_directory_in(Path::new("/game"))
            .to_string_lossy()
            .contains("fathom"));
    }

    #[test]
    fn the_whole_journey_from_old_files_to_a_live_profile() {
        // The acceptance path, end to end: properties files on disk, migrate,
        // resolve what is live, switch, and have the pointer survive a reopen.
        let game = tempfile::tempdir().unwrap();
        let config = game.path().join("config");
        std::fs::create_dir_all(&config).unwrap();
        std::fs::write(
            config.join("fathom-features.properties"),
            "schema=1\nfeature.fps.enabled=true\n",
        )
        .unwrap();

        let dir = standalone_directory_in(game.path());
        let store = ProfileStore::new(&dir);
        migrate(&store, game.path()).unwrap();

        let live = store.resolve_live().unwrap();
        assert_eq!(live.name, DEFAULT_NAME);

        let bedwars = store.create("Bedwars").unwrap();
        store.set_last_profile_id(&bedwars.id).unwrap();

        // A different handle on the same directory is what the launcher and the
        // game each have. They must see the same thing.
        let reopened = ProfileStore::new(&dir);
        assert_eq!(reopened.list().profiles.len(), 2);
        assert_eq!(reopened.resolve_live().unwrap().id, bedwars.id);
    }
}
