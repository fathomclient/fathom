package net.fathom.optimizations.fabric.hub;

import org.lwjgl.input.Keyboard;

import net.fathom.optimizations.core.module.ClientPrefs;
import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.ResponsiveGrid;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.menu.MenuScenes;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.FathomSettingRows;
import net.fathom.optimizations.fabric.ui.Skin;

/**
 * The Hub's one appearance destination.
 *
 * <p>Presentation is profile-scoped because a player may want gameplay glass
 * in one loadout and a quieter Fathom scene in another. The scene itself is a
 * launcher-wide appearance choice, written immediately to the same
 * {@code appearance.json} the desktop picker uses. There is no game-only list.
 */
final class AppearanceSection implements Section {

    private static final float TILE_GAP = Skin.S3;
    private static final float TILE_LABEL = 28f;
    private static final String[] PRESENTATION = {"Fathom background", "Gameplay glass"};

    private final Scroller scroller = new Scroller();
    private Motion glassReveal;
    private int keyboardIndex = -1;
    private boolean keyboardActive;
    private int lastColumns = 1;

    @Override
    public String title() {
        return "Appearance";
    }

    @Override
    public Icon icon() {
        return Icon.LAYERS;
    }

    @Override
    public boolean searchable() {
        return false;
    }

    @Override
    public String subtitle() {
        return "Presentation, glass, and the shared Fathom background.";
    }

    @Override
    public void opened(HubContext context) {
        scroller.reset();
        // Read again: the editor may have changed them since this page last showed.
        editorPrefs = null;
        boolean gameplay = net.fathom.optimizations.fabric.Runtime.gameplayGlass();
        glassReveal = new Motion(gameplay ? 1f : 0f, Timing.NAVIGATION);
        MenuScenes.Scene selected = MenuScenes.selected();
        MenuScenes.Scene[] scenes = MenuScenes.all();
        keyboardIndex = 0;
        keyboardActive = false;
        for (int index = 0; index < scenes.length; index++) {
            if (scenes[index].id.equals(selected.id)) {
                keyboardIndex = index;
                break;
            }
        }
    }

    /** The presentation group: its heading and one row. */
    private static final float PRESENTATION_BLOCK = FathomSettingRows.SECTION_HEADING
            + FathomSettingRows.DESCRIBED_ROW * 2f;
    /** The HUD editor group: its heading and the toolbar's two rows. */
    private static final float EDITOR_BLOCK = FathomSettingRows.SECTION_HEADING
            + FathomSettingRows.DESCRIBED_ROW * 2f;
    /** The gameplay glass group: its heading and the blur and dim rows. */
    private static final float GLASS_BLOCK = FathomSettingRows.SECTION_HEADING
            + FathomSettingRows.DESCRIBED_ROW * 2f;

