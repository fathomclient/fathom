//! Headless companion to the desktop app.
//!
//! Exists for two reasons:
//!
//! - it proves `launcher-core` really is UI-agnostic, which is what makes the
//!   iced-versus-something-else decision cheap to revisit;
//! - `doctor` prints the network allowlist, the resolved paths and the Java
//!   situation, which is the fastest way to check that a build behaves the way
//!   the privacy documentation claims.
//!
//! It performs no network activity unless a subcommand explicitly does.

use std::process::ExitCode;

use launcher_core::minecraft_runtime::{judge_heap, validate_args, HeapVerdict};
use launcher_core::network::{Consent, Feature};
use launcher_core::services::human_bytes;
use launcher_core::Cancel;
use launcher_core::InstallSnapshot;
use launcher_core::Launcher;

mod integration;

fn main() -> ExitCode {
    launcher_core::logging::init(std::env::var("FATHOM_LOG").is_ok());

    let args: Vec<String> = std::env::args().skip(1).collect();
    let command = args.first().map(String::as_str).unwrap_or("doctor");

    let result = match command {
        "doctor" => doctor(),
        "profiles" => profiles(),
        "allowlist" => allowlist(),
        "check-args" => check_args(args.get(1..).unwrap_or_default()),
        "prepare" => prepare(args.get(1).map(String::as_str)),
        "install" => install(args.get(1).map(String::as_str)),
        "verify" => verify(args.get(1).map(String::as_str)),
        "repair" => repair(args.get(1).map(String::as_str)),
        "plan" => plan(args.get(1).map(String::as_str)),
        "launch" => launch(args.get(1).map(String::as_str)),
        "profile" => profile_command(args.get(1..).unwrap_or_default()),
        "mods" => mods_command(args.get(1..).unwrap_or_default()),
        "loaders" => loaders_command(),
        "update" => update_command(args.get(1..).unwrap_or_default()),
        "recover" => recover_command(args.get(1..).unwrap_or_default()),
        "diagnostics" => diagnostics_command(args.get(1..).unwrap_or_default()),
        "integration" => integration_command(args.get(1..).unwrap_or_default()),
        "bench" => bench_command(args.get(1..).unwrap_or_default()),
        // The argument `install::SelfCheckValidator` runs on a replacement
        // before the previous copy is released. It must be cheap, must touch
        // nothing, and must exit non-zero if this build is not usable.
        "--self-check" | "self-check" => self_check(),
        "help" | "--help" | "-h" => {
            usage();
            Ok(())
        }
        other => {
            eprintln!("unknown command: {other}\n");
            usage();
            return ExitCode::from(2);
        }
    };

    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}

/// Prove this build runs.
///
/// Deliberately minimal: it loads no profile, opens no socket, and writes
/// nothing. The question it answers is "did this binary start and can it reach
/// its own code", which is exactly the question an update needs answered before
/// it throws away the version that was working.
///
/// It does check the compiled-in update anchor parses, because a build whose
/// anchor is malformed is a build that can never update again — a slow brick
/// rather than a fast one, and worth catching here.
/// Turn a message into the error type this CLI reports.
fn fail(message: String) -> launcher_core::ServiceError {
    launcher_core::ServiceError::Failed(message)
}

/// The benchmark harness: what to run, and what the runs said.
///
/// # What this does and does not do
///
/// It does not start Minecraft. A benchmark needs a human to put the game into
/// a scenario — stand at the recorded coordinates, face the wall, do not move —
/// and a harness that launched the game would still need that human. What it
/// does is everything either side of that: say precisely what to set up, and
/// then read the runs back, refuse the comparisons that are not valid, and write
/// the summary.
///
/// ```text
///   fathom-cli bench plan                 what to run, in order
///   fathom-cli bench scenarios            the scenarios and their setup
///   fathom-cli bench summary [directory]  read today's runs and write summary.md
/// ```
fn bench_command(args: &[String]) -> Result<(), launcher_core::ServiceError> {
    match args.first().map(String::as_str).unwrap_or("plan") {
        "plan" => bench_plan(),
        "scenarios" => bench_scenarios(),
        "summary" => bench_summary(args.get(1).map(String::as_str)),
        other => Err(fail(format!(
            "unknown bench command {other:?}. Try: plan, scenarios, summary"
        ))),
    }
}

fn bench_plan() -> Result<(), launcher_core::ServiceError> {
    println!("Fathom benchmark plan\n");
    println!("Run every configuration through the same scenario, in this order, alternating");
    println!("the order between sessions so thermal drift does not favour whichever went first.\n");

    for (index, configuration) in CONFIGURATIONS.iter().enumerate() {
        println!("{}. {}  [{}]", index + 1, configuration.name, configuration.id);
        println!("   {}", configuration.purpose);
        println!("   Setup: {}\n", configuration.setup);
    }

    println!("The number this milestone is gated on is the gap between b-loader and");
    println!("d-performance-off in the cpu-ceiling scenario.\n");
    println!(
        "  target  <= {:.0}%    limit  <= {:.0}%    above the limit: stop and find it",
        launcher_core::bench::OVERHEAD_TARGET_PERCENT,
        launcher_core::bench::OVERHEAD_LIMIT_PERCENT
    );
    println!("  bench            benchmark plan, scenarios and summaries");
    println!();
    println!("a-vanilla and b-loader contain no Fathom, so Fathom cannot time them. Capture");
    println!("those two with an external frame-time tool and write the result into the same");
    println!("directory in the same shape, with \"source\" naming the tool.");
    Ok(())
}

fn bench_scenarios() -> Result<(), launcher_core::ServiceError> {
    println!("Fathom benchmark scenarios\n");
    for scenario in SCENARIOS {
        println!("{}  [{}]", scenario.name, scenario.id);
        println!("  {}", scenario.description);
        println!("  Setup: {}", scenario.setup);
        println!("  Duration: {} s\n", scenario.seconds);
    }
    println!("Settings that must be identical across every run:\n");
    for field in launcher_core::bench::MUST_MATCH {
        println!("  {field}");
    }
    println!("\nThe game records all of them into each run, and `bench summary` refuses a");
    println!("comparison whose settings differ rather than printing a percentage for it.");
    Ok(())
}

fn bench_summary(directory: Option<&str>) -> Result<(), launcher_core::ServiceError> {
    let path = match directory {
        Some(given) => std::path::PathBuf::from(given),
        None => {
            return Err(fail(
                "give the directory holding the runs, e.g. fathom-cli bench summary \
                 <game-dir>/benchmarks/2026-09-07"
                    .to_owned(),
            ))
        }
    };

    let runs = launcher_core::bench::read_all(&path).map_err(|error| fail(error.to_string()))?;
    let written =
        launcher_core::bench::write_summary(&path).map_err(|error| fail(error.to_string()))?;

    println!("Read {} run(s) from {}", runs.len(), path.display());
    for run in &runs {
        println!(
            "  {:<20} {:>8} frames  {:>7.2} ms mean",
            run.configuration, run.frames.sample_count, run.frames.mean_frame_time_millis
        );
    }
    println!("\nWrote {}", written.display());
    Ok(())
}

/// The six configurations, mirroring `core/bench/Configuration.java`.
///
/// Repeated rather than shared because the two live in different languages on
/// either side of a file format. `docs/performance/benchmark-harness.md` is the
/// one place that has to be kept in step with both, and a test would not help:
/// the risk is the prose drifting, not the ids.
struct BenchConfiguration {
    id: &'static str,
    name: &'static str,
    purpose: &'static str,
    setup: &'static str,
}

