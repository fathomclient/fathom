package net.fathom.optimizations.fabric.hud;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.scoreboard.Team;

import net.fathom.optimizations.core.hud.Sidebar;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;
import net.fathom.optimizations.core.module.Setting;

/**
 * The scoreboard sidebar, drawn by Fathom so it can be moved, scaled and styled.
 *
 * <h2>How it replaces vanilla's</h2>
 *
 * <p>Vanilla decides which objective the sidebar shows — the team-coloured slot
 * first, then the plain sidebar — and calls one method to draw it. While the
 * module is on, that call hands its objective to {@link #takeOver} and draws
 * nothing; this widget then draws the same rows in the HUD pass, where the
 * player put it. With the module off, vanilla's own sidebar is untouched, so
 * resource packs and the replay cache behave exactly as before.
 *
 * <p>Vanilla only calls the method when there is an objective, so the captured
 * one is cleared at the start of every HUD frame ({@link #frame}): a sidebar a
 * server has removed disappears on the next frame rather than lingering.
 *
 * <h2>What it reads</h2>
 *
 * <p>The scores the client already holds, through the same calls vanilla's
 * sidebar makes. The rows are rebuilt only when the scoreboard, the objective,
 * the font or a setting changes, so an unchanged sidebar costs a comparison.
 */
public final class ScoreboardWidget implements HudWidget {

    static final String ID = "scoreboard";

    private static ScoreboardObjective captured;

    private final List<String> names = new ArrayList<String>(Sidebar.MAX_ROWS);
    private final List<String> scores = new ArrayList<String>(Sidebar.MAX_ROWS);
    private final List<ScoreboardPlayerScore> rows = new ArrayList<ScoreboardPlayerScore>(Sidebar.MAX_ROWS);
    private String title = "";
    private int content;
    private ScoreboardObjective builtFor;
    private int builtRevision = Integer.MIN_VALUE;
    private int builtSettings = Integer.MIN_VALUE;
    private HudFont builtFont;

    /** The objective handed over this frame, or null. For the self-test. */
    /** The title and the lines as this widget will draw them, highest score first. For the self-test. */
    public List<String> qaLines(HudFont font) {
        build(font);
        List<String> out = new ArrayList<String>(names.size() + 1);
        out.add(title);
        out.addAll(names);
        return out;
    }

    public static ScoreboardObjective captured() {
        return captured;
    }

    /** A new HUD frame: forget last frame's objective until vanilla offers one. */
    public static void frame() {
        captured = null;
    }

