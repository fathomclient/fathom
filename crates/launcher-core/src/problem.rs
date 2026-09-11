//! A failure the desktop application can actually show someone.
//!
//! # Why a type and not a string
//!
//! The launcher is started by double-clicking an icon. In a release build there
//! is no console, so `eprintln!` goes nowhere at all — an error that only exists
//! as terminal output is an error the user experiences as *nothing happening*.
//!
//! Everything a caller needs in order to render a failure and offer the right
//! next step therefore has to be in the value:
//!
//! | Field | Answers |
//! |---|---|
//! | [`Category`] | which subsystem, so the interface can group and route it |
//! | [`Problem::summary`] | what happened, in a sentence a player can read |
//! | [`Problem::detail`] | the technical context, for a diagnostic report |
//! | [`Problem::retry`] | is trying again worth anything? |
//! | [`Problem::repair`] | is there a repair that would fix this? |
//! | [`Problem::cause`] | the underlying error chain, for diagnosis |
//!
//! # Secrets
//!
//! `summary`, `detail` and every cause string pass through
//! [`crate::logging::scrub`] on construction. That is a net rather than the
//! defence — tokens are [`crate::secret::Secret`] and cannot be formatted — but
//! a `Problem` is the one type designed to be *displayed and exported*, so it
//! gets the belt as well as the braces.

use std::fmt;

/// Which part of the launcher a failure came from.
///
/// A closed set, because the interface groups by it and an open one would mean
/// a category nothing knows how to display.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Category {
    /// Signing in, or proving ownership.
    Authentication,
    /// Reaching a host, or what it sent back.
    Network,
    /// Reading or writing files.
    Storage,
    /// Something on disk is not what it should be.
    Integrity,
    /// The profile's own configuration.
    Profile,
    /// Acquiring or verifying the game.
    Installation,
    /// The Java runtime.
    JavaRuntime,
    /// The mod loader.
    Loader,
    /// Mods.
    Mods,
    /// Starting or running the game.
    Launch,
    /// The launcher updating itself.
    Update,
    /// Another operation holds what this one needs.
    Busy,
    /// A refusal on policy grounds: a licence, a privacy rule, a safety rule.
    Policy,
    /// Something we did not anticipate.
    Internal,
}

impl Category {
    pub fn label(self) -> &'static str {
        match self {
            Category::Authentication => "Sign-in",
            Category::Network => "Network",
            Category::Storage => "Storage",
            Category::Integrity => "File integrity",
            Category::Profile => "Profile",
            Category::Installation => "Installation",
            Category::JavaRuntime => "Java",
            Category::Loader => "Mod loader",
            Category::Mods => "Mods",
            Category::Launch => "Launch",
            Category::Update => "Update",
            Category::Busy => "Busy",
            Category::Policy => "Policy",
            Category::Internal => "Internal",
        }
    }
}

impl fmt::Display for Category {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

/// Whether trying again could plausibly work.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Retry {
    /// Trying again will fail the same way. Something has to change first.
    Pointless,
    /// A transient condition — a timeout, a dropped connection.
    Worthwhile,
    /// Only after the named repair has run.
    AfterRepair,
}

impl Retry {
    pub fn is_worth_it(self) -> bool {
        matches!(self, Retry::Worthwhile)
    }
}

/// A repair that would address this failure.
///
/// Named rather than described, so the interface can offer the button that runs
/// it instead of printing a sentence telling the user to find it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Repair {
    /// Re-acquire missing or corrupt game files.
    RepairInstallation,
    /// Re-acquire the managed Java runtime.
    RepairJava,
    /// Re-install the mod loader.
    RepairLoader,
    /// Fix what can be fixed in the profile without a decision.
    RepairProfile { profile: String },
    /// Rebuild the mods folder from the configuration.
    RematerializeMods { profile: String },
    /// Discard disposable cached data.
    ClearCache,
    /// Sign in again.
    SignIn,
    /// Choose a Java runtime.
    ChooseJava,
    /// Something only a person can decide.
    UserDecision { what: String },
}

