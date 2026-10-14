//! The game's own reading of a session, for the launcher to show.
//!
//! While a Fathom game started by this launcher runs, its module writes
//! [`RELATIVE_PATH`] in the game directory every few seconds, and once more as
//! it closes (`SessionPulse.java` in the module). The launcher reads it while
//! the session runs, for Home's live figures and the header's session capsule,
//! and afterwards, for the session summary and the Performance page's "last
//! session" card.
//!
//! # What is in it
//!
//! Frame rates, frame-time percentiles, a heap size, a module count, and a
//! timeline of ten-second buckets with the kind of change marked on it
//! ("world", "settings"). Nothing names a world, a server or a player. The file
//! stays on this machine; nothing here sends it anywhere.
//!
//! # Trust
//!
//! It is written by a process that runs third-party mods, so it is read as
//! untrusted input: size-capped, schema-checked, every number clamped to
//! something a frame rate can be, and any failure is "no reading" rather than
//! an error the interface has to explain.

use std::path::Path;
use std::time::{Duration, SystemTime};

use serde::{Deserialize, Serialize};

/// Where the module writes it, relative to the game directory. Must match
/// `PulseJson.RELATIVE_PATH`.
pub const RELATIVE_PATH: &str = "fathom/session-pulse.json";

/// Format version. Must match `PulseJson.SCHEMA`.
pub const SCHEMA: u32 = 1;

/// Larger than any real document (ninety buckets of two numbers), small enough
/// that a hostile file cannot make the launcher read much.
const MAX_BYTES: u64 = 64 * 1024;

/// The module keeps ninety buckets; anything longer is not its document.
const MAX_BUCKETS: usize = 90;

/// A reading older than this, while the game is running, is not live: the
/// module writes every five seconds unless play has stopped (a menu, an
/// unfocused window), when there is nothing new to say.
pub const LIVE_FOR: Duration = Duration::from_secs(20);

/// One reading.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionPulse {
    pub schema: u32,
    /// Milliseconds since the Unix epoch, when the module wrote it.
    pub updated_at: u64,
    /// Seconds of play measured: in a world, with the window focused.
    pub seconds_of_play: u64,
    pub frames: u64,
    /// Frames per second over the last second of play.
    pub current_fps: f32,
    /// Frames over their time, for the whole session.
    pub average_fps: f32,
    #[serde(rename = "p95Ms")]
    pub p95_ms: f32,
    #[serde(rename = "p99Ms")]
    pub p99_ms: f32,
    pub worst_ms: f32,
    /// Frames of 50 ms or longer.
    pub hitches: u64,
    pub heap_used_mb: u64,
    pub heap_max_mb: u64,
    /// Fathom modules switched on.
    pub modules: u32,
    pub bucket_seconds: u32,
    /// Frames per second per bucket, oldest first.
    pub timeline_fps: Vec<f32>,
    /// The slowest frame per bucket, in milliseconds.
    #[serde(rename = "timelineWorstMs")]
    pub timeline_worst_ms: Vec<f32>,
    #[serde(default)]
    pub marks: Vec<PulseMark>,
}

/// A change marked on the timeline.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PulseMark {
    /// Which bucket it fell in.
    pub bucket: u32,
    /// `world` or `settings`; anything else is dropped on reading.
    pub kind: String,
}

impl SessionPulse {
    /// Whether the module wrote this recently enough to call it live.
    pub fn is_fresh(&self, now: SystemTime) -> bool {
        let Some(written) = SystemTime::UNIX_EPOCH.checked_add(Duration::from_millis(self.updated_at))
        else {
            return false;
        };
        match now.duration_since(written) {
            Ok(age) => age <= LIVE_FOR,
            // Written "in the future": a clock change. Not stale.
            Err(_) => true,
        }
    }

    /// Clamp everything to what a reading can be, and drop what it cannot.
    fn sanitised(mut self) -> Option<Self> {
        if self.schema != SCHEMA {
            return None;
        }
        let rate = |value: f32| {
            if value.is_finite() {
                value.clamp(0.0, 100_000.0)
            } else {
                0.0
            }
        };
        let millis = |value: f32| {
            if value.is_finite() {
                value.clamp(0.0, 60_000.0)
            } else {
                0.0
            }
        };
        self.current_fps = rate(self.current_fps);
        self.average_fps = rate(self.average_fps);
        self.p95_ms = millis(self.p95_ms);
        self.p99_ms = millis(self.p99_ms);
        self.worst_ms = millis(self.worst_ms);
        self.timeline_fps.truncate(MAX_BUCKETS);
        self.timeline_worst_ms.truncate(MAX_BUCKETS);
        let buckets = self.timeline_fps.len().min(self.timeline_worst_ms.len());
        self.timeline_fps.truncate(buckets);
        self.timeline_worst_ms.truncate(buckets);
        for value in &mut self.timeline_fps {
            *value = rate(*value);
        }
        for value in &mut self.timeline_worst_ms {
            *value = millis(*value);
        }
        self.marks.retain(|mark| {
            (mark.bucket as usize) < buckets.max(1) && matches!(mark.kind.as_str(), "world" | "settings")
        });
        self.marks.truncate(16);
        if self.bucket_seconds == 0 {
            self.bucket_seconds = 10;
        }
        Some(self)
    }
}