    /**
     * Three groups, as a module page has them: Presentation, Gameplay glass
     * (only while that is the presentation, sliding open and shut), and
     * Background. Headings over flat group surfaces, the shared settings rows,
     * the shared controls.
     */
    @Override
    public void render(HubContext context, Rect area) {
        Renderer ui = context.ui();
        if (glassReveal == null) {
            glassReveal = new Motion(net.fathom.optimizations.fabric.Runtime.gameplayGlass() ? 1f : 0f,
                    Timing.NAVIGATION);
        }
        glassReveal.to(net.fathom.optimizations.fabric.Runtime.gameplayGlass() ? 1f : 0f, context.now());
        float glass = glassReveal.opacity(context.now());

        MenuScenes.Scene[] scenes = MenuScenes.all();
        float width = area.width - Skin.SCROLL_GUTTER;
        float pickerWidth = Math.max(1f, width - Skin.CARD_PADDING * 2f);
        lastColumns = ResponsiveGrid.columns(pickerWidth, 150f, TILE_GAP, 4);
        float cellWidth = ResponsiveGrid.cellWidth(pickerWidth, TILE_GAP, lastColumns);
        float imageHeight = cellWidth / MenuScenes.aspect();
        float tileHeight = imageHeight + TILE_LABEL;
        float gridHeight = ResponsiveGrid.contentHeight(scenes.length, lastColumns, tileHeight, TILE_GAP);
        float creditLine = ui.lineHeight(Type.SMALL) + Skin.S2;
        float backgroundSurface = Skin.CARD_PADDING * 2f + gridHeight + creditLine;
        float backgroundBlock = FathomSettingRows.SECTION_HEADING + backgroundSurface;
        float glassBlock = glass * (Skin.SECTION_GAP + GLASS_BLOCK);
        float contentHeight = PRESENTATION_BLOCK + glassBlock + Skin.SECTION_GAP + EDITOR_BLOCK
                + Skin.SECTION_GAP + backgroundBlock + Skin.S4;
        float offset = scroller.offset(context.delta(), contentHeight, area.height);
        float y = area.y - offset;

        ui.clip(area.x, area.y, area.right(), area.bottom());
        presentation(context, new Rect(area.x, y, width, PRESENTATION_BLOCK));
        y += PRESENTATION_BLOCK;

        if (glass > 0.001f) {
            // Revealed from the top, as one block: the clip grows with it.
            Rect reveal = new Rect(area.x, y, width, glassBlock);
            ui.clip(reveal.x, Math.max(area.y, reveal.y), reveal.right(), Math.min(area.bottom(), reveal.bottom()));
            ui.pushAlpha(glass);
            glassControls(context, new Rect(area.x, y + Skin.SECTION_GAP, width, GLASS_BLOCK), glass > 0.94f);
            ui.popAlpha();
            ui.unclip();
            y += glassBlock;
        }

        y += Skin.SECTION_GAP;
        editorGroup(context, new Rect(area.x, y, width, EDITOR_BLOCK));
        y += EDITOR_BLOCK + Skin.SECTION_GAP;
        Rect heading = new Rect(area.x, y, width, FathomSettingRows.SECTION_HEADING);
        FathomSettingRows.heading(ui, heading, "Background", "Shared with the launcher.");
        y = heading.bottom();
        Rect surface = new Rect(area.x, y, width, backgroundSurface);
        FathomSettingRows.surface(ui, surface);
        float contentX = surface.x + Skin.CARD_PADDING;
        float gridTop = surface.y + Skin.CARD_PADDING;

        MenuScenes.Scene selected = MenuScenes.selected();
        for (int index = 0; index < scenes.length; index++) {
            int row = index / lastColumns;
            int column = index % lastColumns;
            Rect tile = new Rect(contentX + column * (cellWidth + TILE_GAP),
                    gridTop + row * (tileHeight + TILE_GAP), cellWidth, tileHeight);
            if (tile.bottom() < area.y || tile.y > area.bottom()) {
                continue;
            }
            drawScene(context, scenes[index], index, selected, tile, imageHeight);
        }

        if (selected.credit != null) {
            float creditTop = gridTop + gridHeight + Skin.S2;
            ui.text(Text.fit(ui, selected.credit, Type.SMALL, pickerWidth), contentX,
                    creditTop + ui.ascent(Type.SMALL), Type.SMALL, Skin.TEXT_DISABLED);
        }
        ui.unclip();
        scroller.rail(ui, new Rect(area.x, area.y, width + Skin.S4, area.height),
                context.pointer(), context.now());
    }

    /** Fathom background or gameplay glass, with what the choice means right now as its detail. */
    private void presentation(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Rect heading = new Rect(area.x, area.y, area.width, FathomSettingRows.SECTION_HEADING);
        FathomSettingRows.heading(ui, heading, "Presentation", "");
        Rect surface = new Rect(area.x, heading.bottom(), area.width, FathomSettingRows.DESCRIBED_ROW * 2f);
        FathomSettingRows.surface(ui, surface);
        Rect first = new Rect(surface.x, surface.y, surface.width, FathomSettingRows.DESCRIBED_ROW);
        Rect second = new Rect(surface.x, first.bottom(), surface.width, FathomSettingRows.DESCRIBED_ROW);

        int selected = net.fathom.optimizations.fabric.Runtime.gameplayGlass() ? 1 : 0;
        String note = selected == 0
                ? "The selected scene behind every Fathom surface."
                : context.client().world == null
                        ? "Available once a world is visible."
                        : "The live game stays visible behind the window.";
        float natural = controls.segmentedNaturalWidth(ui, PRESENTATION, first.width * 0.6f);
        Rect control = FathomSettingRows.row(ui, controls, "appearance.row.presentation", first,
                "Behind the window", note, natural, Skin.H_SM, true,
                controls.pointerOverVisible(first), false);
        int chosen = controls.segmentedNatural(ui, "appearance.presentation", control, PRESENTATION, selected);
        if (chosen != selected) {
            context.hud().prefs().setRshiftBackgroundChoice(chosen);
            context.markDirty();
        }

        net.fathom.optimizations.core.hud.HubState state = context.hud().hubState();
        Rect toggle = FathomSettingRows.row(ui, controls, "appearance.row.reopen", second,
                "Reopen the last page", "RShift opens on the page you left, scrolled where it was.",
                Controls.TOGGLE_WIDTH, Controls.TOGGLE_HEIGHT, true, controls.pointerOverVisible(second), true);
        if (controls.toggle(ui, "appearance.reopen", toggle, state.reopenLast(), true)) {
            state.setReopenLast(!state.reopenLast());
            // Written now: a preference changed from this page should not wait for the Hub to close.
            state.write(context.hud().gameDirectory());
        }
    }

