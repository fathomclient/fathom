package net.fathom.optimizations.fabric.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;

import net.fathom.optimizations.core.hud.CrosshairShape;
import net.fathom.optimizations.core.module.CrosshairPolicy;
import net.fathom.optimizations.core.module.CrosshairPresets;
import net.fathom.optimizations.core.module.FathomModule;

/**
 * A crosshair you choose.
 *
 * <h2>The shape is not worked out here</h2>
 *
 * <p>{@link CrosshairShape} turns the settings into whole-pixel rectangles, and
 * the preview in the settings drawer asks the same class for the same
 * rectangles. So the preview cannot drift from the crosshair, and the geometry
 * — which is the part with the interesting cases: gaps, outlines that must not
 * overlap, a dot that has to sit in the middle of an even-numbered line — has
 * tests that do not need a running game.
 *
 * <h2>When it is not drawn</h2>
 *
 * <p>The module's own switch, plus the four situations vanilla hides its own
 * crosshair in or the settings ask for: the debug screen (which draws an axis
 * gizmo instead), spectator mode with nothing targeted, third person, and behind
 * a screen. Vanilla's crosshair is hidden by {@code InGameHudCrosshairMixin}
 * under exactly the module's own switch, so there is never a frame with neither
 * or with both.
 *
 * <h2>Dynamic and target colour</h2>
 *
 * <p>Both are off by default and both are read from what the client already
 * knows: the player's own speed for the spread, and the entity the client has
 * already ray-traced for the colour. Nothing is asked of the server, and nothing
 * is drawn that tells the player something they could not see.
 */
public final class CrosshairWidget implements HudWidget {

    /** How long the hit flash lasts. Long enough to see, short enough not to trail. */
    private static final long FLASH_NANOS = 150_000_000L;

    private final CrosshairShape shape = new CrosshairShape();

    // The inputs the current geometry was built from, so it is rebuilt only
    // when one of them changes rather than on every frame.
    private int builtStyle = -1;
    private int builtLength = -1;
    private int builtThickness = -1;
    private int builtGap = -1;
    private boolean builtDot;
    private int builtDotSize = -1;
    private int builtOutline = -1;

    /** Extra gap from movement, in pixels, eased towards its target. */
    private static float spread;
    private static long spreadNanos;
    private long spreadFrame = -1L;
    private static long attackedAt;

    @Override
    public String id() {
        return "crosshair";
    }

    @Override
    public String placeholder() {
        return "+";
    }

    /** A left click the game processed, for the hit flash. */
    public static void attacked() {
        attackedAt = System.nanoTime();
    }

    /** A new world or player: no spread and no flash carried over. */
    public static void reset() {
        spread = 0f;
        spreadNanos = 0L;
        attackedAt = 0L;
    }

    private static FathomModule module() {
        return Widgets.module("crosshair");
    }

    private CrosshairShape.Style style(FathomModule module) {
        String chosen = module == null ? "Cross" : module.choice(CrosshairPresets.STYLE, "Cross");
        if ("Dot".equals(chosen)) {
            return CrosshairShape.Style.DOT;
        }
        if ("Circle".equals(chosen)) {
            return CrosshairShape.Style.CIRCLE;
        }
        if ("Tee".equals(chosen)) {
            return CrosshairShape.Style.TEE;
        }
        return CrosshairShape.Style.CROSS;
    }

    /**
     * The shape for this frame, rebuilt only when an input changes.
     *
     * <p>The movement spread is advanced here rather than in {@code draw},
     * because the box is measured before anything is drawn and a spread that
     * moved in between would size the box from one frame and the arms from the
     * next — which reads as a crosshair drifting off centre while walking.
     */
    private CrosshairShape geometry(FathomModule module) {
        long frame = Widgets.frameNumber();
        if (frame != spreadFrame) {
            spreadFrame = frame;
            updateSpread(MinecraftClient.getInstance(), module);
        }
        CrosshairShape.Style style = style(module);
        int length = (int) Math.round(module == null ? 4 : module.number(CrosshairPresets.LENGTH, 4));
        int thickness = (int) Math.round(module == null ? 1 : module.number(CrosshairPresets.THICKNESS, 1));
        int gap = (int) Math.round(module == null ? 2 : module.number(CrosshairPresets.GAP, 2))
                + (int) spread;
        boolean dot = module != null && module.toggle(CrosshairPresets.DOT, false);
        int dotSize = (int) Math.round(module == null ? 1 : module.number(CrosshairPresets.DOT_SIZE, 1));
        boolean outline = module == null || module.toggle(CrosshairPresets.OUTLINE, true);
        int outlineWidth = outline
                ? (int) Math.round(module.number(CrosshairPresets.OUTLINE_WIDTH, 1))
                : 0;

        if (style.ordinal() != builtStyle || length != builtLength || thickness != builtThickness
                || gap != builtGap || dot != builtDot || dotSize != builtDotSize
                || outlineWidth != builtOutline) {
            shape.build(style, length, thickness, gap, dot, dotSize, outlineWidth);
            builtStyle = style.ordinal();
            builtLength = length;
            builtThickness = thickness;
            builtGap = gap;
            builtDot = dot;
            builtDotSize = dotSize;
            builtOutline = outlineWidth;
        }
        return shape;
    }

