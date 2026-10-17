//! What a profile's mods were the last time the game started properly.
//!
//! # Why keep this at all
//!
//! "It worked yesterday" is the most useful sentence in mod support, and
//! without a record it is a memory. So each launch writes down the exact set
//! of jars it laid out — ids, hashes, which were on — and the session that
//! follows says how it went. The last launch that *worked* is kept separately
//! from the last launch, which is what lets recovery say "these three changed
//! since the last time it started" instead of guessing.
//!
//! # What "worked" means
//!
//! Decided by the caller that watched the session (the desktop's launch
//! thread), not here: the game reached its main menu — for a Fathom profile,
//! the client module confirmed it loaded — and then either exited cleanly or
//! kept running for a few minutes. A crash after that is still recorded, as
//! the last launch, without erasing the set that got that far.
//!
//! # Safe launches
//!
//! A launch without external mods says nothing about the external mods, so it
//! never becomes the "last good" set. It is recorded as the last launch, with
//! `safe` set, so recovery can tell the user the base game itself is fine.
//!
//! # What is in the file
//!
//! Mod ids, file names, hashes, versions and dates. No account, no paths, no
//! log text. It lives beside `profile.json` and is read as text by the mod
//! store's garbage collection, so every hash named here keeps its bytes —
//! which is what makes "restore the last working set" possible offline.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use super::config::{now_seconds, ModSource, ProfileModConfiguration};
use crate::profiles::{Profile, ProfileDirs};

/// The file, beside `profile.json`.
pub const HISTORY_FILE: &str = "launch-history.json";

const SCHEMA: u32 = 1;

/// A ceiling on the file: a record is a few kilobytes even with every mod.
const MAX_BYTES: u64 = 1024 * 1024;

/// One mod as a launch laid it out.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModSnapshot {
    pub id: String,
    pub sha1: String,
    pub file_name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    /// The name an index gave it, when one did.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    pub enabled: bool,
    #[serde(default)]
    pub source: ModSource,
}

/// How a launch ended, as far as the launcher could tell.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "state", rename_all = "kebab-case")]
pub enum Outcome {
    /// Started and not yet judged. Left this way if the launcher was closed
    /// while the game ran, which is why it is not read as either answer.
    Started,
    /// Reached the main menu, then exited cleanly or kept running.
    Worked,
    /// Exited with an error, or before it was confirmed running.
    Failed { message: String },
}

impl Outcome {
    pub fn is_failure(&self) -> bool {
        matches!(self, Outcome::Failed { .. })
    }
}

/// One launch.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchRecord {
    /// When it started, in Unix seconds.
    pub at: u64,
    /// When it was judged, in Unix seconds.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub judged_at: Option<u64>,
    pub outcome: Outcome,
    /// True for a launch without external mods.
    #[serde(default)]
    pub safe: bool,
    pub minecraft: String,
    pub loader: String,
    pub mods: Vec<ModSnapshot>,
}

/// A profile's launch history: the last launch, and the last one that worked.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchHistory {
    #[serde(default = "schema")]
    pub schema: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last: Option<LaunchRecord>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_good: Option<LaunchRecord>,
}

fn schema() -> u32 {
    SCHEMA
}

impl Default for LaunchHistory {
    fn default() -> Self {
        Self {
            schema: SCHEMA,
            last: None,
            last_good: None,
        }
    }
}

/// Every mod in a configuration, as a launch would record it.
pub fn snapshot(mods: &ProfileModConfiguration) -> Vec<ModSnapshot> {
    mods.iter()
        .map(|entry| ModSnapshot {
            id: entry.id.as_str().to_owned(),
            sha1: entry.sha1.to_ascii_lowercase(),
            file_name: entry.file_name.clone(),
            version: entry.version.as_ref().map(|v| v.as_str().to_owned()),
            name: entry.origin.name.clone(),
            enabled: entry.enabled,
            source: entry.source,
        })
        .collect()
}

impl LaunchHistory {
    fn path(dirs: &ProfileDirs) -> std::path::PathBuf {
        dirs.root().join(HISTORY_FILE)
    }

    /// The profile's history. Missing or unreadable reads as empty: a history
    /// is a convenience, and a damaged one must never stop a launch.
    pub fn read(dirs: &ProfileDirs) -> Self {
        let path = Self::path(dirs);
        let Ok(metadata) = std::fs::metadata(&path) else {
            return Self::default();
        };
        if metadata.len() > MAX_BYTES {
            tracing::warn!("launch history is implausibly large; ignoring it");
            return Self::default();
        }
        match std::fs::read(&path)
            .ok()
            .and_then(|bytes| serde_json::from_slice::<Self>(&bytes).ok())
        {
            Some(history) if history.schema <= SCHEMA => history,
            _ => {
                tracing::warn!("launch history is unreadable; starting a new one");
                Self::default()
            }
        }
    }

