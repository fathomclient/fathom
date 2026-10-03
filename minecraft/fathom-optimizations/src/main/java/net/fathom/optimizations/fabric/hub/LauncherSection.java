package net.fathom.optimizations.fabric.hub;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.bridge.LauncherBridge;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.FathomComponents;
import net.fathom.optimizations.fabric.ui.Skin;

/**
 * The Launcher page: one button, and the truth about what it will do.
 *
 * <h2>Why the state is shown and not hidden</h2>
 *
 * <p>"Open Fathom" does two quite different things depending on whether the
 * launcher is running: it either raises a window or starts a program. Those take
 * different amounts of time and fail in different ways, and a button that did
 * both silently would leave somebody staring at a screen wondering whether they
 * had pressed it.
 *
 * <h2>What this page is careful about</h2>
 *
 * <p>Everything the connection can carry is listed on screen, because a client
 * that can talk to a launcher holding a Microsoft session is a client people are
 * right to ask questions about. The answer is that three verbs cross it, none of
 * them carries a credential, and the module has no way to ask for one — and
 * saying that in the interface is better than it being true and undiscoverable.
 */
final class LauncherSection implements Section {

    private String note = "";

    @Override
    public String title() {
        return "Launcher";
    }

    @Override
    public Icon icon() {
        return Icon.EXTERNAL;
    }

    @Override
    public boolean searchable() {
        return false;
    }

    @Override
    public String subtitle() {
        return "Launcher connection.";
    }

    @Override
    public void opened(HubContext context) {
        note = "";
        Runtime.bridge().refresh();
    }

    @Override
    public void render(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        LauncherBridge bridge = Runtime.bridge();
        LauncherBridge.State state = bridge.state();

        // -- state card -------------------------------------------------------
        Rect card = area.top(84f);
        FathomComponents.card(ui, card, 0f, 0f);
        Rect inner = card.inset(Skin.S4, Skin.S4);

        int dot = state == LauncherBridge.State.READY
                ? Skin.GOOD
                : (state == LauncherBridge.State.CLOSED ? Skin.WARN : Skin.TEXT_DISABLED);
        // The dot centred on the title's line; title over detail by the shared stack.
        float dotCentre = inner.y + ui.lineHeight(Type.ROW_TITLE) / 2f;
        ui.rounded(inner.x, dotCentre - 4.5f, inner.x + 9f, dotCentre + 4.5f, 4.5f, dot);
        float textX = inner.x + 9f + Skin.S2;
        ui.text(label(state), textX, Controls.stackTitle(ui, inner.y, Type.ROW_TITLE), Type.ROW_TITLE, Skin.TEXT);
        ui.text(Text.fit(ui, bridge.detail(), Type.SMALL, inner.right() - textX), textX,
                Controls.stackDetail(ui, inner.y, Type.ROW_TITLE, Type.SMALL), Type.SMALL, Skin.TEXT_FAINT);

        // -- actions ----------------------------------------------------------
        Rect actions = area.belowTop(84f + Skin.S5).top(Skin.H_MD);
        String action = state == LauncherBridge.State.READY ? "Bring to front" : "Open Fathom";
        Rect open = new Rect(actions.x, actions.y, 170f, Skin.H_MD);
        if (controls.primary(ui, "launcher.open", open, action, true)) {
            boolean was = state == LauncherBridge.State.READY;
            if (bridge.open()) {
                // What the bridge decided, not what this screen assumed. `open`
                // re-checks before starting anything, so a launcher opened by
                // hand since this page was drawn is raised rather than started a
                // second time -- and the note has to say which happened or it
                // will claim a second launcher was started.
                boolean running = bridge.state() == LauncherBridge.State.READY;
                note = running ? "Fathom raised." : bridge.detail();
                if (!was && !running) {
                    // Closing the hub gets the game out of the way of the window
                    // that is about to appear, which is what somebody who just
                    // pressed "Open Fathom" wanted.
                    context.close();
                }
            } else {
                note = "";
            }
        }
        Rect recheck = new Rect(open.right() + Skin.S3, actions.y, 120f, Skin.H_MD);
        if (controls.secondary(ui, "launcher.refresh", recheck, "Recheck", true)) {
            bridge.refresh();
            note = "";
        }
        if (!note.isEmpty()) {
            ui.text(note, recheck.right() + Skin.S4, Controls.baseline(ui, actions, Type.BODY), Type.BODY, Skin.GOOD);
        }

        // -- what crosses -----------------------------------------------------
        Rect below = area.belowTop(84f + Skin.S5 + Skin.H_MD + Skin.S6);
        Controls.label(ui, "What this connection carries", below.x, below.y);
        float y = below.y + 24f;
        for (String line : new String[] {
            "Bring the launcher to the front",
            "Choose which installation launches next",
            "Ask what the launcher knows",
        }) {
            ui.rounded(below.x + 3f, y + 7f, below.x + 7f, y + 11f, 2f, Skin.TEXT_DISABLED);
            ui.text(line, below.x + 18f, y + ui.ascent(Type.BODY), Type.BODY, Skin.TEXT_FAINT);
            y += 22f;
        }
        y += Skin.S3;
        ui.text("No token or password ever crosses it.", below.x, y + ui.ascent(Type.BODY), Type.BODY, Skin.TEXT_MUTED);
        y += 22f;
        ui.text(Text.fit(ui, "Loopback only, with a key that dies with this session.", Type.SMALL, below.width),
                below.x, y + ui.ascent(Type.SMALL), Type.SMALL, Skin.TEXT_DISABLED);
    }

    private static String label(LauncherBridge.State state) {
        switch (state) {
            case READY:
                return "Connected";
            case CLOSED:
                return "Launcher closed";
            case UNSUPPORTED:
                return "Version mismatch";
            case ABSENT:
            default:
                return "No launcher";
        }
    }
}
