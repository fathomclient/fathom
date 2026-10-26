package net.fathom.optimizations.fabric.hub;

import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.Graphite;
import net.fathom.optimizations.fabric.ui.Skin;
import net.fathom.optimizations.generated.GraphiteTokens;

/**
 * Settings: the Hub's general pages under one rail entry.
 *
 * <p>The RShift rail lists five things -- Modules, HUD Editor, Appearance,
 * Performance, Settings -- and the pages that used to have rail entries of
 * their own (Quick, Profiles, Diagnostics, Launcher) are tabs here. Each is
 * still the same {@link Section}, drawn exactly as before under its own
 * heading; this only chooses which one and passes everything through, so no
 * page lost a capability by moving.
 */
final class SettingsSection implements Section {

    private final Section[] pages;
    private final String[] labels;
    private final String[] tabIds;
    private final String[] tabOnIds;
    private int current;

    SettingsSection(Section... pages) {
        this.pages = pages;
        labels = new String[pages.length];
        tabIds = new String[pages.length];
        tabOnIds = new String[pages.length];
        for (int index = 0; index < pages.length; index++) {
            labels[index] = "Quick".equals(pages[index].title()) ? "General" : pages[index].title();
            tabIds[index] = "settings.tab." + index;
            tabOnIds[index] = "settings.tab." + index + "#on";
        }
    }

    /** Show the page titled {@code title} (or labelled it), if there is one. @return whether there was */
    boolean show(String title) {
        for (int index = 0; index < pages.length; index++) {
            if (pages[index].title().equalsIgnoreCase(title) || labels[index].equalsIgnoreCase(title)) {
                current = index;
                return true;
            }
        }
        return false;
    }

    /** The page showing, for the self-tests. */
    Section page() {
        return pages[current];
    }

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public Icon icon() {
        return Icon.SETTINGS;
    }

    @Override
    public boolean searchable() {
        return pages[current].searchable();
    }

    @Override
    public String subtitle() {
        return pages[current].subtitle();
    }

    @Override
    public boolean ownsHeading() {
        return true;
    }

    @Override
    public void render(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        float height = Graphite.CONTROL_COMPACT;
        Type type = Graphite.floor(ui, Graphite.UI);
        float x = area.x;
        for (int index = 0; index < pages.length; index++) {
            float width = ui.measure(labels[index], type) + Graphite.S3 * 2f;
            Rect cell = new Rect(x, area.y, width, height);
            Controls.State state = controls.state(tabIds[index], cell, true);
            float on = controls.towards(tabOnIds[index], index == current,
                    net.fathom.optimizations.core.ui.anim.Timing.NAVIGATION);
            Graphite.segment(ui, cell, state.hover * (1f - on), on);
            int ink = Colour.mix(Graphite.TEXT_SECONDARY, Graphite.TEXT, Math.max(state.hover, on));
            ui.text(labels[index], cell.x + Graphite.S3, Graphite.baseline(ui, cell, type), type, ink);
            controls.focusRing(ui, state, cell, Graphite.R_SEGMENT);
            if (state.clicked && index != current) {
                controls.closePopover();
                context.keybindCapture().cancel();
                current = index;
                pages[current].opened(context);
            }
            x = cell.right() + Graphite.S1;
        }

        Rect below = area.belowTop(height + Skin.S4);
        Section page = pages[current];
        if (page.ownsHeading()) {
            page.render(context, below);
            return;
        }
        // The page's one-line explanation, as the other sections' headings
        // carry it, without repeating the tab's name above it.
        String subtitle = page.subtitle();
        if (subtitle != null && !subtitle.isEmpty()) {
            Type small = Graphite.floor(ui, Graphite.SMALL);
            ui.text(Text.fit(ui, subtitle, small, below.width), below.x, below.y + ui.ascent(small),
                    small, Graphite.TEXT_SECONDARY);
            below = below.belowTop(ui.lineHeight(small) + Skin.S3);
        }
        page.render(context, below);
    }

    @Override
    public String pageId() {
        return pages[current].title();
    }

    @Override
    public float scrollPosition() {
        return pages[current].scrollPosition();
    }

    @Override
    public void restore(HubContext context, String pageId, float scroll) {
        if (pageId != null && !pageId.isEmpty() && show(pageId)) {
            pages[current].opened(context);
            pages[current].restore(context, "", scroll);
        }
    }

    @Override
    public void scroll(int notches) {
        pages[current].scroll(notches);
    }

    @Override
    public void scroll(int notches, float x, float y) {
        pages[current].scroll(notches, x, y);
    }

    @Override
    public boolean key(HubContext context, char character, int keyCode) {
        return pages[current].key(context, character, keyCode);
    }

    @Override
    public boolean mouse(HubContext context, int button) {
        return pages[current].mouse(context, button);
    }

    @Override
    public boolean typing() {
        return pages[current].typing();
    }

    @Override
    public void opened(HubContext context) {
        pages[current].opened(context);
    }

    @Override
    public void qaReplay(HubContext context) {
        pages[current].qaReplay(context);
    }
}