    /**
     * The HUD editor's toolbar: whether it shows, and how big. The editor's own
     * file holds them (they belong to the tool, not to a profile), read and
     * written here as they change, never per frame.
     */
    private void editorGroup(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Rect heading = new Rect(area.x, area.y, area.width, FathomSettingRows.SECTION_HEADING);
        FathomSettingRows.heading(ui, heading, "HUD editor", "");
        float rowHeight = FathomSettingRows.DESCRIBED_ROW;
        Rect surface = new Rect(area.x, heading.bottom(), area.width, rowHeight * 2f);
        FathomSettingRows.surface(ui, surface);
        if (editorPrefs == null) {
            editorPrefs = net.fathom.optimizations.core.hud.EditorPrefs.read(context.hud().gameDirectory());
        }
        Rect first = new Rect(surface.x, surface.y, surface.width, rowHeight);
        Rect second = new Rect(surface.x, first.bottom(), surface.width, rowHeight);

        Rect toggle = FathomSettingRows.row(ui, controls, "appearance.editor.toolbar.row", first,
                "Show editor toolbar", "The bar along the bottom of the HUD editor. Press T in the editor to hide it.",
                Controls.TOGGLE_WIDTH, Controls.TOGGLE_HEIGHT, true, controls.pointerOverVisible(first), false);
        if (controls.toggle(ui, "appearance.editor.toolbar", toggle, editorPrefs.toolbar(), true)) {
            editorPrefs.setToolbar(!editorPrefs.toolbar());
            editorPrefs.write(context.hud().gameDirectory());
        }
        String[] sizes = net.fathom.optimizations.core.hud.EditorPrefs.TOOLBAR_SIZE_CHOICES;
        float natural = controls.segmentedNaturalWidth(ui, sizes, second.width * 0.5f);
        Rect control = FathomSettingRows.row(ui, controls, "appearance.editor.size.row", second,
                "Toolbar size", "Compact is a shorter bar with smaller buttons.", natural, Skin.H_SM,
                editorPrefs.toolbar(), controls.pointerOverVisible(second), true);
        int selected = editorPrefs.toolbarSizeChoice();
        int chosen = controls.segmentedNatural(ui, "appearance.editor.size", control, sizes, selected);
        if (chosen != selected && editorPrefs.toolbar()) {
            editorPrefs.setToolbarSizeChoice(chosen);
            editorPrefs.write(context.hud().gameDirectory());
        }
    }

    /** The editor's preferences, read when this page first needs them. */
    private net.fathom.optimizations.core.hud.EditorPrefs editorPrefs;

    /** How strongly the live game is blurred and darkened behind the window. */
    private void glassControls(HubContext context, Rect area, boolean enabled) {
        Renderer ui = context.ui();
        Rect heading = new Rect(area.x, area.y, area.width, FathomSettingRows.SECTION_HEADING);
        FathomSettingRows.heading(ui, heading, "Gameplay glass", "");
        float rowHeight = FathomSettingRows.DESCRIBED_ROW;
        Rect surface = new Rect(area.x, heading.bottom(), area.width, rowHeight * 2f);
        FathomSettingRows.surface(ui, surface);
        glassRow(context, new Rect(surface.x, surface.y, surface.width, rowHeight), "Blur",
                "How much the game behind the window is blurred.", "appearance.glass.blur",
                ClientPrefs.RSHIFT_BLUR_CHOICES, context.hud().prefs().rshiftBlurChoice(), enabled, false);
        glassRow(context, new Rect(surface.x, surface.y + rowHeight, surface.width, rowHeight), "Dim",
                "Darkens the game behind the window so text stays readable.", "appearance.glass.dim",
                ClientPrefs.RSHIFT_DIM_CHOICES, context.hud().prefs().rshiftDimChoice(), enabled, true);
    }

