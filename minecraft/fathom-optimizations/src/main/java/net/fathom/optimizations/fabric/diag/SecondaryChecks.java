package net.fathom.optimizations.fabric.diag;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

import net.fathom.optimizations.fabric.mixin.ChatHudAccessor;
import net.fathom.optimizations.fabric.mixin.GameRendererLightmapAccessor;

/**
 * What the self-test's secondary-module phase does to the game and reads back
 * from it: a server's sidebar, chat with click and hover targets, the lightmap,
 * a screenshot's crosshair, a resource pack with a wider font.
 *
 * <p>Development only; reached from {@link ModuleSelfTest} and nothing else.
 */
final class SecondaryChecks {

    private SecondaryChecks() {}

    // -----------------------------------------------------------------------
    // The sidebar
    // -----------------------------------------------------------------------

    static final String OBJECTIVE = "fathomqa";

    /**
     * A sidebar laid out the way large 1.8.9 networks lay theirs out: a bold
     * coloured title, unique invisible entry names (colour codes and resets),
     * each line's text carried in its team's prefix and suffix -- which 1.8
     * caps at sixteen characters each, so long lines are split across the two,
     * with the colour repeated -- blank spacer lines, more entries than fit,
     * and an entry vanilla never shows (a name starting with #).
     */
    static ScoreboardObjective buildSidebar(MinecraftClient client) {
        Scoreboard board = client.world.getScoreboard();
        ScoreboardObjective existing = board.getNullableObjective(OBJECTIVE);
        if (existing != null) {
            board.removeObjective(existing);
        }
        ScoreboardObjective objective = board.method_4884(OBJECTIVE, ScoreboardCriterion.DUMMY);
        objective.setDisplayName("§e§lBED WARS");
        String[][] lines = {
            // {entry name, prefix, suffix}
            {"§0§r", "§710/10/26 ", "§8m42A"},
            {"§1§r", "", ""},
            {"§2§r", "§fRound ends in ", "§a4:32"},
            {"§3§r", "", ""},
            {"§4§r", "§c§lR §fRed: ", "§a✔"},
            {"§5§r", "§9§lB §fBlue: ", "§a✔ §7YOU"},
            {"§6§r", "§a§lG §fGreen: ", "§c✘"},
            {"§7§r", "§e§lY §fYellow: ", "§a3"},
            {"§8§r", "", ""},
            {"§9§r", "§fKills: §a", "12"},
            {"§a§r", "§fFinal Kills: §a", "4"},
            {"§b§r", "§fBeds Broken: §a", "1"},
            {"§c§r", "", ""},
            {"§d§r", "§eplay.example", "§e.net"},
            {"§e§r", "§7(unseen 1)", ""},
            {"§f§r", "§7(unseen 2)", ""},
        };
        // Scores count down the sidebar, as servers number them: the first
        // line has the highest. The last two have the lowest and are cut by
        // the fifteen-line limit.
        for (int index = 0; index < lines.length; index++) {
            String name = lines[index][0];
            Team team = board.getTeam("fqa" + index);
            if (team == null) {
                team = board.addTeam("fqa" + index);
            }
            team.setPrefix(lines[index][1]);
            team.setSuffix(lines[index][2]);
            board.addPlayerToTeam(name, team.getName());
            board.getPlayerScore(name, objective).setScore(lines.length - index);
        }
        board.getPlayerScore("#hidden", objective).setScore(99);
        board.setObjectiveSlot(1, objective);
        return objective;
    }

    /** What vanilla would show for it: the title, then the lines, highest score first. */
    static List<String> expectedSidebar(ScoreboardObjective objective) {
        Scoreboard board = objective.getScoreboard();
        List<ScoreboardPlayerScore> shown = new ArrayList<ScoreboardPlayerScore>();
        Collection<ScoreboardPlayerScore> all = board.getAllPlayerScores(objective);
        for (ScoreboardPlayerScore score : all) {
            if (score.getPlayerName() != null && !score.getPlayerName().startsWith("#")) {
                shown.add(score);
            }
        }
        // Vanilla keeps the last fifteen of the ascending list.
        if (shown.size() > 15) {
            shown = new ArrayList<ScoreboardPlayerScore>(shown.subList(shown.size() - 15, shown.size()));
        }
        List<String> out = new ArrayList<String>();
        out.add(objective.getDisplayName());
        for (int index = shown.size() - 1; index >= 0; index--) {
            String name = shown.get(index).getPlayerName();
            out.add(Team.decorateName(board.getPlayerTeam(name), name));
        }
        return out;
    }

