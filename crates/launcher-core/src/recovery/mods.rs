//! After a launch fails: what changed, what is broken, and what the logs say.
//!
//! # No accusations without evidence
//!
//! A crash with three new mods installed has three suspects and, usually, one
//! culprit. Guessing which is how a launcher teaches people to distrust it. So
//! this module reports three separate kinds of fact and never merges them into
//! a verdict:
//!
//! 1. **What changed** since the last launch that worked (`mods::history`).
//!    Changes are correlation: listed as "changed since", never "caused".
//! 2. **What is broken on disk or in the configuration**: a jar missing from
//!    the store, a jar whose bytes no longer match their hash, a library that
//!    a mod needs and that is gone or off. These are certain.
//! 3. **What the game's own logs say**: the lines in the launch log, the game
//!    log and the newest crash report that the loader and the JVM use to
//!    report mod failures, quoted, with the mods they actually name. A mod is
//!    "named by the log" only when its file name or id appears on such a line.
//!
//! # And the way back
//!
//! [`restore_last_good`] puts the configuration back to the set that last
//! worked: additions and newly enabled mods switched off (not deleted),
//! updated mods put back to the build that ran, removed ones re-added when
//! their bytes are still here. Every change it makes is listed for the user.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use serde::Serialize;

use crate::mods::history::{changes_since, ChangeKind, LaunchHistory, ModChange, Outcome};
use crate::mods::{
    DisabledReason, ModId, ModSource, ModStore, ModVersion, NameLookup, ProfileMod,
    ProfileModConfiguration, Severity,
};
use crate::profiles::{Profile, ProfileDirs};

/// How much of the end of each log is read. Failures are at the end.
const TAIL_BYTES: u64 = 512 * 1024;
/// How many log lines are quoted at most.
const MAX_EVIDENCE: usize = 12;
/// How long one quoted line may be.
const MAX_LINE: usize = 320;

/// A mod whose bytes are not what the profile recorded.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileProblem {
    pub id: String,
    pub name: String,
    /// Whether Fathom can fetch the same build again (it came from an index
    /// and its build is recorded).
    pub repairable: bool,
}

/// One line from a log, and the mods it names.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Evidence {
    /// Which log: `launch`, `game` or `crash report`.
    pub source: &'static str,
    pub line: String,
    /// Display names of the profile's mods this line names.
    pub names: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LastLaunch {
    pub at: u64,
    pub outcome: Outcome,
    pub safe: bool,
}

/// Everything recovery can say about a profile's mods.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModRecoveryReport {
    pub last_launch: Option<LastLaunch>,
    /// When the last launch that worked started, if one is recorded.
    pub last_good_at: Option<u64>,
    /// Changes since then that affect what the game loads.
    pub changes: Vec<ModChange>,
    pub missing: Vec<FileProblem>,
    pub damaged: Vec<FileProblem>,
    /// Problems the resolver finds in the set as it is now, as sentences.
    pub problems: Vec<String>,
    pub evidence: Vec<Evidence>,
    /// Ids of mods the evidence names.
    pub named_by_logs: Vec<String>,
    /// Whether restoring the last working set would change anything.
    pub can_restore: bool,
    /// How many enabled mods are the user's rather than Fathom's own.
    pub external_enabled: usize,
}

fn display_name(entry: &ProfileMod) -> String {
    entry.origin.name.clone().unwrap_or_else(|| {
        entry
            .file_name
            .strip_suffix(".jar")
            .unwrap_or(&entry.file_name)
            .to_owned()
    })
}

fn is_external(entry: &ProfileMod) -> bool {
    entry.source != ModSource::FirstParty
}

/// The newest file in `dir` whose name starts with `prefix`.
fn newest(dir: &Path, prefix: &str) -> Option<PathBuf> {
    std::fs::read_dir(dir)
        .ok()?
        .flatten()
        .filter(|entry| {
            entry
                .file_name()
                .to_str()
                .is_some_and(|name| name.starts_with(prefix))
        })
        .filter_map(|entry| Some((entry.metadata().ok()?.modified().ok()?, entry.path())))
        .max_by_key(|(modified, _)| *modified)
        .map(|(_, path)| path)
}

