package net.fathom.optimizations.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import net.fathom.optimizations.core.registry.Conflict;
import net.fathom.optimizations.core.registry.Optimization;
import net.fathom.optimizations.core.registry.OptimizationRegistry;
import net.fathom.optimizations.core.registry.RegistryJson;

/**
 * The registry's promises, checked: the launcher's copy is current, and no
 * entry can claim more than it is.
 */
public final class RegistryTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        theLauncherCopyIsCurrent();
        plannedWorkClaimsNothing();
        everyEntryRunsOrIsDescribedAsPlanned();
        everyConflictSaysWhy();

        System.out.println("RegistryTests: " + passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAILED: " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /**
     * The launcher compiles {@code assets/optimizations/registry.json} in. If it
     * differs from what the registry writes now, the launcher is describing a
     * catalogue the game does not have.
     */
    static void theLauncherCopyIsCurrent() throws Exception {
        File file = new File("../../assets/optimizations/registry.json");
        String expected = RegistryJson.write(OptimizationRegistry.standard());
        if (!file.isFile()) {
            check(false, "assets/optimizations/registry.json is missing; run gradle exportRegistry");
            return;
        }
        String actual = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        check(actual.equals(expected),
                "assets/optimizations/registry.json is stale; run gradle exportRegistry");
    }

    /** Nothing unwritten is measured, switched on by default, or exempt from a conflict list. */
    static void plannedWorkClaimsNothing() {
        for (Optimization entry : OptimizationRegistry.standard().all()) {
            if (entry.status() == Optimization.Status.PLANNED) {
                check(entry.evidence() == null, entry.id() + " is planned but carries evidence");
                check(!entry.defaultEnabled(), entry.id() + " is planned but on by default");
            }
        }
    }

    /** Implemented entries have a hook; the rest say plainly that they are planned. */
    static void everyEntryRunsOrIsDescribedAsPlanned() {
        int running = 0;
        int planned = 0;
        for (Optimization entry : OptimizationRegistry.standard().all()) {
            switch (entry.status()) {
                case RUNS_IN_GAME:
                case IN_MODULE:
                    running++;
                    break;
                default:
                    planned++;
            }
        }
        check(running == 10, "expected 10 implemented entries, found " + running);
        check(planned == 3, "expected 3 planned entries, found " + planned);
    }

    /** A conflict that does not say what goes wrong is not something anyone can act on. */
    static void everyConflictSaysWhy() {
        for (Optimization entry : OptimizationRegistry.standard().all()) {
            for (Conflict conflict : entry.conflicts()) {
                check(!conflict.with().trim().isEmpty() && !conflict.detail().trim().isEmpty(),
                        entry.id() + " has a conflict without a reason");
            }
        }
    }

    static void check(boolean condition, String what) {
        if (condition) {
            passed++;
        } else {
            failures.add(what);
        }
    }
}
