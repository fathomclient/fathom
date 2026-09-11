//! The animation system.
//!
//! # What this is for
//!
//! iced redraws when a message arrives; it has no notion of a value that
//! changes over time on its own. Animation therefore needs three things: a
//! number that knows where it is heading, a clock that advances it, and a rule
//! that stops the clock when nothing is moving. This module is those three
//! things, and the last one is the important one — a launcher that repaints
//! sixty times a second while sitting idle on a menu is a laptop battery
//! problem, not a polished interface.
//!
//! # The house style
//!
//! Fast, short, and linear-ish. Everything lands between [`FAST`] and [`SLOW`],
//! which is 110–220 ms. Nothing bounces, nothing overshoots, nothing springs.
//! The easing is a cubic ease-out: quick to start, settling at the end, which
//! reads as responsive rather than as an effect. An interface that draws
//! attention to its own transitions is showing you the transition instead of
//! the thing you asked for.
//!
//! # How a screen uses it
//!
//! Hold an [`Animated`] in the application state, call [`Animated::retarget`]
//! when the destination changes, and call [`Animated::advance`] from a tick.
//! [`Animated::is_moving`] decides whether the tick subscription runs at all.

// A complete, small vocabulary rather than only the two durations currently
// referenced: the point of naming FAST/NORMAL/SLOW is that a screen picks one
// instead of inventing a number, and that only works if all three exist before
// they are needed. Every one is exercised by this module's tests.
#![allow(dead_code)]

use std::time::Duration;

use iced::futures::channel::mpsc;
use iced::futures::{SinkExt, StreamExt};
use iced::stream;

/// Roughly one frame at 60 Hz. The resolution everything here works at.
pub const TICK: Duration = Duration::from_millis(16);

/// Selection marks, toggles, hovers — anything the user expects to feel
/// instant but not abrupt.
pub const FAST: Duration = Duration::from_millis(110);

/// The default. Page changes, popovers, status changes.
pub const NORMAL: Duration = Duration::from_millis(160);

/// The slowest thing allowed. Background crossfades, and nothing else without
/// a reason.
pub const SLOW: Duration = Duration::from_millis(220);

/// Cubic ease-out.
///
/// Chosen over ease-in-out because a transition the user *caused* should begin
/// at full speed: they already know it started, they pressed the thing. Easing
/// in as well adds a delay before anything visibly happens, which reads as lag.
fn ease_out(t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    let inverted = 1.0 - t;
    1.0 - inverted * inverted * inverted
}

/// A number that moves toward a target over a fixed duration.
///
/// Progress is tracked separately from the eased output so that retargeting
/// mid-flight is continuous: the value carries on from where it visually is
/// rather than snapping back to the start of a new curve.
#[derive(Debug, Clone, Copy)]
pub struct Animated {
    from: f32,
    to: f32,
    /// 0.0 at the start of the current move, 1.0 when it is finished.
    progress: f32,
    duration: Duration,
}

impl Animated {
    /// A value that starts settled at `value`.
    pub const fn settled(value: f32) -> Self {
        Self {
            from: value,
            to: value,
            progress: 1.0,
            duration: NORMAL,
        }
    }

    pub const fn with_duration(mut self, duration: Duration) -> Self {
        self.duration = duration;
        self
    }

    /// Where the value is right now.
    pub fn value(&self) -> f32 {
        if self.progress >= 1.0 {
            return self.to;
        }
        let eased = ease_out(self.progress);
        self.from + (self.to - self.from) * eased
    }

    /// Whether a clock needs to be running for this value.
    pub fn is_moving(&self) -> bool {
        self.progress < 1.0
    }

    /// Aim at a new target.
    ///
    /// Starts from the current *visible* position, so a value interrupted
    /// half-way does not jump. Retargeting somewhere it is already heading is
    /// ignored, which keeps a repeated message from restarting the motion and
    /// making it look stuck.
    pub fn retarget(&mut self, to: f32) {
        if (self.to - to).abs() < f32::EPSILON {
            return;
        }
        self.from = self.value();
        self.to = to;
        self.progress = 0.0;
    }

    /// Jump straight there, with no animation. For a state change the user did
    /// not cause and should not see moving — restoring a window, say.
    pub fn snap(&mut self, to: f32) {
        self.from = to;
        self.to = to;
        self.progress = 1.0;
    }

    /// Advance by one tick. Returns whether anything changed, so a caller can
    /// avoid requesting a redraw for a value that has already settled.
    pub fn advance(&mut self, elapsed: Duration) -> bool {
        if self.progress >= 1.0 {
            return false;
        }
        let step = elapsed.as_secs_f32() / self.duration.as_secs_f32().max(f32::EPSILON);
        self.progress = (self.progress + step).min(1.0);
        true
    }
}

