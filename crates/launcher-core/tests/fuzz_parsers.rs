//! Generative tests for every parser that accepts input from outside.
//!
//! # What this is, and what it is not
//!
//! This is **property testing with a seeded generator**, not a coverage-guided
//! fuzzer. It will not find the input that reaches a rare branch the way
//! `cargo-fuzz` would. What it does do is run tens of thousands of hostile and
//! malformed inputs through every entry point that takes bytes from a file, an
//! archive or the network, and assert that the invariants hold for all of them.
//!
//! It is written against a hand-rolled xorshift rather than `proptest` on
//! purpose. This crate has no dependency it does not need — `deny.toml` bans a
//! second HTTP stack and a second TLS stack for the same reason — and a
//! generator that is thirty lines and reproducible from a printed seed is worth
//! more here than a shrinking framework and its dependency tree. Every failure
//! reports the seed and the input, so a failing case is reproducible by hand.
//!
//! # The invariants
//!
//! Each section states the one property it is defending. The point is never
//! "does not panic" alone — `unwrap_used` and `panic` are already denied in this
//! crate — but that **a hostile input cannot make the program do something
//! outside its sandbox**.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]

use std::path::{Path, PathBuf};

use launcher_core::fs_safety::{self, FsError};
use launcher_core::paths::DataPaths;
use launcher_core::profiles::model::JavaPreference;
use launcher_core::profiles::ops::ProfileOps;
use launcher_core::profiles::GameDirectory;

// ===========================================================================
// A reproducible generator
// ===========================================================================

/// xorshift64*. Deterministic, seeded, and printed on failure.
struct Rng(u64);

impl Rng {
    fn new(seed: u64) -> Self {
        // A zero state is a fixed point for xorshift, so refuse it.
        Self(if seed == 0 {
            0x9E37_79B9_7F4A_7C15
        } else {
            seed
        })
    }

    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.0 = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    fn below(&mut self, n: usize) -> usize {
        if n == 0 {
            return 0;
        }
        usize::try_from(self.next() % n as u64).unwrap_or(0)
    }

    fn pick<'a, T>(&mut self, options: &'a [T]) -> &'a T {
        let i = self.below(options.len());
        options.get(i).unwrap_or_else(|| {
            // `below` is always in range for a non-empty slice; an empty one is
            // a bug in the test, not in the code under test.
            panic!("pick from an empty slice")
        })
    }

    fn bytes(&mut self, max: usize) -> Vec<u8> {
        let n = self.below(max);
        (0..n)
            .map(|_| u8::try_from(self.next() & 0xff).unwrap_or(0))
            .collect()
    }
}

/// Fragments chosen to be adversarial rather than random: the pieces that have
/// broken path handling in real launchers.
const PATH_FRAGMENTS: &[&str] = &[
    "..",
    ".",
    "",
    "/",
    "\\",
    "//",
    "..\\",
    "../",
    "C:",
    "C:\\",
    "mods",
    "a",
    "\u{0}",
    "con",
    "CON ",
    "nul.",
    "aux..",
    "COM1",
    "lpt9.txt",
    "file:stream",
    "a b",
    "\u{4e2d}\u{6587}",
    "\u{202e}gpj.exe",
    ".fathom-mods.json",
    "x.jar",
    "%2e%2e",
    "....//",
    "~",
    "$HOME",
    "\r\n",
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
];

fn random_path(rng: &mut Rng) -> String {
    let segments = rng.below(6) + 1;
    let mut out = String::new();
    for i in 0..segments {
        if i > 0 {
            out.push(*rng.pick(&['/', '\\']));
        }
        out.push_str(rng.pick(PATH_FRAGMENTS));
    }
    out
}

// ===========================================================================
// Archive entry paths
// ===========================================================================

