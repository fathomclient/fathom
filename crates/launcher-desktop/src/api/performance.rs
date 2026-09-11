//! Performance, as the interface sees it.
//!
//! # The honesty rule this module enforces
//!
//! Every row carries what it actually is: whether it runs today, how far the
//! implementation got, and what we measured. A launcher that lists twenty
//! optimizations and lets you toggle them all while none of them run is lying,
//! and the way that lie gets built is by a view model that quietly drops the
//! status field because it made the design look untidy. So the status is not
//! optional here — it is part of the row, and the page renders it.

use serde::Serialize;

use launcher_core::optimizations::{self, Category, Group, Optimization, Status};
use launcher_core::profiles::{OptimizationPreset, Profile};
use launcher_core::Launcher;

/// What the Performance page needs to draw itself, in one call.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PerformanceView {
    pub profile_name: String,
    pub presets: Vec<PresetOption>,
    pub groups: Vec<OptimizationGroup>,
    pub module: ModuleStatus,
    /// One line for the page header: "4 of 20 running".
    pub summary: String,
    /// The most recent measured run, if this profile has ever been benchmarked.
    ///
    /// `None` is the normal state and the page says nothing about frame rate
    /// when it is `None`. That is the point: the only numbers shown here are
    /// ones somebody measured on this machine, in this profile.
    pub benchmark: Option<BenchmarkSummary>,
    /// How many runs are on disk, so a single result does not look like the
    /// only one there has ever been.
    pub benchmark_count: usize,
}

/// One measured run, rendered.
///
/// Deliberately not a comparison. A percentage needs a baseline and a
/// controlled difference between two runs, and this is one run — so what is
/// shown is what was recorded, with the module's own caveats attached rather
/// than filtered out.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BenchmarkSummary {
    /// "240 fps average, 101 fps 1% low, over 60 seconds".
    pub headline: String,
    pub label: String,
    pub frames: Vec<Measurement>,
    pub memory: Vec<Measurement>,
    pub gc: Vec<Measurement>,
    /// What the module says is wrong with its own measurement.
    pub caveats: Vec<String>,
    /// True when the frame log wrapped, so the percentiles describe the end of
    /// the run rather than all of it.
    pub partial: bool,
}

/// One number with a name. A list of these rather than a struct per section,
/// because the page renders them identically and a struct would only add a
/// place for the two to disagree.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Measurement {
    pub label: String,
    pub value: String,
}

fn measurement(label: &str, value: String) -> Measurement {
    Measurement {
        label: label.to_owned(),
        value,
    }
}

fn megabytes(bytes: u64) -> String {
    format!("{:.0} MB", bytes as f64 / (1024.0 * 1024.0))
}

