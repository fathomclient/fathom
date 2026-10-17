package net.fathom.optimizations.tools;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Implementation;
import net.fathom.optimizations.core.module.ModuleRegistry;

/**
 * The public website's data bundle: {@code gradle exportWebData}.
 *
 * <h2>Why</h2>
 *
 * <p>The website lives in another repository and must not hold hand-copied
 * versions, optimization lists, benchmark numbers or background lists, which drift
 * the day after they are copied. This writes them from the same sources the client
 * uses -- the optimization registry, the module catalogue, the background manifest,
 * {@code gradle.properties}, the launcher's Tauri configuration, committed benchmark
 * reports and an approved-media list -- into {@code web-export/}, which the website
 * consumes as it is. See {@code docs/web-export.md}.
 *
 * <h2>What it refuses</h2>
 *
 * <ul>
 *   <li>A benchmark scene or arm without full context (build, GPU, display, video
 *       settings), with rejected or unfocused frames, or with too few frames. It is
 *       left out, not estimated.
 *   <li>The sky-only scene, at any frame rate: it is not gameplay.
 *   <li>Optimizations that do not run in game, and modules that are not working.
 *   <li>Media that is not an approved file in this repository.
 *   <li>Output containing anything that looks private -- paths, user names, e-mail
 *       addresses, tokens, UUIDs, local addresses and ports. The export fails and
 *       nothing is written.
 * </ul>
 *
 * <p>Deterministic: the same repository state gives byte-identical output. No
 * timestamps, no machine names, keys in a fixed order, files sorted.
 */
public final class WebExport {

    /** Bumped only for a change a website consuming an older bundle cannot read. */
    public static final int SCHEMA = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path root;
    private final Map<String, String> gradle;
    private final JsonObject registry;
    /** Files to copy: published path -> source. */
    private final Map<String, Path> copies = new TreeMap<String, Path>();
    private final List<String> skipped = new ArrayList<String>();

    private WebExport(Path root) throws IOException {
        this.root = root;
        this.gradle = properties(root.resolve("minecraft/fathom-optimizations/gradle.properties"));
        this.registry = readJson(root.resolve("assets/optimizations/registry.json")).getAsJsonObject();
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Sanitizer.selfTest();
        WebExport export = new WebExport(root);

        Map<String, JsonObject> documents = new LinkedHashMap<String, JsonObject>();
        documents.put("product.json", export.product());
        documents.put("performance.json", export.performance());
        documents.put("backgrounds.json", export.backgrounds());
        documents.put("optimizations.json", export.optimizations());
        documents.put("media-manifest.json", export.media());

        Map<String, String> texts = new LinkedHashMap<String, String>();
        List<String> problems = new ArrayList<String>();
        for (Map.Entry<String, JsonObject> document : documents.entrySet()) {
            String text = GSON.toJson(document.getValue()) + "\n";
            texts.put(document.getKey(), text);
            problems.addAll(Sanitizer.scan(document.getKey(), text));
        }
        for (String published : export.copies.keySet()) {
            problems.addAll(Sanitizer.scan("asset path", published));
        }
        Path out = root.resolve("web-export");
        if (!problems.isEmpty()) {
            // No bundle at all, rather than the last one: a consumer must not
            // mistake an older export for this one.
            deleteTree(out);
            System.err.println("web export REFUSED: the output contains what looks like private data.");
            for (String problem : problems) {
                System.err.println("  " + problem);
            }
            System.exit(1);
        }

        deleteTree(out);
        Files.createDirectories(out);
        JsonArray files = new JsonArray();
        for (Map.Entry<String, String> text : texts.entrySet()) {
            byte[] bytes = text.getValue().getBytes(StandardCharsets.UTF_8);
            Files.write(out.resolve(text.getKey()), bytes);
            files.add(fileEntry(text.getKey(), bytes));
        }
        for (Map.Entry<String, Path> copy : export.copies.entrySet()) {
            Path target = out.resolve(copy.getKey());
            Files.createDirectories(target.getParent());
            Files.copy(copy.getValue(), target, StandardCopyOption.REPLACE_EXISTING);
            files.add(fileEntry(copy.getKey(), Files.readAllBytes(target)));
        }
        JsonObject index = new JsonObject();
        index.addProperty("schema", SCHEMA);
        index.addProperty("generatedBy", "gradle exportWebData (net.fathom.optimizations.tools.WebExport)");
        index.addProperty("about", "Public data bundle for the Fathom website. Regenerate, never edit: see docs/web-export.md in the client repository.");
        index.add("files", files);
        String indexText = GSON.toJson(index) + "\n";
        List<String> indexProblems = Sanitizer.scan("index.json", indexText.replaceAll("\"sha256\": \"[0-9a-f]{64}\"", ""));
        if (!indexProblems.isEmpty()) {
            deleteTree(out);
            System.err.println("web export REFUSED: " + indexProblems);
            System.exit(1);
        }
        Files.write(out.resolve("index.json"), indexText.getBytes(StandardCharsets.UTF_8));

        System.out.println("web export: wrote " + (texts.size() + 1) + " JSON files and " + export.copies.size()
                + " assets to " + root.relativize(out));
        for (String note : export.skipped) {
            System.out.println("  left out: " + note);
        }
    }

    // -----------------------------------------------------------------------
    // product.json
    // -----------------------------------------------------------------------

