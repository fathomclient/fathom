//! Building the launch command.
//!
//! Four properties this module is responsible for:
//!
//! - **No shell.** The command is an argument vector handed to
//!   `std::process::Command`. There is no string that a shell ever parses, so
//!   there is no shell injection surface. See [`super::process`].
//! - **No argument injection.** The legacy `minecraftArguments` template is
//!   tokenised *before* substitution, so a value containing whitespace becomes
//!   one argument rather than several.
//! - **Structural redaction.** An argument built from a secret is an
//!   [`Argument::Sensitive`], which carries its redacted rendering alongside the
//!   real one. Rendering never searches for the token in a string, so it cannot
//!   miss an occurrence, and `Debug` on [`LaunchPlan`] renders the redacted form
//!   rather than being unsafe to log.
//! - **Immutability.** A `LaunchPlan` has no public fields and no setters. It is
//!   produced whole by [`build_plan`] and can only be read.
//!
//! The access token is still visible in the OS process list, because 1.8.9
//! takes it as `--accessToken` on the command line. That is inherent to vanilla
//! and is documented in `THREAT_MODEL.md`; whether a controlled Legacy Fabric
//! bootstrap could pass it out of band is an open question written up in
//! `docs/research/token-exposure.md`.

use std::collections::BTreeMap;
use std::fmt;
use std::path::{Path, PathBuf};

use crate::secret::Secret;

#[derive(Debug, thiserror::Error)]
pub enum LaunchError {
    #[error("the version metadata has no launch arguments")]
    NoArguments,
    #[error("unresolved placeholder {0} in the launch arguments")]
    UnresolvedPlaceholder(String),
    #[error("the classpath is empty, so there is nothing to run")]
    EmptyClasspath,
    #[error("the main class is missing from the version metadata")]
    NoMainClass,
}

// ---------------------------------------------------------------------------
// Arguments
// ---------------------------------------------------------------------------

/// One element of the child's `argv`.
///
/// Splitting plain from sensitive is what makes redaction a property of the
/// type rather than of a search-and-replace pass. A `Sensitive` argument cannot
/// be rendered in the clear, because the only rendering method returns
/// `redacted`.
#[derive(Clone, PartialEq, Eq)]
pub enum Argument {
    Plain(String),
    Sensitive {
        /// The real value handed to the process.
        actual: Secret,
        /// What a human or a log file is allowed to see instead.
        redacted: String,
    },
}

impl Argument {
    /// The value the process receives. Deliberately the only way to get at a
    /// secret argument in the clear, and deliberately easy to grep for.
    pub fn expose(&self) -> &str {
        match self {
            Argument::Plain(v) => v,
            Argument::Sensitive { actual, .. } => actual.expose(),
        }
    }

    /// What may be displayed, logged, or written to disk.
    pub fn redacted(&self) -> &str {
        match self {
            Argument::Plain(v) => v,
            Argument::Sensitive { redacted, .. } => redacted,
        }
    }

    pub fn is_sensitive(&self) -> bool {
        matches!(self, Argument::Sensitive { .. })
    }
}

/// Renders redacted, always. There is no `Debug` path that reveals a secret.
impl fmt::Debug for Argument {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:?}", self.redacted())
    }
}

impl fmt::Display for Argument {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.redacted())
    }
}

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

/// One placeholder's value, and whether it may be shown.
#[derive(Clone)]
struct Value {
    real: String,
    redacted: String,
    sensitive: bool,
}

impl Value {
    fn plain(v: impl Into<String>) -> Self {
        let v = v.into();
        Self {
            redacted: v.clone(),
            real: v,
            sensitive: false,
        }
    }

    fn secret(v: &Secret) -> Self {
        Self {
            real: v.expose().to_owned(),
            redacted: "<redacted>".to_owned(),
            sensitive: true,
        }
    }
}

/// Everything the game needs to know about this session.
#[derive(Debug, Clone)]
pub struct LaunchContext {
    pub player_name: String,
    pub player_uuid: String,
    pub access_token: Secret,
    /// `msa` for Microsoft accounts, `legacy` for a development identity.
    pub user_type: String,
    pub version_name: String,
    pub version_type: String,
    pub game_dir: PathBuf,
    pub assets_dir: PathBuf,
    pub assets_index_name: String,
}

