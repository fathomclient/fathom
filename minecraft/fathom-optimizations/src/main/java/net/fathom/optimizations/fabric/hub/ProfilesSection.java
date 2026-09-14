package net.fathom.optimizations.fabric.hub;

import java.util.List;

import org.lwjgl.input.Keyboard;

import net.fathom.optimizations.core.profile.FathomProfile;
import net.fathom.optimizations.core.profile.ProfileException;
import net.fathom.optimizations.core.profile.ProfileLocation;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.profile.ProfileRuntime;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.Skin;

/**
 * The Profiles page: every Fathom Profile, and what you can do with one.
 *
 * <h2>What changed, and why</h2>
 *
 * <p>This page used to be about the launcher's installations — which Minecraft
 * version and loader would start next time — and it said "Launch next time"
 * because none of that can change in a running game. Those are Game Instances
 * now, and they live in the launcher. A Fathom Profile is a loadout: modules,
 * settings, HUD, keys. All of it changes live, so this page switches for real.
 *
 * <h2>Selecting is not switching</h2>
 *
 * <p>Clicking a row shows that profile in the card beside the list. Switching is
 * the card's own button. A list where a stray click replaced somebody's whole HUD
 * mid-game would be a list people stopped clicking.
 *
 * <h2>Nothing here writes a file</h2>
 *
 * <p>Every operation is a call on {@link ProfileRuntime}, which owns the store
 * and the switch transaction. That is what keeps the header picker, the HUD
 * editor and this page from each having a slightly different idea of what
 * "delete the live profile" does.
 */
final class ProfilesSection implements Section {

    private static final float ROW = 52f;
    private static final float LIST_MAX = 360f;
    private static final float CARD_HEIGHT = 236f;
    private static final float WIDE = 640f;
    private static final long CONFIRM_MILLIS = 3000L;
    private static final int MAX_NAME = 64;

    private final Scroller scroller = new Scroller();

    private String selectedId;
    private String renamingId;
    private String renameText = "";
    private String confirmingDelete;
    private long confirmAt;
    private String problem = "";

    @Override
    public String title() {
        return "Profiles";
    }

    @Override
    public Icon icon() {
        return Icon.USER;
    }

    @Override
    public boolean searchable() {
        return false;
    }

    @Override
    public String subtitle() {
        return "Changes apply instantly.";
    }

    @Override
    public void opened(HubContext context) {
        scroller.reset();
        renamingId = null;
        confirmingDelete = null;
        problem = "";
        ProfileRuntime runtime = context.hud().profiles();
        if (runtime != null) {
            runtime.profiles();
            selectedId = runtime.liveId();
        }
    }

    @Override
    public void render(HubContext context, Rect area) {
        Renderer ui = context.ui();
        ProfileRuntime runtime = context.hud().profiles();
        if (runtime == null) {
            ui.text("Profiles are not available.", area.x, area.y + ui.ascent(Type.BODY), Type.BODY, Skin.WARN);
            return;
        }
        List<FathomProfile> profiles = runtime.cachedProfiles();
        FathomProfile selected = find(profiles, selectedId);
        if (selected == null) {
            selected = find(profiles, runtime.liveId());
            selectedId = selected == null ? null : selected.id();
        }

        actions(context, runtime, area.top(Skin.H_MD));

        Rect body = area.belowTop(Skin.H_MD + Skin.S5);
        if (body.width >= WIDE) {
            float listWidth = Math.min(LIST_MAX, body.width * 0.42f);
            list(context, runtime, profiles, new Rect(body.x, body.y, listWidth, body.height));
            if (selected != null) {
                float cardX = body.x + listWidth + Skin.S5;
                card(context, runtime, selected, profiles.size(),
                        new Rect(cardX, body.y, body.right() - cardX, Math.min(body.height, CARD_HEIGHT)));
            }
        } else {
            // Narrow: the card on top, because it is what the list is for.
            float cardHeight = Math.min(CARD_HEIGHT, body.height * 0.55f);
            if (selected != null) {
                card(context, runtime, selected, profiles.size(), new Rect(body.x, body.y, body.width, cardHeight));
            }
            float listTop = body.y + cardHeight + Skin.S5;
            list(context, runtime, profiles,
                    new Rect(body.x, listTop, body.width, Math.max(0f, body.bottom() - listTop)));
        }
    }

    // -----------------------------------------------------------------------
    // Parts
    // -----------------------------------------------------------------------

    private void actions(HubContext context, ProfileRuntime runtime, Rect bar) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        float x = bar.x;

        if (controls.primary(ui, "profiles.new", new Rect(x, bar.y, 132f, bar.height), "New profile", true)) {
            try {
                FathomProfile created = runtime.create("New profile");
                selectedId = created.id();
                // Straight into naming it: "New profile 3" is a name nobody wanted.
                renamingId = created.id();
                renameText = created.name();
                problem = "";
            } catch (ProfileException failure) {
                problem = failure.getMessage();
            }
        }
        x += 132f + Skin.S2;

