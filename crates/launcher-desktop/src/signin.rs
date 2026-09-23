//! Driving a sign-in, off the window's thread.
//!
//! The device-code flow spends most of its life asleep between polls, waiting
//! for somebody to finish signing in on Microsoft's page. Holding the
//! launcher's mutex for that would freeze the whole interface for as long as it
//! takes.
//!
//! So this borrows the shape of `session`: read what is needed under a brief
//! lock, do the waiting holding nothing, and take the lock again only to adopt
//! the result. `Tasks` is the piece that makes it possible — it is an owned
//! handle to the auth service and the credential store, obtained from the
//! launcher and usable without it.
//!
//! # What the user sees
//!
//! Pressing Sign In — in the launcher, or in the game through the bridge —
//! asks Microsoft for a code and opens Microsoft's own sign-in page in the
//! default browser with that code filled in. The drawer shows the code too, and
//! a button to open the page again, so nothing depends on the browser opening.
//! When the account holder finishes, polling picks it up, the Xbox, XSTS and
//! Minecraft steps run, and the drawer shows their username and face.
//!
//! Every way that can end — finished, cancelled, declined, expired, refused by
//! Xbox, not owning the game, no network — settles the supervisor in a
//! non-active phase with a sentence, so nothing is left spinning.

use tauri::Manager;

use launcher_core::auth::{AuthError, XboxError};
use launcher_core::network::{NetworkError, PolicyDenial};
use launcher_core::{Cancel, Launcher, ServiceError};

use crate::api::auth::{AuthPhase, AuthProgress};
use crate::State;

/// Begin a Microsoft sign-in.
///
/// Refused rather than faked when this build has no application id: without one
/// there is no legitimate way to ask Microsoft anything, and inventing an
/// identity to get past the button is exactly what must not happen.
pub fn start(app: &tauri::AppHandle) -> Result<AuthProgress, String> {
    {
        let state = app.state::<State>();
        let core = state
            .core
            .lock()
            .map_err(|_| "launcher state is poisoned")?;
        if !core.sign_in_available() {
            return Err(
                "This build has no Microsoft application id, so it cannot sign in. Set \
                 FATHOM_AZURE_CLIENT_ID to your own registration."
                    .to_owned(),
            );
        }
    }

    let cancel = {
        let state = app.state::<State>();
        state.auth.begin(AuthProgress::starting())?
    };

    spawn(app, cancel, run_device_code)?;
    Ok(app.state::<State>().auth.progress())
}

/// What the in-game Sign In button asks for, through the bridge.
///
/// Brings the launcher forward with its account drawer open, and starts the
/// same sign-in the launcher's own button starts — unless an account is
/// already signed in, or a sign-in is already under way, in which case it only
/// shows it. Returns the word the bridge replies with.
pub fn from_game(app: &tauri::AppHandle) -> Result<&'static str, String> {
    // Tried, never waited for: this runs on the bridge thread while the game
    // waits a quarter of a second. A launch holding the lock for its second of
    // work is "busy", not "signed out" -- guessing the latter would start a
    // second sign-in for somebody already signed in.
    let signed_in = match app.state::<State>().core.try_lock() {
        Ok(core) => core.identity().is_some(),
        Err(_) => return Err("busy".to_owned()),
    };
    if signed_in {
        return Ok("signed-in");
    }
    if app.state::<State>().auth.progress().active {
        return Ok("signing-in");
    }
    start(app).map(|_| "started")
}

/// Bring the main window forward. Posted to the main thread.
fn raise(app: &tauri::AppHandle) {
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || {
        if let Some(window) = handle.get_webview_window("main") {
            let _ = window.unminimize();
            let _ = window.show();
            let _ = window.set_focus();
        }
    });
}

