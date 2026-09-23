//! Quarantined iced-era interface. This file is not a Cargo target.
//!
//! One process, one window, no background services. Everything the interface
//! can do goes through `launcher_core::Launcher`.
//!
//! # This is a normal desktop application
//!
//! It is started by double-clicking `fathom.exe`, from a Start-menu shortcut,
//! or from a taskbar pin. There is no `.bat`, no `.cmd`, no `.ps1`, no
//! `cargo run`, and nothing to type. `docs/reliability.md` §1 covers the build
//! that produces it.
//!
//! Two consequences run through this file:
//!
//! - **The working directory is whatever the shell felt like.** A shortcut with
//!   a blank "Start in" can hand us `C:\Windows\System32`. Nothing here is
//!   resolved relative to it; `DataPaths::discover` finds the per-user data
//!   directory and everything hangs off that.
//! - **In a release build there is no console.** `windows_subsystem = "windows"`
//!   means `stderr` is a handle nobody reads, so anything printed there is
//!   thrown away. Logs go to a file, and a failure before the window opens has
//!   to be shown *as a window*.

// A console window alongside the GUI on Windows is noise for a consumer app,
// and it is the thing that makes a Rust program look like a development build.
// Debug builds keep the console, because useful output during development is
// not something to hide.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod background;
mod components;
mod failure;
mod icons;
mod motion;
mod screens;
mod style;
mod worker;

use app::App;
use failure::Failure;

use launcher_core::ops::Operations;
use launcher_core::paths::DataPaths;
use launcher_core::problem::{Category, Problem, Retry};
use launcher_core::recovery::{startup::StartupReport, RecoveryService};

fn main() -> iced::Result {
    // A self-check has to answer before anything else happens, because the
    // updater runs it on a replacement binary while the previous version is
    // still on disk waiting to be put back. It must not open a window, touch
    // the data directory, or take longer than a moment.
    if std::env::args().any(|a| a == "--self-check") {
        println!(
            "{} {} self-check ok",
            launcher_core::PRODUCT_NAME,
            launcher_core::PRODUCT_VERSION
        );
        return Ok(());
    }

    let mut boot = Boot::run();

    // A launcher that cannot start still opens a window and says why.
    //
    // The alternative -- and what this used to do -- is `eprintln!` and
    // `exit(1)`, which in a windowed build means the user double-clicks an icon
    // and watches nothing happen at all.
    if boot.core.is_none() {
        let problems = boot.problems.clone();
        let log = boot.log_file.clone();
        return iced::application(Failure::title, Failure::update, Failure::view)
            .theme(Failure::theme)
            .default_font(style::FONT_APP)
            .window(iced::window::Settings {
                size: iced::Size::new(760.0, 500.0),
                min_size: Some(iced::Size::new(640.0, 420.0)),
                position: iced::window::Position::Centered,
                ..iced::window::Settings::default()
            })
            .run_with(move || Failure::new(problems.clone(), log.clone()));
    }

    iced::application(App::title, App::update, App::view)
        .subscription(App::subscription)
        .theme(App::theme)
        .default_font(style::FONT_APP)
        .window(iced::window::Settings {
            size: iced::Size::new(1140.0, 760.0),
            min_size: Some(iced::Size::new(840.0, 600.0)),
            position: iced::window::Position::Centered,
            ..iced::window::Settings::default()
        })
        .antialiasing(true)
        .run_with(move || App::new_with_boot(boot.take()))
}

/// Everything that happens before the window exists.
///
/// The result is *data*, handed to the application, rather than something
/// printed. A release build has nowhere to print to, so a boot failure that
/// only existed as console output would be indistinguishable from the launcher
/// silently not starting — which is the worst failure mode a double-clicked
/// application has.
#[derive(Debug, Default)]
pub struct Boot {
    /// The core, if it could be built. `None` means the failure window.
    pub core: Option<launcher_core::Launcher>,
    /// Where the log went, so the interface can offer to open it.
    pub log_file: Option<std::path::PathBuf>,
    /// What the startup scan found. Empty on a healthy launcher.
    pub startup: Option<StartupReport>,
    /// Anything that went wrong before the window opened.
    pub problems: Vec<Problem>,
    /// The shared operation coordinator, so the interface and its workers agree
    /// about what is in progress.
    pub operations: Operations,
}