/// The end of a log, as lossy text, only if it was written after `since`.
fn tail(path: &Path, since: u64) -> Option<String> {
    use std::io::{Read, Seek, SeekFrom};
    let metadata = std::fs::metadata(path).ok()?;
    let modified = metadata
        .modified()
        .ok()?
        .duration_since(std::time::UNIX_EPOCH)
        .ok()?
        .as_secs();
    // A log older than the launch is about some other launch.
    if since > 0 && modified + 2 < since {
        return None;
    }
    let mut file = std::fs::File::open(path).ok()?;
    let start = metadata.len().saturating_sub(TAIL_BYTES);
    file.seek(SeekFrom::Start(start)).ok()?;
    let mut bytes = Vec::new();
    file.take(TAIL_BYTES).read_to_end(&mut bytes).ok()?;
    Some(String::from_utf8_lossy(&bytes).into_owned())
}

/// Whether a log line is one the loader or the JVM uses to report a mod
/// failure. Deliberately specific: "error" alone matches half of every log.
fn is_failure_line(line: &str) -> bool {
    const MARKERS: &[&str] = &[
        "incompatible mod set",
        "some of your mods are incompatible",
        "- mod '",
        "requires version",
        "requires any version",
        "is incompatible with",
        "mixin apply failed",
        "mixinapplyerror",
        "invalidmixinexception",
        "mixin transformation of",
        "from mod ",
        "could not execute entrypoint",
        "entrypointexception",
        "noclassdeffounderror",
        "classnotfoundexception",
        "nosuchmethoderror",
        "nosuchfielderror",
        "duplicate mod",
        "found duplicate",
        "failed to load mod",
        "zipexception",
        "zip end header not found",
        "invalid loc header",
        "outofmemoryerror",
        "exception in thread \"main\"",
        "caused by:",
        "description:",
        // Forge's own wording (FML, 1.8.9).
        "caught exception from",
        "missingmodsexception",
        "duplicatemodsfoundexception",
        "wrongminecraftversionexception",
        "loaderexception",
        "the mod list is:",
        "fml has detected",
        "has failed to load correctly",
    ];
    let lower = line.to_ascii_lowercase();
    MARKERS.iter().any(|marker| lower.contains(marker))
}

/// Words that identify an entry in a log line, longest first.
fn identifiers(entry: &ProfileMod) -> Vec<String> {
    let mut out = BTreeSet::new();
    out.insert(entry.file_name.to_ascii_lowercase());
    let stem = entry
        .file_name
        .strip_suffix(".jar")
        .unwrap_or(&entry.file_name)
        .to_ascii_lowercase();
    out.insert(stem);
    // Short ids ("fps", "map") would match ordinary words; only distinctive
    // ones count.
    if entry.id.as_str().len() >= 5 {
        out.insert(entry.id.as_str().to_ascii_lowercase());
    }
    if let Some(name) = &entry.origin.name {
        if name.len() >= 5 {
            out.insert(name.to_ascii_lowercase());
        }
    }
    let mut out: Vec<String> = out.into_iter().filter(|s| s.len() >= 4).collect();
    out.sort_by_key(|s| std::cmp::Reverse(s.len()));
    out
}

/// Whether `word` occurs in `line` with no letter or digit on either side.
fn contains_word(line: &str, word: &str) -> bool {
    let mut from = 0;
    while let Some(found) = line.get(from..).and_then(|rest| rest.find(word)) {
        let start = from + found;
        let end = start + word.len();
        let before = line[..start].chars().next_back();
        let after = line.get(end..).and_then(|rest| rest.chars().next());
        let boundary = |c: Option<char>| c.is_none_or(|c| !c.is_ascii_alphanumeric());
        if boundary(before) && boundary(after) {
            return true;
        }
        from = end;
    }
    false
}

/// Scrub a line for display: no paths to the user's home, no tokens, no
/// player name. Same rules as a diagnostics report.
fn clean(line: &str) -> String {
    let scrubbed = crate::recovery::diagnostics::anonymise(&crate::logging::scrub(line.trim()));
    let mut out: String = scrubbed.chars().take(MAX_LINE).collect();
    if let Some(home) = std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME")) {
        let home = home.to_string_lossy().into_owned();
        if !home.is_empty() {
            out = out.replace(&home, "<HOME>");
        }
    }
    out
}

