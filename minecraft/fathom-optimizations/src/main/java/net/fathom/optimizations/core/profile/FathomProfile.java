package net.fathom.optimizations.core.profile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One Fathom Profile: a loadout.
 *
 * <p>The Java half of {@code crates/launcher-core/src/loadout/model.rs}. Both
 * read and write the same {@code profiles/<id>.json}; see
 * {@code docs/fathom-profiles.md} for the format and why this is a different
 * thing from the Game Instance the launcher also used to call a profile.
 *
 * <h2>What it never holds</h2>
 *
 * <p>A Minecraft version, a loader, a Java path, a game directory, an external
 * jar set, or anything about an account. A profile is switched while the game is
 * running, so anything that could not change under a running client does not
 * belong in it.
 *
 * <h2>Only what differs</h2>
 *
 * <p>A module at its declared defaults writes nothing. Recording defaults would
 * freeze whatever a default was on the day a profile was first saved into that
 * profile forever, and improving a default would then change nothing for
 * anybody who had ever opened the menu.
 *
 * <h2>A document, not a live object</h2>
 *
 * <p>This is data. Applying it to the running client is
 * {@code fabric.profile.ProfileSwitch}'s job, and capturing the running client
 * into one is {@code ProfileSwitch.capture}'s. Keeping the document free of any
 * Minecraft type is what lets it be tested with a bare JDK against exactly the
 * bytes the launcher writes.
 */
public final class FathomProfile {

    /** Format version. Must match {@code loadout::model::SCHEMA}. */
    public static final int SCHEMA = 1;

    /** The id every collection can fall back to. */
    public static final String DEFAULT_ID = "default";

    /** What it is called when it has to be created. */
    public static final String DEFAULT_NAME = "Default";

    static final int MAX_ID_LENGTH = 48;
    static final int MAX_NAME_LENGTH = 64;

