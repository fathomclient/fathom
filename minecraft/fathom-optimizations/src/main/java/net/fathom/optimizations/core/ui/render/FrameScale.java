package net.fathom.optimizations.core.ui.render;

import net.fathom.optimizations.core.ui.UiScale;

/**
 * How a frame's logical units map onto the framebuffer.
 *
 * <p>A renderer is given the framebuffer's real size in device pixels and a
 * <b>pixel ratio</b> -- device pixels per logical unit. The logical size is the
 * framebuffer divided by that ratio, and the renderer rasterises at the full
 * framebuffer resolution whatever the ratio is. The only question is which ratio
 * a surface wants:
 *
 * <ul>
 *   <li>{@link Mode#FATHOM} -- Fathom's own screens. The ratio is
 *       {@link UiScale#scaleFor}: continuous in the framebuffer height, times the
 *       user's interface-size preference. A 1440p window gets a 2× interface
 *       rather than whatever integer Minecraft's GUI scale lands on.
 *   <li>{@link Mode#MINECRAFT} -- anything that has to line up with vanilla: HUD
 *       elements, overlays on a vanilla screen, anything positioned with the
 *       mouse coordinates Minecraft hands a {@code Screen}. The ratio is
 *       Minecraft's GUI scale factor, so one logical unit is exactly one of
 *       Minecraft's scaled pixels -- but text and curves are still drawn at the
 *       framebuffer's resolution, not magnified.
 * </ul>
 */
public final class FrameScale {

    public enum Mode {
        FATHOM,
        MINECRAFT
    }

    private int framebufferWidth = 1;
    private int framebufferHeight = 1;
    private float ratio = 1f;

    /**
     * @param guiScaleFactor Minecraft's resolved scale factor (1, 2, 3, ...), used
     *                       only in {@link Mode#MINECRAFT}
     * @param preference     the user's interface size, used only in {@link Mode#FATHOM}
     */
    public FrameScale set(Mode mode, int fbWidth, int fbHeight, int guiScaleFactor, float preference) {
        framebufferWidth = Math.max(1, fbWidth);
        framebufferHeight = Math.max(1, fbHeight);
        if (mode == Mode.MINECRAFT) {
            ratio = Math.max(1, guiScaleFactor);
        } else {
            ratio = UiScale.scaleFor(framebufferHeight, preference);
        }
        return this;
    }

    public int framebufferWidth() {
        return framebufferWidth;
    }

    public int framebufferHeight() {
        return framebufferHeight;
    }

    /** Device pixels per logical unit. */
    public float ratio() {
        return ratio;
    }

    public float logicalWidth() {
        return framebufferWidth / ratio;
    }

    public float logicalHeight() {
        return framebufferHeight / ratio;
    }

    /** A device-pixel mouse position (bottom-left origin, as LWJGL 2 reports it) in logical units. */
    public float mouseX(int deviceX) {
        return deviceX / ratio;
    }

    public float mouseY(int deviceY) {
        return (framebufferHeight - deviceY - 1) / ratio;
    }
}
