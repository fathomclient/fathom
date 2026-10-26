package net.fathom.optimizations.fabric.visual;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.MathHelper;

import net.fathom.optimizations.core.input.HubKeyPolicy;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.visual.WaypointList;
import net.fathom.optimizations.fabric.input.MouseButtons;
import net.fathom.optimizations.fabric.input.ScreenKinds;

/**
 * Waypoints: marked spots, drawn as a beam and a label with the distance,
 * visible from anywhere so you can find your way back to them.
 *
 * <p>Made with the Add key (where you stand), the {@code /fwp} command, or
 * automatically where you die. {@code /fwp} never reaches the server. Kept in
 * {@code config/fathom-waypoints.txt} in the game directory.
 */
public final class Waypoints {

    private static final WaypointList LIST = new WaypointList();
    private static boolean loaded;
    private static int savedRevision;
    private static boolean wasAddDown = true;
    private static boolean wasRemoveDown = true;
    private static boolean dead;

    /** Waypoints drawn since the self-test last cleared it. */
    public static int drawn;

    private Waypoints() {}

    static FathomModule active() {
        return WorldEffects.active("waypoints");
    }

    /** The list, for the self-test. */
    public static WaypointList list() {
        return LIST;
    }

    /** Where you are: the server's address, or the singleplayer world's name. */
    public static String place(MinecraftClient client) {
        if (client.isInSingleplayer() && client.getServer() != null) {
            return "world:" + client.getServer().getLevelName();
        }
        ServerInfo server = client.getCurrentServerEntry();
        return server != null && server.address != null ? "server:" + server.address.toLowerCase() : "unknown";
    }

    /** Called once per frame: the keys, the death waypoint, and saving. */
    public static void frame(MinecraftClient client) {
        ensureLoaded(client);
        FathomModule module = active();
        ClientPlayerEntity player = client.player;
        if (module == null || player == null || client.world == null) {
            dead = false;
            save(client);
            return;
        }
        boolean playing = ScreenKinds.of(client.currentScreen) == HubKeyPolicy.Screen.NONE
                && Display.isCreated() && Display.isActive();
        boolean addDown = playing && held(key(module, "add"));
        boolean removeDown = playing && held(key(module, "remove"));
        String place = place(client);
        int x = MathHelper.floor(player.x);
        int y = MathHelper.floor(player.y);
        int z = MathHelper.floor(player.z);
        if (addDown && !wasAddDown) {
            WaypointList.Waypoint made = LIST.add(place, player.dimension, LIST.nextName(place), x, y, z,
                    LIST.nextColour(place));
            tell(client, "Waypoint \"" + made.name + "\" at " + x + " " + y + " " + z + ".");
        }
        if (removeDown && !wasRemoveDown) {
            WaypointList.Waypoint nearest = LIST.nearest(place, player.dimension, player.x, player.y, player.z);
            if (nearest != null) {
                LIST.remove(place, nearest.name);
                tell(client, "Removed \"" + nearest.name + "\".");
            }
        }
        wasAddDown = addDown;
        wasRemoveDown = removeDown;
        boolean deadNow = player.getHealth() <= 0.0F;
        if (deadNow && !dead && module.toggle("death", true)) {
            LIST.add(place, player.dimension, WaypointList.DEATH, x, y, z, 0xE0505C);
        }
        dead = deadNow;
        save(client);
    }

