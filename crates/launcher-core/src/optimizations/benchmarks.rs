//! Reading the measurements the game module writes.
//!
//! # Why the launcher reads a file the game wrote
//!
//! Because a frame rate is not knowable from out here. The launcher starts a
//! process and watches it exit; everything between those two events happens
//! inside a JVM it has no window into. So the module measures, writes what it
//! measured next to the game, and this reads it back.
//!
//! That direction is the opposite of [`super::bridge`], which is settings going
//! the other way, and the asymmetry is deliberate. Settings are small,
//! authoritative and overwritten every launch; results are an artefact of one
//! run, worth keeping several of, and produced at a moment nobody schedules.
//! One file each way, each owned by whoever produces it.
//!
//! # Why this exists at all
//!
//! `FATHOM_MODS_AND_LICENSING.md`: *"Do not show claims such as '+42% FPS'
//! without evidence."* The way not to show a number nobody measured is to have
//! a place where measured numbers live, and to have the interface read from it
//! rather than from a copywriter. A Performance page with nothing in this
//! directory says nothing about frame rate, which is the correct thing to say.

use std::path::{Path, PathBuf};

use serde::Deserialize;

/// Where the module writes results, relative to a game directory. Must match
/// `ReportStore.DIRECTORY` on the Java side.
pub const DIRECTORY: &str = "fathom/benchmarks";

/// One recorded run.
///
/// Every field is what the module measured. Nothing here is derived, compared
/// against a baseline, or turned into a percentage: a percentage needs two runs
/// and a controlled difference between them, and inventing one from a single
/// run is exactly the claim the research file forbids.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Benchmark {
    /// What the run was called. `in-game` for one started from the hub.
    #[serde(default)]
    pub label: String,
    /// Wall-clock start, in milliseconds since the epoch.
    #[serde(default)]
    pub started_wall_millis: u64,
    #[serde(default)]
    pub duration_millis: u64,
    #[serde(default)]
    pub frames: Frames,
    #[serde(default)]
    pub memory: Memory,
    #[serde(default)]
    pub gc: Gc,
    /// What the module says is wrong with its own measurement. Shown, not
    /// hidden: a number with a caveat is worth more than a number without one,
    /// and a caveat nobody reads is worth nothing.
    #[serde(default)]
    pub caveats: Vec<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Frames {
    #[serde(default)]
    pub sample_count: u64,
    /// True when the frame log filled and started overwriting, which means the
    /// percentiles describe the end of the run rather than all of it.
    #[serde(default)]
    pub wrapped: bool,
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
    #[serde(default)]
    pub min_frame_time_millis: f64,
    #[serde(default)]
    pub max_frame_time_millis: f64,
}

#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Memory {
    #[serde(default)]
    pub samples: u64,
    #[serde(default)]
    pub peak_heap_used_bytes: u64,
    #[serde(default)]
    pub mean_heap_used_bytes: u64,
    /// Whether allocation rate was measured at all. On a JVM without the
    /// thread-allocation extension it is not, and reporting zero would look
    /// like an answer.
    #[serde(default)]
    pub allocation_measured: bool,
    #[serde(default)]
    pub allocation_bytes_per_second: f64,
}

#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
pub struct Gc {
    #[serde(default)]
    pub collectors: Vec<Collector>,
    /// What the JVM's own accounting cannot tell us.
    #[serde(default)]
    pub limitations: String,
}

#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Collector {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub collections: u64,
    #[serde(default)]
    pub total_pause_millis: u64,
    #[serde(default)]
    pub mean_pause_millis: f64,
}

impl Benchmark {
    /// Whether this run has enough frames to say anything.
    ///
    /// A hundred frames is under two seconds, and a 1% low over fewer than a
    /// hundred samples is one frame — a number that looks like a statistic and
    /// is not. Below this the run is kept and shown as too short rather than
    /// silently averaged.
    pub const MINIMUM_FRAMES: u64 = 100;

    pub fn is_usable(&self) -> bool {
        self.frames.sample_count >= Self::MINIMUM_FRAMES
    }

