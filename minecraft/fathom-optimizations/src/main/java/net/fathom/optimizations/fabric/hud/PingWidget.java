package net.fathom.optimizations.fabric.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;

/**
 * Latency, as the tab list reports it.
 *
 * <h2>Nothing is measured, and nothing is asked</h2>
 *
 * <p>The number is the one the server sends for the player's own entry in the
 * player list — the same number the tab list draws bars for. Fathom sends no
 * packet of its own to find it out: a client that pinged the server to decorate
 * a widget would be making traffic the server did not ask for, to learn
 * something it already said.
 *
 * <h2>Read on an interval, not per frame</h2>
 *
 * <p>Servers refresh latency every few seconds, so reading it three hundred
 * times a second would produce the same number three hundred times. The player
 * list lookup and the string are done once per the configured interval, and the
 * frames in between draw what was worked out then.
 *
 * <h2>Honest about not knowing</h2>
 *
 * <p>Singleplayer has a network handler and a latency of zero, which is true and
 * useless: the module declares itself multiplayer-only and the widget draws
 * nothing there. On a server, a player list entry that does not exist yet, or a
 * latency the server has not reported, shows as {@code --} rather than as a
 * confident "0 ms".
 */
final class PingWidget implements HudWidget {

    /** Vanilla's own bar geometry: five bars, each two pixels wide with a gap. */
    private static final int BAR_WIDTH = 2;
    private static final int BAR_GAP = 1;
    private static final int BARS = 5;
    private static final int BAR_HEIGHT = 8;

    private static final int UNKNOWN = -1;

    private long nextReadNanos;
    private int settings = -1;
    /** The session the reading was taken in: another server's ping is never shown. */
    private int readSession = -1;
    private int latency = UNKNOWN;
    private String text = "";
    /** The reading measured once while it is unchanged, not on every size asked for. */
    private final MeasuredWidths widths = new MeasuredWidths(2);

    @Override
    public String id() {
        return "ping";
    }

    @Override
    public String placeholder() {
        return "000 ms";
    }

    private static final String[] PREVIEW = {"42 ms"};

    /** A plausible reading rather than the width template. */
    @Override
    public String[] preview() {
        return PREVIEW;
    }

    private static FathomModule module() {
        return Widgets.module("ping");
    }

    /**
     * The current reading, refreshed at most once per the chosen interval — and
     * at once when a setting changes, so editing the format is not a five-second
     * wait to see what it looks like.
     */
    private void read(MinecraftClient client) {
        long now = System.nanoTime();
        int revision = net.fathom.optimizations.core.module.Setting.revision();
        if (now < nextReadNanos && !text.isEmpty() && revision == settings && readSession == Widgets.session()) {
            return;
        }
        settings = revision;
        readSession = Widgets.session();
        nextReadNanos = now + refreshNanos(Widgets.optionChoice("ping", "refresh", "1 second"));
        latency = latencyOf(client);
        text = format(latency, Widgets.optionChoice("ping", "format", "120 ms"));
    }

    private static long refreshNanos(String choice) {
        if ("500 ms".equals(choice)) {
            return 500_000_000L;
        }
        if ("2 seconds".equals(choice)) {
            return 2_000_000_000L;
        }
        if ("5 seconds".equals(choice)) {
            return 5_000_000_000L;
        }
        return 1_000_000_000L;
    }

    private static String format(int latency, String format) {
        String value = latency == UNKNOWN ? "--" : String.valueOf(latency);
        if ("120".equals(format)) {
            return value;
        }
        if ("Ping: 120 ms".equals(format)) {
            return "Ping: " + value + " ms";
        }
        if ("Ping: 120".equals(format)) {
            return "Ping: " + value;
        }
        return value + " ms";
    }

