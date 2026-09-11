//! Reading benchmark runs back, comparing them, and writing the summary.
//!
//! # Why this is in the launcher and not in the game
//!
//! Because **the game cannot measure two of the six configurations.**
//! Configuration A is vanilla with no loader and no mods, so no mod can be
//! running to time it. Configuration B is the loader with an empty mods
//! directory, and a mod that measured it would make it "the loader plus a mod".
//!
//! Fathom's in-game profiler covers C, D, E and F — the four that contain
//! Fathom. A and B have to be captured with an external frame-time tool and
//! imported. That is stated here rather than worked around, because a harness
//! that quietly measured five configurations and estimated the sixth would
//! produce an overhead number with the baseline missing from it.
//!
//! The launcher is also where a JSON parser already exists. `core` on the game
//! side builds with a bare JDK on purpose; giving it a parser so it could read
//! its own output back would be a dependency carried for a report.
//!
//! # What a comparison refuses to do
//!
//! Compare two runs whose settings differ. `Environment::differences` is checked
//! before any delta is computed, and a mismatch is reported instead of a
//! percentage — because a run at a different render distance is a measurement of
//! render distance, and printing a number for it would be publishing a mistake.

use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::path::{Path, PathBuf};

use serde::Deserialize;

/// One run, as the game wrote it.
///
/// Deliberately tolerant: unknown fields are ignored, and everything the
/// launcher does not strictly need is optional. A newer module writing an extra
/// field must not make an older launcher refuse to read the file.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunReport {
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub configuration: String,
    #[serde(default)]
    pub scenario: String,
    #[serde(default)]
    pub duration_millis: u64,
    #[serde(default)]
    pub environment: BTreeMap<String, String>,
    #[serde(default)]
    pub frames: Frames,
    #[serde(default)]
    pub fathom: Option<FathomCost>,
    /// How this run was captured. Absent means Fathom's own profiler.
    #[serde(default)]
    pub source: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Frames {
    #[serde(default)]
    pub sample_count: u64,
    #[serde(default)]
    pub average_fps: f64,
    #[serde(default)]
    pub mean_frame_time_millis: f64,
    #[serde(default)]
    pub median_frame_time_millis: f64,
    #[serde(default)]
    pub p95_frame_time_millis: f64,
    #[serde(default)]
    pub p99_frame_time_millis: f64,
    #[serde(default)]
    pub one_percent_low_fps: f64,
    #[serde(default)]
    pub point_one_percent_low_fps: f64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FathomCost {
    #[serde(default)]
    pub millis_per_frame: f64,
    #[serde(default)]
    pub worst_frame_millis: f64,
    #[serde(default)]
    pub sections: BTreeMap<String, f64>,
}

/// The settings that must match before two runs may be compared.
///
/// The same list the game side enforces, repeated here because this is the side
/// that refuses the comparison, and a check that lives only where the data is
/// produced is a check that is not applied when the data is read.
pub const MUST_MATCH: &[&str] = &[
    "resolution",
    "display",
    "renderDistance",
    "graphics",
    "smoothLighting",
    "particles",
    "vbo",
    "vsync",
    "fpsLimit",
    "guiScale",
    "resourcePacks",
    "mipmaps",
    "entityShadows",
    "fov",
    "java",
    "heapMaxMb",
    "jvmArguments",
    "os",
    "cpuCores",
    "minecraft",
];

#[derive(Debug, thiserror::Error)]
pub enum BenchError {
    #[error("no benchmark runs in {0}")]
    Empty(String),
    #[error("could not read {path}: {source}")]
    Io {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error("{path} is not a benchmark run: {source}")]
    Malformed {
        path: String,
        #[source]
        source: serde_json::Error,
    },
}

/// Read every run in a directory.
pub fn read_all(directory: &Path) -> Result<Vec<RunReport>, BenchError> {
    let entries = std::fs::read_dir(directory).map_err(|source| BenchError::Io {
        path: directory.display().to_string(),
        source,
    })?;

    let mut out = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().is_none_or(|ext| ext != "json") {
            continue;
        }
        let text = std::fs::read_to_string(&path).map_err(|source| BenchError::Io {
            path: path.display().to_string(),
            source,
        })?;
        let report: RunReport =
            serde_json::from_str(&text).map_err(|source| BenchError::Malformed {
                path: path.display().to_string(),
                source,
            })?;
        out.push(report);
    }
    if out.is_empty() {
        return Err(BenchError::Empty(directory.display().to_string()));
    }
    out.sort_by(|a, b| a.configuration.cmp(&b.configuration));
    Ok(out)
}

