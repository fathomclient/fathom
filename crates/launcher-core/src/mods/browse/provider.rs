//! What a mod index has to be able to do, and what may go wrong doing it.
//!
//! # One trait, two implementations, no third opinion
//!
//! [`CatalogProvider`] is the whole contract between Fathom and a mod index.
//! Above it, [`super::ModCatalog`] knows about searching, caching, installing
//! and profiles but nothing about HTTP or JSON; below it, a provider knows
//! about one API and nothing about profiles, stores or downloads.
//!
//! The trait is deliberately narrow. It has no "install", no "update" and no
//! "which of these do I have" — those are questions about a profile, and a
//! provider that could answer them would be a provider that could act on one.
//!
//! # Every method can fail in a way the interface has to explain
//!
//! [`BrowseError`] is the structured half of that. A browser with two indexes
//! must keep working when one of them is down, rate-limited, unconfigured or
//! returning nonsense, so failures carry which provider failed and whether
//! trying again could help — not a string the UI has to pattern-match.

use std::fmt::Debug;

use serde::{Deserialize, Serialize};

use super::model::{ModFile, ModProject, Provider, Target};
use crate::network::{NetworkError, PerActionGrant, PolicyDenial};
use crate::tasks::Cancel;

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/// What went wrong, in terms the interface can act on.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum BrowseError {
    /// The launcher is in offline mode, or the network refused the request.
    #[error("{reason}")]
    Blocked { reason: String },
    /// The provider needs configuration the user has not supplied. Never an
    /// error the user has to fix mid-search: the catalog skips the provider.
    #[error("{provider} is not set up: {reason}")]
    NotConfigured { provider: Provider, reason: String },
    /// The provider answered, but with a refusal or a failure of its own.
    #[error("{provider} returned HTTP {status}")]
    Provider { provider: Provider, status: u16 },
    /// Too many requests. Carries the wait the provider asked for, when it
    /// stated one.
    #[error("{provider} is rate limiting this launcher")]
    RateLimited {
        provider: Provider,
        retry_after_seconds: Option<u64>,
    },
    /// The connection failed, timed out, or the provider is unreachable.
    #[error("could not reach {provider}: {detail}")]
    Unreachable { provider: Provider, detail: String },
    /// The response parsed as JSON but was not what the API documents, or was
    /// larger than we will read.
    #[error("{provider} sent something this launcher could not read: {detail}")]
    Malformed { provider: Provider, detail: String },
    /// The project or file is gone. Deleted projects are normal; a stale link
    /// from a cache or a profile record hits this.
    #[error("{what} is no longer on {provider}")]
    NotFound { provider: Provider, what: String },
    /// The user asked for something that cannot work here.
    #[error("{0}")]
    Incompatible(String),
    /// Refused for safety: a hostile file name, a URL off the allowlist, a file
    /// with no publishable hash.
    #[error("{0}")]
    Refused(String),
    #[error("cancelled")]
    Cancelled,
}

impl BrowseError {
    /// Whether trying the same thing again could plausibly work.
    pub fn is_retryable(&self) -> bool {
        matches!(
            self,
            BrowseError::Unreachable { .. }
                | BrowseError::RateLimited { .. }
                | BrowseError::Provider { .. }
        )
    }

    /// Whether this is a fault of one provider rather than of the browser.
    ///
    /// The catalog uses it to decide whether a failure ends the search or is
    /// reported beside the other provider's results.
    pub fn is_provider_fault(&self) -> bool {
        matches!(
            self,
            BrowseError::Provider { .. }
                | BrowseError::RateLimited { .. }
                | BrowseError::Unreachable { .. }
                | BrowseError::Malformed { .. }
                | BrowseError::NotFound { .. }
                | BrowseError::NotConfigured { .. }
        )
    }

