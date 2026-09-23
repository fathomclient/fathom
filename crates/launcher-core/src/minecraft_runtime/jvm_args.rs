//! JVM argument construction and validation.
//!
//! Two jobs:
//!
//! 1. Produce the default flag set from `PERFORMANCE_MATRIX.md` §F. That set is
//!    deliberately short. Notably it does **not** contain Aikar's flags, which
//!    the research found actively harmful on a client.
//! 2. Refuse user-supplied flags that would execute code, load a library,
//!    redirect the game's I/O, or trade the user's security for a few percent.
//!    Refusals are explained, never silent.
//!
//! # Why the second job is a security boundary and not a convenience
//!
//! `spawn` passes an argument vector straight to `CreateProcessW` / `execvp`,
//! so there is no shell for anything here to be injected into. That guarantee
//! stops at the JVM's own front door: **`HotSpot` will start a shell of its own**
//! for `-XX:OnError` and `-XX:OnOutOfMemoryError`, and will load arbitrary
//! native code for `-agentpath:`, before a single class of Minecraft is
//! touched. A flag list is therefore a code-execution surface whatever the
//! spawner does.
//!
//! It matters because a profile is a **shareable document**. `ProfileOps`
//! exports one so a friend can reproduce a setup, which means the argument list
//! can arrive from someone the user has no reason to trust. The import path
//! answers that by refusing to carry arguments at all (see
//! `ProfileOps::sanitise_import`); the list below is the second layer, and it
//! applies to arguments however they arrived.
//!
//! Both layers exist because a denylist of flag families cannot be complete —
//! the JVM has hundreds of options and gains more each release. The denylist
//! covers the families that are known to execute code or move files; the import
//! refusal covers the ones nobody has thought of yet.

use crate::profiles::Profile;

/// Why a user-supplied JVM argument was refused.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ArgRejection {
    #[error(
        "{flag} needs the SeLockMemoryPrivilege on Windows. Granting a lasting \
         privilege for a few percent is not a trade this launcher makes."
    )]
    RequiresPrivilege { flag: String },
    #[error(
        "{flag} turns off bytecode verification, which is a real security \
         control, to save a fraction of a second at startup."
    )]
    DisablesSecurityControl { flag: String },
    #[error("{flag} was removed from modern JVMs and enables unstable options.")]
    RemovedOrUnstable { flag: String },
    #[error(
        "{flag} would open a debugger or JMX listener reachable from the \
         network, which is a remote code execution channel."
    )]
    OpensRemoteListener { flag: String },
    #[error(
        "the launcher sets the heap itself. Change it in the profile's Java \
         settings rather than passing {flag}."
    )]
    HeapIsManaged { flag: String },
    #[error(
        "{flag} contains a shell metacharacter. Arguments are passed to the JVM \
         directly, never through a shell, so this cannot be what you meant."
    )]
    ShellMetacharacter { flag: String },
    #[error(
        "{flag} hands a command to the JVM to run through a shell when the game \
         fails. This launcher never starts a shell, and it will not ask the JVM \
         to start one either."
    )]
    ExecutesACommand { flag: String },
    #[error(
        "{flag} loads code into the JVM before Minecraft starts, with no \
         signature check and no sandbox. If a mod needs this, install it as a \
         mod, where the launcher can check what it is."
    )]
    LoadsCode { flag: String },
    #[error(
        "{flag} changes where the JVM looks for native libraries. The launcher \
         points it at this profile's own verified natives, and overriding that \
         is how a library gets loaded from somewhere nobody checked."
    )]
    RedirectsLibraryLoading { flag: String },
    #[error(
        "the launcher builds the classpath from the verified installation. \
         {flag} would replace or extend it with something unverified."
    )]
    OverridesTheClasspath { flag: String },
    #[error(
        "{flag} makes the JVM write a file to a path of its own choosing. The \
         launcher keeps the game's output inside the profile."
    )]
    WritesAnArbitraryFile { flag: String },
    #[error(
        "{flag} changes how the game validates TLS certificates, which would \
         let something between the game and Mojang read or alter the session."
    )]
    WeakensTransportSecurity { flag: String },
    #[error(
        "{flag} sends the game's network traffic through a host of its own \
         choosing. `PRIVACY.md` is a promise about where this program connects, \
         and this would quietly break it."
    )]
    RedirectsNetworkTraffic { flag: String },
    #[error("arguments must not be empty or contain control characters")]
    Malformed,
}

