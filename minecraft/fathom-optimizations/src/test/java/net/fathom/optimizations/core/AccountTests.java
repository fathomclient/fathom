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
