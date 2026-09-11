//! Which dangerous operations may run at once, and which may not.
//!
//! # Why this is a coordinator and not a set of booleans
//!
//! The conflicts this prevents are not "two threads touching one field". They
//! are things like *deleting a profile while Minecraft is running out of it*,
//! or *clearing the cache while a download is depending on it*, or *two repairs
//! rewriting the same installation*. Those span several modules and several
//! seconds, and a boolean in each module cannot see the others.
//!
//! So there is one place that knows what is in progress, it is asked before
//! anything destructive starts, and it answers with **who is holding what** so
//! the refusal is something a user can act on.
//!
//! # Scopes
//!
//! ```text
//! GlobalUpdate          the launcher replacing itself or a component
//! SharedInstallation    the shared, content-addressed Minecraft artifacts
//! JavaRuntime           launcher-managed Java
//! ModStore              the content-addressed jar store
//! Profile(id)           one instance: its files, its config, its running game
//! ```
//!
//! `Profile` is per-id, so two profiles never block each other. Everything else
//! is a singleton because the thing it names is a singleton.
//!
//! # Deadlock
//!
//! **Structurally impossible here**, and the ordering below is belt and braces
//! rather than the mechanism.
//!
//! An operation declares every scope it needs up front, and
//! [`Operations::begin`] takes them **all or none, without ever blocking**,
//! under a single mutex held for the duration of the check. There is no moment
//! where a caller holds part of a set and waits for the rest, which is the only
//! way a deadlock can form.
//!
//! The canonical ordering in [`Scope::rank`] is still defined and still
//! enforced by sorting inside `begin`, because it makes the invariant testable
//! and because it keeps the reasoning simple if this ever grows a blocking
//! variant. `scopes_are_taken_in_a_canonical_order` pins it.
//!
//! # What a refusal looks like
//!
//! Not an error code. [`Busy`] names the scope, the operation holding it, and
//! how long it has been running, because "please try again" without saying what
//! to wait for is not help.

use std::collections::BTreeMap;
use std::fmt;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use crate::profiles::ProfileId;

/// A thing that can be exclusively held.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Scope {
    /// Replacing the launcher or a first-party component.
    ///
    /// Ranked first because it is the operation that can invalidate every other
    /// one: the binary running them is being replaced.
    GlobalUpdate,
    /// The shared Minecraft artifacts: libraries, assets, version documents.
    ///
    /// One scope for all of them because they are repaired and cleared as a
    /// unit, and because a per-artifact lock would be a lock per 800 files.
    SharedInstallation,
    /// Launcher-managed Java runtimes.
    JavaRuntime,
    /// The content-addressed mod jar store.
    ModStore,
    /// One instance: its directory, its configuration, and its running game.
    Profile(ProfileId),
}

impl Scope {
    /// Position in the canonical acquisition order.
    ///
    /// Broad-to-narrow. An operation that touches the shared installation and
    /// one profile takes the installation first, so two such operations agree
    /// about the order even though they were written years apart.
    pub fn rank(&self) -> u8 {
        match self {
            Scope::GlobalUpdate => 0,
            Scope::SharedInstallation => 1,
            Scope::JavaRuntime => 2,
            Scope::ModStore => 3,
            Scope::Profile(_) => 4,
        }
    }

    /// For messages. Matches the names in the documentation.
    pub fn label(&self) -> String {
        match self {
            Scope::GlobalUpdate => "the launcher update".to_owned(),
            Scope::SharedInstallation => "the shared Minecraft installation".to_owned(),
            Scope::JavaRuntime => "the managed Java runtime".to_owned(),
            Scope::ModStore => "the mod store".to_owned(),
            Scope::Profile(id) => format!("profile {id}"),
        }
    }
}

impl fmt::Display for Scope {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.label())
    }
}

/// What an operation is, for the message a conflicting caller receives.
///
/// A closed enum rather than a string: a refusal that says "busy" is useless,
/// and a refusal that says whatever the caller happened to type is inconsistent.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Activity {
    Launching,
    Running,
    InstallingGame,
    RepairingGame,
    VerifyingGame,
    RepairingJava,
    InstallingLoader,
    MaterializingMods,
    AddingMod,
    ImportingProfile,
    DeletingProfile,
    RestoringProfile,
    ValidatingProfile,
    RepairingProfile,
    ClearingCache,
    CheckingForUpdates,
    InstallingUpdate,
    RecoveringAtStartup,
    Diagnostics,
}