impl ArgRejection {
    pub fn flag(&self) -> Option<&str> {
        match self {
            ArgRejection::RequiresPrivilege { flag }
            | ArgRejection::DisablesSecurityControl { flag }
            | ArgRejection::RemovedOrUnstable { flag }
            | ArgRejection::OpensRemoteListener { flag }
            | ArgRejection::HeapIsManaged { flag }
            | ArgRejection::ShellMetacharacter { flag }
            | ArgRejection::ExecutesACommand { flag }
            | ArgRejection::LoadsCode { flag }
            | ArgRejection::RedirectsLibraryLoading { flag }
            | ArgRejection::OverridesTheClasspath { flag }
            | ArgRejection::WritesAnArbitraryFile { flag }
            | ArgRejection::WeakensTransportSecurity { flag }
            | ArgRejection::RedirectsNetworkTraffic { flag } => Some(flag),
            ArgRejection::Malformed => None,
        }
    }
}

/// Characters that would matter only if we invoked a shell. We never do, so
/// their presence signals either a misunderstanding or an injection attempt.
///
/// Note what this does **not** protect against on its own: `-XX:OnError=calc`
/// contains no metacharacter at all and is still a command the JVM will run.
/// The families below are the reason this check is not the defence it looks
/// like.
const SHELL_METACHARACTERS: &[char] = &['&', '|', ';', '`', '\n', '\r', '$', '>', '<'];

/// Flags whose value `HotSpot` hands to a shell.
///
/// `-XX:OnError` fires on any fatal VM error and `-XX:OnOutOfMemoryError` on
/// heap exhaustion; both run their value through `cmd /c` on Windows and
/// `/bin/sh -c` elsewhere. A crash is not a rare event in a modded 1.8.9
/// client, so "only on failure" is not much of a mitigation.
const EXECUTES_A_COMMAND: &[&str] = &["-xx:onerror", "-xx:onoutofmemoryerror"];

/// Flags that load code into the JVM before `main` runs.
///
/// `-agentpath:` loads a native library — arbitrary machine code in the game's
/// address space, unconditionally, at startup. The rest are the Java-level
/// equivalents. `-agentlib:jdwp` is handled separately above, because a
/// loopback debugger is a legitimate thing to want.
const LOADS_CODE: &[&str] = &[
    "-agentpath:",
    "-javaagent:",
    "-xbootclasspath",
    "-xx:sharedarchivefile=",
    "-xshare:",
    "-jar",
    "-dcom.sun.management.agent",
    "-djdk.attach.allowattachself",
];

/// Flags that change where native libraries are found.
///
/// The launcher points `java.library.path` at the profile's own natives, which
/// were extracted from hash-verified archives through an allowlist filter. A
/// later `-D` of the same name wins, so this is a working DLL-hijack primitive
/// rather than a theoretical one.
const REDIRECTS_LIBRARY_LOADING: &[&str] = &[
    "-djava.library.path=",
    "-dsun.boot.library.path=",
    "-djava.ext.dirs=",
    "-djava.endorsed.dirs=",
];

/// Flags that replace or extend the classpath the launcher verified.
const OVERRIDES_THE_CLASSPATH: &[&str] = &[
    "-cp",
    "-classpath",
    "--class-path",
    "-djava.class.path=",
    "-djava.system.class.loader=",
];

