package net.fathom.optimizations.fabric.hud;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.ResourcePackLoader;
import net.minecraft.util.Formatting;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;
import net.fathom.optimizations.core.module.Setting;

/**
 * Which resource packs are on.
 *
 * <h2>Nothing is loaded here</h2>
 *
 * <p>The pack list, the names, the descriptions and the icons all already exist:
 * Minecraft builds them when the pack screen is opened or a pack is applied, and
 * keeps them. This reads that list and, for the icon, calls the same
 * {@code bindIcon} the pack screen calls, which uploads the image once and binds
 * the same texture every time after. No file is opened, no metadata is parsed
 * and no texture is re-uploaded by drawing this widget — which matters, because
 * it is drawn every frame and reading a zip from a renderer would stutter the
 * game.
 *
 * <h2>The server's pack counts</h2>
 *
 * <p>A server pack is not in the player's own list — vanilla keeps it apart — so
 * it is read separately and named first when it is on. A player looking at this
 * widget wants to know which textures they are seeing, and on a server that is
 * usually the server's answer.
 *
 * <h2>Read when it changes</h2>
 *
 * <p>A pack list changes when somebody applies a pack, which is roughly never
 * while the game is running. The names are stripped of their colour codes and
 * their {@code .zip} once, when the list or a setting actually differs from the
 * last frame, rather than on every frame of every second.
 */
final class PackDisplayWidget implements HudWidget {

    private static final int ICON = 16;

    private final List<String> names = new ArrayList<String>();
    private final List<String> descriptions = new ArrayList<String>();
    private final List<ResourcePackLoader.Entry> entries = new ArrayList<ResourcePackLoader.Entry>();
    private int extra;
    private long collectedFrame = -1L;

    /** What the strings were built from: the packs shown, and the settings. */
    private final List<Object> builtPacks = new ArrayList<Object>();
    private int builtSettings = -1;

    @Override
    public String id() {
        return "pack-display";
    }

    @Override
    public String placeholder() {
        return "Default";
    }

    private static FathomModule module() {
        return Widgets.module("pack-display");
    }

    private void collect(MinecraftClient client) {
        long frame = Widgets.frameNumber();
        if (frame == collectedFrame) {
            return;
        }
        collectedFrame = frame;
        if (client == null) {
            names.clear();
            descriptions.clear();
            entries.clear();
            builtPacks.clear();
            builtSettings = -1;
            return;
        }
        ResourcePackLoader loader = client.getResourcePackLoader();
        if (!listChanged(loader)) {
            return;
        }
        names.clear();
        descriptions.clear();
        entries.clear();
        extra = 0;
        boolean serverPack = loader != null && loader.getServerContainer() != null;
        List<ResourcePackLoader.Entry> selected = selected(loader);
        String mode = Widgets.optionChoice("pack-display", "packs", "Top pack");
        boolean all = "Every pack".equals(mode);

        if (serverPack) {
            add(null, clean(loader.getServerContainer().getName()), "Sent by the server");
            if (!all) {
                extra = selected.size();
                return;
            }
        }
        if (selected.isEmpty()) {
            if (!serverPack) {
                // No pack at all is not nothing to say: it is "you are seeing
                // Minecraft's own textures", which is what somebody checking
                // this widget wants to know.
                add(null, "Default", "");
            }
            return;
        }
        // The last one in the list wins in Minecraft's stack, so it is the one
        // somebody would name if you asked what pack they were using.
        for (int step = 0; step < selected.size(); step++) {
            int index = selected.size() - 1 - step;
            ResourcePackLoader.Entry entry = selected.get(index);
            add(entry, clean(entry.getName()), clean(entry.getDescription()));
            if (!all) {
                extra = selected.size() - 1;
                break;
            }
        }
    }

    private static List<ResourcePackLoader.Entry> selected(ResourcePackLoader loader) {
        return loader == null
                ? java.util.Collections.<ResourcePackLoader.Entry>emptyList()
                : loader.getSelectedResourcePacks();
    }

    /**
     * Whether the packs or the settings differ from what was last read.
     *
     * <p>Compared by identity: the loader hands out the same entry objects until
     * a pack is applied, so this is a handful of pointer comparisons on a frame
     * where nothing has happened.
     */
    private boolean listChanged(ResourcePackLoader loader) {
        List<ResourcePackLoader.Entry> current = selected(loader);
        Object server = loader == null ? null : loader.getServerContainer();
        int size = current.size() + (server == null ? 0 : 1);
        if (builtSettings != Setting.revision() || builtPacks.size() != size) {
            record(current, server);
            return true;
        }
        int index = 0;
        if (server != null && builtPacks.get(index++) != server) {
            record(current, server);
            return true;
        }
        for (int at = 0; at < current.size(); at++) {
            if (builtPacks.get(index + at) != current.get(at)) {
                record(current, server);
                return true;
            }
        }
        return false;
    }

