//! The local bridge between a running game and the launcher.
//!
//! # What this is for
//!
//! A handful of small things: bring the launcher to the front, choose which
//! profile launches next, ask it to relaunch into one, start a Microsoft
//! sign-in, ask who is signed in, and say hello. That is the whole protocol,
//! and it is the whole protocol on purpose — see below.
//!
//! # What never crosses it
//!
//! **No access token, no refresh token, no Xbox or XSTS token, no device or
//! authorization code, no session id, no Microsoft email, in either
//! direction.** [`Command`] is a closed enum and a reply is one line of text the
//! launcher composes.
//!
//! The one piece of identity that does cross is the *public* Minecraft profile
//! — a username and a UUID — in answer to [`Command::Account`]. It is what
//! every server the player joins already sees, and the game already holds it
//! for its own session; the verb exists so the menu of a game that was not
//! given a real session can show the account the launcher has signed in, and
//! offer to sign in through the launcher when it has none.
//!
//! That is a stronger guarantee than a policy, and it is the reason the
//! protocol is an enum rather than a general RPC.
//!
//! # How it is authenticated, and what that is worth
//!
//! On launch the launcher binds a listener to loopback, generates a random
//! per-session token, and writes both into `fathom/bridge.json` inside that
//! game's own directory. The module reads the file and presents the token.
//!
//! The honest description of that: the secret lives somewhere the game process
//! can already read and write, so anything that can steal it could already put a
//! jar in the mods folder — a much better attack. What the token actually stops
//! is **another program on the same machine** finding an open port and driving
//! the launcher: a different game, a page probing localhost, anything that did
//! not start from this profile's directory. It is per session, so it is
//! worthless a moment after the game exits.
//!
//! # Why loopback and not a named pipe
//!
//! A named pipe is the better Windows answer and would not port. This has to
//! work the same way on three platforms with one implementation, and a TCP
//! listener bound to `127.0.0.1` is refused by the operating system for anything
//! off-machine. `0.0.0.0` is never used and cannot be configured.

use std::fmt::Write as _;
use std::io::{BufRead, BufReader, Write};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use crate::secret::Secret;

/// Where the handshake goes, relative to the game directory. Must match
/// `LauncherBridge.HANDSHAKE_PATH` on the Java side.
pub const HANDSHAKE_PATH: &str = "fathom/bridge.json";

/// Format version. Must match `LauncherBridge.SCHEMA`.
pub const SCHEMA: u32 = 1;

/// How long a connection may take. A local client that has not finished
/// speaking in this long is not a local client.
const TIMEOUT: Duration = Duration::from_millis(500);

/// The most a request may be. Two lines: a token and a short command.
const MAX_REQUEST: u64 = 512;

/// Everything the game may ask for.
///
/// A closed enum, deliberately. Adding a verb is adding a variant here, in the
/// launcher, which is the only place that should be able to decide what a mod
/// running in a game is allowed to make the launcher do.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    /// Are you there?
    Ping,
    /// Bring the launcher's window to the front.
    Focus,
    /// Select the profile that will be used the next time Play is pressed.
    NextProfile(String),
    /// Open the launcher's diagnostics page.
    ///
    /// The one verb added for the Hub's own Diagnostics: somebody looking at
    /// why the game is behaving badly should be able to reach the launcher's
    /// half of the same picture without alt-tabbing and navigating. It brings
    /// the window forward and selects that page; it reads nothing back, because
    /// a reply is one line and this protocol has no "get".
    Diagnostics,
    /// Start the launcher's Microsoft sign-in, the same flow its own Sign In
    /// button starts, and bring its window forward. When an account is already
    /// signed in, only shows it.
    SignIn,
    /// Who is signed in: the public profile, or the state of the sign-in.
    Account,
    /// Public profiles remembered by the launcher, for the in-game picker.
    Accounts,
    /// Make a remembered account active for the next launch.
    SwitchAccount(String),
    /// Select a profile and ask to relaunch into it.
    ///
    /// The launcher decides whether to act. The module never terminates the
    /// game itself: a mod that could kill the process it runs inside is one bad
    /// button away from taking somebody's session with it.
    RestartInto(String),
}