impl Activity {
    pub fn label(self) -> &'static str {
        match self {
            Activity::Launching => "starting Minecraft",
            Activity::Running => "Minecraft is running",
            Activity::InstallingGame => "installing Minecraft",
            Activity::RepairingGame => "repairing the Minecraft installation",
            Activity::VerifyingGame => "verifying the Minecraft installation",
            Activity::RepairingJava => "repairing the managed Java runtime",
            Activity::InstallingLoader => "installing the mod loader",
            Activity::MaterializingMods => "updating the mods folder",
            Activity::AddingMod => "adding a mod",
            Activity::ImportingProfile => "importing a profile",
            Activity::DeletingProfile => "deleting a profile",
            Activity::RestoringProfile => "restoring a profile",
            Activity::ValidatingProfile => "checking a profile",
            Activity::RepairingProfile => "repairing a profile",
            Activity::ClearingCache => "clearing the cache",
            Activity::CheckingForUpdates => "checking for updates",
            Activity::InstallingUpdate => "installing an update",
            Activity::RecoveringAtStartup => "recovering from an interrupted operation",
            Activity::Diagnostics => "collecting diagnostics",
        }
    }
}

impl fmt::Display for Activity {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

/// Why an operation could not start.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error(
    "cannot {wanted} right now: {holder} is already using {scope}{}",
    duration_phrase(*held_for)
)]
pub struct Busy {
    /// What the caller wanted to do.
    pub wanted: Activity,
    /// What is already happening.
    pub holder: Activity,
    /// The scope they collide on.
    pub scope: Scope,
    /// How long the holder has been running. Useful when it is stuck.
    pub held_for: Duration,
}

fn duration_phrase(held: Duration) -> String {
    let seconds = held.as_secs();
    if seconds < 2 {
        String::new()
    } else if seconds < 90 {
        format!(" (started {seconds} seconds ago)")
    } else {
        format!(" (started {} minutes ago)", seconds / 60)
    }
}

/// One held scope.
#[derive(Debug, Clone)]
struct Holder {
    activity: Activity,
    since: Instant,
}

/// The coordinator.
///
/// Cheap to clone: every clone shares one table, which is the point.
#[derive(Debug, Clone, Default)]
pub struct Operations {
    held: Arc<Mutex<BTreeMap<Scope, Holder>>>,
}

impl Operations {
    pub fn new() -> Self {
        Self::default()
    }

    /// Take every scope this operation needs, or none of them.
    ///
    /// Never blocks. A conflict is a [`Busy`] naming the first scope that was
    /// already held, so the caller can say something specific rather than
    /// spinning.
    ///
    /// The returned guard releases on drop, including on panic — which matters,
    /// because a panic inside a repair must not make the launcher permanently
    /// refuse to repair.
    pub fn begin(&self, activity: Activity, scopes: &[Scope]) -> Result<Lease, Busy> {
        // Canonical order. See the module comment: with all-or-nothing,
        // non-blocking acquisition this cannot matter for deadlock, and it is
        // enforced anyway so the invariant is real rather than asserted.
        let mut wanted: Vec<Scope> = scopes.to_vec();
        wanted.sort_by(|a, b| a.rank().cmp(&b.rank()).then_with(|| a.cmp(b)));
        wanted.dedup();

        let mut table = self.held.lock().unwrap_or_else(PoisonError::into_inner);

        // Check them all before taking any.
        for scope in &wanted {
            if let Some(holder) = table.get(scope) {
                return Err(Busy {
                    wanted: activity,
                    holder: holder.activity,
                    scope: scope.clone(),
                    held_for: holder.since.elapsed(),
                });
            }
        }

        let now = Instant::now();
        for scope in &wanted {
            table.insert(
                scope.clone(),
                Holder {
                    activity,
                    since: now,
                },
            );
        }
        drop(table);

        Ok(Lease {
            operations: self.clone(),
            scopes: wanted,
            activity,
        })
    }

    /// What is happening right now. For diagnostics and for the interface.
    pub fn in_progress(&self) -> Vec<(Scope, Activity, Duration)> {
        let table = self.held.lock().unwrap_or_else(PoisonError::into_inner);
        table
            .iter()
            .map(|(scope, holder)| (scope.clone(), holder.activity, holder.since.elapsed()))
            .collect()
    }

    /// Whether a scope is currently held.
    pub fn is_busy(&self, scope: &Scope) -> bool {
        let table = self.held.lock().unwrap_or_else(PoisonError::into_inner);
        table.contains_key(scope)
    }

    /// What is holding a scope, if anything.
    pub fn holder(&self, scope: &Scope) -> Option<Activity> {
        let table = self.held.lock().unwrap_or_else(PoisonError::into_inner);
        table.get(scope).map(|h| h.activity)
    }

    fn release(&self, scopes: &[Scope]) {
        let mut table = self.held.lock().unwrap_or_else(PoisonError::into_inner);
        for scope in scopes {
            table.remove(scope);
        }
    }
}

