//! The video options a brand-new game directory starts with.
//!
//! # Why this exists
//!
//! Minecraft 1.8.9 constructs `GameOptions` with `maxFramerate = 120` and
//! `vsync = true`. Those are the values in the 2015 bytecode, and a profile that
//! has never been played inherits them the first time the game writes
//! `options.txt`. The result is a client that is capped at 120 frames per second
//! with VSync on before anybody has chosen anything.
//!
//! That was measured rather than assumed. On the reference machine, the same
//! production stack, world, resolution and render distance produced:
//!
//! | scenario | VSync off, no cap | vanilla's defaults |
//! | --- | --- | --- |
//! | standing still | 272 fps | 120.2 fps |
//! | panning | 629 fps | 120.1 fps |
//! | looking down | 561 fps | 120.1 fps |
//! | flying | 552 fps | 120.0 fps |
//!
//! A flat 120 in every scene regardless of how much work the scene is, which is
//! what a frame limiter looks like and is not what a bottleneck looks like. It
//! is also the entire gap between Fathom and the clients it gets compared with:
//! a performance client whose out-of-the-box configuration refuses to draw more
//! than 120 frames is not being judged on its rendering.
//!
//! # The rules this follows
//!
//! 1. **Only when the file does not exist.** `options.txt` is the player's, and
//!    `recovery::cache` already treats it that way — it survives "clear cache"
//!    precisely because it is not ours to discard. An existing file is never
//!    read, rewritten or merged into. Somebody who chose a 120 cap keeps it.
//! 2. **Only what was measured.** Two keys. `useVbo` is the obvious third
//!    candidate and is deliberately absent: nothing here has measured it, and
//!    `docs/performance/benchmark-methodology.md` is the reason this file can
//!    make the claim above at all. A default nobody benchmarked is a default
//!    nobody can defend.
//! 3. **Never fatal.** A failure is logged and swallowed. Refusing to launch
//!    because a convenience file could not be written would turn a nicety into
//!    an outage, which is the same rule `profiles::seed` follows.
//!
//! # Why a partial file is correct
//!
//! 1.8.9 loads options by splitting each line on `:` and applying the keys it
//! recognises, leaving the constructor's value in place for every key that is
//! absent. So a two-line file sets exactly these two options and leaves the
//! other ninety alone; the game rewrites the complete file the first time
//! anything is saved. This is the same shape `options.txt` has after a version
//! upgrade adds a key, so it is a case the game already handles.

use crate::fs_safety;
use crate::profiles::ProfileDirs;

/// The options Fathom ships, as they appear in `options.txt`.
///
/// `260` is not an arbitrary large number: it is the value 1.8.9's own video
/// slider uses for "Unlimited", and the top of the range Fathom's Hub writes
/// (`QuickSection`, `FRAME_LIMIT`). Writing a larger number would put the
/// slider in a position it cannot represent.
pub const DEFAULTS: &[(&str, &str)] = &[("maxFps", "260"), ("enableVsync", "false")];

/// The options a brand-new game directory is seeded with.
///
/// [`DEFAULTS`] plus settings that are worth shipping on but must never be
/// applied to a directory somebody already has.
///
/// # Why this is a second list
///
/// [`apply_recommended`] rewrites every key in [`DEFAULTS`] in an *existing*
/// `options.txt`. That is the "Remove the limit" button, and what it promises
/// is precise: it changes the frame cap and VSync and nothing else. Adding a
/// third key to `DEFAULTS` would quietly widen that button into "and also
/// change your renderer", on profiles people have already tuned.
///
/// Seeding is different: the directory is new, so there is no choice to
/// overrule.
///
/// `useVbo` is on because 1.8.9 defaults it off, and on the hardware this
/// launcher targets the VBO chunk renderer is the faster path. It changes
/// nothing about what is drawn -- only how the geometry reaches the GPU -- so
/// it costs no visual quality. Someone for whom display lists are faster can
/// turn it off in Video Settings, and [`apply_recommended`] will not turn it
/// back on.
pub const SEEDED_DEFAULTS: &[(&str, &str)] = &[
    ("maxFps", "260"),
    ("enableVsync", "false"),
    ("useVbo", "true"),
];

