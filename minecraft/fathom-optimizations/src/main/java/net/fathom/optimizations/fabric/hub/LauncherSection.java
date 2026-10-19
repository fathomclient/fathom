package net.fathom.optimizations.fabric.hub;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Box;
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

    private static final String[] CONNECTION_ACTIONS = {
        "Bring the launcher to the front",
        "Choose which installation launches next",
        "Ask what the launcher knows",
    };

    private String note = "";
    private final Box page = Box.column("launcher.page");
    private final Box stateCard = Box.column("launcher.state").height(84f).padding(Skin.S4);
    private final Box actionRow = Box.row("launcher.actions").height(Skin.H_MD).gap(Skin.S3);
    private final Box openButton = Box.fixed("launcher.open", 170f, Skin.H_MD);
    private final Box recheckButton = Box.fixed("launcher.recheck", 120f, Skin.H_MD);
    private final Box noteBox = Box.row("launcher.note").grow(1f);
    private final Box details = Box.column("launcher.details").padding(Skin.S4, Skin.S3);
    private final Box[] detailRows = new Box[CONNECTION_ACTIONS.length];

    LauncherSection() {
        page.add(stateCard);
        page.add(Box.column("launcher.state.after").height(Skin.S5));
        actionRow.add(openButton);
        actionRow.add(recheckButton);
        actionRow.add(noteBox);
        page.add(actionRow);
        page.add(Box.column("launcher.actions.after").height(Skin.S6));
        page.add(details);
        details.add(Box.column("launcher.details.heading").height(24f));
        for (int index = 0; index < detailRows.length; index++) {
            detailRows[index] = Box.row("launcher.detail." + index).height(22f);
            details.add(detailRows[index]);
        }
        details.add(Box.column("launcher.details.gap").height(Skin.S3));
        details.add(Box.column("launcher.details.security").height(22f));
        details.add(Box.column("launcher.details.loopback").height(22f));
    }

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
        // The right gutter every Hub page leaves, so its panels end where theirs do.
        area = Rect.of(area.x, area.y, area.right() - Skin.SCROLL_GUTTER, area.bottom());
        Controls controls = context.controls();
        LauncherBridge bridge = Runtime.bridge();
        LauncherBridge.State state = bridge.state();
        page.layout(ui, area.x, area.y, area.width, area.height);

        // -- state card -------------------------------------------------------
        Rect card = stateCard.rect();
        FathomComponents.card(ui, card, 0f, 0f);
        Rect inner = stateCard.content();

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
        Rect actions = actionRow.rect();
        String action = state == LauncherBridge.State.READY ? "Bring to front" : "Open Fathom";
        Rect open = openButton.rect();
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
        Rect recheck = recheckButton.rect();
        if (controls.secondary(ui, "launcher.refresh", recheck, "Recheck", true)) {
            bridge.refresh();
            note = "";
        }
        if (!note.isEmpty()) {
            ui.text(note, noteBox.rect().x + Skin.S1, Controls.baseline(ui, actions, Type.BODY), Type.BODY, Skin.GOOD);
        }

        // -- what crosses -----------------------------------------------------
        Rect below = details.rect();
        FathomComponents.panel(ui, below);
        Rect detailContent = details.content();
        Controls.label(ui, "What this connection carries", detailContent.x, detailContent.y);
        float y = 0f;
        for (int index = 0; index < CONNECTION_ACTIONS.length; index++) {
            Rect detail = detailRows[index].rect();
            String line = CONNECTION_ACTIONS[index];
            y = detail.y;
            ui.rounded(detailContent.x + 3f, y + 7f, detailContent.x + 7f, y + 11f, 2f,
                    Skin.TEXT_DISABLED);
            ui.text(line, detailContent.x + 18f, y + ui.ascent(Type.BODY), Type.BODY, Skin.TEXT_FAINT);
        }
        y = details.children().get(5).rect().y;
        ui.text("No token or password ever crosses it.", detailContent.x, y + ui.ascent(Type.BODY), Type.BODY,
                Skin.TEXT_MUTED);
        y = details.children().get(6).rect().y;
        ui.text(Text.fit(ui, "Loopback only, with a key that dies with this session.", Type.SMALL,
                        detailContent.width),
                detailContent.x, y + ui.ascent(Type.SMALL), Type.SMALL, Skin.TEXT_DISABLED);
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