    /**
     * Vanilla is about to draw the sidebar for {@code objective}.
     *
     * @return true when the module is on and vanilla's drawing should be skipped
     */
    public static boolean takeOver(ScoreboardObjective objective) {
        FathomModule module = Widgets.module(ID);
        if (module == null || !module.enabled() || !module.switchable() || objective == null) {
            return false;
        }
        captured = objective;
        return true;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String placeholder() {
        return "Scoreboard";
    }

    private static final String[] PREVIEW = {"Scoreboard", "Kills: 3", "Coins: 120", "Time: 4:12"};

    /** A sidebar's shape -- a title and a few scores -- for placing it before any server sends one. */
    @Override
    public String[] preview() {
        return PREVIEW;
    }

    @Override
    public boolean visible(MinecraftClient client) {
        if (captured == null || Widgets.option(ID, "hide", false)) {
            return false;
        }
        return !entries(captured).isEmpty();
    }

    /** Vanilla's selection: hidden names left out, the fifteen highest kept. */
    private ScoreboardObjective rowsFor;
    private int rowsRevision = Integer.MIN_VALUE;

    /**
     * The scores the sidebar shows, collected once per change to the
     * scoreboard rather than on every question: vanilla's lookup builds and
     * sorts a fresh list, and the HUD asks whether this is visible, how large
     * it is and what to draw in every frame.
     */
    private List<ScoreboardPlayerScore> entries(ScoreboardObjective objective) {
        int revision = net.fathom.optimizations.fabric.perf.VanillaHudReplay.scoreboardRevision;
        if (objective == rowsFor && revision == rowsRevision) {
            return rows;
        }
        rowsFor = objective;
        rowsRevision = revision;
        rows.clear();
        Scoreboard scoreboard = objective.getScoreboard();
        if (scoreboard == null) {
            return rows;
        }
        Collection<ScoreboardPlayerScore> all = scoreboard.getAllPlayerScores(objective);
        for (ScoreboardPlayerScore score : all) {
            if (score != null && !Sidebar.hidden(score.getPlayerName())) {
                rows.add(score);
            }
        }
        // Ascending by score; vanilla keeps the last fifteen.
        while (rows.size() > Sidebar.MAX_ROWS) {
            rows.remove(0);
        }
        return rows;
    }

    private void build(HudFont font) {
        ScoreboardObjective objective = captured;
        int revision = net.fathom.optimizations.fabric.perf.VanillaHudReplay.scoreboardRevision;
        if (objective == builtFor && revision == builtRevision && Setting.revision() == builtSettings
                && font == builtFont) {
            return;
        }
        builtFor = objective;
        builtRevision = revision;
        builtSettings = Setting.revision();
        builtFont = font;
        names.clear();
        scores.clear();
        content = 0;
        if (objective == null) {
            title = "";
            return;
        }
        boolean numbers = Widgets.option(ID, "numbers", true);
        Scoreboard scoreboard = objective.getScoreboard();
        title = String.valueOf(objective.getDisplayName());
        if (Widgets.option(ID, "title", true)) {
            content = (int) Math.ceil(font.width(title));
        }
        List<ScoreboardPlayerScore> shown = entries(objective);
        // Drawn top to bottom, highest score first: the reverse of the list.
        for (int index = shown.size() - 1; index >= 0; index--) {
            ScoreboardPlayerScore score = shown.get(index);
            String name = Team.decorateName(scoreboard.getPlayerTeam(score.getPlayerName()), score.getPlayerName());
            String value = "§c" + score.getScore();
            names.add(name);
            scores.add(value);
            // Measured the way vanilla measures it, separator included, so a
            // line is exactly as wide as the sidebar it replaced.
            String measured = numbers ? name + ": " + value : name;
            content = Math.max(content, (int) Math.ceil(font.width(measured)));
        }
    }

    private static int rowHeight(HudFont font) {
        return (int) Math.ceil(font.lineHeight());
    }

    @Override
    public int width(MinecraftClient client, HudFont font) {
        build(font);
        return Sidebar.width(content, HudStyle.padding(Widgets.module(ID)));
    }

    @Override
    public int height(MinecraftClient client, HudFont font) {
        build(font);
        return Sidebar.height(names.size(), rowHeight(font), Widgets.option(ID, "title", true),
                HudStyle.padding(Widgets.module(ID)));
    }

    @Override
    public void draw(MinecraftClient client, HudFont font, int x, int y, int alpha) {
        build(font);
        if (names.isEmpty()) {
            return;
        }
        FathomModule module = Widgets.module(ID);
        int pad = HudStyle.padding(module);
        int width = width(client, font);
        int height = height(client, font);
        boolean showTitle = Widgets.option(ID, "title", true);
        boolean numbers = Widgets.option(ID, "numbers", true);
        int bands = (int) Math.round(Widgets.optionNumber(ID, "rows", 100));
        int line = rowHeight(font);
        int left = x + pad;
        int right = x + width - pad;
        int top = y + pad;

        // Plates first in one batch, then the text: rows never overlap.
        QuadBatch.begin();
        try {
            TextWidget.backdrop(module, x, y, width, height, alpha);
            int cursor = top;
            if (showTitle) {
                int titleBand = Sidebar.bandAlpha(Sidebar.TITLE_ALPHA, bands, alpha);
                int rule = Sidebar.bandAlpha(Sidebar.ROW_ALPHA, bands, alpha);
                if (titleBand > 0) {
                    QuadBatch.rect(left, cursor, right, cursor + line, titleBand << 24);
                }
                if (rule > 0) {
                    QuadBatch.rect(left, cursor + line, right, cursor + line + 1, rule << 24);
                }
                cursor += line + 1;
            }
            int band = Sidebar.bandAlpha(Sidebar.ROW_ALPHA, bands, alpha);
            if (band > 0) {
                QuadBatch.rect(left, cursor, right, cursor + names.size() * line, band << 24);
            }
        } finally {
            QuadBatch.end();
        }

        int colour = (Math.max(0, Math.min(255, alpha)) << 24) | (HudStyle.textColour(module) & 0xFFFFFF);
        boolean shadow = HudStyle.textShadow(module);
        float textLeft = left + Sidebar.INSET;
        float cursor = top;
        if (showTitle) {
            int room = right - left - Sidebar.INSET * 2;
            font.draw(title, textLeft + Math.round((room - font.width(title)) / 2f), cursor, colour, shadow);
            cursor += line + 1;
        }
        for (int index = 0; index < names.size(); index++) {
            font.draw(names.get(index), textLeft, cursor, colour, shadow);
            if (numbers) {
                String value = scores.get(index);
                font.draw(value, right - font.width(value), cursor, colour, shadow);
            }
            cursor += line;
        }
    }
}