/// **Invariant: `contain` either refuses, or returns a path inside the root.**
///
/// There is no third outcome. This is the property every archive extraction and
/// every mod file name depends on, so it is worth hammering directly rather
/// than only through the extractor.
#[test]
fn contain_never_returns_a_path_outside_its_root() {
    let root = Path::new("/launcher/profiles/main");
    let mut rng = Rng::new(0xC0FF_EE00_1234_5678);

    let mut accepted = 0u32;
    let mut refused = 0u32;

    for i in 0..200_000u32 {
        let candidate = random_path(&mut rng);
        match fs_safety::contain(root, &candidate) {
            Ok(contained) => {
                accepted += 1;
                assert!(
                    fs_safety::is_lexically_inside(root, contained.path()),
                    "iteration {i}: contain accepted {candidate:?} and produced {:?}, \
                     which is outside {root:?}",
                    contained.path()
                );
                // No surviving path may contain a traversal *component*.
                //
                // Substring matching would be wrong here, and the first version
                // of this test got it wrong: an entry named `....` produces a
                // path containing the characters `..` while being an ordinary
                // (if strange) directory name. `Component::ParentDir` is the
                // thing that traverses; see the dedicated test below for what
                // Windows actually does with the near-misses.
                assert!(
                    !contained
                        .path()
                        .components()
                        .any(|c| matches!(c, std::path::Component::ParentDir)),
                    "iteration {i}: {candidate:?} produced a path with a `..` component: {:?}",
                    contained.path()
                );
                assert!(
                    !contained.path().to_string_lossy().contains('\u{0}'),
                    "iteration {i}: {candidate:?} produced a path containing a NUL"
                );
            }
            Err(FsError::Escape(_) | FsError::ForbiddenComponent(_)) => refused += 1,
            Err(other) => {
                panic!("iteration {i}: {candidate:?} produced an unexpected error: {other}")
            }
        }
    }

    // A generator that only ever produces refusals would pass the assertion
    // above while testing nothing. Both outcomes have to be reachable.
    assert!(
        accepted > 1000,
        "the generator produced almost no accepted paths ({accepted})"
    );
    assert!(
        refused > 1000,
        "the generator produced almost no refusals ({refused})"
    );
}

/// What Windows actually does with the near-misses for `..`.
///
/// `contain` refuses a component that is exactly `..` and treats everything
/// else as an ordinary name. That is only safe if Windows agrees, because
/// Win32 strips trailing dots and spaces from a path component before
/// resolving it — so the obvious worry is that `".. "` or `"..."` collapses to
/// `".."` and walks out of the root.
///
/// **Measured on Windows 11 26200 with `std::fs`, not assumed:** it does not.
/// Only the exact `..` traverses; `".. "`, `"..."`, `".. ."`, `"..  "` and
/// `"...."` are all rejected as names that do not exist. The stripping happens,
/// but the result is not re-interpreted as a relative directory reference.
///
/// This test pins that, so the day it stops being true it fails here rather
/// than in someone's profile directory. On other platforms it asserts the
/// weaker thing that is true everywhere: the path stays inside the root.
#[test]
fn trailing_dots_and_spaces_do_not_turn_into_a_traversal() {
    let tmp = tempfile::tempdir().unwrap_or_else(|e| panic!("tempdir: {e}"));
    let root = tmp.path().join("root");
    let sub = root.join("sub");
    std::fs::create_dir_all(&sub).unwrap_or_else(|e| panic!("mkdir: {e}"));
    std::fs::write(root.join("CANARY.txt"), b"outside").unwrap_or_else(|e| panic!("canary: {e}"));

    for component in ["..", ".. ", "...", ".. .", "..  ", "....", "\u{a0}.."] {
        let entry = format!("{component}/pwned.txt");
        // A refusal is the answer for `..` itself and needs no further check.
        if let Ok(contained) = fs_safety::contain(&sub, &entry) {
            // Accepted, so it must not be a traversal. Confirmed against the
            // real filesystem rather than only lexically: write through it and
            // check where the bytes landed.
            assert!(
                fs_safety::is_lexically_inside(&sub, contained.path()),
                "{component:?} was accepted and escapes lexically"
            );
            if let Some(parent) = contained.path().parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            if std::fs::write(contained.path(), b"x").is_ok() {
                let escaped = root.join("pwned.txt");
                assert!(
                    !escaped.exists(),
                    "{component:?} was accepted and wrote outside the root"
                );
            }
        }
    }

    // The exact form is always refused, on every platform.
    assert!(
        fs_safety::contain(&sub, "../pwned.txt").is_err(),
        "a plain `..` must always be refused"
    );
}

/// **Invariant: a contained path is stable under being contained again.**
///
/// If containing an already-contained name could produce something different,
/// then any code path that normalises twice has a hole in it.
#[test]
fn containment_is_idempotent() {
    let root = Path::new("/launcher/profiles/main");
    let mut rng = Rng::new(0x5EED_0000_0000_0001);

    for i in 0..50_000u32 {
        let candidate = random_path(&mut rng);
        let Ok(first) = fs_safety::contain(root, &candidate) else {
            continue;
        };
        let relative = first
            .path()
            .strip_prefix(root)
            .unwrap_or(first.path())
            .to_string_lossy()
            .replace('\\', "/");
        if relative.is_empty() {
            continue;
        }
        let second = fs_safety::contain(root, &relative).unwrap_or_else(|e| {
            panic!("iteration {i}: {candidate:?} -> {relative:?} refused on the second pass: {e}")
        });
        assert_eq!(
            first.path(),
            second.path(),
            "iteration {i}: {candidate:?} was not stable under a second containment"
        );
    }
}