/// Turn a recorded run into rows.
fn summarise(run: &launcher_core::optimizations::benchmarks::Benchmark) -> BenchmarkSummary {
    let frames = vec![
        measurement("Average", format!("{:.0} fps", run.frames.average_fps)),
        measurement("1% low", format!("{:.0} fps", run.frames.one_percent_low_fps)),
        measurement(
            "0.1% low",
            format!("{:.0} fps", run.frames.point_one_percent_low_fps),
        ),
        measurement(
            "Median frame",
            format!("{:.2} ms", run.frames.median_frame_time_millis),
        ),
        measurement(
            "95th percentile",
            format!("{:.2} ms", run.frames.p95_frame_time_millis),
        ),
        measurement(
            "99th percentile",
            format!("{:.2} ms", run.frames.p99_frame_time_millis),
        ),
        measurement(
            "Worst frame",
            format!("{:.2} ms", run.frames.max_frame_time_millis),
        ),
        measurement("Frames recorded", run.frames.sample_count.to_string()),
    ];

    let mut memory = vec![
        measurement("Peak heap", megabytes(run.memory.peak_heap_used_bytes)),
        measurement("Mean heap", megabytes(run.memory.mean_heap_used_bytes)),
    ];
    // An unmeasured allocation rate is reported as unmeasured. Showing 0 MB/s
    // would look like an answer, and it is the absence of one.
    memory.push(if run.memory.allocation_measured {
        measurement(
            "Allocation",
            format!(
                "{:.0} MB/s",
                run.memory.allocation_bytes_per_second / (1024.0 * 1024.0)
            ),
        )
    } else {
        measurement("Allocation", "not measurable on this JVM".to_owned())
    });

    let gc = if run.gc.collectors.is_empty() {
        vec![measurement("Collections", "none recorded".to_owned())]
    } else {
        run.gc
            .collectors
            .iter()
            .map(|collector| {
                measurement(
                    &collector.name,
                    format!(
                        "{} collections, {} ms total, {:.1} ms mean",
                        collector.collections,
                        collector.total_pause_millis,
                        collector.mean_pause_millis
                    ),
                )
            })
            .collect()
    };

    let mut caveats = run.caveats.clone();
    if !run.gc.limitations.is_empty() {
        caveats.push(run.gc.limitations.clone());
    }

    BenchmarkSummary {
        headline: run.headline(),
        label: run.label.clone(),
        frames,
        memory,
        gc,
        caveats,
        partial: run.frames.wrapped,
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PresetOption {
    pub id: String,
    pub label: String,
    pub description: String,
    pub selected: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OptimizationGroup {
    pub id: String,
    pub label: String,
    pub rows: Vec<OptimizationRow>,
    /// "3 of 5 on", so a collapsed group still says something.
    pub summary: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OptimizationRow {
    pub id: String,
    pub name: String,
    /// One line. Everything longer lives in `detail`, behind a disclosure.
    pub summary: String,
    pub detail: String,
    pub enabled: bool,
    /// Whether switching it on does anything today.
    pub running: bool,
    /// Whether flipping this switch would change anything.
    ///
    /// # Why this is not simply `running`
    ///
    /// It currently equals it, and it is a separate field anyway, because the
    /// two are different claims and will stop agreeing. `running` is "this is
    /// doing something right now"; `can_toggle` is "there is a decision here to
    /// make". The moment a game-side entry ships that is implemented and
    /// switched off, `running` is false and `can_toggle` is true.
    ///
    /// What must never happen is a switch that moves and changes nothing.
    /// `OptimizationPreset::includes` refuses to enable an unimplemented entry
    /// and the config bridge writes `false` for one regardless, so a toggle on a
    /// planned entry is decoration with a state — which is exactly the "fake
    /// toggle" the brief asks to remove.
    pub can_toggle: bool,
    pub status: String,
    pub readiness: String,
    pub category: String,
    /// `safe` / `optional` / `experimental` — a tone for the badge.
    pub tone: &'static str,
    /// What we measured, where we measured anything. Never a frame-rate claim.
    pub evidence: Option<String>,
}

/// Whether the game-side module is installed for this profile.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModuleStatus {
    pub installed: bool,
    pub headline: String,
    pub detail: String,
    pub version: String,
}

/// The six groups the page shows.
///
/// Deliberately not `optimizations::Group`. The catalogue's grouping is
/// engineering-shaped — `Chunks` and `Entities` are separate because they are
/// separate subsystems — and the page's grouping is reader-shaped: somebody
/// looking for "why is my world stuttering" does not know whether that is a
/// chunk problem or an entity problem. This is the one place the two
/// vocabularies meet, so the mapping is written down rather than implied.
const PAGE_GROUPS: [(&str, &str); 6] = [
    ("rendering", "Rendering"),
    ("memory", "Memory"),
    ("world", "World"),
    ("particles", "Particles"),
    ("input", "Input"),
    ("advanced", "Advanced"),
];

/// Which page group an entry belongs to.
fn page_group(entry: &Optimization) -> &'static str {
    // Checked before the group match: particle work is filed under `Rendering`
    // in the catalogue, and "Particles" is a group people actually go looking
    // for by name.
    if entry.id.starts_with("render.particle.") {
        return "particles";
    }
    // Experimental work is surfaced together regardless of subsystem, because
    // the thing a reader needs to know about it is that it is experimental.
    if entry.category == Category::Experimental {
        return "advanced";
    }
    match entry.group {
        Group::Rendering => "rendering",
        Group::Memory => "memory",
        Group::Chunks | Group::Entities => "world",
        Group::Frames => "input",
        Group::Resources => "advanced",
    }
}

const fn tone_of(category: Category) -> &'static str {
    match category {
        Category::Safe => "safe",
        Category::Optional => "optional",
        Category::Experimental => "experimental",
    }
}

pub const fn preset_id(preset: OptimizationPreset) -> &'static str {
    match preset {
        OptimizationPreset::Balanced => "balanced",
        OptimizationPreset::Competitive => "competitive",
        OptimizationPreset::MaximumFps => "maximum-fps",
        OptimizationPreset::Custom => "custom",
    }
}

pub fn preset_from_id(id: &str) -> Option<OptimizationPreset> {
    OptimizationPreset::ALL
        .into_iter()
        .find(|preset| preset_id(*preset) == id)
}

/// Whether a given entry is on for this profile.
///
/// The profile's stored map wins where it has an opinion, because that map is
/// what the bridge file is written from and therefore what the game will
/// actually do. Falling back to the preset only for entries the map has never
/// heard of is what lets a newly added optimization appear switched on for
/// somebody on Balanced without rewriting their profile.
fn is_enabled(profile: &Profile, entry: &Optimization) -> bool {
    profile
        .optimizations
        .rendering
        .get(entry.id)
        .copied()
        .unwrap_or_else(|| profile.optimizations.preset.includes(entry))
}