/// Try the stored refresh token, once, at start-up.
///
/// Silent when there is nothing stored: a first run must not report a failed
/// sign-in it never attempted. Failure here settles as `Failed` rather than
/// signing the user out, because "we could not reach Microsoft just now" and
/// "you are signed out" are different things to be told.
pub fn restore(app: &tauri::AppHandle) {
    {
        let state = app.state::<State>();
        let Ok(core) = state.core.lock() else { return };
        if core.remembered_account_id().is_none() || !core.sign_in_available() {
            return;
        }
    }

    let Ok(cancel) = ({
        let state = app.state::<State>();
        state.auth.begin(AuthProgress::restoring())
    }) else {
        return;
    };

    let _ = spawn(app, cancel, run_restore);
}

/// Refresh the session if its Minecraft token is about to run out.
///
/// Called before a launch. Blocks the calling thread (the launch thread, never
/// the window's) for as long as the refresh takes; the launcher's lock is held
/// only to read and to adopt. Failure is left for the launch gate to report,
/// because it already explains an expired session.
pub fn refresh_if_needed(app: &tauri::AppHandle) {
    const MARGIN: std::time::Duration = std::time::Duration::from_secs(10 * 60);
    let (tasks, account_id) = {
        let state = app.state::<State>();
        let Ok(core) = state.core.lock() else { return };
        if !core.session_needs_refresh(MARGIN) {
            return;
        }
        let Some(id) = core.remembered_account_id().map(str::to_owned) else {
            return;
        };
        (core.tasks(), id)
    };
    match tasks.restore_session(&account_id) {
        Ok(Some(signed_in)) => {
            let state = app.state::<State>();
            let Ok(mut core) = state.core.lock() else {
                return;
            };
            if let Err(error) = core.adopt_session(signed_in) {
                tracing::warn!(error = %describe(&error), "could not adopt the refreshed session");
            } else {
                tracing::info!("refreshed the Minecraft session before launching");
            }
        }
        Ok(None) => {}
        Err(error) => tracing::warn!(error = %describe(&error), "could not refresh the session"),
    }
}

/// Abandon the attempt in flight.
pub fn cancel(app: &tauri::AppHandle) -> Result<AuthProgress, String> {
    let state = app.state::<State>();
    state.auth.request_cancel()?;
    Ok(state.auth.progress())
}

/// Open Microsoft's page again for the code on screen.
pub fn reopen_page(app: &tauri::AppHandle) -> Result<AuthProgress, String> {
    let progress = app.state::<State>().auth.progress();
    match (
        progress.phase,
        progress.user_code,
        progress.verification_uri,
    ) {
        (AuthPhase::Waiting, Some(code), Some(uri)) => {
            crate::pages::open_microsoft_sign_in(&uri, &code)?;
            Ok(app.state::<State>().auth.progress())
        }
        _ => Err("there is no sign-in waiting for you".to_owned()),
    }
}

/// Forget the account and delete the stored refresh token.
pub fn sign_out(app: &tauri::AppHandle) -> Result<AuthProgress, String> {
    let state = app.state::<State>();
    let mut core = state
        .core
        .lock()
        .map_err(|_| "launcher state is poisoned")?;
    core.sign_out().map_err(|error| describe(&error))?;
    state.auth.set(AuthProgress::idle());
    Ok(state.auth.progress())
}

/// Run one attempt on its own thread, settling the supervisor when it ends.
fn spawn<F>(
    app: &tauri::AppHandle,
    cancel: Cancel,
    body: F,
) -> Result<(), String>
where
    F: FnOnce(&tauri::AppHandle, &Cancel) -> AuthProgress + Send + 'static,
{
    let handle = app.clone();
    let spawned = std::thread::Builder::new()
        .name("fathom-signin".to_owned())
        .spawn(move || {
            let outcome = body(&handle, &cancel);
            handle.state::<State>().auth.set(outcome);
        });
    if let Err(error) = spawned {
        // The supervisor was claimed; release it, or every later attempt is
        // refused as "already under way".
        app.state::<State>().auth.set(AuthProgress::failed(
            "Fathom could not start the sign-in. Try again.",
        ));
        return Err(format!("could not start the sign-in: {error}"));
    }
    Ok(())
}