    pub fn write(&self, dirs: &ProfileDirs) -> Result<(), crate::fs_safety::FsError> {
        let bytes = serde_json::to_vec_pretty(self).unwrap_or_default();
        crate::fs_safety::atomic_write(&Self::path(dirs), &bytes)
    }

    /// Record that a launch is starting with these mods.
    pub fn begin(
        dirs: &ProfileDirs,
        profile: &Profile,
        mods: &ProfileModConfiguration,
        safe: bool,
    ) {
        let mut history = Self::read(dirs);
        history.last = Some(LaunchRecord {
            at: now_seconds(),
            judged_at: None,
            outcome: Outcome::Started,
            safe,
            minecraft: profile.version_id.clone(),
            loader: profile.loader_kind().label().to_owned(),
            mods: snapshot(mods),
        });
        if let Err(error) = history.write(dirs) {
            tracing::warn!(%error, "could not record the launch; recovery will know less");
        }
    }

    /// Record how the last launch went. A launch that worked, with external
    /// mods, becomes the set recovery compares against.
    ///
    /// Idempotent: judging the same launch twice with the same answer changes
    /// nothing, and a launch already judged as having worked is not downgraded
    /// by a later crash — that crash is recorded as the last launch's outcome
    /// while the set that reached the game stays the last good one.
    pub fn judge(dirs: &ProfileDirs, outcome: Outcome) {
        let mut history = Self::read(dirs);
        let Some(last) = history.last.as_mut() else {
            return;
        };
        last.outcome = outcome;
        last.judged_at = Some(now_seconds());
        if last.outcome == Outcome::Worked && !last.safe {
            history.last_good = Some(last.clone());
        }
        if let Err(error) = history.write(dirs) {
            tracing::warn!(%error, "could not record how the launch went");
        }
    }
}

// ---------------------------------------------------------------------------
// What changed
// ---------------------------------------------------------------------------

/// How one mod differs from the last launch that worked.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum ChangeKind {
    /// Not in that launch at all.
    Added,
    /// In that launch, now gone from the profile.
    Removed,
    /// Different bytes under the same id.
    Updated {
        from: Option<String>,
        to: Option<String>,
    },
    /// Was off then, on now.
    Enabled,
    /// Was on then, off now.
    Disabled,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModChange {
    pub id: String,
    pub name: String,
    pub source: ModSource,
    pub change: ChangeKind,
    /// Whether this change can affect what the game loads: a mod that is off
    /// now and was off or absent then changed nothing that ran.
    pub affects_launch: bool,
}

fn display_name(name: Option<&String>, file_name: &str) -> String {
    name.cloned().unwrap_or_else(|| {
        file_name
            .strip_suffix(".jar")
            .unwrap_or(file_name)
            .to_owned()
    })
}

/// Every difference between a past launch and the configuration now.
///
/// Facts only, in the order of the configuration. Nothing here says which
/// change caused anything; see `recovery`.
pub fn changes_since(then: &LaunchRecord, now: &ProfileModConfiguration) -> Vec<ModChange> {
    let before: BTreeMap<&str, &ModSnapshot> =
        then.mods.iter().map(|m| (m.id.as_str(), m)).collect();
    let mut out = Vec::new();
    for entry in now.iter() {
        let name = display_name(entry.origin.name.as_ref(), &entry.file_name);
        let change = match before.get(entry.id.as_str()) {
            None => Some((ChangeKind::Added, entry.enabled)),
            Some(old) if !old.sha1.eq_ignore_ascii_case(&entry.sha1) => Some((
                ChangeKind::Updated {
                    from: old.version.clone(),
                    to: entry.version.as_ref().map(|v| v.as_str().to_owned()),
                },
                entry.enabled || old.enabled,
            )),
            Some(old) if !old.enabled && entry.enabled => Some((ChangeKind::Enabled, true)),
            Some(old) if old.enabled && !entry.enabled => Some((ChangeKind::Disabled, true)),
            Some(_) => None,
        };
        if let Some((change, affects_launch)) = change {
            out.push(ModChange {
                id: entry.id.as_str().to_owned(),
                name,
                source: entry.source,
                change,
                affects_launch,
            });
        }
    }
    for old in &then.mods {
        if now.iter().any(|entry| entry.id.as_str() == old.id) {
            continue;
        }
        out.push(ModChange {
            id: old.id.clone(),
            name: display_name(old.name.as_ref(), &old.file_name),
            source: old.source,
            change: ChangeKind::Removed,
            affects_launch: old.enabled,
        });
    }
    out
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing)]
mod tests {
    use super::*;
    use crate::mods::{ModId, ProfileMod};