    private void record(List<ResourcePackLoader.Entry> current, Object server) {
        builtPacks.clear();
        if (server != null) {
            builtPacks.add(server);
        }
        builtPacks.addAll(current);
        builtSettings = Setting.revision();
    }

    private void add(ResourcePackLoader.Entry entry, String name, String description) {
        entries.add(entry);
        names.add(name);
        descriptions.add(description == null ? "" : description);
    }

    /** Pack names and descriptions can carry colour codes and newlines. */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = Formatting.strip(raw).replace('\n', ' ').replace('\r', ' ').trim();
        return text.endsWith(".zip") ? text.substring(0, text.length() - 4) : text;
    }

    /** The first line for an entry, with the chosen format and the count. */
    private String title(int index) {
        String name = names.get(index);
        if ("Pack: Name".equals(Widgets.optionChoice("pack-display", "format", "Name only"))) {
            name = "Pack: " + name;
        }
        if (index == 0 && extra > 0
                && "Top pack and count".equals(Widgets.optionChoice("pack-display", "packs", "Top pack"))) {
            name = name + " (+" + extra + ")";
        }
        return name;
    }

    private static boolean icons() {
        return Widgets.option("pack-display", "icon", true);
    }

    private boolean describing(int index) {
        return Widgets.option("pack-display", "description", false) && !descriptions.get(index).isEmpty();
    }

    private int rowHeight(HudFont font, int index) {
        int lines = describing(index) ? 2 : 1;
        int text = (int) Math.ceil(lines * font.lineHeight() + (lines - 1));
        return icons() && entries.get(index) != null ? Math.max(ICON, text) : text;
    }

    private int rowWidth(HudFont font, int index) {
        float text = font.width(title(index));
        if (describing(index)) {
            text = Math.max(text, font.width(descriptions.get(index)));
        }
        int width = (int) Math.ceil(text);
        if (icons() && entries.get(index) != null) {
            width += ICON + 3;
        }
        return width;
    }

    @Override
    public int width(MinecraftClient client, HudFont font) {
        collect(client);
        int pad = HudStyle.padding(module());
        int widest = 0;
        for (int index = 0; index < names.size(); index++) {
            widest = Math.max(widest, rowWidth(font, index));
        }
        return widest + pad * 2;
    }

    @Override
    public int height(MinecraftClient client, HudFont font) {
        collect(client);
        int pad = HudStyle.padding(module());
        int height = 0;
        for (int index = 0; index < names.size(); index++) {
            height += rowHeight(font, index) + 1;
        }
        return Math.max(0, height - 1) + pad * 2;
    }

    @Override
    public boolean visible(MinecraftClient client) {
        collect(client);
        return !names.isEmpty();
    }

    @Override
    public void draw(MinecraftClient client, HudFont font, int x, int y, int alpha) {
        collect(client);
        if (names.isEmpty()) {
            return;
        }
        FathomModule module = module();
        int pad = HudStyle.padding(module);
        TextWidget.backdrop(module, x, y, width(client, font), height(client, font), alpha);

        boolean shadow = HudStyle.textShadow(module);
        int colour = (alpha << 24) | (HudStyle.textColour(module) & 0x00FFFFFF);
        int faint = (alpha << 24) | (dim(HudStyle.textColour(module)) & 0x00FFFFFF);
        int left = x + pad;
        int top = y + pad;

        for (int index = 0; index < names.size(); index++) {
            int rowHeight = rowHeight(font, index);
            int textLeft = left;
            ResourcePackLoader.Entry entry = entries.get(index);
            if (icons() && entry != null) {
                icon(client, entry, left, top + (rowHeight - ICON) / 2f, alpha);
                textLeft += ICON + 3;
            }
            float textY = top;
            if (!describing(index)) {
                textY = top + (rowHeight - font.lineHeight()) / 2f;
            }
            font.draw(title(index), textLeft, Math.round(textY), colour, shadow);
            if (describing(index)) {
                font.draw(descriptions.get(index), textLeft,
                        Math.round(textY + font.lineHeight() + 1), faint, shadow);
            }
            top += rowHeight + 1;
        }
    }

    /**
     * The pack's own icon, uploaded once by Minecraft and bound here.
     *
     * <p>Wrapped, because a pack whose icon cannot be turned into a texture
     * must cost a missing icon and not a crash inside the HUD.
     */
    private static void icon(MinecraftClient client, ResourcePackLoader.Entry entry,
            float x, float y, int alpha) {
        if (client.getTextureManager() == null) {
            return;
        }
        try {
            HudGl.flushPending();
            entry.bindIcon(client.getTextureManager());
            HudGl.boundTexture(x, y, ICON, ICON, alpha);
        } catch (RuntimeException unusable) {
            // Nothing to draw, and nothing worth saying every frame about it.
        }
    }

    private static int dim(int rgb) {
        int r = ((rgb >> 16) & 0xFF) * 3 / 4;
        int g = ((rgb >> 8) & 0xFF) * 3 / 4;
        int b = (rgb & 0xFF) * 3 / 4;
        return (r << 16) | (g << 8) | b;
    }
}
