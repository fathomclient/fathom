package net.fathom.optimizations.fabric.menu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.FathomRenderer;
import net.fathom.optimizations.core.ui.render.ImageRef;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.fabric.gfx.GlRenderer;

/**
 * What the menu can sit on, and the picture behind whichever is chosen.
 *
 * <h2>The same approved set as the launcher</h2>
 *
 * <p>{@code desktop/src/lib/backgrounds.ts} is the other copy of this list,
 * and today both hold one environment: Rainforest Storm. The other Minecraft
 * recordings are retired in both places. With one environment there is no
 * {@code scene} setting on the main-menu module at all (a choice of one is not
 * a choice), so a profile that stored a retired name simply gets Rainforest Storm,
 * and nothing else in it is touched.
 *
 * <p>The structure is kept for a curated set later: with one scene the picker
 * is not offered ({@link #pickable()}), exactly as the launcher hides its own,
 * and the setting returns with the second picture.
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
 * because 1.8.9 has no video decoder.
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
        /** True for the one scene that plays as video. */
        public final boolean animated;
        /** True for the flat field, which has no picture behind it. */
        public final boolean gradient;
        /** Who made it, as the credits panel says it, or null. */
        public final String credit;
        /** Interned once; asking NanoVG for a thumbnail allocates nothing. */
        private final ImageRef image;

        Scene(String id, String label, boolean animated, boolean gradient, String credit) {
            this.id = id;
            this.label = label;
            this.animated = animated;
            this.gradient = gradient;
            this.credit = credit;
            this.image = gradient ? null : ImageRef.resource(
                    "bg_5rain".equals(id) ? POSTER : ROOT + id + ".jpg");
        }

        public boolean credited() {
            return credit != null;
        }
    }

    private static final String ROOT = "/assets/fathom-optimizations/textures/menu/scenes/";
    private static final String POSTER =
            "/assets/fathom-optimizations/textures/menu/main-menu-bg.png";
    private static final float SOURCE_ASPECT = 16f / 9f;

    /** The launcher's credit for the photograph, word for word. */
    public static final String RAINFOREST_STORM_CREDIT =
            "Community-made Minecraft world recording; see ATTRIBUTION.md";

    private static final Scene[] SCENES = {
        new Scene("bg_5rain", "Rainforest Storm", true, false, RAINFOREST_STORM_CREDIT),
    };

    /** The scene a profile with no choice recorded gets. */
    public static final String DEFAULT_LABEL = "Rainforest Storm";

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
     * <p>NanoVG decodes the resource asynchronously and caches its image; the
     * compatibility renderer keeps using the already-owned GL texture path.
     * Keeping this bridge here prevents the menu from forcing the fallback
     * renderer merely because one modal contains image tiles.
     *
     * @return true when an image is ready and was submitted
     */
    public static boolean thumbnail(Renderer ui, Scene scene, Rect area, float radius, int backdrop) {
        if (scene == null || scene.gradient) {
            return false;
        }
        if (ui instanceof FathomRenderer) {
            FathomRenderer vector = (FathomRenderer) ui;
            vector.imageCover(scene.image, area.x, area.y, area.right(), area.bottom(), radius, 1f);
            return vector.imageState(scene.image) == ImageRef.State.READY;
        }
        if (ui instanceof GlRenderer) {
            // A first-use upload binds a raw GL texture. Flush geometry queued
            // against the renderer atlas before that bind so a freshly opened
            // scene picker cannot render its preceding rounded controls with
            // the thumbnail texture. Cached thumbnails need no extra flush.
            if (!TEXTURES.containsKey(scene.id)) {
                ((GlRenderer) ui).flush();
            }
            int texture = texture(scene);
            if (texture > 0) {
                ((GlRenderer) ui).image(texture, area.x, area.y, area.right(), area.bottom(),
                        radius, SOURCE_ASPECT, backdrop);
                return true;
            }
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
        String path = "bg_5rain".equals(id) ? POSTER : ROOT + id + ".jpg";
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
