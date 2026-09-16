//! Typing produces one search, not one per keystroke.
//!
//! # What goes wrong without this
//!
//! A search box that fires on every keystroke sends "s", "so", "sod", "sodi",
//! "sodiu", "sodium" — six requests to two providers, five of whose answers are
//! already obsolete when they arrive, and the sixth of which may arrive
//! *before* the fifth and be overwritten by it. It is also the fastest way to
//! be rate limited by an index that is doing nothing wrong.
//!
//! # The three rules
//!
//! 1. **Debounce.** A request is issued only when the query has been still for
//!    [`DEFAULT_DEBOUNCE`]. The caller is told how long to wait rather than
//!    being blocked here, so the waiting happens on its own thread.
//! 2. **One in flight.** Starting a search cancels the one before it. The
//!    cancellation reaches the HTTP layer, so a long request stops rather than
//!    finishing into a cache nobody is reading.
//! 3. **Stale answers are dropped.** Every issued search carries a generation;
//!    a result whose generation is not the current one is discarded by the
//!    coordinator rather than by the interface remembering to check.
//!
//! None of this knows what a search *is*. It is a clock, a counter and a
//! cancellation flag, which is why it can be tested without a network.

use std::sync::Mutex;
use std::time::{Duration, Instant};

use crate::tasks::Cancel;

/// How still the query has to be before a request goes out.
///
/// Long enough to swallow a burst of typing, short enough that it does not feel
/// like the box is ignoring you. Tuned by how fast people type, not by a round
/// number: 200 ms is roughly one character at a fast typist's pace.
pub const DEFAULT_DEBOUNCE: Duration = Duration::from_millis(220);

/// A search that was allowed to start.
#[derive(Debug)]
pub struct SearchTicket {
    generation: u64,
    cancel: Cancel,
    key: String,
}

impl SearchTicket {
    /// The cancellation flag to hand to the providers.
    pub fn cancel(&self) -> &Cancel {
        &self.cancel
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    /// What this ticket was issued for. For logging, and for the interface to
    /// match a result to the box it belongs in.
    pub fn key(&self) -> &str {
        &self.key
    }
}

/// What the coordinator decided about a request.
#[derive(Debug)]
pub enum Decision {
    /// Go ahead. The previous search, if any, has been cancelled.
    Issue(SearchTicket),
    /// Wait this long and ask again. The query is still changing.
    Wait(Duration),
    /// This is the query already running or already answered. Nothing to do.
    Duplicate,
}

#[derive(Debug, Default)]
struct State {
    generation: u64,
    last_key: String,
    last_issued: Option<Instant>,
    in_flight: Option<Cancel>,
    /// The key the user last asked for, which may not be the one in flight.
    pending_key: String,
    pending_since: Option<Instant>,
}

/// Decides when a search may start, and which answers are still wanted.
#[derive(Debug)]
pub struct SearchCoordinator {
    state: Mutex<State>,
    debounce: Duration,
}

impl Default for SearchCoordinator {
    fn default() -> Self {
        Self::new(DEFAULT_DEBOUNCE)
    }
}

impl SearchCoordinator {
    pub fn new(debounce: Duration) -> Self {
        Self {
            state: Mutex::new(State::default()),
            debounce,
        }
    }

    /// Ask to run a search for `key` at time `now`.
    ///
    /// `now` is a parameter rather than read from the clock so the rule can be
    /// tested without sleeping.
    pub fn begin_at(&self, key: &str, now: Instant) -> Decision {
        let Ok(mut state) = self.state.lock() else {
            // A poisoned lock means a thread panicked mid-search. Refusing to
            // search is better than searching with state nobody can trust.
            return Decision::Duplicate;
        };

        if state.pending_key != key {
            key.clone_into(&mut state.pending_key);
            state.pending_since = Some(now);
        }

        // The same query as the last one issued: the answer is either in flight
        // or cached, and asking again would be the duplicate request this type
        // exists to prevent.
        if state.last_key == key && state.last_issued.is_some() {
            return Decision::Duplicate;
        }

        let settled_for = state
            .pending_since
            .map_or(self.debounce, |since| now.saturating_duration_since(since));
        if settled_for < self.debounce {
            return Decision::Wait(self.debounce.saturating_sub(settled_for));
        }

        // Anything still running is for a query the user has moved on from.
        if let Some(previous) = state.in_flight.take() {
            previous.cancel();
        }

        state.generation = state.generation.wrapping_add(1);
        key.clone_into(&mut state.last_key);
        state.last_issued = Some(now);
        let cancel = Cancel::new();
        state.in_flight = Some(cancel.clone());

        Decision::Issue(SearchTicket {
            generation: state.generation,
            cancel,
            key: key.to_owned(),
        })
    }