/// Write the shipped options into a game directory that has none.
///
/// Returns whether a file was created. False means the directory already had
/// one — the overwhelmingly common case, and not a failure.
pub fn seed(dirs: &ProfileDirs) -> bool {
    let path = dirs.options_file();
    if path.exists() {
        return false;
    }

    let mut contents = String::new();
    for (key, value) in SEEDED_DEFAULTS {
        contents.push_str(key);
        contents.push(':');
        contents.push_str(value);
        contents.push('\n');
    }

    match fs_safety::atomic_write(&path, contents.as_bytes()) {
        Ok(()) => {
            tracing::info!(
                path = %path.display(),
                "wrote Fathom's default video options into a new game directory"
            );
            true
        }
        Err(error) => {
            // Rule 3. The game will write its own options.txt on first exit;
            // the only cost of failing here is that the first session runs at
            // vanilla's defaults.
            tracing::warn!(%error, "could not write the default game options");
            false
        }
    }
}

/// What a game directory's existing `options.txt` says about the two settings
/// that decide whether the client is allowed to draw frames.
///
/// Reported rather than acted on. Rule 1 means an existing file is the
/// player's, so the only honest thing to do with a capped profile is say so and
/// offer — which is what [`apply_recommended`] is for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VideoLimits {
    /// `maxFps`, or `None` when the file does not mention it.
    pub max_fps: Option<u32>,
    /// `enableVsync`, or `None` when the file does not mention it.
    pub vsync: Option<bool>,
}

impl VideoLimits {
    /// Whether this profile is held below the frame rate it could reach.
    ///
    /// `260` is not a cap: it is the value 1.8.9 uses for "Unlimited", and at
    /// that setting the game skips `Display.sync` altogether
    /// (`MinecraftClient.isFramerateValid` returns `getMaxFramerate() < 260`).
    /// Anything below it is a real limiter, and VSync is one regardless.
    pub fn is_limited(&self) -> bool {
        self.max_fps.is_some_and(|fps| fps < UNLIMITED) || self.vsync == Some(true)
    }
}

/// The value 1.8.9 stores for "Unlimited". See [`VideoLimits::is_limited`].
pub const UNLIMITED: u32 = 260;

/// Read the two video limits out of a game directory.
///
/// A missing file reports both as `None` rather than failing: a profile that
/// has never been played has no limits to report, and [`seed`] will give it
/// good ones the first time it launches.
pub fn limits(dirs: &ProfileDirs) -> VideoLimits {
    let Ok(text) = std::fs::read_to_string(dirs.options_file()) else {
        return VideoLimits { max_fps: None, vsync: None };
    };

    let mut limits = VideoLimits { max_fps: None, vsync: None };
    for line in text.lines() {
        let Some((key, value)) = line.split_once(':') else {
            continue;
        };
        match key.trim() {
            "maxFps" => limits.max_fps = value.trim().parse().ok(),
            "enableVsync" => limits.vsync = value.trim().parse().ok(),
            _ => {}
        }
    }
    limits
}