/// Quote the failure lines from the logs written for the last launch.
fn gather_evidence(
    dirs: &ProfileDirs,
    since: u64,
    mods: &ProfileModConfiguration,
) -> Vec<Evidence> {
    let sources: Vec<(&'static str, Option<PathBuf>)> = vec![
        (
            "crash report",
            newest(&dirs.minecraft_dir().join("crash-reports"), "crash-"),
        ),
        ("launch", newest(&dirs.logs_dir(), "launch-")),
        (
            "game",
            Some(dirs.minecraft_dir().join("logs").join("latest.log")),
        ),
    ];
    let external: Vec<(&ProfileMod, Vec<String>)> = mods
        .iter()
        .filter(|entry| is_external(entry))
        .map(|entry| (entry, identifiers(entry)))
        .collect();

    let mut seen = BTreeSet::new();
    let mut out = Vec::new();
    for (source, path) in sources {
        let Some(text) = path.as_deref().and_then(|path| tail(path, since)) else {
            continue;
        };
        for line in text.lines().filter(|line| is_failure_line(line)) {
            let cleaned = clean(line);
            if cleaned.is_empty() || !seen.insert(cleaned.clone()) {
                continue;
            }
            let lower = cleaned.to_ascii_lowercase();
            let names: Vec<String> = external
                .iter()
                .filter(|(_, words)| words.iter().any(|word| contains_word(&lower, word)))
                .map(|(entry, _)| display_name(entry))
                .collect();
            out.push(Evidence {
                source,
                line: cleaned,
                names,
            });
            if out.len() >= MAX_EVIDENCE {
                return out;
            }
        }
    }
    out
}

/// Everything recovery can say about this profile's mods, from disk alone.
pub fn analyse(profile: &Profile, dirs: &ProfileDirs, store: &ModStore) -> ModRecoveryReport {
    let history = LaunchHistory::read(dirs);
    let mods = &profile.mods;

    let changes: Vec<ModChange> = history
        .last_good
        .as_ref()
        .map(|good| changes_since(good, mods))
        .unwrap_or_default()
        .into_iter()
        .filter(|change| change.affects_launch)
        .collect();

    let mut missing = Vec::new();
    let mut damaged = Vec::new();
    for entry in mods.enabled() {
        let repairable = entry.origin.repository.is_some()
            && entry.origin.project.is_some()
            && entry.origin.version_id.is_some();
        let problem = || FileProblem {
            id: entry.id.as_str().to_owned(),
            name: display_name(entry),
            repairable,
        };
        if !store.contains(&entry.sha1) {
            missing.push(problem());
        } else if !store.verify(&entry.sha1).unwrap_or(false) {
            damaged.push(problem());
        }
    }

    let resolution = crate::mods::ModEngine::new(store.paths().clone()).resolve(
        mods,
        &profile.version_id,
        profile.loader_kind(),
    );
    let names = NameLookup::from_definitions(&crate::mods::catalog::entries()).with_profile(mods);
    let problems: Vec<String> = resolution
        .problems
        .iter()
        .filter(|problem| problem.severity() >= Severity::Warning)
        .map(|problem| problem.message(&names))
        .collect();

    let since = history.last.as_ref().map_or(0, |last| last.at);
    let evidence = if history
        .last
        .as_ref()
        .is_some_and(|last| last.outcome.is_failure())
    {
        gather_evidence(dirs, since, mods)
    } else {
        Vec::new()
    };
    let mentioned: BTreeSet<String> = evidence
        .iter()
        .flat_map(|evidence| evidence.names.iter().cloned())
        .collect();
    let named_by_logs = mods
        .iter()
        .filter(|entry| mentioned.contains(&display_name(entry)))
        .map(|entry| entry.id.as_str().to_owned())
        .collect();

    let can_restore = history.last_good.as_ref().is_some_and(|good| {
        let mut preview = mods.clone();
        restore_last_good(&mut preview, good, |sha| store.contains(sha)).changed()
    });

    ModRecoveryReport {
        last_launch: history.last.as_ref().map(|last| LastLaunch {
            at: last.at,
            outcome: last.outcome.clone(),
            safe: last.safe,
        }),
        last_good_at: history.last_good.as_ref().map(|good| good.at),
        changes,
        missing,
        damaged,
        problems,
        evidence,
        named_by_logs,
        can_restore,
        external_enabled: mods.enabled().filter(|entry| is_external(entry)).count(),
    }
}

