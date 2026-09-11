package net.fathom.optimizations.core.profile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one Fathom Profile collection.
 *
 * <p>The Java half of {@code crates/launcher-core/src/loadout/store.rs}, over the
 * same directory. There is no launcher copy and game copy to synchronise: the
 * launcher and the running game open these same files, and the rules each side
 * follows are the same rules, tested on each side.
 *
 * <h2>The directory is the index</h2>
 *
 * <p>Listing {@code profiles/} is the list of profiles. There is no index file
 * that could name a profile which is not on disk, or miss one that is.
 *
 * <h2>Two writers</h2>
 *
 * <p>The launcher and the game can both write while the game is running. Every
 * write is a temporary file renamed over the target, so neither side can read
 * half a document, and a concurrent edit to the same profile is last-writer-wins
 * — which is the right answer for a loadout somebody changed in two places at
 * once, and the only one available without a lock file that a crashed process
 * would leave behind.
 */
public final class ProfileStore {

    /** Inside the data root. Must match {@code loadout::directory_in}. */
    public static final String DIRECTORY = "profiles";

    /** The pointer file. Must match {@code loadout::store::STATE_FILE}. */
    public static final String STATE_FILE = "client-state.json";

    private final File directory;

    public ProfileStore(File directory) {
        this.directory = directory;
    }

    public File directory() {
        return directory;
    }

    public File fileFor(String id) {
        return new File(directory, id + ".json");
    }

    public File stateFile() {
        return new File(directory, STATE_FILE);
    }

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    /**
     * Every profile that parsed, sorted by name.
     *
     * <p>One corrupt document is reported into {@code complaints} and skipped; it
     * does not hide the others.
     *
     * @param complaints receives one line per document that would not load; may be null
     */
    public List<FathomProfile> list(List<String> complaints) {
        List<FathomProfile> out = new ArrayList<FathomProfile>();
        File[] files = directory.listFiles();
        if (files == null) {
            return out;
        }
        for (File file : files) {
            String fileName = file.getName();
            if (!file.isFile() || !fileName.endsWith(".json") || STATE_FILE.equals(fileName)) {
                continue;
            }
            try {
                out.add(read(file));
            } catch (ProfileException problem) {
                if (complaints != null) {
                    complaints.add(fileName + ": " + problem.getMessage());
                }
            }
        }
        Collections.sort(out, new Comparator<FathomProfile>() {
            @Override
            public int compare(FathomProfile a, FathomProfile b) {
                return a.name().toLowerCase(Locale.ROOT).compareTo(b.name().toLowerCase(Locale.ROOT));
            }
        });
        return out;
    }

    public List<String> ids() {
        List<String> out = new ArrayList<String>();
        for (FathomProfile profile : list(null)) {
            out.add(profile.id());
        }
        return out;
    }

    public FathomProfile load(String id) throws ProfileException {
        File file = fileFor(id);
        if (!file.isFile()) {
            throw new ProfileException("no profile called " + id);
        }
        return read(file);
    }