const CONFIGURATIONS: &[BenchConfiguration] = &[
    BenchConfiguration {
        id: "a-vanilla",
        name: "Vanilla 1.8.9",
        purpose: "The floor. Everything else is measured against this.",
        setup: "Loader Vanilla, empty mods directory, no Fathom jar anywhere. Needs an \
                external frame-time capture.",
    },
    BenchConfiguration {
        id: "b-loader",
        name: "Legacy Fabric only",
        purpose: "What the loader costs. The baseline for every Fathom configuration.",
        setup: "Loader Legacy Fabric, empty mods directory, no Fabric API. Needs an \
                external frame-time capture.",
    },
    BenchConfiguration {
        id: "c-core",
        name: "Fathom Core",
        purpose: "What the module framework, HUD loop and frame hook cost.",
        setup: "Fathom's jar in mods. Performance preset Vanilla. Default modules only. \
                Hub closed.",
    },
    BenchConfiguration {
        id: "d-performance-off",
        name: "Fathom, everything optional off",
        purpose: "The overhead number. Everything Fathom adds, nothing switched on.",
        setup: "As C, then every module switched off as well. Hub closed.",
    },
    BenchConfiguration {
        id: "e-balanced",
        name: "Fathom Balanced",
        purpose: "The default a user gets.",
        setup: "As C, with the Balanced preset applied.",
    },
    BenchConfiguration {
        id: "f-competitive",
        name: "Fathom Competitive",
        purpose: "Everything Fathom will turn on for someone who asks for frames.",
        setup: "As C, with the Competitive preset applied.",
    },
];

struct BenchScenario {
    id: &'static str,
    name: &'static str,
    description: &'static str,
    setup: &'static str,
    seconds: u32,
}

const SCENARIOS: &[BenchScenario] = &[
    BenchScenario {
        id: "cpu-ceiling",
        name: "CPU ceiling",
        description: "A flat wall filling the view. Frame time here is CPU work, not drawing.",
        setup: "Superflat, seed 0, creative, /gamerule doDaylightCycle false, /time set noon. \
                /tp @s 0 5 0 90 0 facing the prepared stone wall. Render distance 8. Do not move.",
        seconds: 60,
    },
    BenchScenario {
        id: "singleplayer-still",
        name: "Ordinary singleplayer, still",
        description: "A normal outdoor scene from a fixed viewpoint.",
        setup: "The prepared benchmark world. /tp @s -170 71 223 -90 0, /time set 6000, \
                doDaylightCycle and doMobSpawning off. Wait for chunk loading to settle.",
        seconds: 120,
    },
    BenchScenario {
        id: "chunk-movement",
        name: "Chunk traversal",
        description: "Repeatable movement through terrain, so chunk build cost dominates.",
        setup: "The prepared world. Walk the recorded path between two waypoints at a \
                constant speed, or ride a minecart along the prepared track.",
        seconds: 120,
    },
    BenchScenario {
        id: "entity-lobby",
        name: "Entity-heavy scene",
        description: "Hundreds of armour stands, half of them behind geometry.",
        setup: "Flat world. Run the spawn script, then hold a fixed camera at the recorded \
                coordinates.",
        seconds: 90,
    },
    BenchScenario {
        id: "text-heavy",
        name: "HUD-heavy scene",
        description: "Scoreboard, tab list, nametags and the Fathom HUD together.",
        setup: "Flat world. Run the scoreboard script, spawn the named entities, enable \
                every Fathom HUD widget, and hold a fixed camera.",
        seconds: 90,
    },
    BenchScenario {
        id: "particles",
        name: "Particle-heavy scene",
        description: "A sustained particle load from a fixed viewpoint.",
        setup: "Flat world. Place the recorded arrangement of campfires and potion \
                splashes, particles set to All, hold a fixed camera.",
        seconds: 60,
    },
];

fn self_check() -> Result<(), launcher_core::ServiceError> {
    use launcher_core::update::keys;

    let anchor = keys::root_keys()
        .map_err(|e| launcher_core::ServiceError::NotImplemented(leak(e.to_string())))?;
    println!(
        "{} {} self-check ok ({} root key(s) compiled in, update channel {})",
        launcher_core::PRODUCT_NAME,
        launcher_core::PRODUCT_VERSION,
        anchor.len(),
        if keys::update_channel_is_configured() {
            "configured"
        } else {
            "not configured"
        }
    );
    Ok(())
}

/// `recover` — the recovery flows, reachable without the desktop application.
///
/// Every one of these calls the same core service the interface calls. A
/// recovery path that only exists behind a button is a recovery path nobody has
/// ever run.
fn recover_command(args: &[String]) -> Result<(), launcher_core::ServiceError> {
    use launcher_core::ops::Operations;
    use launcher_core::recovery::{java, RecoveryService};

    let paths = launcher_core::paths::DataPaths::discover()?;
    paths.ensure()?;
    let service = RecoveryService::new(paths.clone(), Operations::new());

    match args.first().map(String::as_str).unwrap_or("scan") {
        "scan" => {
            let report = service
                .run_startup_scan(launcher_core::PRODUCT_VERSION)
                .map_err(problem_to_service_error)?;
            println!("Startup scan finished in {} ms.", report.elapsed_ms);
            if report.is_clean() {
                println!("Nothing to recover.");
                return Ok(());
            }
            let handled = report.cleaned_up();
            if !handled.is_empty() {
                println!();
                println!("Dealt with automatically:");
                for finding in handled {
                    println!("  - {}", finding.message());
                }
            }
            let attention = report.needs_attention();
            if !attention.is_empty() {
                println!();
                println!("Needs a decision:");
                for finding in attention {
                    println!("  - {}", finding.message());
                }
            }
            Ok(())
        }
        "cache" => {
            // Always shows what it would remove before removing anything.
            // "Clear cache" is the operation a user is most likely to click
            // without reading.
            let preview = service.preview_cache().map_err(problem_to_service_error)?;
            if preview.is_empty() {
                println!("There is nothing to clear.");
                return Ok(());
            }
            println!("{}", preview.message());
            for (kind, count, bytes) in preview.by_kind() {
                println!(
                    "  {:<28} {count} file(s), {}",
                    kind.label(),
                    launcher_core::services::human_bytes(bytes)
                );
            }

            if args.get(1).map(String::as_str) != Some("--yes") {
                println!();
                println!("Nothing was removed. Re-run with --yes to clear.");
                println!("Worlds, screenshots, resource packs, profiles, accounts and");
                println!("user-supplied mods are never touched; see docs/reliability.md.");
                return Ok(());
            }

            let report = service.clear_cache().map_err(problem_to_service_error)?;
            println!();
            println!("{}", report.message());
            Ok(())
        }
        "java" => {
            let Some(major) = args.get(1).and_then(|v| v.parse::<u32>().ok()) else {
                return Err(launcher_core::ServiceError::NotImplemented(
                    "usage: recover java <major-version> [--repair]",
                ));
            };
            let dir = java::managed_runtime_dir(&paths, major);
            let verdict = service
                .validate_java(&dir, major, false)
                .map_err(problem_to_service_error)?;

            println!("Managed Java {major}");
            println!("  path        {}", dir.display());
            println!("  ownership   {:?}", verdict.ownership);
            println!("  usable      {}", verdict.is_usable());
            for defect in &verdict.defects {
                println!("  defect      {}", defect.describe());
            }
            if let Some(problem) = verdict.problem() {
                println!();
                println!("{}", problem.report());
            }

            if args.iter().any(|a| a == "--repair") {
                let outcome = service
                    .repair_java(&dir, major, false)
                    .map_err(problem_to_service_error)?;
                println!();
                println!("{}", outcome.message());
            }
            Ok(())
        }
        "trash" => {
            let entries = service.trashed_profiles();
            if entries.is_empty() {
                println!("The recovery area is empty.");
                return Ok(());
            }
            println!("Deleted profiles, newest first:");
            for path in entries {
                println!("  {}", path.display());
            }
            println!();
            println!("Restore with: fathom-cli profile restore <path> <id>");
            Ok(())
        }
        other => Err(launcher_core::ServiceError::NotImplemented(leak(format!(
            "unknown recover subcommand {other:?}; expected scan, cache, java or trash"
        )))),
    }
}

