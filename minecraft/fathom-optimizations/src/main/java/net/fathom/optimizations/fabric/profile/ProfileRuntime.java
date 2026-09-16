package net.fathom.optimizations.fabric.profile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.fathom.optimizations.core.module.ModuleLog;
import net.fathom.optimizations.core.profile.FathomProfile;
import net.fathom.optimizations.core.profile.ProfileException;
import net.fathom.optimizations.core.profile.ProfileLocation;
import net.fathom.optimizations.core.profile.ProfileMigration;
import net.fathom.optimizations.core.profile.ProfileStore;
import net.fathom.optimizations.core.registry.OptimizationRegistry;
import net.fathom.optimizations.core.registry.SwitchBoard;
import net.fathom.optimizations.fabric.hud.FathomHud;

/**
 * The live Fathom Profile, and every operation on the collection the in-game
 * interface offers.
 *
 * <p>One instance, created at startup and reached through {@link FathomHud#profiles()}.
 * The hub header's picker, the Profiles page and the HUD editor all call these
 * methods; none of them touches {@link ProfileStore} or {@link ProfileSwitch}
 * directly, so the transaction rules are enforced in exactly one place.
 *
 * <h2>The switch transaction</h2>
 *
 * <ol>
 *   <li>Save the departing profile, so edits made in it are not lost to the switch.
 *   <li>Load and validate the target. A failure here changes nothing.
 *   <li>Capture the running client.
 *   <li>Apply the target. If that throws, apply the capture back.
 *   <li>Only then move the live profile and persist {@code lastProfileId}.
 * </ol>
 */
public final class ProfileRuntime {

    private final File gameDirectory;
    private final ProfileStore store;
    private final ProfileLocation.Resolved location;
    private final FathomHud hud;
    private final SwitchBoard switches;
    private final OptimizationRegistry optimizations;
    /** Each optimization's state before any profile touched it. */
    private final Map<String, Boolean> baseline;
    private final List<String> complaints = new ArrayList<String>();

    private FathomProfile live;
    private String notice = "";
    private long noticeAt;

    private ProfileRuntime(File gameDirectory, ProfileStore store, ProfileLocation.Resolved location,
            FathomHud hud, SwitchBoard switches, OptimizationRegistry optimizations) {
        this.gameDirectory = gameDirectory;
        this.store = store;
        this.location = location;
        this.hud = hud;
        this.switches = switches;
        this.optimizations = optimizations;
        this.baseline = ProfileSwitch.snapshot(switches, optimizations);
    }

    /**
     * Find the collection, migrate the old files once, and apply the last profile.
     *
     * <p>Never throws. A collection that cannot be opened leaves the client on
     * whatever it loaded at construction, with the reason in the log and in
     * Diagnostics — a menu that fails to open over a profile file would be worse.
     */
    public static ProfileRuntime start(File gameDirectory, String sessionRoot, FathomHud hud,
            SwitchBoard switches, OptimizationRegistry optimizations) {
        ProfileLocation.Resolved where = ProfileLocation.resolve(sessionRoot, gameDirectory);
        ProfileRuntime runtime = new ProfileRuntime(gameDirectory, new ProfileStore(where.directory), where,
                hud, switches, optimizations);

        try {
            ProfileMigration.Report report = ProfileMigration.run(runtime.store, gameDirectory);
            if (!report.created.isEmpty()) {
                ModuleLog.say("profiles: " + report.summary()
                        + (report.backup == null ? "" : "; originals backed up to " + report.backup));
            }
            if (!report.unmapped.isEmpty()) {
                ModuleLog.say("profiles: kept in the backup but not mapped: " + report.unmapped);
            }
        } catch (ProfileException failure) {
            runtime.complaints.add("migration skipped: " + failure.getMessage());
            ModuleLog.say("profiles: migration skipped (" + failure.getMessage()
                    + "); the old settings files are untouched");
        }

        try {
            FathomProfile last = runtime.store.resolveLive();
            List<String> skipped = new ArrayList<String>();
            ProfileSwitch.apply(last, hud, switches, optimizations, runtime.baseline, skipped);
            runtime.live = last;
            if (!skipped.isEmpty()) {
                ModuleLog.say("profiles: " + last.name() + " names things this build does not have: " + skipped);
            }
            ModuleLog.say("profiles: " + last.name() + " (" + where.source.label() + ", " + where.directory + ")");
        } catch (ProfileException failure) {
            runtime.complaints.add("could not open the live profile: " + failure.getMessage());
            ModuleLog.say("profiles: could not open the live profile (" + failure.getMessage() + ")");
            runtime.live = ProfileSwitch.capture(FathomProfile.defaultProfile(), hud, switches, optimizations,
                    runtime.baseline);
        }
        return runtime;
    }

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    public FathomProfile live() {
        return live;
    }

