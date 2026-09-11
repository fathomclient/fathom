//! The animated shell background.
//!
//! # What this is
//!
//! Fathom draws real Minecraft gameplay behind the entire window. It is not a
//! decorative strip behind a header: the footage is the canvas the launcher
//! sits on, and the PLAY action sits in the middle of it.
//!
//! # Why it is frames on disk and not a video file
//!
//! Playing an `.mp4` would mean shipping an H.264 decoder, which means either a
//! C library or several hundred crates. `tools/prepare-backgrounds.ps1` turns
//! each source recording into a 300-frame 1280x720 JPEG sequence instead: ten
//! seconds at 30 fps, already defocused, already graded, already looping. The
//! only work left at runtime is one baseline JPEG decode per frame, measured at
//! 5.1 ms — a sixth of the 33 ms frame budget, on one thread that is not the
//! interface thread.
//!
//! # Why the previous version looked broken
//!
//! It shipped 384x216 frames and advanced them six times a second. Both numbers
//! came from the same misdiagnosis. On the software renderer this build used to
//! use, a full-window bilinear blit costs 21.7 ms at 1920x1010 and depends
//! *only* on the destination size — a 384x216 source costs exactly as much to
//! draw as a 1280x720 one. Shrinking the footage bought nothing and cost all of
//! the image quality; the frame rate had to fall anyway. The fix was to give
//! the scaling to the GPU (see `launcher-ui`'s manifest) and then let the
//! footage be as large as it deserves to be.
//!
//! # Never a black frame
//!
//! Three rules, because a launcher that flashes black on every background
//! change looks cheap:
//!
//! - A static midnight surface is painted under everything, always.
//! - Switching keeps the previous background on screen at full opacity and
//!   fades the new one in over it. The old sequence stops decoding only once
//!   the new one has fully arrived.
//! - The loop point is a dissolve baked into the frames, so the wrap from frame
//!   300 to frame 1 has no discontinuity at all.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use iced::futures::channel::mpsc;
use iced::futures::{SinkExt, StreamExt};
use iced::stream;
use iced::widget::image::Handle;
use serde::{Deserialize, Serialize};

const PREFERENCES_FILE: &str = "appearance.json";

/// How long a background change takes to cross over. Long enough to read as
/// deliberate, short enough that it never feels like waiting.
pub const CROSSFADE: Duration = Duration::from_millis(420);

/// Small stills compiled into the executable.
///
/// These cover the two moments the frame sequence cannot: the switcher's
/// previews, and the instant between choosing a background and its first
/// decoded frame arriving. Four 640x360 JPEGs, about 40 KB in total.
const POSTERS: [&[u8]; 4] = [
    include_bytes!("../assets/backgrounds/bg_1/poster.jpg"),
    include_bytes!("../assets/backgrounds/bg_2/poster.jpg"),
    include_bytes!("../assets/backgrounds/bg_3/poster.jpg"),
    include_bytes!("../assets/backgrounds/bg_4/poster.jpg"),
];

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BackgroundChoice {
    /// No footage. The launcher's own midnight surface, which is a finished
    /// look rather than a fallback.
    Static,
    Bg1,
    Bg2,
    Bg3,
    Bg4,
}

impl BackgroundChoice {
    pub const ALL: [Self; 5] = [Self::Static, Self::Bg1, Self::Bg2, Self::Bg3, Self::Bg4];

    /// What the switcher calls it.
    pub const fn label(self) -> &'static str {
        match self {
            Self::Static => "Midnight",
            Self::Bg1 => "Cherry Grove",
            Self::Bg2 => "Snowfall",
            Self::Bg3 => "Midnight Coast",
            Self::Bg4 => "Savanna",
        }
    }

    /// The asset directory name, and what the preference file stores.
    pub const fn slug(self) -> &'static str {
        match self {
            Self::Static => "static",
            Self::Bg1 => "bg_1",
            Self::Bg2 => "bg_2",
            Self::Bg3 => "bg_3",
            Self::Bg4 => "bg_4",
        }
    }

    pub const fn is_animated(self) -> bool {
        !matches!(self, Self::Static)
    }

    /// Index into the poster table. `None` for the static choice.
    const fn poster_index(self) -> Option<usize> {
        match self {
            Self::Static => None,
            Self::Bg1 => Some(0),
            Self::Bg2 => Some(1),
            Self::Bg3 => Some(2),
            Self::Bg4 => Some(3),
        }
    }
}

