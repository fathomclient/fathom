//! Privacy, as the interface sees it.
//!
//! # Why this is a page and not a paragraph
//!
//! Every launcher says it respects your privacy. The claim costs nothing, so it
//! means nothing. What this page shows instead is the mechanism: the exact list
//! of hosts the launcher is capable of contacting, why each one exists, whether
//! it is currently allowed, and what it actually contacted this session.
//!
//! That list is not written here. It is `launcher_core::network::Destination`,
//! the same enumeration the network layer checks before opening a socket — so
//! the page cannot fall out of step with the policy by being edited separately.
//! If a destination is ever added, it appears here whether or not anybody
//! remembered to update the copy.
//!
//! The journal is per-session and in memory. It is not written to disk, because
//! a privacy feature that keeps a permanent record of everything you did would
//! be a strange thing to have built.

use serde::Serialize;

use launcher_core::network::journal::Outcome;
use launcher_core::Launcher;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PrivacyView {
    /// The short, checkable claims. Each is a fact about the build, not a
    /// promise about intent.
    pub guarantees: Vec<Guarantee>,
    pub offline: bool,
    /// One line for the header: "9 requests this session, 0 refused".
    pub summary: String,
    pub destinations: Vec<DestinationRow>,
    /// What happened this session, most recent first. Empty is the normal and
    /// good case, and the page says so rather than showing an empty table.
    pub activity: Vec<ActivityRow>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Guarantee {
    pub label: &'static str,
    /// One line. The page shows this; anything longer belongs in the docs.
    pub detail: &'static str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DestinationRow {
    pub host: String,
    /// Why this host exists at all, from the policy itself.
    pub reason: String,
    pub allowed: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivityRow {
    pub host: String,
    pub purpose: String,
    /// `ok` / `denied` / `failed` — a tone, not a sentence.
    pub tone: &'static str,
    pub outcome: String,
}

/// What this build does and does not do.
///
/// Written as facts that could be checked by reading the source, not as
/// reassurances. "No analytics" is verifiable; "we care about your privacy" is
/// not, and it is the kind of line that appears on the pages of products that
/// do have analytics.
const GUARANTEES: [(&str, &str); 5] = [
    (
        "No analytics or telemetry",
        "Nothing about how you use Fathom is recorded or sent anywhere.",
    ),
    (
        "No account required to browse",
        "Signing in is only needed to launch the game, because Mojang requires it.",
    ),
    (
        "Tokens stay in the OS credential store",
        "Never in a config file, and never handed to the interface layer.",
    ),
    (
        "The interface cannot reach the network",
        "It has no HTTP access at all; every request goes through the checks below.",
    ),
    (
        "Nothing is contacted without a reason",
        "Each host below exists for one named purpose and is refused otherwise.",
    ),
];

const fn tone_of(outcome: &Outcome) -> &'static str {
    match outcome {
        Outcome::Completed { status, .. } if *status >= 200 && *status < 300 => "ok",
        Outcome::Completed { .. } | Outcome::Failed(_) => "failed",
        Outcome::Denied(_) => "denied",
    }
}

fn describe(outcome: &Outcome) -> String {
    match outcome {
        Outcome::Completed { status, bytes } => {
            if *bytes == 0 {
                format!("{status}")
            } else {
                format!("{status} · {}", humanise_bytes(*bytes))
            }
        }
        Outcome::Denied(reason) => format!("Refused: {reason}"),
        Outcome::Failed(reason) => format!("Failed: {reason}"),
    }
}

fn humanise_bytes(bytes: u64) -> String {
    const MB: u64 = 1024 * 1024;
    const KB: u64 = 1024;
    if bytes >= MB {
        format!("{:.1} MB", bytes as f64 / MB as f64)
    } else if bytes >= KB {
        format!("{} KB", bytes / KB)
    } else {
        format!("{bytes} B")
    }
}

pub fn view(core: &Launcher) -> PrivacyView {
    let summary = core.network_summary();

    let destinations = core
        .network_allowlist()
        .into_iter()
        .map(|(destination, allowed)| DestinationRow {
            host: destination.host().to_owned(),
            reason: destination.rationale().to_owned(),
            allowed,
        })
        .collect();

    // A short window. This is a "what just happened" panel, not an audit log,
    // and the journal is in memory precisely so it is not the latter.
    let activity = core
        .network_journal()
        .recent(40)
        .into_iter()
        .rev()
        .map(|entry| ActivityRow {
            host: entry.destination.host().to_owned(),
            purpose: entry.purpose.label().to_owned(),
            tone: tone_of(&entry.outcome),
            outcome: describe(&entry.outcome),
        })
        .collect();

    PrivacyView {
        guarantees: GUARANTEES
            .iter()
            .map(|(label, detail)| Guarantee { label, detail })
            .collect(),
        offline: core.settings().privacy.offline_mode,
        summary: if summary.total == 0 {
            "No network requests this session".to_owned()
        } else {
            format!(
                "{} {} this session · {} refused",
                summary.total,
                if summary.total == 1 {
                    "request"
                } else {
                    "requests"
                },
                summary.denied
            )
        },
        destinations,
        activity,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use launcher_core::network::policy::Destination;

    /// The page lists hosts from the policy enumeration rather than from a copy
    /// deck, so a destination added to the network layer cannot quietly fail to
    /// appear here.
    #[test]
    fn every_destination_has_a_host_and_a_reason() {
        for destination in Destination::ALL {
            assert!(!destination.host().is_empty());
            assert!(
                !destination.rationale().is_empty(),
                "{} has no stated reason",
                destination.host()
            );
        }
    }

    /// A 4xx is a failure, not a success, and colouring it green would be the
    /// page telling a comfortable lie about what happened.
    #[test]
    fn a_non_success_status_is_not_shown_as_success() {
        assert_eq!(
            tone_of(&Outcome::Completed {
                status: 200,
                bytes: 10
            }),
            "ok"
        );
        assert_eq!(
            tone_of(&Outcome::Completed {
                status: 404,
                bytes: 0
            }),
            "failed"
        );
        assert_eq!(tone_of(&Outcome::Failed("dns".to_owned())), "failed");
    }
}