    private static final String[] RESERVED = {"client-state", "index", "backup", "backups"};
    private static final String[] DEVICES = {
        "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8",
        "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    };

    // -----------------------------------------------------------------------
    // Parts
    // -----------------------------------------------------------------------

    /** One module, as far as it differs from its own defaults. */
    public static final class ModuleState {
        /** Null means "whatever the module says", so a new module arrives as its author intended. */
        public Boolean enabled;
        public boolean favourite;
        /** Setting id to its serialised form. The module owns the parsing. */
        public final TreeMap<String, String> settings = new TreeMap<String, String>();
        /** Keybind id to key code, as text for the same reason. */
        public final TreeMap<String, String> keys = new TreeMap<String, String>();

        public boolean isEmpty() {
            return enabled == null && !favourite && settings.isEmpty() && keys.isEmpty();
        }

        ModuleState copy() {
            ModuleState out = new ModuleState();
            out.enabled = enabled;
            out.favourite = favourite;
            out.settings.putAll(settings);
            out.keys.putAll(keys);
            return out;
        }
    }

    /** Where one widget sits, in Minecraft's scaled pixels. */
    public static final class HudEntry {
        /** Kebab-case, e.g. {@code top-left}. */
        public String anchor = "top-left";
        public int x;
        public int y;
        public float scale = 1f;
        public float opacity = 1f;
        public boolean hidden;

        HudEntry copy() {
            HudEntry out = new HudEntry();
            out.anchor = anchor;
            out.x = x;
            out.y = y;
            out.scale = scale;
            out.opacity = opacity;
            out.hidden = hidden;
            return out;
        }
    }

    /** The client's own preferences — not any one module's. Null means default. */
    public static final class Client {
        public Integer hubKey;
        public String uiScale;
        public Boolean highQualityUi;
        public String hudFont;

        boolean isEmpty() {
            return hubKey == null && uiScale == null && highQualityUi == null && hudFont == null;
        }

        Client copy() {
            Client out = new Client();
            out.hubKey = hubKey;
            out.uiScale = uiScale;
            out.highQualityUi = highQualityUi;
            out.hudFont = hudFont;
            return out;
        }
    }

    /** Live-safe performance switches. The JVM-level ones belong to the Game Instance. */
    public static final class Performance {
        public String preset;
        public final TreeMap<String, Boolean> switches = new TreeMap<String, Boolean>();

        boolean isEmpty() {
            return preset == null && switches.isEmpty();
        }

        Performance copy() {
            Performance out = new Performance();
            out.preset = preset;
            out.switches.putAll(switches);
            return out;
        }
    }

    // -----------------------------------------------------------------------
    // The document
    // -----------------------------------------------------------------------

    private final String id;
    private String name;
    private long createdAt;
    private long updatedAt;
    private final TreeMap<String, ModuleState> modules = new TreeMap<String, ModuleState>();
    private final TreeMap<String, HudEntry> hud = new TreeMap<String, HudEntry>();
    private Client client = new Client();
    private Performance performance = new Performance();

    public FathomProfile(String id, String name) {
        this.id = id;
        this.name = name == null ? "" : name;
        long now = now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static FathomProfile defaultProfile() {
        return new FathomProfile(DEFAULT_ID, DEFAULT_NAME);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String value) {
        this.name = value == null ? "" : value.trim();
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public boolean isDefault() {
        return DEFAULT_ID.equals(id);
    }

    /** Live and mutable, on purpose: the capture fills these in place. */
    public TreeMap<String, ModuleState> modules() {
        return modules;
    }

    /** The state for a module, created empty if there is none. */
    public ModuleState module(String moduleId) {
        ModuleState state = modules.get(moduleId);
        if (state == null) {
            state = new ModuleState();
            modules.put(moduleId, state);
        }
        return state;
    }

    public TreeMap<String, HudEntry> hud() {
        return hud;
    }

    public Client client() {
        return client;
    }

    public Performance performance() {
        return performance;
    }

    /** How many modules this profile switches on explicitly. */
    public int modulesOn() {
        int count = 0;
        for (ModuleState state : modules.values()) {
            if (Boolean.TRUE.equals(state.enabled)) {
                count++;
            }
        }
        return count;
    }

    void touch() {
        updatedAt = now();
    }

    /** Drop module entries that say nothing. */
    public void prune() {
        java.util.Iterator<Map.Entry<String, ModuleState>> entries = modules.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue().isEmpty()) {
                entries.remove();
            }
        }
    }

    /**
     * Whether this profile holds nothing anybody chose.
     *
     * <p>Asked by migration, which may replace a blank placeholder with real data
     * but must never replace a Default somebody has edited.
     */
    public boolean isBlank() {
        for (ModuleState state : modules.values()) {
            if (!state.isEmpty()) {
                return false;
            }
        }
        return hud.isEmpty() && client.isEmpty() && performance.isEmpty();
    }

    /**
     * The same identity with nothing in it, for capturing the running client
     * into. Keeps the creation time and the informational preset, which capture
     * has no way to recover from live state.
     */
    public FathomProfile emptied() {
        FathomProfile out = new FathomProfile(id, name);
        out.createdAt = createdAt;
        out.performance.preset = performance.preset;
        return out;
    }

    /** A copy under a new identity, for Duplicate and Import. */
    public FathomProfile duplicate(String newId, String newName) {
        FathomProfile out = new FathomProfile(newId, newName);
        for (Map.Entry<String, ModuleState> entry : modules.entrySet()) {
            out.modules.put(entry.getKey(), entry.getValue().copy());
        }
        for (Map.Entry<String, HudEntry> entry : hud.entrySet()) {
            out.hud.put(entry.getKey(), entry.getValue().copy());
        }
        out.client = client.copy();
        out.performance = performance.copy();
        return out;
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    /** Whether a string can be a profile id: a filename on three platforms. */
    public static boolean validId(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_ID_LENGTH) {
            return false;
        }
        for (int index = 0; index < raw.length(); index++) {
            char c = raw.charAt(index);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                return false;
            }
        }
        if (raw.charAt(0) == '-' || raw.charAt(raw.length() - 1) == '-') {
            return false;
        }
        for (String reserved : RESERVED) {
            if (reserved.equals(raw)) {
                return false;
            }
        }
        for (String device : DEVICES) {
            if (device.equals(raw)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The first reason this document cannot be used, or null.
     *
     * <p>Checked before a switch, not during one. The switch transaction relies on
     * every failure it can have happening here, before anything live is touched.
     */
    public String problem() {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "a profile has to have a name";
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_NAME_LENGTH) {
            return "a profile name cannot be longer than " + MAX_NAME_LENGTH + " characters";
        }
        for (Map.Entry<String, HudEntry> entry : hud.entrySet()) {
            float scale = entry.getValue().scale;
            if (Float.isNaN(scale) || Float.isInfinite(scale) || scale <= 0f) {
                return "widget " + entry.getKey() + " has an impossible scale of " + scale;
            }
            float opacity = entry.getValue().opacity;
            if (Float.isNaN(opacity) || opacity < 0f || opacity > 1f) {
                return "widget " + entry.getKey() + " has an opacity of " + opacity + ", not between 0 and 1";
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // JSON
    // -----------------------------------------------------------------------

    /** Read a stored profile. The id must be a valid filename. */
    public static FathomProfile fromJson(String json) throws ProfileException {
        return fromJson(json, true);
    }

    /**
     * Read a document.
     *
     * @param strictId false for an import, whose id is replaced anyway and
     *     should not stop somebody receiving a profile from a build with looser rules
     */
    static FathomProfile fromJson(String json, boolean strictId) throws ProfileException {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) {
            throw new ProfileException("a profile is a JSON object");
        }
        Map<?, ?> document = (Map<?, ?>) root;

        Object schemaValue = document.get("schema");
        if (!(schemaValue instanceof Number)) {
            throw new ProfileException("the document has no schema");
        }
        long schema = ((Number) schemaValue).longValue();
        if (schema < 1 || schema > SCHEMA) {
            // A newer schema is refused rather than guessed at. Half-applying a
            // document written by a build that knows something this one does not
            // is how a profile switch ends with somebody's HUD in the wrong place.
            throw new ProfileException("profile schema " + schema + " is not one this build can read");
        }

        String id = text(document.get("id"));
        if (id == null || (strictId && !validId(id))) {
            throw new ProfileException("the document has no usable id");
        }
        FathomProfile profile = new FathomProfile(id, text(document.get("name")));
        profile.createdAt = whole(document.get("createdAt"), profile.createdAt);
        profile.updatedAt = whole(document.get("updatedAt"), profile.updatedAt);

        Map<?, ?> modules = object(document.get("modules"));
        for (Map.Entry<?, ?> entry : modules.entrySet()) {
            Map<?, ?> fields = object(entry.getValue());
            ModuleState state = new ModuleState();
            Object enabled = fields.get("enabled");
            state.enabled = enabled instanceof Boolean ? (Boolean) enabled : null;
            state.favourite = Boolean.TRUE.equals(fields.get("favourite"));
            for (Map.Entry<?, ?> setting : object(fields.get("settings")).entrySet()) {
                if (setting.getValue() != null) {
                    state.settings.put(String.valueOf(setting.getKey()), scalar(setting.getValue()));
                }
            }
            for (Map.Entry<?, ?> key : object(fields.get("keys")).entrySet()) {
                if (key.getValue() != null) {
                    state.keys.put(String.valueOf(key.getKey()), scalar(key.getValue()));
                }
            }
            profile.modules.put(String.valueOf(entry.getKey()), state);
        }

        Map<?, ?> hud = object(document.get("hud"));
        for (Map.Entry<?, ?> entry : hud.entrySet()) {
            Map<?, ?> fields = object(entry.getValue());
            HudEntry placement = new HudEntry();
            String anchor = text(fields.get("anchor"));
            if (anchor != null && !anchor.trim().isEmpty()) {
                placement.anchor = anchor.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            }
            placement.x = (int) whole(fields.get("x"), 0L);
            placement.y = (int) whole(fields.get("y"), 0L);
            placement.scale = decimal(fields.get("scale"), 1f);
            placement.opacity = decimal(fields.get("opacity"), 1f);
            placement.hidden = Boolean.TRUE.equals(fields.get("hidden"));
            profile.hud.put(String.valueOf(entry.getKey()), placement);
        }

        Map<?, ?> client = object(document.get("client"));
        Object hubKey = client.get("hubKey");
        profile.client.hubKey = hubKey instanceof Number ? Integer.valueOf(((Number) hubKey).intValue()) : null;
        profile.client.uiScale = text(client.get("uiScale"));
        Object quality = client.get("highQualityUi");
        profile.client.highQualityUi = quality instanceof Boolean ? (Boolean) quality : null;
        profile.client.hudFont = text(client.get("hudFont"));

        Map<?, ?> performance = object(document.get("performance"));
        profile.performance.preset = text(performance.get("preset"));
        for (Map.Entry<?, ?> entry : object(performance.get("switches")).entrySet()) {
            if (entry.getValue() instanceof Boolean) {
                profile.performance.switches.put(String.valueOf(entry.getKey()), (Boolean) entry.getValue());
            }
        }

        String problem = profile.problem();
        if (problem != null) {
            throw new ProfileException(problem);
        }
        return profile;
    }

    /**
     * The document, in the shape {@code serde_json} writes it.
     *
     * <p>Field names and omission rules match the Rust struct exactly — camelCase
     * at the top and in {@code client}, empty maps and false flags left out, and
     * {@code client} and {@code performance} always present. Byte-for-byte
     * identity with the launcher is not required, since both sides parse; what is
     * required is that each side can read everything the other writes.
     */
    public String toJson() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("schema", Integer.valueOf(SCHEMA));
        out.put("id", id);
        out.put("name", name);
        out.put("createdAt", Long.valueOf(createdAt));
        out.put("updatedAt", Long.valueOf(updatedAt));

        if (!modules.isEmpty()) {
            Map<String, Object> moduleMap = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, ModuleState> entry : modules.entrySet()) {
                ModuleState state = entry.getValue();
                Map<String, Object> fields = new LinkedHashMap<String, Object>();
                if (state.enabled != null) {
                    fields.put("enabled", state.enabled);
                }
                if (state.favourite) {
                    fields.put("favourite", Boolean.TRUE);
                }
                if (!state.settings.isEmpty()) {
                    fields.put("settings", new LinkedHashMap<String, Object>(state.settings));
                }
                if (!state.keys.isEmpty()) {
                    fields.put("keys", new LinkedHashMap<String, Object>(state.keys));
                }
                moduleMap.put(entry.getKey(), fields);
            }
            out.put("modules", moduleMap);
        }

        if (!hud.isEmpty()) {
            Map<String, Object> hudMap = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, HudEntry> entry : hud.entrySet()) {
                HudEntry placement = entry.getValue();
                Map<String, Object> fields = new LinkedHashMap<String, Object>();
                fields.put("anchor", placement.anchor);
                fields.put("x", Integer.valueOf(placement.x));
                fields.put("y", Integer.valueOf(placement.y));
                fields.put("scale", Float.valueOf(placement.scale));
                fields.put("opacity", Float.valueOf(placement.opacity));
                if (placement.hidden) {
                    fields.put("hidden", Boolean.TRUE);
                }
                hudMap.put(entry.getKey(), fields);
            }
            out.put("hud", hudMap);
        }

        Map<String, Object> clientMap = new LinkedHashMap<String, Object>();
        if (client.hubKey != null) {
            clientMap.put("hubKey", client.hubKey);
        }
        if (client.uiScale != null) {
            clientMap.put("uiScale", client.uiScale);
        }
        if (client.highQualityUi != null) {
            clientMap.put("highQualityUi", client.highQualityUi);
        }
        if (client.hudFont != null) {
            clientMap.put("hudFont", client.hudFont);
        }
        out.put("client", clientMap);

        Map<String, Object> performanceMap = new LinkedHashMap<String, Object>();
        if (performance.preset != null) {
            performanceMap.put("preset", performance.preset);
        }
        if (!performance.switches.isEmpty()) {
            performanceMap.put("switches", new LinkedHashMap<String, Object>(performance.switches));
        }
        out.put("performance", performanceMap);

        return Json.write(out);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Map<?, ?> object(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : java.util.Collections.emptyMap();
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String scalar(Object value) {
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (number == Math.rint(number) && !Double.isInfinite(number)) {
                return String.valueOf((long) number);
            }
        }
        return String.valueOf(value);
    }

    private static long whole(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static float decimal(Object value, float fallback) {
        return value instanceof Number ? ((Number) value).floatValue() : fallback;
    }

    static long now() {
        return System.currentTimeMillis() / 1000L;
    }
}
