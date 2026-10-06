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
import net.fathom.optimizations.fabric.ui.FathomComponents;
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

    private static final float PRESENTATION_HEIGHT = 92f;
    private static final float GLASS_HEIGHT = 112f;
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
        return Icon.SETTINGS;
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
        boolean gameplay = net.fathom.optimizations.fabric.Runtime.gameplayGlass();
        glassReveal = new Motion(gameplay ? 1f : 0f, Timing.PANEL);
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

    @Override
    public void render(HubContext context, Rect area) {
        Renderer ui = context.ui();
        if (glassReveal == null) {
            glassReveal = new Motion(net.fathom.optimizations.fabric.Runtime.gameplayGlass() ? 1f : 0f,
                    Timing.PANEL);
        }
        glassReveal.to(net.fathom.optimizations.fabric.Runtime.gameplayGlass() ? 1f : 0f, context.now());
        float glass = glassReveal.opacity(context.now());

        MenuScenes.Scene[] scenes = MenuScenes.all();
        float width = Math.min(744f, area.width - Skin.S4);
        float pickerWidth = Math.max(1f, width - Skin.CARD_PADDING * 2f);
        lastColumns = ResponsiveGrid.columns(pickerWidth, 150f, TILE_GAP, 4);
        float cellWidth = ResponsiveGrid.cellWidth(pickerWidth, TILE_GAP, lastColumns);
        float imageHeight = cellWidth / MenuScenes.aspect();
        float tileHeight = imageHeight + TILE_LABEL;
        float gridHeight = ResponsiveGrid.contentHeight(scenes.length, lastColumns, tileHeight, TILE_GAP);
        float backgroundHeading = ui.lineHeight(Type.LABEL) + ui.lineHeight(Type.SMALL) + Skin.S3;
        float backgroundBlock = Skin.S3 + backgroundHeading + gridHeight + Skin.S4
                + ui.lineHeight(Type.SMALL) + Skin.S3;
        float presentationBlock = PRESENTATION_HEIGHT + glass * (GLASS_HEIGHT + Skin.SECTION_GAP);
        float contentHeight = presentationBlock + Skin.SECTION_GAP + backgroundBlock;
        float offset = scroller.offset(context.delta(), contentHeight, area.height);
        float y = area.y - offset;

        ui.clip(area.x, area.y, area.right(), area.bottom());
        presentation(context, new Rect(area.x, y, width, PRESENTATION_HEIGHT));
        y += PRESENTATION_HEIGHT;

        if (glass > 0.001f) {
            float visible = glass * (GLASS_HEIGHT + Skin.SECTION_GAP);
            Rect revealClip = new Rect(area.x, y, width, visible);
            ui.clip(revealClip.x, revealClip.y, revealClip.right(), revealClip.bottom());
            ui.pushAlpha(glass);
            glassControls(context, new Rect(area.x, y + Skin.SECTION_GAP, width, GLASS_HEIGHT), glass > 0.94f);
            ui.popAlpha();
            ui.unclip();
            y += visible;
        }

        y += Skin.SECTION_GAP;
        Rect backgroundPanel = new Rect(area.x, y, width, backgroundBlock);
        FathomComponents.panel(ui, backgroundPanel);
        Rect backgroundInner = backgroundPanel.inset(Skin.CARD_PADDING, Skin.S3);
        float contentX = backgroundInner.x;
        y = backgroundInner.y;
        Controls.label(ui, "Background", contentX, y);
        y += ui.lineHeight(Type.LABEL) + Skin.S1;
        ui.text(Text.fit(ui, "Shared with the launcher. A choice is preloaded, then applied everywhere.",
                        Type.SMALL, pickerWidth),
                contentX, y + ui.ascent(Type.SMALL), Type.SMALL, Skin.TEXT_FAINT);
        y += ui.lineHeight(Type.SMALL) + Skin.S3;

        MenuScenes.Scene selected = MenuScenes.selected();
        for (int index = 0; index < scenes.length; index++) {
            int row = index / lastColumns;
            int column = index % lastColumns;
            Rect tile = new Rect(contentX + column * (cellWidth + TILE_GAP),
                    y + row * (tileHeight + TILE_GAP), cellWidth, tileHeight);
            if (tile.bottom() < area.y || tile.y > area.bottom()) {
                continue;
            }
            drawScene(context, scenes[index], index, selected, tile, imageHeight);
        }

        float footerY = y + gridHeight + Skin.S4;
        if (selected.credit != null) {
            ui.text(Text.fit(ui, selected.credit, Type.SMALL, pickerWidth), contentX,
                    footerY + ui.ascent(Type.SMALL), Type.SMALL, Skin.TEXT_DISABLED);
        }
        ui.unclip();
        scroller.rail(ui, new Rect(area.x, area.y, width + Skin.S4, area.height),
                context.pointer(), context.now());
    }

    private void presentation(HubContext context, Rect area) {
        Renderer ui = context.ui();
        FathomComponents.panel(ui, area);
        Rect inner = area.inset(Skin.CARD_PADDING, Skin.S3);
        Controls.label(ui, "Presentation", inner.x, inner.y);
        float controlWidth = Math.min(370f, inner.width);
        Rect control = new Rect(inner.x, inner.bottom() - Skin.H_MD, controlWidth, Skin.H_MD);
        int selected = net.fathom.optimizations.fabric.Runtime.gameplayGlass() ? 1 : 0;
        int chosen = context.controls().segmented(ui, "appearance.presentation", control,
                PRESENTATION, selected);
        if (chosen != selected) {
            context.hud().prefs().setRshiftBackgroundChoice(chosen);
            context.markDirty();
        }
        String note = selected == 0
                ? "Uses the selected scene behind every Fathom surface."
                : context.client().world == null
                        ? "Available once a world is visible."
                        : "The live world stays visible beneath the interface.";
        ui.text(Text.fit(ui, note, Type.SMALL, Math.max(0f, inner.width - controlWidth - Skin.S4)),
                control.right() + Skin.S4, Controls.baseline(ui, control, Type.SMALL),
                Type.SMALL, Skin.TEXT_FAINT);
    }

    private void glassControls(HubContext context, Rect area, boolean enabled) {
        Renderer ui = context.ui();
        FathomComponents.panel(ui, area);
        Rect inner = area.inset(Skin.CARD_PADDING, Skin.S3);
        Controls.label(ui, "Gameplay glass", inner.x, inner.y);
        float rowTop = inner.y + ui.lineHeight(Type.LABEL) + Skin.S2;
        float rowHeight = (inner.bottom() - rowTop) / 2f;
        glassRow(context, new Rect(inner.x, rowTop, inner.width, rowHeight), "Blur",
                "A restrained downsampled GPU blur.", "appearance.glass.blur",
                ClientPrefs.RSHIFT_BLUR_CHOICES, context.hud().prefs().rshiftBlurChoice(), enabled);
        glassRow(context, new Rect(inner.x, rowTop + rowHeight, inner.width, rowHeight), "Dim",
                "Neutral tint for legibility over bright play.", "appearance.glass.dim",
                ClientPrefs.RSHIFT_DIM_CHOICES, context.hud().prefs().rshiftDimChoice(), enabled);
    }

    private void glassRow(HubContext context, Rect area, String title, String detail, String id,
            String[] options, int selected, boolean enabled) {
        Renderer ui = context.ui();
        float pickerWidth = Math.min(190f, Math.max(142f, area.width * 0.28f));
        Rect picker = area.vcentreRight(0f, pickerWidth, Skin.H_MD);
        float top = Controls.stackTop(ui, area, Type.ROW_TITLE, Type.SMALL);
        ui.text(title, area.x, Controls.stackTitle(ui, top, Type.ROW_TITLE), Type.ROW_TITLE, Skin.TEXT);
        ui.text(Text.fit(ui, detail, Type.SMALL, picker.x - area.x - Skin.S4), area.x,
                Controls.stackDetail(ui, top, Type.ROW_TITLE, Type.SMALL), Type.SMALL, Skin.TEXT_FAINT);
        int chosen = context.controls().dropdown(ui, id, picker, options, selected, enabled);
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
