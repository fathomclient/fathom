//! Centralised networking.
//!
//! Three pieces, in dependency order:
//!
//! - [`policy`] — the compile-time allowlist of destinations, plus the runtime
//!   switches (offline mode, per-feature consent). Pure data and pure
//!   functions; no I/O, fully testable.
//! - [`journal`] — an in-memory, never-persisted record of what was requested
//!   and why, rendered by the Privacy / Network screen.
//! - [`http`] — the only module that opens a socket.
//!
//! Nothing outside this module may construct a URL or call a transport.

pub mod http;
pub mod journal;
pub mod policy;

pub use http::{Body, HttpClient, Method, NetworkError, Response};
pub use journal::{Entry, NetworkJournal, Outcome, Summary};
pub use policy::{
    descriptor_from_url, Consent, Destination, Feature, NetworkPolicy, PerActionGrant,
    PolicyDenial, Purpose, RequestDescriptor, UrlRejection,
};