    /// One sentence for a person, with no jargon and nothing secret in it.
    pub fn user_message(&self) -> String {
        match self {
            BrowseError::Blocked { reason } => reason.clone(),
            BrowseError::NotConfigured { provider, reason } => {
                format!("{provider} is not set up. {reason}")
            }
            BrowseError::Provider { provider, status } => match status {
                403 => format!("{provider} refused this request."),
                500..=599 => format!("{provider} is having trouble. Try again shortly."),
                _ => format!("{provider} could not answer that ({status})."),
            },
            BrowseError::RateLimited {
                provider,
                retry_after_seconds,
            } => match retry_after_seconds {
                Some(seconds) => {
                    format!("{provider} is rate limiting us. Try again in {seconds} seconds.")
                }
                None => format!("{provider} is rate limiting us. Try again shortly."),
            },
            BrowseError::Unreachable { provider, .. } => {
                format!("Could not reach {provider}. Check your connection.")
            }
            BrowseError::Malformed { provider, .. } => {
                format!("{provider} sent a response this launcher could not read.")
            }
            BrowseError::NotFound { provider, what } => {
                format!("{what} is no longer available on {provider}.")
            }
            BrowseError::Incompatible(detail) | BrowseError::Refused(detail) => detail.clone(),
            BrowseError::Cancelled => "Cancelled.".to_owned(),
        }
    }

    /// Translate a network failure into the provider-shaped error above.
    ///
    /// Written once, here, so both providers classify a 429 and a timeout the
    /// same way and the interface has one set of cases to handle.
    pub fn from_network(provider: Provider, error: &NetworkError) -> Self {
        match error {
            NetworkError::Denied(denial) => match denial {
                PolicyDenial::Offline => BrowseError::Blocked {
                    reason: "Fathom is in offline mode.".to_owned(),
                },
                PolicyDenial::ModRepositoriesDisabled => BrowseError::Blocked {
                    reason: "Mod repositories are turned off in Privacy settings.".to_owned(),
                },
                other => BrowseError::Blocked {
                    reason: other.to_string(),
                },
            },
            NetworkError::Cancelled => BrowseError::Cancelled,
            NetworkError::Status { status, .. } => Self::from_status(provider, *status, None),
            NetworkError::Malformed { source, .. } => BrowseError::Malformed {
                provider,
                detail: super::model::clip(&source.to_string(), 200),
            },
            NetworkError::ResponseTooLarge { .. } => BrowseError::Malformed {
                provider,
                detail: "the response was larger than this launcher will read".to_owned(),
            },
            other => BrowseError::Unreachable {
                provider,
                detail: super::model::clip(&other.to_string(), 200),
            },
        }
    }

    /// Classify an HTTP status this launcher read itself.
    pub fn from_status(provider: Provider, status: u16, retry_after: Option<u64>) -> Self {
        match status {
            404 | 410 => BrowseError::NotFound {
                provider,
                what: "that project".to_owned(),
            },
            429 => BrowseError::RateLimited {
                provider,
                retry_after_seconds: retry_after,
            },
            _ => BrowseError::Provider { provider, status },
        }
    }
}

// ---------------------------------------------------------------------------
// Availability
// ---------------------------------------------------------------------------

/// Whether a provider can be asked anything at all.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "state", rename_all = "kebab-case")]
pub enum Availability {
    /// Configured and usable.
    Ready,
    /// Missing configuration the user has to supply, with what to do about it.
    NotConfigured { reason: String },
}

impl Availability {
    pub fn is_ready(&self) -> bool {
        matches!(self, Availability::Ready)
    }
}

/// What a provider did on the last request, for the interface to show beside
/// the results it did get.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderStatus {
    pub provider: Provider,
    pub availability: Availability,
    /// The failure from this request, if there was one. `None` means the
    /// provider answered.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<BrowseError>,
    /// How many results it contributed.
    pub results: usize,
}

impl ProviderStatus {
    pub fn ok(provider: Provider, results: usize) -> Self {
        Self {
            provider,
            availability: Availability::Ready,
            error: None,
            results,
        }
    }

    pub fn failed(provider: Provider, error: BrowseError) -> Self {
        Self {
            provider,
            availability: Availability::Ready,
            error: Some(error),
            results: 0,
        }
    }

    pub fn unconfigured(provider: Provider, reason: String) -> Self {
        Self {
            provider,
            availability: Availability::NotConfigured {
                reason: reason.clone(),
            },
            error: Some(BrowseError::NotConfigured { provider, reason }),
            results: 0,
        }
    }
}

// ---------------------------------------------------------------------------
// Queries
// ---------------------------------------------------------------------------

/// How to order results, where the provider supports it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Sort {
    #[default]
    Relevance,
    Downloads,
    Updated,
    Newest,
    Name,
}