    private JsonObject product() throws IOException {
        JsonObject tauri = readJson(root.resolve("crates/launcher-desktop/tauri.conf.json")).getAsJsonObject();
        JsonObject bundle = tauri.getAsJsonObject("bundle");
        JsonObject out = new JsonObject();
        out.addProperty("schema", SCHEMA);
        out.addProperty("name", rustConstant("crates/launcher-core/src/lib.rs", "PRODUCT_NAME"));
        out.addProperty("displayName", tauri.get("productName").getAsString());
        out.addProperty("tagline", bundle.get("shortDescription").getAsString());
        out.addProperty("description", bundle.get("longDescription").getAsString());

        JsonObject versions = new JsonObject();
        versions.addProperty("launcher", tauri.get("version").getAsString());
        versions.addProperty("client", require(gradle, "mod_version"));
        versions.addProperty("release", hasReleaseTag() ? "released" : "pre-release");
        out.add("versions", versions);

        JsonObject minecraft = new JsonObject();
        minecraft.addProperty("edition", "Java Edition");
        minecraft.addProperty("version", require(gradle, "minecraft_version"));
        minecraft.addProperty("loader", "Legacy Fabric");
        minecraft.addProperty("loaderVersion", require(gradle, "loader_version"));
        minecraft.addProperty("java", Integer.parseInt(require(gradle, "java_version")));
        out.add("minecraft", minecraft);

        JsonArray platforms = new JsonArray();
        for (JsonElement target : bundle.getAsJsonArray("targets")) {
            String installer = target.getAsString();
            JsonObject platform = new JsonObject();
            if ("nsis".equals(installer) || "msi".equals(installer)) {
                platform.addProperty("os", "Windows");
            } else if ("dmg".equals(installer) || "app".equals(installer)) {
                platform.addProperty("os", "macOS");
            } else {
                platform.addProperty("os", "Linux");
            }
            platform.addProperty("installer", installer.toUpperCase(Locale.ROOT));
            platforms.add(platform);
        }
        out.add("platforms", platforms);

        JsonObject authentication = new JsonObject();
        String described = authDescription();
        Matcher parts = Pattern.compile("^(.*?)\\s*\\((.*)\\)$").matcher(described);
        authentication.addProperty("type", parts.matches() ? parts.group(1) : described);
        if (parts.matches()) {
            authentication.addProperty("method", parts.group(2));
        }
        authentication.addProperty("offlineMode", false);
        out.add("authentication", authentication);

        JsonObject capabilities = new JsonObject();
        JsonArray modules = new JsonArray();
        for (FathomModule module : ModuleRegistry.standard().all()) {
            if (module.implementation() != Implementation.WORKING || !"Fathom".equals(module.owner())) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", module.id());
            entry.addProperty("name", module.name());
            entry.addProperty("category", module.category().label());
            entry.addProperty("description", module.description());
            modules.add(entry);
        }
        capabilities.add("modules", modules);
        int implemented = 0;
        for (JsonElement optimization : registry.getAsJsonArray("optimizations")) {
            if ("RUNS_IN_GAME".equals(optimization.getAsJsonObject().get("status").getAsString())) {
                implemented++;
            }
        }
        capabilities.addProperty("optimizationsImplemented", implemented);
        capabilities.addProperty("backgrounds", currentBackgrounds().size());
        out.add("capabilities", capabilities);
        out.add("brand", brand());
        return out;
    }

    /** The brand files the site shows, from assets/brand, in a fixed order. */
    private static final String[][] BRAND_FILES = {
        {"icon", "icon.svg"},
        {"iconPng", "png/fathom-icon-512.png"},
        {"iconPngSmall", "png/fathom-icon-64.png"},
        {"mark", "mark.svg"},
        {"markReversed", "mark-reversed.svg"},
        {"wordmark", "wordmark.svg"},
        {"wordmarkReversed", "wordmark-reversed.svg"},
        {"lockup", "lockup.svg"},
        {"lockupReversed", "lockup-reversed.svg"},
        {"lockupStacked", "lockup-stacked.svg"},
        {"lockupStackedReversed", "lockup-stacked-reversed.svg"},
    };

    /**
     * The identity: the generated brand files (copied, never redrawn) and the
     * inks they are drawn in, resolved from the theme manifest, so the site's
     * own accents can be the icon's.
     */
    private JsonObject brand() throws IOException {
        JsonObject out = new JsonObject();
        out.addProperty("name", "Orbit");
        JsonObject files = new JsonObject();
        for (String[] file : BRAND_FILES) {
            Path source = root.resolve("assets/brand").resolve(file[1]);
            if (!Files.isRegularFile(source)) {
                throw new IllegalStateException("brand file missing: assets/brand/" + file[1]
                        + " (run node assets/brand/build-brand.mjs)");
            }
            String published = "brand/" + source.getFileName().toString();
            copies.put(published, source);
            files.addProperty(file[0], published);
        }
        out.add("files", files);

        JsonObject system = readJson(root.resolve("assets/theme/fathom-theme.json")).getAsJsonObject()
                .getAsJsonObject("system");
        JsonObject palette = system.getAsJsonObject("palette");
        JsonObject colours = new JsonObject();
        for (Map.Entry<String, JsonElement> token : system.getAsJsonObject("brand").entrySet()) {
            if (token.getKey().startsWith("_") || !token.getValue().isJsonPrimitive()
                    || !token.getValue().getAsJsonPrimitive().isString()) {
                continue;
            }
            String value = token.getValue().getAsString();
            if (value.startsWith("@")) {
                value = palette.get(value.substring(1)).getAsString();
            }
            colours.addProperty(token.getKey(), value);
        }
        out.add("colours", colours);
        return out;
    }

    /** {@code describe()} of the production Microsoft provider: the sign-in Fathom offers. */
    private String authDescription() throws IOException {
        String source = read(root.resolve("crates/launcher-core/src/auth/provider.rs"));
        int at = source.indexOf("impl AuthProvider for MicrosoftAuthProvider");
        Matcher describe = Pattern.compile("fn describe\\(&self\\) -> String \\{\\s*\"([^\"]+)\"")
                .matcher(source);
        if (at < 0 || !describe.find(at)) {
            throw new IllegalStateException("cannot read the Microsoft provider's description from provider.rs");
        }
        return describe.group(1);
    }

