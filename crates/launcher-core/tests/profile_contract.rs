//! The launcher's half of the launcher/game profile contract.
//!
//! "One collection, two readers" is only true if the Java store reads what this
//! one writes and this one reads what the Java store writes. Unit tests on each
//! side prove each side follows the rules; this proves they agree, against real
//! files, with no game window involved.
//!
//! Driven by `tools/profile-contract.sh`, which runs three stages in order:
//!
//! 1. `launcher-writes` — this test creates Default, Bedwars and Survival with
//!    visibly different modules and HUD, and makes Survival the next profile.
//! 2. `ProfileContract game` — the game's store opens the same directory,
//!    checks it sees all three and starts on Survival, then does what the game
//!    does when somebody switches in the hub: saves an edit to Bedwars and makes
//!    it live. It also restarts (a fresh store on the same files) and checks
//!    Bedwars is still live and no second Default appeared.
//! 3. `launcher-reads` — this test checks it sees the game's edit and pointer.
//!
//! Without `FATHOM_CONTRACT_DIR` set, the test does nothing, so an ordinary
//! `cargo test` is unaffected.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::path::PathBuf;

use launcher_core::loadout::{HudEntry, ModuleState, ProfileId, ProfileStore};

#[test]
fn the_launcher_and_the_game_share_one_profile_collection() {
    let Some(root) = std::env::var_os("FATHOM_CONTRACT_DIR") else {
        return;
    };
    let store = ProfileStore::new(PathBuf::from(root).join("profiles"));
    match std::env::var("FATHOM_CONTRACT_STAGE")
        .unwrap_or_default()
        .as_str()
    {
        "launcher-writes" => launcher_writes(&store),
        "launcher-reads" => launcher_reads(&store),
        other => panic!("unknown contract stage {other:?}"),
    }
}

fn launcher_writes(store: &ProfileStore) {
    if store.directory().exists() {
        std::fs::remove_dir_all(store.directory()).unwrap();
    }

    // The launcher opening an empty collection: exactly one Default.
    let default = store.resolve_live().unwrap();
    assert_eq!(default.name, "Default");

    let mut bedwars = store.create("Bedwars").unwrap();
    for module in ["cps", "keystrokes", "ping"] {
        bedwars.modules.insert(
            module.to_owned(),
            ModuleState {
                enabled: Some(true),
                ..ModuleState::default()
            },
        );
    }
    bedwars.hud.insert(
        "fps".to_owned(),
        HudEntry {
            anchor: "top-right".to_owned(),
            x: 6,
            y: 6,
            scale: 1.5,
            ..HudEntry::default()
        },
    );
    store.save(&bedwars).unwrap();

    let mut survival = store.create("Survival").unwrap();
    for module in ["coordinates", "armour", "potions"] {
        survival.modules.insert(
            module.to_owned(),
            ModuleState {
                enabled: Some(true),
                ..ModuleState::default()
            },
        );
    }
    survival.client.hud_font = Some("Minecraft".to_owned());
    survival.client.rshift_background = Some("Gameplay glass".to_owned());
    survival.client.rshift_blur = Some("Strong".to_owned());
    survival.client.rshift_dim = Some("Deep".to_owned());
    survival.client.menu_background = Some("Low".to_owned());
    store.save(&survival).unwrap();

    // Chosen in the launcher: the next game opens Survival.
    store.set_last_profile_id(&survival.id).unwrap();
    assert_eq!(store.list().profiles.len(), 3);
}

fn launcher_reads(store: &ProfileStore) {
    let listing = store.list();
    assert!(listing.complaints.is_empty(), "{:?}", listing.complaints);
    let names: Vec<&str> = listing.profiles.iter().map(|p| p.name.as_str()).collect();
    assert_eq!(
        names,
        ["Bedwars", "Default", "Survival"],
        "no duplicate Default"
    );

    // The game switched to Bedwars and that is what the launcher now shows as live.
    let live = store.resolve_live().unwrap();
    assert_eq!(live.id, ProfileId::new("bedwars").unwrap());

    // And the edit the game saved into Bedwars is here, byte-compatible.
    let bedwars = store.load(&live.id).unwrap();
    let zoom = bedwars
        .modules
        .get("zoom")
        .expect("the game's edit arrived");
    assert_eq!(zoom.enabled, Some(true));
    assert_eq!(bedwars.hud.get("fps").unwrap().anchor, "top-right");
    assert_eq!(
        bedwars.client.rshift_background.as_deref(),
        Some("Fathom background")
    );
    assert_eq!(bedwars.client.rshift_blur.as_deref(), Some("Soft"));
    assert_eq!(bedwars.client.rshift_dim.as_deref(), Some("Light"));
    assert_eq!(bedwars.client.menu_background.as_deref(), Some("Off"));
    // A preference this launcher has never heard of is kept, not dropped,
    // when the launcher itself rewrites the profile.
    assert_eq!(
        bedwars.client.extra.get("futurePreference"),
        Some(&serde_json::json!("kept"))
    );
    store.save(&bedwars).unwrap();
    let rewritten = store.load(&live.id).unwrap();
    assert_eq!(
        rewritten.client.extra.get("futurePreference"),
        Some(&serde_json::json!("kept"))
    );
    assert_eq!(store.list().profiles.len(), 3, "reading created nothing");
}