/// One search, as the catalog asks it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchQuery {
    /// What the user typed. May be empty, which means "show me anything".
    pub text: String,
    /// The profile's Minecraft version and loader. Always present: browsing is
    /// always browsing *for* something.
    pub target: Target,
    /// One category, in the provider's own vocabulary where it has one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
    #[serde(default)]
    pub sort: Sort,
    #[serde(default)]
    pub offset: usize,
    #[serde(default = "default_limit")]
    pub limit: usize,
    /// Show projects that do not fit the target.
    ///
    /// Off by default, and it stays off in every default path: a 1.8.9 profile
    /// browsing the whole of Modrinth is a list of mods that cannot load. When
    /// it is on, results are still marked, and installing an incompatible file
    /// is refused regardless — see [`super::install`].
    #[serde(default)]
    pub include_incompatible: bool,
    /// Which providers to ask. Empty means every configured one.
    #[serde(default)]
    pub providers: Vec<Provider>,
}

fn default_limit() -> usize {
    20
}

impl SearchQuery {
    pub fn new(target: Target) -> Self {
        Self {
            text: String::new(),
            target,
            category: None,
            sort: Sort::default(),
            offset: 0,
            limit: default_limit(),
            include_incompatible: false,
            providers: Vec::new(),
        }
    }

    #[must_use]
    pub fn with_text(mut self, text: impl Into<String>) -> Self {
        self.text = text.into();
        self
    }

    #[must_use]
    pub fn with_page(mut self, offset: usize, limit: usize) -> Self {
        self.offset = offset;
        self.limit = limit.clamp(1, 50);
        self
    }

    #[must_use]
    pub fn with_provider(mut self, provider: Provider) -> Self {
        self.providers = vec![provider];
        self
    }

    /// Whether this query wants results from a provider.
    pub fn wants(&self, provider: Provider) -> bool {
        self.providers.is_empty() || self.providers.contains(&provider)
    }

    /// The key this query caches under. Two queries with the same key have the
    /// same answer, so the cache must include everything that changes it.
    pub fn cache_key(&self) -> String {
        format!(
            "{}|{}|{}|{:?}|{}|{}|{}|{}",
            self.text.trim().to_lowercase(),
            self.target.minecraft,
            self.target.loader.label(),
            self.sort,
            self.category.as_deref().unwrap_or(""),
            self.offset,
            self.limit,
            self.include_incompatible,
        )
    }
}

/// One page of results from one provider.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchPage {
    pub hits: Vec<ModProject>,
    /// How many results exist in total, as the provider reports it. Used to
    /// decide whether another page exists, not to render a number.
    pub total: u64,
    pub offset: usize,
}

impl SearchPage {
    pub fn empty() -> Self {
        Self {
            hits: Vec::new(),
            total: 0,
            offset: 0,
        }
    }

    /// Whether asking for the next page could return anything.
    ///
    /// A page that came back empty ends the list whatever the reported total
    /// says: a provider that keeps claiming more while returning none would
    /// otherwise be an infinite scroll.
    pub fn has_more(&self) -> bool {
        !self.hits.is_empty() && ((self.offset + self.hits.len()) as u64) < self.total
    }
}

// ---------------------------------------------------------------------------
// The trait
// ---------------------------------------------------------------------------

/// What every request carries: the user's own act, and a way to stop.
#[derive(Debug, Clone, Copy)]
pub struct ProviderContext<'a> {
    /// The per-action grant the network policy demands for a mod repository.
    /// There is no path to these hosts without one.
    pub grant: &'a PerActionGrant,
    /// Checked between steps so a search the user has moved on from stops
    /// rather than finishing into a cache nobody reads.
    pub cancel: &'a Cancel,
}

impl<'a> ProviderContext<'a> {
    pub fn new(grant: &'a PerActionGrant, cancel: &'a Cancel) -> Self {
        Self { grant, cancel }
    }

    /// `Err(Cancelled)` once the user has moved on.
    pub fn check(&self) -> Result<(), BrowseError> {
        if self.cancel.is_cancelled() {
            Err(BrowseError::Cancelled)
        } else {
            Ok(())
        }
    }
}

/// One mod index.
pub trait CatalogProvider: Debug + Send + Sync {
    fn provider(&self) -> Provider;

    /// Whether this provider can be used, and what to say when it cannot.
    fn availability(&self) -> Availability;

    /// A page of projects matching a query.
    fn search(
        &self,
        query: &SearchQuery,
        ctx: ProviderContext<'_>,
    ) -> Result<SearchPage, BrowseError>;