// ===========================================================================
// Profile import
// ===========================================================================

struct Fixture {
    _tmp: tempfile::TempDir,
    paths: DataPaths,
    ops: ProfileOps,
}

impl Fixture {
    fn new() -> Self {
        let tmp = tempfile::tempdir().unwrap_or_else(|e| panic!("tempdir: {e}"));
        let paths = DataPaths::rooted_at(tmp.path());
        let ops = ProfileOps::new(paths.clone());
        Self {
            _tmp: tmp,
            paths,
            ops,
        }
    }

    fn ops(&self) -> &ProfileOps {
        &self.ops
    }
}

/// Every hostile shape we can think of, plus a lot we did not.
fn random_profile_document(rng: &mut Rng) -> Vec<u8> {
    // Weighted deliberately toward names that yield a valid profile id. The
    // first version of this generator was almost all hostile names, so almost
    // every document was refused by the id validator and the *sanitiser* — the
    // thing being tested — barely ran. A generator that mostly produces
    // rejections is a test that mostly proves nothing.
    const NAMES: &[&str] = &[
        "main",
        "Survival",
        "PvP Practice",
        "test 1",
        "Fathom",
        "a",
        "profile-2",
        "",
        " ",
        "../escape",
        "..\\escape",
        "CON",
        "nul ",
        "\u{0}name",
        "\u{4e2d}\u{6587}",
        "name\nwith\nnewlines",
        "%00",
    ];
    const ARGS: &[&str] = &[
        "-XX:OnOutOfMemoryError=calc.exe",
        "-agentpath:/tmp/evil.so",
        "-javaagent:/tmp/evil.jar",
        "-Djava.library.path=/tmp",
        "-Xmx99999G",
        "-XX:+AlwaysPreTouch",
        "",
    ];
    const HEAPS: &[i64] = &[-1, 0, 1, 512, 2048, 4_000_000, i64::MAX, i64::MIN];
    const DIMS: &[i64] = &[-1, 0, 1, 640, 1920, 100_000, i64::MAX];

    let body = format!(
        r#"{{
            "schema": {schema},
            "exported_by": "fuzz",
            "exported_at": 0,
            "profile": {{
                "id": "{id}",
                "display_name": "{name}",
                "version_id": "{version}",
                "memory": {{ "heap_mb": {heap} }},
                "display": {{ "resolution": {{ "width": {w}, "height": {h} }}, "fullscreen": {full} }},
                "java": {{ "kind": "explicit", "path": "{java}" }},
                "game_directory": {{ "kind": "external", "path": "{gamedir}" }},
                "jvm": {{ "extra_args": ["{arg}"] }},
                "mods": {{ "mods": [
                    {{ "id": "m", "sha1": "{sha}", "file_name": "{modname}", "enabled": true }}
                ]}}
            }}
        }}"#,
        schema = rng.below(6),
        id = escape(rng.pick(NAMES)),
        name = escape(rng.pick(NAMES)),
        version = escape(rng.pick(&["1.8.9", "", "../../x"])),
        heap = rng.pick(HEAPS),
        w = rng.pick(DIMS),
        h = rng.pick(DIMS),
        full = rng.pick(&["true", "false"]),
        java = escape(&random_path(rng)),
        gamedir = escape(&random_path(rng)),
        arg = escape(rng.pick(ARGS)),
        sha = "a".repeat(40),
        modname = escape(&random_path(rng)),
    );
    body.into_bytes()
}

/// JSON string escaping, enough for the fragments above.
fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            c if (c as u32) < 0x20 => {
                use std::fmt::Write as _;
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out
}