/// Parse a document. `None` for anything that is not one.
pub fn parse(document: &str) -> Option<SessionPulse> {
    serde_json::from_str::<SessionPulse>(document)
        .ok()
        .and_then(SessionPulse::sanitised)
}

/// Read the reading in `game_directory`, if there is a usable one.
pub fn read(game_directory: &Path) -> Option<SessionPulse> {
    let path = game_directory.join(RELATIVE_PATH);
    let metadata = std::fs::metadata(&path).ok()?;
    if !metadata.is_file() || metadata.len() > MAX_BYTES {
        return None;
    }
    let text = std::fs::read_to_string(&path).ok()?;
    parse(&text)
}

/// Remove the last session's reading, before a new session starts, so the
/// launcher never shows the previous game's numbers as this one's.
pub fn clear(game_directory: &Path) {
    let _ = std::fs::remove_file(game_directory.join(RELATIVE_PATH));
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    /// The document `PulseTests.theDocumentHasTheLaunchersShape` writes.
    const FROM_THE_MODULE: &str = "{\"schema\":1,\"updatedAt\":1700000000000,\"secondsOfPlay\":20,\
        \"frames\":2000,\"currentFps\":100.0,\"averageFps\":100.0,\"p95Ms\":10.00,\
        \"p99Ms\":10.00,\"worstMs\":10.00,\"hitches\":0,\"heapUsedMb\":512,\
        \"heapMaxMb\":4096,\"modules\":7,\"bucketSeconds\":10,\
        \"timelineFps\":[100.0,100.0],\"timelineWorstMs\":[10.0,10.0],\
        \"marks\":[{\"bucket\":1,\"kind\":\"settings\"}]}";

    #[test]
    fn the_modules_document_is_read() {
        let pulse = parse(FROM_THE_MODULE).expect("the module's own document");
        assert_eq!(pulse.seconds_of_play, 20);
        assert_eq!(pulse.frames, 2000);
        assert!((pulse.average_fps - 100.0).abs() < f32::EPSILON);
        assert!((pulse.p99_ms - 10.0).abs() < f32::EPSILON);
        assert_eq!(pulse.heap_max_mb, 4096);
        assert_eq!(pulse.modules, 7);
        assert_eq!(pulse.timeline_fps.len(), 2);
        assert_eq!(
            pulse.marks,
            vec![PulseMark {
                bucket: 1,
                kind: "settings".to_owned()
            }]
        );
    }

    #[test]
    fn another_schema_is_not_guessed_at() {
        let newer = FROM_THE_MODULE.replace("\"schema\":1", "\"schema\":2");
        assert!(parse(&newer).is_none());
        assert!(parse("not json").is_none());
        assert!(parse("{}").is_none());
    }

    #[test]
    fn hostile_numbers_are_clamped_and_unknown_marks_dropped() {
        let hostile = FROM_THE_MODULE
            .replace("\"currentFps\":100.0", "\"currentFps\":1e30")
            .replace("\"p99Ms\":10.00", "\"p99Ms\":-5")
            .replace(
                "\"marks\":[{\"bucket\":1,\"kind\":\"settings\"}]",
                "\"marks\":[{\"bucket\":1,\"kind\":\"<script>\"},{\"bucket\":99,\"kind\":\"world\"}]",
            );
        let pulse = parse(&hostile).expect("still a document");
        assert!(pulse.current_fps <= 100_000.0);
        assert!(pulse.p99_ms >= 0.0);
        assert!(pulse.marks.is_empty(), "{:?}", pulse.marks);
    }

    #[test]
    fn mismatched_timelines_are_cut_to_the_shorter() {
        let uneven = FROM_THE_MODULE.replace("\"timelineWorstMs\":[10.0,10.0]", "\"timelineWorstMs\":[10.0]");
        let pulse = parse(&uneven).expect("a document");
        assert_eq!(pulse.timeline_fps.len(), 1);
        assert_eq!(pulse.timeline_worst_ms.len(), 1);
        assert!(pulse.marks.is_empty(), "the mark's bucket is gone with it");
    }

    #[test]
    fn freshness_follows_the_write_time() {
        let pulse = parse(FROM_THE_MODULE).expect("a document");
        let written = SystemTime::UNIX_EPOCH + Duration::from_millis(pulse.updated_at);
        assert!(pulse.is_fresh(written + Duration::from_secs(5)));
        assert!(!pulse.is_fresh(written + Duration::from_secs(60)));
        assert!(pulse.is_fresh(written - Duration::from_secs(5)), "a clock that moved back");
    }

    #[test]
    fn reading_and_clearing_a_directory() {
        let tmp = tempfile::tempdir().expect("tempdir");
        assert!(read(tmp.path()).is_none());
        let path = tmp.path().join(RELATIVE_PATH);
        std::fs::create_dir_all(path.parent().expect("parent")).expect("mkdir");
        std::fs::write(&path, FROM_THE_MODULE).expect("write");
        assert!(read(tmp.path()).is_some());
        clear(tmp.path());
        assert!(read(tmp.path()).is_none());
    }

    #[test]
    fn an_oversized_file_is_not_read() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let path = tmp.path().join(RELATIVE_PATH);
        std::fs::create_dir_all(path.parent().expect("parent")).expect("mkdir");
        std::fs::write(&path, " ".repeat(70 * 1024) + FROM_THE_MODULE).expect("write");
        assert!(read(tmp.path()).is_none());
    }
}
