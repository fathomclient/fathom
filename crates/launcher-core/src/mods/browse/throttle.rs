//! Request pacing for one mod index.
//!
//! # Why the launcher paces itself
//!
//! Modrinth documents a limit of 300 requests a minute per IP, and `CurseForge`
//! throttles by key. A browser that verifies every visible row, loads icons,
//! and follows dependency trees can produce a burst well past either if nothing
//! holds it back — and when that happens the index answers 429 for everybody
//! behind the same address, including the game's own launcher on a shared
//! connection.
//!
//! So each provider owns one [`Throttle`]: a token bucket that allows a short
//! burst (a search plus its first screen of checks) and then settles to a
//! steady rate comfortably under the published limit. When an index says "slow
//! down" with a 429 and a wait, [`Throttle::back_off`] stops every request to
//! that index until the wait is over, rather than each request discovering the
//! limit for itself.
//!
//! # Blocking, and cancellable
//!
//! [`Throttle::acquire`] sleeps. It is only ever called on the worker threads
//! the browser runs on, and it wakes every few tens of milliseconds to check
//! the caller's [`Cancel`], so a search the user has typed past stops waiting
//! for its turn as soon as they move on.

use std::sync::Mutex;
use std::time::{Duration, Instant};

use crate::tasks::Cancel;

/// The longest single sleep between cancellation checks.
const NAP: Duration = Duration::from_millis(40);

#[derive(Debug)]
struct Bucket {
    tokens: f64,
    refilled: Instant,
    /// No request before this instant, whatever the bucket holds.
    paused_until: Option<Instant>,
}

/// A token bucket shared by every request to one index.
#[derive(Debug)]
pub struct Throttle {
    /// Tokens added per second: the sustained request rate.
    rate: f64,
    /// The most tokens the bucket holds: the largest burst.
    burst: f64,
    bucket: Mutex<Bucket>,
}

/// Why [`Throttle::acquire`] gave up.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Wait {
    /// The caller cancelled while waiting.
    Cancelled,
    /// The index asked us to stop for longer than this caller is willing to
    /// wait; the remaining pause is in whole seconds.
    Paused { seconds: u64 },
}

impl Throttle {
    /// `rate` requests per second sustained, `burst` at once from rest.
    pub fn new(rate: f64, burst: u32) -> Self {
        let burst = f64::from(burst.max(1));
        Self {
            rate: rate.max(0.1),
            burst,
            bucket: Mutex::new(Bucket {
                tokens: burst,
                refilled: Instant::now(),
                paused_until: None,
            }),
        }
    }

    /// Pacing for Modrinth: two and a half a second after a burst of twelve. Three
    /// hundred a minute is the documented ceiling; this stays at a hundred
    /// and fifty sustained (half of it) so a second Fathom window, or the
    /// user's browser on modrinth.com, still has room.
    pub fn modrinth() -> Self {
        Self::new(2.5, 12)
    }

    /// Pacing for `CurseForge`, which does not publish a number. Conservative.
    pub fn curseforge() -> Self {
        Self::new(2.0, 8)
    }

    fn refill(&self, bucket: &mut Bucket, now: Instant) {
        let elapsed = now.saturating_duration_since(bucket.refilled).as_secs_f64();
        bucket.tokens = (bucket.tokens + elapsed * self.rate).min(self.burst);
        bucket.refilled = now;
    }

    /// Take one request's worth of budget, sleeping until there is some.
    ///
    /// `patience` is the longest this caller will sit out an index-imposed
    /// pause. A search box should not freeze for a five-minute 429; it should
    /// say so. Ordinary pacing waits are always short and always taken.
    pub fn acquire(&self, cancel: &Cancel, patience: Duration) -> Result<(), Wait> {
        loop {
            if cancel.is_cancelled() {
                return Err(Wait::Cancelled);
            }
            let now = Instant::now();
            let wait = {
                let Ok(mut bucket) = self.bucket.lock() else {
                    // A poisoned pacer paces nothing; requests still carry
                    // their own timeouts and 429 handling.
                    return Ok(());
                };
                if let Some(until) = bucket.paused_until {
                    if until > now {
                        let remaining = until - now;
                        if remaining > patience {
                            return Err(Wait::Paused {
                                seconds: remaining.as_secs().max(1),
                            });
                        }
                        Some(remaining)
                    } else {
                        bucket.paused_until = None;
                        None
                    }
                } else {
                    None
                }
                .or_else(|| {
                    self.refill(&mut bucket, now);
                    if bucket.tokens >= 1.0 {
                        bucket.tokens -= 1.0;
                        None
                    } else {
                        Some(Duration::from_secs_f64((1.0 - bucket.tokens) / self.rate))
                    }
                })
            };
            match wait {
                None => return Ok(()),
                Some(wait) => std::thread::sleep(wait.min(NAP)),
            }
        }
    }

    /// The index told us to stop for `seconds`. Every request waits it out.
    pub fn back_off(&self, seconds: u64) {
        let until = Instant::now() + Duration::from_secs(seconds.max(1));
        if let Ok(mut bucket) = self.bucket.lock() {
            bucket.tokens = 0.0;
            bucket.paused_until = Some(bucket.paused_until.map_or(until, |at| at.max(until)));
        }
    }

    /// Seconds left on an index-imposed pause, if one is running.
    pub fn paused_for(&self) -> Option<u64> {
        let bucket = self.bucket.lock().ok()?;
        let until = bucket.paused_until?;
        let now = Instant::now();
        (until > now).then(|| (until - now).as_secs().max(1))
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    #[test]
    fn a_burst_is_free_and_the_next_request_waits_for_the_rate() {
        let throttle = Throttle::new(20.0, 3);
        let cancel = Cancel::new();
        let start = Instant::now();
        for _ in 0..3 {
            throttle.acquire(&cancel, Duration::ZERO).unwrap();
        }
        assert!(
            start.elapsed() < Duration::from_millis(30),
            "the burst was paced"
        );
        throttle.acquire(&cancel, Duration::ZERO).unwrap();
        // One token at 20/s is 50 ms.
        assert!(start.elapsed() >= Duration::from_millis(35));
    }

    #[test]
    fn a_pause_longer_than_the_caller_will_wait_is_reported_not_slept() {
        let throttle = Throttle::new(10.0, 5);
        throttle.back_off(120);
        let cancel = Cancel::new();
        let start = Instant::now();
        let outcome = throttle.acquire(&cancel, Duration::from_secs(2));
        assert!(matches!(outcome, Err(Wait::Paused { seconds }) if seconds >= 119));
        assert!(start.elapsed() < Duration::from_millis(50));
        assert!(throttle.paused_for().is_some());
    }

    #[test]
    fn cancelling_ends_the_wait() {
        let throttle = Throttle::new(0.1, 1);
        let cancel = Cancel::new();
        throttle.acquire(&cancel, Duration::ZERO).unwrap();
        cancel.cancel();
        assert_eq!(
            throttle.acquire(&cancel, Duration::ZERO),
            Err(Wait::Cancelled)
        );
    }

    #[test]
    fn a_later_shorter_pause_does_not_cut_a_longer_one_short() {
        let throttle = Throttle::new(10.0, 5);
        throttle.back_off(60);
        throttle.back_off(1);
        assert!(throttle.paused_for().unwrap() >= 59);
    }
}