    public String liveId() {
        return live.id();
    }

    public String liveName() {
        return live.name();
    }

    /** Documents that would not load on the latest listing. Startup problems stay in {@link #complaints}. */
    private List<String> listingComplaints = new ArrayList<String>();
    private List<FathomProfile> cached = new ArrayList<FathomProfile>();
    private long cachedAt;

    /** Every profile, sorted by name, read from disk now so the launcher's edits appear. */
    public List<FathomProfile> profiles() {
        List<String> found = new ArrayList<String>();
        List<FathomProfile> out = store.list(found);
        listingComplaints = found;
        cached = out;
        cachedAt = System.currentTimeMillis();
        return out;
    }

    /**
     * {@link #profiles()}, re-read at most twice a second.
     *
     * <p>For the header picker, which asks every frame. Listing a directory of
     * JSON sixty times a second would be the most expensive thing the hub did,
     * for a list that changes when somebody clicks — or when the launcher edits
     * it, which half a second of staleness does not hurt.
     */
    public List<FathomProfile> cachedProfiles() {
        if (System.currentTimeMillis() - cachedAt > 500L) {
            profiles();
        }
        return cached;
    }

    public ProfileLocation.Resolved location() {
        return location;
    }

    /** For Diagnostics: problems from startup, then documents that would not load. */
    public List<String> complaints() {
        List<String> out = new ArrayList<String>(complaints);
        out.addAll(listingComplaints);
        return out;
    }

    /** A short confirmation for the header, empty once it has been shown for a while. */
    public String notice() {
        if (!notice.isEmpty() && System.currentTimeMillis() - noticeAt > 2600L) {
            notice = "";
        }
        return notice;
    }

    private void say(String what) {
        notice = what;
        noticeAt = System.currentTimeMillis();
        // Whatever was just confirmed changed the collection or the live profile.
        cachedAt = 0L;
    }

    // -----------------------------------------------------------------------
    // Switching
    // -----------------------------------------------------------------------

    /** Make {@code id} live. Throws, leaving the current profile exactly as it was, if it cannot. */
    public void switchTo(String id) throws ProfileException {
        if (id == null || id.equals(live.id())) {
            return;
        }
        saveLive();
        FathomProfile target = store.load(id);

        FathomProfile before = ProfileSwitch.capture(live, hud, switches, optimizations, baseline);
        List<String> skipped = new ArrayList<String>();
        try {
            ProfileSwitch.apply(target, hud, switches, optimizations, baseline, skipped);
        } catch (ProfileException failure) {
            rollBack(before);
            throw failure;
        } catch (RuntimeException failure) {
            rollBack(before);
            throw new ProfileException("could not switch to " + target.name() + " (" + failure + ")", failure);
        }

        live = target;
        try {
            store.setLastProfileId(target.id());
        } catch (ProfileException failure) {
            // Switched, but it will not survive a restart. Said, not hidden.
            complaints.add(failure.getMessage());
            say(target.name() + " — not remembered for next time");
            return;
        }
        if (!skipped.isEmpty()) {
            ModuleLog.say("profiles: " + target.name() + " names things this build does not have: " + skipped);
        }
        say("Switched to " + target.name());
    }

    private void rollBack(FathomProfile before) {
        try {
            ProfileSwitch.apply(before, hud, switches, optimizations, baseline, new ArrayList<String>());
        } catch (ProfileException impossible) {
            // A capture of the running client is valid by construction. If it is
            // not, the log is the only honest place left to say so.
            ModuleLog.say("profiles: could not restore after a failed switch (" + impossible.getMessage() + ")");
        }
    }

    /** Write the running client into the live profile. */
    public void saveLive() throws ProfileException {
        FathomProfile captured = ProfileSwitch.capture(live, hud, switches, optimizations, baseline);
        store.save(captured);
        live = captured;
    }