    static void removeSidebar(MinecraftClient client) {
        Scoreboard board = client.world.getScoreboard();
        ScoreboardObjective objective = board.getNullableObjective(OBJECTIVE);
        if (objective != null) {
            board.removeObjective(objective);
        }
        for (int index = 0; index < 32; index++) {
            Team team = board.getTeam("fqa" + index);
            if (team != null) {
                board.removeTeam(team);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Chat
    // -----------------------------------------------------------------------

    /** One check's outcome: a name, whether it held, and what was seen. */
    static final class Outcome {
        final String name;
        final boolean ok;
        final String detail;

        Outcome(String name, boolean ok, String detail) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
        }
    }

    private static Text clickable(String words, String command, String hover) {
        Text message = new LiteralText(words);
        message.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        message.getStyle().setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText(hover)));
        return message;
    }

    /** The click command anywhere in a stored message, or null. */
    private static String command(Text text) {
        for (Text part : text) {
            ClickEvent click = part.getStyle().getClickEvent();
            if (click != null) {
                return click.getValue();
            }
        }
        return null;
    }

    private static String hover(Text text) {
        for (Text part : text) {
            HoverEvent hover = part.getStyle().getHoverEvent();
            if (hover != null) {
                return hover.getValue().asUnformattedString();
            }
        }
        return null;
    }

    /**
     * Everything chat has to keep, in one frame (so no server message lands in
     * the middle): with timestamps and folding on, a repeated message folds and
     * keeps its click and hover targets; one that reads the same but runs a
     * different command does not fold; each wrapped line can still be clicked.
     * With both off, a hundred-and-twenty messages leave exactly vanilla's
     * hundred, newest first, none skipped; and a message is stored untouched.
     */
    static List<Outcome> chat(MinecraftClient client, net.fathom.optimizations.core.module.FathomModule chat) {
        List<Outcome> out = new ArrayList<Outcome>();
        net.minecraft.client.gui.hud.ChatHud hud = client.inGameHud.getChatHud();
        ChatHudAccessor lists = (ChatHudAccessor) hud;
        set(chat, "timestamps", true);
        set(chat, "stack", true);
        hud.clear();

        hud.addMessage(clickable("Click to join the queue", "/qa one", "Joins queue one"));
        hud.addMessage(clickable("Click to join the queue", "/qa one", "Joins queue one"));
        List<ChatHudLine> messages = lists.fathom$messages();
        String folded = messages.isEmpty() ? "" : messages.get(0).getText().asUnformattedString();
        out.add(new Outcome("chat: a repeat folds into one line with a count",
                messages.size() == 1 && folded.contains("(x2)"), messages.size() + " stored: " + folded));
        out.add(new Outcome("chat: the folded line keeps its click and hover",
                !messages.isEmpty() && "/qa one".equals(command(messages.get(0).getText()))
                        && "Joins queue one".equals(hover(messages.get(0).getText())),
                messages.isEmpty() ? "" : command(messages.get(0).getText()) + " / " + hover(messages.get(0).getText())));
        out.add(new Outcome("chat: the timestamp is in front of it",
                folded.replaceAll("§.", "").matches("^\\[?\\d{1,2}:\\d{2}.*"), folded));

        hud.addMessage(clickable("Click to join the queue", "/qa two", "Joins queue two"));
        messages = lists.fathom$messages();
        out.add(new Outcome("chat: the same words with a different command are not folded",
                messages.size() == 2 && "/qa two".equals(command(messages.get(0).getText()))
                        && "/qa one".equals(command(messages.get(1).getText())),
                messages.size() + " stored"));

        boolean everyLine = true;
        for (ChatHudLine line : lists.fathom$visibleMessages()) {
            everyLine &= command(line.getText()) != null;
        }
        out.add(new Outcome("chat: every wrapped line can still be clicked", everyLine,
                lists.fathom$visibleMessages().size() + " lines"));

        set(chat, "timestamps", false);
        set(chat, "stack", false);
        hud.clear();
        for (int index = 0; index < 120; index++) {
            hud.addMessage(new LiteralText("qa line " + index));
        }
        messages = lists.fathom$messages();
        boolean ordered = messages.size() == 100;
        for (int index = 0; ordered && index < 100; index++) {
            ordered = ("qa line " + (119 - index)).equals(messages.get(index).getText().asUnformattedString());
        }
        out.add(new Outcome("chat: 120 messages keep vanilla's newest 100, in order, none skipped", ordered,
                messages.size() + " stored"));

        Text plain = clickable("Untouched", "/qa three", "three");
        hud.addMessage(plain);
        out.add(new Outcome("chat: with nothing to add, a message is stored exactly as the server sent it",
                lists.fathom$messages().get(0).getText() == plain, ""));
        hud.clear();
        return out;
    }