    private String rustConstant(String file, String name) throws IOException {
        Matcher constant = Pattern.compile("pub const " + name + ": &str = \"([^\"]+)\";")
                .matcher(read(root.resolve(file)));
        if (!constant.find()) {
            throw new IllegalStateException("no constant " + name + " in " + file);
        }
        return constant.group(1);
    }

    /** A {@code v*} tag in this repository: something was released publicly. */
    private boolean hasReleaseTag() throws IOException {
        Path tags = root.resolve(".git/refs/tags");
        if (Files.isDirectory(tags)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tags, "v*")) {
                if (stream.iterator().hasNext()) {
                    return true;
                }
            }
        }
        Path packed = root.resolve(".git/packed-refs");
        return Files.isRegularFile(packed) && read(packed).contains("refs/tags/v");
    }

    // -----------------------------------------------------------------------
    // backgrounds.json
    // -----------------------------------------------------------------------

    private List<JsonObject> currentBackgrounds() throws IOException {
        JsonObject manifest = readJson(root.resolve("assets/backgrounds/manifest.json")).getAsJsonObject();
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (JsonElement element : manifest.getAsJsonArray("backgrounds")) {
            JsonObject background = element.getAsJsonObject();
            // The launcher's set is the user-selectable one; anything else is an
            // in-game-only or retired entry and not "current Fathom".
            if (background.has("launcher") && background.get("launcher").getAsBoolean()) {
                out.add(background);
            }
        }
        return out;
    }

    private JsonObject backgrounds() throws IOException {
        JsonObject manifest = readJson(root.resolve("assets/backgrounds/manifest.json")).getAsJsonObject();
        String defaultId = manifest.get("default").getAsString();
        JsonArray list = new JsonArray();
        for (JsonObject background : currentBackgrounds()) {
            String id = background.get("id").getAsString();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id);
            entry.addProperty("name", background.get("label").getAsString());
            entry.addProperty("type", background.get("type").getAsString());
            entry.addProperty("default", id.equals(defaultId));
            entry.addProperty("status", "current");
            JsonObject files = new JsonObject();
            files.addProperty("media", copyPublic(background.get("asset").getAsString()));
            files.addProperty("still", copyPublic(background.get("still").getAsString()));
            files.addProperty("preview", copyPublic(background.get("thumbnail").getAsString()));
            entry.add("files", files);
            entry.addProperty("width", background.get("width").getAsInt());
            entry.addProperty("height", background.get("height").getAsInt());
            if (background.has("accent")) {
                entry.addProperty("accent", background.get("accent").getAsString());
            }
            if (background.has("credit")) {
                entry.addProperty("credit", background.get("credit").getAsString()
                        .replaceAll(";\\s*see ATTRIBUTION\\.md", "").trim());
            }
            list.add(entry);
        }
        JsonObject out = new JsonObject();
        out.addProperty("schema", SCHEMA);
        out.addProperty("default", defaultId);
        out.add("backgrounds", list);
        return out;
    }

    /** Queue a launcher public file for copying; return its published path. */
    private String copyPublic(String relative) {
        Path source = root.resolve("desktop/public").resolve(relative).normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("background file missing: desktop/public/" + relative);
        }
        String published = relative.replace('\\', '/');
        copies.put(published, source);
        return published;
    }

    // -----------------------------------------------------------------------
    // optimizations.json
    // -----------------------------------------------------------------------

    private JsonObject optimizations() {
        JsonArray list = new JsonArray();
        for (JsonElement element : registry.getAsJsonArray("optimizations")) {
            JsonObject source = element.getAsJsonObject();
            if (!"RUNS_IN_GAME".equals(source.get("status").getAsString())) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", source.get("id").getAsString());
            entry.addProperty("name", source.get("name").getAsString());
            entry.addProperty("category", source.get("groupLabel").getAsString());
            entry.addProperty("description", source.get("description").getAsString());
            entry.addProperty("status", "implemented");
            entry.addProperty("enabledByDefault", enabledByDefault(source));
            entry.addProperty("setFrom", "IN_GAME".equals(source.get("control").getAsString())
                    ? "Performance layer settings, in game" : "launcher Performance page");
            entry.addProperty("kind", "SAFE".equals(source.get("category").getAsString())
                    ? "output-identical" : "optional trade-off");
            JsonElement evidence = source.get("evidence");
            String level = evidence == null || evidence.isJsonNull() ? null
                    : evidence.getAsJsonObject().get("level").getAsString();
            entry.addProperty("evidence", "IN_GAME".equals(level) ? "measured in game"
                    : "ISOLATED".equals(level) ? "measured in isolation" : "code analysis");
            boolean standsDownForOptiFine = false;
            if (source.has("conflicts")) {
                for (JsonElement conflict : source.getAsJsonArray("conflicts")) {
                    JsonObject value = conflict.getAsJsonObject();
                    if ("OptiFine".equals(value.get("with").getAsString())
                            && "DISABLE_OURS".equals(value.get("resolution").getAsString())) {
                        standsDownForOptiFine = true;
                    }
                }
            }
            entry.addProperty("activeWithOptiFine", !standsDownForOptiFine);
            list.add(entry);
        }
        JsonObject out = new JsonObject();
        out.addProperty("schema", SCHEMA);
        out.add("optimizations", list);
        return out;
    }

    /**
     * The state a new installation starts in. A launcher switch: the registry's own
     * default. A Performance layer setting: what the layer reads from a module at its
     * default settings, computed the way the game computes it, never assumed.
     */
    private static boolean enabledByDefault(JsonObject source) {
        if (!"IN_GAME".equals(source.get("control").getAsString())) {
            return source.get("defaultOn").getAsBoolean();
        }
        net.fathom.optimizations.core.module.PerformanceLayer.Snapshot defaults =
                new net.fathom.optimizations.core.module.PerformanceLayer.Snapshot().read(
                        ModuleRegistry.standard().find(net.fathom.optimizations.core.module.PerformanceLayer.ID));
        String id = source.get("id").getAsString();
        if ("render.nametag.replay".equals(id)) {
            return defaults.fastNametags;
        }
        if ("render.hud.caching".equals(id)) {
            return defaults.hudCache;
        }
        if ("render.entity.distance-limit".equals(id)) {
            return defaults.entitiesLimited();
        }
        if ("render.tile-entity.distance-limit".equals(id)) {
            return defaults.tilesLimited();
        }
        throw new IllegalStateException(id + " is set in game, but the export does not know which Performance "
                + "layer setting decides it; add it to WebExport.enabledByDefault");
    }

    // -----------------------------------------------------------------------
    // media-manifest.json
    // -----------------------------------------------------------------------

    private static final Pattern MEDIA_EXTENSION = Pattern.compile("(?i)\\.(png|jpg|jpeg|webp|webm|mp4)$");

    private JsonObject media() throws IOException {
        JsonObject approved = readJson(root.resolve("assets/web/media.json")).getAsJsonObject();
        JsonArray list = new JsonArray();
        for (JsonElement element : approved.getAsJsonArray("slots")) {
            JsonObject slot = element.getAsJsonObject();
            String id = slot.get("id").getAsString();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id);
            entry.addProperty("title", slot.get("title").getAsString());
            entry.addProperty("alt", slot.get("alt").getAsString());
            JsonElement sourceValue = slot.get("source");
            Path source = sourceValue == null || sourceValue.isJsonNull() ? null
                    : root.resolve(sourceValue.getAsString()).normalize();
            Matcher extension = source == null ? null : MEDIA_EXTENSION.matcher(source.getFileName().toString());
            if (source == null) {
                entry.addProperty("status", "unavailable");
                entry.addProperty("reason", "no approved capture yet");
                entry.add("file", JsonNull.INSTANCE);
            } else if (!source.startsWith(root) || !Files.isRegularFile(source) || !extension.find()) {
                entry.addProperty("status", "unavailable");
                entry.addProperty("reason", "the approved file is missing or not an image or video");
                entry.add("file", JsonNull.INSTANCE);
            } else {
                String published = "media/" + id + "." + extension.group(1).toLowerCase(Locale.ROOT);
                copies.put(published, source);
                entry.addProperty("status", "available");
                entry.addProperty("file", published);
            }
            list.add(entry);
        }
        JsonObject out = new JsonObject();
        out.addProperty("schema", SCHEMA);
        out.add("media", list);
        return out;
    }

    // -----------------------------------------------------------------------
    // performance.json
    // -----------------------------------------------------------------------

    /** How a scene id is presented, and whether it may be. */
    private static final class Scenario {
        final String category;
        final boolean representative;
        final String title;
        final String description;

        Scenario(String category, boolean representative, String title, String description) {
            this.category = category;
            this.representative = representative;
            this.title = title;
            this.description = description;
        }
    }

    private static final String SERVER = "Local 1.8.9 test server with scripted players (not a public server). ";
    private static final Map<String, Scenario> SCENARIOS = new LinkedHashMap<String, Scenario>();

    static {
        SCENARIOS.put("sp", new Scenario("singleplayer", true, "Singleplayer",
                "Singleplayer world, camera level across the terrain, no other players."));
        SCENARIOS.put("horizon", new Scenario("singleplayer", true, "Singleplayer, level view",
                "Singleplayer world, camera level to the horizon."));
        SCENARIOS.put("down45", new Scenario("camera-study", false, "Looking 45 degrees down",
                "Singleplayer camera pointed at the ground; a terrain-drawing study, not normal play."));
        SCENARIOS.put("down90", new Scenario("camera-study", false, "Looking straight down",
                "Singleplayer camera pointed straight down; a terrain-drawing study, not normal play."));
        SCENARIOS.put("entities", new Scenario("synthetic", false, "48 players (client-side)",
                "Singleplayer with 48 armoured, sword-holding players with nametags spawned client-side in "
                        + "front of the camera: an entity-rendering stress test."));
        SCENARIOS.put("particles", new Scenario("synthetic", false, "Particle field",
                "A steady field of flame and smoke particles in front of the camera: a stress test."));
        SCENARIOS.put("translucent", new Scenario("synthetic", false, "Glass and water walls",
                "Two stained-glass walls and a water wall in front of the camera: a stress test."));
        SCENARIOS.put("mp-empty", new Scenario("multiplayer", true, "Server, nobody nearby",
                SERVER + "Connected, same camera as the singleplayer scene, no other player in range."));
        SCENARIOS.put("mp-few", new Scenario("multiplayer", true, "Server, 5 players",
                SERVER + "5 players walking 10-25 blocks away."));
        SCENARIOS.put("lobby", new Scenario("multiplayer", true, "Server lobby, 40 players",
                SERVER + "40 players walking, sneaking, sprinting and chatting; team-coloured names, health "
                        + "below names and a scoreboard sidebar."));
        SCENARIOS.put("combat", new Scenario("multiplayer", true, "Combat, 20 armoured players",
                SERVER + "20 players fighting in pairs 6-20 blocks away in iron armour with diamond swords, "
                        + "half of it enchanted."));
        SCENARIOS.put("combat-particles", new Scenario("multiplayer-stress", false, "Combat with heavy particles",
                SERVER + "The combat scene plus a continuous stream of crit, magic-crit and dust particles from "
                        + "the server: a stress test."));
        SCENARIOS.put("nametags", new Scenario("multiplayer-stress", false, "40 nametags",
                SERVER + "40 players standing 6-30 blocks away with team-coloured names and health below "
                        + "each: a nametag stress test."));
        SCENARIOS.put("hud-heavy", new Scenario("multiplayer-stress", false, "Scoreboard, tab list and chat",
                SERVER + "40 players; a 15-line scoreboard updated every tick, the tab list held open and a "
                        + "chat message every 100 ms: a HUD stress test."));
        SCENARIOS.put("chunks", new Scenario("multiplayer-stress", false, "Blocks changing nearby",
                SERVER + "Blocks changing near the camera every tick while 10 players walk: a chunk-update "
                        + "stress test."));
        // Deliberately absent, so never exported: "sky" (no geometry; its frame
        // rate is not gameplay), "reload" (measures rebuild time, not frames) and
        // "live" (a real server whose content the report cannot describe).
    }

    private static final Pattern REPORT = Pattern.compile("fathom-(view|mp)-(\\d{4}-\\d{2}-\\d{2})-(\\d{2}-\\d{2}-\\d{2})\\.txt");
    private static final int MIN_FRAMES = 1000;

    private JsonObject performance() throws IOException {
        Path directory = root.resolve("benchmarks/reports");
        List<Path> reports = new ArrayList<Path>();
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "fathom-*.txt")) {
                for (Path path : stream) {
                    reports.add(path);
                }
            }
        }
        Collections.sort(reports);
        JsonArray runs = new JsonArray();
        for (Path report : reports) {
            JsonObject run = run(report);
            if (run != null) {
                runs.add(run);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("schema", SCHEMA);
        JsonObject method = new JsonObject();
        method.addProperty("tool", "Fathom's frame profiler (-Dfathom.benchmark=view)");
        method.addProperty("frameTime", "Loop head to loop head, minus the profiler's own time, every frame in "
                + "each measured window. Median, mean, p95 and p99 are exact over all frames, not sampled.");
        method.addProperty("interleaving", "Within a run, every configuration of a scene is measured in "
                + "alternating short windows, the order reversed every other round, so machine drift lands on "
                + "all of them equally.");
        method.addProperty("fps", "FPS figures are 1000 divided by the frame time. Prefer frame times: at these "
                + "rates a small FPS change is a large frame-time change and vice versa.");
        method.addProperty("representative", "true: a view of ordinary play. false: a stress test or a study of "
                + "one cost; do not present its frame rate as typical.");
        method.addProperty("excluded", "Scenes and configurations without full context, with rejected or "
                + "unfocused frames, or with fewer than " + MIN_FRAMES + " frames; the sky-only scene "
                + "always.");
        out.add("methodology", method);
        out.add("runs", runs);
        return out;
    }

    /** One report pair, or null (with a reason recorded) when it lacks the context to be presented. */
    private JsonObject run(Path text) throws IOException {
        Matcher name = REPORT.matcher(text.getFileName().toString());
        Path csvPath = text.resolveSibling(text.getFileName().toString().replaceAll("\\.txt$", ".csv"));
        if (!name.matches() || !Files.isRegularFile(csvPath)) {
            skipped.add(text.getFileName() + ": not a report pair");
            return null;
        }
        List<String> lines = Files.readAllLines(text, StandardCharsets.UTF_8);
        Map<String, String> head = new LinkedHashMap<String, String>();
        Map<String, String> sceneNotes = new LinkedHashMap<String, String>();
        Map<String, String> normalArmStates = new TreeMap<String, String>();
        for (String line : lines) {
            for (String key : new String[] {"Fathom build: ", "GL: ", "Display: ", "Video: ", "OptiFine runtime: ",
                "OptiFine active: ", "Java ", "Method: "}) {
                if (line.startsWith(key) && !head.containsKey(key)) {
                    head.put(key, line.substring(key.length()));
                }
            }
            Matcher scene = Pattern.compile("^Scene ([a-z0-9-]+): ").matcher(line);
            if (scene.find()) {
                sceneNotes.put(scene.group(1), line);
            }
            Matcher arm = Pattern.compile("^Switch arm (?:on:)?([a-z0-9.-]+): (on|off) in the normal arm").matcher(line);
            if (arm.find()) {
                normalArmStates.put(arm.group(1), arm.group(2));
            }
        }
        for (String key : new String[] {"Fathom build: ", "GL: ", "Display: ", "Video: ", "Java ", "Method: "}) {
            if (!head.containsKey(key)) {
                skipped.add(text.getFileName() + ": no '" + key.trim() + "' line, so no context; whole report");
                return null;
            }
        }
        Map<String, int[]> validity = validity(lines);
        Map<String, String> paired = pairedTable(lines);

        JsonObject run = new JsonObject();
        String id = name.group(1) + "-" + name.group(2) + "-" + name.group(3);
        run.addProperty("id", id);
        run.addProperty("date", name.group(2));
        Matcher version = Pattern.compile("^([0-9][^ ]*)").matcher(head.get("Fathom build: "));
        run.addProperty("fathomVersion", version.find() ? version.group(1) : "unknown");
        run.addProperty("minecraft", require(gradle, "minecraft_version"));
        run.addProperty("kind", "mp".equals(name.group(1)) ? "multiplayer profile" : "view profile");
        run.add("machine", machine(head));
        run.add("settings", settings(head));
        run.addProperty("method", head.get("Method: "));

        JsonArray configurationNotes = new JsonArray();
        for (Map.Entry<String, String> state : normalArmStates.entrySet()) {
            configurationNotes.add(new JsonPrimitive("In this build's defaults, " + optimizationName(state.getKey())
                    + " was " + state.getValue() + "."));
        }
        run.add("defaultsNotes", configurationNotes);

        JsonArray results = new JsonArray();
        for (String[] row : csvRows(csvPath)) {
            String scene = row[0];
            String arm = row[1];
            Scenario scenario = SCENARIOS.get(scene);
            JsonObject configuration = configuration(arm);
            if (scenario == null || configuration == null) {
                continue;
            }
            int frames = Integer.parseInt(row[2]);
            int rejected = Integer.parseInt(row[3]);
            int[] valid = validity.get(scene + "|" + arm);
            int unfocused = valid == null ? -1 : valid[2];
            String where = id + " " + scene + "/" + arm;
            if (frames < MIN_FRAMES) {
                skipped.add(where + ": " + frames + " frames");
                continue;
            }
            if (rejected > frames / 100) {
                skipped.add(where + ": " + rejected + " rejected frames");
                continue;
            }
            if (unfocused < 0 || unfocused > frames / 20) {
                skipped.add(where + ": " + (unfocused < 0 ? "focus not recorded" : unfocused + " unfocused frames"));
                continue;
            }
            double mean = Double.parseDouble(row[4]);
            double median = Double.parseDouble(row[5]);
            double p95 = Double.parseDouble(row[6]);
            double p99 = Double.parseDouble(row[7]);
            JsonObject result = new JsonObject();
            result.addProperty("scene", scene);
            result.addProperty("title", scenario.title);
            result.addProperty("category", scenario.category);
            result.addProperty("representative", scenario.representative);
            result.addProperty("description", scenario.description);
            result.add("configuration", configuration);
            result.addProperty("frames", frames);
            JsonObject frameTime = new JsonObject();
            frameTime.addProperty("medianMs", round(median, 3));
            frameTime.addProperty("meanMs", round(mean, 3));
            frameTime.addProperty("p95Ms", round(p95, 3));
            frameTime.addProperty("p99Ms", round(p99, 3));
            result.add("frameTime", frameTime);
            JsonObject fps = new JsonObject();
            fps.addProperty("fromMedian", Math.round(1000d / median));
            fps.addProperty("fromMean", Math.round(1000d / mean));
            fps.addProperty("fromP99", Math.round(1000d / p99));
            result.add("fpsEquivalent", fps);
            String comparison = paired.get(scene + "|" + arm);
            if (comparison != null && !"default".equals(configuration.get("id").getAsString())) {
                String[] values = comparison.split(" ");
                JsonObject versus = new JsonObject();
                versus.addProperty("against", "default");
                versus.addProperty("pairedMedianDifferenceMs", round(Double.parseDouble(values[0]), 3));
                String[] wins = values[1].split("/");
                versus.addProperty("roundsFasterThanDefault", Integer.parseInt(wins[0]));
                versus.addProperty("rounds", Integer.parseInt(wins[1]));
                result.add("versusDefault", versus);
            }
            results.add(result);
        }
        if (results.size() == 0) {
            skipped.add(id + ": no scene with enough context");
            return null;
        }
        run.add("results", results);
        return run;
    }

    /** How an arm is described publicly, or null for instrumentation arms that are never exported. */
    private JsonObject configuration(String arm) {
        JsonObject out = new JsonObject();
        JsonArray changed = new JsonArray();
        if ("normal".equals(arm)) {
            out.addProperty("id", "default");
            out.addProperty("label", "Fathom with its default settings");
        } else if ("fathom-off".equals(arm)) {
            out.addProperty("id", "fathom-bypassed");
            out.addProperty("label", "Fathom's hooks and performance layer bypassed");
        } else if (arm.startsWith("off:") || arm.startsWith("on:")) {
            boolean on = arm.startsWith("on:");
            String[] ids = arm.substring(on ? 3 : 4).split("\\+");
            List<String> names = new ArrayList<String>();
            for (String optimization : ids) {
                String name = optimizationName(optimization);
                if (name == null) {
                    return null;
                }
                names.add(name);
                JsonObject change = new JsonObject();
                change.addProperty("optimization", optimization);
                change.addProperty("name", name);
                change.addProperty("state", on ? "on" : "off");
                changed.add(change);
            }
            out.addProperty("id", ids.length == 1 ? (on ? "on-" : "off-") + ids[0]
                    : (on ? "on-" : "off-") + ids.length + "-optimizations");
            out.addProperty("label", "Fathom defaults with " + join(names) + " switched " + (on ? "on" : "off"));
        } else {
            // probes-off, drain, OptiFine option arms: instrumentation, not configurations.
            return null;
        }
        out.add("changed", changed);
        return out;
    }

    private String optimizationName(String id) {
        for (JsonElement element : registry.getAsJsonArray("optimizations")) {
            JsonObject entry = element.getAsJsonObject();
            if (id.equals(entry.get("id").getAsString())) {
                return entry.get("name").getAsString();
            }
        }
        return null;
    }

    private static JsonObject machine(Map<String, String> head) {
        JsonObject out = new JsonObject();
        String[] gl = head.get("GL: ").split(" / ");
        out.addProperty("gpu", gl[0].replaceAll("/PCIe.*$", "").replaceAll("/SSE2$", "").trim());
        if (gl.length > 1) {
            Matcher driver = Pattern.compile("NVIDIA ([0-9.]+)|([0-9]+\\.[0-9.]+)$").matcher(gl[1]);
            if (driver.find()) {
                out.addProperty("gpuDriver", driver.group(1) != null ? driver.group(1) : driver.group(2));
            }
        }
        Matcher threads = Pattern.compile("(\\d+) hardware threads").matcher(head.get("Java "));
        if (threads.find()) {
            out.addProperty("cpuThreads", Integer.parseInt(threads.group(1)));
        }
        Matcher java = Pattern.compile("^([0-9._]+)").matcher(head.get("Java "));
        if (java.find()) {
            out.addProperty("java", java.group(1));
        }
        Matcher heap = Pattern.compile("heap max (\\d+) MB").matcher(head.get("Java "));
        if (heap.find()) {
            out.addProperty("heapMb", Integer.parseInt(heap.group(1)));
        }
        return out;
    }

    private static JsonObject settings(Map<String, String> head) {
        String display = head.get("Display: ");
        String video = head.get("Video: ");
        JsonObject out = new JsonObject();
        Matcher size = Pattern.compile("^(\\d+)x(\\d+)").matcher(display);
        if (size.find()) {
            out.addProperty("resolution", size.group(1) + "x" + size.group(2));
        }
        out.addProperty("fullscreen", flag(display, "fullscreen"));
        out.addProperty("vsync", flag(display, "vsync"));
        Matcher fps = Pattern.compile("maxFps (\\d+)").matcher(display);
        if (fps.find()) {
            int cap = Integer.parseInt(fps.group(1));
            out.addProperty("frameLimit", cap >= 260 ? "unlimited" : String.valueOf(cap));
        }
        Matcher gui = Pattern.compile("guiScale (\\d+)").matcher(display);
        if (gui.find()) {
            out.addProperty("guiScale", Integer.parseInt(gui.group(1)));
        }
        Matcher distance = Pattern.compile("renderDistance (\\d+)").matcher(video);
        if (distance.find()) {
            out.addProperty("renderDistanceChunks", Integer.parseInt(distance.group(1)));
        }
        out.addProperty("graphics", flag(video, "fancy") ? "fancy" : "fast");
        Matcher smooth = Pattern.compile("smooth lighting (\\d)").matcher(video);
        if (smooth.find()) {
            out.addProperty("smoothLighting", new String[] {"off", "minimum", "maximum"}[Integer.parseInt(smooth.group(1))]);
        }
        Matcher mipmaps = Pattern.compile("mipmaps (\\d+)").matcher(video);
        if (mipmaps.find()) {
            out.addProperty("mipmapLevels", Integer.parseInt(mipmaps.group(1)));
        }
        Matcher particles = Pattern.compile("particles (\\d)").matcher(video);
        if (particles.find()) {
            out.addProperty("particles", new String[] {"all", "decreased", "minimal"}[Integer.parseInt(particles.group(1))]);
        }
        out.addProperty("entityShadows", flag(video, "entity shadows"));
        out.addProperty("vbo", flag(video, "VBO"));
        String optifine = head.get("OptiFine active: ");
        boolean optifineActive = optifine != null && optifine.startsWith("true");
        JsonObject of = new JsonObject();
        of.addProperty("installed", optifineActive);
        String runtime = head.get("OptiFine runtime: ");
        if (optifineActive && runtime != null) {
            for (String key : new String[] {"RenderRegions", "FastRender", "Shaders"}) {
                Matcher value = Pattern.compile(key + "=(true|false)").matcher(runtime);
                if (value.find()) {
                    of.addProperty(Character.toLowerCase(key.charAt(0)) + key.substring(1),
                            Boolean.parseBoolean(value.group(1)));
                }
            }
        }
        out.add("optifine", of);
        return out;
    }

    private static boolean flag(String line, String key) {
        Matcher matcher = Pattern.compile(Pattern.quote(key) + " (true|false)").matcher(line);
        return matcher.find() && Boolean.parseBoolean(matcher.group(1));
    }

    /** SAMPLE VALIDITY: scene|arm -> [frames, rejected, unfocused]. */
    private static Map<String, int[]> validity(List<String> lines) {
        Map<String, int[]> out = new LinkedHashMap<String, int[]>();
        int at = lines.indexOf("SAMPLE VALIDITY");
        if (at < 0) {
            return out;
        }
        for (int index = at + 3; index < lines.size() && !lines.get(index).trim().isEmpty(); index++) {
            String[] columns = lines.get(index).trim().split("\\s+");
            if (columns.length >= 5) {
                try {
                    out.put(columns[0] + "|" + columns[1], new int[] {Integer.parseInt(columns[2]),
                        Integer.parseInt(columns[3]), Integer.parseInt(columns[4])});
                } catch (NumberFormatException header) {
                    // the column header row
                }
            }
        }
        return out;
    }

    /**
     * ARMS: DISTRIBUTION: scene|arm -> "pairedDifference wins/rounds". The report
     * truncates arm names to 34 characters; they are matched back by prefix.
     */
    private Map<String, String> pairedTable(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        int at = lines.indexOf("ARMS: DISTRIBUTION AND PAIRED DIFFERENCE (ms per frame)");
        if (at < 0) {
            return out;
        }
        Pattern row = Pattern.compile("^(\\S+)\\s+(\\S+)\\s+[-0-9.]+\\s+[-0-9.]+\\s+[-0-9.]+\\s+[-0-9.]+\\s+[-+0-9.]+\\s+"
                + "[-+0-9.]+\\s+[-+0-9.]+\\s+[-+0-9.]+\\s+([-+0-9.]+)\\s+(\\d+/\\d+)\\s*$");
        for (int index = at + 1; index < lines.size() && !lines.get(index).startsWith("VIEW COMPARISON"); index++) {
            Matcher match = row.matcher(lines.get(index));
            if (match.find()) {
                out.put(match.group(1) + "|" + match.group(2), match.group(3) + " " + match.group(4));
            }
        }
        return new PrefixMap(out);
    }

    /** Looks a key up exactly, then by the report's 34-character arm prefix. */
    private static final class PrefixMap extends LinkedHashMap<String, String> {
        private static final long serialVersionUID = 1L;

        PrefixMap(Map<String, String> from) {
            super(from);
        }

        @Override
        public String get(Object key) {
            String exact = super.get(key);
            if (exact != null) {
                return exact;
            }
            String[] parts = String.valueOf(key).split("\\|", 2);
            if (parts.length == 2 && parts[1].length() > 34) {
                return super.get(parts[0] + "|" + parts[1].substring(0, 34));
            }
            return null;
        }
    }

    /** One row per scene and arm (the CSV repeats them per section; the first is kept). */
    private static List<String[]> csvRows(Path csv) throws IOException {
        List<String[]> out = new ArrayList<String[]>();
        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        for (int index = 1; index < lines.size(); index++) {
            String[] columns = lines.get(index).split(",", -1);
            if (columns.length < 8 || seen.containsKey(columns[0] + "|" + columns[1])) {
                continue;
            }
            seen.put(columns[0] + "|" + columns[1], Boolean.TRUE);
            out.add(columns);
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Sanitization
    // -----------------------------------------------------------------------

    /** What must never reach the website. Patterns look for values, not for words in prose. */
    static final class Sanitizer {

        private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<String, Pattern>();

        static {
            PATTERNS.put("Windows path", Pattern.compile("(?i)\\b[a-z]:[\\\\/]"));
            PATTERNS.put("home directory", Pattern.compile("(?i)[\\\\/](users|home|documents and settings)[\\\\/]"));
            PATTERNS.put("AppData", Pattern.compile("(?i)appdata"));
            PATTERNS.put("e-mail address", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
            PATTERNS.put("local address", Pattern.compile("(?i)\\blocalhost\\b|\\b127\\.0\\.0\\.1\\b|\\b0\\.0\\.0\\.0\\b|\\[::1\\]"));
            PATTERNS.put("address with a port", Pattern.compile("(?i)\\b(?:\\d{1,3}\\.){3}\\d{1,3}:\\d{2,5}\\b|://[^\\s\"/]+:\\d{2,5}"));
            PATTERNS.put("JWT", Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));
            PATTERNS.put("UUID", Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"));
            PATTERNS.put("long hex value", Pattern.compile("\\b(?=[0-9a-fA-F]*[0-9])(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{12,}\\b"));
            PATTERNS.put("dirty build stamp", Pattern.compile("-dirty\\b"));
            PATTERNS.put("credential keyword", Pattern.compile(
                    "(?i)\\b(access_?token|refresh_?token|id_?token|client_?secret|client_?id|api_?key|bearer|xuid|password)\\b"));
            PATTERNS.put("private JSON key", Pattern.compile(
                    "(?i)\"(token|secret|password|email|username|uuid|accountId|xuid|path|localPath|session)\"\\s*:"));
        }

        static List<String> scan(String where, String text) {
            List<String> problems = new ArrayList<String>();
            for (Map.Entry<String, Pattern> pattern : PATTERNS.entrySet()) {
                Matcher matcher = pattern.getValue().matcher(text);
                if (matcher.find()) {
                    problems.add(where + ": " + pattern.getKey() + " near \"" + context(text, matcher.start()) + "\"");
                }
            }
            String user = System.getProperty("user.name", "");
            if (user.length() >= 2) {
                Matcher matcher = Pattern.compile("(?i)\\b" + Pattern.quote(user) + "\\b").matcher(text);
                if (matcher.find()) {
                    problems.add(where + ": this machine's user name near \"" + context(text, matcher.start()) + "\"");
                }
            }
            return problems;
        }

        private static String context(String text, int at) {
            int from = Math.max(0, at - 20);
            int to = Math.min(text.length(), at + 40);
            return text.substring(from, to).replace('\n', ' ');
        }

        /** Every pattern must catch its example, or the scan proves nothing. */
        static void selfTest() {
            String[] bad = {
                "C:\\Users\\someone\\x", "/home/someone/x", "%LOCALAPPDATA%", "player@example.com",
                "http://localhost/x", "10.0.0.5:25565", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0",
                "c80a67ae-fda1-3fd5-8561-0000ad7ec96d", "1248250fb8af", "0.3.0-hud-dirty", "refresh_token",
                "{\"email\": 1}",
            };
            for (String sample : bad) {
                if (scan("self-test", sample).isEmpty()) {
                    throw new IllegalStateException("sanitizer self-test: nothing caught in " + sample);
                }
            }
            String fine = "{\"name\": \"Nametag name cache\", \"description\": \"a UUID turned into a string\","
                    + " \"resolution\": \"1920x1080\", \"medianMs\": 1.729}";
            if (!scan("self-test", fine).isEmpty()) {
                throw new IllegalStateException("sanitizer self-test: false positive " + scan("self-test", fine));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static JsonObject fileEntry(String path, byte[] bytes) throws IOException {
        JsonObject entry = new JsonObject();
        entry.addProperty("file", path);
        entry.addProperty("bytes", bytes.length);
        entry.addProperty("sha256", sha256(bytes));
        return entry;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte value : digest) {
                out.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }

    private static String join(List<String> names) {
        if (names.size() == 1) {
            return names.get(0);
        }
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) {
                out.append(index == names.size() - 1 ? " and " : ", ");
            }
            out.append(names.get(index));
        }
        return out.toString();
    }

    private static Map<String, String> properties(Path file) throws IOException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int equals = trimmed.indexOf('=');
            if (!trimmed.startsWith("#") && equals > 0) {
                out.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
            }
        }
        return out;
    }

    private static String require(Map<String, String> map, String key) {
        String value = map.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("gradle.properties has no " + key);
        }
        return value;
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static JsonElement readJson(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new JsonParser().parse(reader);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        List<Path> paths = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
            walk.forEach(paths::add);
        }
        Collections.reverse(paths);
        for (Path path : paths) {
            Files.delete(path);
        }
    }
}