    /// One line summarising the run, or the reason there is not one.
    ///
    /// The cast to `f64` is lossy above 2^53 milliseconds, which is a run
    /// lasting roughly 285,000 years. Allowed rather than guarded, because a
    /// guard would be code for a case that cannot occur.
    #[allow(clippy::cast_precision_loss)]
    pub fn headline(&self) -> String {
        if !self.is_usable() {
            return format!(
                "Too short to report: {} frames recorded, {} needed.",
                self.frames.sample_count,
                Self::MINIMUM_FRAMES
            );
        }
        format!(
            "{:.0} fps average, {:.0} fps 1% low, over {:.0} seconds",
            self.frames.average_fps,
            self.frames.one_percent_low_fps,
            self.duration_millis as f64 / 1000.0
        )
    }
}

/// Read every result in a game directory, newest first.
///
/// Never fails: a directory that does not exist is a game that has never been
/// benchmarked, and a file that will not parse is one bad file rather than a
/// broken page. Anything unreadable is skipped silently, because the only
/// action available to a user would be to delete it, and the launcher can do
/// that itself the next time the module prunes.
pub fn read_all(game_directory: &Path) -> Vec<Benchmark> {
    let directory = game_directory.join(DIRECTORY);
    let Ok(entries) = std::fs::read_dir(&directory) else {
        return Vec::new();
    };

    let mut files: Vec<PathBuf> = entries
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| {
            path.is_file()
                && path
                    .extension()
                    .is_some_and(|extension| extension.eq_ignore_ascii_case("json"))
        })
        .collect();
    // By name, which is the start timestamp, so this is chronological without
    // asking the filesystem for a modification time a copy would have changed.
    files.sort();
    files.reverse();

    files
        .iter()
        .filter_map(|path| {
            let bytes = std::fs::read(path).ok()?;
            serde_json::from_slice::<Benchmark>(&bytes).ok()
        })
        .collect()
}

/// The most recent usable run, if there is one.
pub fn newest(game_directory: &Path) -> Option<Benchmark> {
    read_all(game_directory).into_iter().find(Benchmark::is_usable)
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]
mod tests {
    use super::*;