// ---------------------------------------------------------------------------
// Putting the last working set back
// ---------------------------------------------------------------------------

/// What restoring did, by display name.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RestoreReport {
    /// Added or switched on since; now off (not removed).
    pub switched_off: Vec<String>,
    /// Updated since; put back to the build that ran.
    pub reverted: Vec<String>,
    /// On then, off now; switched back on.
    pub switched_on: Vec<String>,
    /// Removed since; added back from the store.
    pub re_added: Vec<String>,
    /// Could not be put back because their bytes are gone; switched off.
    pub unavailable: Vec<String>,
}

impl RestoreReport {
    pub fn changed(&self) -> bool {
        !(self.switched_off.is_empty()
            && self.reverted.is_empty()
            && self.switched_on.is_empty()
            && self.re_added.is_empty()
            && self.unavailable.is_empty())
    }
}

/// Make `mods` load what `good` loaded, as far as the store allows.
///
/// Never deletes an entry: something added since is switched off, with a
/// reason the user can read, and stays one switch away. Fathom's own
/// components are left alone: the launcher keeps those current itself, and
/// "restoring" an old client jar would fight that.
pub fn restore_last_good(
    mods: &mut ProfileModConfiguration,
    good: &crate::mods::history::LaunchRecord,
    has_bytes: impl Fn(&str) -> bool,
) -> RestoreReport {
    let mut report = RestoreReport::default();
    let now = crate::mods::config::now_seconds();
    let detail = "Switched off by Restore last working mods: it was added or switched on after \
                  the last launch that worked."
        .to_owned();
    for change in changes_since(good, mods) {
        if change.source == ModSource::FirstParty || !change.affects_launch {
            continue;
        }
        let Ok(id) = ModId::new(&change.id) else {
            continue;
        };
        let then = good.mods.iter().find(|m| m.id == change.id);
        match change.change {
            ChangeKind::Added | ChangeKind::Enabled => {
                if let Some(entry) = mods.get_mut(&id) {
                    entry.disable(DisabledReason::Recovery {
                        detail: detail.clone(),
                    });
                    report.switched_off.push(change.name);
                }
            }
            ChangeKind::Updated { .. } => {
                let (Some(then), Some(entry)) = (then, mods.get_mut(&id)) else {
                    continue;
                };
                if has_bytes(&then.sha1) {
                    if entry
                        .previous
                        .iter()
                        .any(|build| build.sha1.eq_ignore_ascii_case(&then.sha1))
                    {
                        let _ = entry.restore_previous(&then.sha1, now);
                    } else {
                        let current = entry.clone();
                        entry.sha1.clone_from(&then.sha1);
                        entry.file_name.clone_from(&then.file_name);
                        entry.version = then.version.as_deref().map(ModVersion::new);
                        entry.origin.version_id = None;
                        entry.succeed(&current, now);
                    }
                    if then.enabled {
                        entry.enable();
                    }
                    report.reverted.push(change.name);
                } else {
                    entry.disable(DisabledReason::Recovery {
                        detail: "Switched off by Restore last working mods: the build that last \
                                 worked is no longer on this computer."
                            .to_owned(),
                    });
                    report.unavailable.push(change.name);
                }
            }
            ChangeKind::Disabled => {
                if let Some(entry) = mods.get_mut(&id) {
                    entry.enable();
                    report.switched_on.push(change.name);
                }
            }
            ChangeKind::Removed => {
                let Some(then) = then else { continue };
                if has_bytes(&then.sha1) {
                    let mut entry =
                        ProfileMod::new(id, &then.sha1, &then.file_name).from_source(then.source);
                    entry.version = then.version.as_deref().map(ModVersion::new);
                    entry.origin.name.clone_from(&then.name);
                    entry.enabled = then.enabled;
                    mods.upsert(entry);
                    report.re_added.push(change.name);
                } else {
                    report.unavailable.push(change.name);
                }
            }
        }
    }
    report
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing)]
mod tests {
    use super::*;
    use crate::mods::history::{snapshot, LaunchRecord};