/// `diagnostics` — build the report, print it or save it.
///
/// **Never uploaded.** It is written to a file the user names, or to standard
/// output, and that is the whole of its distribution.
fn diagnostics_command(args: &[String]) -> Result<(), launcher_core::ServiceError> {
    use launcher_core::ops::Operations;
    use launcher_core::recovery::diagnostics::{audit, Builder, Environment, Normalizer, Report};
    use launcher_core::recovery::RecoveryService;

    let mut core = open()?;
    // Populate the Java list: it is empty until something asks, and "which Java
    // did it find" is one of the first questions a diagnostic has to answer.
    core.refresh_java();
    let paths = core.paths().clone();
    let service = RecoveryService::new(paths.clone(), Operations::new());
    let env = Environment::detect();

    let mut builder = Builder::new(Normalizer::new(&paths))
        .section("Launcher")
        .entry("Version", env.launcher_version.clone())
        .entry("Build", env.build_profile)
        .entry("OS", env.os.clone())
        .entry("OS version", env.os_version.clone())
        .entry("Architecture", env.arch.clone())
        .path_entry("Data root", paths.root())
        .entry(
            "Signed in",
            if core.identity().is_some() {
                "yes"
            } else {
                "no"
            },
        )
        .entry("Credential store", core.credential_store_description());

    // Java. Version and vendor, never a path outside the roots we normalise.
    builder = builder.section("Java");
    for install in core.java_installations() {
        builder = builder.entry(
            &format!("Java {}", install.major),
            format!(
                "{} ({})",
                install.version_string,
                install
                    .vendor
                    .clone()
                    .unwrap_or_else(|| "unknown".to_owned())
            ),
        );
    }

    // Profiles, with their validation state.
    builder = builder.section("Profiles");
    for profile in core.profiles() {
        let report = service
            .validate_profile(&profile, None)
            .map_err(problem_to_service_error)?;
        builder = builder.entry(
            &profile.id.to_string(),
            format!(
                "{} | {} | loader {:?} {} | heap {} MB | {} mod(s) | {}",
                profile.display_name,
                profile.version_id,
                profile.loader.kind,
                profile.loader.version.clone().unwrap_or_default(),
                profile.memory.heap_mb,
                profile.mods.len(),
                report.health.label(),
            ),
        );
        for defect in &report.defects {
            builder = builder.line(format!("  {}: {}", profile.id, defect.message()));
        }
    }

    // Mods, by id and version. No file paths: the id is what identifies a mod.
    builder = builder.section("Mods");
    for profile in core.profiles() {
        for entry in profile.mods.mods.iter() {
            builder = builder.entry(
                &format!("{}/{}", profile.id, entry.id),
                format!(
                    "{} sha1={}",
                    if entry.enabled { "enabled" } else { "disabled" },
                    entry.sha1.get(..12).unwrap_or(&entry.sha1)
                ),
            );
        }
    }

    // The optimization module, as a versioned component.
    builder = builder.section("Components");
    if let Some(module) = launcher_core::mods::ModId::new("fathom-optimizations")
        .ok()
        .and_then(|id| core.mod_definition(&id))
    {
        builder = builder.entry("Optimization module", module.version.to_string());
    }
    let update_state = launcher_core::update::trust::TrustState::load(
        &service.paths().root().join("update/trust.json"),
    );
    builder = builder.entry(
        "Update metadata version",
        update_state.release_version.to_string(),
    );
    builder = builder.entry(
        "Update channel",
        if launcher_core::update::keys::update_channel_is_configured() {
            "configured"
        } else {
            "not configured in this build"
        },
    );

    // In-progress operations, which is often the answer to "why is it stuck".
    builder = builder.section("Operations");
    let running = service.operations().in_progress();
    if running.is_empty() {
        builder = builder.line("nothing in progress");
    }
    for (scope, activity, held) in running {
        builder = builder.line(format!("{scope} held by {activity} for {held:?}"));
    }

    // The tail of our own log, normalised and scrubbed by the builder.
    builder = builder.section("Recent launcher log");
    let log = launcher_core::logging::log_file_path()
        .map(std::path::Path::to_path_buf)
        .unwrap_or_else(|| paths.launcher_logs_dir().join("launcher.log"));
    let tail = launcher_core::recovery::diagnostics::log_tail(&log, 80);
    if tail.is_empty() {
        builder = builder.line("(no launcher log on this machine yet)");
    }
    for line in tail {
        builder = builder.line(line);
    }

    let text = builder.build().to_text();

    // A last look before it goes anywhere. Construction is the defence; this is
    // the second pair of eyes.
    let leaked = audit(&text);
    if !leaked.is_empty() {
        return Err(launcher_core::ServiceError::NotImplemented(leak(format!(
            "refusing to emit a diagnostic report: it still contains {leaked:?}"
        ))));
    }

    match args.first().map(String::as_str) {
        Some("--out") => {
            let Some(target) = args.get(1) else {
                return Err(launcher_core::ServiceError::NotImplemented(
                    "usage: diagnostics --out <file>",
                ));
            };
            std::fs::write(target, &text).map_err(|e| {
                launcher_core::ServiceError::NotImplemented(leak(format!(
                    "could not write {target}: {e}"
                )))
            })?;
            println!("Wrote {} ({} bytes).", target, text.len());
            println!("Nothing was uploaded. Read it before sharing it.");
            Ok(())
        }
        _ => {
            print!("{text}");
            println!();
            println!(
                "(Save with: fathom-cli diagnostics --out {})",
                Report::suggested_file_name()
            );
            Ok(())
        }
    }
}

/// Carry a `Problem` through the CLI's error type without losing its message.
fn problem_to_service_error(
    problem: launcher_core::problem::Problem,
) -> launcher_core::ServiceError {
    launcher_core::ServiceError::NotImplemented(leak(problem.report()))
}

/// `update` — check, recover, and restore.
///
/// There is no `install` here on purpose. Downloading and replacing the
/// launcher wants a supervising process that outlives the swap, which is the
/// running launcher rather than a subcommand that exits; see
/// `update::install`. What the CLI is for is inspecting the channel and
/// operating the recovery paths.
fn update_command(args: &[String]) -> Result<(), launcher_core::ServiceError> {
    use launcher_core::update::manifest::{ComponentId, Platform};
    use launcher_core::update::UpdateService;

    let paths = launcher_core::paths::DataPaths::discover()?;
    let service = UpdateService::new(paths.root());

    match args.first().map(String::as_str).unwrap_or("status") {
        "status" => {
            println!("Update channel");
            println!("  platform          {}", Platform::current());
            println!("  root keys         {}", keys_line());
            println!(
                "  channel           {}",
                if service.is_configured() {
                    "configured"
                } else {
                    "NOT CONFIGURED - updates are disabled in this build"
                }
            );
            println!("  metadata          {}", launcher_core::update::ROOT_PATH);
            println!(
                "                    {}",
                launcher_core::update::RELEASE_PATH
            );
            println!(
                "  state             {}",
                service.paths().trust_state().display()
            );
            println!();

            let state =
                launcher_core::update::trust::TrustState::load(&service.paths().trust_state());
            println!("Rollback floors (nothing at or below these is accepted)");
            println!("  root version      {}", state.root_version);
            println!("  release version   {}", state.release_version);
            println!();
            println!("Installed");
            for component in ComponentId::ALL {
                println!(
                    "  {:<20} {}",
                    component.as_str(),
                    state.installed_version(component).unwrap_or("(unknown)")
                );
            }
            println!();
            println!("Recovery");
            for component in ComponentId::ALL {
                println!(
                    "  {:<20} backup {}",
                    component.as_str(),
                    if service.installer().has_backup(component) {
                        "present"
                    } else {
                        "none"
                    }
                );
            }

            if !service.is_configured() {
                println!();
                println!(
                    "This build has no update signing key compiled in, so it refuses to \
                     fetch a manifest it could not verify. See \
                     crates/launcher-core/src/update/keys.rs."
                );
            }
            Ok(())
        }
        "check" => {
            // The whole point of the refusal below: a launcher that cannot
            // verify must not fetch. It is reported as an error rather than as
            // "up to date", because those are different facts.
            let http = launcher_core::network::http::HttpClient::new(
                launcher_core::network::policy::NetworkPolicy::default(),
                launcher_core::network::journal::NetworkJournal::default(),
                &launcher_core::user_agent(),
            );
            let state =
                launcher_core::update::trust::TrustState::load(&service.paths().trust_state());
            let outcome = service.check(&http, now_seconds(), &|c| {
                state
                    .installed_version(c)
                    .unwrap_or(launcher_core::PRODUCT_VERSION)
                    .to_owned()
            })?;

            println!("Release metadata v{}", outcome.metadata_version);
            for update in &outcome.available {
                println!(
                    "  UPDATE  {:<20} {} -> {} ({} bytes, {})",
                    update.component.as_str(),
                    update.from_version,
                    update.release.version,
                    update.release.size,
                    update.release.sha256
                );
            }
            for component in &outcome.up_to_date {
                println!("  current {}", component.as_str());
            }
            for (component, why) in &outcome.refused {
                println!("  REFUSED {:<20} {why}", component.as_str());
            }
            if !outcome.has_updates() {
                println!("\\nNothing to install.");
            }
            Ok(())
        }
        "recover" => {
            let outcome = service.recover(launcher_core::PRODUCT_VERSION);
            println!(
                "{}",
                outcome
                    .message()
                    .unwrap_or_else(|| "No interrupted update to recover.".to_owned())
            );
            let swept = service.installer().sweep_staging();
            if swept > 0 {
                println!("Discarded {swept} unverified file(s) from staging.");
            }
            Ok(())
        }
        "restore" => {
            let Some(name) = args.get(1) else {
                return Err(launcher_core::ServiceError::NotImplemented(
                    "usage: update restore <component> <target-path>",
                ));
            };
            let Some(component) = ComponentId::parse(name) else {
                return Err(launcher_core::ServiceError::NotImplemented(leak(format!(
                    "unknown component {name:?}; expected launcher, optimization-module \
                     or metadata"
                ))));
            };
            let Some(target) = args.get(2) else {
                return Err(launcher_core::ServiceError::NotImplemented(
                    "usage: update restore <component> <target-path>",
                ));
            };
            service
                .installer()
                .restore(component, std::path::Path::new(target))?;
            println!("Restored the previous {component} to {target}.");
            Ok(())
        }
        other => Err(launcher_core::ServiceError::NotImplemented(leak(format!(
            "unknown update subcommand {other:?}; expected status, check, recover or restore"
        )))),
    }
}