    /// [`Self::begin_at`] with the current time.
    pub fn begin(&self, key: &str) -> Decision {
        self.begin_at(key, Instant::now())
    }

    /// Whether a ticket's results are still the ones being waited for.
    ///
    /// Checked after the request returns, so an answer that arrives late — or
    /// out of order — is dropped rather than overwriting a newer one.
    pub fn is_current(&self, ticket: &SearchTicket) -> bool {
        self.state
            .lock()
            .is_ok_and(|state| state.generation == ticket.generation)
    }

    /// Mark a search finished. Returns whether its results should be used.
    pub fn finish(&self, ticket: &SearchTicket) -> bool {
        let Ok(mut state) = self.state.lock() else {
            return false;
        };
        let current = state.generation == ticket.generation;
        if current {
            state.in_flight = None;
        }
        current
    }

    /// Stop whatever is running and forget what was asked.
    ///
    /// Called when the browser closes: a search still running against a screen
    /// that no longer exists is work nobody will read.
    pub fn reset(&self) {
        if let Ok(mut state) = self.state.lock() {
            if let Some(cancel) = state.in_flight.take() {
                cancel.cancel();
            }
            state.last_key.clear();
            state.last_issued = None;
            state.pending_key.clear();
            state.pending_since = None;
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    fn ticket(decision: Decision) -> SearchTicket {
        match decision {
            Decision::Issue(ticket) => ticket,
            other => panic!("expected a search to be issued, got {other:?}"),
        }
    }

    #[test]
    fn typing_quickly_issues_one_search_and_not_six() {
        let coordinator = SearchCoordinator::new(Duration::from_millis(200));
        let start = Instant::now();

        let mut issued = 0;
        for (index, text) in ["s", "so", "sod", "sodi", "sodiu", "sodium"]
            .into_iter()
            .enumerate()
        {
            // One keystroke every 50 ms.
            let now = start + Duration::from_millis(50 * index as u64);
            if let Decision::Issue(_) = coordinator.begin_at(text, now) {
                issued += 1;
            }
        }
        assert_eq!(issued, 0, "nothing goes out while the query keeps changing");

        // The user stops typing.
        let settled = start + Duration::from_millis(250 + 200);
        assert!(matches!(
            coordinator.begin_at("sodium", settled),
            Decision::Issue(_)
        ));
    }

    #[test]
    fn a_waiting_caller_is_told_how_long_to_wait() {
        let coordinator = SearchCoordinator::new(Duration::from_millis(200));
        let start = Instant::now();
        match coordinator.begin_at("so", start) {
            Decision::Wait(remaining) => {
                assert!(remaining <= Duration::from_millis(200) && !remaining.is_zero());
            }
            other => panic!("expected a wait, got {other:?}"),
        }
    }

    #[test]
    fn asking_the_same_question_twice_does_not_ask_twice() {
        let coordinator = SearchCoordinator::new(Duration::from_millis(0));
        let now = Instant::now();
        let _ = ticket(coordinator.begin_at("sodium", now));
        assert!(matches!(
            coordinator.begin_at("sodium", now + Duration::from_secs(1)),
            Decision::Duplicate
        ));
    }

    #[test]
    fn a_new_search_cancels_the_one_before_it() {
        let coordinator = SearchCoordinator::new(Duration::from_millis(0));
        let now = Instant::now();
        let first = ticket(coordinator.begin_at("sod", now));
        let first_cancel = first.cancel().clone();
        assert!(!first_cancel.is_cancelled());

        let second = ticket(coordinator.begin_at("sodium", now + Duration::from_millis(1)));
        assert!(
            first_cancel.is_cancelled(),
            "the older request must stop rather than finish into nothing"
        );
        assert!(!second.cancel().is_cancelled());
    }

    #[test]
    fn a_stale_answer_is_dropped_even_if_it_arrives_last() {
        let coordinator = SearchCoordinator::new(Duration::from_millis(0));
        let now = Instant::now();
        let first = ticket(coordinator.begin_at("sod", now));
        let second = ticket(coordinator.begin_at("sodium", now + Duration::from_millis(1)));

        // The slow first request comes back after the second one.
        assert!(!coordinator.is_current(&first));
        assert!(!coordinator.finish(&first), "its results are not wanted");
        assert!(coordinator.is_current(&second));
        assert!(coordinator.finish(&second));
    }

    #[test]
    fn resetting_stops_whatever_was_running() {
        let coordinator = SearchCoordinator::new(Duration::from_millis(0));
        let issued = ticket(coordinator.begin_at("sodium", Instant::now()));
        coordinator.reset();
        assert!(issued.cancel().is_cancelled());
        // And the same query may be asked again afterwards.
        assert!(matches!(
            coordinator.begin_at("sodium", Instant::now()),
            Decision::Issue(_)
        ));
    }
}
