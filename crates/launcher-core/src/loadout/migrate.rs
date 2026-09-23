//! Turning the three properties files into profiles, once, safely.
//!
//! Fathom's game-side configuration used to be three files in a game
//! directory: `config/fathom-features.properties` (modules, their settings and
//! their keybinds, plus the client's own preferences),
//! `config/fathom-hud[-name].properties` (HUD layouts, already named — the
//! closest thing the client had to a loadout) and
//! `config/fathom-performance.properties` (what the launcher asked for).
//!
//! # The rules
//!
//! 1. **Back up before touching anything.** Every source file is copied into
//!    `backups/<stamp>/` before a profile is written, and the originals are then
//!    left exactly where they are. Migration is not a move.
//! 2. **Never delete what could not be mapped.** A key this build does not
//!    recognise is a key somebody chose. It costs bytes to keep and it costs
//!    somebody's afternoon to lose.
//! 3. **Never overwrite an edited Default.** A blank placeholder is filled in;
//!    a Default somebody changed is kept, and the migrated data goes into
//!    `Default (imported)` beside it.
//! 4. **Once per game directory, per collection.** Recorded in
//!    `fathom/profiles-migrated` inside the game directory, one collection per
//!    line. A second run into the same collection does nothing, so it cannot
//!    produce a second Default — the redundant-default loop this model exists to
//!    end. A different collection still gets the data: a game that only ever ran
//!    standalone, later started by the launcher, migrates into the launcher's
//!    collection too.
//!
//! # Why named HUD layouts become profiles
//!
//! Because that is what they were being used for. Somebody with
//! `fathom-hud-bedwars.properties` had already built a loadout by hand, in the
//! only place the client let them, and turning it into a nameless orphan would
//! be the migration losing the exact thing it was written to preserve.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use super::model::{FathomProfile, ProfileId, DEFAULT_NAME, SCHEMA};
use super::store::{ProfileStore, StoreError};

/// What migration did, for the log and for Diagnostics.
#[derive(Debug, Default, Clone)]
pub struct Report {
    /// Profiles written. Empty when there was nothing to migrate.
    pub created: Vec<String>,
    /// Where the originals were copied.
    pub backup: Option<PathBuf>,
    /// Files read.
    pub sources: Vec<PathBuf>,
    /// Keys that were kept in the backup but not mapped into a profile.
    pub unmapped: Vec<String>,
    /// True when migration had already run and this call did nothing.
    pub already_done: bool,
}

impl Report {
    /// One line for the log.
    pub fn summary(&self) -> String {
        if self.already_done {
            return "profiles already migrated".to_owned();
        }
        if self.created.is_empty() {
            return "nothing to migrate".to_owned();
        }
        format!(
            "migrated {} into {} profile(s): {}",
            self.sources.len(),
            self.created.len(),
            self.created.join(", ")
        )
    }
}

/// Relative to the game directory. Must match `ProfileMigration.MARKER`.
pub const MARKER: &str = "fathom/profiles-migrated";

/// What migrated data is called when a Default somebody edited is already there.
pub const IMPORTED_NAME: &str = "Default (imported)";

