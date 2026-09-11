package net.fathom.optimizations.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Properties;

import net.fathom.optimizations.core.config.LauncherConfig;
import net.fathom.optimizations.core.registry.OptimizationId;
import net.fathom.optimizations.core.registry.OptimizationRegistry;
import net.fathom.optimizations.core.registry.SwitchBoard;

/**
 * The launcher-to-module config bridge.
 *
 * <p>The question behind every one of these: <em>can a bad file stop Minecraft from
 * starting?</em> The answer has to be no for every shape of bad file, because the module
 * is optional and a performance mod that prevents a launch has cost more than it saves.
 *
 * <p>Same hand-rolled runner as {@link CoreTests} and {@link OptTests}, for the same
 * reason: no dependencies outside the JDK.
 */
public final class ConfigTests {

    private static int passed;

    public static void main(String[] args) throws IOException {
        anAbsentFileIsNotAnError();
        anEmptyConfigLeavesTheDefaultsAlone();
        aSwitchIsApplied();
        anUnknownOptimizationIsIgnoredNotFatal();
        aNonBooleanValueIsRejectedRatherThanReadAsFalse();
        anUnparseableFileFallsBackToDefaults();
        aFileFromTheFutureStillReads();
        theSchemaAndPresetAreReadable();
        aRealLauncherFileRoundTrips();
        report();
    }

    // -----------------------------------------------------------------------

    private static void anAbsentFileIsNotAnError() {
        File empty = new File(System.getProperty("java.io.tmpdir"), "fathom-no-such-dir");
        LauncherConfig config = LauncherConfig.fromGameDirectory(empty);
        check(!config.isPresent(), "an absent file should read as not present");
        check(config.complaints().isEmpty(), "an absent file is normal, not a complaint");
        pass("a missing config is the normal case, not an error");
    }

    private static void anEmptyConfigLeavesTheDefaultsAlone() {
        SwitchBoard board = LauncherConfig.empty().apply(OptimizationRegistry.standard());
        check(board.enabled().isEmpty(), "no launcher config must mean registry defaults");
        pass("an empty config produces the registry defaults");
    }

    private static void aSwitchIsApplied() {
        Properties values = new Properties();
        values.setProperty("opt.render.font.batching", "true");
        SwitchBoard board = LauncherConfig.of(values).apply(OptimizationRegistry.standard());

        check(board.isEnabled("render.font.batching"), "the switch was not applied");
        check(
                board.state(OptimizationId.of("render.font.batching")).chosenByUser(),
                "a launcher switch represents a user's decision and must be recorded as one");
        pass("a switch from the launcher is applied as a user decision");
    }

    private static void anUnknownOptimizationIsIgnoredNotFatal() {
        Properties values = new Properties();
        values.setProperty("opt.render.font.batching", "true");
        values.setProperty("opt.not.a.real.optimization", "true");

        LauncherConfig config = LauncherConfig.of(values);
        SwitchBoard board = config.apply(OptimizationRegistry.standard());

        check(board.isEnabled("render.font.batching"), "a valid switch was dropped");
        check(!config.lastComplaints().isEmpty(), "an unknown id should be recorded");
        pass("an unknown optimization is ignored, and the rest of the file still applies");
    }

    private static void aNonBooleanValueIsRejectedRatherThanReadAsFalse() {
        // Boolean.parseBoolean maps every non-"true" string to false, which would read a
        // typo as "off" and leave the user wondering why their setting did nothing.
        check(LauncherConfig.parseBoolean("yes") == null, "'yes' should be rejected");
        check(LauncherConfig.parseBoolean("1") == null, "'1' should be rejected");
        check(LauncherConfig.parseBoolean("TRUE").booleanValue(), "case should not matter");
        check(!LauncherConfig.parseBoolean(" false ").booleanValue(), "padding should not matter");
        check(LauncherConfig.parseBoolean(null) == null, "null should be rejected");

        Properties values = new Properties();
        values.setProperty("opt.render.font.batching", "yes");
        LauncherConfig config = LauncherConfig.of(values);
        SwitchBoard board = config.apply(OptimizationRegistry.standard());

        check(!board.isEnabled("render.font.batching"), "a rejected value must not enable");
        check(!config.lastComplaints().isEmpty(), "a rejected value should be recorded");
        pass("a value that is not a boolean is rejected rather than silently read as off");
    }