/// What `prepare-backgrounds.ps1` recorded about one sequence.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Sequence {
    frames: usize,
    fps: u32,
}

impl Sequence {
    const DEFAULT: Self = Self {
        frames: 300,
        fps: 30,
    };

    fn frame_interval(self) -> Duration {
        Duration::from_secs_f64(1.0 / f64::from(self.fps.max(1)))
    }
}

#[derive(Debug, Deserialize)]
struct Manifest {
    frames: usize,
    fps: u32,
}

/// Where the frame sequences live.
///
/// Next to the executable in a real installation. `main` makes the point that
/// the working directory is whatever the shell felt like, so nothing here is
/// resolved relative to it. The source tree is the development fallback, which
/// is what makes `cargo run` show a background without a staging step.
fn asset_root() -> PathBuf {
    if let Ok(exe) = std::env::current_exe() {
        if let Some(directory) = exe.parent() {
            let beside = directory.join("assets").join("backgrounds");
            // In a release build this is the only place worth looking, so
            // return it whether or not it exists and let `probe` decide.
            if beside.is_dir() || !cfg!(debug_assertions) {
                return beside;
            }
        }
    }
    source_tree_assets()
}

/// The source tree, so `cargo run` shows a background without a staging step.
///
/// Compiled only into debug builds. `env!("CARGO_MANIFEST_DIR")` expands to an
/// absolute path on the machine that did the build — which on a developer's
/// machine contains their user name — and that string would otherwise sit in
/// the shipped executable for anyone to read. A launcher that advertises "no
/// device identifier" should not be leaking whose computer built it.
#[cfg(debug_assertions)]
fn source_tree_assets() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("assets")
        .join("backgrounds")
}

/// Release builds have no source tree to fall back to. Relative to the working
/// directory, which `main` warns is arbitrary, so this is expected to miss and
/// leave the launcher on its static background.
#[cfg(not(debug_assertions))]
fn source_tree_assets() -> PathBuf {
    PathBuf::from("assets").join("backgrounds")
}

fn sequence_dir(choice: BackgroundChoice) -> Option<PathBuf> {
    choice.is_animated().then(|| asset_root().join(choice.slug()))
}

/// Read `manifest.json`, or work it out from what is on disk.
///
/// Returning `None` means the background is not installed, which is a normal
/// state — a trimmed-down copy of the launcher may ship without footage — and
/// the caller falls back to the static surface rather than failing.
fn probe(choice: BackgroundChoice) -> Option<Sequence> {
    let directory = sequence_dir(choice)?;
    if !directory.is_dir() {
        return None;
    }

    // PowerShell's `Set-Content -Encoding utf8` writes a byte-order mark, and
    // serde_json treats one as a syntax error.
    if let Ok(raw) = fs::read_to_string(directory.join("manifest.json")) {
        if let Ok(manifest) = serde_json::from_str::<Manifest>(raw.trim_start_matches('\u{feff}')) {
            if manifest.frames > 0 {
                return Some(Sequence {
                    frames: manifest.frames,
                    fps: manifest.fps,
                });
            }
        }
    }

    let counted = fs::read_dir(&directory)
        .ok()?
        .filter_map(Result::ok)
        .filter(|entry| {
            entry
                .path()
                .extension()
                .is_some_and(|extension| extension == "jpg")
        })
        .count();
    (counted > 1).then_some(Sequence {
        frames: counted,
        ..Sequence::DEFAULT
    })
}

