package net.fathom.optimizations.fabric.bridge;

import java.io.File;

/**
 * The signed-in Minecraft account, as far as this game can know it.
 *
 * <h2>Two sources</h2>
 *
 * <p>The game's own session — the username and UUID it was launched with — is
 * the account it actually plays as. When Fathom started the game that session is
 * real, because the launcher refuses to start anything without a verified
 * Microsoft account. When something else started it (a development run hands out
 * {@code Player123}), the session is a placeholder, and the launcher is asked
 * over the bridge who, if anyone, it has signed in.
 *
 * <h2>Signing in</h2>
 *
 * <p>There is no second sign-in implementation here. {@link #signIn} asks the
 * launcher to run its own: the launcher opens Microsoft's page in the browser,
 * while the game's textured account panel follows progress over the bridge.
 * No token or password ever reaches this process; the only transient auth data
 * is Microsoft's short device user-code, shown so the player can paste it.
 *
 * <h2>Threads</h2>
 *
 * <p>Every bridge call is a loopback socket with a quarter-second timeout, which
 * is still a quarter-second the render thread must not spend. All of them run
 * on one daemon worker; the menu reads the volatile fields below and never
 * waits.
 */
public final class LauncherAccount {

    public enum Phase {
        /** Nothing asked yet, or nothing reachable to ask. */
        UNKNOWN,
        SIGNED_OUT,
        SIGNING_IN,
        SIGNED_IN,
        FAILED
    }

    /** How long a sign-in is watched before giving up. Microsoft's codes last 15 minutes. */
    private static final long WATCH_MILLIS = 16L * 60L * 1000L;
    private static final long POLL_MILLIS = 1000L;
    /** How long to wait for a launcher that is being started. */
    private static final long START_WAIT_MILLIS = 30L * 1000L;

    private static volatile LauncherAccount instance;

    private final LauncherBridge bridge;

    private volatile Phase phase = Phase.UNKNOWN;
    private volatile String name = "";
    private volatile String uuid = "";
    private volatile String userCode = "";
    private volatile LauncherBridge.SavedAccount[] saved = new LauncherBridge.SavedAccount[0];
    private volatile String notice;
    private volatile int noticeSerial;
    private volatile boolean working;
    /** A press that arrived while a quick check was still running. */
    private volatile boolean signInQueued;
    private volatile long lastCheck;

    private LauncherAccount(File gameDirectory) {
        // Its own bridge object: the menu's copy is read on the render thread,
        // and LauncherBridge is not built to be shared across threads.
        this.bridge = new LauncherBridge(gameDirectory);
    }

    /** The one instance, for the game directory the module was started in. */
    public static LauncherAccount get(File gameDirectory) {
        LauncherAccount current = instance;
        if (current == null) {
            synchronized (LauncherAccount.class) {
                current = instance;
                if (current == null) {
                    current = new LauncherAccount(gameDirectory);
                    instance = current;
                }
            }
        }
        return current;
    }

    public Phase phase() {
        return phase;
    }

    /** The launcher's signed-in username, or empty. */
    public String name() {
        return name;
    }

    /** The launcher's signed-in UUID, 32 hex characters, or empty. */
    public String uuid() {
        return uuid;
    }

    public String userCode() {
        return userCode;
    }

    public LauncherBridge.SavedAccount[] savedAccounts() {
        return saved;
    }

    public boolean working() {
        return working;
    }

    /** A sentence for the menu, changed whenever {@link #noticeSerial} changes. */
    public String notice() {
        return notice;
    }

    public int noticeSerial() {
        return noticeSerial;
    }

    /** Fathom's data directory, where the launcher keeps the account's head. */
    public File launcherRoot() {
        return bridge.launcherRoot();
    }

    /**
     * Ask the launcher who is signed in, at most every few seconds.
     *
     * <p>Called by the menu as it opens. Cheap when nothing is reachable: the
     * handshake read fails and the worker exits.
     */
    public void check() {
        long now = System.currentTimeMillis();
        if (working || now - lastCheck < 5000L) {
            return;
        }
        lastCheck = now;
        run(new Runnable() {
            @Override
            public void run() {
                bridge.refresh();
                if (bridge.state() == LauncherBridge.State.READY) {
                    poll();
                    saved = bridge.accounts();
                    if (phase == Phase.SIGNING_IN) {
                        watch();
                    }
                }
            }
        });
    }

    /** Switch the launcher's active account without leaving the game UI. */
    public void switchTo(final String accountId) {
        if (working) {
            return;
        }
        run(new Runnable() {
            @Override
            public void run() {
                bridge.refresh();
                if (bridge.state() != LauncherBridge.State.READY
                        || !bridge.switchAccount(accountId)) {
                    say("Fathom could not switch accounts. Open the launcher and try again.");
                    return;
                }
                phase = Phase.SIGNING_IN;
                say("Switching accounts…");
                watch();
                saved = bridge.accounts();
            }
        });
    }

