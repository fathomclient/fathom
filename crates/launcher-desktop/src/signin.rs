//! Driving a sign-in, off the window's thread.
//!
//! The device-code flow spends most of its life asleep between polls, waiting
//! for somebody to type a code on a page. Holding the launcher's mutex for that
//! would freeze the whole interface for as long as it takes to find a phone.
//!
//! So this borrows the shape of `session`: read what is needed under a brief
//! lock, do the waiting holding nothing, and take the lock again only to adopt
//! the result. `Tasks` is the piece that makes it possible — it is an owned
//! handle to the auth service and the credential store, obtained from the
//! launcher and usable without it.

use tauri::Manager;

use launcher_core::{Cancel, Launcher};

use crate::api::auth::AuthProgress;
use crate::State;

/// Begin a Microsoft sign-in.
///
/// Refused rather than faked when this build has no application id: without one
/// there is no legitimate way to ask Microsoft anything, and inventing an
/// identity to get past the button is exactly what must not happen.
pub fn start(app: &tauri::AppHandle) -> Result<AuthProgress, String> {
    {
        let state = app.state::<State>();
        let core = state.core.lock().map_err(|_| "launcher state is poisoned")?;
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
        if core.remembered_account_id().is_none() {
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

/// Abandon the attempt in flight.
pub fn cancel(app: &tauri::AppHandle) -> Result<AuthProgress, String> {
    let state = app.state::<State>();
    state.auth.request_cancel()?;
    Ok(state.auth.progress())
}

/// Forget the account and delete the stored refresh token.
pub fn sign_out(app: &tauri::AppHandle) -> Result<AuthProgress, String> {
    let state = app.state::<State>();
    let mut core = state.core.lock().map_err(|_| "launcher state is poisoned")?;
    core.sign_out().map_err(|error| error.to_string())?;
    state.auth.set(AuthProgress::idle());
    Ok(state.auth.progress())
}

/// Run one attempt on its own thread, settling the supervisor when it ends.
fn spawn(
    app: &tauri::AppHandle,
    cancel: Cancel,
    body: fn(&tauri::AppHandle, &Cancel) -> AuthProgress,
) -> Result<(), String> {
    let handle = app.clone();
    std::thread::Builder::new()
        .name("fathom-signin".to_owned())
        .spawn(move || {
            let outcome = body(&handle, &cancel);
            handle.state::<State>().auth.set(outcome);
        })
        .map_err(|error| format!("could not start the sign-in: {error}"))?;
    Ok(())
}

fn run_device_code(app: &tauri::AppHandle, cancel: &Cancel) -> AuthProgress {
    let tasks = {
        let state = app.state::<State>();
        let Ok(core) = state.core.lock() else {
            return AuthProgress::failed("the launcher is in a bad state; restart Fathom");
        };
        core.tasks()
    };

    // The code arrives once, in a callback, part-way through a call that then
    // blocks for minutes. Writing it into the supervisor from inside that
    // callback is what puts it on screen while the same call is still polling.
    let handle = app.clone();
    let signed_in = tasks.sign_in(cancel, move |prompt| {
        handle
            .state::<State>()
            .auth
            .set(AuthProgress::waiting(
                &prompt.user_code,
                &prompt.verification_uri,
            ));
    });

    let signed_in = match signed_in {
        Ok(signed_in) => signed_in,
        Err(error) => {
            return if cancel.is_cancelled() {
                AuthProgress::cancelled()
            } else {
                AuthProgress::failed(error.to_string())
            }
        }
    };

    app.state::<State>().auth.set(AuthProgress::verifying());
    adopt(app, |core| core.adopt_session(signed_in))
}

fn run_restore(app: &tauri::AppHandle, _cancel: &Cancel) -> AuthProgress {
    let state = app.state::<State>();
    let Ok(mut core) = state.core.lock() else {
        return AuthProgress::failed("the launcher is in a bad state; restart Fathom");
    };
    match core.restore_session() {
        // Nothing stored after all, which is not a failure and not worth a
        // message: the drawer already says "not signed in".
        Ok(false) => AuthProgress::idle(),
        Ok(true) => signed_in_progress(&core),
        Err(error) => AuthProgress::failed(error.to_string()),
    }
}

/// Take the lock, apply the result, and report who is signed in.
fn adopt(
    app: &tauri::AppHandle,
    apply: impl FnOnce(&mut Launcher) -> Result<(), launcher_core::ServiceError>,
) -> AuthProgress {
    let state = app.state::<State>();
    let Ok(mut core) = state.core.lock() else {
        return AuthProgress::failed("the launcher is in a bad state; restart Fathom");
    };
    match apply(&mut core) {
        Ok(()) => signed_in_progress(&core),
        Err(error) => AuthProgress::failed(error.to_string()),
    }
}

fn signed_in_progress(core: &Launcher) -> AuthProgress {
    core.identity().map_or_else(
        || AuthProgress::failed("the sign-in finished but no identity was recorded"),
        |identity| AuthProgress::signed_in(&identity.profile.name),
    )
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
    let mut core = state.core.lock().map_err(|_| "launcher state is poisoned")?;
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
        assert_ne!(AuthProgress::restoring().phase, AuthProgress::starting().phase);
    }
}