    private FathomProfile read(File file) throws ProfileException {
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new ProfileException("could not be read (" + failure.getMessage() + ")", failure);
        }
        FathomProfile profile = FathomProfile.fromJson(text);
        // The filename is the identity. A document whose id disagrees with its
        // file was copied by hand, and trusting the field would give two profiles
        // the same id the first time either was saved.
        String stem = file.getName().substring(0, file.getName().length() - ".json".length());
        if (!profile.id().equals(stem)) {
            throw new ProfileException("says its id is " + profile.id() + " but is stored as " + stem + ".json");
        }
        return profile;
    }

    // -----------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------

    public void save(FathomProfile profile) throws ProfileException {
        if (!FathomProfile.validId(profile.id())) {
            throw new ProfileException(profile.id() + " is not a usable profile id");
        }
        String problem = profile.problem();
        if (problem != null) {
            throw new ProfileException(problem);
        }
        profile.prune();
        profile.touch();
        try {
            atomicWrite(fileFor(profile.id()), profile.toJson());
        } catch (IOException failure) {
            throw new ProfileException("could not save " + profile.name() + " (" + failure.getMessage() + ")", failure);
        }
    }

    public FathomProfile create(String name) throws ProfileException {
        List<String> taken = ids();
        String id = idFromName(name, taken);
        if (id == null) {
            id = freeNumbered(taken);
        }
        FathomProfile profile = new FathomProfile(id, name == null ? "" : name.trim());
        save(profile);
        return profile;
    }

    public FathomProfile duplicate(String id, String name) throws ProfileException {
        FathomProfile original = load(id);
        List<String> taken = ids();
        String newId = idFromName(name, taken);
        if (newId == null) {
            newId = freeNumbered(taken);
        }
        FathomProfile copy = original.duplicate(newId, name == null ? "" : name.trim());
        save(copy);
        return copy;
    }

    /**
     * Change a display name. The id stays, because it is in the pointer file and
     * in whatever the launcher has selected.
     */
    public FathomProfile rename(String id, String name) throws ProfileException {
        FathomProfile profile = load(id);
        profile.setName(name);
        save(profile);
        return profile;
    }

    /**
     * Remove a profile. The last one is refused, and a live one hands the pointer
     * to something that exists before it goes.
     */
    public void delete(String id) throws ProfileException {
        List<String> remaining = new ArrayList<String>(ids());
        remaining.remove(id);
        if (remaining.isEmpty()) {
            throw new ProfileException("the last profile cannot be deleted");
        }
        File file = fileFor(id);
        if (!file.isFile()) {
            throw new ProfileException("no profile called " + id);
        }
        if (!file.delete()) {
            throw new ProfileException("could not delete " + file.getName());
        }
        if (id.equals(lastProfileId())) {
            String next = remaining.contains(FathomProfile.DEFAULT_ID) ? FathomProfile.DEFAULT_ID : remaining.get(0);
            try {
                setLastProfileId(next);
            } catch (ProfileException ignored) {
                // The profile is gone either way; resolveLive repairs a dangling
                // pointer on the next start without creating anything.
            }
        }
    }

    /**
     * What is live, guaranteeing the collection is not empty.
     *
     * <p>The only place a profile is ever created without somebody asking, and
     * only when there are none at all. A pointer that names a missing profile
     * falls back to an existing Default or the first profile there is; it never
     * produces a second Default.
     */
    public FathomProfile resolveLive() throws ProfileException {
        List<FathomProfile> profiles = list(null);
        if (profiles.isEmpty()) {
            FathomProfile created = FathomProfile.defaultProfile();
            save(created);
            setLastProfileId(created.id());
            return created;
        }
        String last = lastProfileId();
        if (last != null) {
            for (FathomProfile profile : profiles) {
                if (profile.id().equals(last)) {
                    return profile;
                }
            }
        }
        FathomProfile chosen = profiles.get(0);
        for (FathomProfile profile : profiles) {
            if (profile.isDefault()) {
                chosen = profile;
                break;
            }
        }
        setLastProfileId(chosen.id());
        return chosen;
    }

    // -----------------------------------------------------------------------
    // The pointer
    // -----------------------------------------------------------------------

    public String lastProfileId() {
        return stateValue("lastProfileId");
    }

    public String migratedFrom() {
        return stateValue("migratedFrom");
    }

    public void setLastProfileId(String id) throws ProfileException {
        writeState(id, migratedFrom());
    }

    public void setMigratedFrom(String stamp) throws ProfileException {
        writeState(lastProfileId(), stamp);
    }

    private void writeState(String last, String migrated) throws ProfileException {
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("schema", Integer.valueOf(FathomProfile.SCHEMA));
        if (last != null) {
            state.put("lastProfileId", last);
        }
        if (migrated != null) {
            state.put("migratedFrom", migrated);
        }
        try {
            atomicWrite(stateFile(), Json.write(state));
        } catch (IOException failure) {
            throw new ProfileException("could not record the live profile (" + failure.getMessage() + ")", failure);
        }
    }

    private String stateValue(String key) {
        File file = stateFile();
        if (!file.isFile()) {
            return null;
        }
        try {
            Object root = Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (root instanceof Map) {
                Object value = ((Map<?, ?>) root).get(key);
                return value instanceof String ? (String) value : null;
            }
        } catch (IOException unreadable) {
            return null;
        } catch (ProfileException corrupt) {
            return null;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Import and export
    // -----------------------------------------------------------------------

    public String export(String id) throws ProfileException {
        return load(id).toJson();
    }

    /**
     * Take a document from elsewhere under a free id. The incoming id is never
     * honoured, so importing a friend's "default" cannot overwrite your own.
     */
    public FathomProfile importJson(String json) throws ProfileException {
        FathomProfile incoming = FathomProfile.fromJson(json, false);
        List<String> taken = ids();
        String id = idFromName(incoming.name(), taken);
        if (id == null) {
            id = freeNumbered(taken);
        }
        FathomProfile profile = incoming.duplicate(id, incoming.name());
        save(profile);
        return profile;
    }

    // -----------------------------------------------------------------------
    // Ids
    // -----------------------------------------------------------------------

    /**
     * An id from a display name, avoiding those taken. Null when nothing usable
     * survives. The same rules as {@code ProfileId::from_display_name}, so the
     * launcher and the game agree on what "Bedwars" becomes.
     */
    public static String idFromName(String name, List<String> taken) {
        if (name == null) {
            return null;
        }
        StringBuilder slug = new StringBuilder();
        boolean lastHyphen = false;
        for (int index = 0; index < name.length(); index++) {
            char c = name.charAt(index);
            boolean ascii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (ascii) {
                slug.append(Character.toLowerCase(c));
                lastHyphen = false;
            } else if (slug.length() > 0 && !lastHyphen) {
                slug.append('-');
                lastHyphen = true;
            }
        }
        trimHyphens(slug);
        if (slug.length() > FathomProfile.MAX_ID_LENGTH) {
            slug.setLength(FathomProfile.MAX_ID_LENGTH);
        }
        trimHyphens(slug);
        if (slug.length() == 0) {
            return null;
        }
        String base = slug.toString();
        if (!FathomProfile.validId(base)) {
            base = base + "-1";
            if (!FathomProfile.validId(base)) {
                return null;
            }
        }
        if (!taken.contains(base)) {
            return base;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String tail = "-" + suffix;
            String head = base.length() + tail.length() > FathomProfile.MAX_ID_LENGTH
                    ? base.substring(0, FathomProfile.MAX_ID_LENGTH - tail.length())
                    : base;
            String candidate = head + tail;
            if (FathomProfile.validId(candidate) && !taken.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void trimHyphens(StringBuilder slug) {
        while (slug.length() > 0 && slug.charAt(slug.length() - 1) == '-') {
            slug.setLength(slug.length() - 1);
        }
    }

    private static String freeNumbered(List<String> taken) {
        for (int suffix = 1; suffix < 10000; suffix++) {
            String candidate = "profile-" + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return FathomProfile.DEFAULT_ID;
    }

    /**
     * Write a whole file or nothing: a sibling temporary file, synced, renamed
     * over the target.
     */
    static void atomicWrite(File target, String text) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        File temporary = File.createTempFile(target.getName() + ".", ".tmp", parent);
        boolean moved = false;
        try {
            FileOutputStream out = new FileOutputStream(temporary);
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved && temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }
}