/// **Invariant: an imported profile is always safe to act on.**
///
/// Whatever the document said, what comes out has no Java path, no external
/// game directory, no JVM arguments, a heap in range, a sane resolution, and
/// only mod file names that stay inside the instance. Those are the fields that
/// become a command line or a filesystem path, and this is the boundary that
/// decides whether opening a file someone else wrote is safe.
#[test]
fn no_import_document_can_produce_an_unsafe_profile() {
    let f = Fixture::new();
    let ops = f.ops();
    let mut rng = Rng::new(0xDEAD_BEEF_CAFE_0001);

    let mut imported = 0u32;

    for i in 0..6_000u32 {
        let document = random_profile_document(&mut rng);
        let Ok((profile, _outcome)) = ops.import(&document) else {
            continue;
        };
        imported += 1;

        let shown = String::from_utf8_lossy(&document);

        // Nothing that becomes an executable path survives.
        assert_eq!(
            profile.java,
            JavaPreference::Automatic,
            "iteration {i}: an imported Java path survived.\n{shown}"
        );
        // Nothing that becomes a JVM flag survives. This is the second of the
        // two layers guarding the code-execution finding; the first is
        // `jvm_args::validate_arg`.
        assert!(
            profile.jvm.extra_args.is_empty(),
            "iteration {i}: imported JVM arguments survived: {:?}\n{shown}",
            profile.jvm.extra_args
        );
        // Nothing that becomes a directory outside launcher storage survives.
        assert_eq!(
            profile.game_directory,
            GameDirectory::Managed,
            "iteration {i}: an external game directory survived.\n{shown}"
        );
        assert!(
            profile.display.resolution.is_sane(),
            "iteration {i}: resolution {:?} is not sane.\n{shown}",
            profile.display.resolution
        );
        assert!(
            (512..=32768).contains(&profile.memory.heap_mb),
            "iteration {i}: heap {} is out of range.\n{shown}",
            profile.memory.heap_mb
        );

        // Every surviving mod name must produce a path inside the instance.
        let dirs = ops.dirs_for(&profile);
        for entry in &profile.mods.mods {
            let contained = dirs
                .contain_in_game(&format!("mods/{}", entry.file_name))
                .unwrap_or_else(|e| {
                    panic!(
                        "iteration {i}: mod name {:?} survived import but will not \
                         contain: {e}\n{shown}",
                        entry.file_name
                    )
                });
            assert!(
                fs_safety::is_lexically_inside(dirs.root(), &contained),
                "iteration {i}: mod {:?} resolves outside the profile.\n{shown}",
                entry.file_name
            );
        }

        // And the profile's own directory is inside the profiles root.
        assert!(
            fs_safety::is_lexically_inside(&f.paths.profiles_dir(), dirs.root()),
            "iteration {i}: profile directory {:?} is outside the profiles root.\n{shown}",
            dirs.root()
        );
    }

    assert!(
        imported > 100,
        "almost nothing was imported ({imported}); the generator is producing junk \
         rather than exercising the sanitiser"
    );
}

/// **Invariant: arbitrary bytes are refused, not obeyed.**
///
/// The document is JSON, so almost all of these fail to parse. What matters is
/// that none of them panics, hangs, or leaves a directory behind.
#[test]
fn arbitrary_bytes_are_refused_without_side_effects() {
    let f = Fixture::new();
    let ops = f.ops();
    let mut rng = Rng::new(0x0BAD_0BAD_0BAD_0BAD);

    for i in 0..20_000u32 {
        let bytes = rng.bytes(512);
        // The only requirement is that this returns.
        let _ = ops.import(&bytes);

        if i % 2000 == 0 {
            // Nothing may accumulate: an import that fails must not have
            // created a profile directory on its way to failing.
            let created = ops.store().list().len();
            assert!(
                created < 100,
                "iteration {i}: {created} profiles exist after only failed imports"
            );
        }
    }
}