impl Default for Animated {
    fn default() -> Self {
        Self::settled(0.0)
    }
}

/// A 60 Hz clock, alive only while something is animating.
///
/// The same shape as the background decoder's ticker and for the same reason:
/// this build has no async runtime, so `iced::time::every` is unavailable and a
/// sleeping thread is the whole requirement. The thread ends when iced drops
/// the subscription, which happens the moment the caller stops returning it.
pub fn ticks() -> impl iced::futures::Stream<Item = ()> {
    stream::channel(1, |mut output| async move {
        let (tx, mut rx) = mpsc::unbounded();
        let spawned = std::thread::Builder::new()
            .name("fathom-motion".to_owned())
            .spawn(move || loop {
                std::thread::sleep(TICK);
                if tx.unbounded_send(()).is_err() {
                    break;
                }
            });
        if spawned.is_err() {
            return;
        }
        while rx.next().await.is_some() {
            if output.send(()).await.is_err() {
                break;
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run_to_completion(value: &mut Animated) -> u32 {
        let mut ticks = 0;
        while value.is_moving() {
            value.advance(TICK);
            ticks += 1;
            assert!(ticks < 1000, "animation never settled");
        }
        ticks
    }

    #[test]
    fn a_settled_value_needs_no_clock() {
        let mut value = Animated::settled(1.0);
        assert!(!value.is_moving());
        assert!(!value.advance(TICK), "a settled value reported a change");
        assert_eq!(value.value(), 1.0);
    }

    #[test]
    fn a_value_reaches_its_target_and_stops() {
        let mut value = Animated::settled(0.0).with_duration(NORMAL);
        value.retarget(1.0);
        assert!(value.is_moving());

        let ticks = run_to_completion(&mut value);
        assert_eq!(value.value(), 1.0);
        assert!(!value.is_moving(), "the clock would keep running");

        // 160ms at 16ms a tick, give or take the final clamp.
        assert!((9..=11).contains(&ticks), "settled in {ticks} ticks");
    }

    /// The property that keeps fast clicking from looking broken: a value
    /// redirected mid-flight continues from where it is rather than snapping.
    #[test]
    fn retargeting_midway_is_continuous() {
        let mut value = Animated::settled(0.0);
        value.retarget(1.0);
        for _ in 0..4 {
            value.advance(TICK);
        }
        let midway = value.value();
        assert!(midway > 0.0 && midway < 1.0, "expected to be in flight");

        value.retarget(0.0);
        let after = value.value();
        assert!(
            (midway - after).abs() < 0.001,
            "jumped from {midway} to {after} when redirected"
        );

        run_to_completion(&mut value);
        assert_eq!(value.value(), 0.0);
    }

    /// Re-sending the same target must not restart the motion, or a message
    /// that repeats every frame would freeze the value at its first step.
    #[test]
    fn retargeting_somewhere_it_is_already_going_is_ignored() {
        let mut value = Animated::settled(0.0);
        value.retarget(1.0);
        for _ in 0..3 {
            value.advance(TICK);
        }
        let before = value.value();
        value.retarget(1.0);
        assert!(
            value.value() >= before,
            "the value went backwards on a repeated retarget"
        );
        assert!(run_to_completion(&mut value) < 12);
    }

    #[test]
    fn snapping_skips_the_animation_entirely() {
        let mut value = Animated::settled(0.0);
        value.snap(1.0);
        assert_eq!(value.value(), 1.0);
        assert!(!value.is_moving());
    }

    /// Ease-out, not ease-in: over a third of the distance is covered in the
    /// first third of the time. This is what makes it feel responsive, and a
    /// change of curve that broke it should fail here.
    #[test]
    fn the_curve_starts_fast_and_settles() {
        assert!(ease_out(0.33) > 0.33, "the curve does not start fast");
        assert!(ease_out(0.9) > 0.99, "the curve does not settle");
        assert_eq!(ease_out(0.0), 0.0);
        assert_eq!(ease_out(1.0), 1.0);
        assert_eq!(ease_out(-5.0), 0.0, "out-of-range input must clamp");
        assert_eq!(ease_out(5.0), 1.0, "out-of-range input must clamp");
    }

    /// The house style is a range, and it is narrow on purpose.
    #[test]
    fn every_duration_is_within_the_house_range() {
        for duration in [FAST, NORMAL, SLOW] {
            assert!(
                duration >= Duration::from_millis(100)
                    && duration <= Duration::from_millis(220),
                "{duration:?} is outside the 100-220ms range this file promises"
            );
        }
        assert!(FAST < NORMAL && NORMAL < SLOW);
    }
}