    /// The exact shape the Java side writes. Hand-written from
    /// `Report.toJson`, because the two have to agree and nothing else checks
    /// that they do.
    const SAMPLE: &str = r#"{
      "label": "in-game",
      "startedWallMillis": 1788500000000,
      "durationMillis": 60000,
      "frames": {
        "sampleCount": 3600,
        "wrapped": false,
        "averageFps": 240.5,
        "meanFrameTimeMillis": 4.16,
        "medianFrameTimeMillis": 4.0,
        "p95FrameTimeMillis": 6.2,
        "p99FrameTimeMillis": 9.9,
        "onePercentLowFps": 101.0,
        "pointOnePercentLowFps": 62.5,
        "minFrameTimeMillis": 3.1,
        "maxFrameTimeMillis": 22.0,
        "frameTimeCoefficientOfVariation": 0.21
      },
      "memory": {
        "samples": 60,
        "peakHeapUsedBytes": 812000000,
        "meanHeapUsedBytes": 540000000,
        "allocationMeasured": true,
        "allocationMethod": "ThreadMXBean",
        "allocationBytesPerSecond": 41000000.0
      },
      "gc": {
        "collectors": [
          {"name": "G1 Young Generation", "collections": 42, "totalPauseMillis": 380,
           "meanPauseMillis": 9.05}
        ],
        "limitations": "Pause times are the JVM's own accounting."
      },
      "counters": {},
      "caveats": ["VSync was not checked."]
    }"#;

    fn write(directory: &Path, name: &str, contents: &str) {
        let target = directory.join(DIRECTORY);
        std::fs::create_dir_all(&target).unwrap();
        std::fs::write(target.join(name), contents).unwrap();
    }

    /// The contract with the Java side, in one test: every field the module
    /// writes arrives here with the right name and the right value.
    #[test]
    fn a_report_the_module_wrote_reads_back_field_for_field() {
        let report: Benchmark = serde_json::from_str(SAMPLE).expect("it parses");
        assert_eq!(report.label, "in-game");
        assert_eq!(report.duration_millis, 60_000);

        assert_eq!(report.frames.sample_count, 3600);
        assert!(!report.frames.wrapped);
        assert!((report.frames.average_fps - 240.5).abs() < 0.01);
        assert!((report.frames.one_percent_low_fps - 101.0).abs() < 0.01);
        assert!((report.frames.p99_frame_time_millis - 9.9).abs() < 0.01);
        assert!((report.frames.point_one_percent_low_fps - 62.5).abs() < 0.01);

        assert_eq!(report.memory.peak_heap_used_bytes, 812_000_000);
        assert!(report.memory.allocation_measured);

        assert_eq!(report.gc.collectors.len(), 1);
        assert_eq!(report.gc.collectors[0].collections, 42);
        assert_eq!(report.gc.collectors[0].total_pause_millis, 380);
        assert!(!report.gc.limitations.is_empty());

        assert_eq!(report.caveats.len(), 1);
    }

    /// A run too short to have a meaningful 1% low says so rather than
    /// reporting one frame as a statistic.
    #[test]
    fn a_run_that_is_too_short_is_reported_as_too_short() {
        let short = Benchmark {
            frames: Frames {
                sample_count: 12,
                average_fps: 300.0,
                one_percent_low_fps: 300.0,
                ..Frames::default()
            },
            ..serde_json::from_str::<Benchmark>(SAMPLE).unwrap()
        };
        assert!(!short.is_usable());
        let headline = short.headline();
        assert!(headline.contains("Too short"), "{headline}");
        assert!(!headline.contains("300"), "{headline}");
    }

    #[test]
    fn a_usable_run_reports_both_the_average_and_the_low() {
        let report: Benchmark = serde_json::from_str(SAMPLE).unwrap();
        assert!(report.is_usable());
        let headline = report.headline();
        assert!(headline.contains("240"), "{headline}");
        assert!(headline.contains("101"), "{headline}");
        assert!(headline.contains("1% low"), "{headline}");
        // Never a comparison. There is one run here and no baseline.
        assert!(!headline.contains('%') || headline.contains("1%"), "{headline}");
        assert!(!headline.contains('+'), "{headline}");
    }

    #[test]
    fn a_game_that_was_never_benchmarked_reports_nothing_rather_than_failing() {
        let tmp = tempfile::tempdir().unwrap();
        assert!(read_all(tmp.path()).is_empty());
        assert!(newest(tmp.path()).is_none());
    }

    /// One unreadable file is one unreadable file, not a broken page.
    #[test]
    fn a_corrupt_result_is_skipped_and_the_others_still_read() {
        let tmp = tempfile::tempdir().unwrap();
        write(tmp.path(), "1788500000000.json", SAMPLE);
        write(tmp.path(), "1788500001000.json", "{ this is not json");
        write(tmp.path(), "notes.txt", "ignored");

        let all = read_all(tmp.path());
        assert_eq!(all.len(), 1, "the good one survived");
        assert_eq!(all[0].label, "in-game");
    }

    /// Newest first, by the timestamp in the name.
    #[test]
    fn results_come_back_newest_first() {
        let tmp = tempfile::tempdir().unwrap();
        write(
            tmp.path(),
            "1788500000000.json",
            &SAMPLE.replace("\"in-game\"", "\"older\""),
        );
        write(
            tmp.path(),
            "1788599999999.json",
            &SAMPLE.replace("\"in-game\"", "\"newer\""),
        );

        let all = read_all(tmp.path());
        assert_eq!(all.len(), 2);
        assert_eq!(all[0].label, "newer");
        assert_eq!(all[1].label, "older");
        assert_eq!(newest(tmp.path()).unwrap().label, "newer");
    }

    /// `newest` returns the newest *usable* run. A short one at the top would
    /// otherwise hide the real measurement underneath it.
    #[test]
    fn the_newest_usable_run_is_the_one_reported() {
        let tmp = tempfile::tempdir().unwrap();
        write(tmp.path(), "1788500000000.json", SAMPLE);
        write(
            tmp.path(),
            "1788599999999.json",
            &SAMPLE.replace("\"sampleCount\": 3600", "\"sampleCount\": 4"),
        );

        let newest = newest(tmp.path()).expect("a usable run exists");
        assert_eq!(newest.frames.sample_count, 3600);
    }

    /// The directory the launcher looks in has to be the one the module writes
    /// to. Two constants in two languages, checked against the Java source.
    #[test]
    fn the_directory_matches_the_java_side() {
        let source = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../minecraft/fathom-optimizations/src/main/java/net/fathom/",
            "optimizations/core/instrument/ReportStore.java"
        );
        let Ok(java) = std::fs::read_to_string(source) else {
            // The Java module is optional in a source checkout.
            return;
        };
        assert!(
            java.contains(&format!("DIRECTORY = \"{DIRECTORY}\"")),
            "the launcher reads {DIRECTORY}, which the module does not write to"
        );
    }
}