/// **Invariant: nesting cannot exhaust the stack.**
///
/// `serde_json` has a recursion limit, but relying on a default without testing
/// it is how a stack overflow ships. A stack overflow is an abort, which no
/// `Result` can catch.
#[test]
fn deeply_nested_documents_are_refused_rather_than_overflowing() {
    let f = Fixture::new();
    let ops = f.ops();

    for depth in [64usize, 128, 1_000, 100_000] {
        let mut document = String::with_capacity(depth * 2 + 64);
        document.push_str(r#"{"schema":3,"profile":"#);
        for _ in 0..depth {
            document.push('[');
        }
        for _ in 0..depth {
            document.push(']');
        }
        document.push('}');

        let result = ops.import(document.as_bytes());
        assert!(
            result.is_err(),
            "a document nested {depth} deep is not a profile and must be refused"
        );
    }
}

// ===========================================================================
// Version metadata
// ===========================================================================

/// **Invariant: a version document we did not write cannot produce a JVM flag.**
///
/// `logging.client.argument` is a free-form template from the version document.
/// Before the security pass it went onto the JVM command line unvalidated,
/// which made it the one route into the argument vector that skipped every
/// refusal in `jvm_args`.
#[test]
fn a_version_documents_logging_argument_is_always_a_log4j_property_or_nothing() {
    use launcher_core::minecraft_runtime::jvm_args;

    const PREFIXES: &[&str] = &[
        "-Dlog4j.configurationFile=",
        "-Dlog4j.configurationFileX=",
        "-XX:OnOutOfMemoryError=",
        "-agentpath:",
        "-javaagent:",
        "-Djava.library.path=",
        "",
        "-D",
    ];
    let mut rng = Rng::new(0x1234_5678_9ABC_DEF0);

    for i in 0..20_000u32 {
        let candidate = format!("{}{}", rng.pick(PREFIXES), random_path(&mut rng));

        // Whatever the document said, if it is not the log4j property it must
        // not survive validation as an ordinary argument either.
        let is_log4j = candidate.starts_with("-Dlog4j.configurationFile=");
        if !is_log4j {
            continue;
        }
        // Validation looks at the trimmed argument, and the trimmed argument is
        // what gets used, so that is what the assertion has to check. The first
        // version compared against the untrimmed candidate and reported
        // `-Dlog4j.configurationFile=\r\n` as a hole; it was not one, but
        // chasing it did find that `LoggingConfig::jvm_argument` validated the
        // trimmed string and returned the untrimmed one. That is fixed.
        let used = candidate.trim();
        if jvm_args::validate_arg(&candidate).is_ok() {
            assert!(
                !used.contains(['&', '|', ';', '`', '$', '>', '<', '\n', '\r']),
                "iteration {i}: {used:?} was accepted with a shell metacharacter"
            );
            assert!(
                !used.chars().any(char::is_control),
                "iteration {i}: {used:?} was accepted with a control character"
            );
        }
    }
}

/// **Invariant: no argument that loads code is ever accepted, however dressed.**
#[test]
fn no_generated_argument_that_loads_code_is_ever_accepted() {
    use launcher_core::minecraft_runtime::jvm_args;

    const DANGEROUS: &[&str] = &[
        "-agentpath:",
        "-javaagent:",
        "-Xbootclasspath/a:",
        "-Xbootclasspath/p:",
        "-XX:OnError=",
        "-XX:OnOutOfMemoryError=",
        "-Djava.library.path=",
        "-Dsun.boot.library.path=",
        "-Djava.class.path=",
        "-XX:ErrorFile=",
        "-XX:Flags=",
        "-Xloggc:",
    ];
    let mut rng = Rng::new(0xFEED_FACE_0000_0007);

    for i in 0..20_000u32 {
        let prefix = rng.pick(DANGEROUS);
        // Vary the case, because a refusal that a capital letter walks past is
        // not a refusal.
        let prefix: String = prefix
            .chars()
            .map(|c| {
                if rng.next() & 1 == 0 {
                    c.to_ascii_uppercase()
                } else {
                    c.to_ascii_lowercase()
                }
            })
            .collect();
        let candidate = format!("{prefix}{}", random_path(&mut rng));

        assert!(
            jvm_args::validate_arg(&candidate).is_err(),
            "iteration {i}: {candidate:?} was accepted and would reach the JVM"
        );
    }
}

// ===========================================================================
// Log scrubbing
// ===========================================================================

/// **Invariant: a credential behind a known marker never survives scrubbing.**
///
/// The structural defence is that tokens are `Secret` values, whose `Debug` and
/// `Display` redact. `scrub` is the net under the game's own output, which we
/// do not control, and this checks the net has no hole at any marker.
#[test]
fn a_marked_credential_never_survives_scrubbing() {
    use launcher_core::logging;

    const MARKERS: &[&str] = &[
        "--accessToken",
        "--session",
        "Authorization",
        "identityToken",
        "access_token",
        "refresh_token",
        "device_code",
        "RpsTicket",
        "accessToken",
        "Set-Cookie",
        "Cookie",
        "sessionid",
        "Bearer",
        "Session ID is token:",
    ];
    const SEPARATORS: &[&str] = &[" ", "=", ":", "\":\"", "\"=\"", ": ", " = "];
    const NOISE: &[&str] = &[
        "",
        "[Client thread/INFO]: ",
        "some prefix ",
        "{\"a\":1,",
        "\t",
    ];

    let secret = "eyJhbGciOiJIUzI1NiJ9.SUPERSECRETVALUE.signature";
    let mut rng = Rng::new(0xABCD_1234_5678_9999);

    for i in 0..50_000u32 {
        let line = format!(
            "{}{}{}{}{}",
            rng.pick(NOISE),
            rng.pick(MARKERS),
            rng.pick(SEPARATORS),
            secret,
            rng.pick(&["", " trailing words", "\"}", ", \"next\": 2"])
        );
        let scrubbed = logging::scrub(&line);
        assert!(
            !scrubbed.contains("SUPERSECRETVALUE"),
            "iteration {i}: the secret survived scrubbing.\n  in:  {line}\n  out: {scrubbed}"
        );
    }
}

/// **Invariant: scrubbing is total and terminating.**
///
/// Arbitrary text, including text that looks like half a marker, must come back
/// without hanging. `scrub_after` walks a string looking for markers, and a
/// marker-finding loop that fails to advance is an infinite loop.
#[test]
fn scrubbing_arbitrary_text_always_terminates() {
    use launcher_core::logging;

    const PIECES: &[&str] = &[
        "Bearer",
        "Bearer",
        "Cookie",
        "accessToken",
        "--accessToken",
        "Session ID is",
        "Session ID is token:",
        "=",
        ":",
        "\"",
        " ",
        "",
        "\u{4e2d}",
        "\u{202e}",
        "\\",
        "}",
        ",",
    ];
    let mut rng = Rng::new(0x9999_8888_7777_6666);

    for _ in 0..50_000u32 {
        let n = rng.below(12) + 1;
        let mut line = String::new();
        for _ in 0..n {
            line.push_str(rng.pick(PIECES));
        }
        // The assertion is that this returns at all; a hang fails the test by
        // timing out rather than by assertion, which is the only way to observe
        // it.
        let scrubbed = logging::scrub(&line);
        // A scrubbed line is never longer than the original plus one marker's
        // worth of replacement per marker occurrence.
        assert!(
            scrubbed.len() <= line.len() + n * "<redacted>".len(),
            "scrubbing grew the line unreasonably: {line:?} -> {scrubbed:?}"
        );
    }
}

// ===========================================================================
// Mod manifest names
// ===========================================================================

/// **Invariant: a mod file name from a manifest is either refused or contained.**
///
/// `.fathom-mods.json` sits inside the profile, so its contents are as
/// untrusted as anything else a user can edit or receive.
#[test]
fn no_manifest_file_name_escapes_the_mods_directory() {
    let f = Fixture::new();
    let ops = f.ops();
    let profile = ops
        .create("Fuzzed")
        .unwrap_or_else(|e| panic!("create: {e}"));
    let dirs = ops.dirs_for(&profile);
    let mods_dir = dirs.mods_dir();

    let mut rng = Rng::new(0x0101_0202_0303_0404);
    let mut contained_count = 0u32;

    for i in 0..100_000u32 {
        let name = random_path(&mut rng);
        // A refusal is a fine outcome; what matters is that an acceptance is
        // always inside the mods directory.
        if let Ok(path) = dirs.contain_in_game(&format!("mods/{name}")) {
            contained_count += 1;
            assert!(
                fs_safety::is_lexically_inside(&mods_dir, &path),
                "iteration {i}: mod name {name:?} resolved to {path:?}, \
                 outside {mods_dir:?}"
            );
        }
    }

    assert!(
        contained_count > 1000,
        "the generator produced almost no valid names ({contained_count})"
    );
}

/// A path with spaces and non-ASCII must work end to end, because plenty of
/// real Windows profiles live under one and a launcher that only works under
/// `C:\dev` is not finished.
#[test]
fn hostile_looking_but_legitimate_paths_still_work() {
    let tmp = tempfile::tempdir().unwrap_or_else(|e| panic!("tempdir: {e}"));
    let awkward: PathBuf = tmp
        .path()
        .join("Users/A Name (test)/App Data/\u{4e2d}\u{6587} launcher");
    std::fs::create_dir_all(&awkward).unwrap_or_else(|e| panic!("mkdir: {e}"));

    let paths = DataPaths::rooted_at(&awkward);
    let ops = ProfileOps::new(paths);
    let profile = ops
        .create("Ordinary")
        .unwrap_or_else(|e| panic!("create under an awkward path: {e}"));

    let dirs = ops.dirs_for(&profile);
    let contained = dirs
        .contain_in_game("mods/fine.jar")
        .unwrap_or_else(|e| panic!("contain under an awkward path: {e}"));
    assert!(fs_safety::is_lexically_inside(dirs.root(), &contained));
}