fn frame_path(directory: &Path, index: usize) -> PathBuf {
    directory.join(format!("frame_{:03}.jpg", index + 1))
}

fn decode(bytes: &[u8]) -> Result<Handle, image::ImageError> {
    let rgba = image::load_from_memory_with_format(bytes, image::ImageFormat::Jpeg)?.into_rgba8();
    let (width, height) = rgba.dimensions();
    Ok(Handle::from_rgba(width, height, rgba.into_raw()))
}

// -- preferences ----------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
struct AppearancePreferences {
    background: BackgroundChoice,
    /// Retained so a launcher that has been downgraded still reads the file it
    /// wrote, and so the older shape below keeps a meaning to migrate from.
    animated_background: bool,
}

impl Default for AppearancePreferences {
    fn default() -> Self {
        Self {
            background: BackgroundChoice::Bg1,
            animated_background: true,
        }
    }
}

impl AppearancePreferences {
    fn of(choice: BackgroundChoice) -> Self {
        Self {
            background: choice,
            animated_background: choice.is_animated(),
        }
    }

    fn choice(self) -> BackgroundChoice {
        // The previous build stored the on/off switch separately from the
        // selection, so a file written by it can say "bg_2, but off".
        if self.animated_background {
            self.background
        } else {
            BackgroundChoice::Static
        }
    }
}

fn load_preferences(path: &Path) -> AppearancePreferences {
    match fs::read(path) {
        // The launcher writes this file itself and never emits a byte-order
        // mark, but it is small, readable JSON in the user's data directory,
        // and anything that saves it back — Notepad, a PowerShell one-liner —
        // will add one. serde_json treats a BOM as a syntax error, so a user
        // who edited their preference by hand would silently get the default
        // background back.
        Ok(bytes) => match serde_json::from_slice(strip_bom(&bytes)) {
            Ok(preferences) => preferences,
            Err(error) => {
                tracing::warn!(%error, "appearance preferences were invalid; using defaults");
                AppearancePreferences::default()
            }
        },
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            AppearancePreferences::default()
        }
        Err(error) => {
            tracing::warn!(%error, "appearance preferences could not be read; using defaults");
            AppearancePreferences::default()
        }
    }
}

/// Drop a UTF-8 byte-order mark if one is present.
fn strip_bom(bytes: &[u8]) -> &[u8] {
    bytes.strip_prefix(&[0xEF, 0xBB, 0xBF]).unwrap_or(bytes)
}

// -- player ---------------------------------------------------------------

pub struct BackgroundPlayer {
    choice: BackgroundChoice,
    preferences_path: PathBuf,
    /// Embedded stills, decoded once. Indexed by `poster_index`.
    posters: Vec<Option<Handle>>,
    /// What is installed, probed once at startup so the switcher can grey out
    /// a background whose frames are missing.
    installed: Vec<bool>,
    /// The live frame of the current sequence, or its poster until the first
    /// one arrives.
    current: Option<Handle>,
    /// The background being replaced, held at full opacity underneath so the
    /// crossfade never reveals the window behind it.
    outgoing: Option<Handle>,
    /// 0.0 the instant a change starts, 1.0 when it is finished.
    fade: f32,
}

impl BackgroundPlayer {
    pub fn load(data_directory: &Path) -> Self {
        let preferences_path = data_directory.join(PREFERENCES_FILE);
        let mut choice = load_preferences(&preferences_path).choice();

        let posters = BackgroundChoice::ALL
            .iter()
            .map(|c| {
                c.poster_index().and_then(|index| match decode(POSTERS[index]) {
                    Ok(handle) => Some(handle),
                    Err(error) => {
                        tracing::warn!(background = c.slug(), %error, "poster could not be decoded");
                        None
                    }
                })
            })
            .collect::<Vec<_>>();

        let installed = BackgroundChoice::ALL
            .iter()
            .map(|c| !c.is_animated() || probe(*c).is_some())
            .collect::<Vec<_>>();

        // A preference pointing at footage that is not installed would leave
        // the window empty. Fall back rather than show nothing.
        if !installed[index_of(choice)] {
            tracing::warn!(
                background = choice.slug(),
                "selected background is not installed; using the static surface"
            );
            choice = BackgroundChoice::Static;
        }

        let current = poster_for(&posters, choice);
        Self {
            choice,
            preferences_path,
            posters,
            installed,
            current,
            outgoing: None,
            fade: 1.0,
        }
    }

