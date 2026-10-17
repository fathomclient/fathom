package net.fathom.optimizations.core;

import java.io.File;
import java.util.List;

import net.fathom.optimizations.core.profile.FathomProfile;
import net.fathom.optimizations.core.profile.ProfileStore;

/**
 * The game's half of the launcher/game profile contract.
 *
 * <p>Not part of {@code coreTest}: it needs the directory the launcher's half
 * wrote. Run by {@code tools/profile-contract.sh}; see
 * {@code crates/launcher-core/tests/profile_contract.rs} for the three stages.
 *
 * <p>What the game does at startup is {@link ProfileStore#resolveLive()}, which
 * is exactly what {@code ProfileRuntime.start} calls, so "the next game opens the
 * launcher's choice" and "a restart keeps the live profile" are both asserted
 * against the real call rather than a stand-in.
 */
public final class ProfileContract {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ProfileContract <data root>");
        }
        File directory = new File(args[0], ProfileStore.DIRECTORY);

        // -- the game starts ------------------------------------------------------
        ProfileStore game = new ProfileStore(directory);
        List<FathomProfile> profiles = game.list(null);
        check(profiles.size() == 3, "the game sees all three the launcher wrote: " + profiles.size());
        check("Bedwars".equals(profiles.get(0).name()) && "Default".equals(profiles.get(1).name())
                && "Survival".equals(profiles.get(2).name()), "by name");
        FathomProfile startup = game.resolveLive();
        check("survival".equals(startup.id()), "it opens the launcher's choice: " + startup.id());
        check("Minecraft".equals(startup.client().hudFont), "with Survival's own settings");
        check("Gameplay glass".equals(startup.client().rshiftBackground)
                && "Strong".equals(startup.client().rshiftBlur)
                && "Deep".equals(startup.client().rshiftDim), "with Survival's RShift presentation");
        check("Low".equals(startup.client().menuBackground), "and its menu background");

        FathomProfile bedwars = game.load("bedwars");
        check(Boolean.TRUE.equals(bedwars.modules().get("cps").enabled), "Bedwars' modules came across");
        check(bedwars.hud().get("fps").scale == 1.5f, "and its HUD");
        System.out.println("  ok   the game opens the launcher's collection on the launcher's choice");

        // -- switched in the hub: the departing profile saved, Bedwars live ------
        bedwars.module("zoom").enabled = Boolean.TRUE;
        bedwars.client().rshiftBackground = "Fathom background";
        bedwars.client().rshiftBlur = "Soft";
        bedwars.client().rshiftDim = "Light";
        bedwars.client().menuBackground = "Off";
        bedwars.client().extra.put("futurePreference", "kept");
        game.save(bedwars);
        game.setLastProfileId("bedwars");
        System.out.println("  ok   switched to Bedwars in game, with an edit");

        // -- the game restarts: a fresh store on the same files -------------------
        ProfileStore restarted = new ProfileStore(directory);
        FathomProfile again = restarted.resolveLive();
        check("bedwars".equals(again.id()), "Bedwars is still live after a restart: " + again.id());
        check(restarted.list(null).size() == 3, "and no second Default was created");
        System.out.println("  ok   a restart keeps Bedwars and creates nothing");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