fn keys_line() -> String {
    match launcher_core::update::keys::root_keys() {
        Ok(keys) if keys.is_empty() => "none compiled in".to_owned(),
        Ok(keys) => keys
            .iter()
            .map(|k| k.id().to_string())
            .collect::<Vec<_>>()
            .join(", "),
        Err(e) => format!("UNUSABLE: {e}"),
    }
}

fn now_seconds() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_secs()).unwrap_or(i64::MAX))
}

fn usage() {
    println!(
        "\
{name} {version}

USAGE:
    fathom-cli <command>

COMMANDS:
    doctor        Paths, credential store, Java, and network posture (default)
    profiles      List profiles and where each one stores its data
    allowlist     Every host this build can contact, and why
    check-args    Validate JVM arguments without launching anything

    prepare [ver] Resolve every artifact 1.8.9 needs, without downloading
    install [ver] Download and verify a complete installation
    verify  [ver] Hash every installed artifact and report the state
    repair  [ver] Re-acquire whatever is missing or corrupt

    plan    [prof] Build the launch command and print it, redacted
    launch  [prof] Build it and run Minecraft, streaming the exit outcome

    profile <subcommand>
        list                      Every profile, with its health
        show    <id>              One profile in full
        create  <name>            A new profile
        clone   <id> <name>       Copy a profile, worlds included
        rename  <id> <name>       Change the display name (the id does not move)
        delete  <id>              Move to the recovery area; nothing is destroyed
        trash                     What is in the recovery area
        restore <path> <id>       Put a recovery-area instance back
        purge   <path>            Permanently remove one recovery-area entry
        validate <id>             What is wrong with it
        repair  <id>              Fix what can be fixed without a decision
        export  <id>              Print portable configuration JSON
        import  <file>            Create a profile from an exported document

    mods <subcommand> [profile]
        list                      What this profile wants, and what is in the folder
        add     <file>            Copy a jar into the store and record it
        enable  <id>              Turn a mod on
        disable <id>              Turn a mod off
        remove  <id>              Forget a mod (the file stays in the store)
        resolve                   Dependencies, conflicts, and what will load
        apply                     Rebuild the mods folder from the configuration
        catalog                   What Fathom knows, and what it will not fetch

    loaders                       What each loader can do in this build

    recover <subcommand>
        scan                      Run the startup recovery check now
        cache [--yes]             Show what clearing the cache removes, then do it
        java <major> [--repair]   Validate, and optionally repair, managed Java
        trash                     Deleted profiles that can be restored

    diagnostics [--out <file>]    A privacy-safe report. Never uploaded.

    update <subcommand>
        status                    The trust anchor, rollback floors, and backups
        check                     Fetch and verify signed metadata; report only
        recover                   Finish or undo an interrupted replacement
        restore <component> <path>  Put the previous copy back

    --self-check                  Prove this build runs. Used by the updater
                                  before it releases the previous version.

    help          This text

`doctor`, `profiles`, `allowlist` and `check-args` make no network requests.
`prepare`, `install` and `repair` contact Mojang's own distribution hosts.
`update check` contacts the release host with a fixed path and no identifier;
`update status`, `recover` and `restore` are entirely local.",
        name = launcher_core::PRODUCT_NAME,
        version = launcher_core::PRODUCT_VERSION
    );
}

fn open() -> Result<Launcher, launcher_core::ServiceError> {
    Launcher::discover()
}

fn doctor() -> Result<(), launcher_core::ServiceError> {
    let mut core = open()?;

    println!(
        "{} {}",
        launcher_core::PRODUCT_NAME,
        launcher_core::PRODUCT_VERSION
    );
    println!("user agent   {}", launcher_core::user_agent());
    println!("data root    {}", core.paths().root().display());
    println!("shared cache {}", core.paths().shared_cache().display());
    println!();

    println!("credentials");
    println!("  store       {}", core.credential_store_description());
    println!("  persistent  {}", core.credential_store_is_persistent());
    match core.identity() {
        None => println!("  signed in   no"),
        Some(identity) => {
            println!("  signed in   yes ({})", identity.profile.name);
            println!("  identity    {}", identity.kind.label());
        }
    }
    if let Some(warning) = core.identity_warning() {
        println!("  WARNING     {warning}");
    }
    println!(
        "  sign-in     {}",
        if core.sign_in_available() {
            "available"
        } else {
            "unavailable (no FATHOM_AZURE_CLIENT_ID)"
        }
    );
    println!("  providers   {}", core.auth_providers().len());
    for p in core.auth_providers() {
        println!(
            "    {:<14} {:<12} {}",
            p.id(),
            if p.is_production() {
                "production"
            } else {
                "NOT REAL"
            },
            p.unavailable_reason()
                .unwrap_or_else(|| "available".to_owned())
        );
    }
    println!();

    let policy = core.network_policy();
    println!("network");
    println!("  online              {}", policy.online);
    println!("  update check        {}", policy.allow_update_check);
    println!("  managed java        {}", policy.allow_managed_java);
    println!("  mod repositories    {}", policy.allow_mod_repositories);
    println!("  requests so far     {}", core.network_summary().total);
    println!();

    println!("java");
    let installs = core.refresh_java().to_vec();
    if installs.is_empty() {
        println!("  none found on PATH or in JAVA_HOME");
    } else {
        for install in &installs {
            let suitable = if install.major == 8 { "<- 1.8.9" } else { "" };
            println!(
                "  Java {:<3} {:<50} {} {}",
                install.major,
                install.path.display(),
                install.version_string,
                suitable
            );
        }
    }
    println!();

    println!("profiles");
    let service = core.install_service();
    for profile in core.profiles() {
        // `doctor` reports the truth about a machine, so it checks the files
        // rather than passing `None` and calling them unknown. Existence only:
        // hashing 139 MB per profile would turn a diagnostic into a chore.
        let cached = core.cached_version(&profile.version_id);
        let resolved = cached
            .as_ref()
            .and_then(|v| service.resolve_cached(v, Feature::ProfileInstall).ok());
        let snapshot = resolved.as_ref().map(|r| {
            let natives = core.profile_dirs(&profile.id).natives_dir();
            InstallSnapshot::from_report(&service.quick_check(r, &natives), r)
        });

        let readiness = core.readiness(&profile, cached.as_ref(), snapshot.as_ref());
        let state = if readiness.can_play() {
            "ready".to_owned()
        } else {
            format!("{} blocker(s)", readiness.blockers.len())
        };
        println!("  {:<24} {:<12} {}", profile.id, profile.version_id, state);
        for blocker in &readiness.blockers {
            println!("      - {}", blocker.message());
        }
    }

    Ok(())
}