    fn entry(id: &str, sha: char, enabled: bool) -> ProfileMod {
        let mut entry = ProfileMod::new(
            ModId::new(id).unwrap(),
            sha.to_string().repeat(40),
            format!("{id}.jar"),
        );
        entry.enabled = enabled;
        entry
    }

    fn record(mods: &ProfileModConfiguration) -> LaunchRecord {
        LaunchRecord {
            at: 1,
            judged_at: None,
            outcome: Outcome::Worked,
            safe: false,
            minecraft: "1.8.9".into(),
            loader: "Legacy Fabric".into(),
            mods: snapshot(mods),
        }
    }

    #[test]
    fn every_kind_of_change_is_found_and_nothing_else() {
        let mut then = ProfileModConfiguration::default();
        then.upsert(entry("same", 'a', true));
        then.upsert(entry("updated", 'b', true));
        then.upsert(entry("switched-on", 'c', false));
        then.upsert(entry("switched-off", 'd', true));
        then.upsert(entry("removed", 'e', true));
        let good = record(&then);

        let mut now = ProfileModConfiguration::default();
        now.upsert(entry("same", 'a', true));
        now.upsert(entry("updated", 'f', true));
        now.upsert(entry("switched-on", 'c', true));
        now.upsert(entry("switched-off", 'd', false));
        now.upsert(entry("added", '1', true));
        now.upsert(entry("added-but-off", '2', false));

        let changes = changes_since(&good, &now);
        let by_id: BTreeMap<&str, &ModChange> =
            changes.iter().map(|c| (c.id.as_str(), c)).collect();
        assert!(!by_id.contains_key("same"));
        assert!(matches!(
            by_id["updated"].change,
            ChangeKind::Updated { .. }
        ));
        assert_eq!(by_id["switched-on"].change, ChangeKind::Enabled);
        assert_eq!(by_id["switched-off"].change, ChangeKind::Disabled);
        assert_eq!(by_id["removed"].change, ChangeKind::Removed);
        assert_eq!(by_id["added"].change, ChangeKind::Added);
        assert!(by_id["added"].affects_launch);
        assert!(
            !by_id["added-but-off"].affects_launch,
            "a mod that is off changed nothing that ran"
        );
        assert_eq!(changes.len(), 6);
    }

    #[test]
    fn a_launch_that_worked_becomes_the_last_good_one_and_a_safe_one_never_does() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = crate::paths::DataPaths::rooted_at(tmp.path());
        let profile = Profile::new(crate::profiles::ProfileId::new("test").unwrap(), "Test");
        let dirs = ProfileDirs::new(&paths, &profile.id);
        std::fs::create_dir_all(dirs.root()).unwrap();
        let mut mods = ProfileModConfiguration::default();
        mods.upsert(entry("one", 'a', true));

        LaunchHistory::begin(&dirs, &profile, &mods, false);
        assert_eq!(
            LaunchHistory::read(&dirs).last.unwrap().outcome,
            Outcome::Started
        );
        LaunchHistory::judge(&dirs, Outcome::Worked);
        let good = LaunchHistory::read(&dirs).last_good.unwrap();
        assert_eq!(good.mods.len(), 1);

        // A later launch with a new mod crashes: the good set is kept.
        mods.upsert(entry("two", 'b', true));
        LaunchHistory::begin(&dirs, &profile, &mods, false);
        LaunchHistory::judge(
            &dirs,
            Outcome::Failed {
                message: "exit 1".into(),
            },
        );
        let history = LaunchHistory::read(&dirs);
        assert!(history.last.unwrap().outcome.is_failure());
        assert_eq!(history.last_good.unwrap().mods.len(), 1);

        // A safe launch that works says nothing about the external mods.
        LaunchHistory::begin(&dirs, &profile, &ProfileModConfiguration::default(), true);
        LaunchHistory::judge(&dirs, Outcome::Worked);
        assert_eq!(LaunchHistory::read(&dirs).last_good.unwrap().mods.len(), 1);
    }

    #[test]
    fn a_damaged_history_reads_as_empty_and_does_not_fail() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = crate::paths::DataPaths::rooted_at(tmp.path());
        let profile = Profile::new(crate::profiles::ProfileId::new("test").unwrap(), "Test");
        let dirs = ProfileDirs::new(&paths, &profile.id);
        std::fs::create_dir_all(dirs.root()).unwrap();
        std::fs::write(dirs.root().join(HISTORY_FILE), b"{ not json").unwrap();
        assert_eq!(LaunchHistory::read(&dirs), LaunchHistory::default());
        // And recording over it works.
        LaunchHistory::begin(&dirs, &profile, &ProfileModConfiguration::default(), false);
        assert!(LaunchHistory::read(&dirs).last.is_some());
    }
}