impl LaunchContext {
    fn placeholders(&self) -> BTreeMap<&'static str, Value> {
        let mut map = BTreeMap::new();
        map.insert("auth_player_name", Value::plain(&self.player_name));
        map.insert("auth_uuid", Value::plain(&self.player_uuid));
        map.insert("auth_access_token", Value::secret(&self.access_token));
        // 1.7-era templates use this instead; it embeds the same token inside a
        // longer string, which is exactly why redaction is per-argument rather
        // than a search for the bare token.
        map.insert(
            "auth_session",
            Value {
                real: format!("token:{}", self.access_token.expose()),
                redacted: "token:<redacted>".to_owned(),
                sensitive: true,
            },
        );
        map.insert("user_type", Value::plain(&self.user_type));
        map.insert("version_name", Value::plain(&self.version_name));
        map.insert("version_type", Value::plain(&self.version_type));
        map.insert("game_directory", Value::plain(display(&self.game_dir)));
        map.insert("assets_root", Value::plain(display(&self.assets_dir)));
        map.insert("game_assets", Value::plain(display(&self.assets_dir)));
        map.insert("assets_index_name", Value::plain(&self.assets_index_name));
        // Vanilla expects a JSON object here. An empty one is correct and is
        // what the official launcher sends for accounts with no properties.
        map.insert("user_properties", Value::plain("{}"));
        map.insert("clientid", Value::plain(""));
        map.insert("auth_xuid", Value::plain(""));
        map
    }
}

fn display(path: &Path) -> String {
    path.display().to_string()
}

// ---------------------------------------------------------------------------
// The plan
// ---------------------------------------------------------------------------

/// What [`build_plan`] needs that does not come from the session.
#[derive(Debug, Clone)]
pub struct LaunchSpec<'a> {
    pub java: &'a Path,
    pub java_major: u32,
    pub jvm_args: Vec<String>,
    pub classpath: &'a [PathBuf],
    pub classpath_separator: &'static str,
    pub main_class: &'a str,
    pub argument_template: Option<&'a str>,
    /// Arguments a loader adds after the vanilla ones.
    ///
    /// Appended literally rather than run through the template substitution:
    /// these come from a loader profile, not from Mojang's argument string, and
    /// they contain no `${placeholders}`. Passing them through `substitute`
    /// would make an unrecognised `${` in a loader's argument a launch failure
    /// for no benefit.
    ///
    /// They are still `Argument`s, so they are covered by the same redaction
    /// machinery as everything else on the command line.
    pub extra_game_args: &'a [String],
    /// The profile's own root, which is the parent of everything below.
    pub profile_dir: &'a Path,
    pub natives_dir: &'a Path,
    /// Where the launcher writes the game's captured stdout and stderr.
    pub log_file: &'a Path,
}

/// A fully resolved, validated command, not yet run.
///
/// Immutable by construction: no public fields, no setters. Everything about
/// the launch is decided before this exists, so nothing can be adjusted between
/// validation and spawning.
#[derive(Clone)]
pub struct LaunchPlan {
    java: PathBuf,
    java_major: u32,
    working_dir: PathBuf,
    profile_dir: PathBuf,
    natives_dir: PathBuf,
    log_file: PathBuf,
    main_class: String,
    classpath: Vec<PathBuf>,
    classpath_separator: &'static str,
    jvm_args: Vec<String>,
    game_args: Vec<Argument>,
    environment: BTreeMap<String, String>,
    removed_environment: Vec<&'static str>,
}

impl LaunchPlan {
    // -- the parts, individually addressable ------------------------------

    pub fn java(&self) -> &Path {
        &self.java
    }

    pub fn java_major(&self) -> u32 {
        self.java_major
    }

    /// The game's working directory: the profile's isolated `minecraft/`.
    pub fn working_dir(&self) -> &Path {
        &self.working_dir
    }

    /// The profile root, which contains `minecraft/`, `natives/` and `logs/`.
    pub fn profile_dir(&self) -> &Path {
        &self.profile_dir
    }

    pub fn natives_dir(&self) -> &Path {
        &self.natives_dir
    }

    /// Where the launcher writes the child's captured output.
    pub fn log_file(&self) -> &Path {
        &self.log_file
    }

    pub fn main_class(&self) -> &str {
        &self.main_class
    }

    pub fn classpath(&self) -> &[PathBuf] {
        &self.classpath
    }

    pub fn jvm_args(&self) -> &[String] {
        &self.jvm_args
    }

    /// The Minecraft arguments, still typed, so a caller cannot lose track of
    /// which ones are secret.
    pub fn game_args(&self) -> &[Argument] {
        &self.game_args
    }

    pub fn environment(&self) -> &BTreeMap<String, String> {
        &self.environment
    }

    /// Variables removed from the inherited environment before spawning.
    pub fn removed_environment(&self) -> &[&'static str] {
        &self.removed_environment
    }

    pub fn classpath_argument(&self) -> String {
        self.classpath
            .iter()
            .map(|p| p.display().to_string())
            .collect::<Vec<_>>()
            .join(self.classpath_separator)
    }