impl Command {
    /// Parse one request line.
    ///
    /// Refuses anything it does not recognise rather than guessing, and bounds
    /// the argument: a profile id is a short slug, and something longer than one
    /// is not a profile id however it is spelled.
    fn parse(line: &str) -> Option<Command> {
        let line = line.trim();
        if line.len() > 128 {
            return None;
        }
        let (verb, rest) = match line.split_once(' ') {
            Some((verb, rest)) => (verb, rest.trim()),
            None => (line, ""),
        };
        match verb {
            "ping" => Some(Command::Ping),
            "focus" => Some(Command::Focus),
            "diagnostics" => Some(Command::Diagnostics),
            "sign-in" if rest.is_empty() => Some(Command::SignIn),
            "account" if rest.is_empty() => Some(Command::Account),
            "accounts" if rest.is_empty() => Some(Command::Accounts),
            "switch-account" if is_account_id(rest) => {
                Some(Command::SwitchAccount(rest.to_owned()))
            }
            "next-profile" if is_id(rest) => Some(Command::NextProfile(rest.to_owned())),
            "restart-into" if is_id(rest) => Some(Command::RestartInto(rest.to_owned())),
            _ => None,
        }
    }
}

fn is_account_id(value: &str) -> bool {
    value.len() == 32 && value.chars().all(|c| c.is_ascii_hexdigit())
}

/// Whether something is shaped like a profile id.
///
/// The same alphabet `ProfileId` accepts, checked here as well rather than
/// relying on the lookup to fail: a string that is not an id should be refused
/// at the boundary, not carried inward to be refused later.
fn is_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
}

/// What the launcher says back.
///
/// One line. `ok` and an optional word, or `no` and a reason. Deliberately not
/// structured: everything the game does with a reply is decide whether it
/// worked, and a format with fields is a format that grows fields.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Reply {
    Ok(String),
    No(String),
}

impl Reply {
    pub fn ok() -> Self {
        Reply::Ok(String::new())
    }

    fn line(&self) -> String {
        match self {
            Reply::Ok(detail) if detail.is_empty() => "ok\n".to_owned(),
            Reply::Ok(detail) => format!("ok {detail}\n"),
            Reply::No(reason) => format!("no {reason}\n"),
        }
    }
}

/// What the launcher tells the game about itself, once, in the handshake.
///
/// Facts about the installation and the profile list, and nothing about the
/// account. `profiles` exists so the in-game profile picker has something to
/// show without a round trip per name.
#[derive(Debug, Clone)]
pub struct Handshake {
    /// The launcher executable, so the module can start it if it has been closed.
    pub launcher_path: Option<PathBuf>,
    pub launcher_version: String,
    /// The profile this game was started with.
    pub profile_id: String,
    pub profile_name: String,
    /// The background the launcher is showing, so the hub can match it.
    pub theme: String,
    /// Every profile, as (id, display name).
    pub profiles: Vec<(String, String)>,
}

/// A running bridge. Dropping it stops the listener.
pub struct Bridge {
    port: u16,
    running: Arc<AtomicBool>,
    handshake_file: PathBuf,
}

impl std::fmt::Debug for Bridge {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // The token is deliberately absent. A `Debug` that printed it would put
        // it in a log the moment anything logged this struct.
        f.debug_struct("Bridge")
            .field("port", &self.port)
            .field("running", &self.running.load(Ordering::Relaxed))
            .finish_non_exhaustive()
    }
}

impl Bridge {
    /// Which port it ended up on. Chosen by the operating system.
    pub fn port(&self) -> u16 {
        self.port
    }