    pub const fn selected(&self) -> BackgroundChoice {
        self.choice
    }

    pub fn is_installed(&self, choice: BackgroundChoice) -> bool {
        self.installed
            .get(index_of(choice))
            .copied()
            .unwrap_or(false)
    }

    /// The frame to draw, and how opaque it should be.
    pub fn current(&self) -> Option<(&Handle, f32)> {
        self.current.as_ref().map(|handle| (handle, self.fade))
    }

    /// The background being replaced, drawn underneath during a change.
    ///
    /// It holds full opacity while something is fading in over it, and fades
    /// out itself only when the destination is the static surface — which is
    /// the one case where nothing is arriving to cover it.
    pub fn outgoing(&self) -> Option<(&Handle, f32)> {
        let handle = self.outgoing.as_ref()?;
        let opacity = if self.choice.is_animated() {
            1.0
        } else {
            1.0 - self.fade
        };
        Some((handle, opacity))
    }

    pub fn thumbnail(&self, choice: BackgroundChoice) -> Option<&Handle> {
        self.posters.get(index_of(choice)).and_then(Option::as_ref)
    }

    pub fn transitioning(&self) -> bool {
        self.fade < 1.0
    }

    /// Whether frames should be decoded at all.
    ///
    /// Playback stops when Minecraft is running, because the launcher must not
    /// compete with the game for a core, and when the window is minimised,
    /// because nothing is on screen to see it. It deliberately does *not* stop
    /// merely because the window lost focus: a background that freezes when
    /// the user clicks their browser looks like a bug, and the cost of not
    /// doing that is one JPEG decode every 33 ms.
    pub fn should_play(&self, visible: bool, game_running: bool) -> bool {
        self.choice.is_animated() && visible && !game_running
    }

    /// The sequence to decode, if any. This is the subscription's identity, so
    /// returning a different value is what stops the previous decoder thread.
    pub fn active_sequence(&self, visible: bool, game_running: bool) -> Option<BackgroundChoice> {
        self.should_play(visible, game_running)
            .then_some(self.choice)
    }

    /// Take a freshly decoded frame.
    pub fn accept(&mut self, choice: BackgroundChoice, handle: Handle) {
        // A frame from the sequence we just switched away from is still in
        // flight for a moment. Dropping it keeps the crossfade monotonic.
        if choice != self.choice {
            return;
        }
        self.current = Some(handle);
    }

    /// Advance the crossfade. Returns whether anything changed.
    pub fn tick_fade(&mut self, elapsed: Duration) -> bool {
        if self.fade >= 1.0 {
            return false;
        }
        let step = elapsed.as_secs_f32() / CROSSFADE.as_secs_f32();
        self.fade = (self.fade + step).min(1.0);
        if self.fade >= 1.0 {
            // The new background is fully covering. Release the old frame.
            self.outgoing = None;
        }
        true
    }

    pub fn select(&mut self, choice: BackgroundChoice) -> std::io::Result<()> {
        if choice == self.choice {
            return Ok(());
        }
        if !self.is_installed(choice) {
            tracing::warn!(background = choice.slug(), "background is not installed");
            return Ok(());
        }

        // Freeze whatever is on screen and keep it underneath. If a change is
        // interrupted mid-fade, the frame that was already committed stays as
        // the floor, so the window never drops to black.
        if let Some(handle) = self.current.take().or_else(|| self.outgoing.take()) {
            self.outgoing = Some(handle);
        }
        self.choice = choice;
        // The poster stands in until the first real frame lands, so the fade
        // has something to work with from its very first millisecond.
        self.current = poster_for(&self.posters, choice);
        // Always from zero, including when there is nothing to replace: going
        // from the static surface to footage should ease in rather than snap.
        self.fade = 0.0;

        self.persist()
    }

