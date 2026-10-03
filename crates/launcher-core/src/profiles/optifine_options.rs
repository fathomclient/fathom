//! OptiFine's own options file, `optionsof.txt`, read and -- on request -- edited.
//!
//! # Why the launcher reads it at all
//!
//! Fathom's view-direction profile (`ViewBenchmark` in the module) measured
//! where a 1.8.9 frame goes when the camera turns from the sky to the ground.
//! With OptiFine installed and its defaults left alone, 95-98% of the extra
//! time was terrain draw submission: roughly 0.6 microseconds of CPU per chunk
//! section per layer, ~580 draws looking 45 degrees down. OptiFine already
//! has the fix, **Render Regions**, which groups nearby sections into one
//! draw -- and ships it switched off.
//!
//! Measured on the reference machine (RTX 4060, 1920x1080, render distance 8,
//! two interleaved rounds per scene, OptiFine HD U M5):
//!
//! | scene | Regions off | Regions on |
//! | --- | --- | --- |
//! | horizon | 0.549 ms | 0.344 ms |
//! | 45 degrees down | 0.684 ms | 0.361 ms |
//! | straight down | 0.796 ms | 0.442 ms |
//! | sky | 0.281 ms | 0.272 ms |
//!
//! Screenshots of each scene with and without it differ only where the world
//! moves on its own (clouds, animated water, the frame counter).
//!
//! # The rules this follows
//!
//! It is OptiFine's setting, not Fathom's, so the launcher never changes it on
//! its own. [`render_regions`] reports; [`set_render_regions`] runs only when a
//! player presses the button that says what it does, and it edits that one key
//! in place -- every other line of the file survives byte for byte, the same
//! contract [`super::game_options::apply_recommended`] keeps for `options.txt`.

use std::path::PathBuf;

use crate::fs_safety;
use crate::profiles::ProfileDirs;

/// The key OptiFine writes Render Regions under.
pub const RENDER_REGIONS: &str = "ofRenderRegions";

/// Where OptiFine keeps its options: beside vanilla's, in the game directory.
pub fn file(dirs: &ProfileDirs) -> PathBuf {
    dirs.options_file().with_file_name("optionsof.txt")
}

/// Whether Render Regions is on, or `None` when the file or key is absent --
/// a profile OptiFine has never run in, where OptiFine's own default (off)
/// will apply.
pub fn render_regions(dirs: &ProfileDirs) -> Option<bool> {
    read_bool(dirs, RENDER_REGIONS)
}

fn read_bool(dirs: &ProfileDirs, wanted: &str) -> Option<bool> {
    let text = std::fs::read_to_string(file(dirs)).ok()?;
    text.lines().find_map(|line| {
        let (key, value) = line.split_once(':')?;
        (key.trim() == wanted).then(|| value.trim().parse().ok()).flatten()
    })
}

/// Set Render Regions, leaving every other line exactly as it was.
///
/// A missing file is created with just this key: OptiFine applies the keys it
/// finds and keeps its defaults for the rest, then rewrites the whole file the
/// first time it saves. Returns whether anything changed.
pub fn set_render_regions(dirs: &ProfileDirs, on: bool) -> Result<bool, fs_safety::FsError> {
    let path = file(dirs);
    let existing = std::fs::read_to_string(&path).unwrap_or_default();
    let value = if on { "true" } else { "false" };

    let mut found = false;
    let mut rewritten = String::with_capacity(existing.len() + 24);
    for line in existing.lines() {
        let key = line.split_once(':').map(|(key, _)| key.trim()).unwrap_or("");
        if key == RENDER_REGIONS {
            found = true;
            rewritten.push_str(RENDER_REGIONS);
            rewritten.push(':');
            rewritten.push_str(value);
        } else {
            rewritten.push_str(line);
        }
        rewritten.push('\n');
    }
    if !found {
        rewritten.push_str(RENDER_REGIONS);
        rewritten.push(':');
        rewritten.push_str(value);
        rewritten.push('\n');
    }

    if rewritten == existing {
        return Ok(false);
    }
    fs_safety::atomic_write(&path, rewritten.as_bytes())?;
    tracing::info!(path = %path.display(), on, "set OptiFine Render Regions at the player's request");
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
    fn an_absent_file_reports_nothing() {
        let (_temp, dirs) = dirs();
        assert_eq!(render_regions(&dirs), None);
    }

    #[test]
    fn the_setting_is_read_as_written() {
        let (_temp, dirs) = dirs();
        std::fs::write(file(&dirs), "ofFastRender:false\nofRenderRegions:false\n").unwrap();
        assert_eq!(render_regions(&dirs), Some(false));
    }

    #[test]
    fn turning_it_on_edits_one_line_and_keeps_the_rest() {
        let (_temp, dirs) = dirs();
        let before = "ofFogType:1\nofRenderRegions:false\nkey_of.key.zoom:46\nunknown line\n";
        std::fs::write(file(&dirs), before).unwrap();

        assert!(set_render_regions(&dirs, true).unwrap());
        let after = std::fs::read_to_string(file(&dirs)).unwrap();
        assert_eq!(after, "ofFogType:1\nofRenderRegions:true\nkey_of.key.zoom:46\nunknown line\n");
        assert_eq!(render_regions(&dirs), Some(true));
    }

    #[test]
    fn a_missing_key_is_appended() {
        let (_temp, dirs) = dirs();
        std::fs::write(file(&dirs), "ofFogType:1\n").unwrap();
        assert!(set_render_regions(&dirs, true).unwrap());
        assert_eq!(
            std::fs::read_to_string(file(&dirs)).unwrap(),
            "ofFogType:1\nofRenderRegions:true\n"
        );
    }

    #[test]
    fn setting_what_is_already_set_changes_nothing() {
        let (_temp, dirs) = dirs();
        std::fs::write(file(&dirs), "ofRenderRegions:true\n").unwrap();
        assert!(!set_render_regions(&dirs, true).unwrap());
    }
}