    /// Stop listening and remove the handshake.
    ///
    /// Called when the game exits. Leaving the file behind would mean the next
    /// game to start in that directory read a token for a listener that is gone
    /// — which is harmless and looks like a broken bridge.
    pub fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
        // Waking the accept loop by connecting to it, so the thread exits now
        // rather than at the next connection. Failure is fine: it means nothing
        // is listening, which is the state being asked for.
        let _ = TcpStream::connect(SocketAddr::V4(SocketAddrV4::new(
            Ipv4Addr::LOCALHOST,
            self.port,
        )));
        let _ = std::fs::remove_file(&self.handshake_file);
    }
}

impl Drop for Bridge {
    fn drop(&mut self) {
        self.stop();
    }
}

#[derive(Debug, thiserror::Error)]
pub enum BridgeError {
    #[error("could not listen on loopback: {0}")]
    Listen(#[source] std::io::Error),
    #[error("could not write the handshake at {path}: {source}")]
    Handshake {
        path: String,
        #[source]
        source: std::io::Error,
    },
}

/// Start a bridge for one game.
///
/// The handler runs on the listener thread, once per accepted command. It must
/// not block for long: a game pressing "Open Fathom" is waiting on it, with a
/// quarter-second timeout on its end.
pub fn start<H>(
    game_directory: &Path,
    handshake: &Handshake,
    handler: H,
) -> Result<Bridge, BridgeError>
where
    H: Fn(Command) -> Reply + Send + Sync + 'static,
{
    // Port zero asks the operating system for a free one, which is the only way
    // to avoid both a fixed port two launchers would fight over and a scan.
    let listener = TcpListener::bind(SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 0)))
        .map_err(BridgeError::Listen)?;
    let port = listener.local_addr().map_err(BridgeError::Listen)?.port();

    let token = Secret::new(random_token());
    let file = game_directory.join(HANDSHAKE_PATH);
    write_handshake(&file, port, &token, handshake).map_err(|source| BridgeError::Handshake {
        path: file.display().to_string(),
        source,
    })?;

    let running = Arc::new(AtomicBool::new(true));
    let thread_running = Arc::clone(&running);
    let handler = Arc::new(handler);

    std::thread::Builder::new()
        .name("fathom-bridge".to_owned())
        .spawn(move || {
            for stream in listener.incoming() {
                if !thread_running.load(Ordering::SeqCst) {
                    return;
                }
                let Ok(stream) = stream else { continue };
                // Serially, on this thread. The protocol is four verbs used a
                // handful of times a session; a thread per connection would be
                // a thread pool for a button.
                serve(stream, &token, handler.as_ref());
            }
        })
        .map_err(BridgeError::Listen)?;

    Ok(Bridge {
        port,
        running,
        handshake_file: file,
    })
}

/// One connection: a token line, a command line, one reply, closed.
fn serve<H>(stream: TcpStream, token: &Secret, handler: &H)
where
    H: Fn(Command) -> Reply,
{
    let _ = stream.set_read_timeout(Some(TIMEOUT));
    let _ = stream.set_write_timeout(Some(TIMEOUT));

    let mut reader = BufReader::new(match stream.try_clone() {
        Ok(clone) => clone,
        Err(_) => return,
    });
    let mut writer = stream;

    let mut presented = String::new();
    let mut command = String::new();
    // Bounded before anything is parsed: a client that sends megabytes should
    // cost a buffer, not a heap.
    let mut limited = (&mut reader).take(MAX_REQUEST);
    if limited.read_line(&mut presented).is_err() || limited.read_line(&mut command).is_err() {
        return;
    }

    if !token.equals_constant_time(presented.trim()) {
        // No detail. A wrong token learns only that it was wrong, which is all
        // an unauthenticated caller is entitled to.
        let _ = writer.write_all(Reply::No("unauthorised".to_owned()).line().as_bytes());
        return;
    }

    let reply = match Command::parse(&command) {
        Some(parsed) => handler(parsed),
        None => Reply::No("unknown command".to_owned()),
    };
    let _ = writer.write_all(reply.line().as_bytes());
    let _ = writer.flush();
}