/// The first sentence of a summary, for the collapsed row.
///
/// The copy rule for this page is a short label, one line, and an action;
/// anything deeper goes behind a disclosure. Rather than write every summary
/// twice, the row takes the first sentence and the drawer takes all of it.
fn first_sentence(text: &str) -> String {
    match text.find(". ") {
        Some(end) => text[..=end].trim().to_owned(),
        None => text.to_owned(),
    }
}

pub fn view(core: &Launcher, profile: &Profile) -> PerformanceView {
    let catalogue = optimizations::all();
    let active = optimizations::active_count();

    let groups = PAGE_GROUPS
        .iter()
        .map(|(id, label)| {
            let rows: Vec<OptimizationRow> = catalogue
                .iter()
                .filter(|entry| page_group(entry) == *id)
                .map(|entry| OptimizationRow {
                    id: entry.id.to_owned(),
                    name: entry.name.to_owned(),
                    summary: first_sentence(entry.summary),
                    detail: entry.summary.to_owned(),
                    enabled: is_enabled(profile, entry),
                    running: entry.status == Status::Active,
                    can_toggle: entry.is_actionable(),
                    status: entry.status.label().to_owned(),
                    readiness: entry.readiness.label().to_owned(),
                    category: entry.category.label().to_owned(),
                    tone: tone_of(entry.category),
                    evidence: entry.evidence.map(str::to_owned),
                })
                .collect();

            let on = rows.iter().filter(|row| row.enabled).count();
            OptimizationGroup {
                id: (*id).to_owned(),
                label: (*label).to_owned(),
                summary: format!("{on} of {} on", rows.len()),
                rows,
            }
        })
        // A group with nothing in it is not a design decision worth rendering.
        .filter(|group| !group.rows.is_empty())
        .collect();

    let game_directory = core.dirs_for(profile).minecraft_dir();
    let runs = launcher_core::optimizations::benchmarks::read_all(&game_directory);

    PerformanceView {
        benchmark: runs
            .iter()
            .find(|run| run.is_usable())
            .map(summarise),
        benchmark_count: runs.len(),
        profile_name: profile.display_name.clone(),
        presets: OptimizationPreset::ALL
            .into_iter()
            .map(|preset| PresetOption {
                id: preset_id(preset).to_owned(),
                label: preset.label().to_owned(),
                description: preset.description().to_owned(),
                selected: preset == profile.optimizations.preset,
            })
            .collect(),
        groups,
        module: module_status(core, profile),
        summary: format!("{active} of {} running", catalogue.len()),
    }
}

/// Is the module in this profile's mods directory?
fn module_status(core: &Launcher, profile: &Profile) -> ModuleStatus {
    let version = launcher_core::mods::catalog::find(&module_id())
        .map_or_else(|| "unknown".to_owned(), |entry| entry.version.0.clone());

    let installed = core
        .mods_for(&profile.id)
        .iter()
        .any(|installed| installed.file_name.contains("fathom-optimizations"));

    if installed {
        return ModuleStatus {
            installed: true,
            headline: "Optimization module installed".to_owned(),
            detail: "Applied when you launch.".to_owned(),
            version,
        };
    }

    // Loader first: on a Vanilla profile the module cannot load at all, and
    // saying "not added" would send somebody looking for a button that would
    // not help them.
    if !profile.loader_kind().is_implemented() {
        return ModuleStatus {
            installed: false,
            headline: "Needs Legacy Fabric".to_owned(),
            detail: "Switch this profile to Legacy Fabric to use them.".to_owned(),
            version,
        };
    }

    ModuleStatus {
        installed: false,
        headline: "Optimization module not added".to_owned(),
        detail: "Java settings still apply. Add it for the in-game ones.".to_owned(),
        version,
    }
}

fn module_id() -> launcher_core::mods::ModId {
    // A compile-time constant in the catalogue, so this cannot fail — but an
    // `unwrap` here would be a panic inside a command handler.
    launcher_core::mods::ModId::new("fathom-optimizations")
        .unwrap_or_else(|_| unreachable!("the module id is a valid mod id"))
}

/// Apply a preset to a profile, expanding it into the stored switch map.
///
/// Expanded rather than stored as a name, because the bridge file the game
/// reads is a list of switches. Writing the expansion at the moment the user
/// chooses means the file and the page cannot disagree about what "Balanced"
/// meant on the day it was picked.
pub fn apply_preset(profile: &mut Profile, preset: OptimizationPreset) {
    profile.optimizations.preset = preset;
    if preset != OptimizationPreset::Custom {
        profile.optimizations.rendering = preset.switches();
        // The two JVM entries are real flags rather than map keys, so the map
        // alone would not reach them.
        profile.optimizations.jvm.disable_explicit_gc = preset
            .switches()
            .get("jvm.disable-explicit-gc")
            .copied()
            .unwrap_or(true);
        profile.optimizations.jvm.compile_huge_methods = preset
            .switches()
            .get("jvm.compile-huge-methods")
            .copied()
            .unwrap_or(false);
    }
}