    // -- the assembled command --------------------------------------------

    /// The full argument vector, with secrets in the clear.
    ///
    /// The only caller that should ever need this is the spawner. It is named
    /// to be conspicuous in a diff, and there is no `Display` or `Debug` path
    /// that reaches it.
    pub fn argv_exposing_secrets(&self) -> Vec<String> {
        let mut argv = Vec::with_capacity(self.jvm_args.len() + self.game_args.len() + 3);
        argv.extend(self.jvm_args.iter().cloned());
        argv.push("-cp".to_owned());
        argv.push(self.classpath_argument());
        argv.push(self.main_class.clone());
        argv.extend(self.game_args.iter().map(|a| a.expose().to_owned()));
        argv
    }

    /// The same vector with every secret replaced. Safe everywhere.
    pub fn redacted_argv(&self) -> Vec<String> {
        let mut argv = Vec::with_capacity(self.jvm_args.len() + self.game_args.len() + 3);
        argv.extend(self.jvm_args.iter().cloned());
        argv.push("-cp".to_owned());
        argv.push(self.classpath_argument());
        argv.push(self.main_class.clone());
        argv.extend(self.game_args.iter().map(|a| a.redacted().to_owned()));
        argv
    }

    /// The command as a human would read it, with every secret removed.
    ///
    /// This is what the UI shows and what goes in the log. There is no
    /// unredacted rendering function.
    pub fn redacted_command_line(&self) -> String {
        std::iter::once(display(&self.java))
            .chain(self.redacted_argv())
            .map(|a| {
                if a.contains(' ') {
                    format!("\"{a}\"")
                } else {
                    a
                }
            })
            .collect::<Vec<_>>()
            .join(" ")
    }

    /// Whether any argument carries a secret. False would mean the token is not
    /// on the command line at all, which is the state the Legacy Fabric
    /// research in `docs/research/token-exposure.md` is aiming at.
    pub fn exposes_a_secret_argument(&self) -> bool {
        self.game_args.iter().any(Argument::is_sensitive)
    }

    /// The secret values this launch passes to the game.
    ///
    /// Needed by the output capture in [`super::process`]: the game echoes its
    /// own session id to stdout, so scrubbing captured output by marker alone
    /// is not enough -- the actual values have to be searched for too. Nothing
    /// else should call this.
    pub fn secret_values(&self) -> Vec<Secret> {
        self.game_args
            .iter()
            .filter_map(|a| match a {
                Argument::Sensitive { actual, .. } => Some(actual.clone()),
                Argument::Plain(_) => None,
            })
            .collect()
    }
}

/// Redacted, always. `LaunchPlan` is safe to log, print, and put in an error.
impl fmt::Debug for LaunchPlan {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LaunchPlan")
            .field("java", &self.java)
            .field("java_major", &self.java_major)
            .field("working_dir", &self.working_dir)
            .field("profile_dir", &self.profile_dir)
            .field("natives_dir", &self.natives_dir)
            .field("log_file", &self.log_file)
            .field("main_class", &self.main_class)
            .field("classpath_entries", &self.classpath.len())
            .field("jvm_args", &self.jvm_args)
            .field("game_args", &self.game_args)
            .field("environment", &self.environment)
            .field("removed_environment", &self.removed_environment)
            // Non-exhaustive on purpose: the classpath is 31 absolute paths on
            // 1.8.9 and printing it would bury everything else. Its length is
            // above, and `classpath()` returns it in full.
            .finish_non_exhaustive()
    }
}

/// Environment variables removed from the child's environment before spawning.
///
/// Removed rather than merged: the flag set in a `LaunchPlan` has been through
/// `jvm_args::validate`, and a value in the ambient environment would bypass
/// that entirely. A user who wants extra flags has a field for them in the
/// profile, where they are checked.
///
/// Three groups, and the second and third were added by the security pass:
///
/// 1. **JVM option injection.** `_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS` and
///    `JDK_JAVA_OPTIONS` are prepended to the argument list by the JVM itself.
///    An `_JAVA_OPTIONS=-agentpath:...` in the environment is the same
///    code-execution primitive `jvm_args` refuses on the command line, arriving
///    by a route that never passes through validation.
/// 2. **Dynamic-loader injection.** `LD_PRELOAD` and its relatives load a
///    shared object into the process before `main`; `DYLD_INSERT_LIBRARIES` is
///    the macOS equivalent. They are unix mechanisms and this launcher's first
///    target is Windows, but the code is cross-platform and the cost of
///    clearing them is one line each.
/// 3. **Classpath.** `CLASSPATH` is overridden by the `-cp` we pass, so this is
///    belt and braces rather than a hole being closed. It is here so that the
///    answer to "what classpath does the game get" is the plan and nothing else.
///
/// What is deliberately **not** removed is `PATH`. The game and the JVM need a
/// working environment, and a launcher that curates `PATH` would break more
/// than it protected; an attacker who can write to a directory on the user's
/// `PATH` already has code execution without involving us.
const SUPPRESSED_ENVIRONMENT: [&str; 8] = [
    // JVM option injection.
    "_JAVA_OPTIONS",
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    // Dynamic-loader injection.
    "LD_PRELOAD",
    "LD_LIBRARY_PATH",
    "LD_AUDIT",
    "DYLD_INSERT_LIBRARIES",
    // Belt and braces.
    "CLASSPATH",
];