    @Override
    public int width(MinecraftClient client, HudFont font) {
        return geometry(module()).size();
    }

    @Override
    public int height(MinecraftClient client, HudFont font) {
        return geometry(module()).size();
    }

    @Override
    public boolean visible(MinecraftClient client) {
        FathomModule module = module();
        if (!CrosshairPolicy.drawsCustom(module) || client == null || client.player == null
                || client.options == null) {
            return false;
        }
        if (client.options.debugEnabled && !client.player.getReducedDebugInfo()
                && !client.options.reducedDebugInfo) {
            // The debug screen draws its own axis gizmo where the crosshair goes.
            return false;
        }
        if (client.player.isSpectator() && client.targetedEntity == null) {
            return false;
        }
        if (client.options.perspective != 0 && module.toggle("hide.third-person", true)) {
            return false;
        }
        if (client.currentScreen != null && module.toggle("hide.gui", false)
                && !(client.currentScreen instanceof HudEditorScreen)) {
            return false;
        }
        return true;
    }

    @Override
    public void draw(MinecraftClient client, HudFont font, int x, int y, int alpha) {
        FathomModule module = module();
        if (module == null) {
            return;
        }
        CrosshairShape geometry = geometry(module);

        int opacity = (int) Math.round(module.number("opacity", 100));
        int drawn = Math.max(0, Math.min(255, alpha * Math.max(0, Math.min(100, opacity)) / 100));
        int colour = (drawn << 24) | (colour(client, module) & 0x00FFFFFF);
        int outlineColour = (drawn << 24) | (module.colour("outline.colour", 0x000000) & 0x00FFFFFF);

        // One batched draw: a circle crosshair with an outline is up to a
        // hundred rectangles, and a hundred separate fills is a hundred rounds
        // of blend and texture state for a shape twenty pixels across.
        QuadBatch.begin();
        try {
            paint(geometry.outline(), geometry.outlineCount(), x, y, outlineColour);
            paint(geometry.fill(), geometry.fillCount(), x, y, colour);
        } finally {
            QuadBatch.end();
        }
    }

    private static void paint(int[] rects, int count, int x, int y, int argb) {
        for (int index = 0; index < count; index += 4) {
            QuadBatch.rect(x + rects[index], y + rects[index + 1],
                    x + rects[index + 2], y + rects[index + 3], argb);
        }
    }

    /** The colour to draw in: the chosen one, or the feedback colour on a target or a hit. */
    private static int colour(MinecraftClient client, FathomModule module) {
        int base = module.colour("colour", 0xFFFFFF);
        if (!module.toggle("feedback", false)) {
            return base;
        }
        boolean flashing = System.nanoTime() - attackedAt < FLASH_NANOS;
        boolean onTarget = client.targetedEntity != null
                || (client.result != null && client.result.type == BlockHitResult.Type.ENTITY);
        return flashing || onTarget ? module.colour("feedback.colour", 0xFF5555) : base;
    }

    /**
     * How far the arms have moved out, from the player's own motion.
     *
     * <p>Eased in real time rather than per tick, so it is as smooth as the
     * frame rate allows and does not depend on it. The inputs are the horizontal
     * speed the player is actually travelling at and whether they are off the
     * ground, which is what a spread is meant to say.
     */
    private static void updateSpread(MinecraftClient client, FathomModule module) {
        if (client == null || module == null) {
            return;
        }
        long now = System.nanoTime();
        float elapsed = spreadNanos == 0L ? 0f : Math.min(0.25f, (now - spreadNanos) / 1_000_000_000f);
        spreadNanos = now;

        float target = 0f;
        ClientPlayerEntity player = client.player;
        if (module.toggle("dynamic", false) && player != null) {
            double dx = player.x - player.prevX;
            double dz = player.z - player.prevZ;
            double speed = Math.sqrt(dx * dx + dz * dz);
            target = (float) Math.min(4.0, speed * 22.0);
            if (!player.onGround) {
                target = Math.max(target, 2f);
            }
        }
        // About a fifth of a second to settle either way.
        float step = elapsed * 5f;
        spread = target > spread ? Math.min(target, spread + step * Math.max(1f, target))
                : Math.max(target, spread - step * Math.max(1f, spread));
    }
}
