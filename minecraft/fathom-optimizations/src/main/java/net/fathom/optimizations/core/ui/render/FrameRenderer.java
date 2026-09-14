package net.fathom.optimizations.core.ui.render;

/**
 * A {@link Renderer} that owns its own frame.
 *
 * <h2>Why this exists beside {@code Renderer}</h2>
 *
 * <p>{@link Renderer} is what interface code draws with, and it deliberately
 * says nothing about frames: the GL backend's frame is opened by {@code Gfx},
 * which also advances the animation clock and records frame time, so a screen
 * never touches the backend directly. A second backend needs the same seam from
 * the other side -- something that can be told "a frame starts here, at this
 * size" and "it ends here" without knowing which library is underneath.
 *
 * <p>This is that seam, and it is the abstraction the NanoVG backend is loaded
 * behind. Interface code keeps calling {@link Renderer}; only the code that opens
 * a frame sees this type. It lives in {@code core} for the same reason
 * {@code Renderer} does: no Minecraft, no LWJGL, no GL. That matters twice over
 * here, because the NanoVG backend is loaded by an isolated classloader and this
 * interface is the one type both sides of that boundary must agree on.
 *
 * <h2>Units</h2>
 *
 * <p>The frame is sized in device pixels -- the framebuffer the game is drawing
 * into -- and a scale in device pixels per logical unit, from {@code UiScale}.
 * Everything drawn inside the frame is in logical units, exactly as with
 * {@link Renderer}.
 */
public interface FrameRenderer extends Renderer {

    /** The backend, its version, and the GL it found. For diagnostics. */
    String describe();

    /**
     * Open a frame.
     *
     * <p>The caller owns GL state around this: it must leave the viewport at the
     * framebuffer's size, and it must restore whatever the backend changes once
     * the frame has ended.
     *
     * @param framebufferWidth  the framebuffer being drawn into, in device pixels
     * @param framebufferHeight the framebuffer being drawn into, in device pixels
     * @param scale             device pixels per logical unit
     */
    void beginFrame(int framebufferWidth, int framebufferHeight, float scale);

    /** Submit the frame. Every {@link #beginFrame} must end in this or {@link #cancelFrame}. */
    void endFrame();

    /** Throw the frame away without drawing it -- for a draw that failed halfway. */
    void cancelFrame();

    /** Whether a frame is open. */
    boolean inFrame();

    /** Release GPU resources. Nothing may be drawn afterwards. */
    void dispose();
}