/// Assemble the launch plan.
///
/// `spec.jvm_args` comes from [`super::jvm_args::build_jvm_args`], which has
/// already dropped anything the policy refuses.
/// The JVM to launch the game with: `javaw.exe` when there is one beside the
/// `java.exe` we resolved.
///
/// # Why this is not just `CREATE_NO_WINDOW`
///
/// The child is already created with `CREATE_NO_WINDOW` (see
/// `process::suppress_console_window`), and on a machine whose default terminal
/// is the classic console host that is enough. It is not enough everywhere:
/// with Windows Terminal set as the default terminal application, launching a
/// **console-subsystem** binary can still bring up a terminal window, because
/// the decision about whether to draw one moved out of that flag's reach.
///
/// `java.exe` is a console-subsystem binary. `javaw.exe` is the same JVM built
/// as a GUI-subsystem one, ships beside it in every JDK and JRE, and is what
/// Minecraft launchers have used for this reason for as long as there have been
/// Minecraft launchers. Redirected stdout and stderr still work -- the pipes
/// `process::spawn` installs are inherited exactly as before -- so nothing is
/// lost from the log or from the game console window.
///
/// Falls back to whatever was resolved when there is no `javaw.exe` beside it,
/// which is every non-Windows platform and an unusual Windows runtime. A
/// missing `javaw.exe` must never stop a launch that would otherwise work.
fn windowless_java(resolved: &Path) -> PathBuf {
    if !cfg!(windows) {
        return resolved.to_path_buf();
    }
    let Some(name) = resolved.file_name().and_then(|name| name.to_str()) else {
        return resolved.to_path_buf();
    };
    // Only ever java.exe -> javaw.exe. Anything else was chosen deliberately.
    if !name.eq_ignore_ascii_case("java.exe") {
        return resolved.to_path_buf();
    }
    let windowless = resolved.with_file_name("javaw.exe");
    if windowless.is_file() {
        windowless
    } else {
        resolved.to_path_buf()
    }
}

pub fn build_plan(
    spec: &LaunchSpec<'_>,
    context: &LaunchContext,
) -> Result<LaunchPlan, LaunchError> {
    let template = spec.argument_template.ok_or(LaunchError::NoArguments)?;
    if spec.classpath.is_empty() {
        return Err(LaunchError::EmptyClasspath);
    }
    if spec.main_class.trim().is_empty() {
        return Err(LaunchError::NoMainClass);
    }

    let placeholders = context.placeholders();

    // Tokenise first, substitute second. A player name of `a b --extra c`
    // becomes one argument, not four.
    let mut game_args = Vec::new();
    for token in template.split_whitespace() {
        game_args.push(substitute(token, &placeholders)?);
    }

    for extra in spec.extra_game_args {
        game_args.push(Argument::Plain(extra.clone()));
    }

    Ok(LaunchPlan {
        java: windowless_java(spec.java),
        java_major: spec.java_major,
        working_dir: context.game_dir.clone(),
        profile_dir: spec.profile_dir.to_path_buf(),
        natives_dir: spec.natives_dir.to_path_buf(),
        log_file: spec.log_file.to_path_buf(),
        main_class: spec.main_class.to_owned(),
        classpath: spec.classpath.to_vec(),
        classpath_separator: spec.classpath_separator,
        jvm_args: spec.jvm_args.clone(),
        game_args,
        // Nothing is added today. The field exists because the spawner needs a
        // single declared answer to "what environment does the game get", and
        // an empty map is that answer rather than an omission.
        environment: BTreeMap::new(),
        removed_environment: SUPPRESSED_ENVIRONMENT.to_vec(),
    })
}