/// Migrate a game directory's properties files into `store`.
///
/// Idempotent per collection: the second call into the same collection reports
/// `already_done` and writes nothing.
pub fn run(store: &ProfileStore, game_directory: &Path) -> Result<Report, StoreError> {
    let mut report = Report::default();
    let marker = game_directory.join(MARKER);
    let key = collection_key(store.directory());
    let seen = std::fs::read_to_string(&marker)
        .is_ok_and(|text| text.lines().any(|line| line.trim() == key));
    if seen {
        report.already_done = true;
        return Ok(report);
    }

    let config = game_directory.join("config");
    let features = config.join("fathom-features.properties");
    let performance = config.join("fathom-performance.properties");
    let layouts = hud_layouts(&config);

    if !features.is_file() && !performance.is_file() && layouts.is_empty() {
        // Nothing to migrate. Deliberately *not* recorded: a fresh install that
        // later has files restored from a backup should still get to migrate them.
        return Ok(report);
    }

    // -- 1. back up, before a single profile is written ----------------------
    let stamp = stamp();
    let backup = game_directory.join("fathom").join("backups").join(&stamp);
    std::fs::create_dir_all(&backup).map_err(|source| StoreError::Io {
        path: backup.clone(),
        source,
    })?;
    for source in std::iter::once(&features)
        .chain(std::iter::once(&performance))
        .chain(layouts.iter().map(|(_, path)| path))
    {
        if source.is_file() {
            if let Some(name) = source.file_name() {
                std::fs::copy(source, backup.join(name)).map_err(|error| StoreError::Io {
                    path: source.clone(),
                    source: error,
                })?;
            }
            report.sources.push(source.clone());
        }
    }
    report.backup = Some(backup);

    // -- 2. the default profile, from features + performance + default HUD ---
    let mut migrated = FathomProfile::new(ProfileId::default_id(), DEFAULT_NAME);
    apply_features(&mut migrated, &read_properties(&features), &mut report);
    apply_performance(&mut migrated, &read_properties(&performance));
    if let Some((_, path)) = layouts.iter().find(|(name, _)| name.is_none()) {
        apply_hud(&mut migrated, &read_properties(path));
    }
    migrated.prune();

    // A Default that will not parse is still somebody's file, so "not blank".
    let default_id = ProfileId::default_id();
    let keep_existing = store.path_for(&default_id).is_file()
        && store
            .load(&default_id)
            .map_or(true, |existing| !existing.is_blank());
    let base = if keep_existing {
        let id = ProfileId::from_display_name("Default imported", &store.ids())
            .ok_or_else(|| StoreError::Exists(default_id.clone()))?;
        migrated.duplicated(id, IMPORTED_NAME)
    } else {
        migrated
    };
    store.save(&base)?;
    report.created.push(base.name.clone());

    // -- 3. every named layout becomes a profile of its own ------------------
    //
    // Sharing the modules and settings of the base, because a named layout was
    // only ever a HUD arrangement: the person who made one was using the same
    // modules, just placed differently.
    for (name, path) in &layouts {
        let Some(name) = name else { continue };
        let Some(id) = ProfileId::from_display_name(name, &store.ids()) else {
            report
                .unmapped
                .push(format!("hud layout {name:?}: unusable name"));
            continue;
        };
        let mut profile = base.duplicated(id, title_case(name));
        profile.hud.clear();
        apply_hud(&mut profile, &read_properties(path));
        profile.prune();
        store.save(&profile)?;
        report.created.push(profile.name.clone());
    }

    // -- 4. record it --------------------------------------------------------
    let mut state = store.client_state();
    state.schema = SCHEMA;
    if state.last_profile_id.is_none() {
        state.last_profile_id = Some(base.id.clone());
    }
    state.migrated_from = Some(stamp);
    store.write_state(&state)?;

    let mut text = std::fs::read_to_string(&marker).unwrap_or_default();
    if !text.is_empty() && !text.ends_with('\n') {
        text.push('\n');
    }
    text.push_str(&key);
    text.push('\n');
    crate::fs_safety::atomic_write(&marker, text.as_bytes()).map_err(|source| {
        StoreError::Write {
            path: marker,
            source,
        }
    })?;

    Ok(report)
}

/// One line identifying a collection.
///
/// Normalised because the launcher and the game both write the marker for the
/// same collection and spell paths differently: separators, trailing slashes,
/// and on Windows, case. Must match `ProfileMigration.collectionKey`.
fn collection_key(directory: &Path) -> String {
    let absolute = std::path::absolute(directory).unwrap_or_else(|_| directory.to_path_buf());
    let mut key = absolute.to_string_lossy().replace('\\', "/");
    while key.ends_with('/') {
        key.pop();
    }
    key.to_lowercase()
}