/// Toggle one entry, which necessarily moves the profile to Custom.
pub fn set_optimization(profile: &mut Profile, id: &str, enabled: bool) -> Result<(), String> {
    if optimizations::find(id).is_none() {
        return Err(format!("unknown optimization: {id}"));
    }
    // Expand the preset before overwriting one key, so that switching one thing
    // off a Balanced profile keeps everything else Balanced had, rather than
    // leaving a map with a single key in it.
    if profile.optimizations.preset != OptimizationPreset::Custom {
        let expanded = profile.optimizations.preset.switches();
        for (key, value) in expanded {
            profile.optimizations.rendering.entry(key).or_insert(value);
        }
    }
    profile.optimizations.rendering.insert(id.to_owned(), enabled);
    profile.optimizations.preset = OptimizationPreset::Custom;

    match id {
        "jvm.disable-explicit-gc" => profile.optimizations.jvm.disable_explicit_gc = enabled,
        "jvm.compile-huge-methods" => profile.optimizations.jvm.compile_huge_methods = enabled,
        _ => {}
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use launcher_core::profiles::ProfileId;

    /// No fake toggles.
    ///
    /// A switch that moves and changes nothing teaches the user that the
    /// switches on this page are decorative, and then the ones that do work are
    /// not believed either. `OptimizationPreset::includes` refuses to enable an
    /// unimplemented entry and the config bridge writes `false` for one whatever
    /// the profile says, so a toggle on a planned entry could never have done
    /// anything.
    ///
    /// This asserts the two halves agree: every row that offers a control is a
    /// row where the control does something, and every implemented entry offers
    /// one.
    #[test]
    fn only_rows_that_do_something_offer_a_switch() {
        for entry in optimizations::all() {
            let actionable = entry.is_actionable();
            assert_eq!(
                actionable,
                entry.status == Status::Active,
                "{} disagrees with itself about whether it runs",
                entry.id
            );
            if !actionable {
                assert!(
                    !OptimizationPreset::MaximumFps.includes(&entry),
                    "{} has no implementation and a preset would still switch it on",
                    entry.id
                );
            }
        }
    }

    fn profile() -> Profile {
        Profile::new(ProfileId::new("test").expect("a valid id"), "Test")
    }

    /// Every catalogue entry must land in exactly one page group. An entry that
    /// matched nothing would silently vanish from the page — which is precisely
    /// the failure this whole module is written to avoid.
    #[test]
    fn every_optimization_has_a_home() {
        let ids: Vec<&str> = PAGE_GROUPS.iter().map(|(id, _)| *id).collect();
        for entry in optimizations::all() {
            let group = page_group(&entry);
            assert!(
                ids.contains(&group),
                "{} mapped to unknown group {group}",
                entry.id
            );
        }
    }

    #[test]
    fn preset_ids_round_trip() {
        for preset in OptimizationPreset::ALL {
            assert_eq!(preset_from_id(preset_id(preset)), Some(preset));
        }
        assert_eq!(preset_from_id("turbo"), None);
    }

    /// Turning one thing off must not discard everything else the preset had
    /// switched on.
    #[test]
    fn switching_one_entry_off_keeps_the_rest_of_the_preset() {
        let mut profile = profile();
        apply_preset(&mut profile, OptimizationPreset::Balanced);
        let before = profile
            .optimizations
            .rendering
            .values()
            .filter(|on| **on)
            .count();
        assert!(before > 1, "Balanced should switch on more than one thing");

        set_optimization(&mut profile, "render.font.metrics-cache", false)
            .expect("a real optimization id");

        let after = profile
            .optimizations
            .rendering
            .values()
            .filter(|on| **on)
            .count();
        assert_eq!(after, before - 1);
        assert_eq!(profile.optimizations.preset, OptimizationPreset::Custom);
    }

    #[test]
    fn an_unknown_id_is_refused_rather_than_stored() {
        let mut profile = profile();
        assert!(set_optimization(&mut profile, "render.make-it-faster", true).is_err());
        assert!(profile.optimizations.rendering.is_empty());
    }

    /// The row is one line; the drawer is the whole thing.
    #[test]
    fn the_row_summary_is_one_sentence() {
        let long = "First sentence here. Second sentence that the row should not carry.";
        assert_eq!(first_sentence(long), "First sentence here.");
        assert_eq!(first_sentence("No full stop"), "No full stop");
    }
}