        if (controls.secondary(ui, "profiles.import", new Rect(x, bar.y, 96f, bar.height), "Import", true)) {
            try {
                List<FathomProfile> imported = runtime.importAll();
                if (!imported.isEmpty()) {
                    selectedId = imported.get(imported.size() - 1).id();
                }
                problem = "";
            } catch (ProfileException failure) {
                problem = failure.getMessage();
            }
        }
        x += 96f + Skin.S4;

        String shown = problem.isEmpty() ? runtime.notice() : problem;
        if (!shown.isEmpty()) {
            ui.text(Text.fit(ui, shown, Type.SMALL, bar.right() - x), x, Controls.baseline(ui, bar, Type.SMALL),
                    Type.SMALL, problem.isEmpty() ? Skin.GOOD : Skin.WARN);
        }
    }

    private void list(HubContext context, ProfileRuntime runtime, List<FathomProfile> profiles, Rect list) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        float offset = scroller.offset(context.delta(), profiles.size() * ROW, list.height);

        ui.clip(list.x, list.y, list.right(), list.bottom());
        for (int index = 0; index < profiles.size(); index++) {
            FathomProfile profile = profiles.get(index);
            Rect row = new Rect(list.x, list.y + index * ROW - offset, list.width - Skin.S3, ROW).inset(0f, 2f);
            if (row.bottom() < list.y || row.y > list.bottom()) {
                continue;
            }
            boolean live = profile.id().equals(runtime.liveId());
            boolean chosen = profile.id().equals(selectedId);
            String id = "profiles.row." + profile.id();
            Controls.State state = controls.state(id, row, true);
            controls.rowBackground(ui, id, row, state.hovered, chosen);

            float badge = live ? Controls.badgeWidth(ui, "Live") + Skin.S3 : 0f;
            float room = row.width - Skin.S3 * 2f - badge;
            float top = row.centreY() - (ui.lineHeight(Type.ROW_TITLE) + ui.lineHeight(Type.CAPTION)) / 2f;
            ui.text(Text.fit(ui, profile.name(), Type.ROW_TITLE, room), row.x + Skin.S3,
                    top + ui.ascent(Type.ROW_TITLE), Type.ROW_TITLE,
                    chosen || live ? Skin.TEXT : Skin.TEXT_MUTED);
            ui.text(Text.fit(ui, describe(profile), Type.CAPTION, room), row.x + Skin.S3,
                    top + ui.lineHeight(Type.ROW_TITLE) + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.TEXT_FAINT);
            if (live) {
                controls.badge(ui, "profiles.live." + profile.id(), "Live",
                        row.vcentreRight(Skin.S3, Controls.badgeWidth(ui, "Live"), 20f), Skin.GOOD);
            }

            if (state.clicked && !chosen) {
                selectedId = profile.id();
                renamingId = null;
                confirmingDelete = null;
                problem = "";
            }
        }
        ui.unclip();
        scroller.rail(ui, list, context.pointer(), context.now());
    }

    private void card(HubContext context, ProfileRuntime runtime, FathomProfile profile, int total, Rect card) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        ui.rounded(card.x, card.y, card.right(), card.bottom(), Skin.R_CARD, Skin.FILL);
        ui.outline(card.x, card.y, card.right(), card.bottom(), Skin.R_CARD, Skin.HAIRLINE, Skin.EDGE);
        Rect inner = card.inset(Skin.S4, Skin.S4);
        boolean live = profile.id().equals(runtime.liveId());
        boolean renaming = profile.id().equals(renamingId);

        float cursor = inner.y;
        Controls.label(ui, live ? "Live profile" : "Profile", inner.x, cursor);
        cursor += ui.lineHeight(Type.LABEL) + Skin.S2;

        Rect nameRow = new Rect(inner.x, cursor, inner.width, Math.max(ui.lineHeight(Type.SECTION), Skin.H_MD));
        if (renaming) {
            controls.textField(ui, "profiles.rename", nameRow, renameText, true);
        } else {
            ui.text(Text.fit(ui, profile.name(), Type.SECTION, inner.width), inner.x,
                    nameRow.centreY() - ui.lineHeight(Type.SECTION) / 2f + ui.ascent(Type.SECTION),
                    Type.SECTION, Skin.TEXT);
        }
        cursor = nameRow.bottom() + Skin.S2;
        ui.text(Text.fit(ui, describe(profile), Type.SMALL, inner.width), inner.x,
                cursor + ui.ascent(Type.SMALL), Type.SMALL, Skin.TEXT_FAINT);
        cursor += ui.lineHeight(Type.SMALL) + Skin.S5;

        float gap = Skin.S2;
        float cell = (inner.width - gap * 2f) / 3f;
        Rect switchButton = new Rect(inner.x, cursor, cell, Skin.H_MD);
        Rect renameButton = new Rect(inner.x + cell + gap, cursor, cell, Skin.H_MD);
        Rect duplicateButton = new Rect(inner.x + (cell + gap) * 2f, cursor, cell, Skin.H_MD);
        cursor += Skin.H_MD + gap;
        Rect exportButton = new Rect(inner.x, cursor, cell, Skin.H_MD);
        Rect deleteButton = new Rect(inner.x + cell + gap, cursor, cell, Skin.H_MD);
        cursor += Skin.H_MD + Skin.S4;

        if (live) {
            controls.primary(ui, "profiles.switch", switchButton, "Live", false);
        } else if (controls.primary(ui, "profiles.switch", switchButton, "Switch", true)) {
            try {
                runtime.switchTo(profile.id());
                renamingId = null;
                problem = "";
            } catch (ProfileException failure) {
                problem = failure.getMessage();
            }
        }

        if (controls.secondary(ui, "profiles.rename.button", renameButton, renaming ? "Save" : "Rename", true)) {
            if (renaming) {
                commitRename(runtime);
            } else {
                renamingId = profile.id();
                renameText = profile.name();
                confirmingDelete = null;
            }
        }

        if (controls.secondary(ui, "profiles.duplicate", duplicateButton, "Duplicate", true)) {
            try {
                selectedId = runtime.duplicate(profile.id()).id();
                renamingId = null;
                problem = "";
            } catch (ProfileException failure) {
                problem = failure.getMessage();
            }
        }

        if (controls.secondary(ui, "profiles.export", exportButton, "Export", true)) {
            try {
                runtime.export(profile.id());
                problem = "";
            } catch (ProfileException failure) {
                problem = failure.getMessage();
            }
        }

        // Two clicks, the second within three seconds. A confirmation dialog would
        // be a second screen for a decision that fits on a button.
        boolean confirming = profile.id().equals(confirmingDelete)
                && System.currentTimeMillis() - confirmAt < CONFIRM_MILLIS;
        if (controls.danger(ui, "profiles.delete", deleteButton, confirming ? "Confirm" : "Delete", total > 1)) {
            if (!confirming) {
                confirmingDelete = profile.id();
                confirmAt = System.currentTimeMillis();
            } else {
                try {
                    runtime.delete(profile.id());
                    selectedId = runtime.liveId();
                    confirmingDelete = null;
                    renamingId = null;
                    problem = "";
                } catch (ProfileException failure) {
                    problem = failure.getMessage();
                }
            }
        }

        if (cursor + ui.lineHeight(Type.CAPTION) <= inner.bottom()) {
            ui.text(Text.fit(ui, where(runtime.location()), Type.CAPTION, inner.width), inner.x,
                    cursor + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.TEXT_DISABLED);
        }
    }

    // -----------------------------------------------------------------------
    // Rename
    // -----------------------------------------------------------------------

    @Override
    public boolean key(HubContext context, char character, int keyCode) {
        if (renamingId == null) {
            return false;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            renamingId = null;
            return true;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            ProfileRuntime runtime = context.hud().profiles();
            if (runtime != null) {
                commitRename(runtime);
            }
            return true;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (!renameText.isEmpty()) {
                renameText = renameText.substring(0, renameText.length() - 1);
            }
            return true;
        }
        if (character >= 32 && character != 127 && renameText.length() < MAX_NAME) {
            renameText = renameText + character;
        }
        // Everything is swallowed while naming, so a name containing the hub key
        // does not close the hub halfway through typing it.
        return true;
    }

    private void commitRename(ProfileRuntime runtime) {
        String name = renameText.trim();
        if (name.isEmpty()) {
            problem = "A profile needs a name.";
            return;
        }
        try {
            runtime.rename(renamingId, name);
            renamingId = null;
            problem = "";
        } catch (ProfileException failure) {
            problem = failure.getMessage();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** One line about what a profile changes, in the fewest words that say it. */
    private static String describe(FathomProfile profile) {
        StringBuilder out = new StringBuilder();
        int on = profile.modulesOn();
        if (on > 0) {
            out.append(on).append(on == 1 ? " module on" : " modules on");
        }
        int placed = profile.hud().size();
        if (placed > 0) {
            if (out.length() > 0) {
                out.append(" · ");
            }
            out.append(placed).append(placed == 1 ? " widget placed" : " widgets placed");
        }
        return out.length() == 0 ? "Module defaults" : out.toString();
    }

    private static String where(ProfileLocation.Resolved location) {
        switch (location.source) {
            case LAUNCHER:
            case PLATFORM:
                return "Shared with the launcher.";
            case OVERRIDE:
                return "Saved in a test folder.";
            case STANDALONE:
            default:
                return "Saved in this game only.";
        }
    }

    private static FathomProfile find(List<FathomProfile> profiles, String id) {
        if (id == null) {
            return null;
        }
        for (FathomProfile profile : profiles) {
            if (profile.id().equals(id)) {
                return profile;
            }
        }
        return null;
    }

    @Override
    public void scroll(int notches) {
        scroller.wheel(notches);
    }
}