fn profiles() -> Result<(), launcher_core::ServiceError> {
    let core = open()?;
    for profile in core.profiles() {
        let dirs = core.profile_dirs(&profile.id);
        let mods = core.mods_for(&profile.id);
        println!("{} ({})", profile.display_name, profile.id);
        println!("  version   {}", profile.version_id);
        println!("  loader    {}", profile.loader_kind().label());
        println!("  heap      {} MB", profile.memory.heap_mb);
        println!("  game dir  {}", dirs.minecraft_dir().display());
        println!("  mods      {} in the mods folder", mods.len());
        for item in &mods {
            // `managed` is whether the launcher put it there. Whether it is
            // *enabled* is a fact about the profile, not about the directory:
            // the directory only ever contains what should be loaded.
            println!(
                "      {:<40} {:>10}  sha1 {}  {}",
                item.file_name,
                human_bytes(item.size),
                item.short_hash(),
                if item.managed { "managed" } else { "yours" }
            );
        }
        println!();
    }
    Ok(())
}

fn allowlist() -> Result<(), launcher_core::ServiceError> {
    let core = open()?;
    println!("Hosts this build is capable of contacting.\n");
    for (destination, permitted) in core.network_allowlist() {
        let consent = match destination.consent() {
            Consent::CoreFlow => "core",
            Consent::OptOut => "on-unless-disabled",
            Consent::OptIn => "opt-in",
            Consent::ExplicitPerAction => "per-action",
        };
        println!(
            "{:<40} {:<20} {}",
            destination.host(),
            consent,
            if permitted {
                "allowed now"
            } else {
                "blocked now"
            }
        );
        println!("    {}", destination.rationale());
    }
    println!("\nThere is no other host. Destinations are an enumeration in the source,");
    println!("not strings assembled at runtime.");
    Ok(())
}