/// Restore a different remembered account and make it the active account.
/// The old session remains usable until the replacement has been verified, so
/// a network failure cannot silently sign somebody out.
pub fn switch_account(app: &tauri::AppHandle, id: String) -> Result<AuthProgress, String> {
    let id = launcher_core::auth::account::normalise_uuid(&id)
        .ok_or_else(|| "invalid account id".to_owned())?;
    {
        let state = app.state::<State>();
        let core = state
            .core
            .lock()
            .map_err(|_| "launcher state is poisoned")?;
        if !core.accounts().iter().any(|account| account.id == id) {
            return Err("that account is not remembered on this device".to_owned());
        }
    }
    let cancel = app
        .state::<State>()
        .auth
        .begin(AuthProgress::restoring())?;
    spawn(app, cancel, move |handle, _| run_restore_account(handle, &id))?;
    Ok(app.state::<State>().auth.progress())
}

fn run_restore_account(app: &tauri::AppHandle, account_id: &str) -> AuthProgress {
    let tasks = {
        let state = app.state::<State>();
        let Ok(core) = state.core.lock() else {
            return AuthProgress::failed("The launcher is in a bad state. Restart Fathom.");
        };
        core.tasks()
    };
    match tasks.restore_session(account_id) {
        Ok(Some(signed_in)) => adopt(app, |core| core.adopt_session(signed_in)),
        Ok(None) => AuthProgress::failed(
            "That saved sign-in is no longer available. Forget it and add the account again.",
        ),
        Err(error) => AuthProgress::failed(describe(&error)),
    }
}

pub fn forget_account(app: &tauri::AppHandle, id: String) -> Result<AuthProgress, String> {
    let state = app.state::<State>();
    let mut core = state
        .core
        .lock()
        .map_err(|_| "launcher state is poisoned")?;
    core.forget_account(&id).map_err(|error| describe(&error))?;
    state.auth.set(AuthProgress::idle());
    Ok(state.auth.progress())
}

fn run_device_code(app: &tauri::AppHandle, cancel: &Cancel) -> AuthProgress {
    let tasks = {
        let state = app.state::<State>();
        let Ok(core) = state.core.lock() else {
            return AuthProgress::failed("The launcher is in a bad state. Restart Fathom.");
        };
        core.tasks()
    };

    // The code arrives once, in a callback, part-way through a call that then
    // blocks for minutes. Opening the page and writing the progress from
    // inside that callback is what puts both in front of the user while the
    // same call is still polling.
    let handle = app.clone();
    let signed_in = tasks.sign_in(cancel, move |prompt| {
        let (opened, copied) =
            match crate::pages::open_microsoft_sign_in(&prompt.verification_uri, &prompt.user_code)
            {
                Ok(copied) => (true, copied),
                Err(error) => {
                    tracing::warn!(%error, "could not open the Microsoft sign-in page");
                    (false, false)
                }
            };
        handle.state::<State>().auth.set(AuthProgress::waiting(
            &prompt.user_code,
            &prompt.verification_uri,
            opened,
            copied,
        ));
    });

    let signed_in = match signed_in {
        Ok(signed_in) => signed_in,
        Err(error) => {
            return if cancel.is_cancelled() || matches!(error, ServiceError::Cancelled) {
                AuthProgress::cancelled()
            } else {
                tracing::info!(error = %describe(&error), "sign-in did not complete");
                AuthProgress::failed(describe(&error))
            };
        }
    };

    app.state::<State>().auth.set(AuthProgress::verifying());
    let outcome = adopt(app, |core| core.adopt_session(signed_in));
    if outcome.phase == AuthPhase::SignedIn {
        raise(app);
    }
    outcome
}

