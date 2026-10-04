package net.fathom.optimizations.fabric.menu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.profile.ProfileLocation;
import net.fathom.optimizations.core.ui.BackgroundChoice;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.fabric.gfx.GlRenderer;
import net.fathom.optimizations.generated.BackgroundCatalog;

/**
 * What the menu can sit on, and the picture behind whichever is chosen.
 *
 * <h2>The launcher's library, and the launcher's choice</h2>
 *
 * <p>The list is generated from {@code assets/backgrounds/manifest.json} (the
 * {@link BackgroundCatalog}), the same catalogue the launcher's picker is
 * generated from. The choice is made in the launcher -- Settings, Appearance,
 * Background -- and {@link #selected()} reads it, so the title screen, the Hub
 * and every Fathom menu backdrop show the background the launcher shows. There
 * is no second picker in the game to disagree with it.
 *
 * <h2>The fallback is not a scene</h2>
 *
 * <p>When the picture cannot be loaded, or the player has turned the
 * background off, the menu sits on the launcher's neutral static field
 * ({@link MenuPoster#drawFlat}). It is never offered as a choice.
 *
 * <h2>Video</h2>
 *
 * <p>{@link MenuVideo} streams the cached Rainforest Storm frame sequence,
 * because 1.8.9 has no video decoder. Every other background shows its still
 * in the game -- the launcher's own full-resolution frame of it.
 *
 * <h2>Textures</h2>
 *
 * <p>Loaded from the classpath on first use and kept. Legacy Fabric's 1.8.9
 * resource manager does not expose mod asset namespaces as texture packs, which
 * is the same reason {@link MenuPoster} loads the way it does.
 */
public final class MenuScenes {

    /** One entry in the picker. */
    public static final class Scene {
        public final String id;
        public final String label;
        /** True where the game has a frame sequence to play. */
        public final boolean animated;
        /** True for the flat field, which has no picture behind it. */
        public final boolean gradient;
        /** Who made it, as the credits panel says it, or null. */
        public final String credit;
        /** The light the interface takes while this scene shows, 0xRRGGBB. */
        public final int accent;
        /** The still's classpath resource: the same picture the launcher shows. */
        final String still;

        Scene(String id, String label, boolean animated, String still, String credit, int accent) {
            this.id = id;
            this.accent = accent;
            this.label = label;
            this.animated = animated;
            this.gradient = false;
            this.credit = credit;
            this.still = MENU + still;
        }

        public boolean credited() {
            return credit != null;
        }
    }

    private static final String MENU = "/assets/fathom-optimizations/textures/menu/";
    private static final float SOURCE_ASPECT = 16f / 9f;

    /** The library, one scene per catalogue entry, in the launcher's order. */
    private static final Scene[] SCENES = new Scene[BackgroundCatalog.IDS.length];

    static {
        for (int index = 0; index < SCENES.length; index++) {
            SCENES[index] = new Scene(BackgroundCatalog.IDS[index], BackgroundCatalog.LABELS[index],
                    BackgroundCatalog.VIDEO[index] != null, BackgroundCatalog.STILL[index],
                    BackgroundCatalog.CREDITS[index], BackgroundCatalog.ACCENT[index]);
        }
    }

    /** The scene a game with no launcher choice gets. */
    public static final String DEFAULT_LABEL = labelOf(BackgroundCatalog.DEFAULT_ID);

    /** How often the launcher's choice is looked at again while a menu is open. */
    private static final long CHOICE_REFRESH_NANOS = 1_000_000_000L;
    private static Scene chosen;
    private static long chosenAt;
    private static long chosenModified = Long.MIN_VALUE;

    /** Scene id to texture name. Zero means tried and failed; absent means untried. */
    private static final Map<String, Integer> TEXTURES = new HashMap<String, Integer>();

    private MenuScenes() {}

    /** Release every classpath scene texture on a resource reload. Render thread only. */
    static void dispose() {
        GlStateManager.bindTexture(0);
        for (Integer texture : TEXTURES.values()) {
            if (texture != null && texture.intValue() > 0) {
                GL11.glDeleteTextures(texture.intValue());
            }
        }
        TEXTURES.clear();
    }

    public static Scene[] all() {
        return SCENES;
    }

    /** Whether there is a choice to offer. With one scene there is not. */
    public static boolean pickable() {
        return SCENES.length > 1;
    }

    public static String[] labels() {
        String[] out = new String[SCENES.length];
        for (int index = 0; index < SCENES.length; index++) {
            out[index] = SCENES[index].label;
        }
        return out;
    }

    /** The scene by its launcher label, falling back to the default. */
    private static String labelOf(String id) {
        for (int index = 0; index < BackgroundCatalog.IDS.length; index++) {
            if (BackgroundCatalog.IDS[index].equals(id)) {
                return BackgroundCatalog.LABELS[index];
            }
        }
        return BackgroundCatalog.LABELS[0];
    }

    /** The scene with this id, or the default. */
    public static Scene byId(String id) {
        for (Scene scene : SCENES) {
            if (scene.id.equals(id)) {
                return scene;
            }
        }
        return byLabel(DEFAULT_LABEL);
    }

    /**
     * The background the user chose in the launcher, for every Fathom menu.
     *
     * <p>Read from the launcher's {@code appearance.json} (see
     * {@link BackgroundChoice}), looked at again at most once a second and only
     * re-parsed when the file changed, so a choice made in the launcher while a
     * menu is open arrives within a second. A game started without the
     * launcher has no file and shows the default.
     */
    private static boolean standardCapitalization;