fn check_args(args: &[String]) -> Result<(), launcher_core::ServiceError> {
    if args.is_empty() {
        println!("usage: fathom-cli check-args <heap-mb> [jvm args...]");
        return Ok(());
    }

    if let Ok(heap) = args[0].parse::<u32>() {
        let verdict = judge_heap(heap, None);
        let note = match verdict {
            HeapVerdict::Ok => "fine",
            HeapVerdict::TooSmall => "too small for 1.8.9 to start reliably",
            HeapVerdict::ExceedsPhysical => "larger than this machine can back",
            HeapVerdict::CounterProductive => {
                "past the point where more heap means longer pauses, not fewer"
            }
        };
        println!("heap {heap} MB: {note}");
    }

    let rest: Vec<String> = args.get(1..).unwrap_or_default().to_vec();
    if rest.is_empty() {
        return Ok(());
    }
    let report = validate_args(&rest);
    for accepted in &report.accepted {
        println!("accept  {accepted}");
    }
    for (flag, why) in &report.rejected {
        println!("REFUSE  {flag}\n        {why}");
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Installation
// ---------------------------------------------------------------------------

/// Only 1.8.9 is supported; the argument exists so the commands read naturally
/// and so an unsupported version produces a clear refusal rather than silence.
fn version_arg(arg: Option<&str>) -> String {
    arg.unwrap_or(launcher_core::install::SUPPORTED_VERSION)
        .to_owned()
}

/// Resolve a version and print what an installation would involve.
fn prepare(version: Option<&str>) -> Result<(), launcher_core::ServiceError> {
    let core = open()?;
    let version_id = version_arg(version);
    let service = core.install_service();
    let cancel = Cancel::new();

    println!("Resolving {version_id}...");
    let resolved = service
        .prepare(&version_id, Feature::ProfileInstall, &cancel)
        .map_err(|e| launcher_core::ServiceError::NotImplemented(leak(e.to_string())))?;

    describe(&resolved);
    println!();
    println!("requests made: {}", core.network_summary().total);
    Ok(())
}

fn describe(resolved: &launcher_core::install::ResolvedInstall) {
    println!("version        {}", resolved.version_id);
    println!("main class     {}", resolved.main_class);
    println!("required java  {}", resolved.required_java_major);
    println!(
        "arguments      {}",
        if resolved.argument_template.is_some() {
            "legacy minecraftArguments string"
        } else {
            "none"
        }
    );
    println!("client jar     1");
    println!("libraries      {}", resolved.libraries.len());
    println!("natives        {}", resolved.natives.len());
    println!(
        "asset index    {}",
        resolved.asset_index_id.as_deref().unwrap_or("none")
    );
    println!("asset objects  {}", resolved.assets.len());
    println!(
        "logging config {}",
        resolved
            .logging
            .as_ref()
            .map_or("none".to_owned(), |l| l.download.name.clone())
    );
    println!(
        "total          {} artifacts, {}",
        resolved.all_downloads().len(),
        human_bytes(resolved.declared_bytes())
    );
}

fn install(version: Option<&str>) -> Result<(), launcher_core::ServiceError> {
    let core = open()?;
    let version_id = version_arg(version);
    let service = core.install_service();
    let cancel = Cancel::new();
    let natives = core.scratch_natives_dir(&version_id);

    println!("Resolving {version_id}...");
    let resolved = service
        .prepare(&version_id, Feature::ProfileInstall, &cancel)
        .map_err(|e| launcher_core::ServiceError::NotImplemented(leak(e.to_string())))?;
    describe(&resolved);
    println!();

    let mut last_line = 0usize;
    let bytes = service
        .install(&resolved, &natives, &cancel, &mut |stage| {
            if let launcher_core::install::InstallStage::Downloading(p) = stage {
                // One line per 25 files keeps the output readable for 738 assets.
                if p.completed >= last_line + 25 || p.completed == p.total {
                    last_line = p.completed;
                    println!(
                        "  {}/{}  {}",
                        p.completed,
                        p.total,
                        human_bytes(p.bytes_transferred)
                    );
                }
            }
        })
        .map_err(|e| launcher_core::ServiceError::NotImplemented(leak(e.to_string())))?;

    println!();
    println!("transferred {}", human_bytes(bytes));

    let report = service.verify(&resolved, &natives);
    print_report(&report);
    Ok(())
}

fn verify(version: Option<&str>) -> Result<(), launcher_core::ServiceError> {
    let core = open()?;
    let version_id = version_arg(version);
    let service = core.install_service();
    let natives = core.scratch_natives_dir(&version_id);

    let Some(detail) = service.metadata().cached(&version_id) else {
        println!("No cached metadata for {version_id}. Run `prepare` or `install` first.");
        return Ok(());
    };
    let resolved = service
        .resolve_cached(&detail, Feature::ProfileInstall)
        .map_err(|e| launcher_core::ServiceError::NotImplemented(leak(e.to_string())))?;

    println!("Hashing {} artifacts...", resolved.all_downloads().len());
    let report = service.verify(&resolved, &natives);
    print_report(&report);
    Ok(())
}

fn repair(version: Option<&str>) -> Result<(), launcher_core::ServiceError> {
    let core = open()?;
    let version_id = version_arg(version);
    let service = core.install_service();
    let cancel = Cancel::new();
    let natives = core.scratch_natives_dir(&version_id);

    let resolved = service
        .prepare(&version_id, Feature::ProfileInstall, &cancel)
        .map_err(|e| launcher_core::ServiceError::NotImplemented(leak(e.to_string())))?;

    println!("Verifying...");
    let outcome = service
        .repair(&resolved, &natives, &cancel, &mut |_| {})
        .map_err(|e| launcher_core::ServiceError::NotImplemented(leak(e.to_string())))?;

    println!("before  {}", outcome.before.label());
    println!("after   {}", outcome.after.label());
    println!("{}", outcome.summary());
    println!("transferred {}", human_bytes(outcome.bytes));
    Ok(())
}

fn print_report(report: &launcher_core::install::VerificationReport) {
    println!();
    println!("state          {}", report.state.label());
    println!("verified       {}/{}", report.verified, report.total);
    println!(
        "natives        {}",
        if report.natives_extracted {
            "extracted"
        } else {
            "NOT extracted"
        }
    );
    if !report.defects.is_empty() {
        println!("defects        {}", report.defects.len());
        for defect in report.defects.iter().take(10) {
            println!(
                "  {:<12} {}",
                match defect.kind {
                    launcher_core::install::DefectKind::Missing => "missing",
                    launcher_core::install::DefectKind::HashMismatch => "bad hash",
                },
                defect.name
            );
        }
        if report.defects.len() > 10 {
            println!("  ... and {} more", report.defects.len() - 10);
        }
    }
    println!();
    println!("{}", report.state.explanation());
}

/// `ServiceError::NotImplemented` wants a `&'static str`; these messages are
/// produced once, at the end of a CLI command, so leaking them is harmless and
/// avoids threading another error type through for the sake of a diagnostic.
fn leak(s: String) -> &'static str {
    Box::leak(s.into_boxed_str())
}

// ---------------------------------------------------------------------------
// Launching
// ---------------------------------------------------------------------------

/// Resolve the profile a launch command should act on.
fn pick_profile(
    core: &Launcher,
    wanted: Option<&str>,
) -> Result<launcher_core::profiles::Profile, launcher_core::ServiceError> {
    let profiles = core.profiles();
    match wanted {
        Some(id) => profiles
            .iter()
            .find(|p| p.id.as_str() == id)
            .cloned()
            .ok_or(launcher_core::ServiceError::NotImplemented("that profile")),
        None => profiles
            .first()
            .cloned()
            .ok_or(launcher_core::ServiceError::NotImplemented("any profile")),
    }
}

/// Sign in with the fake development identity if this build has one and the
/// operator has opted in.
///
/// Deliberately quiet about *how* it is enabled: the requirement is in
/// `development.rs`, and duplicating it here is how a second, looser path gets
/// created by accident.
#[cfg(feature = "dev-auth")]
fn ensure_identity(core: &mut Launcher) -> Result<(), launcher_core::ServiceError> {
    if core.identity().is_some() {
        return Ok(());
    }
    if core.development_auth_available() {
        core.sign_in_development()?;
        println!("signed in with the DEVELOPMENT identity (not a Minecraft account)");
    }
    Ok(())
}

#[cfg(not(feature = "dev-auth"))]
fn ensure_identity(_core: &mut Launcher) -> Result<(), launcher_core::ServiceError> {
    Ok(())
}

/// Everything a launch needs, short of starting it.
fn prepared(
    version: Option<&str>,
) -> Result<
    (
        Launcher,
        launcher_core::profiles::Profile,
        launcher_core::minecraft_runtime::VersionDetail,
    ),
    launcher_core::ServiceError,
> {
    let mut core = open()?;
    core.refresh_java();
    ensure_identity(&mut core)?;

    let profile = pick_profile(&core, version)?;
    let detail = core.cached_version(&profile.version_id).ok_or(
        launcher_core::ServiceError::NotImplemented(
            "launching a version that has not been installed; run `install` first,",
        ),
    )?;
    Ok((core, profile, detail))
}

/// Walk the whole pipeline and report on every stage.
///
/// Deliberately does NOT go through `prepared`, which calls `ensure_identity`
/// and would fail at the first step on a machine with no approved client id.
/// The point of this command is to check everything that does not need one.
fn integration_command(args: &[String]) -> Result<(), launcher_core::ServiceError> {
    let offline = args.iter().any(|arg| arg == "--offline");
    let target = args.iter().find(|arg| !arg.starts_with("--")).map(String::as_str);

    let mut core = open()?;

    // WORKFLOW A -- core integration test mode.
    //
    // With the `dev-auth` feature compiled in AND the environment variable set,
    // sign in with the development identity so the launch-plan stage can be
    // validated for real: classpath order, main class, argument substitution
    // and the working directory. That identity owns nothing and cannot join an
    // online-mode server, so this proves the PLAN is correct without pretending
    // to be a Minecraft account.
    //
    // Without the feature, this does nothing and the stage reports blocked.
    ensure_identity(&mut core)?;

    let profile = pick_profile(&core, target)?;
    let report = integration::run(&mut core, &profile, offline);

    println!();
    println!("{}", report.summary());
    println!("requests made: {}", core.network_summary().total);

    if report.ok() {
        println!();
        println!("Everything that can be checked without an approved Microsoft");
        println!("application id passes. See `docs/research/auth-feasibility.md`.");
        Ok(())
    } else {
        Err(launcher_core::ServiceError::NotImplemented(
            "one or more integration stages failed",
        ))
    }
}

fn plan(profile_id: Option<&str>) -> Result<(), launcher_core::ServiceError> {
    let (core, profile, detail) = prepared(profile_id)?;
    let plan = core.build_launch_plan(&profile, &detail)?;
    print_plan(&plan);
    Ok(())
}

fn print_plan(plan: &launcher_core::minecraft_runtime::LaunchPlan) {
    println!("java           {}", plan.java().display());
    println!("java major     {}", plan.java_major());
    println!("main class     {}", plan.main_class());
    println!("working dir    {}", plan.working_dir().display());
    println!("profile dir    {}", plan.profile_dir().display());
    println!("natives dir    {}", plan.natives_dir().display());
    println!("log file       {}", plan.log_file().display());
    println!("classpath      {} entries", plan.classpath().len());
    println!("jvm args       {}", plan.jvm_args().join(" "));
    println!(
        "environment    {} set, {} removed ({})",
        plan.environment().len(),
        plan.removed_environment().len(),
        plan.removed_environment().join(", ")
    );
    println!(
        "secret args    {}",
        if plan.exposes_a_secret_argument() {
            "yes - the access token is on the command line (see docs/research/token-exposure.md)"
        } else {
            "no"
        }
    );
    println!();
    println!("command (redacted):");
    println!("{}", plan.redacted_command_line());
}

fn launch(profile_id: Option<&str>) -> Result<(), launcher_core::ServiceError> {
    let (core, profile, detail) = prepared(profile_id)?;

    println!("launching {} ({})...", profile.display_name, profile.id);
    let status = core.launch(&profile, &detail)?;
    println!("pid            {}", status.pid);
    println!("log            {}", status.log_file.display());
    println!();
    println!("waiting for the game to exit; close Minecraft to continue");

    // Poll rather than block, so the outcome can be reported as soon as the
    // process ends without holding a handle the registry also owns.
    loop {
        std::thread::sleep(std::time::Duration::from_millis(500));
        let Some(current) = core.game_status(&profile.id) else {
            break;
        };
        if !current.is_running() {
            println!();
            println!("exit code      {:?}", current.exit_code);
            println!("ran for        {:.1}s", current.elapsed().as_secs_f64());
            println!("outcome        {}", current.outcome.message());
            if current.outcome.suggests_repair() {
                println!("               try `fathom-cli repair`");
            }
            break;
        }
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// Profiles
// ---------------------------------------------------------------------------

fn profile_command(args: &[String]) -> Result<(), launcher_core::ServiceError> {
    let core = open()?;
    let sub = args.first().map(String::as_str).unwrap_or("list");
    let rest: Vec<&str> = args.iter().skip(1).map(String::as_str).collect();

    match (sub, rest.as_slice()) {
        ("list", _) => profile_list(&core),
        ("show", [id]) => profile_show(&core, id),
        ("create", name) if !name.is_empty() => profile_create(&core, &name.join(" ")),
        ("clone", [id, name @ ..]) if !name.is_empty() => profile_clone(&core, id, &name.join(" ")),
        ("rename", [id, name @ ..]) if !name.is_empty() => {
            profile_rename(&core, id, &name.join(" "))
        }
        ("delete", [id]) => profile_delete(&core, id),
        ("trash", _) => profile_trash(&core),
        ("restore", [path, id]) => profile_restore(&core, path, id),
        ("purge", [path]) => profile_purge(&core, path),
        ("validate", [id]) => profile_validate(&core, id),
        ("repair", [id]) => profile_repair(&core, id),
        ("export", [id]) => profile_export(&core, id),
        ("import", [file]) => profile_import(&core, file),
        _ => {
            println!("Unknown or incomplete profile subcommand. Run `fathom-cli help`.");
            Ok(())
        }
    }
}

/// Parse an id, reporting the validation failure rather than a generic error.
///
/// A profile id is untrusted input even on the command line, so this is the same
/// gate every other entry point uses.
fn parse_id(raw: &str) -> Result<launcher_core::profiles::ProfileId, launcher_core::ServiceError> {
    launcher_core::profiles::ProfileId::new(raw).map_err(Into::into)
}

fn profile_list(core: &Launcher) -> Result<(), launcher_core::ServiceError> {
    let detailed = core.profiles_detailed();
    if detailed.is_empty() {
        println!("No profiles yet. `fathom-cli profile create <name>` makes one.");
        return Ok(());
    }

    for (id, result) in detailed {
        match result {
            Ok(loaded) => {
                let report = core.validate_profile(&loaded.profile, None);
                println!(
                    "  {:<24} {:<28} {:<10} {}",
                    id,
                    loaded.profile.display_name,
                    loaded.profile.loader_kind().label(),
                    report.health.label()
                );
                if let Some(note) = loaded.outcome.message() {
                    println!("      note: {note}");
                }
                for defect in report.defects.iter().take(3) {
                    println!("      - {}", defect.message());
                }
            }
            // A broken profile is shown, not hidden. Silently omitting it is how
            // someone concludes their profile "disappeared".
            Err(e) => println!("  {id:<24} UNREADABLE: {e}"),
        }
    }
    Ok(())
}

fn profile_show(core: &Launcher, raw_id: &str) -> Result<(), launcher_core::ServiceError> {
    let id = parse_id(raw_id)?;
    let profile = core
        .profiles()
        .into_iter()
        .find(|p| p.id == id)
        .ok_or(launcher_core::ServiceError::NotImplemented("that profile"))?;
    let dirs = core.dirs_for(&profile);

    println!("id             {}", profile.id);
    println!("name           {}", profile.display_name);
    println!("version        {}", profile.version_id);
    println!(
        "loader         {}{}",
        profile.loader_kind().label(),
        profile
            .loader
            .version
            .as_ref()
            .map_or(String::new(), |v| format!(" {v}"))
    );
    println!("game directory {}", dirs.minecraft_dir().display());
    println!(
        "  owned by us  {}",
        if profile.game_directory.is_launcher_owned() {
            "yes - deleting the profile moves it to the recovery area"
        } else {
            "no - the launcher will never delete it"
        }
    );
    println!("java           {:?}", profile.java);
    println!("heap           {} MB", profile.memory.heap_mb);
    println!("jvm args       {}", profile.jvm.extra_args.join(" "));
    println!(
        "window         {}x{}{}",
        profile.display.resolution.width,
        profile.display.resolution.height,
        if profile.display.fullscreen {
            " (fullscreen)"
        } else {
            ""
        }
    );
    println!("mods           {}", profile.mods.len());
    println!(
        "optimizations  {} preset, explicit gc {}, huge methods {}",
        profile.optimizations.preset.label(),
        profile.optimizations.jvm.disable_explicit_gc,
        profile.optimizations.jvm.compile_huge_methods
    );
    println!("created        {}", profile.created_at);
    println!("updated        {}", profile.updated_at);
    Ok(())
}

fn profile_create(core: &Launcher, name: &str) -> Result<(), launcher_core::ServiceError> {
    let profile = core.create_profile(name)?;
    println!("created {} ({})", profile.display_name, profile.id);
    println!("  {}", core.dirs_for(&profile).root().display());
    Ok(())
}

fn profile_clone(
    core: &Launcher,
    raw_id: &str,
    name: &str,
) -> Result<(), launcher_core::ServiceError> {
    let copy = core.clone_profile(&parse_id(raw_id)?, name)?;
    println!("cloned to {} ({})", copy.display_name, copy.id);
    println!("  worlds, options, mods and configuration were copied");
    println!("  natives and logs were not: they are rebuilt per profile");
    Ok(())
}

fn profile_rename(
    core: &Launcher,
    raw_id: &str,
    name: &str,
) -> Result<(), launcher_core::ServiceError> {
    let profile = core.rename_profile(&parse_id(raw_id)?, name)?;
    println!("renamed to {}", profile.display_name);
    println!("  the id is still {}, so nothing moved on disk", profile.id);
    Ok(())
}

fn profile_delete(core: &Launcher, raw_id: &str) -> Result<(), launcher_core::ServiceError> {
    let receipt = core.delete_profile(&parse_id(raw_id)?)?;
    println!("{}", receipt.message());
    println!();
    println!("`fathom-cli profile trash` lists it; `restore` puts it back.");
    Ok(())
}

fn profile_trash(core: &Launcher) -> Result<(), launcher_core::ServiceError> {
    let entries = core.trashed_profiles();
    if entries.is_empty() {
        println!("The recovery area is empty.");
        return Ok(());
    }
    println!("recovery area (nothing here has been deleted):");
    for entry in entries {
        println!("  {}", entry.display());
    }
    Ok(())
}

fn profile_restore(
    core: &Launcher,
    path: &str,
    raw_id: &str,
) -> Result<(), launcher_core::ServiceError> {
    core.restore_profile(std::path::Path::new(path), &parse_id(raw_id)?)?;
    println!("restored to {raw_id}");
    Ok(())
}

fn profile_purge(core: &Launcher, path: &str) -> Result<(), launcher_core::ServiceError> {
    core.purge_trashed_profile(std::path::Path::new(path))?;
    println!("permanently removed {path}");
    Ok(())
}

fn profile_validate(core: &Launcher, raw_id: &str) -> Result<(), launcher_core::ServiceError> {
    let id = parse_id(raw_id)?;
    let profile = core
        .profiles()
        .into_iter()
        .find(|p| p.id == id)
        .ok_or(launcher_core::ServiceError::NotImplemented("that profile"))?;

    let report = core.validate_profile(&profile, None);
    println!("health   {}", report.health.label());
    if report.is_healthy() {
        println!("Nothing wrong.");
        return Ok(());
    }
    println!("defects  {}", report.defects.len());
    for defect in &report.defects {
        println!(
            "  [{}] {} {}",
            if defect.is_blocking() {
                "blocks"
            } else {
                "notice"
            },
            if defect.is_auto_repairable() {
                "(repairable)"
            } else {
                "             "
            },
            defect.message()
        );
    }
    Ok(())
}

fn profile_repair(core: &Launcher, raw_id: &str) -> Result<(), launcher_core::ServiceError> {
    let summary = core.repair_profile(&parse_id(raw_id)?, None)?;
    println!("{}", summary.message());
    for action in &summary.actions {
        println!("  fixed  {}", action.description);
    }
    for defect in &summary.left_alone {
        println!("  left   {}", defect.message());
    }
    Ok(())
}

fn profile_export(core: &Launcher, raw_id: &str) -> Result<(), launcher_core::ServiceError> {
    println!("{}", core.export_profile(&parse_id(raw_id)?)?);
    Ok(())
}

fn profile_import(core: &Launcher, file: &str) -> Result<(), launcher_core::ServiceError> {
    let bytes = std::fs::read(file)
        .map_err(|_| launcher_core::ServiceError::NotImplemented("reading that file"))?;
    let (profile, outcome) = core.import_profile(&bytes)?;

    println!("imported {} ({})", profile.display_name, profile.id);
    if outcome.renamed {
        println!("  the name was taken, so a new id was derived");
    }
    for adjustment in &outcome.adjustments {
        println!("  adjusted: {adjustment}");
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Loaders and mods
// ---------------------------------------------------------------------------

fn loaders_command() -> Result<(), launcher_core::ServiceError> {
    let core = open()?;
    println!("{:<24} {:<14} mods", "loader", "launchable");
    for (loader, implemented, mods) in core.loader_capabilities() {
        println!(
            "{:<24} {:<14} {}",
            loader.label(),
            if implemented { "yes" } else { "not yet" },
            if mods { "yes" } else { "no" }
        );
    }
    Ok(())
}

fn mods_command(args: &[String]) -> Result<(), launcher_core::ServiceError> {
    let sub = args.first().map(String::as_str).unwrap_or("list");
    let rest: Vec<&str> = args.iter().skip(1).map(String::as_str).collect();

    // `catalog` is about the launcher, not about a profile.
    if sub == "catalog" {
        return mods_catalog();
    }

    let mut core = open()?;
    // The trailing argument is a profile id where it names one; otherwise the
    // first profile is used, which is what a single-profile install wants.
    let (operand, profile_id) = split_operand(&rest, &core);
    let mut profile = pick_profile(&core, profile_id)?;

    match (sub, operand) {
        ("list", _) => mods_list(&core, &profile),
        ("add", Some(file)) => mods_add(&core, &mut profile, file),
        ("enable", Some(id)) => mods_toggle(&core, &mut profile, id, true),
        ("disable", Some(id)) => mods_toggle(&core, &mut profile, id, false),
        ("remove", Some(id)) => mods_remove(&core, &mut profile, id),
        ("resolve", _) => mods_resolve(&core, &profile),
        ("apply", _) => mods_apply(&mut core, &mut profile),
        _ => {
            println!("Unknown or incomplete mods subcommand. Run `fathom-cli help`.");
            Ok(())
        }
    }
}

/// Separate an operand from an optional trailing profile id.
///
/// `mods add x.jar my-profile` and `mods add x.jar` both work, and neither
/// requires the caller to remember an order beyond "operand first".
fn split_operand<'a>(rest: &[&'a str], core: &Launcher) -> (Option<&'a str>, Option<&'a str>) {
    let known: Vec<String> = core.profiles().iter().map(|p| p.id.to_string()).collect();
    match rest {
        [] => (None, None),
        [one] if known.iter().any(|k| k == one) => (None, Some(one)),
        [one] => (Some(one), None),
        [first, second, ..] => (Some(first), Some(second)),
    }
}

fn mods_list(
    core: &Launcher,
    profile: &launcher_core::profiles::Profile,
) -> Result<(), launcher_core::ServiceError> {
    println!("profile        {}", profile.display_name);
    println!("loader         {}", profile.loader_kind().label());

    if profile.mods.is_empty() {
        println!("\nThis profile wants no mods.");
    } else {
        println!("\nwanted by this profile:");
        for entry in &profile.mods.mods {
            println!(
                "  [{}] {:<32} {:<24} sha1 {}",
                if entry.enabled { "on " } else { "off" },
                entry.id,
                entry.file_name,
                entry.sha1.chars().take(12).collect::<String>()
            );
            if let Some(reason) = &entry.disabled_reason {
                if !entry.enabled {
                    println!("        {reason:?}");
                }
            }
        }
    }

    // What is physically in the folder is a different question.
    let installed = core.mods_for(&profile.id);
    if installed.is_empty() {
        println!("\nThe mods folder is empty.");
    } else {
        println!("\nin the mods folder:");
        for item in &installed {
            println!(
                "  {:<40} {:>10}  {}",
                item.file_name,
                human_bytes(item.size),
                if item.managed {
                    "placed by Fathom"
                } else {
                    "added by you"
                }
            );
        }
    }
    Ok(())
}

fn mods_add(
    core: &Launcher,
    profile: &mut launcher_core::profiles::Profile,
    file: &str,
) -> Result<(), launcher_core::ServiceError> {
    let entry = core.add_mod_from_file(profile, std::path::Path::new(file))?;
    println!("added {} as {}", entry.file_name, entry.id);
    println!("  sha1 {}", entry.sha1);
    println!("  your original file was copied, not moved");
    println!("  run `fathom-cli mods apply` to put it in the mods folder");
    Ok(())
}

fn mods_toggle(
    core: &Launcher,
    profile: &mut launcher_core::profiles::Profile,
    raw_id: &str,
    enabled: bool,
) -> Result<(), launcher_core::ServiceError> {
    let id =
        launcher_core::mods::ModId::new(raw_id).map_err(launcher_core::mods::ModError::from)?;
    if core.set_mod_enabled(profile, &id, enabled)? {
        println!("{id} is now {}", if enabled { "on" } else { "off" });
        println!("  nothing on disk changed; run `mods apply` to rebuild the folder");
    } else {
        println!("{id} was already {}", if enabled { "on" } else { "off" });
    }
    Ok(())
}

fn mods_remove(
    core: &Launcher,
    profile: &mut launcher_core::profiles::Profile,
    raw_id: &str,
) -> Result<(), launcher_core::ServiceError> {
    let id =
        launcher_core::mods::ModId::new(raw_id).map_err(launcher_core::mods::ModError::from)?;
    match core.remove_mod(profile, &id)? {
        Some(removed) => {
            println!("{} is no longer part of this profile", removed.file_name);
            println!("  the file is still in Fathom's mod store, shared with any other profile");
        }
        None => println!("{id} is not in this profile"),
    }
    Ok(())
}

fn mods_resolve(
    core: &Launcher,
    profile: &launcher_core::profiles::Profile,
) -> Result<(), launcher_core::ServiceError> {
    let resolution = core.resolve_mods(profile);
    let names = launcher_core::mods::NameLookup::from_definitions(&core.mod_catalogue());

    println!("will load      {}", resolution.effective.len());
    for item in &resolution.effective {
        println!("  {}", item.file_name);
    }

    if resolution.problems.is_empty() {
        println!("\nNothing to sort out.");
        return Ok(());
    }

    println!("\nproblems       {}", resolution.problems.len());
    for problem in &resolution.problems {
        println!(
            "  [{:<12}] {}",
            problem.severity().label(),
            problem.message(&names)
        );
    }
    println!();
    println!(
        "{}",
        if resolution.is_launchable() {
            "The game can still start."
        } else {
            "The game will not start until the blocking problems are fixed."
        }
    );
    Ok(())
}

fn mods_apply(
    core: &mut Launcher,
    profile: &mut launcher_core::profiles::Profile,
) -> Result<(), launcher_core::ServiceError> {
    let outcome = core.materialize_mods(profile)?;
    println!("{}", outcome.summary());
    for name in &outcome.placed {
        println!("  placed   {name}");
    }
    for name in &outcome.removed {
        println!("  removed  {name}");
    }
    for name in &outcome.foreign {
        println!("  adopted  {name} (you had added it yourself)");
    }
    for name in &outcome.missing {
        println!("  missing  {name} - its file is not in the store");
    }
    Ok(())
}

fn mods_catalog() -> Result<(), launcher_core::ServiceError> {
    println!("What Fathom knows about, from docs/research/mod-policies.md.\n");
    for definition in launcher_core::mods::catalog::entries() {
        println!("{} ({})", definition.name, definition.id);
        println!("  licence      {}", definition.license.describe());
        println!("  privacy      {}", definition.privacy.describe());
        println!(
            "  loaders      {}",
            definition
                .loaders
                .iter()
                .map(|l| l.label())
                .collect::<Vec<_>>()
                .join(", ")
        );
        match definition.why_not_fetchable() {
            Some(reason) => println!("  downloading  NO - {reason}"),
            None => println!("  downloading  on your explicit request only"),
        }
        for conflict in &definition.conflicts {
            println!("  conflicts    {} ({:?})", conflict.id, conflict.kind);
        }
        for dependency in &definition.dependencies {
            println!("  requires     {}", dependency.id);
        }
        println!();
    }
    Ok(())
}