// ---------------------------------------------------------------------------
// Mapping
// ---------------------------------------------------------------------------

fn apply_features(
    profile: &mut FathomProfile,
    keys: &BTreeMap<String, String>,
    report: &mut Report,
) {
    for (key, value) in keys {
        if key == "schema" {
            continue;
        }
        if let Some(rest) = key.strip_prefix("client.") {
            match rest {
                "hub.key" => profile.client.hub_key = value.parse().ok(),
                "ui.scale" => profile.client.ui_scale = Some(value.clone()),
                "ui.high-quality" => profile.client.high_quality_ui = parse_bool(value),
                "hud.font" => profile.client.hud_font = Some(value.clone()),
                other => report.unmapped.push(format!("client.{other}")),
            }
            continue;
        }
        let Some(rest) = key.strip_prefix("feature.") else {
            report.unmapped.push(key.clone());
            continue;
        };
        // `feature.<module>.<what>[.<id>]`
        let Some((module, what)) = rest.split_once('.') else {
            report.unmapped.push(key.clone());
            continue;
        };
        let entry = profile.modules.entry(module.to_owned()).or_default();
        if what == "enabled" {
            entry.enabled = parse_bool(value);
        } else if what == "favourite" {
            entry.favourite = parse_bool(value).unwrap_or(false);
        } else if let Some(setting) = what.strip_prefix("set.") {
            entry.settings.insert(setting.to_owned(), value.clone());
        } else if let Some(keybind) = what.strip_prefix("key.") {
            entry.keys.insert(keybind.to_owned(), value.clone());
        } else {
            report.unmapped.push(key.clone());
        }
    }
}

fn apply_performance(profile: &mut FathomProfile, keys: &BTreeMap<String, String>) {
    for (key, value) in keys {
        match key.as_str() {
            "schema" => {}
            "preset" => profile.performance.preset = Some(value.clone()),
            other => {
                // `opt.<id>` is one optimization's switch, by the same dotted id
                // the game's registry uses. `conflict.<id>` is the launcher's own
                // compatibility stand-down, recomputed every launch: a fact about
                // this installation rather than a choice, so it stays out of a
                // loadout.
                let Some(id) = other.strip_prefix("opt.") else {
                    continue;
                };
                if let Some(on) = parse_bool(value) {
                    profile
                        .performance
                        .switches
                        .insert(id.trim().to_owned(), on);
                }
            }
        }
    }
}

fn apply_hud(profile: &mut FathomProfile, keys: &BTreeMap<String, String>) {
    // `widget.<id>.<field>`
    for (key, value) in keys {
        let Some(rest) = key.strip_prefix("widget.") else {
            continue;
        };
        let Some((widget, field)) = rest.rsplit_once('.') else {
            continue;
        };
        let entry = profile.hud.entry(widget.to_owned()).or_default();
        match field {
            // The old files wrote the Java enum's name, `TOP_LEFT`. The profile
            // format is kebab-case, `top-left`, and a migrated profile must not
            // disagree with a new one about how to spell the same corner.
            "anchor" => entry.anchor = value.trim().to_ascii_lowercase().replace('_', "-"),
            "x" => entry.x = value.parse().unwrap_or(0),
            "y" => entry.y = value.parse().unwrap_or(0),
            "scale" => {
                if let Ok(parsed) = value.parse::<f32>() {
                    if parsed.is_finite() && parsed > 0.0 {
                        entry.scale = parsed;
                    }
                }
            }
            "opacity" => {
                if let Ok(parsed) = value.parse::<f32>() {
                    if parsed.is_finite() && (0.0..=1.0).contains(&parsed) {
                        entry.opacity = parsed;
                    }
                }
            }
            "hidden" => entry.hidden = parse_bool(value).unwrap_or(false),
            _ => {}
        }
    }
}

