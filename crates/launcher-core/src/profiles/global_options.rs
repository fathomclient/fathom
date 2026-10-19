//! Launcher-wide Minecraft controls and settings.
//!
//! Installations keep separate worlds, mods and config directories, but a
//! player's controls, video/audio choices and accessibility settings are not a
//! property of a loader. Before launch, the newest `options.txt` from any known
//! installation becomes the target installation's options. Minecraft writes
//! on change/exit, so the next launch of any installation sees the latest copy.

use std::path::{Path, PathBuf};
use std::time::SystemTime;

use super::ProfileDirs;

const MAX_OPTIONS_BYTES: u64 = 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Sync {
    NoOptionsYet,
    AlreadyNewest,
    Copied { source: PathBuf },
}

pub fn sync_newest(installations: &[ProfileDirs], target: &ProfileDirs) -> Result<Sync, String> {
    let target_file = target.options_file();
    let mut newest: Option<(SystemTime, PathBuf)> = None;
    for dirs in installations {
        let candidate = dirs.options_file();
        let Ok(meta) = std::fs::metadata(&candidate) else {
            continue;
        };
        if !meta.is_file() || meta.len() == 0 || meta.len() > MAX_OPTIONS_BYTES {
            continue;
        }
        let Ok(modified) = meta.modified() else {
            continue;
        };
        if newest.as_ref().is_none_or(|(found, _)| modified > *found) {
            newest = Some((modified, candidate));
        }
    }
    let Some((_, source)) = newest else {
        return Ok(Sync::NoOptionsYet);
    };
    if same_file(&source, &target_file) {
        return Ok(Sync::AlreadyNewest);
    }

    let bytes = std::fs::read(&source).map_err(|error| {
        format!(
            "could not read global settings from {}: {error}",
            source.display()
        )
    })?;
    let text = std::str::from_utf8(&bytes)
        .map_err(|_| format!("{} is not a valid Minecraft options file", source.display()))?;
    if !text.lines().any(|line| line.contains(':')) {
        return Err(format!(
            "{} is not a valid Minecraft options file",
            source.display()
        ));
    }
    crate::fs_safety::atomic_write(&target_file, &bytes).map_err(|error| {
        format!(
            "could not apply global settings to {}: {error}",
            target_file.display()
        )
    })?;
    Ok(Sync::Copied { source })
}

fn same_file(a: &Path, b: &Path) -> bool {
    if a == b {
        return true;
    }
    match (a.canonicalize(), b.canonicalize()) {
        (Ok(a), Ok(b)) => a == b,
        _ => false,
    }
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::cast_possible_truncation
)]
mod tests {
    use super::*;
    use crate::paths::DataPaths;
    use crate::profiles::ProfileId;

    fn dirs(paths: &DataPaths, id: &str) -> ProfileDirs {
        let dirs = ProfileDirs::new(paths, &ProfileId::new(id).unwrap());
        dirs.ensure().unwrap();
        dirs
    }

    #[test]
    fn a_new_installation_inherits_the_existing_controls_and_settings() {
        let root = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(root.path());
        let fabric = dirs(&paths, "fabric");
        let forge = dirs(&paths, "forge");
        let options = b"music:0.0\nkey_key.forward:17\nguiScale:2\n";
        std::fs::write(fabric.options_file(), options).unwrap();

        let result = sync_newest(&[fabric.clone(), forge.clone()], &forge).unwrap();

        assert!(matches!(result, Sync::Copied { .. }));
        assert_eq!(std::fs::read(forge.options_file()).unwrap(), options);
    }

    #[test]
    fn the_newest_installation_wins_without_rewriting_itself() {
        let root = tempfile::tempdir().unwrap();
        let paths = DataPaths::rooted_at(root.path());
        let old = dirs(&paths, "old");
        let current = dirs(&paths, "current");
        std::fs::write(old.options_file(), b"fov:0.0\n").unwrap();
        std::thread::sleep(std::time::Duration::from_millis(20));
        std::fs::write(current.options_file(), b"fov:1.0\n").unwrap();

        assert_eq!(
            sync_newest(&[old, current.clone()], &current).unwrap(),
            Sync::AlreadyNewest
        );
        assert_eq!(
            std::fs::read_to_string(current.options_file()).unwrap(),
            "fov:1.0\n"
        );
    }
}