    public static Scene selected() {
        long now = System.nanoTime();
        if (chosen != null && now - chosenAt < CHOICE_REFRESH_NANOS) {
            return chosen;
        }
        chosenAt = now;
        File root = ProfileLocation.platformRoot();
        File file = root == null ? null : new File(root, BackgroundChoice.FILE);
        long modified = file != null && file.isFile() ? file.lastModified() : 0L;
        if (chosen != null && modified == chosenModified) {
            return chosen;
        }
        chosenModified = modified;
        String json = null;
        if (modified != 0L) {
            try {
                json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException unreadable) {
                json = null;
            }
        }
        chosen = byId(BackgroundChoice.resolve(json, BackgroundCatalog.IDS, BackgroundCatalog.DEFAULT_ID));
        standardCapitalization = BackgroundChoice.standardCapitalization(json);
        return chosen;
    }

    /**
     * Whether Fathom's screens use standard capitalization: the launcher's
     * Appearance switch, read from the same file as the background, so it
     * changes here within a second of changing there.
     */
    public static boolean standardCapitalization() {
        selected();
        return standardCapitalization;
    }

    public static Scene byLabel(String label) {
        for (Scene scene : SCENES) {
            if (scene.label.equals(label)) {
                return scene;
            }
        }
        for (Scene scene : SCENES) {
            if (scene.label.equals(DEFAULT_LABEL)) {
                return scene;
            }
        }
        return SCENES[0];
    }

    public static float aspect() {
        return SOURCE_ASPECT;
    }

    /**
     * Draw a picker thumbnail through the active UI backend.
     *
     * <p>The texture is uploaded once and either renderer submits it through
     * the same rounded-image primitive.
     *
     * @return true when an image is ready and was submitted
     */
    public static boolean thumbnail(Renderer ui, Scene scene, Rect area, float radius, int backdrop) {
        if (scene == null || scene.gradient) {
            return false;
        }
        if (ui instanceof GlRenderer && !TEXTURES.containsKey(scene.id)) {
            // A first-use upload binds a raw GL texture. Flush geometry queued
            // against the old renderer's atlas before that bind so a freshly
            // opened scene picker cannot render its preceding rounded controls
            // with the thumbnail texture. The compositor records and draws
            // later, so it needs no flush.
            ((GlRenderer) ui).flush();
        }
        int texture = texture(scene);
        if (texture > 0) {
            if (ui instanceof GlRenderer) {
                ((GlRenderer) ui).image(texture, area.x, area.y, area.right(), area.bottom(),
                        radius, SOURCE_ASPECT, backdrop);
            } else {
                ui.image(texture, area.x, area.y, area.right(), area.bottom(), radius, SOURCE_ASPECT);
            }
            return true;
        }
        return false;
    }

    /**
     * The still behind a scene, uploaded on first use.
     *
     * @return the GL texture name, or 0 when there is no picture to show
     */
    public static int texture(Scene scene) {
        if (scene == null || scene.gradient) {
            return 0;
        }
        Integer known = TEXTURES.get(scene.id);
        if (known != null) {
            return known.intValue();
        }
        int uploaded = upload(scene.id);
        TEXTURES.put(scene.id, Integer.valueOf(uploaded));
        return uploaded;
    }

    private static int upload(String id) {
        String path = byId(id).still;
        try {
            BufferedImage image;
            try (InputStream stream = MenuScenes.class.getResourceAsStream(path)) {
                if (stream == null) {
                    System.err.println("[Fathom] Menu scene is missing: " + path);
                    return 0;
                }
                image = ImageIO.read(stream);
            }
            if (image == null) {
                System.err.println("[Fathom] Menu scene could not be decoded: " + path);
                return 0;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                image.getRGB(0, y, width, 1, row, 0, width);
                for (int x = 0; x < width; x++) {
                    int argb = row[x];
                    pixels.put((byte) ((argb >> 16) & 0xFF));
                    pixels.put((byte) ((argb >> 8) & 0xFF));
                    pixels.put((byte) (argb & 0xFF));
                    pixels.put((byte) ((argb >>> 24) & 0xFF));
                }
            }
            pixels.flip();

            int texture = GL11.glGenTextures();
            GlStateManager.bindTexture(texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                    width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            return texture;
        } catch (IOException | RuntimeException failed) {
            System.err.println("[Fathom] Menu scene failed to upload: " + failed.getMessage());
            return 0;
        }
    }

    /**
     * Draw the chosen scene behind the menu.
     *
     * <p>An animated scene would go through {@link MenuVideo}. A still goes
     * through the poster's drawing path, so every picture gets the same cover
     * crop and the launcher's neutral scrim. A picture that cannot load gives the
     * neutral field instead, never black.
     */
    public static void draw(MinecraftClient client, Scene scene, String videoQuality) {
        if (scene == null || scene.gradient) {
            MenuPoster.drawFlat(client);
            return;
        }
        if (scene.animated) {
            MenuVideo.draw(client, videoQuality);
            return;
        }
        int texture = texture(scene);
        if (texture <= 0) {
            MenuPoster.drawFlat(client);
            return;
        }
        MenuPoster.drawTexture(client, texture);
    }
}