/// Replace every `${name}` in `token`, producing both renderings at once.
///
/// An unknown placeholder is an error, not a silent empty string: launching
/// with a half-substituted argument produces confusing failures much later.
fn substitute(
    token: &str,
    values: &BTreeMap<&'static str, Value>,
) -> Result<Argument, LaunchError> {
    let mut real = String::with_capacity(token.len());
    let mut redacted = String::with_capacity(token.len());
    let mut sensitive = false;
    let mut rest = token;

    while let Some(start) = rest.find("${") {
        let literal = rest.get(..start).unwrap_or_default();
        real.push_str(literal);
        redacted.push_str(literal);

        let after = rest.get(start + 2..).unwrap_or_default();
        let Some(end) = after.find('}') else {
            // A literal `${` with no closing brace: keep it verbatim.
            let tail = rest.get(start..).unwrap_or_default();
            real.push_str(tail);
            redacted.push_str(tail);
            return Ok(finish(real, redacted, sensitive));
        };

        let name = after.get(..end).unwrap_or_default();
        let value = values
            .get(name)
            .ok_or_else(|| LaunchError::UnresolvedPlaceholder(name.to_owned()))?;
        real.push_str(&value.real);
        redacted.push_str(&value.redacted);
        sensitive |= value.sensitive;

        rest = after.get(end + 1..).unwrap_or_default();
    }

    real.push_str(rest);
    redacted.push_str(rest);
    Ok(finish(real, redacted, sensitive))
}

