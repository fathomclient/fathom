package net.fathom.optimizations.fabric.gfx;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.gui.screen.Screen;

import net.fathom.optimizations.core.ui.layout.TextStack;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Layer;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.core.ui.style.ControlSurface;
import net.fathom.optimizations.core.ui.style.Elevation;
import net.fathom.optimizations.core.ui.style.Material;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.generated.FathomTheme.Tokens;

/**
 * The compositor's proof: one screen with every primitive the interface is
 * made of, at the exact logical positions of the launcher's reference page
 * ({@code desktop/proof/renderer.html}), with the launcher's CSS values.
 * Screenshot both at the same size and compare.
 *
 * <p>Development only: opened by the UI tour's {@code proof} step, never by
 * anything a player can reach. {@code -Dfathom.proofBackdrop=<jpg>} sets the
 * picture behind it -- pass the launcher's poster so both sides frost the
 * same image.
 *
 * <p>What it proves, region by region: glass of three densities with their
 * inset rims and elevation shadows (A, B, D); text at every size the
 * interface uses, with tracking, kerning and both families (C); controls
 * built from the same fill and rim tokens (A, C, D); a clipped, scrolled list
 * whose clip cannot leak into what follows (B); a popover drawn before the
 * card under it, kept above it by its layer (D); and the corner of a 12px
 * control magnified eight times, where a quarter circle has to look like one.
 */
public final class RendererProofScreen extends Screen implements FathomScreen {

    private static final int TEXT = Tokens.COLOR_TEXT;
    private static final int SECONDARY = Tokens.COLOR_TEXT_SECONDARY;
    private static final int TERTIARY = Tokens.COLOR_TEXT_TERTIARY;

    private static int backdrop;
    private static float backdropAspect = 16f / 9f;

    @Override
    public boolean shouldPauseGame() {
        return false;
    }