/// What differs between two runs, among the settings that matter.
pub fn differences(baseline: &RunReport, candidate: &RunReport) -> Vec<String> {
    let mut out = Vec::new();
    for field in MUST_MATCH {
        let was = baseline.environment.get(*field).map_or("", String::as_str);
        let now = candidate.environment.get(*field).map_or("", String::as_str);
        if was != now {
            out.push(format!(
                "{field}: {} → {}",
                if was.is_empty() { "(not recorded)" } else { was },
                if now.is_empty() { "(not recorded)" } else { now }
            ));
        }
    }
    out
}

/// A comparison of one run against a baseline.
#[derive(Debug, Clone)]
pub struct Delta {
    pub configuration: String,
    /// Percentage change in mean frame time. Positive is slower.
    pub mean_frame_time_percent: f64,
    /// Percentage change in the 99th percentile. Positive is worse.
    pub p99_percent: f64,
    /// Percentage change in the 1% low frame rate. Negative is worse.
    pub one_percent_low_percent: f64,
    /// Settings that differ, which invalidate the numbers above.
    pub blocked_by: Vec<String>,
}

impl Delta {
    /// Whether the numbers may be quoted.
    pub fn usable(&self) -> bool {
        self.blocked_by.is_empty()
    }
}

/// Compare a run against a baseline.
pub fn compare(baseline: &RunReport, candidate: &RunReport) -> Delta {
    Delta {
        configuration: candidate.configuration.clone(),
        mean_frame_time_percent: percent(
            baseline.frames.mean_frame_time_millis,
            candidate.frames.mean_frame_time_millis,
        ),
        p99_percent: percent(
            baseline.frames.p99_frame_time_millis,
            candidate.frames.p99_frame_time_millis,
        ),
        one_percent_low_percent: percent(
            baseline.frames.one_percent_low_fps,
            candidate.frames.one_percent_low_fps,
        ),
        blocked_by: differences(baseline, candidate),
    }
}

/// Change from `was` to `now`, as a percentage of `was`.
///
/// Zero when there is nothing to divide by, rather than infinity: a baseline of
/// zero means the baseline was not measured, and "infinitely slower" is a worse
/// way of saying that than "no comparison".
fn percent(was: f64, now: f64) -> f64 {
    if was <= 0.0 {
        return 0.0;
    }
    (now - was) / was * 100.0
}

/// The overhead threshold this milestone is gated on.
///
/// Fathom with everything optional off, against the loader alone, in a
/// CPU-bound scene. Above this, no more Minecraft optimizations get written
/// until the overhead is understood — see
/// `docs/performance/regression-thresholds.md`.
pub const OVERHEAD_TARGET_PERCENT: f64 = 3.0;
pub const OVERHEAD_LIMIT_PERCENT: f64 = 5.0;

/// What the overhead number means.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    /// At or under the target.
    Good,
    /// Between the target and the limit.
    Acceptable,
    /// Above the limit. Stop and find it.
    Stop,
    /// Not enough runs to say.
    Unknown,
}

impl Verdict {
    pub fn of(overhead_percent: Option<f64>) -> Self {
        match overhead_percent {
            None => Verdict::Unknown,
            Some(value) if value <= OVERHEAD_TARGET_PERCENT => Verdict::Good,
            Some(value) if value <= OVERHEAD_LIMIT_PERCENT => Verdict::Acceptable,
            Some(_) => Verdict::Stop,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Verdict::Good => "within target",
            Verdict::Acceptable => "acceptable, worth reducing",
            Verdict::Stop => "ABOVE LIMIT — stop and find it",
            Verdict::Unknown => "not measured",
        }
    }
}