    /**
     * Start signing in through the launcher.
     *
     * <p>Visible straight away in the game's account panel. The launcher opens
     * Microsoft's page and this sets a notice saying so. When the launcher is
     * closed it is started, and the sign-in follows once it answers.
     */
    public void signIn() {
        if (working) {
            if (phase == Phase.SIGNING_IN) {
                say("Signing in with Microsoft in Fathom…");
            } else {
                // A menu-open check is mid-flight; run the sign-in straight after.
                signInQueued = true;
            }
            return;
        }
        run(new Runnable() {
            @Override
            public void run() {
                bridge.refresh();
                if (bridge.state() == LauncherBridge.State.ABSENT) {
                    say("Open the Fathom launcher to sign in. This game cannot reach it.");
                    return;
                }
                if (bridge.state() == LauncherBridge.State.UNSUPPORTED) {
                    say("This Fathom launcher is a different version. Sign in there.");
                    return;
                }
                if (bridge.state() != LauncherBridge.State.READY) {
                    say("Starting Fathom to sign in…");
                    if (!bridge.open()) {
                        say("Fathom could not be started. Open it and sign in there.");
                        return;
                    }
                    if (!waitForLauncher()) {
                        say("Fathom did not start in time. Open it and sign in there.");
                        return;
                    }
                }
                String answer = bridge.signIn();
                if (answer == null) {
                    say("Fathom did not answer. Open it and sign in there.");
                    return;
                }
                if (answer.startsWith("no")) {
                    String reason = answer.substring(2).trim();
                    if ("busy".equals(reason)) {
                        say("Fathom is busy for a moment. Press Sign in again.");
                        return;
                    }
                    say(reason.isEmpty() ? "Fathom could not start the sign-in." : reason);
                    phase = Phase.FAILED;
                    return;
                }
                if ("signed-in".equals(answer)) {
                    poll();
                    say(name.isEmpty() ? "Already signed in. Fathom is showing the account."
                            : "Fathom is signed in as " + name + ".");
                    return;
                }
                phase = Phase.SIGNING_IN;
                say("Continue in your browser. Microsoft's sign-in page is open.");
                watch();
            }
        });
    }

    /** Follow a sign-in until it settles, or the watch runs out. */
    private void watch() {
        long until = System.currentTimeMillis() + WATCH_MILLIS;
        while (System.currentTimeMillis() < until) {
            if (!sleep(POLL_MILLIS)) {
                return;
            }
            LauncherBridge.Account account = bridge.account();
            if (account == null) {
                // The launcher was closed mid-sign-in. Nothing is finishing now.
                phase = Phase.UNKNOWN;
                say("Fathom closed before the sign-in finished.");
                return;
            }
            if (account.status == LauncherBridge.Account.Status.BUSY
                    || account.status == LauncherBridge.Account.Status.SIGNING_IN) {
                continue;
            }
            apply(account);
            if (phase == Phase.SIGNED_IN) {
                say("Signed in as " + name + ".");
            } else if (phase == Phase.FAILED) {
                say(account.message.isEmpty() ? "Sign-in did not finish." : account.message);
            } else {
                say("Sign-in was cancelled.");
            }
            return;
        }
        phase = Phase.UNKNOWN;
    }

    private void poll() {
        LauncherBridge.Account account = bridge.account();
        if (account != null && account.status != LauncherBridge.Account.Status.BUSY) {
            apply(account);
        }
    }

    private void apply(LauncherBridge.Account account) {
        switch (account.status) {
            case SIGNED_IN:
                uuid = account.uuid;
                name = account.name;
                userCode = "";
                phase = Phase.SIGNED_IN;
                break;
            case SIGNING_IN:
                userCode = account.userCode;
                phase = Phase.SIGNING_IN;
                break;
            case FAILED:
                uuid = "";
                name = "";
                userCode = "";
                phase = Phase.FAILED;
                break;
            default:
                uuid = "";
                name = "";
                phase = Phase.SIGNED_OUT;
                break;
        }
    }

    private boolean waitForLauncher() {
        long until = System.currentTimeMillis() + START_WAIT_MILLIS;
        while (System.currentTimeMillis() < until) {
            if (!sleep(POLL_MILLIS)) {
                return false;
            }
            bridge.refresh();
            if (bridge.state() == LauncherBridge.State.READY) {
                return true;
            }
        }
        return false;
    }

    private void say(String message) {
        notice = message;
        noticeSerial++;
    }

    private void run(final Runnable body) {
        working = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    body.run();
                } catch (RuntimeException unexpected) {
                    say("Sign-in could not continue. Try again from Fathom.");
                } finally {
                    working = false;
                    if (signInQueued) {
                        signInQueued = false;
                        signIn();
                    }
                }
            }
        }, "fathom-account");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Whether a game session is a stand-in rather than an account.
     *
     * <p>A development run is handed {@code Player} and up to three digits; that
     * shape, or no name at all, is what is treated as signed out. A real account
     * that happens to be called {@code Player123} can only reach this game
     * through a launcher that says so, and when Fathom started the game this is
     * never consulted.
     */
    public static boolean isPlaceholder(String username) {
        return username == null || username.isEmpty() || username.matches("Player\\d{0,4}");
    }
}