    /// Everything about one project, including description and gallery.
    fn project(&self, id: &str, ctx: ProviderContext<'_>) -> Result<ModProject, BrowseError>;

    /// The project's builds that fit the target, newest first.
    ///
    /// Filtered by the provider *and* checked again by the caller. A provider
    /// returning an incompatible file is a provider bug; installing it would be
    /// ours.
    fn files(
        &self,
        id: &str,
        target: &Target,
        ctx: ProviderContext<'_>,
    ) -> Result<Vec<ModFile>, BrowseError>;

    /// One specific build, by the provider's own version id.
    ///
    /// Needed for a dependency an author pinned to an exact file, and for
    /// re-checking an installed build before offering an update.
    fn file(
        &self,
        project: &str,
        version_id: &str,
        ctx: ProviderContext<'_>,
    ) -> Result<ModFile, BrowseError>;

    /// The newest compatible build: a release if there is one, otherwise the
    /// newest of anything.
    ///
    /// Defaulted because both providers return their file lists already sorted
    /// that way, and a provider that does not can override it.
    fn latest_compatible(
        &self,
        id: &str,
        target: &Target,
        ctx: ProviderContext<'_>,
    ) -> Result<Option<ModFile>, BrowseError> {
        let files = self.files(id, target, ctx)?;
        Ok(files.into_iter().next())
    }

    /// Identify a jar by its SHA-1, when the provider offers such a lookup.
    ///
    /// This is how a mod somebody installed by hand gets a name, a project and
    /// an update path. Defaults to "cannot", because only Modrinth publishes a
    /// hash index; `CurseForge` has a fingerprint API that hashes differently
    /// and is not this.
    fn lookup_by_hash(
        &self,
        _sha1: &str,
        _ctx: ProviderContext<'_>,
    ) -> Result<Option<(ModProject, ModFile)>, BrowseError> {
        Ok(None)
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use crate::profiles::Loader;

    #[test]
    fn a_cache_key_changes_with_everything_that_changes_the_answer() {
        let base = SearchQuery::new(Target::new("1.8.9", Loader::LegacyFabric)).with_text("sodium");
        let same =
            SearchQuery::new(Target::new("1.8.9", Loader::LegacyFabric)).with_text("Sodium ");
        assert_eq!(base.cache_key(), same.cache_key(), "case and spacing only");

        let other_version =
            SearchQuery::new(Target::new("1.12.2", Loader::LegacyFabric)).with_text("sodium");
        assert_ne!(base.cache_key(), other_version.cache_key());

        let other_loader =
            SearchQuery::new(Target::new("1.8.9", Loader::Forge)).with_text("sodium");
        assert_ne!(base.cache_key(), other_loader.cache_key());

        let page = base.clone().with_page(20, 20);
        assert_ne!(base.cache_key(), page.cache_key());

        let mut sorted = base.clone();
        sorted.sort = Sort::Downloads;
        assert_ne!(base.cache_key(), sorted.cache_key());
    }

    #[test]
    fn a_rate_limit_is_retryable_and_explains_the_wait() {
        let error = BrowseError::RateLimited {
            provider: Provider::CurseForge,
            retry_after_seconds: Some(30),
        };
        assert!(error.is_retryable());
        assert!(error.user_message().contains("30 seconds"));
    }

    #[test]
    fn one_provider_failing_is_a_provider_fault_and_not_a_browser_failure() {
        let down = BrowseError::Provider {
            provider: Provider::CurseForge,
            status: 503,
        };
        assert!(down.is_provider_fault());
        assert!(down.user_message().contains("trouble"));

        // A blocked request is about Fathom's own settings, not the provider.
        let blocked = BrowseError::Blocked {
            reason: "offline".to_owned(),
        };
        assert!(!blocked.is_provider_fault());
    }

    #[test]
    fn statuses_are_classified_rather_than_passed_through() {
        assert!(matches!(
            BrowseError::from_status(Provider::Modrinth, 404, None),
            BrowseError::NotFound { .. }
        ));
        assert!(matches!(
            BrowseError::from_status(Provider::Modrinth, 429, Some(12)),
            BrowseError::RateLimited {
                retry_after_seconds: Some(12),
                ..
            }
        ));
        assert!(matches!(
            BrowseError::from_status(Provider::Modrinth, 500, None),
            BrowseError::Provider { status: 500, .. }
        ));
    }
}