// ---------------------------------------------------------------------------
// Reading the old format
// ---------------------------------------------------------------------------

/// Every `fathom-hud*.properties` in a config directory, with its layout name.
///
/// `None` for the unnamed default layout, `Some(name)` for the rest.
fn hud_layouts(config: &Path) -> Vec<(Option<String>, PathBuf)> {
    let mut out = Vec::new();
    let Ok(entries) = std::fs::read_dir(config) else {
        return out;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
            continue;
        };
        if name == "fathom-hud.properties" {
            out.push((None, path));
        } else if let Some(rest) = name.strip_prefix("fathom-hud-") {
            if let Some(layout) = rest.strip_suffix(".properties") {
                out.push((Some(layout.to_owned()), path));
            }
        }
    }
    out.sort_by(|a, b| a.0.cmp(&b.0));
    out
}

/// A `.properties` file, as a map.
///
/// Hand-parsed rather than pulled in as a dependency: the files were written by
/// `java.util.Properties` with ASCII keys and no line continuations, and the
/// subset that matters is `key=value` plus `#` comments.
fn read_properties(path: &Path) -> BTreeMap<String, String> {
    let mut out = BTreeMap::new();
    let Ok(text) = std::fs::read_to_string(path) else {
        return out;
    };
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        out.insert(key.trim().to_owned(), unescape(value.trim()));
    }
    out
}

/// `java.util.Properties` escapes `:`, `=`, `\` and writes `\:` in values.
fn unescape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut chars = value.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('n') => out.push('\n'),
            Some('t') => out.push('\t'),
            Some('r') => out.push('\r'),
            Some(other) => out.push(other),
            None => {}
        }
    }
    out
}

fn parse_bool(value: &str) -> Option<bool> {
    match value.trim().to_ascii_lowercase().as_str() {
        "true" => Some(true),
        "false" => Some(false),
        _ => None,
    }
}

fn title_case(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut start = true;
    for c in raw.chars() {
        if c == '-' || c == '_' {
            out.push(' ');
            start = true;
        } else if start {
            out.extend(c.to_uppercase());
            start = false;
        } else {
            out.push(c);
        }
    }
    out
}

fn stamp() -> String {
    let seconds = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    format!("{seconds}")
}

#[allow(clippy::unwrap_used)]
#[cfg(test)]
mod tests {
    use super::*;

