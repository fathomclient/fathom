//! An in-memory record of every request the launcher made, and why.
//!
//! This is the opposite of telemetry: it never leaves the machine, it is never
//! written to disk, and it exists so the *user* can audit *us*. The Privacy /
//! Network screen renders it directly.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use super::policy::{Destination, Feature, PolicyDenial, Purpose, RequestDescriptor};

/// How many entries to retain. Bounded so a long session cannot grow without
/// limit; the oldest entries are dropped.
const CAPACITY: usize = 500;

/// What happened to a request.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// Refused before any socket was opened.
    Denied(PolicyDenial),
    /// Completed with an HTTP status.
    Completed { status: u16, bytes: u64 },
    /// Transport-level failure (DNS, TLS, timeout).
    Failed(String),
}

impl Outcome {
    pub fn is_success(&self) -> bool {
        matches!(self, Outcome::Completed { status, .. } if (200..300).contains(status))
    }
}

/// One journal line.
#[derive(Debug, Clone)]
pub struct Entry {
    pub sequence: u64,
    pub at: Instant,
    pub destination: Destination,
    pub purpose: Purpose,
    pub feature: Feature,
    pub path: String,
    pub outcome: Outcome,
}

impl Entry {
    pub fn age(&self) -> Duration {
        self.at.elapsed()
    }
}

/// Shared, cheap-to-clone handle to the journal.
#[derive(Debug, Clone, Default)]
pub struct NetworkJournal {
    inner: Arc<Mutex<Inner>>,
}

#[derive(Debug, Default)]
struct Inner {
    entries: VecDeque<Entry>,
    next_sequence: u64,
    /// Counts by destination, retained even after entries are evicted, so the
    /// Privacy screen can show honest lifetime totals for the session.
    denied_count: u64,
    completed_count: u64,
    failed_count: u64,
}

impl NetworkJournal {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn record(&self, request: &RequestDescriptor, outcome: Outcome) {
        // A poisoned journal must never take down the launcher; recovering the
        // guard is correct here because the data is append-only and advisory.
        let mut inner = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        match &outcome {
            Outcome::Denied(_) => inner.denied_count += 1,
            Outcome::Completed { .. } => inner.completed_count += 1,
            Outcome::Failed(_) => inner.failed_count += 1,
        }
        let sequence = inner.next_sequence;
        inner.next_sequence += 1;
        if inner.entries.len() == CAPACITY {
            inner.entries.pop_front();
        }
        inner.entries.push_back(Entry {
            sequence,
            at: Instant::now(),
            destination: request.destination,
            purpose: request.purpose,
            feature: request.feature,
            path: request.path.clone(),
            outcome,
        });
    }

    /// Newest first.
    pub fn recent(&self, limit: usize) -> Vec<Entry> {
        let inner = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        inner.entries.iter().rev().take(limit).cloned().collect()
    }

    pub fn summary(&self) -> Summary {
        let inner = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        Summary {
            total: inner.next_sequence,
            completed: inner.completed_count,
            denied: inner.denied_count,
            failed: inner.failed_count,
            retained: inner.entries.len(),
        }
    }

    /// Discard the retained entries. Counters are reset too, because a user who
    /// clears the log expects it to be cleared.
    pub fn clear(&self) {
        let mut inner = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        inner.entries.clear();
        inner.denied_count = 0;
        inner.completed_count = 0;
        inner.failed_count = 0;
        // The sequence counter feeds `Summary::total`, so a "clear" that left
        // it running would report history the user just asked us to forget.
        inner.next_sequence = 0;
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Summary {
    pub total: u64,
    pub completed: u64,
    pub denied: u64,
    pub failed: u64,
    pub retained: usize,
}

#[cfg(test)]
#[allow(clippy::indexing_slicing, clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    fn req(path: &str) -> RequestDescriptor {
        RequestDescriptor::new(
            Destination::PistonMeta,
            Purpose::ResolveGameMetadata,
            Feature::ProfileInstall,
            path,
        )
    }

    #[test]
    fn records_and_counts_outcomes() {
        let j = NetworkJournal::new();
        j.record(
            &req("/a"),
            Outcome::Completed {
                status: 200,
                bytes: 10,
            },
        );
        j.record(&req("/b"), Outcome::Denied(PolicyDenial::Offline));
        j.record(&req("/c"), Outcome::Failed("dns".into()));

        let s = j.summary();
        assert_eq!(s.total, 3);
        assert_eq!(s.completed, 1);
        assert_eq!(s.denied, 1);
        assert_eq!(s.failed, 1);

        let recent = j.recent(10);
        assert_eq!(recent.len(), 3);
        // Newest first.
        assert_eq!(recent[0].path, "/c");
        assert_eq!(recent[2].path, "/a");
    }

    #[test]
    fn retention_is_bounded_but_totals_are_not() {
        let j = NetworkJournal::new();
        for i in 0..(CAPACITY + 50) {
            j.record(
                &req(&format!("/{i}")),
                Outcome::Completed {
                    status: 200,
                    bytes: 1,
                },
            );
        }
        let s = j.summary();
        assert_eq!(s.retained, CAPACITY);
        assert_eq!(s.total, (CAPACITY + 50) as u64);
    }

    #[test]
    fn clear_resets_everything() {
        let j = NetworkJournal::new();
        j.record(
            &req("/a"),
            Outcome::Completed {
                status: 200,
                bytes: 1,
            },
        );
        j.clear();
        assert_eq!(j.summary(), Summary::default());
        assert!(j.recent(10).is_empty());
    }
}