    fn entry(id: &str, sha: char, enabled: bool) -> ProfileMod {
        let mut entry = ProfileMod::new(
            ModId::new(id).unwrap(),
            sha.to_string().repeat(40),
            format!("{id}.jar"),
        )
        .from_source(ModSource::Managed);
        entry.enabled = enabled;
        entry
    }

    fn good(mods: &ProfileModConfiguration) -> LaunchRecord {
        LaunchRecord {
            at: 1,
            judged_at: Some(2),
            outcome: Outcome::Worked,
            safe: false,
            minecraft: "1.8.9".into(),
            loader: "Legacy Fabric".into(),
            mods: snapshot(mods),
        }
    }

    #[test]
    fn restoring_switches_off_reverts_and_re_adds_but_never_deletes() {
        let mut then = ProfileModConfiguration::default();
        then.upsert(entry("kept", 'a', true));
        then.upsert(entry("updated", 'b', true));
        then.upsert(entry("removed", 'c', true));
        then.upsert(entry("was-off", 'd', false));
        let record = good(&then);

        let mut now = ProfileModConfiguration::default();
        now.upsert(entry("kept", 'a', true));
        let mut updated = entry("updated", 'e', true);
        updated.succeed(&entry("updated", 'b', true), 5);
        now.upsert(updated);
        now.upsert(entry("was-off", 'd', true));
        now.upsert(entry("added", 'f', true));
        let mut component =
            entry("fathom-optimizations", '9', true).from_source(ModSource::FirstParty);
        component.sha1 = "8".repeat(40);
        now.upsert(component);

        let report = restore_last_good(&mut now, &record, |_| true);
        assert_eq!(
            report.switched_off,
            vec!["added".to_owned(), "was-off".to_owned()]
        );
        assert_eq!(report.reverted, vec!["updated".to_owned()]);
        assert_eq!(report.re_added, vec!["removed".to_owned()]);
        assert!(report.unavailable.is_empty());

        let added = now.get(&ModId::new("added").unwrap()).unwrap();
        assert!(!added.enabled, "switched off");
        assert!(matches!(
            added.disabled_reason,
            Some(DisabledReason::Recovery { .. })
        ));
        let reverted = now.get(&ModId::new("updated").unwrap()).unwrap();
        assert_eq!(reverted.sha1, "b".repeat(40));
        assert_eq!(
            reverted.previous[0].sha1,
            "e".repeat(40),
            "the newer build can be put back again"
        );
        assert!(now.get(&ModId::new("removed").unwrap()).unwrap().enabled);
        assert_eq!(now.len(), 6, "nothing was deleted");
        assert_eq!(
            now.get(&ModId::new("fathom-optimizations").unwrap())
                .unwrap()
                .sha1,
            "8".repeat(40),
            "Fathom's own components are not rolled back"
        );

        // Restoring again changes nothing.
        assert!(!restore_last_good(&mut now, &record, |_| true).changed());
    }

    #[test]
    fn a_build_whose_bytes_are_gone_is_switched_off_not_faked() {
        let mut then = ProfileModConfiguration::default();
        then.upsert(entry("updated", 'b', true));
        let record = good(&then);
        let mut now = ProfileModConfiguration::default();
        now.upsert(entry("updated", 'e', true));
        let report = restore_last_good(&mut now, &record, |sha| sha != "b".repeat(40));
        assert_eq!(report.unavailable, vec!["updated".to_owned()]);
        let entry = now.get(&ModId::new("updated").unwrap()).unwrap();
        assert_eq!(
            entry.sha1,
            "e".repeat(40),
            "not pointed at bytes that do not exist"
        );
        assert!(!entry.enabled);
    }

