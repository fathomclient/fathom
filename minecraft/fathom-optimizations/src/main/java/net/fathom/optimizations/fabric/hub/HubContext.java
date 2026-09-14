package net.fathom.optimizations.fabric.hub;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.core.input.KeybindCaptureState;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;
import net.fathom.optimizations.fabric.gfx.Pointer;
import net.fathom.optimizations.fabric.gfx.SpikeScreen;
import net.fathom.optimizations.fabric.hud.FathomHud;
import net.fathom.optimizations.fabric.hud.HudEditorScreen;
import net.fathom.optimizations.fabric.ui.Controls;

/**
 * Everything a section is allowed to reach.
 *
 * <h2>Why sections get this and not the screen</h2>
 *
 * <p>A section handed its parent screen could add a button, move the rail,
 * replace the header, or open a different screen entirely. None of those is
 * something a section should be able to do, and "should not" is not a boundary —
 * this is.
 *
 * <h2>Saving</h2>
 *
 * <p>Sections do not write files. They mark the context dirty and the shell
 * saves once, when the hub closes — so dragging a slider is not forty writes,
 * and a section cannot forget to persist something.
 */
public final class HubContext {

    private final MinecraftClient client;
    private final FathomHud hud;
    private final ModuleRegistry features;
    private final Controls controls;
    private final Screen screen;
    /** The renderer selected for the frame currently being drawn. */
    private Renderer renderer;
    private final Pointer pointer;
    private final KeybindCaptureState keybindCapture = new KeybindCaptureState();

    private float delta;
    private boolean dirty;
    private String search = "";
    /** Set when something changed that only a restart will apply. */
    private String restartNote;

    HubContext(
            MinecraftClient client,
            FathomHud hud,
            ModuleRegistry features,
            Controls controls,
            Screen screen,
            Pointer pointer) {
        this.client = client;
        this.hud = hud;
        this.features = features;
        this.controls = controls;
        this.screen = screen;
        this.pointer = pointer;
    }

    public MinecraftClient client() {
        return client;
    }

    /** What draws. Logical units in, device pixels out. */
    public Renderer ui() {
        if (renderer == null) {
            throw new IllegalStateException("the Hub has no open renderer frame");
        }
        return renderer;
    }

    /** Bind sections to the backend selected for this frame. */
    void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    /**
     * The mouse, as of this frame rather than as of this tick.
     *
     * <p>Sections rarely need it directly — the controls do the hit-testing —
     * but a list still has to know whether the pointer is over a row.
     */
    public Pointer pointer() {
        return pointer;
    }

    /** Seconds since the last frame, clamped. */
    public float delta() {
        return delta;
    }

    /** Shared monotonic UI time for every section animation. */
    public float now() {
        return net.fathom.optimizations.fabric.gfx.Gfx.clock().seconds();
    }

    void setDelta(float seconds) {
        this.delta = seconds;
    }

    public FathomHud hud() {
        return hud;
    }

    public ModuleRegistry features() {
        return features;
    }

    /** The controls, and the animation memory they share. */
    public Controls controls() {
        return controls;
    }

    /** The one capture owner shared by every Hub page. */
    public KeybindCaptureState keybindCapture() {
        return keybindCapture;
    }

    /**
     * The accent.
     *
     * <p>One colour, always. It used to depend on a theme; there are no themes
     * now, and an accent that changes is an accent that stops meaning "this is
     * the selected one".
     */
    public int accent() {
        return net.fathom.optimizations.fabric.ui.Skin.ACCENT;
    }

    /** What the header's search box currently holds. */
    public String search() {
        return search;
    }

    void setSearch(String value) {
        this.search = value == null ? "" : value;
    }

    /** Something changed and should be written when the hub closes. */
    public void markDirty() {
        dirty = true;
    }

    boolean dirty() {
        return dirty;
    }

    void saved() {
        dirty = false;
    }

    /**
     * Say that a change will not take effect until the game restarts.
     *
     * <p>Shown in the header, once, rather than as a badge on every row that
     * caused it: a setting that silently does nothing until next time is worse
     * than one that says so.
     */
    public void needsRestart(String what) {
        restartNote = what;
    }

    public String restartNote() {
        return restartNote;
    }

    // -----------------------------------------------------------------------
    // Actions a section may take
    // -----------------------------------------------------------------------

    /**
     * Switch a feature on or off, and its widgets and optimizations with it.
     *
     * <p>One call, so a feature cannot end up half-on: switched off in the list
     * while its optimization is still running, or on with its widget hidden.
     */
    public void setFeatureEnabled(FathomModule feature, boolean enabled) {
        feature.setEnabled(enabled);
        for (String id : feature.optimizations()) {
            if (Runtime.switches() != null) {
                Runtime.switches()
                        .setByUser(net.fathom.optimizations.core.registry.OptimizationId.of(id), enabled);
            }
        }
        if (feature.restart() == FathomModule.Restart.REQUIRED) {
            needsRestart(feature.name());
        }
        markDirty();
    }

    /** Open the HUD editor, coming back here when it closes. */
    public void editHud() {
        FathomNavigation.push(client, new HudEditorScreen(hud));
    }

    /**
     * Open the renderer comparison screen.
     *
     * <p>The spike the renderer was chosen with, kept rather than deleted: it is
     * the fastest way to see what a change to the text or shape pipeline did.
     */
    public void openSpike() {
        FathomNavigation.push(client, new SpikeScreen());
    }

    /** Close the hub — with the animation, not by vanishing. */
    public void close() {
        if (screen instanceof FathomHubScreen) {
            ((FathomHubScreen) screen).beginClose();
            return;
        }
        FathomNavigation.back(client, screen);
    }
}