    /**
     * {@link #saveLive} for a screen that is closing or an edit being committed:
     * the snapshot and JSON are taken now, on the render thread, and the disk
     * write happens on the store's writer thread so the frame does not wait for
     * a sync. Returns a validation failure now, or an I/O failure from an
     * earlier background write, rather than throwing.
     */
    public String saveLiveQuietly() {
        try {
            FathomProfile captured = ProfileSwitch.capture(live, hud, switches, optimizations, baseline);
            store.saveInBackground(captured);
            live = captured;
            return store.takeBackgroundFailure();
        } catch (ProfileException failure) {
            return failure.getMessage();
        }
    }

    // -----------------------------------------------------------------------
    // Collection operations
    // -----------------------------------------------------------------------

    /** A new profile at every module's defaults, made live. */
    public FathomProfile create(String name) throws ProfileException {
        saveLive();
        FathomProfile created = store.create(name == null || name.trim().isEmpty() ? "New profile" : name);
        switchTo(created.id());
        return created;
    }

    /** A copy of {@code id}, not made live. */
    public FathomProfile duplicate(String id) throws ProfileException {
        if (id.equals(live.id())) {
            saveLive();
        }
        FathomProfile source = store.load(id);
        FathomProfile copy = store.duplicate(id, source.name() + " copy");
        say("Duplicated " + source.name());
        return copy;
    }

    public void rename(String id, String name) throws ProfileException {
        if (id.equals(live.id())) {
            saveLive();
        }
        FathomProfile renamed = store.rename(id, name);
        if (id.equals(live.id())) {
            live = renamed;
            hud.layout().setName(renamed.name());
        }
    }

    /**
     * Remove a profile. The last one is refused; the live one hands over to what
     * the store's pointer moves to, applied live, before it goes.
     */
    public void delete(String id) throws ProfileException {
        List<FathomProfile> all = profiles();
        if (all.size() <= 1) {
            throw new ProfileException("the last profile cannot be deleted");
        }
        String name = store.load(id).name();
        if (id.equals(live.id())) {
            String next = null;
            for (FathomProfile profile : all) {
                if (!profile.id().equals(id) && profile.isDefault()) {
                    next = profile.id();
                }
            }
            if (next == null) {
                for (FathomProfile profile : all) {
                    if (!profile.id().equals(id)) {
                        next = profile.id();
                        break;
                    }
                }
            }
            switchTo(next);
        }
        store.delete(id);
        say("Deleted " + name);
    }

    /** Where exports go and imports are read from, inside this game's directory. */
    public File exchangeDirectory() {
        return new File(gameDirectory, "fathom/profile-exchange");
    }

    /** Write {@code id} to the exchange folder. */
    public File export(String id) throws ProfileException {
        if (id.equals(live.id())) {
            saveLive();
        }
        File target = new File(exchangeDirectory(), id + ".json");
        try {
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("could not create " + parent);
            }
            Files.write(target.toPath(), store.export(id).getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new ProfileException("could not export (" + failure.getMessage() + ")", failure);
        }
        say("Exported to " + target.getName());
        return target;
    }

    /** Import every {@code .json} in the exchange folder that is not already an export of ours. */
    public List<FathomProfile> importAll() throws ProfileException {
        List<FathomProfile> out = new ArrayList<FathomProfile>();
        File[] files = exchangeDirectory().listFiles();
        if (files == null) {
            throw new ProfileException("put a profile in " + exchangeDirectory() + " first");
        }
        List<String> refused = new ArrayList<String>();
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".json")) {
                continue;
            }
            try {
                String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                out.add(store.importJson(json));
                File done = new File(file.getParentFile(), file.getName() + ".imported");
                if (!file.renameTo(done)) {
                    // Left in place: importing it again makes a numbered copy, never an overwrite.
                    refused.add(file.getName() + " was imported but could not be marked");
                }
            } catch (IOException failure) {
                refused.add(file.getName() + ": " + failure.getMessage());
            } catch (ProfileException failure) {
                refused.add(file.getName() + ": " + failure.getMessage());
            }
        }
        complaints.addAll(refused);
        say(out.isEmpty() ? "Nothing to import" : "Imported " + out.size());
        return out;
    }
}
