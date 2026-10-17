package net.fathom.optimizations.fabric.bridge;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Talking to the launcher from inside the game.
 *
 * <h2>What crosses, and what never does</h2>
 *
 * <p>A few commands, none of which carries anything secret: bring the launcher
 * to the front, choose which profile launches next, start the launcher's
 * Microsoft sign-in, ask who is signed in, and say hello. <b>No access token,
 * no refresh token, no Xbox token, no sign-in code, no email and no session id
 * ever crosses this connection in either direction.</b> The one identity that
 * does is the public Minecraft profile — a username and a UUID, which every
 * server the player joins already sees.
 *
 * <h2>Two handshakes</h2>
 *
 * <p>A game Fathom started finds {@code fathom/bridge.json} in its own
 * directory. A game started any other way — a development run, another
 * launcher — has none, so it looks for the launcher's own handshake in
 * Fathom's data directory instead, which the launcher writes for as long as it
 * is open. That one is enough to sign in and to read the account; it names no
 * profile for this game, because Fathom did not start this game.
 *
 * <h2>How it is authenticated</h2>
 *
 * <p>The launcher, when it starts a game, binds a listener to <em>loopback
 * only</em>, generates a random per-session token, and writes both into
 * {@code fathom/bridge.json} inside that game's own directory. The module reads
 * the file and presents the token on connect.
 *
 * <p>That is exactly the right strength for what is being protected. The secret
 * lives in a directory the game process can already read and write — anything
 * that can steal it can already put a jar in the mods folder, which is a much
 * better attack. What the token actually stops is <em>another program on the
 * same machine</em> from finding an open port and driving the launcher: a
 * different game, a browser page probing localhost, anything that did not start
 * from this profile's directory. The token is per session, so it is worthless a
 * moment after the game exits.
 *
 * <h2>Why everything here fails quietly</h2>
 *
 * <p>Because every single call is optional. The launcher may have been closed,
 * may be a build without a bridge, may be mid-restart. A client that showed an
 * error because a convenience was unavailable would be worse than one that shows
 * the button greyed out and says why — so nothing here throws, and
 * {@link #status()} is the one place that reports what is going on.
 *
 * <h2>Why the socket is opened per call</h2>
 *
 * <p>Four commands, none of them in a loop, none of them more than once a
 * minute. A kept-open connection would be a socket, a reader thread and a
 * reconnect policy in exchange for saving a millisecond on a button press.
 */
public final class LauncherBridge {

    /** Where the launcher leaves the handshake, relative to the game directory. */
    public static final String HANDSHAKE_PATH = "fathom/bridge.json";

    /** Format version. The launcher writes it; a mismatch means do not connect. */
    public static final int SCHEMA = 1;

    /**
     * How long to wait for the launcher.
     *
     * <p>Short. This runs on the client thread when a button is pressed, and a
     * launcher that has not answered in a quarter of a second is a launcher that
     * is not there — freezing the game to find that out would be worse than the
     * feature being unavailable.
     */
    private static final int TIMEOUT_MILLIS = 250;

    /** What the module could work out about the launcher. */
    public enum State {
        /** No handshake file: the game was not started by a launcher with a bridge. */
        ABSENT,
        /** A handshake file, but nothing answering. The launcher has been closed. */
        CLOSED,
        /** Connected and answering. */
        READY,
        /** A handshake file this build cannot read. */
        UNSUPPORTED
    }

    private final File gameDirectory;
    private Handshake handshake;
    /** Whether the handshake is the launcher's own rather than this game's. */
    private boolean launcherOnly;
    private State state = State.ABSENT;
    private String detail = "";

    /**
     * What the launcher said this launch is, with no secret in it.
     *
     * <p>Read separately from the handshake and kept even when the handshake is
     * absent, because the two answer different questions. The handshake answers
     * "can I talk to the launcher"; this answers "what is this session", and a
     * launcher that has since been closed does not make the profile name wrong.
     */
    private net.fathom.optimizations.core.module.LaunchSession session =
            net.fathom.optimizations.core.module.LaunchSession.absent();

    /**
     * When the launcher was last asked to start, so it is asked once.
     *
     * <p>Starting takes a few seconds, during which the bridge still reads as
     * closed. Without this, somebody pressing Open Launcher twice gets two
     * launchers — and two launchers writing the same profile files is a worse
     * problem than a button that appears not to have worked.
     */
    private long lastStartAttempt;

    private static final long START_COOLDOWN_MILLIS = 8000L;

    public LauncherBridge(File gameDirectory) {
        this.gameDirectory = gameDirectory;
        refresh();
    }

    /** Re-read the handshake and the descriptor, and re-check the listener. */
    public void refresh() {
        session = readSession();
        handshake = Handshake.read(new File(gameDirectory, HANDSHAKE_PATH));
        launcherOnly = false;
        if (handshake == null) {
            File root = launcherRoot();
            handshake = root == null ? null : Handshake.read(new File(new File(root, "launcher"), HANDSHAKE_PATH));
            launcherOnly = handshake != null;
            if (launcherOnly) {
                // Not this game's profile, so none of it is repeated as if it were.
                handshake.profileId = null;
                handshake.profileName = null;
                handshake.profiles = Collections.<Profile>emptyList();
            }
        }
        if (handshake == null) {
            state = State.ABSENT;
            detail = "Not started from Fathom.";
            return;
        }
        if (handshake.schema != SCHEMA) {
            state = State.UNSUPPORTED;
            detail = "The launcher speaks a newer bridge.";
            return;
        }
        String answer = send("ping");
        if (answer != null && answer.startsWith("ok")) {
            state = State.READY;
            detail = handshake.launcherVersion == null ? "Connected." : "Fathom " + handshake.launcherVersion;
        } else {
            state = State.CLOSED;
            detail = "Fathom is not running.";
        }
    }

    public State state() {
        return state;
    }

    /** The directory this bridge was made for. */
    public File gameDirectory() {
        return gameDirectory;
    }

    /** Whether Fathom started this game, as opposed to only being reachable from it. */
    public boolean startedByLauncher() {
        return handshake != null && !launcherOnly;
    }

    /**
     * Fathom's data directory: the one the launcher named in this session's
     * descriptor, or the platform default. Null when neither can be worked out.
     */
    public File launcherRoot() {
        String named = session.root();
        if (named != null && !named.trim().isEmpty()) {
            return new File(named);
        }
        return net.fathom.optimizations.core.profile.ProfileLocation.platformRoot();
    }

    /**
     * Ask the launcher to sign in with Microsoft.
     *
     * <p>The launcher opens Microsoft's page in the browser and raises its own
     * account drawer; no credential ever reaches this process. Returns the
     * launcher's word for what it did — {@code started}, {@code signing-in} or
     * {@code signed-in} — or {@code null} when it did not answer, and
     * {@code "no <reason>"} when it refused.
     */
    public String signIn() {
        String answer = send("sign-in");
        if (answer == null) {
            return null;
        }
        return answer.startsWith("ok") ? answer.substring(2).trim() : answer;
    }

    /**
     * Ask the launcher to add a Microsoft account -- a new sign-in whoever is
     * signed in already. Same answers as {@link #signIn}; a launcher too old
     * to know the verb answers {@code "no unknown command"}.
     */
    public String addAccount() {
        String answer = send("add-account");
        if (answer == null) {
            return null;
        }
        return answer.startsWith("ok") ? answer.substring(2).trim() : answer;
    }

    /** The current or last sign-in attempt, or null when the launcher did not answer. */
    public SignInStatus signInStatus() {
        String answer = send("sign-in-status");
        return answer == null ? null : SignInStatus.parse(answer);
    }

    /** Abandon the sign-in under way. */
    public boolean cancelSignIn() {
        String answer = send("cancel-sign-in");
        return answer != null && answer.startsWith("ok");
    }

    /** Who the launcher has signed in, or null when it did not answer. */
    public Account account() {
        String answer = send("account");
        return answer == null ? null : Account.parse(answer);
    }

    /** Public Minecraft profiles remembered by the launcher. */
    public SavedAccount[] accounts() {
        String answer = send("accounts");
        return answer == null ? new SavedAccount[0] : SavedAccount.parse(answer);
    }

    /** Select a remembered account for the next launch. */
    public boolean switchAccount(String uuid) {
        if (uuid == null || !uuid.matches("[0-9a-f]{32}")) {
            return false;
        }
        String answer = send("switch-account " + uuid);
        return answer != null && answer.startsWith("ok");
    }

    /** One sentence about the connection, for the Launcher section. */
    public String detail() {
        return detail;
    }

    /** The profile this game was started with, as the launcher named it. */
    /**
     * What the launcher said it started, or null when it said nothing.
     *
     * <p>Null rather than a placeholder: only the caller knows whether this is a
     * development run or somebody else's instance, and inventing "Unknown
     * profile" here is what put that phrase across the interface.
     * {@code SessionName} turns this into something to show.
     */
    public String profileName() {
        // The descriptor first. It is written before the process starts and
        // survives the launcher being closed afterwards, so it is the more
        // reliable of the two — and the handshake is only there at all when a
        // listener came up.
        if (session.present()) {
            return session.profileName();
        }
        return handshake == null || handshake.profileName == null || handshake.profileName.trim().isEmpty()
                ? null
                : handshake.profileName;
    }

    public String profileId() {
        if (session.present() && !session.profileId().isEmpty()) {
            return session.profileId();
        }
        return handshake == null ? null : handshake.profileId;
    }

    /** What the launcher said this launch is: version, loader, preset, component. */
    public net.fathom.optimizations.core.module.LaunchSession session() {
        return session;
    }

    /**
     * Read the session descriptor.
     *
     * <p>Everything is caught. This runs during start-up, the file is written
     * by another process, and a client that failed to start because a
     * display-name file was unreadable would be a client broken by its own
     * cosmetics.
     */
    private net.fathom.optimizations.core.module.LaunchSession readSession() {
        File file = new File(
                gameDirectory, net.fathom.optimizations.core.module.LaunchSession.RELATIVE_PATH);
        if (!file.isFile() || file.length() > 64 * 1024) {
            return net.fathom.optimizations.core.module.LaunchSession.absent();
        }
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int step = in.read(bytes, read, bytes.length - read);
                if (step < 0) {
                    break;
                }
                read += step;
            }
            return net.fathom.optimizations.core.module.LaunchSession.parse(
                    new String(bytes, 0, read, "UTF-8"));
        } catch (Exception unreadable) {
            return net.fathom.optimizations.core.module.LaunchSession.absent();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Nothing useful to do with a failed close on a read.
                }
            }
        }
    }

    /** Every profile the launcher knows about, for the "launch next" list. */
    public List<Profile> profiles() {
        return handshake == null ? Collections.<Profile>emptyList() : handshake.profiles;
    }

    /**
     * Bring the launcher to the front, starting it if it is not running.
     *
     * @return true when something was done
     */
    public boolean open() {
        if (state == State.READY) {
            return send("focus") != null;
        }
        // Ask again before starting anything. The state was last decided when
        // `refresh` ran, which may have been when this screen opened; the
        // launcher could have been started by hand since, and starting a second
        // one because of a stale answer is the failure this guards.
        if (handshake != null && send("ping") != null) {
            state = State.READY;
            detail = "Connected.";
            return send("focus") != null;
        }

        long now = System.currentTimeMillis();
        if (now - lastStartAttempt < START_COOLDOWN_MILLIS) {
            // Already starting. Saying so is more use than starting another.
            detail = "Fathom is starting…";
            return true;
        }
        lastStartAttempt = now;
        return start();
    }

    /**
     * Open the launcher's own diagnostics page.
     *
     * <p>Only when the launcher is running: this raises a window and navigates
     * it, and there is nothing sensible to do with the request against a
     * launcher that has been closed. The Hub offers the button as disabled in
     * that case rather than starting the launcher for it, because starting a
     * program is a bigger thing than the button says.
     */
    public boolean openDiagnostics() {
        if (state != State.READY) {
            return false;
        }
        String answer = send("diagnostics");
        return answer != null && answer.startsWith("ok");
    }

    /** Choose which profile the launcher should use next time. */
    public boolean selectNextProfile(String id) {
        if (state != State.READY || launcherOnly || id == null) {
            return false;
        }
        String answer = send("next-profile " + id);
        return answer != null && answer.startsWith("ok");
    }

    /**
     * Ask the launcher to relaunch the game into a profile.
     *
     * <p>The module does not restart anything itself. It asks, the launcher
     * decides, and the user is the one who closes the game — because a mod that
     * could terminate the process it is running inside is a mod one bad button
     * away from taking somebody's session with it.
     */
    public boolean requestRestart(String profileId) {
        if (state != State.READY || launcherOnly || profileId == null) {
            return false;
        }
        String answer = send("restart-into " + profileId);
        return answer != null && answer.startsWith("ok");
    }

    /**
     * Start the launcher.
     *
     * <p>The executable path is the one the launcher wrote about itself into a
     * file in its own directory. It is checked to exist and to be a file before
     * anything is spawned; a handshake that named something absent is a
     * handshake from an install that has moved, not an instruction.
     */
    private boolean start() {
        if (handshake == null || handshake.launcherPath == null) {
            return false;
        }
        File executable = new File(handshake.launcherPath);
        if (!executable.isFile()) {
            detail = "Fathom is not where it was.";
            return false;
        }
        try {
            new ProcessBuilder(executable.getAbsolutePath())
                    .directory(executable.getParentFile())
                    .start();
            detail = "Starting Fathom…";
            return true;
        } catch (IOException problem) {
            detail = "Could not start Fathom.";
            return false;
        }
    }

    /**
     * One request, one reply, one closed socket.
     *
     * <p>The token goes first on its own line, then the command. Line-delimited
     * because the whole protocol is four verbs and a profile id, and a framed
     * binary format would be ceremony around a string.
     */
    private String send(String command) {
        if (handshake == null || handshake.port <= 0 || handshake.token == null) {
            return null;
        }
        Socket socket = null;
        BufferedReader reader = null;
        try {
            socket = new Socket();
            // Loopback explicitly, not "localhost": the name could resolve
            // anywhere a hosts file says, and this connection is only ever
            // meant for this machine.
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), handshake.port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);

            OutputStream out = socket.getOutputStream();
            out.write((handshake.token + "\n" + command + "\n").getBytes("UTF-8"));
            out.flush();

            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            return reader.readLine();
        } catch (IOException unreachable) {
            return null;
        } finally {
            close(reader);
            close(socket);
        }
    }

    private static void close(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // A failed close on a socket we are done with is not news.
            }
        }
    }

    /**
     * The launcher's answer to "who is signed in".
     *
     * <p>Public profile only. Parsed strictly: a name that is not a Minecraft
     * username or a UUID that is not 32 hex characters is treated as no answer
     * rather than displayed.
     */
    public static final class Account {
        public enum Status { SIGNED_IN, SIGNING_IN, SIGNED_OUT, FAILED, BUSY }

        public final Status status;
        /** 32 lowercase hex characters, or empty. */
        public final String uuid;
        public final String name;
        /** Why the last sign-in failed, when it did. */
        public final String message;
        /** Device code while a Microsoft sign-in is waiting; never a token. */
        public final String userCode;

        Account(Status status, String uuid, String name, String message, String userCode) {
            this.status = status;
            this.uuid = uuid;
            this.name = name;
            this.message = message;
            this.userCode = userCode;
        }

        public static Account parse(String line) {
            String text = line.trim();
            if (text.startsWith("no")) {
                return new Account(Status.BUSY, "", "", "", "");
            }
            if (!text.startsWith("ok")) {
                return null;
            }
            String[] words = text.substring(2).trim().split(" ", 3);
            String word = words.length > 0 ? words[0] : "";
            if ("signed-in".equals(word) && words.length == 3
                    && words[1].matches("[0-9a-f]{32}") && words[2].matches("[A-Za-z0-9_]{1,16}")) {
                return new Account(Status.SIGNED_IN, words[1], words[2], "", "");
            }
            if ("signing-in".equals(word)) {
                String code = words.length > 1 && words[1].matches("[A-Z0-9-]{4,16}")
                        ? words[1] : "";
                return new Account(Status.SIGNING_IN, "", "", "", code);
            }
            if ("failed".equals(word)) {
                String reason = text.substring(2).trim().substring("failed".length()).trim();
                return new Account(Status.FAILED, "", "", reason.length() > 160 ? reason.substring(0, 160) : reason, "");
            }
            if ("signed-out".equals(word)) {
                return new Account(Status.SIGNED_OUT, "", "", "", "");
            }
            return null;
        }
    }

    /**
     * One sign-in attempt, as the launcher reports it. Never a token: the
     * phase, the short code Microsoft asks people to type (the launcher shows
     * it on screen too), the public profile once it is done, or a sentence.
     */
    public static final class SignInStatus {
        public enum Phase { IDLE, STARTING, WAITING, VERIFYING, SIGNED_IN, FAILED, CANCELLED, BUSY, UNSUPPORTED }

        public final Phase phase;
        public final String code;
        public final boolean browserOpened;
        public final String uuid;
        public final String name;
        public final String message;

        SignInStatus(Phase phase, String code, boolean browserOpened, String uuid, String name, String message) {
            this.phase = phase;
            this.code = code;
            this.browserOpened = browserOpened;
            this.uuid = uuid;
            this.name = name;
            this.message = message;
        }

        private static SignInStatus of(Phase phase) {
            return new SignInStatus(phase, "", false, "", "", "");
        }

        public static SignInStatus parse(String line) {
            String text = line.trim();
            if (text.startsWith("no")) {
                return of("unknown command".equals(text.substring(2).trim()) ? Phase.UNSUPPORTED : Phase.BUSY);
            }
            if (!text.startsWith("ok")) {
                return null;
            }
            String[] words = text.substring(2).trim().split(" ", 3);
            String word = words.length > 0 ? words[0] : "";
            if ("idle".equals(word)) {
                return of(Phase.IDLE);
            }
            if ("starting".equals(word)) {
                return of(Phase.STARTING);
            }
            if ("waiting".equals(word)) {
                String code = words.length > 1 && words[1].matches("[A-Z0-9-]{4,16}") ? words[1] : "";
                boolean opened = words.length > 2 && "1".equals(words[2]);
                return new SignInStatus(Phase.WAITING, code, opened, "", "", "");
            }
            if ("verifying".equals(word)) {
                return of(Phase.VERIFYING);
            }
            if ("signed-in".equals(word) && words.length == 3
                    && words[1].matches("[0-9a-f]{32}") && words[2].matches("[A-Za-z0-9_]{1,16}")) {
                return new SignInStatus(Phase.SIGNED_IN, "", false, words[1], words[2], "");
            }
            if ("failed".equals(word)) {
                String reason = text.substring(2).trim().substring("failed".length()).trim();
                return new SignInStatus(Phase.FAILED, "", false, "", "",
                        reason.length() > 160 ? reason.substring(0, 160) : reason);
            }
            if ("cancelled".equals(word)) {
                return of(Phase.CANCELLED);
            }
            return null;
        }
    }

    /** One non-secret row in the launcher's account picker. */
    public static final class SavedAccount {
        public final String uuid;
        public final String name;
        public final boolean active;

        SavedAccount(String uuid, String name, boolean active) {
            this.uuid = uuid;
            this.name = name;
            this.active = active;
        }

        static SavedAccount[] parse(String line) {
            String text = line == null ? "" : line.trim();
            if (!text.startsWith("ok")) {
                return new SavedAccount[0];
            }
            text = text.substring(2).trim();
            if (text.isEmpty()) {
                return new SavedAccount[0];
            }
            String[] rows = text.split(",");
            List<SavedAccount> accounts = new ArrayList<SavedAccount>();
            for (String row : rows) {
                String[] fields = row.split(":", 3);
                if (fields.length == 3 && fields[0].matches("[0-9a-f]{32}")
                        && fields[1].matches("[A-Za-z0-9_]{1,16}")
                        && ("0".equals(fields[2]) || "1".equals(fields[2]))) {
                    accounts.add(new SavedAccount(fields[0], fields[1], "1".equals(fields[2])));
                }
            }
            return accounts.toArray(new SavedAccount[accounts.size()]);
        }
    }

    /** One profile the launcher knows about. */
    public static final class Profile {
        public final String id;
        public final String name;
        public final boolean current;

        Profile(String id, String name, boolean current) {
            this.id = id;
            this.name = name;
            this.current = current;
        }
    }

    /**
     * What the launcher left behind.
     *
     * <h2>Why this is parsed by hand</h2>
     *
     * <p>The module's whole dependency list is the JDK, Fabric loader and
     * Minecraft. Adding a JSON library so that one small file can be read would
     * be a dependency every user carries for a handshake, and this file has six
     * scalar fields and one array of pairs. What is written below is a scanner
     * for exactly that shape: it finds a key and takes the value, and anything
     * it does not understand is left null. It is not a JSON parser and does not
     * pretend to be one — a malformed file yields an absent bridge, which is the
     * same outcome as no file at all.
     */
    static final class Handshake {
        int schema;
        int port;
        String token;
        String launcherPath;
        String launcherVersion;
        String profileId;
        String profileName;
        List<Profile> profiles = Collections.emptyList();

        static Handshake read(File file) {
            if (!file.isFile() || file.length() > 64 * 1024) {
                return null;
            }
            String text;
            try {
                byte[] bytes = new byte[(int) file.length()];
                java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(file));
                try {
                    in.readFully(bytes);
                } finally {
                    in.close();
                }
                text = new String(bytes, "UTF-8");
            } catch (IOException unreadable) {
                return null;
            }

            Handshake out = new Handshake();
            out.schema = (int) number(text, "schema");
            out.port = (int) number(text, "port");
            out.token = string(text, "token");
            out.launcherPath = string(text, "launcher");
            out.launcherVersion = string(text, "version");
            out.profileId = string(text, "profile");
            out.profileName = string(text, "profileName");
            out.profiles = profiles(text, out.profileId);

            // A handshake without these is not usable, and a half-usable one is
            // worse than none: it would put a Connected badge on a bridge that
            // cannot authenticate.
            if (out.port <= 0 || out.token == null || out.token.isEmpty()) {
                return null;
            }
            return out;
        }

        private static String string(String text, String key) {
            int at = text.indexOf('"' + key + '"');
            if (at < 0) {
                return null;
            }
            int colon = text.indexOf(':', at);
            int open = text.indexOf('"', colon + 1);
            if (colon < 0 || open < 0) {
                return null;
            }
            StringBuilder value = new StringBuilder();
            for (int i = open + 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length()) {
                    // The launcher writes Windows paths, so backslashes arrive
                    // escaped. Unescaping the two forms that can appear is
                    // enough; anything else is passed through as itself.
                    char next = text.charAt(++i);
                    value.append(next == 'n' ? '\n' : next);
                    continue;
                }
                if (c == '"') {
                    break;
                }
                value.append(c);
            }
            return value.toString();
        }

        private static long number(String text, String key) {
            int at = text.indexOf('"' + key + '"');
            if (at < 0) {
                return 0;
            }
            int colon = text.indexOf(':', at);
            if (colon < 0) {
                return 0;
            }
            StringBuilder digits = new StringBuilder();
            for (int i = colon + 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else if (digits.length() > 0) {
                    break;
                } else if (c == ',' || c == '}') {
                    break;
                }
            }
            try {
                return digits.length() == 0 ? 0 : Long.parseLong(digits.toString());
            } catch (NumberFormatException notANumber) {
                return 0;
            }
        }

        private static List<Profile> profiles(String text, String currentId) {
            List<Profile> out = new ArrayList<Profile>();
            int at = text.indexOf("\"profiles\"");
            if (at < 0) {
                return out;
            }
            int end = text.indexOf(']', at);
            if (end < 0) {
                return out;
            }
            String block = text.substring(at, end);

            int cursor = 0;
            while (true) {
                int open = block.indexOf('{', cursor);
                if (open < 0) {
                    break;
                }
                int close = block.indexOf('}', open);
                if (close < 0) {
                    break;
                }
                String entry = block.substring(open, close);
                String id = string(entry, "id");
                String name = string(entry, "name");
                if (id != null && !id.isEmpty()) {
                    out.add(new Profile(id, name == null || name.isEmpty() ? id : name, id.equals(currentId)));
                }
                cursor = close + 1;
            }
            return out;
        }
    }
}
