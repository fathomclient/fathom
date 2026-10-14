//! Core services for the launcher. No UI, no global state, no background
//! threads started on your behalf.
//!
//! Layering, outermost first:
//!
//! - [`network`] is the only module that opens a socket, and it can only reach
//!   hosts named in a compile-time allowlist.
//! - [`fs_safety`] is the only module that should be used to turn untrusted
//!   names into paths.
//! - [`secret`] keeps tokens out of logs.
//!
//! Everything else is domain logic built on those three.

pub mod auth;
pub mod bench;
pub mod bridge;
pub mod components;
pub mod configuration;
pub mod credentials;
pub mod fathom_client;
pub mod fs_safety;
pub mod install;
pub mod java_runtime;
pub mod launching;
pub mod loaders;
pub mod loadout;
pub mod logging;
pub mod minecraft_runtime;
pub mod mods;
pub mod network;
pub mod ops;
pub mod optimizations;
pub mod paths;
pub mod problem;
pub mod profiles;
pub mod recovery;
pub mod secret;
pub mod services;
pub mod session;
pub mod session_pulse;
pub mod tasks;
pub mod update;

pub use install::{InstallService, InstallationState, InstallationVerifier};
pub use services::{Blocker, InstallSnapshot, LaunchOptions, Launcher, Readiness, ServiceError};
pub use tasks::{Cancel, SignedIn, Tasks};

/// Product identity. Used for the data directory and the `User-Agent`.
///
/// `mod-policies.md` §8 notes the Minecraft Usage Guidelines require that tools
/// not appear official or approved, so this name must stay clearly distinct
/// from Mojang's branding.
pub const PRODUCT_NAME: &str = "Fathom";
pub const PRODUCT_VERSION: &str = env!("CARGO_PKG_VERSION");

/// The `User-Agent` sent on every request. Deliberately contains no identifier,
/// no OS build, and no install id — just what we are and which version.
///
/// In the `owner/project/version` form Modrinth's API documentation asks of
/// applications (a bare product name "increases the likelihood that we will
/// block your traffic"), naming the public repository, so whoever runs an
/// index can see what is calling it and where to report a problem. The same
/// for every user of a build.
pub fn user_agent() -> String {
    format!("fathomclient/fathom/{PRODUCT_VERSION}")
}

#[cfg(test)]
#[allow(clippy::indexing_slicing, clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    #[test]
    fn user_agent_is_minimal() {
        let ua = user_agent();
        assert_eq!(ua, format!("fathomclient/fathom/{PRODUCT_VERSION}"));
        assert!(!ua.contains(' '), "no room for an identifier to hide in");
    }
}
