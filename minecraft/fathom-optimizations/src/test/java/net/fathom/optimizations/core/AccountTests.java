package net.fathom.optimizations.core;

import net.fathom.optimizations.fabric.bridge.LauncherAccount;
import net.fathom.optimizations.fabric.bridge.LauncherBridge;

/**
 * The game's half of signing in: reading the launcher's answer about the
 * account, and deciding when the game's own session is a stand-in.
 *
 * <p>Both classes are plain JDK code, so the suite runs without a game.
 */
public final class AccountTests {

    private static int passed;

    public static void main(String[] args) {
        aSignedInAnswerCarriesTheProfile();
        anAnswerThatDoesNotLookLikeAProfileIsNotShown();
        everyOtherStateParses();
        everySignInPhaseParses();
        aSignInStatusNeverCarriesMoreThanItShould();
        placeholdersAreRecognised();

        System.out.println();
        System.out.println(passed + " passed, 0 failed");
    }

    static void aSignedInAnswerCarriesTheProfile() {
        LauncherBridge.Account account =
                LauncherBridge.Account.parse("ok signed-in 069a79f444e94726a5befca90e38aaf5 Notch");
        check(account != null && account.status == LauncherBridge.Account.Status.SIGNED_IN, "signed in");
        check("Notch".equals(account.name), "the name");
        check("069a79f444e94726a5befca90e38aaf5".equals(account.uuid), "the uuid");
        pass("a signed-in answer carries the public profile");
    }

    static void anAnswerThatDoesNotLookLikeAProfileIsNotShown() {
        String[] hostile = {
            "ok signed-in not-a-uuid Notch",
            "ok signed-in 069a79f444e94726a5befca90e38aaf5 <script>",
            "ok signed-in 069a79f444e94726a5befca90e38aaf5 a name with spaces",
            "ok signed-in 069a79f444e94726a5befca90e38aaf5",
            "ok something-new",
            "garbage",
        };
        for (String line : hostile) {
            check(LauncherBridge.Account.parse(line) == null, "refused: " + line);
        }
        pass("an answer that is not a profile is not displayed");
    }

    static void everyOtherStateParses() {
        check(LauncherBridge.Account.parse("ok signing-in").status == LauncherBridge.Account.Status.SIGNING_IN,
                "signing in");
        check(LauncherBridge.Account.parse("ok signed-out").status == LauncherBridge.Account.Status.SIGNED_OUT,
                "signed out");
        LauncherBridge.Account failed = LauncherBridge.Account.parse(
                "ok failed This Microsoft account does not own Minecraft: Java Edition.");
        check(failed.status == LauncherBridge.Account.Status.FAILED, "failed");
        check(failed.message.startsWith("This Microsoft account"), "the reason: " + failed.message);
        check(LauncherBridge.Account.parse("no busy").status == LauncherBridge.Account.Status.BUSY, "busy");
        pass("signing in, signed out, failed and busy parse");
    }

    /** The add-account attempt, phase by phase, as the panel follows it. */
    static void everySignInPhaseParses() {
        check(LauncherBridge.SignInStatus.parse("ok idle").phase == LauncherBridge.SignInStatus.Phase.IDLE, "idle");
        check(LauncherBridge.SignInStatus.parse("ok starting").phase == LauncherBridge.SignInStatus.Phase.STARTING,
                "starting");
        LauncherBridge.SignInStatus waiting = LauncherBridge.SignInStatus.parse("ok waiting ABCD-1234 1");
        check(waiting.phase == LauncherBridge.SignInStatus.Phase.WAITING, "waiting");
        check("ABCD-1234".equals(waiting.code) && waiting.browserOpened, "the code, and the browser opened");
        check(!LauncherBridge.SignInStatus.parse("ok waiting ABCD1234 0").browserOpened, "the browser did not open");
        check(LauncherBridge.SignInStatus.parse("ok verifying").phase == LauncherBridge.SignInStatus.Phase.VERIFYING,
                "verifying");
        LauncherBridge.SignInStatus done = LauncherBridge.SignInStatus.parse(
                "ok signed-in 069a79f444e94726a5befca90e38aaf5 Notch");
        check(done.phase == LauncherBridge.SignInStatus.Phase.SIGNED_IN && "Notch".equals(done.name), "signed in");
        LauncherBridge.SignInStatus failed = LauncherBridge.SignInStatus.parse(
                "ok failed This Microsoft account does not own Minecraft: Java Edition.");
        check(failed.phase == LauncherBridge.SignInStatus.Phase.FAILED
                && failed.message.startsWith("This Microsoft account"), "failed, with its reason");
        check(LauncherBridge.SignInStatus.parse("ok cancelled").phase
                == LauncherBridge.SignInStatus.Phase.CANCELLED, "cancelled");
        check(LauncherBridge.SignInStatus.parse("no busy").phase == LauncherBridge.SignInStatus.Phase.BUSY, "busy");
        check(LauncherBridge.SignInStatus.parse("no unknown command").phase
                == LauncherBridge.SignInStatus.Phase.UNSUPPORTED, "an older launcher");
        pass("every sign-in phase parses");
    }

    /** A code that is not a code is dropped, and a profile that is not one is refused. */
    static void aSignInStatusNeverCarriesMoreThanItShould() {
        check(LauncherBridge.SignInStatus.parse("ok waiting <script> 1").code.isEmpty(), "no markup as a code");
        check(LauncherBridge.SignInStatus.parse("ok signed-in not-a-uuid Notch") == null, "no fake profile");
        check(LauncherBridge.SignInStatus.parse("ok something-new") == null, "nothing unknown");
        check(LauncherBridge.SignInStatus.parse("garbage") == null, "nothing malformed");
        pass("a sign-in status never carries more than it should");
    }

    static void placeholdersAreRecognised() {
        check(LauncherAccount.isPlaceholder(""), "empty");
        check(LauncherAccount.isPlaceholder(null), "null");
        check(LauncherAccount.isPlaceholder("Player"), "Player");
        check(LauncherAccount.isPlaceholder("Player482"), "Player482");
        check(!LauncherAccount.isPlaceholder("PlayerOne"), "a real name that starts with Player");
        check(!LauncherAccount.isPlaceholder("Notch"), "Notch");
        pass("development placeholders are recognised and real names are not");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAILED: " + what);
            System.exit(1);
        }
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  ok  " + name);
    }
}