    private static void set(net.fathom.optimizations.core.module.FathomModule module, String id, boolean value) {
        if (module.setting(id) instanceof net.fathom.optimizations.core.module.Setting.Toggle) {
            ((net.fathom.optimizations.core.module.Setting.Toggle) module.setting(id)).set(value);
        }
    }

    // -----------------------------------------------------------------------
    // The lightmap
    // -----------------------------------------------------------------------

    /** The brightest channel of the darkest lightmap cell: no block light, no sky light. */
    static int darkestLight(MinecraftClient client) {
        int[] pixels = ((GameRendererLightmapAccessor) client.gameRenderer).fathom$lightmapPixels();
        int argb = pixels[0];
        return Math.max((argb >> 16) & 0xFF, Math.max((argb >> 8) & 0xFF, argb & 0xFF));
    }

    // -----------------------------------------------------------------------
    // The crosshair, from a screenshot
    // -----------------------------------------------------------------------

    /**
     * Where the magenta crosshair's middle is, relative to the window's, in
     * screen pixels: {dx, dy, pixels found}. Null when the image cannot be read.
     */
    static float[] crosshairOffset(File png) throws IOException {
        BufferedImage image = ImageIO.read(png);
        if (image == null) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int reach = Math.min(160, Math.min(width, height) / 2);
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int found = 0;
        for (int y = height / 2 - reach; y < height / 2 + reach; y++) {
            for (int x = width / 2 - reach; x < width / 2 + reach; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r > 200 && g < 60 && b > 200) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    found++;
                }
            }
        }
        if (found == 0) {
            return new float[] {Float.NaN, Float.NaN, 0f};
        }
        // A pixel's middle is at its index plus a half; the box's middle is
        // the middle of its first and last pixels'.
        float centreX = (minX + maxX + 1) / 2f;
        float centreY = (minY + maxY + 1) / 2f;
        return new float[] {centreX - width / 2f, centreY - height / 2f, found};
    }

    // -----------------------------------------------------------------------
    // A resource pack with a wider font
    // -----------------------------------------------------------------------

    static final String PACK = "fathom-qa-wide-font";

    /**
     * A resource pack whose font has every glyph a full cell wide, written into
     * the game's resource pack folder. Every character is then wider than the
     * default font's, which any widget that cached a width across the reload
     * would get wrong.
     */
    static File writeWideFontPack(MinecraftClient client) throws IOException {
        File root = new File(client.getResourcePackLoader().getResourcePackDir(), PACK);
        File font = new File(root, "assets/minecraft/textures/font");
        if (!font.isDirectory() && !font.mkdirs()) {
            throw new IOException("could not make " + font);
        }
        Files.write(new File(root, "pack.mcmeta").toPath(),
                "{\"pack\":{\"pack_format\":1,\"description\":\"Fathom self-test: a wide font\"}}"
                        .getBytes(StandardCharsets.UTF_8));
        BufferedImage ascii = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                ascii.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        try (OutputStream stream = Files.newOutputStream(new File(font, "ascii.png").toPath())) {
            ImageIO.write(ascii, "png", stream);
        }
        return root;
    }

    /** Select or deselect the pack, as the resource pack screen does, and reload. */
    static boolean usePack(MinecraftClient client, boolean on) {
        net.minecraft.client.resource.ResourcePackLoader loader = client.getResourcePackLoader();
        loader.initResourcePacks();
        List<net.minecraft.client.resource.ResourcePackLoader.Entry> selected =
                new ArrayList<net.minecraft.client.resource.ResourcePackLoader.Entry>(loader.getSelectedResourcePacks());
        net.minecraft.client.resource.ResourcePackLoader.Entry pack = null;
        for (net.minecraft.client.resource.ResourcePackLoader.Entry entry : loader.getAvailableResourcePacks()) {
            if (PACK.equals(entry.getName())) {
                pack = entry;
            }
        }
        for (net.minecraft.client.resource.ResourcePackLoader.Entry entry : loader.getSelectedResourcePacks()) {
            if (PACK.equals(entry.getName())) {
                pack = entry;
            }
        }
        if (pack == null) {
            return false;
        }
        selected.remove(pack);
        if (on) {
            selected.add(0, pack);
        }
        loader.setSelectedResourcePacks(selected);
        client.reloadResources();
        return true;
    }

    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        if (file.exists() && !file.delete()) {
            System.out.println("[Fathom] QA modules: could not delete " + file);
        }
    }
}
