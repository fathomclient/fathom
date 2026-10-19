package net.fathom.optimizations.core;

import java.io.File;

import net.fathom.optimizations.core.module.LaunchSession;
import net.fathom.optimizations.core.profile.ProfileLocation;

/**
 * Where the game looks for the shared profile collection.
 *
 * <p>These are the rows of {@code docs/fathom-profiles.md}'s platform table,
 * asserted. If they fail, the launcher and the game have stopped agreeing about
 * where "one collection" is.
 */
public final class ProfileLocationTests {

    private static int passed;

    private static final File GAME = new File("/games/fathom-1-8-9/minecraft");

    private static final ProfileLocation.Probe EVERYTHING_EXISTS = new ProfileLocation.Probe() {
        @Override
        public boolean isDirectory(File directory) {
            return true;
        }
    };

    private static final ProfileLocation.Probe NOTHING_EXISTS = new ProfileLocation.Probe() {
        @Override
        public boolean isDirectory(File directory) {
            return false;
        }
    };

    public static void main(String[] args) {
        windowsAppendsData();
        macUsesTheBundleId();
        linuxLowercasesAndHonoursXdg();
        linuxIgnoresARelativeXdg();
        theLaunchersOwnRootWins();
        anOverrideWinsOverEverything();
        noLauncherMeansProfilesLocalToThisGame();
        theSessionCarriesTheRootAndOlderSessionsDoNot();

        System.out.println();
        System.out.println(passed + " passed, 0 failed");
    }

    static void windowsAppendsData() {
        ProfileLocation.Resolved resolved = ProfileLocation.resolve(null, null, "Windows 11",
                "C:\\Users\\Example\\AppData\\Local", "C:\\Users\\Example", null, GAME, EVERYTHING_EXISTS);
        check(resolved.source == ProfileLocation.Source.PLATFORM, "the platform rule chose it");
        check(path(resolved).endsWith("AppData/Local/Fathom/data/profiles"), "windows: " + path(resolved));
        pass("Windows appends data");
    }

    static void macUsesTheBundleId() {
        ProfileLocation.Resolved resolved = ProfileLocation.resolve(null, null, "Mac OS X", null,
                "/Users/Example", null, GAME, EVERYTHING_EXISTS);
        check(path(resolved).equals("/Users/Example/Library/Application Support/Fathom/profiles"), "mac: " + path(resolved));
        pass("macOS uses the bundle id");
    }

    static void linuxLowercasesAndHonoursXdg() {
        ProfileLocation.Resolved withXdg = ProfileLocation.resolve(null, null, "Linux", null,
                "/home/Example", "/data/xdg", GAME, EVERYTHING_EXISTS);
        check(path(withXdg).equals("/data/xdg/fathom/profiles"), "xdg: " + path(withXdg));
        ProfileLocation.Resolved without = ProfileLocation.resolve(null, null, "Linux", null,
                "/home/Example", null, GAME, EVERYTHING_EXISTS);
        check(path(without).equals("/home/Example/.local/share/fathom/profiles"), "default: " + path(without));
        pass("Linux lowercases and honours XDG_DATA_HOME");
    }

    static void linuxIgnoresARelativeXdg() {
        // The XDG spec says a relative value is invalid and must be ignored, and
        // `directories` ignores it. So must we, or the two part company.
        ProfileLocation.Resolved resolved = ProfileLocation.resolve(null, null, "Linux", null,
                "/home/Example", "relative/path", GAME, EVERYTHING_EXISTS);
        check(path(resolved).equals("/home/Example/.local/share/fathom/profiles"), "relative ignored: " + path(resolved));
        pass("Linux ignores a relative XDG_DATA_HOME");
    }

    static void theLaunchersOwnRootWins() {
        ProfileLocation.Resolved resolved = ProfileLocation.resolve(null, "E:/Portable/Fathom", "Windows 11",
                "C:\\Users\\Example\\AppData\\Local", "C:\\Users\\Example", null, GAME, EVERYTHING_EXISTS);
        check(resolved.source == ProfileLocation.Source.LAUNCHER, "the session chose it");
        check(path(resolved).equals("E:/Portable/Fathom/profiles"), "portable: " + path(resolved));
        pass("the launcher's own root wins over the platform default");
    }

    static void anOverrideWinsOverEverything() {
        ProfileLocation.Resolved resolved = ProfileLocation.resolve("/tmp/qa-profiles", "E:/Portable/Fathom",
                "Windows 11", "C:\\Users\\Example\\AppData\\Local", null, null, GAME, EVERYTHING_EXISTS);
        check(resolved.source == ProfileLocation.Source.OVERRIDE, "the override chose it");
        check(path(resolved).equals("/tmp/qa-profiles"), "override: " + path(resolved));
        pass("an override wins over everything");
    }

    static void noLauncherMeansProfilesLocalToThisGame() {
        ProfileLocation.Resolved resolved = ProfileLocation.resolve(null, null, "Windows 11",
                "C:\\Users\\Example\\AppData\\Local", "C:\\Users\\Example", null, GAME, NOTHING_EXISTS);
        check(resolved.source == ProfileLocation.Source.STANDALONE, "standalone");
        check(path(resolved).equals("/games/fathom-1-8-9/minecraft/fathom/profiles"), "local: " + path(resolved));
        pass("no launcher means profiles local to this game");
    }

    static void theSessionCarriesTheRootAndOlderSessionsDoNot() {
        String current = "{\n  \"schema\": 1,\n  \"profile\": \"fathom-1-8-9\",\n  \"profileName\": \"Fathom 1.8.9\",\n"
                + "  \"root\": \"C:\\\\Users\\\\Example\\\\AppData\\\\Local\\\\Fathom\\\\data\"\n}\n";
        check("C:\\Users\\Example\\AppData\\Local\\Fathom\\data".equals(LaunchSession.parse(current).root()),
                "root is read and unescaped: " + LaunchSession.parse(current).root());
        String older = "{\n  \"schema\": 1,\n  \"profile\": \"fathom-1-8-9\",\n  \"profileName\": \"Fathom 1.8.9\"\n}\n";
        check("".equals(LaunchSession.parse(older).root()), "an older launcher's session has no root");
        pass("the session carries the root, and older sessions do not");
    }

    private static String path(ProfileLocation.Resolved resolved) {
        return resolved.directory.getPath().replace('\\', '/');
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  ok   " + name);
    }
}