///
/// Split into a section per heading rather than written as one function. The
/// first version was one 160-line block, which is a shape that invites a sixth
/// section to be added in the middle of the fifth.
pub fn summarise(runs: &[RunReport]) -> String {
    let mut out = String::with_capacity(4096);
    out.push_str("# Benchmark summary\n\n");

    let baseline = runs.iter().find(|run| run.configuration == "b-loader");
    write_overhead(&mut out, runs, baseline);
    write_runs(&mut out, runs);
    if let Some(base) = baseline {
        write_deltas(&mut out, runs, base);
    }
    write_sections(&mut out, runs);
    write_missing(&mut out, runs);

    out.push_str(
        "---\n\nGenerated by `fathom-cli bench summary`. Numbers come from the runs in this \
         directory and nothing here is estimated: a configuration that was not run is listed \
         as not run.\n",
    );
    out
}

/// The headline: what Fathom costs with everything switched off.
fn write_overhead(out: &mut String, runs: &[RunReport], baseline: Option<&RunReport>) {
    let candidate = runs.iter().find(|run| run.configuration == "d-performance-off");
    let overhead = match (baseline, candidate) {
        (Some(base), Some(run)) => {
            let delta = compare(base, run);
            if delta.usable() {
                Some(delta.mean_frame_time_percent)
            } else {
                None
            }
        }
        _ => None,
    };

    out.push_str("## Fathom overhead\n\n");
    match overhead {
        Some(value) => {
            let _ = writeln!(
                out,
                "Fathom with everything optional off costs **{value:+.1}%** mean frame time \
                 against Legacy Fabric alone — {}.\n",
                Verdict::of(overhead).label()
            );
        }
        None => out.push_str(
            "**Not measured.** The overhead number needs both `b-loader` and \
             `d-performance-off`, taken under identical settings. See the runs below for \
             which is missing or mismatched.\n\n",
        ),
    }
}

/// Every run, as it was recorded.
fn write_runs(out: &mut String, runs: &[RunReport]) {
    out.push_str("## Runs\n\n");
    out.push_str(
        "| Configuration | Scenario | Frames | Mean ms | p95 | p99 | 1% low fps | Fathom ms |\n\
         | --- | --- | --- | --- | --- | --- | --- | --- |\n",
    );
    for run in runs {
        let fathom = run
            .fathom
            .as_ref()
            .map_or_else(|| "—".to_owned(), |cost| format!("{:.3}", cost.millis_per_frame));
        let _ = writeln!(
            out,
            "| {} | {} | {} | {:.2} | {:.2} | {:.2} | {:.1} | {fathom} |",
            if run.configuration.is_empty() { "(unnamed)" } else { &run.configuration },
            if run.scenario.is_empty() { "—" } else { &run.scenario },
            run.frames.sample_count,
            run.frames.mean_frame_time_millis,
            run.frames.p95_frame_time_millis,
            run.frames.p99_frame_time_millis,
            run.frames.one_percent_low_fps,
        );
    }
    out.push('\n');
}

