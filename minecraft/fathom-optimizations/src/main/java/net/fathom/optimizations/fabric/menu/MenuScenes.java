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

/**
 * What the menu can sit on, and the picture behind whichever is chosen.
 *
 * <h2>The same list as the launcher</h2>
 *
 * <p>Fifteen scenes, in the launcher's own order and under the launcher's own
 * names -- {@code desktop/src/lib/backgrounds.ts} is the other copy of this
 * list. Somebody who picked Neon Grid in the launcher should find Neon Grid
 * here, spelled the same way and looking the same.
 *
 * <h2>One of them moves</h2>
 *
 * <p>Rainforest Storm is a preprocessed frame sequence, because 1.8.9 has no
 * video decoder; see {@link MenuVideo}. It is 31 MB in two quality tiers, and
 * fifteen of those would be most of a gigabyte of jar for something playing
 * behind a menu. So the rest are stills, about 40 KB each, baked from the same
 * recordings by {@code tools/prepare-menu-scenes.ps1} with each clip's own
 * grade. Midnight is not a picture at all: it is the flat field the menu falls
 * back to, and it is in the list because the launcher offers it too.
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
        /** The world it was recorded in, or null when it is vanilla. */
        public final String world;
        public final String creator;

        Scene(String id, String label, boolean animated, boolean gradient,
                String world, String creator) {
            this.id = id;
            this.label = label;
            this.animated = animated;
            this.gradient = gradient;
            this.world = world;
            this.creator = creator;
        }

        public boolean credited() {
            return world != null;
        }
    }

    private static final String TIDEWORN = "Tideworn - 8 sqkm Forgotten Flooded City";
    private static final String TIDEWORN_BY = "milkshake_mp3";
    private static final String TOKYO = "Tokyo Inspired City";
    private static final String TOKYO_BY = "CoinCoffer";

    private static final Scene[] SCENES = {
        new Scene("bg_1", "Cherry Grove", false, false, null, null),
        new Scene("bg_6pull_city-overlook", "City Overlook", false, false, TOKYO, TOKYO_BY),
        new Scene("bg_6clear", "Metropolis", false, false, TOKYO, TOKYO_BY),
        new Scene("static", "Midnight", false, true, null, null),
        new Scene("bg_3", "Midnight Coast", false, false, null, null),
        new Scene("bg_6midnight", "Midnight Metropolis", false, false, TOKYO, TOKYO_BY),
        new Scene("bg_6pull_moon-tower", "Moon Tower", false, false, TOKYO, TOKYO_BY),
        new Scene("bg_6pull_neon-grid", "Neon Grid", false, false, TOKYO, TOKYO_BY),
        new Scene("bg_5noraintickunfrozen", "Rainforest Drift", false, false, TIDEWORN, TIDEWORN_BY),
        new Scene("bg_5rain", "Rainforest Storm", true, false, TIDEWORN, TIDEWORN_BY),
        new Scene("bg_5noraintickfrozen", "Rainforest Twilight", false, false, TIDEWORN, TIDEWORN_BY),
        new Scene("bg_6pull_red-tower", "Red Tower", false, false, TOKYO, TOKYO_BY),
        new Scene("bg_4", "Savanna", false, false, null, null),
        new Scene("bg_2", "Snowfall", false, false, null, null),
        new Scene("bg_6pull_storm-panorama", "Storm Panorama", false, false, TOKYO, TOKYO_BY),
    };

    /** The scene a profile with no choice recorded gets. */
    public static final String DEFAULT_LABEL = "Rainforest Storm";

    private static final String ROOT = "/assets/fathom-optimizations/textures/menu/scenes/";
    private static final float SOURCE_ASPECT = 16f / 9f;

    /** Scene id to texture name. Zero means tried and failed; absent means untried. */
    private static final Map<String, Integer> TEXTURES = new HashMap<String, Integer>();

    private MenuScenes() {}

    public static Scene[] all() {
        return SCENES;
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
        String path = ROOT + id + ".jpg";
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
     * <p>The animated one goes through {@link MenuVideo}, which owns its decode
     * thread and its own poster fallback. A still goes through the poster's
     * drawing path, so both get the same cover crop, contrast wash and bottom
     * scrim -- the grade the menu's type was tuned against.
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
            MenuPoster.draw(client);
            return;
        }
        MenuPoster.drawTexture(client, texture);
    }
}