fn run_restore(app: &tauri::AppHandle, _cancel: &Cancel) -> AuthProgress {
    // Read under the lock, refresh without it: the refresh is three or four
    // round trips to Microsoft, Xbox and Mojang, and the window must stay
    // usable while they happen.
    let (tasks, account_id) = {
        let state = app.state::<State>();
        let Ok(core) = state.core.lock() else {
            return AuthProgress::failed("The launcher is in a bad state. Restart Fathom.");
        };
        let Some(id) = core.remembered_account_id().map(str::to_owned) else {
            return AuthProgress::idle();
        };
        (core.tasks(), id)
    };

    match tasks.restore_session(&account_id) {
        // Nothing stored after all, which is not a failure and not worth a
        // message: the drawer already says "not signed in".
        Ok(None) => {
            // Logged, because "nothing stored" and "restored fine" were both
            // silent, and the only way to tell them apart was to watch the
            // window and guess.
            tracing::info!(
                account = %account_id,
                "no stored session for the remembered account; it will need signing in"
            );
            AuthProgress::idle()
        }
        Ok(Some(signed_in)) => {
            tracing::info!(account = %account_id, "restored the remembered account at start-up");
            adopt(app, |core| core.adopt_session(signed_in))
        }
        Err(error) => {
            tracing::info!(error = %describe(&error), "the saved session could not be restored");
            AuthProgress::failed(describe(&error))
        }
    }
}

/// Take the lock, apply the result, and report who is signed in.
fn adopt(
    app: &tauri::AppHandle,
    apply: impl FnOnce(&mut Launcher) -> Result<(), ServiceError>,
) -> AuthProgress {
    let state = app.state::<State>();
    let Ok(mut core) = state.core.lock() else {
        return AuthProgress::failed("The launcher is in a bad state. Restart Fathom.");
    };
    match apply(&mut core) {
        Ok(()) => signed_in_progress(&core),
        Err(error) => AuthProgress::failed(describe(&error)),
    }
}

fn signed_in_progress(core: &Launcher) -> AuthProgress {
    core.identity().map_or_else(
        || AuthProgress::failed("The sign-in finished but no account was recorded. Try again."),
        |identity| AuthProgress::signed_in(&identity.profile.name),
    )
}

/// The sentence a person is shown for a failed sign-in.
///
/// Written for the person, not the log: what happened and what to do next. The
/// underlying errors never carry a token — the network layer's `Display`
/// omits response bodies, and every token type is a `Secret` — so nothing
/// here can leak one; this only chooses kinder words.
pub fn describe(error: &ServiceError) -> String {
    match error {
        ServiceError::Cancelled => "Sign-in was cancelled. Nothing was saved.".to_owned(),
        ServiceError::Auth(auth) => describe_auth(auth),
        other => sentence(&other.to_string()),
    }
}

fn describe_auth(error: &AuthError) -> String {
    match error {
        AuthError::NoClientId => {
            "This build has no Microsoft application id, so it cannot sign in.".to_owned()
        }
        AuthError::Network(network) => describe_network(network),
        AuthError::Declined => {
            "The sign-in was declined on Microsoft's page. Nothing was saved.".to_owned()
        }
        AuthError::DeviceCodeExpired => {
            "The sign-in took too long and its code expired. Try again.".to_owned()
        }
        AuthError::OAuth { code, .. } if code == "invalid_grant" => {
            "Your saved sign-in is no longer valid. Sign in again.".to_owned()
        }
        AuthError::OAuth { code, .. } if code == "access_denied" || code == "consent_required" => {
            "Microsoft did not grant access. Approve the permissions on Microsoft's page to sign in."
                .to_owned()
        }
        AuthError::OAuth { code, .. } => {
            format!("Microsoft refused the sign-in ({code}). Try again in a moment.")
        }
        AuthError::Xbox(xbox) => match xbox {
            XboxError::Unknown(code) => format!(
                "Xbox Live refused the sign-in (code {code}). Check your account at xbox.com, then try again."
            ),
            other => other.to_string(),
        },
        AuthError::NotApproved => {
            "Minecraft's services refused this launcher or this account (HTTP 403). Try again later."
                .to_owned()
        }
        AuthError::MinecraftService { status } => format!(
            "Minecraft's account service did not answer properly (HTTP {status}). Try again shortly."
        ),
        AuthError::Entitlement(_) | AuthError::NotEntitled(_) => {
            "This Microsoft account does not own Minecraft: Java Edition. If you bought it on \
             another account, sign in with that one."
                .to_owned()
        }
        AuthError::NoProfile => error.to_string(),
        AuthError::Credentials(_) => {
            "You are signed in, but Fathom could not save the sign-in to your system's credential \
             store, so you will be asked again next time."
                .to_owned()
        }
    }
}