/// A 32-byte random token, hex-encoded.
///
/// From the same source the rest of the launcher uses for anything that has to
/// be unguessable, rather than from a timestamp or a counter.
fn random_token() -> String {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).unwrap_or_else(|_| {
        // Unreachable on any platform this runs on. If the operating system
        // cannot produce randomness, a predictable token would be worse than no
        // bridge: the fallback is a token nothing can match, which disables the
        // bridge rather than weakening it.
        bytes = [0u8; 32];
    });
    if bytes == [0u8; 32] {
        return String::new();
    }
    bytes
        .iter()
        .fold(String::with_capacity(64), |mut out, byte| {
            // `write!` into a String cannot fail, so the result is discarded.
            let _ = write!(out, "{byte:02x}");
            out
        })
}

/// Write the handshake the module reads.
///
/// Hand-rolled JSON for a document with six scalars and a list of pairs, escaped
/// through [`escape`]. `serde_json` would be a fine alternative; what is not
/// fine is concatenating a Windows path into a JSON string without escaping it,
/// which is the one real hazard here and is why the escaping is a function with
/// a test rather than an inline `replace`.
fn write_handshake(
    path: &Path,
    port: u16,
    token: &Secret,
    handshake: &Handshake,
) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }

    let mut profiles = String::new();
    for (index, (id, name)) in handshake.profiles.iter().enumerate() {
        if index > 0 {
            profiles.push(',');
        }
        // `write!` into a String cannot fail, so the result is discarded rather
        // than unwrapped.
        let _ = write!(
            profiles,
            "\n    {{\"id\": \"{}\", \"name\": \"{}\"}}",
            escape(id),
            escape(name)
        );
    }

    let document = format!(
        "{{\n  \"schema\": {SCHEMA},\n  \"port\": {port},\n  \"token\": \"{}\",\n  \
         \"launcher\": \"{}\",\n  \"version\": \"{}\",\n  \"profile\": \"{}\",\n  \
         \"profileName\": \"{}\",\n  \"theme\": \"{}\",\n  \"profiles\": [{}\n  ]\n}}\n",
        escape(token.expose()),
        handshake
            .launcher_path
            .as_ref()
            .map(|path| escape(&path.display().to_string()))
            .unwrap_or_default(),
        escape(&handshake.launcher_version),
        escape(&handshake.profile_id),
        escape(&handshake.profile_name),
        escape(&handshake.theme),
        profiles
    );

    crate::fs_safety::atomic_write(path, document.as_bytes())
        .map_err(|error| std::io::Error::other(error.to_string()))
}

/// Escape a string for a JSON document.
///
/// Backslashes and quotes, which is what a Windows path and a user-chosen
/// profile name can contain, plus the control characters that would otherwise
/// end the line. Anything else goes through as itself.
fn escape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            other if (other as u32) < 0x20 => out.push(' '),
            other => out.push(other),
        }
    }
    out
}