impl Boot {
    /// Move the contents out.
    ///
    /// `iced`'s `run_with` takes an `FnMut`, so the closure cannot consume a
    /// captured value. It is only ever called once, and taking the fields
    /// leaves an empty `Boot` behind rather than requiring `Launcher: Clone` --
    /// which it must not be, because two `Launcher`s would be two views of one
    /// on-disk state.
    fn take(&mut self) -> Boot {
        Boot {
            core: self.core.take(),
            log_file: self.log_file.take(),
            startup: self.startup.take(),
            problems: std::mem::take(&mut self.problems),
            operations: self.operations.clone(),
        }
    }

    fn run() -> Self {
        let mut boot = Boot {
            operations: Operations::new(),
            ..Boot::default()
        };

        // 1. Paths. Everything else depends on them, and there is no useful
        //    fallback: without a data directory there is nowhere to put a
        //    profile.
        let paths = match DataPaths::discover() {
            Ok(paths) => paths,
            Err(error) => {
                boot.problems.push(
                    Problem::new(
                        Category::Storage,
                        "Fathom could not find a place to keep its data on this system.",
                    )
                    .caused_by(&error)
                    .retry(Retry::Pointless),
                );
                return boot;
            }
        };

        if let Err(error) = paths.ensure() {
            boot.problems.push(
                Problem::new(
                    Category::Storage,
                    format!(
                        "Fathom could not create its data directory at {}.",
                        paths.root().display()
                    ),
                )
                .caused_by(&error)
                .detail("This usually means the drive is full or the folder is read-only.")
                .retry(Retry::Worthwhile),
            );
            // Keep going: logging and the window may still work, and a visible
            // window that explains the problem beats no window at all.
        }

        // 2. Logging, to a file, because a windowed build has no stderr. Debug
        //    builds also keep the console.
        match launcher_core::logging::init_with_file(
            cfg!(debug_assertions),
            &paths.launcher_logs_dir(),
            cfg!(debug_assertions),
        ) {
            Ok(path) => boot.log_file = Some(path),
            Err(error) => {
                // Not fatal. A launcher that refuses to start because it cannot
                // write a log is worse than one that runs without logging.
                launcher_core::logging::init(cfg!(debug_assertions));
                boot.problems.push(
                    Problem::new(
                        Category::Storage,
                        "Fathom could not open its log file, so this session will not be \
                         recorded.",
                    )
                    .caused_by(&error)
                    .retry(Retry::Worthwhile),
                );
            }
        }

        tracing::info!(
            version = launcher_core::PRODUCT_VERSION,
            data = %paths.root().display(),
            "launcher starting"
        );

        // 3. Startup recovery. Fast checks only -- see
        //    `recovery::startup`. Anything that might matter to the user is
        //    reported rather than fixed.
        let recovery = RecoveryService::new(paths, boot.operations.clone());
        match recovery.run_startup_scan(launcher_core::PRODUCT_VERSION) {
            Ok(report) => {
                for finding in &report.findings {
                    tracing::info!(finding = %finding.message(), "startup recovery");
                }
                boot.problems.extend(report.problems());
                boot.startup = Some(report);
            }
            Err(problem) => boot.problems.push(problem),
        }

        // 4. The core itself, last, so that a failure here still has logging
        //    and still reports whatever the startup scan found.
        match launcher_core::Launcher::discover() {
            Ok(core) => boot.core = Some(core),
            Err(error) => boot.problems.push(
                Problem::new(
                    Category::Storage,
                    "Fathom could not open its data directory, so it cannot start.",
                )
                .caused_by(&error)
                .detail(
                    "Check that the folder exists, that the drive is not full, and that \
                     another copy of Fathom is not running.",
                )
                .retry(Retry::Worthwhile),
            ),
        }

        boot
    }
}