    #[test]
    fn a_log_names_a_mod_only_when_the_mod_is_actually_in_the_line() {
        let ksyxis = entry("ksyxis", 'a', true);
        let fps = entry("fps", 'b', true);
        let words = identifiers(&ksyxis);
        assert!(words
            .iter()
            .any(|w| contains_word("mixin apply failed ksyxis.mixins.json:a from mod ksyxis", w)));
        assert!(!words
            .iter()
            .any(|w| contains_word("mixin apply failed from mod someksyxisfork", w)));
        // A short id is not distinctive enough to count on its own.
        assert!(!identifiers(&fps).iter().any(|w| w == "fps"));
        assert!(identifiers(&fps).iter().any(|w| w == "fps.jar"));
    }

    /// Forge words its failures differently, and its ordinary start-up talks
    /// about coremods without anything being wrong.
    #[test]
    fn forges_failure_lines_are_quoted_and_its_chatter_is_not() {
        assert!(is_failure_line(
            "[Client thread/ERROR] [FML]: Caught exception from Some Mod (somemod)"
        ));
        assert!(is_failure_line(
            "net.minecraftforge.fml.common.MissingModsException: Mod somemod (Some Mod) requires [otherlib@[1.2,)]"
        ));
        assert!(is_failure_line(
            "net.minecraftforge.fml.common.DuplicateModsFoundException: Found a duplicate mod somemod"
        ));
        assert!(!is_failure_line(
            "[main/INFO] [LaunchWrapper]: Loading tweak class name net.minecraftforge.fml.common.launcher.FMLTweaker"
        ));
        assert!(!is_failure_line(
            "[main/INFO]: Found a command line coremod : net.example.CoreMod"
        ));
    }

    #[test]
    fn only_failure_lines_are_quoted() {
        assert!(is_failure_line("net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!"));
        assert!(is_failure_line(
            " - Mod 'Foo' (foo) 1.0 requires version 2.0 of 'Bar' (bar)"
        ));
        assert!(is_failure_line(
            "Caused by: java.lang.NoClassDefFoundError: net/minecraft/class_123"
        ));
        assert!(!is_failure_line("[Render thread/INFO]: Loading 12 mods"));
        assert!(!is_failure_line("[main/WARN]: an error occurred once"));
    }

    #[test]
    fn evidence_comes_from_the_logs_of_the_failed_launch_and_names_mods_it_mentions() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = crate::paths::DataPaths::rooted_at(tmp.path());
        paths.ensure().unwrap();
        let mut profile = Profile::new(crate::profiles::ProfileId::new("p").unwrap(), "P");
        profile.mods.upsert(entry("ksyxis", 'a', true));
        profile.mods.upsert(entry("innocent", 'b', true));
        let dirs = ProfileDirs::new(&paths, &profile.id);
        std::fs::create_dir_all(dirs.minecraft_dir().join("logs")).unwrap();
        std::fs::create_dir_all(dirs.logs_dir()).unwrap();

        LaunchHistory::begin(&dirs, &profile, &profile.mods, false);
        LaunchHistory::judge(
            &dirs,
            Outcome::Failed {
                message: "exit 1".into(),
            },
        );
        std::fs::write(
            dirs.minecraft_dir().join("logs").join("latest.log"),
            "[main/INFO]: Loading 3 mods\n[main/ERROR]: Mixin apply failed ksyxis.mixins.json:WorldMixin from mod ksyxis\nCaused by: java.lang.NoSuchMethodError: a.b()V\n",
        )
        .unwrap();

        let store = ModStore::new(paths.clone());
        let report = analyse(&profile, &dirs, &store);
        assert_eq!(report.evidence.len(), 2, "{:?}", report.evidence);
        assert_eq!(report.named_by_logs, vec!["ksyxis".to_owned()]);
        assert!(
            !report.named_by_logs.contains(&"innocent".to_owned()),
            "never blamed without evidence"
        );
        assert_eq!(
            report.missing.len(),
            2,
            "neither jar is in this empty store"
        );
        assert!(
            report.changes.is_empty(),
            "no launch has worked yet, so nothing is 'changed since'"
        );
        assert_eq!(report.external_enabled, 2);
    }
}