/// Each configuration against the loader, or why it cannot be compared.
fn write_deltas(out: &mut String, runs: &[RunReport], baseline: &RunReport) {
    out.push_str("## Against Legacy Fabric alone\n\n");
    out.push_str("| Configuration | Mean frame time | p99 | 1% low |\n| --- | --- | --- | --- |\n");

    let mut blocked = Vec::new();
    for run in runs {
        if run.configuration == baseline.configuration {
            continue;
        }
        let delta = compare(baseline, run);
        if delta.usable() {
            let _ = writeln!(
                out,
                "| {} | {:+.1}% | {:+.1}% | {:+.1}% |",
                delta.configuration,
                delta.mean_frame_time_percent,
                delta.p99_percent,
                delta.one_percent_low_percent
            );
        } else {
            let _ = writeln!(out, "| {} | not comparable | | |", delta.configuration);
            blocked.push((delta.configuration.clone(), delta.blocked_by));
        }
    }
    out.push('\n');

    // Anything that could not be compared says why, here, rather than being
    // silently absent from the table above.
    if !blocked.is_empty() {
        out.push_str("### Not comparable\n\n");
        for (configuration, reasons) in blocked {
            let _ = writeln!(out, "**{configuration}** — settings differ from the baseline:\n");
            for reason in reasons {
                let _ = writeln!(out, "- {reason}");
            }
            out.push('\n');
        }
    }
}

/// Where Fathom's own time went, per section.
fn write_sections(out: &mut String, runs: &[RunReport]) {
    let with_sections: Vec<&RunReport> = runs
        .iter()
        .filter(|run| run.fathom.as_ref().is_some_and(|cost| !cost.sections.is_empty()))
        .collect();
    let Some(first) = with_sections.first() else {
        return;
    };

    let mut names: Vec<&String> = first
        .fathom
        .as_ref()
        .map(|cost| cost.sections.keys().collect())
        .unwrap_or_default();
    names.sort();

    out.push_str("## Where Fathom's time went\n\nMilliseconds per frame.\n\n");
    out.push_str("| Configuration | ");
    for name in &names {
        let _ = write!(out, "{name} | ");
    }
    out.push_str("worst frame |\n| --- |");
    for _ in &names {
        out.push_str(" --- |");
    }
    out.push_str(" --- |\n");

    for run in with_sections {
        let Some(cost) = run.fathom.as_ref() else {
            continue;
        };
        let _ = write!(out, "| {} | ", run.configuration);
        for name in &names {
            let _ = write!(out, "{:.3} | ", cost.sections.get(*name).copied().unwrap_or(0.0));
        }
        let _ = writeln!(out, "{:.2} |", cost.worst_frame_millis);
    }
    out.push('\n');
}

/// What was not run, and for A and B, why Fathom could not run it.
fn write_missing(out: &mut String, runs: &[RunReport]) {
    let expected = [
        "a-vanilla",
        "b-loader",
        "c-core",
        "d-performance-off",
        "e-balanced",
        "f-competitive",
    ];
    let missing: Vec<&str> = expected
        .iter()
        .filter(|id| !runs.iter().any(|run| run.configuration == **id))
        .copied()
        .collect();
    if missing.is_empty() {
        return;
    }

    out.push_str("## Not run\n\n");
    for id in missing {
        let why = if id == "a-vanilla" || id == "b-loader" {
            " — no Fathom in this configuration, so it needs an external capture"
        } else {
            ""
        };
        let _ = writeln!(out, "- `{id}`{why}");
    }
    out.push('\n');
}