fn describe_network(error: &NetworkError) -> String {
    match error {
        NetworkError::Denied(PolicyDenial::Offline) => {
            "Offline mode is on. Turn it off in Settings to sign in.".to_owned()
        }
        NetworkError::Cancelled => "Sign-in was cancelled. Nothing was saved.".to_owned(),
        NetworkError::Status { status, .. } if *status >= 500 => {
            "Microsoft's sign-in service is having trouble. Try again shortly.".to_owned()
        }
        _ => "Fathom could not reach Microsoft. Check your internet connection and try again."
            .to_owned(),
    }
}

fn sentence(message: &str) -> String {
    let mut chars = message.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        None => "Sign-in failed.".to_owned(),
    }
}

/// Sign in with the fake development identity.
///
/// Compiled out entirely without the `dev-auth` feature, and refused even then
/// unless the environment variable is set. It owns nothing, cannot join an
/// online-mode server, and every view that names it carries a `development`
/// flag the interface shows permanently. It exists so the launch pipeline can
/// be exercised on a machine with no Microsoft registration -- not so that
/// anybody can play without one.
#[cfg(feature = "dev-auth")]
pub fn development(app: &tauri::AppHandle) -> Result<AuthProgress, String> {
    let state = app.state::<State>();
    let mut core = state
        .core
        .lock()
        .map_err(|_| "launcher state is poisoned")?;
    if !core.development_auth_available() {
        return Err("the development identity is not enabled in this environment".to_owned());
    }
    core.sign_in_development().map_err(|e| e.to_string())?;
    let progress = signed_in_progress(&core);
    state.auth.set(progress.clone());
    Ok(progress)
}

#[cfg(not(feature = "dev-auth"))]
pub fn development(_app: &tauri::AppHandle) -> Result<AuthProgress, String> {
    Err("this build has no development identity".to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::auth::{AuthPhase, AuthProgress};

    // A release build has no path to a fake identity at all: `development` is
    // one `Err` and `Launcher::sign_in_development` is not compiled in. That is
    // enforced by `#[cfg]` above rather than by a test, because a test can only
    // ever confirm what the compiler has already decided.

    /// Restoring must be distinguishable from signing in: one is something the
    /// user asked for and the other happens at start-up, and a drawer that
    /// showed "Contacting Microsoft" every launch would be alarming.
    #[test]
    fn restoring_is_its_own_phase() {
        assert_eq!(AuthProgress::restoring().phase, AuthPhase::Restoring);
        assert_ne!(
            AuthProgress::restoring().phase,
            AuthProgress::starting().phase
        );
    }

    /// Every way a sign-in can end has its own sentence, and none of them is a
    /// bare error code.
    #[test]
    fn every_failure_is_explained_in_words() {
        let cases = [
            ServiceError::Cancelled,
            ServiceError::Auth(AuthError::Declined),
            ServiceError::Auth(AuthError::DeviceCodeExpired),
            ServiceError::Auth(AuthError::Xbox(XboxError::ChildAccount)),
            ServiceError::Auth(AuthError::Xbox(XboxError::Unknown(2_148_916_239))),
            ServiceError::Auth(AuthError::NotEntitled("no".to_owned())),
            ServiceError::Auth(AuthError::NoProfile),
            ServiceError::Auth(AuthError::Network(NetworkError::Denied(
                PolicyDenial::Offline,
            ))),
            ServiceError::Auth(AuthError::OAuth {
                code: "invalid_grant".to_owned(),
                description: Some("AADSTS70000: secret-looking detail".to_owned()),
            }),
        ];
        let mut seen = std::collections::BTreeSet::new();
        for error in &cases {
            let text = describe(error);
            assert!(text.len() > 20, "{text}");
            assert!(text.ends_with('.'), "{text}");
            assert!(
                !text.contains("AADSTS"),
                "provider detail is not shown: {text}"
            );
            seen.insert(text);
        }
        assert!(
            seen.len() >= cases.len() - 1,
            "distinct situations read differently"
        );
        assert!(describe(&cases[5]).contains("does not own Minecraft"));
    }
}