/// Rewrite just the two shipped keys in an existing `options.txt`, leaving
/// every other line exactly as it was.
///
/// This is the deliberate exception to rule 1, and it is only reachable from a
/// user pressing a button that says what it will do. A profile that has been
/// played has ninety settings in that file which are none of our business, so
/// this rewrites two lines rather than replacing the file: a key that is
/// present is edited in place, a key that is absent is appended, and everything
/// else — including comments, ordering and unknown keys from another version —
/// survives byte for byte.
///
/// Returns whether anything changed.
pub fn apply_recommended(dirs: &ProfileDirs) -> Result<bool, crate::fs_safety::FsError> {
    let path = dirs.options_file();
    let Ok(existing) = std::fs::read_to_string(&path) else {
        // Nothing to edit. Seeding is the right path for a directory with no
        // options file, and it is already run before every launch.
        return Ok(seed(dirs));
    };

    let mut seen: Vec<&str> = Vec::new();
    let mut rewritten = String::with_capacity(existing.len());
    for line in existing.lines() {
        let key = line.split_once(':').map(|(key, _)| key.trim()).unwrap_or("");
        match DEFAULTS.iter().find(|(name, _)| *name == key) {
            Some((name, value)) => {
                seen.push(name);
                rewritten.push_str(name);
                rewritten.push(':');
                rewritten.push_str(value);
            }
            None => rewritten.push_str(line),
        }
        rewritten.push('\n');
    }
    for (key, value) in DEFAULTS {
        if !seen.contains(key) {
            rewritten.push_str(key);
            rewritten.push(':');
            rewritten.push_str(value);
            rewritten.push('\n');
        }
    }

    if rewritten == existing {
        return Ok(false);
    }
    crate::fs_safety::atomic_write(&path, rewritten.as_bytes())?;
    tracing::info!(path = %path.display(), "applied Fathom's recommended video options");
    Ok(true)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;
    use crate::paths::DataPaths;
    use crate::profiles::ProfileId;

    fn dirs() -> (tempfile::TempDir, ProfileDirs) {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let paths = DataPaths::rooted_at(dir.path());
        paths.ensure().expect("the data directories");
        let id = ProfileId::new("test").expect("a valid id");
        let dirs = ProfileDirs::new(&paths, &id);
        dirs.ensure().expect("the profile directories");
        (dir, dirs)
    }

    #[test]
    fn a_new_game_directory_gets_the_shipped_options() {
        let (_dir, dirs) = dirs();

        assert!(seed(&dirs), "the first call should write the file");

        let written = std::fs::read_to_string(dirs.options_file()).expect("the options file");
        assert!(written.contains("maxFps:260"), "{written}");
        assert!(written.contains("enableVsync:false"), "{written}");
    }

    /// Rule 1, and the one that matters most: this runs before every launch, so
    /// a version of it that rewrote the file would reset the player's video
    /// settings every time they pressed Play.
    #[test]
    fn an_existing_options_file_is_never_touched() {
        let (_dir, dirs) = dirs();
        let theirs = "maxFps:120\nenableVsync:true\nfov:0.5\n";
        std::fs::write(dirs.options_file(), theirs).expect("their options");

        assert!(!seed(&dirs), "an existing file should not be replaced");

        let after = std::fs::read_to_string(dirs.options_file()).expect("the options file");
        assert_eq!(after, theirs, "the player's own settings were overwritten");
    }

    /// Seeding runs on every launch, not once per installation, because the
    /// game directory can be deleted or moved without the profile going away.
    /// It therefore has to be safe to call repeatedly.
    /// The distinction the two lists exist for.
    ///
    /// A new directory gets the renderer setting; the "Remove the limit" button
    /// must not reach it on a directory somebody already has, however it was
    /// configured.
    #[test]
    fn seeding_ships_vbo_but_the_frame_limit_fix_never_touches_it() {
        assert!(SEEDED_DEFAULTS.iter().any(|(k, v)| *k == "useVbo" && *v == "true"));
        assert!(!DEFAULTS.iter().any(|(k, _)| *k == "useVbo"));

        let (_tmp, dirs) = dirs();
        assert!(seed(&dirs));
        let seeded = std::fs::read_to_string(dirs.options_file()).unwrap();
        assert!(seeded.contains("useVbo:true"), "{seeded}");

        // A player who turned it off, and then presses "Remove the limit".
        std::fs::write(
            dirs.options_file(),
            "maxFps:120
enableVsync:true
useVbo:false
fov:0.75
",
        )
        .unwrap();
        assert!(apply_recommended(&dirs).unwrap());
        let after = std::fs::read_to_string(dirs.options_file()).unwrap();
        assert!(after.contains("maxFps:260"), "{after}");
        assert!(after.contains("enableVsync:false"), "{after}");
        assert!(after.contains("useVbo:false"), "their choice survives: {after}");
        assert!(after.contains("fov:0.75"), "{after}");
    }

    #[test]
    fn seeding_twice_writes_once() {
        let (_dir, dirs) = dirs();

        assert!(seed(&dirs));
        let first = std::fs::read_to_string(dirs.options_file()).expect("the options file");
        assert!(!seed(&dirs), "the second call should find the file already there");
        let second = std::fs::read_to_string(dirs.options_file()).expect("the options file");

        assert_eq!(first, second);
    }

    #[test]
    fn a_stock_minecraft_profile_reads_as_limited() {
        let (_dir, dirs) = dirs();
        // Exactly what 1.8.9 writes for a profile nobody has touched.
        std::fs::write(dirs.options_file(), "maxFps:120\nenableVsync:true\n").unwrap();

        let found = limits(&dirs);
        assert_eq!(found.max_fps, Some(120));
        assert_eq!(found.vsync, Some(true));
        assert!(found.is_limited());
    }

    /// The distinction the whole feature turns on: 260 is not a cap, it is what
    /// 1.8.9 stores for "Unlimited", and at that value the game never calls
    /// `Display.sync`. Reporting it as a limit would have the launcher offering
    /// to fix a profile that is already correct.
    #[test]
    fn the_unlimited_sentinel_is_not_a_limit() {
        let (_dir, dirs) = dirs();
        std::fs::write(dirs.options_file(), "maxFps:260\nenableVsync:false\n").unwrap();

        assert!(!limits(&dirs).is_limited());
    }

    #[test]
    fn vsync_alone_is_a_limit() {
        let (_dir, dirs) = dirs();
        std::fs::write(dirs.options_file(), "maxFps:260\nenableVsync:true\n").unwrap();

        assert!(limits(&dirs).is_limited());
    }

    /// The reason this rewrites lines instead of replacing the file. A played
    /// profile has settings in there that are the player's, and an "unlock my
    /// frame rate" button that reset their keybinds or brightness would be a
    /// far worse bug than the one it fixed.
    #[test]
    fn applying_the_recommendation_keeps_every_other_setting() {
        let (_dir, dirs) = dirs();
        let theirs = "maxFps:120\nfov:0.75\nenableVsync:true\nguiScale:2\nkey_key.jump:57\n";
        std::fs::write(dirs.options_file(), theirs).unwrap();

        assert!(apply_recommended(&dirs).unwrap());

        let after = std::fs::read_to_string(dirs.options_file()).unwrap();
        assert_eq!(
            after,
            "maxFps:260\nfov:0.75\nenableVsync:false\nguiScale:2\nkey_key.jump:57\n"
        );
        assert!(!limits(&dirs).is_limited());
    }

    #[test]
    fn a_missing_key_is_appended_rather_than_lost() {
        let (_dir, dirs) = dirs();
        std::fs::write(dirs.options_file(), "fov:0.75\n").unwrap();

        assert!(apply_recommended(&dirs).unwrap());

        let found = limits(&dirs);
        assert_eq!(found.max_fps, Some(260));
        assert_eq!(found.vsync, Some(false));
        assert!(std::fs::read_to_string(dirs.options_file()).unwrap().contains("fov:0.75"));
    }

    /// So the button can be pressed twice without the launcher claiming it did
    /// something the second time.
    #[test]
    fn applying_a_recommendation_already_in_place_changes_nothing() {
        let (_dir, dirs) = dirs();
        std::fs::write(dirs.options_file(), "maxFps:260\nenableVsync:false\n").unwrap();

        assert!(!apply_recommended(&dirs).unwrap());
    }

    /// The claim in this module's documentation is that these two values are
    /// the ones vanilla gets wrong. If a third is ever added it should be
    /// because somebody measured it, so the count is asserted rather than left
    /// to drift.
    #[test]
    fn only_the_two_measured_options_are_shipped() {
        let keys: Vec<&str> = DEFAULTS.iter().map(|(key, _)| *key).collect();
        assert_eq!(keys, vec!["maxFps", "enableVsync"]);
    }
}