/// Flags that make the JVM write somewhere of the caller's choosing.
///
/// Not code execution on their own. They are a file-overwrite primitive, and
/// `-xx:flags=` is worse than that: it reads *more flags* out of a file, which
/// would route straight around everything on this page.
const WRITES_AN_ARBITRARY_FILE: &[&str] = &[
    "-xx:errorfile=",
    "-xx:heapdumppath=",
    "-xx:logfile=",
    "-xloggc:",
    "-xx:flags=",
    "-xx:compilecommandfile=",
    "-xx:+logvmoutput",
];

/// Flags that weaken the TLS the *game* uses to talk to Mojang.
const WEAKENS_TRANSPORT_SECURITY: &[&str] = &[
    "-djavax.net.ssl.truststore",
    "-djavax.net.ssl.keystore",
    "-dcom.sun.net.ssl.checkrevocation=false",
    "-djdk.tls.client.protocols=",
    "-dtrust_all_cert",
];

/// Flags that route the game's traffic somewhere else.
const REDIRECTS_NETWORK_TRAFFIC: &[&str] = &[
    "-dhttp.proxyhost=",
    "-dhttps.proxyhost=",
    "-dsocksproxyhost=",
    "-djava.net.usesystemproxies=",
];

/// Validate one user-supplied JVM argument.
pub fn validate_arg(arg: &str) -> Result<(), ArgRejection> {
    let trimmed = arg.trim();
    if trimmed.is_empty() || trimmed.chars().any(char::is_control) {
        return Err(ArgRejection::Malformed);
    }
    if let Some(c) = trimmed.chars().find(|c| SHELL_METACHARACTERS.contains(c)) {
        let _ = c;
        return Err(ArgRejection::ShellMetacharacter {
            flag: trimmed.to_owned(),
        });
    }

    let lower = trimmed.to_ascii_lowercase();
    let flag = trimmed.to_owned();

    // Large pages: needs a privilege grant. security-model.md §8.
    if lower.starts_with("-xx:+uselargepages") || lower.starts_with("-xx:+usetransparenthugepages")
    {
        return Err(ArgRejection::RequiresPrivilege { flag });
    }
    // Bytecode verification.
    if lower == "-noverify" || lower.starts_with("-xverify:none") {
        return Err(ArgRejection::DisablesSecurityControl { flag });
    }
    // Removed / umbrella-unstable.
    if lower.starts_with("-xx:+aggressiveopts") {
        return Err(ArgRejection::RemovedOrUnstable { flag });
    }
    // Remote listeners. A loopback-bound debugger is fine; anything else is not.
    if (lower.starts_with("-agentlib:jdwp") || lower.starts_with("-xrunjdwp"))
        && !binds_loopback_only(&lower)
    {
        return Err(ArgRejection::OpensRemoteListener { flag });
    }
    if lower.starts_with("-dcom.sun.management.jmxremote") && !lower.contains("localhost") {
        return Err(ArgRejection::OpensRemoteListener { flag });
    }
    // The heap is ours to set, so that Xms == Xmx holds.
    if lower.starts_with("-xmx") || lower.starts_with("-xms") {
        return Err(ArgRejection::HeapIsManaged { flag });
    }

    // -- the code-execution families ------------------------------------
    //
    // Checked after the specific cases above so that a more useful message
    // wins where one exists, and matched on a lowercased copy because a
    // rejection that can be dodged by changing a letter is not a rejection.
    if matches_family(&lower, EXECUTES_A_COMMAND) {
        return Err(ArgRejection::ExecutesACommand { flag });
    }
    if matches_family(&lower, LOADS_CODE) {
        return Err(ArgRejection::LoadsCode { flag });
    }
    if matches_family(&lower, REDIRECTS_LIBRARY_LOADING) {
        return Err(ArgRejection::RedirectsLibraryLoading { flag });
    }
    if matches_family(&lower, OVERRIDES_THE_CLASSPATH) {
        return Err(ArgRejection::OverridesTheClasspath { flag });
    }
    if matches_family(&lower, WRITES_AN_ARBITRARY_FILE) {
        return Err(ArgRejection::WritesAnArbitraryFile { flag });
    }
    if matches_family(&lower, WEAKENS_TRANSPORT_SECURITY) {
        return Err(ArgRejection::WeakensTransportSecurity { flag });
    }
    if matches_family(&lower, REDIRECTS_NETWORK_TRAFFIC) {
        return Err(ArgRejection::RedirectsNetworkTraffic { flag });
    }

    Ok(())
}