    private void glassRow(HubContext context, Rect area, String title, String detail, String id,
            String[] options, int selected, boolean enabled, boolean last) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Rect picker = FathomSettingRows.row(ui, controls, id + ".row", area, title, detail,
                Math.min(150f, area.width * 0.5f), Skin.H_SM, enabled, controls.pointerOverVisible(area), last);
        int chosen = controls.dropdown(ui, id, picker, options, selected, enabled);
        if (chosen != selected) {
            if (id.endsWith("blur")) {
                context.hud().prefs().setRshiftBlurChoice(chosen);
            } else {
                context.hud().prefs().setRshiftDimChoice(chosen);
            }
            context.markDirty();
        }
    }

    private void drawScene(HubContext context, MenuScenes.Scene scene, int index,
            MenuScenes.Scene selected, Rect tile, float imageHeight) {
        Renderer ui = context.ui();
        Controls.State state = context.controls().state("appearance.background." + scene.id, tile, true);
        Rect image = new Rect(tile.x, tile.y, tile.width, imageHeight);
        float radius = Skin.R_SMALL;
        ui.rounded(image.x, image.y, image.right(), image.bottom(), radius, Skin.RECESSED);
        MenuScenes.thumbnail(ui, scene, image, radius, Skin.RECESSED);

        boolean active = scene.id.equals(selected.id);
        int edge = active ? Skin.EDGE_ELEVATED
                : Skin.mix(Skin.EDGE_SURFACE, Skin.EDGE_ELEVATED, state.hover);
        ui.rim(image.x, image.y, image.right(), image.bottom(), radius, edge);
        if (!active && state.hover > 0.01f) {
            ui.rounded(image.x, image.y, image.right(), image.bottom(), radius,
                    Skin.scaleAlpha(Skin.SURFACE_HOVER, state.hover * 0.35f));
        }
        if (active) {
            float badge = 20f;
            Rect mark = new Rect(image.right() - badge - Skin.S2, image.y + Skin.S2, badge, badge);
            ui.rounded(mark.x, mark.y, mark.right(), mark.bottom(), Skin.pill(mark), Skin.ELEVATED);
            ui.rim(mark.x, mark.y, mark.right(), mark.bottom(), Skin.pill(mark), Skin.EDGE_ELEVATED);
            ui.icon(Icon.CHECK, mark.x + 4f, mark.y + 4f, 12f, Skin.TEXT);
        }
        int label = active ? Skin.TEXT : Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, state.hover);
        ui.text(Text.fit(ui, scene.label, Type.CAPTION, tile.width), tile.x,
                Controls.baseline(ui, tile.y + imageHeight, TILE_LABEL, Type.CAPTION),
                Type.CAPTION, label);
        if (keyboardActive && keyboardIndex == index) {
            ui.focusRing(image.x, image.y, image.right(), image.bottom(), radius, 2f, Skin.FOCUS);
        }
        if (state.clicked) {
            keyboardIndex = index;
            keyboardActive = false;
            if (ui instanceof net.fathom.optimizations.fabric.gfx.GlRenderer) {
                ((net.fathom.optimizations.fabric.gfx.GlRenderer) ui).flush();
            }
            MenuScenes.choose(scene);
        }
    }

    @Override
    public float scrollPosition() {
        return scroller.position();
    }

    @Override
    public void restore(HubContext context, String pageId, float scroll) {
        scroller.restore(scroll);
    }

    @Override
    public void scroll(int notches) {
        scroller.wheel(notches);
    }

    @Override
    public boolean key(HubContext context, char character, int keyCode) {
        MenuScenes.Scene[] scenes = MenuScenes.all();
        if (scenes.length == 0) {
            return false;
        }
        int next = keyboardIndex < 0 ? 0 : keyboardIndex;
        if (keyCode == Keyboard.KEY_RIGHT) next++;
        else if (keyCode == Keyboard.KEY_LEFT) next--;
        else if (keyCode == Keyboard.KEY_DOWN) next += lastColumns;
        else if (keyCode == Keyboard.KEY_UP) next -= lastColumns;
        else if (keyCode == Keyboard.KEY_TAB) next += Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) ? -1 : 1;
        else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_SPACE) {
            MenuScenes.choose(scenes[Math.max(0, Math.min(next, scenes.length - 1))]);
            return true;
        } else {
            return false;
        }
        keyboardIndex = (next % scenes.length + scenes.length) % scenes.length;
        keyboardActive = true;
        return true;
    }
}