use std::io::Read as _;

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    fn handshake() -> Handshake {
        Handshake {
            launcher_path: Some(PathBuf::from("C:\\Program Files\\Fathom\\fathom.exe")),
            launcher_version: "0.1.0".to_owned(),
            profile_id: "fathom-1-8-9".to_owned(),
            profile_name: "Fathom 1.8.9".to_owned(),
            theme: "cherry-grove".to_owned(),
            profiles: vec![
                ("fathom-1-8-9".to_owned(), "Fathom 1.8.9".to_owned()),
                ("vanilla".to_owned(), "Vanilla \"quotes\"".to_owned()),
            ],
        }
    }

    /// Speak to a bridge the way the module does: token, command, one reply.
    fn ask(port: u16, token: &str, command: &str) -> String {
        let mut stream =
            TcpStream::connect(SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port)))
                .expect("the bridge is listening");
        stream
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        stream
            .write_all(format!("{token}\n{command}\n").as_bytes())
            .expect("the request is sent");
        let mut reply = String::new();
        BufReader::new(stream)
            .read_line(&mut reply)
            .expect("a reply");
        reply.trim().to_owned()
    }

    /// Read the token out of a handshake the way the module does.
    fn token_from(directory: &Path) -> String {
        let text = std::fs::read_to_string(directory.join(HANDSHAKE_PATH)).expect("a handshake");
        let at = text.find("\"token\"").expect("a token field");
        let open = text[at..].find(':').unwrap() + at;
        let start = text[open..].find('"').unwrap() + open + 1;
        let end = text[start..].find('"').unwrap() + start;
        text[start..end].to_owned()
    }

    #[test]
    fn only_the_known_commands_parse() {
        assert_eq!(Command::parse("sign-in"), Some(Command::SignIn));
        assert_eq!(Command::parse("account"), Some(Command::Account));
        assert_eq!(Command::parse("accounts"), Some(Command::Accounts));
        assert_eq!(
            Command::parse("switch-account 0123456789abcdef0123456789abcdef"),
            Some(Command::SwitchAccount(
                "0123456789abcdef0123456789abcdef".to_owned()
            ))
        );
        // Neither takes an argument, so nothing can be asked *about*.
        assert_eq!(Command::parse("account token"), None);
        assert_eq!(Command::parse("sign-in someone@example.com"), None);
        assert_eq!(Command::parse("ping"), Some(Command::Ping));
        assert_eq!(Command::parse("focus"), Some(Command::Focus));
        assert_eq!(
            Command::parse("next-profile fathom-1-8-9"),
            Some(Command::NextProfile("fathom-1-8-9".to_owned()))
        );
        assert_eq!(
            Command::parse("restart-into vanilla"),
            Some(Command::RestartInto("vanilla".to_owned()))
        );

        // There is no verb for reading anything, and a closed enum is why.
        for hostile in [
            "",
            "get token",
            "read C:\\Windows\\System32",
            "focus; rm -rf /",
            "next-profile ../../etc",
            "next-profile WITH-CAPS",
            "next-profile with space",
            "exec",
        ] {
            assert_eq!(
                Command::parse(hostile),
                None,
                "{hostile:?} should be refused"
            );
        }
        // And an argument that is too long is not an id however it is spelled.
        assert_eq!(
            Command::parse(&format!("next-profile {}", "a".repeat(200))),
            None
        );
    }

    #[test]
    fn a_handshake_escapes_what_it_writes() {
        // A Windows path in an unescaped JSON string is the one real hazard in
        // this file, and a profile name is whatever somebody typed.
        assert_eq!(escape("C:\\Fathom\\a.exe"), "C:\\\\Fathom\\\\a.exe");
        assert_eq!(escape("say \"hi\""), "say \\\"hi\\\"");
        assert_eq!(escape("two\nlines"), "two\\nlines");
        assert_eq!(escape("plain"), "plain");
    }

    #[test]
    fn the_handshake_is_readable_and_carries_no_account_data() {
        let tmp = tempfile::tempdir().unwrap();
        let bridge = start(tmp.path(), &handshake(), |_| Reply::ok()).expect("a bridge");

        let text = std::fs::read_to_string(tmp.path().join(HANDSHAKE_PATH)).expect("a handshake");
        assert!(text.contains("\"schema\": 1"));
        assert!(text.contains(&format!("\"port\": {}", bridge.port())));
        assert!(text.contains("\"profileName\": \"Fathom 1.8.9\""));
        assert!(text.contains("\"theme\": \"cherry-grove\""));
        assert!(text.contains("C:\\\\Program Files\\\\Fathom\\\\fathom.exe"));
        assert!(text.contains("Vanilla \\\"quotes\\\""));

        // The guarantee the whole module rests on, checked against the bytes
        // rather than asserted in a comment.
        for forbidden in [
            "access_token",
            "refresh",
            "Bearer",
            "minecraft_token",
            "uuid",
        ] {
            assert!(
                !text.to_lowercase().contains(&forbidden.to_lowercase()),
                "the handshake mentions {forbidden}"
            );
        }
    }

    #[test]
    fn a_command_with_the_right_token_is_served() {
        let tmp = tempfile::tempdir().unwrap();
        let seen = Arc::new(AtomicUsize::new(0));
        let counter = Arc::clone(&seen);
        let bridge = start(tmp.path(), &handshake(), move |command| {
            counter.fetch_add(1, Ordering::SeqCst);
            match command {
                Command::Ping => Reply::Ok("fathom".to_owned()),
                Command::Focus => Reply::ok(),
                _ => Reply::No("not here".to_owned()),
            }
        })
        .expect("a bridge");

        let token = token_from(tmp.path());
        assert_eq!(ask(bridge.port(), &token, "ping"), "ok fathom");
        assert_eq!(ask(bridge.port(), &token, "focus"), "ok");
        assert_eq!(ask(bridge.port(), &token, "next-profile x"), "no not here");
        assert_eq!(seen.load(Ordering::SeqCst), 3);
    }

    /// The point of the token: another program on this machine that found the
    /// port cannot drive the launcher.
    #[test]
    fn a_command_with_the_wrong_token_is_refused_and_never_reaches_the_handler() {
        let tmp = tempfile::tempdir().unwrap();
        let seen = Arc::new(AtomicUsize::new(0));
        let counter = Arc::clone(&seen);
        let bridge = start(tmp.path(), &handshake(), move |_| {
            counter.fetch_add(1, Ordering::SeqCst);
            Reply::ok()
        })
        .expect("a bridge");

        for wrong in ["", "0", &"a".repeat(64), "not-the-token"] {
            assert_eq!(
                ask(bridge.port(), wrong, "focus"),
                "no unauthorised",
                "token {wrong:?}"
            );
        }
        assert_eq!(
            seen.load(Ordering::SeqCst),
            0,
            "an unauthorised request reached the handler"
        );
    }

    #[test]
    fn an_unknown_command_is_refused_even_with_the_right_token() {
        let tmp = tempfile::tempdir().unwrap();
        let seen = Arc::new(AtomicUsize::new(0));
        let counter = Arc::clone(&seen);
        let bridge = start(tmp.path(), &handshake(), move |_| {
            counter.fetch_add(1, Ordering::SeqCst);
            Reply::ok()
        })
        .expect("a bridge");

        let token = token_from(tmp.path());
        assert_eq!(ask(bridge.port(), &token, "sudo"), "no unknown command");
        assert_eq!(
            seen.load(Ordering::SeqCst),
            0,
            "it must not reach the handler"
        );
    }

    #[test]
    fn stopping_closes_the_listener_and_removes_the_handshake() {
        let tmp = tempfile::tempdir().unwrap();
        let bridge = start(tmp.path(), &handshake(), |_| Reply::ok()).expect("a bridge");
        assert!(tmp.path().join(HANDSHAKE_PATH).is_file());

        bridge.stop();
        assert!(
            !tmp.path().join(HANDSHAKE_PATH).is_file(),
            "a stale handshake names a listener that is gone"
        );
    }

    /// Two games at once get two bridges. Port zero is what makes that work,
    /// and a fixed port is what would make it fail.
    #[test]
    fn two_bridges_do_not_collide() {
        let first = tempfile::tempdir().unwrap();
        let second = tempfile::tempdir().unwrap();
        let one = start(first.path(), &handshake(), |_| Reply::ok()).expect("the first");
        let two = start(second.path(), &handshake(), |_| Reply::ok()).expect("the second");
        assert_ne!(one.port(), two.port());
        assert_ne!(token_from(first.path()), token_from(second.path()));
    }

    #[test]
    fn a_token_is_long_and_different_every_time() {
        let one = random_token();
        let two = random_token();
        assert_eq!(one.len(), 64, "32 bytes of hex");
        assert_ne!(one, two);
        assert!(one.chars().all(|c| c.is_ascii_hexdigit()));
    }

    /// The debug output must not be somewhere the token turns up.
    #[test]
    fn debug_does_not_print_the_token() {
        let tmp = tempfile::tempdir().unwrap();
        let bridge = start(tmp.path(), &handshake(), |_| Reply::ok()).expect("a bridge");
        let token = token_from(tmp.path());
        let printed = format!("{bridge:?}");
        assert!(!printed.contains(&token), "{printed}");
    }
}