/// Whether `lower` is one of `family`, or begins with one of its prefixes.
///
/// Entries ending in `:` or `=` are prefixes of a flag that takes a value;
/// the rest must match the whole argument or be followed by a separator, so
/// that banning `-cp` does not also ban `-cpuprofile`.
fn matches_family(lower: &str, family: &[&str]) -> bool {
    family.iter().any(|entry| {
        if entry.ends_with(':') || entry.ends_with('=') {
            return lower.starts_with(entry);
        }
        if !lower.starts_with(entry) {
            return false;
        }
        // Exactly the flag, or the flag followed by something that separates it
        // from its value. `-Xbootclasspath/a:...` and `-XX:OnError=...` both
        // land here.
        lower.len() == entry.len()
            || lower
                .get(entry.len()..)
                .and_then(|rest| rest.chars().next())
                .is_some_and(|c| matches!(c, '=' | ':' | '/' | ' '))
    })
}

fn binds_loopback_only(jdwp: &str) -> bool {
    // `address=127.0.0.1:5005` or `address=localhost:5005` is local-only.
    // A bare port, or `*:port`, listens on every interface.
    jdwp.contains("address=127.0.0.1:") || jdwp.contains("address=localhost:")
}

/// Result of checking a whole list of user arguments.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ValidationReport {
    pub accepted: Vec<String>,
    pub rejected: Vec<(String, ArgRejection)>,
}

impl ValidationReport {
    pub fn is_clean(&self) -> bool {
        self.rejected.is_empty()
    }
}

/// Validate every argument, keeping the good ones and explaining the rest.
pub fn validate_args(args: &[String]) -> ValidationReport {
    let mut report = ValidationReport::default();
    for arg in args {
        match validate_arg(arg) {
            Ok(()) => report.accepted.push(arg.trim().to_owned()),
            Err(rejection) => report.rejected.push((arg.clone(), rejection)),
        }
    }
    report
}

/// Whether a heap size is sane for this machine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HeapVerdict {
    Ok,
    /// Below what 1.8.9 needs to start reliably.
    TooSmall,
    /// Above 75% of physical memory: guarantees swapping.
    ExceedsPhysical,
    /// Large enough that GC pauses get worse, not better.
    CounterProductive,
}

/// The point past which extra heap makes pauses longer rather than shorter.
/// `performance.md` §5.2.
pub const COUNTERPRODUCTIVE_HEAP_MB: u32 = 8192;
pub const MINIMUM_HEAP_MB: u32 = 512;

pub fn judge_heap(heap_mb: u32, physical_mb: Option<u32>) -> HeapVerdict {
    if heap_mb < MINIMUM_HEAP_MB {
        return HeapVerdict::TooSmall;
    }
    if let Some(physical) = physical_mb {
        // security-model.md §8: refuse anything at or above ~75% of RAM.
        let ceiling = physical.saturating_mul(3) / 4;
        if heap_mb > ceiling {
            return HeapVerdict::ExceedsPhysical;
        }
    }
    if heap_mb > COUNTERPRODUCTIVE_HEAP_MB {
        return HeapVerdict::CounterProductive;
    }
    HeapVerdict::Ok
}