    fn game_directory_with(files: &[(&str, &str)]) -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        let config = dir.path().join("config");
        std::fs::create_dir_all(&config).unwrap();
        for (name, body) in files {
            std::fs::write(config.join(name), body).unwrap();
        }
        dir
    }

    const FEATURES: &str = "\
schema=1
client.hub.key=54
client.ui.scale=110%
client.hud.font=Minecraft
feature.fps.enabled=true
feature.fps.favourite=true
feature.fps.set.decimals=0
feature.zoom.key.hold=46
feature.cps.enabled=false
something.we.do.not.know=42
";

    const HUD: &str = "\
schema=1
widget.fps.anchor=TOP_LEFT
widget.fps.x=4
widget.fps.y=4
widget.fps.scale=1.5
widget.cps.anchor=bottom-right
widget.cps.hidden=true
";

    // The real shape: written by the launcher before every launch, `key = value`
    // with spaces, `opt.` switches by dotted id, and a `conflict.` stand-down.
    const PERFORMANCE: &str = "\
# Written by Fathom before launch. Do not edit.
schema = 1
preset = balanced
opt.render.font.metrics-cache = true
opt.memory.world-change-gc = false
conflict.render.font.batching = OptiFine
";

    #[test]
    fn the_three_files_become_one_default_profile() {
        let game = game_directory_with(&[
            ("fathom-features.properties", FEATURES),
            ("fathom-hud.properties", HUD),
            ("fathom-performance.properties", PERFORMANCE),
        ]);
        let store = ProfileStore::new(game.path().join("profiles"));

        let report = run(&store, game.path()).unwrap();
        assert_eq!(report.created, vec!["Default".to_owned()]);
        assert_eq!(report.sources.len(), 3);

        let profile = store.load(&ProfileId::default_id()).unwrap();
        assert_eq!(profile.client.hub_key, Some(54));
        assert_eq!(profile.client.ui_scale.as_deref(), Some("110%"));
        assert_eq!(profile.client.hud_font.as_deref(), Some("Minecraft"));

        let fps = profile.modules.get("fps").unwrap();
        assert_eq!(fps.enabled, Some(true));
        assert!(fps.favourite);
        assert_eq!(fps.settings.get("decimals").unwrap(), "0");
        assert_eq!(
            profile
                .modules
                .get("zoom")
                .unwrap()
                .keys
                .get("hold")
                .unwrap(),
            "46"
        );
        assert_eq!(profile.modules.get("cps").unwrap().enabled, Some(false));

        assert_eq!(
            profile.hud.get("fps").unwrap().anchor,
            "top-left",
            "enum name normalised"
        );
        assert_eq!(profile.hud.get("fps").unwrap().x, 4);
        assert!((profile.hud.get("fps").unwrap().scale - 1.5).abs() < f32::EPSILON);
        assert!(profile.hud.get("cps").unwrap().hidden);

        assert_eq!(profile.performance.preset.as_deref(), Some("balanced"));
        assert_eq!(
            profile
                .performance
                .switches
                .get("render.font.metrics-cache"),
            Some(&true),
            "the opt. prefix is stripped to the registry id"
        );
        assert_eq!(
            profile.performance.switches.get("memory.world-change-gc"),
            Some(&false)
        );
        assert!(
            !profile
                .performance
                .switches
                .keys()
                .any(|k| k.contains("conflict") || k.starts_with("opt.")),
            "stand-downs are not choices: {:?}",
            profile.performance.switches
        );
    }

    #[test]
    fn the_originals_are_backed_up_and_left_alone() {
        let game = game_directory_with(&[("fathom-features.properties", FEATURES)]);
        let store = ProfileStore::new(game.path().join("profiles"));
        let report = run(&store, game.path()).unwrap();

        let backup = report.backup.unwrap();
        assert!(backup.join("fathom-features.properties").is_file());
        assert!(
            game.path()
                .join("config")
                .join("fathom-features.properties")
                .is_file(),
            "migration is a copy, not a move"
        );
    }

    #[test]
    fn what_could_not_be_mapped_is_reported_rather_than_dropped_silently() {
        let game = game_directory_with(&[("fathom-features.properties", FEATURES)]);
        let store = ProfileStore::new(game.path().join("profiles"));
        let report = run(&store, game.path()).unwrap();
        assert!(
            report
                .unmapped
                .contains(&"something.we.do.not.know".to_owned()),
            "unmapped keys: {:?}",
            report.unmapped
        );
        // And it is still in the backup, which is the copy that matters.
        let backup = report.backup.unwrap();
        let kept = std::fs::read_to_string(backup.join("fathom-features.properties")).unwrap();
        assert!(kept.contains("something.we.do.not.know"));
    }

    #[test]
    fn named_hud_layouts_become_profiles() {
        let game = game_directory_with(&[
            ("fathom-features.properties", FEATURES),
            ("fathom-hud.properties", HUD),
            ("fathom-hud-bedwars.properties", HUD),
        ]);
        let store = ProfileStore::new(game.path().join("profiles"));
        let report = run(&store, game.path()).unwrap();

        assert!(report.created.contains(&"Default".to_owned()));
        assert!(report.created.contains(&"Bedwars".to_owned()));
        let bedwars = store.load(&ProfileId::new("bedwars").unwrap()).unwrap();
        // It shares the modules; only the HUD was ever its own.
        assert_eq!(bedwars.modules.get("fps").unwrap().enabled, Some(true));
        assert!(bedwars.hud.contains_key("fps"));
    }

    #[test]
    fn running_twice_does_not_produce_a_second_default() {
        let game = game_directory_with(&[
            ("fathom-features.properties", FEATURES),
            ("fathom-hud.properties", HUD),
        ]);
        let store = ProfileStore::new(game.path().join("profiles"));

        let first = run(&store, game.path()).unwrap();
        assert!(!first.already_done);
        let before = store.list().profiles.len();

        let second = run(&store, game.path()).unwrap();
        assert!(second.already_done, "the second run must be a no-op");
        assert_eq!(store.list().profiles.len(), before);
    }

    #[test]
    fn an_edited_default_is_never_overwritten_by_migration() {
        let game = game_directory_with(&[("fathom-features.properties", FEATURES)]);
        let store = ProfileStore::new(game.path().join("profiles"));
        let mut mine = FathomProfile::default_profile();
        mine.client.hud_font = Some("Fathom".to_owned());
        store.save(&mine).unwrap();

        let report = run(&store, game.path()).unwrap();
        assert!(
            report.created.contains(&IMPORTED_NAME.to_owned()),
            "{:?}",
            report.created
        );
        let kept = store.load(&ProfileId::default_id()).unwrap();
        assert_eq!(
            kept.client.hud_font.as_deref(),
            Some("Fathom"),
            "the edited Default survived"
        );
        assert_eq!(store.list().profiles.len(), 2);
    }

    #[test]
    fn a_blank_default_is_filled_rather_than_duplicated() {
        let game = game_directory_with(&[("fathom-features.properties", FEATURES)]);
        let store = ProfileStore::new(game.path().join("profiles"));
        store.resolve_live().unwrap();
        run(&store, game.path()).unwrap();
        assert_eq!(
            store.list().profiles.len(),
            1,
            "a placeholder is filled, not duplicated"
        );
        assert_eq!(
            store.load(&ProfileId::default_id()).unwrap().client.hub_key,
            Some(54)
        );
    }

    #[test]
    fn a_second_collection_can_still_migrate_the_same_game() {
        let game = game_directory_with(&[("fathom-features.properties", FEATURES)]);
        let standalone = ProfileStore::new(game.path().join("fathom").join("profiles"));
        let root = tempfile::tempdir().unwrap();
        let launcher = ProfileStore::new(root.path().join("profiles"));
        assert!(!run(&standalone, game.path()).unwrap().already_done);
        assert!(run(&standalone, game.path()).unwrap().already_done);
        assert!(
            !run(&launcher, game.path()).unwrap().already_done,
            "a different collection has not seen this game"
        );
    }

    #[test]
    fn a_fresh_install_migrates_nothing_and_stays_migratable() {
        let game = tempfile::tempdir().unwrap();
        let store = ProfileStore::new(game.path().join("profiles"));
        let report = run(&store, game.path()).unwrap();
        assert!(report.created.is_empty());
        assert!(!report.already_done);
        assert!(
            store.client_state().migrated_from.is_none(),
            "an empty install has not migrated, so a later restore still can"
        );
    }

    #[test]
    fn an_impossible_hud_value_is_ignored_rather_than_failing_the_migration() {
        let game = game_directory_with(&[(
            "fathom-hud.properties",
            "widget.fps.opacity=9.0\nwidget.fps.scale=-1\nwidget.fps.x=4\n",
        )]);
        let store = ProfileStore::new(game.path().join("profiles"));
        run(&store, game.path()).unwrap();

        let profile = store.load(&ProfileId::default_id()).unwrap();
        let fps = profile.hud.get("fps").unwrap();
        assert!(
            (fps.opacity - 1.0).abs() < f32::EPSILON,
            "fell back to the default"
        );
        assert!((fps.scale - 1.0).abs() < f32::EPSILON);
        assert_eq!(fps.x, 4, "and the usable value beside it survived");
    }
}