    /**
     * The player's own row in the player list.
     *
     * <p>By UUID first, then by name. Behind a proxy, or on a server in offline
     * mode, the UUID the server lists the player under is not the one the
     * client's session has, and a lookup by UUID alone found no row: the widget
     * showed "--" on exactly the servers where the tab list showed full bars.
     */
    static PlayerListEntry ownEntry(MinecraftClient client) {
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            return null;
        }
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        if (entry == null && client.player.getGameProfile() != null && client.player.getGameProfile().getName() != null) {
            entry = client.getNetworkHandler().getPlayerListEntry(client.player.getGameProfile().getName());
        }
        return entry;
    }

    static int latencyOf(MinecraftClient client) {
        PlayerListEntry entry = ownEntry(client);
        if (entry == null) {
            return UNKNOWN;
        }
        int reported = entry.getLatency();
        // Zero is what a server sends before it has measured anything, and what
        // an integrated server always sends.
        return reported <= 0 ? UNKNOWN : reported;
    }

    private static boolean bars() {
        return !"Number".equals(Widgets.optionChoice("ping", "display", "Number"));
    }

    private static boolean numbers() {
        return !"Bars".equals(Widgets.optionChoice("ping", "display", "Number"));
    }

    private static int barsWidth() {
        return BARS * BAR_WIDTH + (BARS - 1) * BAR_GAP;
    }

    @Override
    public int width(MinecraftClient client, HudFont font) {
        read(client);
        int pad = HudStyle.padding(module());
        int width = 0;
        if (bars()) {
            width += barsWidth();
        }
        if (numbers()) {
            width += (bars() ? 3 : 0) + (int) Math.ceil(widths.of(font, text));
        }
        return width + pad * 2;
    }

    @Override
    public int height(MinecraftClient client, HudFont font) {
        int pad = HudStyle.padding(module());
        int height = numbers() ? (int) Math.ceil(font.lineHeight()) : 0;
        if (bars()) {
            height = Math.max(height, BAR_HEIGHT);
        }
        return height + pad * 2;
    }

    @Override
    public boolean visible(MinecraftClient client) {
        if (client == null || client.isInSingleplayer() || client.getNetworkHandler() == null) {
            return false;
        }
        read(client);
        return true;
    }

    @Override
    public void draw(MinecraftClient client, HudFont font, int x, int y, int alpha) {
        read(client);
        FathomModule module = module();
        int pad = HudStyle.padding(module);
        int boxWidth = width(client, font);
        int boxHeight = height(client, font);
        TextWidget.backdrop(module, x, y, boxWidth, boxHeight, alpha);

        int left = x + pad;
        int inner = boxHeight - pad * 2;
        int colour = colour(module);

        if (bars()) {
            drawBars(left, y + pad + (inner - BAR_HEIGHT) / 2, alpha, colour);
            left += barsWidth() + (numbers() ? 3 : 0);
        }
        if (numbers()) {
            float textY = y + pad + (inner - font.lineHeight()) / 2f;
            font.draw(text, left, Math.round(textY), (alpha << 24) | (colour & 0x00FFFFFF),
                    HudStyle.textShadow(module));
        }
    }

    /**
     * Five bars, the shortest first, lit as far as the latency allows.
     *
     * <p>Unlit bars are drawn dark rather than left out, so the widget is the
     * same width and the same shape whatever the connection is doing — a readout
     * that changed size as it got worse would be the second thing to notice
     * during a lag spike.
     */
    private void drawBars(int x, int y, int alpha, int colour) {
        int lit = lit();
        QuadBatch.begin();
        try {
            for (int index = 0; index < BARS; index++) {
                int height = 2 + index * ((BAR_HEIGHT - 2) / (BARS - 1));
                int left = x + index * (BAR_WIDTH + BAR_GAP);
                int top = y + BAR_HEIGHT - height;
                boolean on = index < lit;
                int bar = on ? colour : 0x333333;
                QuadBatch.rect(left, top, left + BAR_WIDTH, y + BAR_HEIGHT,
                        ((on ? alpha : alpha * 2 / 3) << 24) | (bar & 0x00FFFFFF));
            }
        } finally {
            QuadBatch.end();
        }
    }

    /** How many bars the current latency earns, on vanilla's own thresholds. */
    private int lit() {
        if (latency == UNKNOWN) {
            return 0;
        }
        if (latency < 150) {
            return 5;
        }
        if (latency < 300) {
            return 4;
        }
        if (latency < 600) {
            return 3;
        }
        return latency < 1000 ? 2 : 1;
    }

    /**
     * Green, amber or red.
     *
     * <p>The thresholds are settings, defaulting to the ones the vanilla tab
     * list already changes its bars at, so the colour agrees with the icon a
     * player is used to rather than introducing a second opinion.
     */
    private int colour(FathomModule module) {
        int text = HudStyle.textColour(module);
        if (latency == UNKNOWN || !Widgets.option("ping", "colour-by-quality", true)) {
            return text;
        }
        int good = (int) Math.round(Widgets.optionNumber("ping", "good", 100));
        int bad = (int) Math.round(Widgets.optionNumber("ping", "bad", 300));
        if (latency < good) {
            return net.fathom.optimizations.generated.GraphiteTokens.COLOR_STATUS_OK & 0xFFFFFF;
        }
        // The shared status tones, so a HUD warning and a launcher warning
        // are the same amber.
        return (latency <= Math.max(good, bad)
                ? net.fathom.optimizations.generated.GraphiteTokens.COLOR_STATUS_WARN : net.fathom.optimizations.generated.GraphiteTokens.COLOR_STATUS_ERROR) & 0xFFFFFF;
    }
}
