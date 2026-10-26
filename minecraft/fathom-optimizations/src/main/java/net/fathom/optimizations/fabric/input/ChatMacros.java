package net.fathom.optimizations.fabric.input;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

import net.fathom.optimizations.core.chat.GameChat;
import net.fathom.optimizations.core.input.HubKeyPolicy;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Chat's preset messages on keys, Auto GG, and the counts Bedwars Stats shows.
 *
 * <p>A preset is sent exactly as if typed: a line starting with / is a
 * command. Every send waits at least a second after the last one, from a key
 * or from Auto GG, so a held or bouncing key cannot flood chat and get you
 * muted or kicked.
 */
public final class ChatMacros {

    /** How many preset slots the Chat module offers. */
    public static final int SLOTS = 4;

    private static final long MIN_GAP_MILLIS = 1000L;

    private static final boolean[] wasDown = {true, true, true, true};
    private static long lastSent;
    private static String pendingGg;
    private static long ggAt;
    private static long lastGg;

    private static ClientWorld statsWorld;
    private static int kills;
    private static int finals;
    private static int deaths;
    private static int beds;

    private ChatMacros() {}

    private static FathomModule module(String id) {
        if (Runtime.features() == null) {
            return null;
        }
        FathomModule module = Runtime.features().find(id);
        return module != null && module.enabled() && module.switchable() ? module : null;
    }

    /** Called once per frame. */
    public static void frame(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        if (client.world != statsWorld) {
            // A new world is a new game on every minigame server.
            statsWorld = client.world;
            kills = 0;
            finals = 0;
            deaths = 0;
            beds = 0;
        }
        long now = System.currentTimeMillis();
        if (pendingGg != null && now >= ggAt) {
            send(client, pendingGg, now);
            pendingGg = null;
        }
        FathomModule chat = module("chat");
        boolean playing = ScreenKinds.of(client.currentScreen) == HubKeyPolicy.Screen.NONE
                && Display.isCreated() && Display.isActive();
        for (int slot = 1; slot <= SLOTS; slot++) {
            Keybind key = chat == null ? null : key(chat, "macro" + slot);
            boolean down = playing && key != null && held(key);
            if (down && !wasDown[slot - 1]) {
                String message = chat.text("macro" + slot + ".text", "").trim();
                if (!message.isEmpty()) {
                    send(client, message, now);
                }
            }
            wasDown[slot - 1] = down;
        }
    }

    /** An incoming chat line, unformatted. */
    public static void received(MinecraftClient client, String line) {
        if (client == null || client.player == null || line == null) {
            return;
        }
        String you = client.getSession() == null ? null : client.getSession().getUsername();
        switch (GameChat.read(line, you)) {
            case KILL:
                kills++;
                break;
            case FINAL_KILL:
                kills++;
                finals++;
                break;
            case DEATH:
            case FINAL_DEATH:
                deaths++;
                break;
            case BED:
                beds++;
                break;
            default:
                break;
        }
        FathomModule gg = module("auto-gg");
        long now = System.currentTimeMillis();
        if (gg != null && pendingGg == null && now - lastGg > 10_000L && GameChat.gameOver(line)) {
            String message = gg.text("message", "gg").trim();
            if (!message.isEmpty()) {
                if ("All chat (/ac)".equals(gg.choice("channel", "Chat"))) {
                    message = "/ac " + message;
                }
                pendingGg = message;
                ggAt = now + (long) (gg.number("delay", 1) * 1000.0);
                lastGg = now;
            }
        }
    }

    public static int kills() {
        return kills;
    }

    public static int finals() {
        return finals;
    }

    public static int deaths() {
        return deaths;
    }

    public static int beds() {
        return beds;
    }

    /** The last line sent from a key or Auto GG, for the self-test. */
    public static String lastSentMessage;

    private static void send(MinecraftClient client, String message, long now) {
        if (now - lastSent < MIN_GAP_MILLIS || client.player == null) {
            return;
        }
        lastSent = now;
        lastSentMessage = message;
        client.player.sendChatMessage(message.length() > 100 ? message.substring(0, 100) : message);
    }

    private static Keybind key(FathomModule module, String id) {
        for (Keybind each : module.keybinds()) {
            if (id.equals(each.id())) {
                return each;
            }
        }
        return null;
    }

    private static boolean held(Keybind key) {
        if (!key.isBound()) {
            return false;
        }
        if (key.isMouseButton()) {
            return MouseButtons.isDown(key.mouseButton());
        }
        return Keyboard.isCreated() && key.key() > 0 && key.key() < Keyboard.KEYBOARD_SIZE
                && Keyboard.isKeyDown(key.key());
    }
}