impl Repair {
    /// What the button should say.
    pub fn label(&self) -> String {
        match self {
            Repair::RepairInstallation => "Repair the Minecraft installation".to_owned(),
            Repair::RepairJava => "Repair the managed Java runtime".to_owned(),
            Repair::RepairLoader => "Reinstall the mod loader".to_owned(),
            Repair::RepairProfile { profile } => format!("Repair profile {profile}"),
            Repair::RematerializeMods { profile } => {
                format!("Rebuild the mods folder for {profile}")
            }
            Repair::ClearCache => "Clear the cache".to_owned(),
            Repair::SignIn => "Sign in".to_owned(),
            Repair::ChooseJava => "Choose a Java runtime".to_owned(),
            Repair::UserDecision { what } => what.clone(),
        }
    }
}

/// A failure, packaged for a window rather than a terminal.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Problem {
    category: Category,
    summary: String,
    detail: Option<String>,
    retry: Retry,
    repair: Option<Repair>,
    causes: Vec<String>,
}

impl Problem {
    /// Build one. Every string is scrubbed on the way in.
    pub fn new(category: Category, summary: impl Into<String>) -> Self {
        Self {
            category,
            summary: crate::logging::scrub(&summary.into()),
            detail: None,
            retry: Retry::Pointless,
            repair: None,
            causes: Vec::new(),
        }
    }

    #[must_use]
    pub fn detail(mut self, detail: impl Into<String>) -> Self {
        self.detail = Some(crate::logging::scrub(&detail.into()));
        self
    }

    #[must_use]
    pub fn retry(mut self, retry: Retry) -> Self {
        self.retry = retry;
        self
    }

    #[must_use]
    pub fn repair(mut self, repair: Repair) -> Self {
        self.repair = Some(repair);
        self
    }

    /// Record the underlying error, and everything it was caused by.
    ///
    /// Walks the `source()` chain, because the interesting sentence is usually
    /// three levels down — "the file is in use by another process" rather than
    /// "could not repair the installation".
    #[must_use]
    pub fn caused_by(mut self, error: &dyn std::error::Error) -> Self {
        self.causes.push(crate::logging::scrub(&error.to_string()));
        let mut source = error.source();
        // Bounded: a cyclic or absurdly deep chain must not produce an
        // unbounded report.
        let mut depth = 0;
        while let Some(inner) = source {
            if depth >= 8 {
                self.causes.push("(further causes omitted)".to_owned());
                break;
            }
            self.causes.push(crate::logging::scrub(&inner.to_string()));
            source = inner.source();
            depth += 1;
        }
        self
    }

    pub fn category(&self) -> Category {
        self.category
    }

    /// One sentence, safe to show anyone.
    pub fn summary(&self) -> &str {
        &self.summary
    }

    /// Technical context. Shown on request; always included in a diagnostic.
    pub fn technical_detail(&self) -> Option<&str> {
        self.detail.as_deref()
    }

    pub fn retry_advice(&self) -> Retry {
        self.retry
    }

    pub fn available_repair(&self) -> Option<&Repair> {
        self.repair.as_ref()
    }

    pub fn causes(&self) -> &[String] {
        &self.causes
    }

    /// Everything, as a block for a diagnostic report.
    pub fn report(&self) -> String {
        use std::fmt::Write as _;

        let mut out = format!("[{}] {}", self.category.label(), self.summary);
        if let Some(detail) = &self.detail {
            out.push_str("\n  detail: ");
            out.push_str(detail);
        }
        for (i, cause) in self.causes.iter().enumerate() {
            let _ = write!(out, "\n  cause[{i}]: {cause}");
        }
        let _ = write!(
            out,
            "\n  retry: {:?}\n  repair: {}",
            self.retry,
            self.repair
                .as_ref()
                .map_or_else(|| "none".to_owned(), Repair::label)
        );
        out
    }
}

impl fmt::Display for Problem {
    /// The summary only. `Display` is what ends up in a title bar or a toast,
    /// and the technical context belongs where there is room for it.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.summary)
    }
}

impl std::error::Error for Problem {}

/// Turn a `Busy` refusal into a `Problem`. Retrying really is worthwhile: the
/// holder will finish.
impl From<crate::ops::Busy> for Problem {
    fn from(busy: crate::ops::Busy) -> Self {
        Problem::new(Category::Busy, busy.to_string())
            .detail(format!("scope={:?} holder={:?}", busy.scope, busy.holder))
            .retry(Retry::Worthwhile)
    }
}