/// Proof that an operation holds its scopes.
///
/// Not `Clone`: two owners would each release on drop and the second would
/// release scopes the first no longer holds.
#[derive(Debug)]
pub struct Lease {
    operations: Operations,
    scopes: Vec<Scope>,
    activity: Activity,
}

impl Lease {
    pub fn activity(&self) -> Activity {
        self.activity
    }

    /// The scopes held, in the order they were taken.
    pub fn scopes(&self) -> &[Scope] {
        &self.scopes
    }

    /// Whether this lease covers a scope. Used by recovery code to assert it is
    /// operating inside a lease rather than around one.
    pub fn holds(&self, scope: &Scope) -> bool {
        self.scopes.contains(scope)
    }
}

impl Drop for Lease {
    fn drop(&mut self) {
        self.operations.release(&self.scopes);
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

    fn profile(name: &str) -> Scope {
        Scope::Profile(ProfileId::new(name).expect("a valid id"))
    }

    #[test]
    fn a_lease_releases_when_it_is_dropped() {
        let ops = Operations::new();
        {
            let _lease = ops.begin(Activity::RepairingGame, &[Scope::SharedInstallation]);
            assert!(ops.is_busy(&Scope::SharedInstallation));
        }
        assert!(!ops.is_busy(&Scope::SharedInstallation));
    }

    /// Two repairs on the same installation is the conflict this exists for.
    #[test]
    fn a_second_holder_of_the_same_scope_is_refused_with_a_useful_message() {
        let ops = Operations::new();
        let _first = ops
            .begin(Activity::RepairingGame, &[Scope::SharedInstallation])
            .expect("the first one starts");

        let busy = ops
            .begin(Activity::VerifyingGame, &[Scope::SharedInstallation])
            .expect_err("the second must be refused");

        assert_eq!(busy.wanted, Activity::VerifyingGame);
        assert_eq!(busy.holder, Activity::RepairingGame);
        assert_eq!(busy.scope, Scope::SharedInstallation);

        let text = busy.to_string();
        assert!(text.contains("verifying"), "{text}");
        assert!(text.contains("repairing"), "{text}");
        assert!(
            text.contains("shared Minecraft installation"),
            "the message must name the thing that is busy: {text}"
        );
    }

    #[test]
    fn different_profiles_do_not_block_each_other() {
        let ops = Operations::new();
        let _a = ops
            .begin(Activity::RepairingProfile, &[profile("alpha")])
            .expect("alpha");
        let _b = ops
            .begin(Activity::RepairingProfile, &[profile("beta")])
            .expect("beta must not be blocked by alpha");
    }

    /// Deleting a profile while its game is running.
    #[test]
    fn a_running_game_blocks_deleting_its_own_profile() {
        let ops = Operations::new();
        let _running = ops
            .begin(Activity::Running, &[profile("main")])
            .expect("the game starts");

        let busy = ops
            .begin(Activity::DeletingProfile, &[profile("main")])
            .expect_err("deleting a running profile must be refused");
        assert_eq!(busy.holder, Activity::Running);
    }

    /// The property that makes deadlock impossible: a partial acquisition never
    /// happens, so a caller can never be holding one scope while wanting
    /// another that someone else holds.
    #[test]
    fn a_refused_multi_scope_operation_takes_nothing_at_all() {
        let ops = Operations::new();
        let _holder = ops
            .begin(Activity::RepairingJava, &[Scope::JavaRuntime])
            .expect("java repair starts");

        let busy = ops.begin(
            Activity::InstallingGame,
            &[
                Scope::SharedInstallation,
                Scope::JavaRuntime,
                profile("main"),
            ],
        );
        assert!(busy.is_err());

        // Neither of the scopes it *could* have taken is held.
        assert!(
            !ops.is_busy(&Scope::SharedInstallation),
            "a failed acquisition must leave nothing behind"
        );
        assert!(!ops.is_busy(&profile("main")));

        // And they are still free for someone else.
        let _other = ops
            .begin(Activity::VerifyingGame, &[Scope::SharedInstallation])
            .expect("the scope was never taken, so it is available");
    }

    #[test]
    fn scopes_are_taken_in_a_canonical_order() {
        let ops = Operations::new();
        let lease = ops
            .begin(
                Activity::InstallingGame,
                &[
                    profile("main"),
                    Scope::ModStore,
                    Scope::GlobalUpdate,
                    Scope::SharedInstallation,
                ],
            )
            .expect("all free");

        let ranks: Vec<u8> = lease.scopes().iter().map(Scope::rank).collect();
        let mut sorted = ranks.clone();
        sorted.sort_unstable();
        assert_eq!(ranks, sorted, "scopes must be held broad-to-narrow");
        assert_eq!(lease.scopes().first(), Some(&Scope::GlobalUpdate));
    }

    #[test]
    fn asking_for_the_same_scope_twice_is_not_a_conflict_with_itself() {
        let ops = Operations::new();
        let lease = ops
            .begin(
                Activity::ClearingCache,
                &[Scope::SharedInstallation, Scope::SharedInstallation],
            )
            .expect("a duplicate in the request is not a conflict");
        assert_eq!(lease.scopes().len(), 1);
    }

    /// A panic inside a repair must not make the launcher permanently refuse to
    /// repair. `Drop` runs while unwinding, so the lease is released.
    #[test]
    fn a_panicking_operation_still_releases_its_scopes() {
        let ops = Operations::new();
        let result = std::panic::catch_unwind({
            let ops = ops.clone();
            move || {
                let _lease = ops
                    .begin(Activity::RepairingGame, &[Scope::SharedInstallation])
                    .expect("start");
                panic!("something went wrong mid-repair");
            }
        });
        assert!(result.is_err(), "the panic was expected");
        assert!(
            !ops.is_busy(&Scope::SharedInstallation),
            "a panic must not leave a scope held forever"
        );
    }

    #[test]
    fn in_progress_reports_what_is_happening() {
        let ops = Operations::new();
        let _a = ops.begin(Activity::CheckingForUpdates, &[Scope::GlobalUpdate]);
        let _b = ops.begin(Activity::MaterializingMods, &[profile("main")]);

        let running = ops.in_progress();
        assert_eq!(running.len(), 2);
        assert_eq!(running[0].0, Scope::GlobalUpdate, "reported in scope order");
        assert_eq!(running[0].1, Activity::CheckingForUpdates);
    }

    #[test]
    fn holders_are_visible_by_scope() {
        let ops = Operations::new();
        assert_eq!(ops.holder(&Scope::ModStore), None);
        let _lease = ops.begin(Activity::AddingMod, &[Scope::ModStore]);
        assert_eq!(ops.holder(&Scope::ModStore), Some(Activity::AddingMod));
    }

    /// Every conflict named in the milestone brief, as one table.
    #[test]
    fn the_conflicts_the_brief_names_are_all_prevented() {
        /// What is happening, what starts, and what must then be refused.
        ///
        /// Owned rather than borrowed: the profile scopes carry a runtime
        /// `ProfileId`, so there is nothing static to point at.
        type Conflict = (&'static str, Activity, Vec<Scope>, Activity, Vec<Scope>);

        let cases: Vec<Conflict> = vec![
            (
                "launching while the same profile is being mutated",
                Activity::MaterializingMods,
                vec![Scope::Profile(ProfileId::new("main").unwrap())],
                Activity::Launching,
                vec![Scope::Profile(ProfileId::new("main").unwrap())],
            ),
            (
                "deleting a profile while Minecraft is running",
                Activity::Running,
                vec![Scope::Profile(ProfileId::new("main").unwrap())],
                Activity::DeletingProfile,
                vec![Scope::Profile(ProfileId::new("main").unwrap())],
            ),
            (
                "two installation repairs",
                Activity::RepairingGame,
                vec![Scope::SharedInstallation],
                Activity::RepairingGame,
                vec![Scope::SharedInstallation],
            ),
            (
                "two java repairs",
                Activity::RepairingJava,
                vec![Scope::JavaRuntime],
                Activity::RepairingJava,
                vec![Scope::JavaRuntime],
            ),
            (
                "mod materialization while Minecraft is starting",
                Activity::Launching,
                vec![Scope::Profile(ProfileId::new("main").unwrap())],
                Activity::MaterializingMods,
                vec![Scope::Profile(ProfileId::new("main").unwrap())],
            ),
            (
                "profile import while a repair is mutating it",
                Activity::RepairingProfile,
                vec![Scope::Profile(ProfileId::new("main").unwrap())],
                Activity::ImportingProfile,
                vec![Scope::Profile(ProfileId::new("main").unwrap())],
            ),
            (
                "two updater operations",
                Activity::InstallingUpdate,
                vec![Scope::GlobalUpdate],
                Activity::CheckingForUpdates,
                vec![Scope::GlobalUpdate],
            ),
            (
                "cache clearing while a download depends on that cache",
                Activity::InstallingGame,
                vec![Scope::SharedInstallation],
                Activity::ClearingCache,
                vec![Scope::SharedInstallation],
            ),
        ];

        for (what, first, first_scopes, second, second_scopes) in &cases {
            let ops = Operations::new();
            let _held = ops.begin(*first, first_scopes).expect("the first starts");
            assert!(
                ops.begin(*second, second_scopes).is_err(),
                "{what}: the second operation should have been refused"
            );
        }
    }
}