    /** {@code /fwp ...}, typed in chat. Whether it was one; if so it is not sent. */
    public static boolean command(MinecraftClient client, String message) {
        if (message == null || !(message.equals("/fwp") || message.startsWith("/fwp "))) {
            return false;
        }
        ensureLoaded(client);
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return true;
        }
        String reply = LIST.command(message.substring(4), place(client), player.dimension,
                MathHelper.floor(player.x), MathHelper.floor(player.y), MathHelper.floor(player.z));
        if (active() == null) {
            reply += " (Waypoints is off: turn it on in the Hub to see them.)";
        }
        tell(client, reply);
        save(client);
        return true;
    }

    /** Draws the current place's waypoints. Called with the world's camera transform. */
    public static void render(MinecraftClient client) {
        FathomModule module = active();
        if (module == null || client.world == null || client.player == null) {
            return;
        }
        List<WaypointList.Waypoint> here = LIST.at(place(client), client.player.dimension);
        if (here.isEmpty()) {
            return;
        }
        EntityRenderDispatcher dispatcher = client.getEntityRenderManager();
        TextRenderer font = client.textRenderer;
        double maxDistance = module.number("distance", 0);
        boolean beam = module.toggle("beam", true);
        boolean showDistance = module.toggle("show-distance", true);
        float labelScale = (float) (module.number("scale", 100) / 100.0);

        GlStateManager.pushMatrix();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableCull();
        try {
            for (WaypointList.Waypoint each : here) {
                double dx = each.x + 0.5 - dispatcher.cameraX;
                double dy = each.y + 1.0 - dispatcher.cameraY;
                double dz = each.z + 0.5 - dispatcher.cameraZ;
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (maxDistance > 0 && distance > maxDistance) {
                    continue;
                }
                if (beam) {
                    drawBeam(each, dispatcher);
                }
                drawLabel(font, dispatcher, each, dx, dy, dz, distance, showDistance, labelScale);
                drawn++;
            }
        } finally {
            GlStateManager.enableCull();
            GlStateManager.disableBlend();
            GlStateManager.enableTexture();
            GlStateManager.enableDepthTest();
            GlStateManager.depthMask(true);
            // As this hook found it: vanilla ends the world pass with fog
            // disabled, just before this runs. See Teamview.
            GlStateManager.disableFog();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    /** A thin column of the waypoint's colour, from the bottom of the world to the top. */
    private static void drawBeam(WaypointList.Waypoint each, EntityRenderDispatcher dispatcher) {
        double left = each.x + 0.35 - dispatcher.cameraX;
        double right = each.x + 0.65 - dispatcher.cameraX;
        double near = each.z + 0.35 - dispatcher.cameraZ;
        double far = each.z + 0.65 - dispatcher.cameraZ;
        double bottom = 0 - dispatcher.cameraY;
        double top = 256 - dispatcher.cameraY;
        int red = (each.rgb >> 16) & 0xFF;
        int green = (each.rgb >> 8) & 0xFF;
        int blue = each.rgb & 0xFF;
        GlStateManager.disableTexture();
        GlStateManager.enableDepthTest();
        GlStateManager.depthMask(false);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        double[][] sides = {{left, near, right, near}, {right, near, right, far}, {right, far, left, far},
            {left, far, left, near}};
        for (double[] side : sides) {
            buffer.vertex(side[0], bottom, side[1]).color(red, green, blue, 90).next();
            buffer.vertex(side[2], bottom, side[3]).color(red, green, blue, 90).next();
            buffer.vertex(side[2], top, side[3]).color(red, green, blue, 90).next();
            buffer.vertex(side[0], top, side[1]).color(red, green, blue, 90).next();
        }
        tessellator.draw();
    }

    /**
     * The name and distance, facing the camera at a constant size on screen
     * and seen through anything, so it can always be found.
     */
    private static void drawLabel(TextRenderer font, EntityRenderDispatcher dispatcher, WaypointList.Waypoint each,
            double dx, double dy, double dz, double distance, boolean showDistance, float labelScale) {
        // Labels far away are drawn nearer along the same line, scaled up to
        // match, so the far plane never clips them.
        double drawnAt = Math.min(distance, 48.0);
        double shrink = distance > 0.0 ? drawnAt / distance : 1.0;
        float scale = 0.0266F * labelScale * (float) Math.max(1.0, drawnAt / 6.0);
        String text = each.name + (showDistance ? " §7" + Math.round(distance) + "m" : "");
        int half = font.getStringWidth(text) / 2;

        GlStateManager.pushMatrix();
        GlStateManager.translate(dx * shrink, dy * shrink, dz * shrink);
        GlStateManager.rotate(-dispatcher.yaw, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(dispatcher.pitch, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-scale, -scale, scale);
        GlStateManager.disableDepthTest();
        GlStateManager.depthMask(false);
        GlStateManager.disableTexture();
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        int red = (each.rgb >> 16) & 0xFF;
        int green = (each.rgb >> 8) & 0xFF;
        int blue = each.rgb & 0xFF;
        buffer.vertex(-half - 2, -2, 0.0).color(0, 0, 0, 110).next();
        buffer.vertex(-half - 2, 9, 0.0).color(0, 0, 0, 110).next();
        buffer.vertex(half + 2, 9, 0.0).color(0, 0, 0, 110).next();
        buffer.vertex(half + 2, -2, 0.0).color(0, 0, 0, 110).next();
        buffer.vertex(-half - 2, 9, 0.0).color(red, green, blue, 255).next();
        buffer.vertex(-half - 2, 10, 0.0).color(red, green, blue, 255).next();
        buffer.vertex(half + 2, 10, 0.0).color(red, green, blue, 255).next();
        buffer.vertex(half + 2, 9, 0.0).color(red, green, blue, 255).next();
        tessellator.draw();
        GlStateManager.enableTexture();
        font.draw(text, -half, 0, 0xFF000000 | each.rgb);
        GlStateManager.popMatrix();
    }

    private static Keybind key(FathomModule module, String id) {
        for (Keybind each : module.keybinds()) {
            if (id.equals(each.id())) {
                return each;
            }
        }
        return null;
    }

    private static boolean held(Keybind key) {
        if (key == null || !key.isBound()) {
            return false;
        }
        if (key.isMouseButton()) {
            return MouseButtons.isDown(key.mouseButton());
        }
        return Keyboard.isCreated() && key.key() > 0 && key.key() < Keyboard.KEYBOARD_SIZE
                && Keyboard.isKeyDown(key.key());
    }

    private static void tell(MinecraftClient client, String line) {
        if (client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(new LiteralText("§b[Waypoints]§r " + line));
        }
    }

    private static File file(MinecraftClient client) {
        return new File(new File(client.runDirectory, "config"), "fathom-waypoints.txt");
    }

    private static void ensureLoaded(MinecraftClient client) {
        if (loaded || client == null) {
            return;
        }
        loaded = true;
        File file = file(client);
        List<String> lines = Collections.emptyList();
        if (file.isFile()) {
            try {
                lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                System.out.println("[Fathom] Waypoints: could not read " + file + ": " + unreadable);
            }
        }
        LIST.load(lines);
        savedRevision = LIST.revision();
    }

    private static void save(MinecraftClient client) {
        if (!loaded || LIST.revision() == savedRevision) {
            return;
        }
        savedRevision = LIST.revision();
        File file = file(client);
        try {
            file.getParentFile().mkdirs();
            File next = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.write(next.toPath(), LIST.save(), StandardCharsets.UTF_8);
            Files.move(next.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unwritable) {
            System.out.println("[Fathom] Waypoints: could not save " + file + ": " + unwritable);
        }
    }
}