/// Write the summary beside the runs.
pub fn write_summary(directory: &Path) -> Result<PathBuf, BenchError> {
    let runs = read_all(directory)?;
    let path = directory.join("summary.md");
    std::fs::write(&path, summarise(&runs)).map_err(|source| BenchError::Io {
        path: path.display().to_string(),
        source,
    })?;
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(configuration: &str, mean: f64, render_distance: &str) -> RunReport {
        let mut environment = BTreeMap::new();
        for field in MUST_MATCH {
            environment.insert((*field).to_owned(), "same".to_owned());
        }
        environment.insert("renderDistance".to_owned(), render_distance.to_owned());
        RunReport {
            label: configuration.to_owned(),
            configuration: configuration.to_owned(),
            scenario: "cpu-ceiling".to_owned(),
            duration_millis: 60_000,
            environment,
            frames: Frames {
                sample_count: 20_000,
                average_fps: 1000.0 / mean,
                mean_frame_time_millis: mean,
                median_frame_time_millis: mean,
                p95_frame_time_millis: mean * 1.1,
                p99_frame_time_millis: mean * 1.2,
                one_percent_low_fps: 1000.0 / (mean * 1.2),
                point_one_percent_low_fps: 1000.0 / (mean * 1.4),
            },
            fathom: None,
            source: None,
        }
    }

    #[test]
    fn overhead_is_the_gap_between_the_loader_and_fathom_with_everything_off() {
        let runs = vec![run("b-loader", 5.00, "8"), run("d-performance-off", 5.10, "8")];
        let summary = summarise(&runs);
        // 2% is inside the 3% target.
        assert!(summary.contains("+2.0%"), "{summary}");
        assert!(summary.contains("within target"), "{summary}");
    }

    #[test]
    fn overhead_above_the_limit_says_stop() {
        let runs = vec![run("b-loader", 5.00, "8"), run("d-performance-off", 5.50, "8")];
        let summary = summarise(&runs);
        assert!(summary.contains("ABOVE LIMIT"), "{summary}");
    }

    #[test]
    fn a_settings_difference_refuses_the_comparison_rather_than_reporting_it() {
        // The rule the whole module exists for: two runs at different render
        // distances are two measurements of render distance.
        let runs = vec![run("b-loader", 5.00, "8"), run("d-performance-off", 4.00, "4")];
        let summary = summarise(&runs);
        assert!(summary.contains("Not measured"), "{summary}");
        assert!(summary.contains("not comparable"), "{summary}");
        assert!(summary.contains("renderDistance: 8 → 4"), "{summary}");
        assert!(!summary.contains("-20.0%"), "a delta must not be printed: {summary}");
    }

    #[test]
    fn configurations_that_were_not_run_are_listed_as_not_run() {
        let runs = vec![run("d-performance-off", 5.0, "8")];
        let summary = summarise(&runs);
        assert!(summary.contains("## Not run"), "{summary}");
        assert!(summary.contains("a-vanilla"), "{summary}");
        // And says why those two are special.
        assert!(summary.contains("external capture"), "{summary}");
    }

    #[test]
    fn a_missing_baseline_means_no_overhead_number_rather_than_a_guess() {
        let runs = vec![run("d-performance-off", 5.0, "8")];
        assert_eq!(Verdict::of(None), Verdict::Unknown);
        assert!(summarise(&runs).contains("**Not measured.**"));
    }

    #[test]
    fn reading_a_directory_of_runs_round_trips() {
        let directory = tempfile::tempdir().unwrap();
        let json = r#"{
            "label": "x", "configuration": "d-performance-off", "scenario": "cpu-ceiling",
            "durationMillis": 60000,
            "environment": {"renderDistance": "8"},
            "frames": {"sampleCount": 20000, "meanFrameTimeMillis": 5.0},
            "fathom": {"millisPerFrame": 0.12, "worstFrameMillis": 1.4,
                       "sections": {"hud": 0.08, "modules": 0.04}}
        }"#;
        std::fs::write(directory.path().join("d-performance-off.json"), json).unwrap();

        let runs = read_all(directory.path()).unwrap();
        assert_eq!(runs.len(), 1);
        assert_eq!(runs[0].configuration, "d-performance-off");
        let cost = runs[0].fathom.as_ref().unwrap();
        assert!((cost.millis_per_frame - 0.12).abs() < 1e-9);
        assert_eq!(cost.sections.len(), 2);

        let summary = write_summary(directory.path()).unwrap();
        assert!(std::fs::read_to_string(summary).unwrap().contains("Where Fathom's time went"));
    }

    #[test]
    fn an_unknown_field_does_not_stop_a_newer_run_being_read() {
        let directory = tempfile::tempdir().unwrap();
        std::fs::write(
            directory.path().join("c-core.json"),
            r#"{"configuration": "c-core", "somethingNewer": 1,
                "frames": {"meanFrameTimeMillis": 4.0}}"#,
        )
        .unwrap();
        assert_eq!(read_all(directory.path()).unwrap().len(), 1);
    }
}