    @Override
    public boolean handleFathomKey(char character, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            FathomNavigation.back(client, this);
            return true;
        }
        return false;
    }

    @Override
    protected void keyPressed(char character, int keyCode) {
        // FrameKeyboard owns the queue while this screen is current.
    }

    @Override
    public void render(int ignoredX, int ignoredY, float ignoredDelta) {
        ensureBackdrop();
        Renderer ui = Gfx.beginScreen(client, Runtime.uiScale());
        try {
            ui.pushLayer(Layer.BACKGROUND);
            ui.image(backdrop, 0f, 0f, ui.width(), ui.height(), 0f, backdropAspect);
            ui.popLayer();
            draw(ui);
        } finally {
            Gfx.endScreen();
        }
    }

    // -----------------------------------------------------------------------

    private static Type type(float size, Type.Weight weight, float tracking) {
        return new Type(size, weight, tracking);
    }

    /** A line of text whose line box (CSS line-height) has its top at y. */
    private static void line(Renderer ui, float x, float top, float lineHeight, String value, Type type, int ink) {
        ui.text(value, x, TextStack.baseline(ui, top, lineHeight, type), type, ink);
    }

    private static void centred(Renderer ui, float left, float top, float width, float height, String value,
            Type type, int ink) {
        ui.textCentred(value, left + width / 2f, TextStack.baseline(ui, top, height, type), type, ink);
    }

    private void draw(Renderer ui) {
        Type label = type(12f, Type.Weight.MEDIUM, 0.01f);
        Type heading = type(18f, Type.Weight.SEMIBOLD, -0.012f);
        Type body = type(14f, Type.Weight.REGULAR, -0.003f);
        Type caption = type(12f, Type.Weight.REGULAR, 0.005f);
        Type button = type(14f, Type.Weight.MEDIUM, -0.005f);
        Type buttonSmall = type(13f, Type.Weight.MEDIUM, -0.005f);
        Type strong = type(14f, Type.Weight.SEMIBOLD, -0.005f);
        Type value = type(13f, Type.Weight.MEDIUM, 0f);
        Type secondary = type(13f, Type.Weight.REGULAR, 0f);
        Type itemTitle = type(14f, Type.Weight.SEMIBOLD, -0.012f);
        Type plain14 = type(14f, Type.Weight.REGULAR, 0f);
        Type segment = type(13f, Type.Weight.MEDIUM, 0f);
        Type pill = type(12f, Type.Weight.MEDIUM, 0.01f);

        // --- A: a standard pane ------------------------------------------
        Material.STANDARD.paint(ui, 40, 40, 440, 340, 18);
        line(ui, 64, 64, 16, "current installation", label, SECONDARY);
        line(ui, 64, 86, 24, "minecraft 1.8.9", heading, TEXT);
        line(ui, 64, 116, 21, "the quick brown fox jumps over the lazy dog", body, SECONDARY);
        line(ui, 64, 140, 16, "1.8.9 · legacy fabric · 11 mods", caption, TERTIARY);
        ui.rounded(64, 176, 232, 220, 12, ControlSurface.PROMINENT);
        centred(ui, 64, 176, 168, 44, "play", strong, Tokens.COLOR_PROMINENT_TEXT);
        ControlSurface.paint(ui, 244, 176, 348, 220, 12, ControlSurface.FILL, false);
        centred(ui, 244, 176, 104, 44, "settings", button, TEXT);
        ui.icon(Icon.SETTINGS, 370, 190, 16, SECONDARY);
        ControlSurface.hairline(ui, 64, 240, 416);
        line(ui, 64, 256, 16, "client", caption.tracked(0f), SECONDARY);
        line(ui, 64, 274, 18, "fathom", value, TEXT);
        ControlSurface.hairlineDown(ui, 170, 256, 292);
        line(ui, 184, 256, 16, "preset", caption.tracked(0f), SECONDARY);
        line(ui, 184, 274, 18, "balanced", value, TEXT);
        ControlSurface.hairlineDown(ui, 290, 256, 292);
        line(ui, 304, 256, 16, "modules", caption.tracked(0f), SECONDARY);
        line(ui, 304, 274, 18, "11 active", value, TEXT);

        // --- B: a dense card, a clipped, scrolled list --------------------
        Material.DENSE.paint(ui, 40, 370, 440, 680, 18);
        String[] names = {"keystrokes", "armour status", "coordinates", "crosshair", "fps", "ping", "potions",
            "toggle sprint"};
        ui.clip(40, 378, 440, 672);
        for (int index = 0; index < names.length; index++) {
            float top = 378 + index * 56 - 20;
            if (index > 0) {
                ControlSurface.hairline(ui, 56, top, 424);
            }
            ControlSurface.paint(ui, 56, top + 12, 88, top + 44, 10, ControlSurface.SELECTED, false);
            centred(ui, 56, top + 12, 32, 32, names[index].substring(0, 1), type(14, Type.Weight.SEMIBOLD, 0f), TEXT);
            line(ui, 104, top + 8, 19, names[index], itemTitle, TEXT);
            line(ui, 104, top + 29, 19, "module " + (index + 1) + " of 8", secondary, SECONDARY);
        }
        ui.unclip();
        ui.rounded(433, 382, 437, 582, 2, ControlSurface.SCROLLBAR);

        // --- C: controls ----------------------------------------------------
        String[] options = {"low", "balanced", "high"};
        float trackWidth = 4f;
        float[] widths = new float[options.length];
        for (int index = 0; index < options.length; index++) {
            widths[index] = ui.measure(options[index], segment) + 32f;
            trackWidth += widths[index];
        }
        ControlSurface.paint(ui, 480, 40, 480 + trackWidth, 76, 12, ControlSurface.FAINT, false);
        float x = 482;
        for (int index = 0; index < options.length; index++) {
            if (index == 1) {
                ui.rounded(x, 42, x + widths[index], 74, 10, ControlSurface.SELECTED);
            }
            centred(ui, x, 42, widths[index], 32, options[index], segment, index == 1 ? TEXT : SECONDARY);
            x += widths[index];
        }

        toggle(ui, 480, 92, true);
        toggle(ui, 528, 92, false);
        pill(ui, 576, 89, 60, "active", pill);
        pill(ui, 644, 89, 52, "beta", pill);

        Icon[] icons = {Icon.HOME, Icon.MODS, Icon.GAUGE};
        String[] labels = {"home", "mods", "performance"};
        int[] fills = {ControlSurface.SELECTED, ControlSurface.FILL, 0};
        for (int index = 0; index < 3; index++) {
            float y = 130 + index * 40;
            int ink = index == 2 ? SECONDARY : TEXT;
            ui.rounded(480, y, 700, y + 36, 10, fills[index]);
            ui.icon(icons[index], 488, y + 8, 20, ink);
            line(ui, 520, y + 8, 20, labels[index], plain14, ink);
        }

        line(ui, 480, 260, 16, "caption 12 · the quick brown fox", caption, SECONDARY);
        line(ui, 480, 280, 19, "secondary 13 · the quick brown fox", secondary, SECONDARY);
        line(ui, 480, 303, 21, "body 14 · the quick brown fox", body, TEXT);
        line(ui, 480, 328, 21, "button 14 · the quick brown fox", button, TEXT);
        line(ui, 480, 352, 23, "heading 18 · the quick brown fox", heading, TEXT);
        line(ui, 480, 380, 31, "title 28 · fathom", type(28f, Type.Weight.SEMIBOLD, -0.022f), TEXT);
        line(ui, 480, 418, 18, "144 fps · 6.94 ms · 0123456789", type(13f, Type.Weight.MONO_REGULAR, 0f),
                SECONDARY);

        // --- D: field, dropdown, a card under a popover, the 8x lens --------
        ControlSurface.paint(ui, 840, 40, 1240, 76, 12, ControlSurface.SUNKEN, false);
        line(ui, 852, 47, 22, "search modules", plain14, TERTIARY);
        ControlSurface.paint(ui, 840, 92, 1020, 128, 12, ControlSurface.FILL, false);
        line(ui, 856, 100, 20, "balanced", buttonSmall, TEXT);
        ui.icon(Icon.CHEVRON_DOWN, 992, 102, 16, SECONDARY);

        // The popover is drawn first and the card after it, on purpose: its
        // layer, not the order, keeps it on top.
        ui.pushLayer(Layer.POPOVER);
        Material.DENSE.paint(ui, 840, 134, 1040, 300, 18);
        String[] items = {"low", "balanced", "high", "ultra"};
        for (int index = 0; index < items.length; index++) {
            float top = 142 + index * 38;
            if (index == 1) {
                ui.rounded(848, top, 1032, top + 36, 10, ControlSurface.HOVER);
            }
            line(ui, 860, top, 36, items[index], plain14, index == 1 ? TEXT : SECONDARY);
        }
        ui.popLayer();
        Material.LIGHT.paint(ui, 840, 200, 1240, 340, 18);
        line(ui, 1064, 224, 19, "layered card", itemTitle, TEXT);
        line(ui, 1064, 249, 19, "drawn after the popover;", secondary, SECONDARY);
        line(ui, 1064, 268, 19, "the popover stays above.", secondary, SECONDARY);

        Material.LIGHT.paint(ui, 840, 360, 1240, 680, 18);
        ui.clip(864, 384, 1216, 656);
        ui.pushTransform(864, 384, 8f, 0f, 0f);
        ControlSurface.paint(ui, 4, 4, 84, 40, 12, ControlSurface.FILL, false);
        line(ui, 18, 12, 20, "ag", type(13f, Type.Weight.MEDIUM, 0f), TEXT);
        ui.popTransform();
        ui.unclip();
    }

    private static void toggle(Renderer ui, float x, float y, boolean on) {
        ui.rounded(x, y, x + 32, y + 18, 9, on ? ControlSurface.SWITCH_TRACK_ON : ControlSurface.SWITCH_TRACK);
        ControlSurface.rim(ui, x, y, x + 32, y + 18, 9, on);
        float knob = on ? x + 16 : x + 2;
        Elevation.KNOB.paint(ui, knob, y + 2, knob + 14, y + 16, 7);
        ui.rounded(knob, y + 2, knob + 14, y + 16, 7, on ? ControlSurface.SWITCH_KNOB_ON : ControlSurface.SWITCH_KNOB);
        ui.inset(knob, y + 2, knob + 14, y + 16, 7, 0f, -ui.pixel(), 0f, Elevation.KNOB_LIP);
    }

    private static void pill(Renderer ui, float x, float y, float width, String value, Type type) {
        ControlSurface.paint(ui, x, y, x + width, y + 24, 12, ControlSurface.FILL, false);
        centred(ui, x, y, width, 24, value, type, TEXT);
    }

    // -----------------------------------------------------------------------

    private static void ensureBackdrop() {
        if (backdrop != 0) {
            return;
        }
        try {
            BufferedImage image;
            String path = System.getProperty("fathom.proofBackdrop", "");
            if (!path.isEmpty()) {
                image = ImageIO.read(new File(path));
            } else {
                InputStream stream = RendererProofScreen.class.getResourceAsStream(
                        "/assets/fathom-optimizations/textures/menu/bg_5rain/high/frame_000.jpg");
                image = ImageIO.read(stream);
                stream.close();
            }
            int w = image.getWidth();
            int h = image.getHeight();
            int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
            for (int value : argb) {
                pixels.put((byte) (value >> 16)).put((byte) (value >> 8)).put((byte) value).put((byte) 0xFF);
            }
            pixels.flip();
            backdrop = GL11.glGenTextures();
            GlStateManager.bindTexture(backdrop);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                    pixels);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            backdropAspect = w / (float) h;
        } catch (Exception problem) {
            System.err.println("[Fathom] Renderer proof: no backdrop (" + problem + ")");
            backdrop = -1;
        }
    }
}