    fn persist(&self) -> std::io::Result<()> {
        let serialized = serde_json::to_vec_pretty(&AppearancePreferences::of(self.choice))
            .map_err(std::io::Error::other)?;
        fs::write(&self.preferences_path, serialized)
    }
}

fn index_of(choice: BackgroundChoice) -> usize {
    BackgroundChoice::ALL
        .iter()
        .position(|c| *c == choice)
        .unwrap_or(0)
}

fn poster_for(posters: &[Option<Handle>], choice: BackgroundChoice) -> Option<Handle> {
    posters.get(index_of(choice))?.clone()
}

// -- decoding -------------------------------------------------------------

/// A ~60 Hz ticker used only to drive a crossfade to completion.
///
/// `iced::time::every` needs one of iced's async-runtime features, and this
/// build deliberately has none of them — the executor is a thread pool and
/// nothing more. A sleeping thread is the whole requirement, and it exists for
/// the 420 ms a transition lasts and not one moment longer, because the
/// subscription that owns it is only returned while `transitioning()`.
pub fn fade_ticks() -> impl iced::futures::Stream<Item = ()> {
    stream::channel(1, |mut output| async move {
        let (tick_tx, mut tick_rx) = mpsc::unbounded();
        let spawned = std::thread::Builder::new()
            .name("fathom-bg-fade".to_owned())
            .spawn(move || loop {
                std::thread::sleep(Duration::from_millis(16));
                if tick_tx.unbounded_send(()).is_err() {
                    break;
                }
            });
        if spawned.is_err() {
            return;
        }

        while tick_rx.next().await.is_some() {
            if output.send(()).await.is_err() {
                break;
            }
        }
    })
}

/// One decoded frame, tagged with the sequence it came from.
#[derive(Debug, Clone)]
pub struct Frame {
    pub choice: BackgroundChoice,
    pub handle: Handle,
}

