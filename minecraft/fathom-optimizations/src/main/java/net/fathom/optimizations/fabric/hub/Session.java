package net.fathom.optimizations.fabric.hub;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

/**
 * Where the player currently is, in a few words.
 *
 * <p>The header shows it beside the profile name, because "which profile am I
 * on" and "where am I" are the two facts a client menu should answer before you
 * have read anything else on it.
 *
 * <h2>What is shown, and what is not</h2>
 *
 * <p>The server's <em>name</em> when the user gave it one in their server list,
 * because that is what they call it. The address when they did not. In
 * singleplayer, the world name.
 *
 * <p>Not shown: the port, the IP a hostname resolved to, the protocol version,
 * or anything else that identifies a machine rather than a place. This is a
 * label on a menu, and a menu is a thing people take screenshots of.
 */
final class Session {

    private Session() {}

    /** One short phrase for the header. Never null, never empty. */
    static String describe(MinecraftClient client) {
        if (client == null || client.world == null) {
            return "Main menu";
        }
        if (client.isInSingleplayer()) {
            String name = singleplayerName(client);
            return name == null ? "Singleplayer" : name;
        }
        ServerInfo entry = client.getCurrentServerEntry();
        if (entry == null) {
            return "Multiplayer";
        }
        if (entry.name != null && !entry.name.trim().isEmpty()) {
            return entry.name.trim();
        }
        return entry.address == null ? "Multiplayer" : hostOnly(entry.address);
    }

    /**
     * The host without its port.
     *
     * <p>A port is a fact about a connection, not about a place, and
     * `play.example.net:25565` in a header is four characters of noise on every
     * server that uses the default and a leak of nothing useful on the ones that
     * do not.
     */
    private static String hostOnly(String address) {
        int colon = address.lastIndexOf(':');
        return colon > 0 ? address.substring(0, colon) : address;
    }

    /**
     * The world's name in singleplayer.
     *
     * <p>Wrapped, because reaching for the integrated server touches several
     * things that can be null during a world change and one of them being null
     * should cost the header a word, not the frame.
     */
    private static String singleplayerName(MinecraftClient client) {
        try {
            net.minecraft.server.integrated.IntegratedServer server = client.getServer();
            if (server == null) {
                return null;
            }
            String name = server.getLevelName();
            return name == null || name.trim().isEmpty() ? null : name.trim();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