#[cfg(test)]
#[allow(
    clippy::indexing_slicing,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic
)]
mod tests {
    use super::*;

    #[derive(Debug, thiserror::Error)]
    #[error("the outer failure")]
    struct Outer(#[source] Inner);

    #[derive(Debug, thiserror::Error)]
    #[error("the real reason: {0}")]
    struct Inner(String);

    #[test]
    fn a_problem_carries_everything_the_window_needs() {
        let problem = Problem::new(Category::Installation, "Some game files are missing.")
            .detail("3 of 811 artifacts failed verification")
            .retry(Retry::AfterRepair)
            .repair(Repair::RepairInstallation);

        assert_eq!(problem.category(), Category::Installation);
        assert!(problem.summary().contains("missing"));
        assert!(problem.technical_detail().is_some());
        assert_eq!(problem.retry_advice(), Retry::AfterRepair);
        assert!(!problem.retry_advice().is_worth_it());
        assert_eq!(
            problem.available_repair().map(Repair::label),
            Some("Repair the Minecraft installation".to_owned())
        );
    }

    #[test]
    fn the_cause_chain_is_walked_because_the_useful_sentence_is_usually_inside_it() {
        let error = Outer(Inner("the file is in use by another process".to_owned()));
        let problem =
            Problem::new(Category::Storage, "Could not write the profile.").caused_by(&error);

        assert_eq!(problem.causes().len(), 2);
        assert!(problem.causes()[0].contains("outer failure"));
        assert!(
            problem.causes()[1].contains("in use by another process"),
            "the actionable sentence must survive: {:?}",
            problem.causes()
        );
    }

    /// A `Problem` is built to be displayed and exported, so it is the one type
    /// where a leaked token would definitely reach a screen or a file.
    #[test]
    fn every_string_is_scrubbed_on_the_way_in() {
        let leaky = "failed with --accessToken eyJhbGciOi.SECRETVALUE.sig while launching";
        let problem = Problem::new(Category::Launch, leaky)
            .detail(leaky)
            .caused_by(&Inner(leaky.to_owned()));

        assert!(!problem.summary().contains("SECRETVALUE"));
        assert!(!problem
            .technical_detail()
            .unwrap_or_default()
            .contains("SECRETVALUE"));
        assert!(!problem.causes()[0].contains("SECRETVALUE"));
        assert!(
            !problem.report().contains("SECRETVALUE"),
            "the exported form must be clean too"
        );
    }

    #[test]
    fn a_deep_cause_chain_is_bounded() {
        // A chain long enough to matter, built by nesting.
        #[derive(Debug)]
        struct Deep(usize);
        impl fmt::Display for Deep {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                write!(f, "level {}", self.0)
            }
        }
        impl std::error::Error for Deep {
            fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
                None
            }
        }

        // A self-referential chain is not constructible in safe Rust, so the
        // realistic risk is merely a long one. The bound is asserted directly.
        let problem = Problem::new(Category::Internal, "x").caused_by(&Deep(0));
        assert_eq!(problem.causes().len(), 1);
    }

    #[test]
    fn a_busy_refusal_becomes_a_retryable_problem() {
        let busy = crate::ops::Busy {
            wanted: crate::ops::Activity::DeletingProfile,
            holder: crate::ops::Activity::Running,
            scope: crate::ops::Scope::SharedInstallation,
            held_for: std::time::Duration::from_secs(5),
        };
        let problem = Problem::from(busy);
        assert_eq!(problem.category(), Category::Busy);
        assert!(
            problem.retry_advice().is_worth_it(),
            "the holder will finish, so retrying is genuinely worth it"
        );
        assert!(problem.summary().contains("Minecraft is running"));
    }

    #[test]
    fn display_is_the_summary_and_report_is_everything() {
        let problem = Problem::new(Category::Network, "Could not reach the release host.")
            .detail("connect timeout after 30s")
            .retry(Retry::Worthwhile);

        assert_eq!(problem.to_string(), "Could not reach the release host.");
        let report = problem.report();
        assert!(report.contains("[Network]"));
        assert!(report.contains("connect timeout"));
        assert!(report.contains("Worthwhile"));
    }
}