/// Which garbage collector to ask for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Collector {
    /// Java 8's G1. Weaker than modern G1 but still pause-oriented, and the
    /// only sensible choice on the `jre-legacy` target.
    G1Legacy,
    /// Modern G1, for the Java 17+ path if the LWJGL 3 spike lands.
    G1Modern,
}

/// Build the JVM argument list.
///
/// Deliberately short. Every entry traces to a **SAFE -- ON** row in
/// `PERFORMANCE_MATRIX.md` §F, except the ones gated behind explicit config.
pub fn build_jvm_args(profile: &Profile, java_major: u32, natives_dir: &str) -> Vec<String> {
    let mut args = Vec::new();

    // Xms == Xmx: removes heap-resize pauses at zero cost.
    args.push(format!("-Xms{}M", profile.memory.heap_mb));
    args.push(format!("-Xmx{}M", profile.memory.heap_mb));

    // Neutralises vanilla's System.gc() on world load, which otherwise causes a
    // multi-second freeze on every world change. MemoryFix's README names this
    // exact flag as equivalent to its in-game fix.
    // SAFE in the matrix and on by default, but a profile toggle now, so a
    // user chasing a specific problem can turn it off without editing flags.
    if profile.optimizations.jvm.disable_explicit_gc {
        args.push("-XX:+DisableExplicitGC".to_owned());
    }

    // Pause-oriented collector.
    match collector_for(java_major) {
        Collector::G1Legacy | Collector::G1Modern => {
            args.push("-XX:+UseG1GC".to_owned());
            // Conservative, not aggressive. The research is explicit that
            // Aikar-style aggressive G1 tuning produces client stutter.
            args.push("-XX:MaxGCPauseMillis=50".to_owned());
        }
    }

    if profile.optimizations.jvm.compile_huge_methods {
        // Kept switchable, but it buys nothing on 1.8.9 and defaults off.
        //
        // The claim this used to carry -- that Minecraft has hot methods over
        // HotSpot's 8000-byte HugeMethodLimit which "otherwise run interpreted
        // forever" -- is true of later versions and false of this one. Every
        // method in `minecraft-client.jar` and `minecraft-merged.jar` was
        // measured (the Code attribute length, which is what the limit is
        // compared against): exactly **one** exceeds 8000 bytes, at 8296 --
        // `RecipeDispatcher.<init>`, which registers the crafting recipes once
        // during startup and is never on a render path.
        //
        // So the flag changes the compilation of a single constructor that runs
        // before the main menu, and `ReservedCodeCacheSize=400M` reserves 400 MB
        // of address space for it. Neither is harmful; neither is an
        // optimization. It stays because a profile that was written with it on
        // must keep meaning what it said, and because the 1.8.9 fact does not
        // hold for a future version target.
        args.push("-XX:-DontCompileHugeMethods".to_owned());
        args.push("-XX:ReservedCodeCacheSize=400M".to_owned());
    }

    // Where the extracted LWJGL natives live. Required by 1.8.9.
    args.push(format!("-Djava.library.path={natives_dir}"));

    // Keeps Minecraft's own crash reports inside the profile.
    args.push("-Dfile.encoding=UTF-8".to_owned());

    // Only arguments that survived validation.
    args.extend(validate_args(&profile.jvm.extra_args).accepted);

    args
}

pub fn collector_for(java_major: u32) -> Collector {
    if java_major >= 11 {
        Collector::G1Modern
    } else {
        Collector::G1Legacy
    }
}

#[cfg(test)]
#[allow(
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::unwrap_used,
    clippy::panic
)]
mod tests {
    use super::*;

    #[test]
    fn large_pages_are_refused() {
        for flag in ["-XX:+UseLargePages", "-XX:+UseTransparentHugePages"] {
            assert!(matches!(
                validate_arg(flag),
                Err(ArgRejection::RequiresPrivilege { .. })
            ));
        }
    }