    private static void anUnparseableFileFallsBackToDefaults() throws IOException {
        File directory = temporaryDirectory("fathom-bad-config");
        File file = new File(directory, LauncherConfig.RELATIVE_PATH);
        check(file.getParentFile().mkdirs(), "could not create the config directory");
        // A truncated write, which is what an interrupted launcher would leave.
        write(file, "schema = 1\nopt.render.font.batching");

        LauncherConfig config = LauncherConfig.fromGameDirectory(directory);
        SwitchBoard board = config.apply(OptimizationRegistry.standard());
        // Properties treats a key with no separator as an empty value, which is not a
        // boolean, so the switch is rejected. The important part is that nothing threw.
        check(!board.isEnabled("render.font.batching"), "a truncated line must not enable");
        pass("a truncated file does not throw and does not enable anything");
    }

    private static void aFileFromTheFutureStillReads() {
        Properties values = new Properties();
        values.setProperty("schema", "99");
        values.setProperty("preset", "something-we-have-never-heard-of");
        values.setProperty("opt.render.font.batching", "true");
        values.setProperty("some.future.key", "whatever");

        SwitchBoard board = LauncherConfig.of(values).apply(OptimizationRegistry.standard());
        check(board.isEnabled("render.font.batching"), "a known switch must still apply");
        pass("a config from a newer launcher still applies the parts we understand");
    }

    private static void theSchemaAndPresetAreReadable() {
        Properties values = new Properties();
        values.setProperty("schema", "1");
        values.setProperty("preset", "Safe");
        LauncherConfig config = LauncherConfig.of(values);

        check(config.schema() == 1, "schema should be 1");
        check(config.preset().equals("safe"), "preset should be normalised to lower case");
        check(LauncherConfig.of(new Properties()).schema() == 0, "a missing schema reads as 0");
        pass("schema and preset are readable and normalised");
    }

    /**
     * The exact bytes the Rust writer produces, pasted in. If the two sides ever disagree
     * about the format, this is where it shows up rather than in a user's game.
     */
    private static void aRealLauncherFileRoundTrips() throws IOException {
        File directory = temporaryDirectory("fathom-real-config");
        File file = new File(directory, LauncherConfig.RELATIVE_PATH);
        check(file.getParentFile().mkdirs(), "could not create the config directory");
        write(
                file,
                "# Written by Fathom before launch. Do not edit: it is replaced every time\n"
                        + "# the game starts. Change these settings in the launcher's"
                        + " Performance page.\n"
                        + "schema = 1\n"
                        + "preset = safe\n"
                        + "opt.render.font.batching = true\n"
                        + "opt.render.font.metrics-cache = true\n"
                        + "opt.render.hud.quad-batching = false\n");

        LauncherConfig config = LauncherConfig.fromGameDirectory(directory);
        check(config.isPresent(), "the file should have been found");
        check(config.schema() == LauncherConfig.SCHEMA, "schema disagrees across the bridge");
        check(config.preset().equals("safe"), "preset did not survive");

        SwitchBoard board = config.apply(OptimizationRegistry.standard());
        check(board.isEnabled("render.font.batching"), "batching should be on");
        check(board.isEnabled("render.font.metrics-cache"), "metrics cache should be on");
        check(!board.isEnabled("render.hud.quad-batching"), "hud batching should be off");
        check(config.lastComplaints().isEmpty(), "a valid file should produce no complaints");
        pass("a file written by the launcher is read exactly as intended");
    }

    // =======================================================================
    // Harness
    // =======================================================================

    private static File temporaryDirectory(String name) throws IOException {
        File base = new File(System.getProperty("java.io.tmpdir"), name + "-" + System.nanoTime());
        if (!base.mkdirs()) {
            throw new IOException("could not create " + base);
        }
        base.deleteOnExit();
        return base;
    }

    private static void write(File file, String contents) throws IOException {
        Writer writer =
                new OutputStreamWriter(new FileOutputStream(file), Charset.forName("UTF-8"));
        try {
            writer.write(contents);
        } finally {
            writer.close();
        }
        file.deleteOnExit();
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

    private static void report() {
        System.out.println();
        System.out.println(passed + " passed, 0 failed");
    }

    private ConfigTests() {}
}