fn finish(real: String, redacted: String, sensitive: bool) -> Argument {
    if sensitive {
        Argument::Sensitive {
            actual: Secret::new(real),
            redacted,
        }
    } else {
        Argument::Plain(real)
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

    /// The real 1.8.9 template, verbatim from the version JSON.
    const TEMPLATE_1_8_9: &str = "--username ${auth_player_name} --version ${version_name} \
         --gameDir ${game_directory} --assetsDir ${assets_root} \
         --assetIndex ${assets_index_name} --uuid ${auth_uuid} \
         --accessToken ${auth_access_token} --userProperties ${user_properties} \
         --userType ${user_type}";

    const TOKEN: &str = "SUPER-SECRET-TOKEN";

    fn context() -> LaunchContext {
        LaunchContext {
            player_name: "Steve".into(),
            player_uuid: "986dec87b7ec47ff89ff033fdb95c4b5".into(),
            access_token: Secret::new(TOKEN),
            user_type: "msa".into(),
            version_name: "1.8.9".into(),
            version_type: "release".into(),
            game_dir: PathBuf::from("/data/profiles/main/minecraft"),
            assets_dir: PathBuf::from("/data/shared/assets"),
            assets_index_name: "1.8".into(),
        }
    }

    fn classpath() -> Vec<PathBuf> {
        vec![PathBuf::from("/libs/a.jar"), PathBuf::from("/libs/b.jar")]
    }

    fn spec<'a>(cp: &'a [PathBuf], template: Option<&'a str>) -> LaunchSpec<'a> {
        LaunchSpec {
            java: Path::new("/usr/bin/java"),
            java_major: 8,
            jvm_args: vec!["-Xms2048M".into(), "-Xmx2048M".into()],
            classpath: cp,
            classpath_separator: ":",
            main_class: "net.minecraft.client.main.Main",
            argument_template: template,
            extra_game_args: &[],
            profile_dir: Path::new("/data/profiles/main"),
            natives_dir: Path::new("/data/profiles/main/natives"),
            log_file: Path::new("/data/profiles/main/logs/launch.log"),
        }
    }

    /// A loader's game arguments are appended, in order, after the vanilla
    /// ones. Order matters: Fabric reads its arguments positionally in places,
    /// and prepending them would put them where the game expects a username.
    /// The launcher used to run `java.exe`, which is a console-subsystem
    /// binary; on a machine whose default terminal is Windows Terminal that can
    /// put a terminal window on screen at every launch even with
    /// `CREATE_NO_WINDOW` set.
    #[test]
    #[cfg(windows)]
    fn a_windows_launch_prefers_javaw_when_it_is_there() {
        let directory = tempfile::tempdir().expect("a temporary directory");
        let java = directory.path().join("java.exe");
        std::fs::write(&java, b"stub").expect("java.exe");

        // No javaw.exe beside it: the resolved runtime stands, because a
        // missing javaw must never stop a launch that would otherwise work.
        assert_eq!(windowless_java(&java), java);

        std::fs::write(directory.path().join("javaw.exe"), b"stub").expect("javaw.exe");
        assert_eq!(
            windowless_java(&java),
            directory.path().join("javaw.exe"),
            "javaw.exe beside java.exe should be preferred"
        );
    }

    /// Only that one substitution. A runtime somebody named something else was
    /// chosen deliberately and is not ours to second-guess.
    #[test]
    #[cfg(windows)]
    fn no_other_runtime_name_is_rewritten() {
        let directory = tempfile::tempdir().expect("a temporary directory");
        let odd = directory.path().join("jre-custom.exe");
        std::fs::write(&odd, b"stub").expect("the runtime");
        std::fs::write(directory.path().join("javaw.exe"), b"stub").expect("javaw.exe");

        assert_eq!(windowless_java(&odd), odd);
    }

    #[test]
    fn loader_game_arguments_are_appended_after_the_vanilla_ones() {
        let cp = classpath();
        let mut spec = spec(&cp, Some("--username ${auth_player_name}"));
        let extra = vec!["--fabric-flag".to_owned(), "value".to_owned()];
        spec.extra_game_args = &extra;

        let plan = build_plan(&spec, &context()).expect("a plan");
        let rendered = plan.redacted_command_line();
        let username_at = rendered.find("--username").expect("vanilla argument");
        let flag_at = rendered.find("--fabric-flag").expect("loader argument");
        assert!(
            username_at < flag_at,
            "loader arguments must come after the vanilla ones: {rendered}"
        );
        assert!(rendered.contains("--fabric-flag value"));
    }

    fn plan(ctx: &LaunchContext) -> LaunchPlan {
        let cp = classpath();
        build_plan(&spec(&cp, Some(TEMPLATE_1_8_9)), ctx).expect("plan should build")
    }

    #[test]
    fn produces_the_expected_argument_order() {
        let p = plan(&context());
        let argv = p.redacted_argv();
        let cp_index = argv.iter().position(|a| a == "-cp").unwrap();
        let main_index = argv
            .iter()
            .position(|a| a == "net.minecraft.client.main.Main")
            .unwrap();
        let user_index = argv.iter().position(|a| a == "--username").unwrap();

        assert!(argv[0].starts_with("-Xms"));
        assert!(cp_index < main_index, "-cp must precede the main class");
        assert!(
            main_index < user_index,
            "game args come after the main class"
        );
        assert_eq!(argv[cp_index + 1], "/libs/a.jar:/libs/b.jar");
    }

    #[test]
    fn every_part_of_the_plan_is_addressable() {
        let p = plan(&context());
        assert_eq!(p.java(), Path::new("/usr/bin/java"));
        assert_eq!(p.java_major(), 8);
        assert_eq!(p.main_class(), "net.minecraft.client.main.Main");
        assert_eq!(p.classpath().len(), 2);
        assert_eq!(p.working_dir(), Path::new("/data/profiles/main/minecraft"));
        assert_eq!(p.profile_dir(), Path::new("/data/profiles/main"));
        assert_eq!(p.natives_dir(), Path::new("/data/profiles/main/natives"));
        assert_eq!(
            p.log_file(),
            Path::new("/data/profiles/main/logs/launch.log")
        );
        assert!(p.jvm_args().iter().any(|a| a.starts_with("-Xmx")));
        assert!(!p.game_args().is_empty());
        assert!(p.environment().is_empty());
    }

    /// A flag injected through the environment would never have been through
    /// `jvm_args::validate`, so the plan declares it removed rather than merged.
    #[test]
    fn jvm_option_environment_variables_are_suppressed() {
        let p = plan(&context());
        for name in [
            "_JAVA_OPTIONS",
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            // Added by the security pass: each of these loads code into the
            // process before `main`, without passing `jvm_args::validate_arg`.
            "LD_PRELOAD",
            "LD_LIBRARY_PATH",
            "LD_AUDIT",
            "DYLD_INSERT_LIBRARIES",
            "CLASSPATH",
        ] {
            assert!(
                p.removed_environment().contains(&name),
                "{name} must not reach the child"
            );
        }
    }

    #[test]
    fn placeholders_are_substituted() {
        let ctx = context();
        let p = plan(&ctx);
        let joined = p.redacted_argv().join(" ");
        assert!(joined.contains("--username Steve"));
        assert!(joined.contains("--uuid 986dec87b7ec47ff89ff033fdb95c4b5"));
        assert!(joined.contains("--assetIndex 1.8"));
        assert!(joined.contains("--userType msa"));
        assert!(joined.contains("--userProperties {}"));
        assert!(!joined.contains("${"), "no placeholder may survive");
    }

    // -- token exposure ----------------------------------------------------

    #[test]
    fn the_token_reaches_the_process_but_nothing_else() {
        let p = plan(&context());

        // It is present in the real argv, because vanilla requires it there.
        assert!(p.argv_exposing_secrets().iter().any(|a| a == TOKEN));
        assert!(p.exposes_a_secret_argument());

        // And in nothing a human, a log file, or an error message ever sees.
        for rendering in [
            p.redacted_command_line(),
            p.redacted_argv().join(" "),
            format!("{p:?}"),
            format!("{:?}", p.game_args()),
        ] {
            assert!(
                !rendering.contains(TOKEN),
                "token leaked into a rendering: {rendering}"
            );
        }
        assert!(p
            .redacted_command_line()
            .contains("--accessToken <redacted>"));
        assert!(p.redacted_command_line().contains("--username Steve"));
    }

    /// The regression this type exists to prevent: a token embedded inside a
    /// longer argument. A search-and-replace renderer would have to find the
    /// token inside `token:...`; a typed one cannot miss it.
    #[test]
    fn a_token_embedded_in_a_larger_argument_is_still_redacted() {
        let ctx = context();
        let cp = classpath();
        let p = build_plan(&spec(&cp, Some("--session ${auth_session}")), &ctx).unwrap();

        assert_eq!(p.game_args()[1].expose(), format!("token:{TOKEN}"));
        assert_eq!(p.game_args()[1].redacted(), "token:<redacted>");
        assert!(!p.redacted_command_line().contains(TOKEN));
        assert!(!format!("{p:?}").contains(TOKEN));
    }

    /// `Debug` is the trap this design closes: the old plan held raw strings, so
    /// deriving `Debug` would have printed the token into any error chain.
    #[test]
    fn debug_of_the_whole_plan_is_safe_to_log() {
        let rendered = format!("{:?}", plan(&context()));
        assert!(!rendered.contains(TOKEN));
        assert!(rendered.contains("LaunchPlan"));
        // The non-secret parts are still useful for diagnosis.
        assert!(rendered.contains("net.minecraft.client.main.Main"));
    }

    #[test]
    fn an_argument_that_is_not_secret_is_not_marked_as_one() {
        let p = plan(&context());
        let username = p
            .game_args()
            .iter()
            .position(|a| a.redacted() == "--username")
            .unwrap();
        assert!(!p.game_args()[username + 1].is_sensitive());
        assert_eq!(p.game_args()[username + 1].expose(), "Steve");
    }

    // -- injection ---------------------------------------------------------

    #[test]
    fn a_hostile_player_name_cannot_inject_extra_arguments() {
        // Tokenise-then-substitute is what makes this safe. If we substituted
        // into the template string and split afterwards, this name would add
        // three arguments including a second --accessToken.
        let mut ctx = context();
        ctx.player_name = "evil --accessToken stolen --gameDir /etc".into();

        let p = plan(&ctx);
        let argv = p.redacted_argv();
        let at = argv.iter().position(|a| a == "--username").unwrap();
        assert_eq!(
            argv[at + 1],
            "evil --accessToken stolen --gameDir /etc",
            "the whole name must land in exactly one argument"
        );
        assert_eq!(argv.iter().filter(|a| *a == "--accessToken").count(), 1);
        assert_eq!(argv.iter().filter(|a| *a == "--gameDir").count(), 1);
    }

    #[test]
    fn a_hostile_game_directory_cannot_inject_arguments_either() {
        let mut ctx = context();
        ctx.game_dir = PathBuf::from("/tmp/a b --username Mallory");
        let p = plan(&ctx);
        let argv = p.redacted_argv();
        let at = argv.iter().position(|a| a == "--gameDir").unwrap();
        assert_eq!(argv[at + 1], "/tmp/a b --username Mallory");
        assert_eq!(argv.iter().filter(|a| *a == "--username").count(), 1);
    }

    // -- Windows-shaped paths ---------------------------------------------

    #[test]
    fn windows_paths_with_spaces_survive_as_single_arguments() {
        let mut ctx = context();
        ctx.game_dir =
            PathBuf::from(r"C:\Users\Jane Doe\AppData\Local\Fathom\profiles\my profile\minecraft");
        ctx.assets_dir = PathBuf::from(r"C:\Users\Jane Doe\AppData\Local\Fathom\shared\assets");

        let cp = vec![
            PathBuf::from(r"C:\Users\Jane Doe\libs\a b.jar"),
            PathBuf::from(r"C:\Users\Jane Doe\libs\c.jar"),
        ];
        let mut s = spec(&cp, Some(TEMPLATE_1_8_9));
        s.classpath_separator = ";";
        let p = build_plan(&s, &ctx).unwrap();

        let argv = p.redacted_argv();
        let at = argv.iter().position(|a| a == "--gameDir").unwrap();
        assert_eq!(argv[at + 1], ctx.game_dir.display().to_string());

        // The classpath is one argument, separated by `;` on Windows, and the
        // spaces inside an entry do not split it.
        let cp_at = argv.iter().position(|a| a == "-cp").unwrap();
        assert_eq!(
            argv[cp_at + 1],
            r"C:\Users\Jane Doe\libs\a b.jar;C:\Users\Jane Doe\libs\c.jar"
        );
        assert_eq!(p.classpath().len(), 2);
    }

    /// Quoting is a *rendering* concern only. The argv itself must stay
    /// unquoted, or the JVM receives literal quote characters in the path.
    #[test]
    fn quoting_happens_only_in_the_rendered_line_never_in_argv() {
        let mut ctx = context();
        ctx.game_dir = PathBuf::from(r"C:\Program Files\Fathom\profiles\main\minecraft");
        let p = plan(&ctx);

        let argv = p.argv_exposing_secrets();
        let at = argv.iter().position(|a| a == "--gameDir").unwrap();
        assert!(!argv[at + 1].contains('"'), "argv must not be quoted");

        let line = p.redacted_command_line();
        assert!(
            line.contains(r#""C:\Program Files\Fathom\profiles\main\minecraft""#),
            "the rendered line should quote it: {line}"
        );
    }

    #[test]
    fn special_characters_in_a_player_name_are_passed_through_unchanged() {
        for name in [
            "Ünïcødé_Playér",
            "player&name",
            "name|with|pipes",
            "quote\"inside",
            "percent%name%",
            "back\\slash",
            "semi;colon",
            "dollar$sign",
            "back`tick",
            "new\nline",
        ] {
            let mut ctx = context();
            ctx.player_name = name.to_owned();
            let p = plan(&ctx);
            let argv = p.argv_exposing_secrets();
            let at = argv.iter().position(|a| a == "--username").unwrap();
            assert_eq!(
                argv[at + 1],
                name,
                "the name must reach the process byte for byte"
            );
        }
    }

    #[test]
    fn a_uuid_with_dashes_or_without_is_passed_through_verbatim() {
        for uuid in [
            "986dec87b7ec47ff89ff033fdb95c4b5",
            "986dec87-b7ec-47ff-89ff-033fdb95c4b5",
        ] {
            let mut ctx = context();
            ctx.player_uuid = uuid.to_owned();
            let p = plan(&ctx);
            let argv = p.redacted_argv();
            let at = argv.iter().position(|a| a == "--uuid").unwrap();
            assert_eq!(argv[at + 1], uuid);
        }
    }

    // -- validation --------------------------------------------------------

    #[test]
    fn an_unknown_placeholder_is_an_error_not_an_empty_string() {
        let cp = classpath();
        let err = build_plan(
            &spec(&cp, Some("--thing ${not_a_real_placeholder}")),
            &context(),
        )
        .expect_err("unknown placeholder must fail");
        assert!(
            matches!(err, LaunchError::UnresolvedPlaceholder(name) if name == "not_a_real_placeholder")
        );
    }

    #[test]
    fn missing_arguments_are_an_error() {
        let cp = classpath();
        let err = build_plan(&spec(&cp, None), &context()).expect_err("missing template");
        assert!(matches!(err, LaunchError::NoArguments));
    }

    #[test]
    fn an_empty_classpath_is_refused_rather_than_producing_a_broken_command() {
        let err = build_plan(&spec(&[], Some(TEMPLATE_1_8_9)), &context())
            .expect_err("empty classpath must fail");
        assert!(matches!(err, LaunchError::EmptyClasspath));
    }

    #[test]
    fn a_blank_main_class_is_refused() {
        let cp = classpath();
        let mut s = spec(&cp, Some(TEMPLATE_1_8_9));
        s.main_class = "   ";
        assert!(matches!(
            build_plan(&s, &context()),
            Err(LaunchError::NoMainClass)
        ));
    }

    #[test]
    fn substitute_handles_adjacent_and_partial_placeholders() {
        let mut values = BTreeMap::new();
        values.insert("a", Value::plain("1"));
        values.insert("b", Value::plain("2"));

        assert_eq!(substitute("${a}${b}", &values).unwrap().expose(), "12");
        assert_eq!(substitute("x${a}y", &values).unwrap().expose(), "x1y");
        assert_eq!(substitute("plain", &values).unwrap().expose(), "plain");
        // Unterminated: kept verbatim rather than swallowing the rest.
        assert_eq!(
            substitute("${unterminated", &values).unwrap().expose(),
            "${unterminated"
        );
    }

    /// Two placeholders in one token, one of them secret, must taint the whole
    /// argument rather than only the part that came from the secret.
    #[test]
    fn one_secret_placeholder_makes_the_whole_argument_sensitive() {
        let mut values = BTreeMap::new();
        values.insert("name", Value::plain("Steve"));
        values.insert("tok", Value::secret(&Secret::new("abc")));

        let arg = substitute("${name}:${tok}", &values).unwrap();
        assert!(arg.is_sensitive());
        assert_eq!(arg.expose(), "Steve:abc");
        assert_eq!(arg.redacted(), "Steve:<redacted>");
    }
}