    #[test]
    fn disabling_bytecode_verification_is_refused() {
        for flag in ["-noverify", "-Xverify:none"] {
            assert!(matches!(
                validate_arg(flag),
                Err(ArgRejection::DisablesSecurityControl { .. })
            ));
        }
    }

    #[test]
    fn aggressive_opts_is_refused() {
        assert!(matches!(
            validate_arg("-XX:+AggressiveOpts"),
            Err(ArgRejection::RemovedOrUnstable { .. })
        ));
    }

    #[test]
    fn remote_debug_listeners_are_refused_but_loopback_is_allowed() {
        assert!(matches!(
            validate_arg("-agentlib:jdwp=transport=dt_socket,server=y,address=5005"),
            Err(ArgRejection::OpensRemoteListener { .. })
        ));
        assert!(matches!(
            validate_arg("-agentlib:jdwp=transport=dt_socket,server=y,address=*:5005"),
            Err(ArgRejection::OpensRemoteListener { .. })
        ));
        assert!(validate_arg(
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"
        )
        .is_ok());
    }

    #[test]
    fn heap_flags_are_managed_by_the_launcher() {
        for flag in ["-Xmx8G", "-Xms1024m"] {
            assert!(matches!(
                validate_arg(flag),
                Err(ArgRejection::HeapIsManaged { .. })
            ));
        }
    }

    #[test]
    fn shell_metacharacters_are_refused() {
        for flag in [
            "-Dfoo=bar; rm -rf /",
            "-Dfoo=$(whoami)",
            "-Dfoo=a`b`",
            "-Dfoo=a|b",
            "-Dfoo=a>b",
        ] {
            assert!(
                matches!(
                    validate_arg(flag),
                    Err(ArgRejection::ShellMetacharacter { .. })
                ),
                "{flag:?} should have been refused"
            );
        }
    }