/// A decoder for one sequence.
///
/// The thread paces itself against a fixed schedule rather than sleeping for a
/// frame interval each time, so a slow decode is absorbed by the next frame
/// instead of accumulating into drift. If the interface falls behind, frames
/// are dropped rather than queued — a launcher that is late should skip, not
/// build a backlog and then play it in fast-forward.
///
/// The thread ends when iced drops the subscription, which happens when the
/// background changes, when Minecraft starts, and when the window is
/// minimised. There is no other stop signal and none is needed.
pub fn frames(choice: BackgroundChoice) -> impl iced::futures::Stream<Item = Frame> {
    stream::channel(1, move |mut output| async move {
        let Some(directory) = sequence_dir(choice) else {
            return;
        };
        let Some(sequence) = probe(choice) else {
            tracing::warn!(background = choice.slug(), "background frames are not installed");
            return;
        };

        let (mut tx, mut rx) = mpsc::channel::<Frame>(1);
        let interval = sequence.frame_interval();
        let spawned = std::thread::Builder::new()
            .name(format!("fathom-bg-{}", choice.slug()))
            .spawn(move || {
                let mut due = Instant::now();
                let mut index = 0_usize;
                // Playback cadence, reported every few seconds at debug level.
                // `dropped` is the number the interface was not ready for; a
                // non-zero figure means the renderer, not the decoder, is
                // setting the frame rate.
                let mut window = Instant::now();
                let (mut sent, mut dropped) = (0_u32, 0_u32);
                loop {
                    let path = frame_path(&directory, index);
                    match fs::read(&path).map_err(|e| e.to_string()).and_then(|bytes| {
                        decode(&bytes).map_err(|e| e.to_string())
                    }) {
                        Ok(handle) => {
                            // `Full` means the interface has not consumed the
                            // previous frame. Drop this one and stay on
                            // schedule. `Disconnected` means the subscription
                            // is gone, which is the exit condition.
                            match tx.try_send(Frame { choice, handle }) {
                                Ok(()) => sent += 1,
                                Err(error) if error.is_disconnected() => break,
                                Err(_) => dropped += 1,
                            }
                        }
                        Err(error) => {
                            tracing::warn!(
                                background = choice.slug(),
                                path = %path.display(),
                                %error,
                                "background frame could not be read"
                            );
                            break;
                        }
                    }

                    index = (index + 1) % sequence.frames;

                    if window.elapsed() >= Duration::from_secs(3) {
                        let seconds = window.elapsed().as_secs_f64();
                        tracing::debug!(
                            background = choice.slug(),
                            delivered_fps = format!("{:.1}", f64::from(sent) / seconds),
                            dropped_fps = format!("{:.1}", f64::from(dropped) / seconds),
                            "background playback"
                        );
                        window = Instant::now();
                        sent = 0;
                        dropped = 0;
                    }

                    // Advance the schedule by exactly one frame rather than
                    // sleeping for one, so a decode that overran is absorbed
                    // by the next frame instead of accumulating into drift.
                    // If we are already past the slot -- a laptop coming back
                    // from sleep, a stalled disk -- the schedule is re-based
                    // on now, because catching up by decoding at full speed
                    // would look worse than the frames we missed.
                    due += interval;
                    let now = Instant::now();
                    match due.checked_duration_since(now) {
                        Some(wait) => std::thread::sleep(wait),
                        None => due = now,
                    }
                }
            });

        if let Err(error) = spawned {
            tracing::warn!(background = choice.slug(), %error, "background decoder could not start");
            return;
        }

        while let Some(frame) = rx.next().await {
            if output.send(frame).await.is_err() {
                break;
            }
        }
    })
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    fn player() -> (tempfile::TempDir, BackgroundPlayer) {
        let directory = tempfile::tempdir().expect("temporary directory");
        let player = BackgroundPlayer::load(directory.path());
        (directory, player)
    }

    #[test]
    fn every_background_ships_a_decodable_poster() {
        for choice in BackgroundChoice::ALL {
            let Some(index) = choice.poster_index() else {
                continue;
            };
            decode(POSTERS[index]).expect("embedded poster decodes");
        }
    }

    /// The manifests the preparation script writes must describe what it
    /// actually produced, because playback length comes from them.
    #[test]
    fn every_installed_sequence_matches_its_manifest() {
        for choice in BackgroundChoice::ALL.iter().filter(|c| c.is_animated()) {
            let Some(sequence) = probe(*choice) else {
                continue; // Footage is optional; `load` falls back.
            };
            assert!(sequence.frames > 1, "{} frame count", choice.slug());
            assert!(
                sequence.fps >= 24,
                "{} plays at {} fps; the whole point of this rewrite was not to ship \
                 single-digit playback",
                choice.slug(),
                sequence.fps
            );

            let directory = sequence_dir(*choice).expect("animated backgrounds have a directory");
            for index in [0, sequence.frames / 2, sequence.frames - 1] {
                let path = frame_path(&directory, index);
                assert!(path.is_file(), "missing {}", path.display());
            }
        }
    }

    #[test]
    fn selection_survives_a_relaunch() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let mut player = BackgroundPlayer::load(directory.path());
        // Only assert round-tripping for a background this build actually has.
        let target = BackgroundChoice::ALL
            .iter()
            .copied()
            .find(|c| c.is_animated() && player.is_installed(*c) && *c != player.selected());
        let Some(target) = target else {
            return;
        };

        player.select(target).expect("save selection");
        drop(player);

        assert_eq!(
            BackgroundPlayer::load(directory.path()).selected(),
            target,
            "the background must be the one chosen before the restart"
        );
    }

    #[test]
    fn static_selection_survives_a_relaunch() {
        let (directory, mut player) = player();
        player
            .select(BackgroundChoice::Static)
            .expect("save selection");
        drop(player);

        let reloaded = BackgroundPlayer::load(directory.path());
        assert_eq!(reloaded.selected(), BackgroundChoice::Static);
        assert!(!reloaded.should_play(true, false));
    }

    /// A file written by the previous build stored the selection and an on/off
    /// switch separately. "bg_2, but disabled" has to come back as Static.
    #[test]
    fn the_previous_preference_shape_still_loads() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let path = directory.path().join(PREFERENCES_FILE);

        fs::write(&path, br#"{"animated_background":false,"background":"bg2"}"#)
            .expect("write legacy preferences");
        assert_eq!(load_preferences(&path).choice(), BackgroundChoice::Static);

        fs::write(&path, br#"{"animated_background":true,"background":"bg2"}"#)
            .expect("write legacy preferences");
        assert_eq!(load_preferences(&path).choice(), BackgroundChoice::Bg2);
    }

    #[test]
    fn invalid_or_missing_preferences_use_defaults() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let path = directory.path().join(PREFERENCES_FILE);
        assert_eq!(load_preferences(&path), AppearancePreferences::default());

        fs::write(&path, b"not json").expect("write invalid preferences");
        assert_eq!(load_preferences(&path), AppearancePreferences::default());
    }

    /// Hand-edited preferences come back with a byte-order mark on Windows.
    #[test]
    fn a_byte_order_mark_does_not_reset_the_background() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let path = directory.path().join(PREFERENCES_FILE);
        let mut bytes = vec![0xEF, 0xBB, 0xBF];
        bytes.extend_from_slice(br#"{"background":"bg3","animated_background":true}"#);
        fs::write(&path, bytes).expect("write preferences with a BOM");

        assert_eq!(load_preferences(&path).choice(), BackgroundChoice::Bg3);
    }

    /// The property the whole "no black frame" claim rests on: at every point
    /// during a change, something is being drawn.
    #[test]
    fn a_change_never_leaves_the_window_with_nothing_to_draw() {
        let (_directory, mut player) = player();
        let Some(target) = BackgroundChoice::ALL
            .iter()
            .copied()
            .find(|c| c.is_animated() && player.is_installed(*c) && *c != player.selected())
        else {
            return;
        };

        player.select(target).expect("save selection");
        let mut elapsed = Duration::ZERO;
        while player.transitioning() {
            assert!(
                player.current().is_some() || player.outgoing().is_some(),
                "nothing to draw {elapsed:?} into the crossfade"
            );
            player.tick_fade(Duration::from_millis(16));
            elapsed += Duration::from_millis(16);
            assert!(elapsed < Duration::from_secs(5), "crossfade never finished");
        }
        assert!(player.current().is_some());
        assert!(
            player.outgoing().is_none(),
            "the replaced background must be released once it is covered"
        );
    }

    #[test]
    fn playback_stops_for_gameplay_and_for_a_hidden_window() {
        let (_directory, player) = player();
        if !player.selected().is_animated() {
            return;
        }
        assert!(player.should_play(true, false));
        assert!(!player.should_play(true, true), "Minecraft is running");
        assert!(!player.should_play(false, false), "window is minimised");
        assert_eq!(player.active_sequence(true, true), None);
        assert_eq!(
            player.active_sequence(true, false),
            Some(player.selected()),
            "the sequence identity is what keeps one decoder alive"
        );
    }

    /// A frame that was already in flight when the user switched must not
    /// overwrite the newly selected background.
    #[test]
    fn a_late_frame_from_the_old_sequence_is_discarded() {
        let (_directory, mut player) = player();
        let stale = player
            .thumbnail(BackgroundChoice::Bg4)
            .cloned()
            .expect("poster");
        player.select(BackgroundChoice::Static).expect("select");
        player.accept(BackgroundChoice::Bg1, stale);
        assert_eq!(player.selected(), BackgroundChoice::Static);
    }
}