    /// The finding this list exists for.
    ///
    /// Every one of these passed validation before the security pass, and every
    /// one of them is reachable by importing a profile someone else wrote.
    /// `-agentpath:` is the sharpest: it loads a native library into the game's
    /// address space unconditionally, at JVM startup, before any Minecraft code
    /// runs.
    #[test]
    fn flags_that_execute_code_are_refused() {
        /// A flag, and the kind of refusal it must produce.
        type Case = (&'static str, fn(&ArgRejection) -> bool);

        let cases: &[Case] = &[
            ("-XX:OnOutOfMemoryError=calc.exe", |r| {
                matches!(r, ArgRejection::ExecutesACommand { .. })
            }),
            ("-XX:OnError=powershell.exe -enc AAAA", |r| {
                matches!(r, ArgRejection::ExecutesACommand { .. })
            }),
            ("-agentpath:C:/Users/Public/evil.dll", |r| {
                matches!(r, ArgRejection::LoadsCode { .. })
            }),
            ("-agentpath:/tmp/evil.so=opts", |r| {
                matches!(r, ArgRejection::LoadsCode { .. })
            }),
            ("-javaagent:C:/Users/Public/evil.jar", |r| {
                matches!(r, ArgRejection::LoadsCode { .. })
            }),
            ("-Xbootclasspath/a:C:/Users/Public/evil.jar", |r| {
                matches!(r, ArgRejection::LoadsCode { .. })
            }),
            ("-Xbootclasspath/p:/tmp/first.jar", |r| {
                matches!(r, ArgRejection::LoadsCode { .. })
            }),
            ("-XX:SharedArchiveFile=/tmp/evil.jsa", |r| {
                matches!(r, ArgRejection::LoadsCode { .. })
            }),
            ("-Djava.library.path=C:/Users/Public", |r| {
                matches!(r, ArgRejection::RedirectsLibraryLoading { .. })
            }),
            ("-Dsun.boot.library.path=/tmp", |r| {
                matches!(r, ArgRejection::RedirectsLibraryLoading { .. })
            }),
            ("-Djava.ext.dirs=/tmp", |r| {
                matches!(r, ArgRejection::RedirectsLibraryLoading { .. })
            }),
            ("-Djava.class.path=/tmp/evil.jar", |r| {
                matches!(r, ArgRejection::OverridesTheClasspath { .. })
            }),
            ("-cp", |r| {
                matches!(r, ArgRejection::OverridesTheClasspath { .. })
            }),
            ("-classpath /tmp/evil.jar", |r| {
                matches!(r, ArgRejection::OverridesTheClasspath { .. })
            }),
            ("-XX:ErrorFile=C:/Windows/System32/x.dll", |r| {
                matches!(r, ArgRejection::WritesAnArbitraryFile { .. })
            }),
            ("-XX:HeapDumpPath=/etc/cron.d/x", |r| {
                matches!(r, ArgRejection::WritesAnArbitraryFile { .. })
            }),
            ("-Xloggc:/tmp/anything", |r| {
                matches!(r, ArgRejection::WritesAnArbitraryFile { .. })
            }),
            ("-XX:Flags=/tmp/more-flags", |r| {
                matches!(r, ArgRejection::WritesAnArbitraryFile { .. })
            }),
            ("-Djavax.net.ssl.trustStore=/tmp/mine.jks", |r| {
                matches!(r, ArgRejection::WeakensTransportSecurity { .. })
            }),
            ("-Dhttps.proxyHost=evil.example", |r| {
                matches!(r, ArgRejection::RedirectsNetworkTraffic { .. })
            }),
        ];

        for (flag, expected) in cases {
            let result = validate_arg(flag);
            let Err(rejection) = result else {
                panic!("{flag} must be refused, but validation accepted it");
            };
            assert!(
                expected(&rejection),
                "{flag} was refused as the wrong kind of problem: {rejection:?}"
            );
            // A refusal a user cannot act on is barely a refusal.
            assert!(
                !rejection.to_string().is_empty(),
                "{flag} refused without an explanation"
            );
        }
    }

    /// Case is not a way around any of this.
    #[test]
    fn the_refusals_are_case_insensitive() {
        for flag in [
            "-XX:ONOUTOFMEMORYERROR=calc.exe",
            "-xx:onerror=calc.exe",
            "-AgentPath:/tmp/x.so",
            "-JavaAgent:/tmp/x.jar",
        ] {
            assert!(
                validate_arg(flag).is_err(),
                "{flag} must be refused whatever its case"
            );
        }
    }

    /// The families are prefixes, so they have to stop at a flag boundary.
    /// Banning `-cp` must not ban every flag that happens to begin with it.
    #[test]
    fn the_family_check_stops_at_a_flag_boundary() {
        for flag in [
            "-XX:+UseCompressedOops",
            "-Dcpu.count=4",
            "-Djava.library.pathological=1",
            "-Dsun.java2d.opengl=true",
        ] {
            assert!(
                validate_arg(flag).is_ok(),
                "{flag} is harmless and must still be accepted"
            );
        }
    }

    #[test]
    fn ordinary_flags_pass() {
        for flag in [
            "-XX:+UseStringDeduplication",
            "-Dsun.java2d.opengl=true",
            "-XX:+AlwaysPreTouch",
        ] {
            assert!(validate_arg(flag).is_ok(), "{flag} should be allowed");
        }
    }

    #[test]
    fn validation_report_separates_good_from_bad() {
        let args = vec![
            "-XX:+AlwaysPreTouch".to_owned(),
            "-Xmx16G".to_owned(),
            "-XX:+UseLargePages".to_owned(),
        ];
        let report = validate_args(&args);
        assert_eq!(report.accepted, vec!["-XX:+AlwaysPreTouch".to_owned()]);
        assert_eq!(report.rejected.len(), 2);
        assert!(!report.is_clean());
    }

    #[test]
    fn heap_judgement_matches_the_research() {
        assert_eq!(judge_heap(2048, Some(16384)), HeapVerdict::Ok);
        assert_eq!(judge_heap(256, Some(16384)), HeapVerdict::TooSmall);
        // 75% of 8 GB is 6 GB, so 7 GB is refused on this machine.
        assert_eq!(judge_heap(7168, Some(8192)), HeapVerdict::ExceedsPhysical);
        // Fits in RAM but is past the point of diminishing returns.
        assert_eq!(
            judge_heap(12288, Some(65536)),
            HeapVerdict::CounterProductive
        );
    }

    /// A profile with nothing customised, for the argument-building tests.
    fn test_profile() -> Profile {
        Profile::new(
            crate::profiles::ProfileId::new("test").expect("valid id"),
            "Test",
        )
    }

    #[test]
    fn default_args_are_short_and_contain_no_rejected_flags() {
        let args = build_jvm_args(&test_profile(), 8, "/tmp/natives");

        assert!(args.contains(&"-Xms2048M".to_owned()));
        assert!(args.contains(&"-Xmx2048M".to_owned()));
        assert!(args.contains(&"-XX:+DisableExplicitGC".to_owned()));
        assert!(args.contains(&"-XX:+UseG1GC".to_owned()));

        // PERFORMANCE_MATRIX.md §F marks these REJECTED.
        for forbidden in [
            "-XX:+UseLargePages",
            "-Xverify:none",
            "-XX:+AggressiveOpts",
            "-XX:+UseZGC",
        ] {
            assert!(
                !args.iter().any(|a| a.contains(forbidden)),
                "default args must not contain {forbidden}"
            );
        }

        // Aikar's flags, which the research explicitly refuses for clients.
        for aikar in [
            "G1NewSizePercent",
            "G1MaxNewSizePercent",
            "G1HeapRegionSize",
        ] {
            assert!(
                !args.iter().any(|a| a.contains(aikar)),
                "default args must not contain Aikar's {aikar}"
            );
        }
    }

    #[test]
    fn xms_always_equals_xmx() {
        for heap in [512u32, 2048, 4096] {
            let mut profile = test_profile();
            profile.memory.heap_mb = heap;
            let args = build_jvm_args(&profile, 8, "/n");
            assert!(args.contains(&format!("-Xms{heap}M")));
            assert!(args.contains(&format!("-Xmx{heap}M")));
        }
    }

    #[test]
    fn rejected_user_args_never_reach_the_command_line() {
        let mut profile = test_profile();
        profile.jvm.extra_args = vec!["-XX:+UseLargePages".to_owned(), "-Dok=1".to_owned()];
        let args = build_jvm_args(&profile, 8, "/n");
        assert!(args.contains(&"-Dok=1".to_owned()));
        assert!(!args.iter().any(|a| a.contains("UseLargePages")));
    }

    #[test]
    fn huge_methods_flag_is_opt_in() {
        let off = build_jvm_args(&test_profile(), 8, "/n");
        assert!(!off.iter().any(|a| a.contains("DontCompileHugeMethods")));

        let mut profile = test_profile();
        profile.optimizations.jvm.compile_huge_methods = true;
        let on = build_jvm_args(&profile, 8, "/n");
        assert!(on.contains(&"-XX:-DontCompileHugeMethods".to_owned()));
        assert!(on.contains(&"-XX:ReservedCodeCacheSize=400M".to_owned()));
    }

    /// The optimization toggles are profile settings now, so turning one off has
    /// to actually change the command line.
    #[test]
    fn turning_off_an_optimization_removes_its_flag() {
        let mut profile = test_profile();
        profile.optimizations.jvm.disable_explicit_gc = false;
        let args = build_jvm_args(&profile, 8, "/n");
        assert!(!args.contains(&"-XX:+DisableExplicitGC".to_owned()));
        // And the rest of the defaults are unaffected.
        assert!(args.contains(&"-XX:+UseG1GC".to_owned()));
    }
}
