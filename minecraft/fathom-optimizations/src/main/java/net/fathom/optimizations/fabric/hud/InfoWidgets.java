package net.fathom.optimizations.fabric.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/**
 * The small read-outs: saturation, the server you are on, your speed, what
 * you are carrying and what you are looking at. Each reads the client's own
 * state, which the game already shows somewhere or already knows; none asks
 * the server for anything.
 */
public final class InfoWidgets {

    private InfoWidgets() {}

    /** Hunger saturation, which vanilla tracks but never shows. */
    public static final class Saturation extends TextWidget {
        private final List<String> out = new ArrayList<String>(1);

        public Saturation() {
            super("saturation", "Saturation 5.0");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            out.clear();
            ClientPlayerEntity player = client.player;
            if (player == null) {
                return out;
            }
            float saturation = player.getHungerManager().getSaturationLevel();
            String value = String.format(Locale.ROOT, "%.1f", saturation);
            String format = Widgets.optionChoice("saturation", "format", "Saturation 5.0");
            if ("5.0".equals(format)) {
                out.add(value);
            } else if ("Food 20 / Sat 5.0".equals(format)) {
                out.add("Food " + player.getHungerManager().getFoodLevel() + " / Sat " + value);
            } else {
                out.add("Saturation " + value);
            }
            return out;
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.player != null;
        }
    }

    /** The address of the server you are connected to. */
    public static final class ServerAddress extends TextWidget {
        private final List<String> out = new ArrayList<String>(1);

        public ServerAddress() {
            super("server-address", "mc.example.net");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            out.clear();
            ServerInfo server = client.getCurrentServerEntry();
            String address;
            if (server != null && server.address != null && !client.isInSingleplayer()) {
                address = server.address;
                if (Widgets.option("server-address", "hide.port", true) && address.endsWith(":25565")) {
                    address = address.substring(0, address.length() - ":25565".length());
                }
            } else if (Widgets.option("server-address", "singleplayer", false)) {
                address = "Singleplayer";
            } else {
                return out;
            }
            out.add(Widgets.option("server-address", "label", false) ? "IP: " + address : address);
            return out;
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.world != null;
        }
    }

    /**
     * How fast you are moving, in blocks a second: the distance between the
     * last two ticks, which is what the game itself moved you by.
     */
    public static final class Speed extends TextWidget {
        private final List<String> out = new ArrayList<String>(1);
        private double shown;

        public Speed() {
            super("speed", "4.32 m/s");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            out.clear();
            ClientPlayerEntity player = client.player;
            if (player == null) {
                return out;
            }
            double dx = player.x - player.prevX;
            double dz = player.z - player.prevZ;
            double dy = player.y - player.prevY;
            boolean vertical = Widgets.option("speed", "vertical", false);
            double perTick = Math.sqrt(dx * dx + dz * dz + (vertical ? dy * dy : 0.0));
            double speed = perTick * 20.0;
            // A little smoothing: tick-to-tick movement jitters by a hundredth or two.
            shown += (speed - shown) * 0.25;
            if (Math.abs(shown) < 0.005) {
                shown = 0.0;
            }
            int decimals = (int) Widgets.optionNumber("speed", "decimals", 2);
            String unit = Widgets.optionChoice("speed", "unit", "m/s");
            double value = "km/h".equals(unit) ? shown * 3.6 : shown;
            out.add(String.format(Locale.ROOT, "%." + Math.max(0, Math.min(3, decimals)) + "f %s", value, unit));
            return out;
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.player != null;
        }
    }

    /** How many of the items that matter you are carrying. */
    public static final class ItemCounter extends TextWidget {
        private final List<String> out = new ArrayList<String>(6);

        public ItemCounter() {
            super("item-counter", "Blocks 64");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            out.clear();
            ClientPlayerEntity player = client.player;
            if (player == null || player.inventory == null) {
                return out;
            }
            int blocks = 0;
            int arrows = 0;
            int pearls = 0;
            int apples = 0;
            int fireballs = 0;
            int snowballs = 0;
            int iron = 0;
            int gold = 0;
            int diamonds = 0;
            int emeralds = 0;
            for (ItemStack stack : player.inventory.main) {
                if (stack == null || stack.getItem() == null) {
                    continue;
                }
                Item item = stack.getItem();
                if (item instanceof BlockItem) {
                    blocks += stack.count;
                } else if (item == Items.ARROW) {
                    arrows += stack.count;
                } else if (item == Items.ENDER_PEARL) {
                    pearls += stack.count;
                } else if (item == Items.GOLDEN_APPLE) {
                    apples += stack.count;
                } else if (item == Items.FIRE_CHARGE) {
                    fireballs += stack.count;
                } else if (item == Items.SNOWBALL || item == Items.EGG) {
                    snowballs += stack.count;
                } else if (item == Items.IRON_INGOT) {
                    iron += stack.count;
                } else if (item == Items.GOLD_INGOT) {
                    gold += stack.count;
                } else if (item == Items.DIAMOND) {
                    diamonds += stack.count;
                } else if (item == Items.EMERALD) {
                    emeralds += stack.count;
                }
            }
            boolean hideZero = Widgets.option("item-counter", "hide.zero", true);
            add("blocks", "Blocks", blocks, true, hideZero);
            add("arrows", "Arrows", arrows, true, hideZero);
            add("pearls", "Pearls", pearls, true, hideZero);
            add("apples", "Golden apples", apples, true, hideZero);
            add("fireballs", "Fireballs", fireballs, false, hideZero);
            add("throwables", "Snowballs and eggs", snowballs, false, hideZero);
            add("iron", "Iron", iron, false, hideZero);
            add("gold", "Gold", gold, false, hideZero);
            add("diamonds", "Diamonds", diamonds, false, hideZero);
            add("emeralds", "Emeralds", emeralds, false, hideZero);
            return out;
        }

        private void add(String id, String label, int count, boolean fallback, boolean hideZero) {
            if (!Widgets.option("item-counter", id, fallback) || (hideZero && count == 0)) {
                return;
            }
            out.add(label + " " + count);
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.player != null;
        }
    }

    /**
     * How far you are below the height limit, for maps that cap how high you
     * can build. The limit is the one you set: servers do not send it.
     */
    public static final class HeightLimit extends TextWidget {
        private final List<String> out = new ArrayList<String>(1);

        public HeightLimit() {
            super("height-limit", "Height limit in 12");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            out.clear();
            ClientPlayerEntity player = client.player;
            if (player == null) {
                return out;
            }
            int limit = (int) Widgets.optionNumber("height-limit", "limit", 100);
            int warn = (int) Widgets.optionNumber("height-limit", "warn", 10);
            int left = limit - (int) Math.floor(player.y);
            if (left > warn && !Widgets.option("height-limit", "always", false)) {
                return out;
            }
            if (left <= 0) {
                out.add("\u00a7cAt the height limit");
            } else {
                String colour = left <= Math.max(1, warn / 3) ? "\u00a7c" : left <= warn ? "\u00a7e" : "";
                out.add(colour + "Height limit in " + left);
            }
            return out;
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.player != null;
        }
    }

    /** Your kills, final kills, beds and deaths this game, read from the server's own messages. */
    public static final class BedwarsStats extends TextWidget {
        private final List<String> out = new ArrayList<String>(4);

        public BedwarsStats() {
            super("bedwars-stats", "Kills 0");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            out.clear();
            if (client.player == null) {
                return out;
            }
            out.add("Kills " + net.fathom.optimizations.fabric.input.ChatMacros.kills());
            out.add("Finals " + net.fathom.optimizations.fabric.input.ChatMacros.finals());
            out.add("Beds " + net.fathom.optimizations.fabric.input.ChatMacros.beds());
            if (Widgets.option("bedwars-stats", "deaths", false)) {
                out.add("Deaths " + net.fathom.optimizations.fabric.input.ChatMacros.deaths());
            }
            return out;
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.player != null;
        }
    }

    /** What you are looking at: the block's name, or the entity's and its health. */
    public static final class BlockInfo extends TextWidget {
        private final List<String> out = new ArrayList<String>(2);

        public BlockInfo() {
            super("block-info", "Oak Planks");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            out.clear();
            if (client.world == null || client.result == null) {
                return out;
            }
            if (client.result.type == BlockHitResult.Type.BLOCK) {
                BlockPos pos = client.result.getBlockPos();
                Block block = client.world.getBlockState(pos).getBlock();
                if (block.getMaterial() == Material.AIR) {
                    return out;
                }
                out.add(blockName(client, block, pos));
                if (Widgets.option("block-info", "position", false)) {
                    out.add(pos.getX() + " / " + pos.getY() + " / " + pos.getZ());
                }
            } else if (client.result.type == BlockHitResult.Type.ENTITY && client.targetedEntity != null
                    && Widgets.option("block-info", "entities", true)) {
                Entity entity = client.targetedEntity;
                out.add(entity.getName().asFormattedString());
                if (entity instanceof LivingEntity && Widgets.option("block-info", "health", true)) {
                    LivingEntity living = (LivingEntity) entity;
                    out.add(String.format(Locale.ROOT, "%.1f / %.1f ❤", living.getHealth(), living.getMaxHealth()));
                }
            }
            return out;
        }

        private static String blockName(MinecraftClient client, Block block, BlockPos pos) {
            try {
                Item item = block.getPickItem(client.world, pos);
                if (item != null) {
                    String name = new ItemStack(item, 1, block.getMeta(client.world, pos)).getCustomName();
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            } catch (RuntimeException unnamed) {
                // Some blocks have no item, or one that cannot name this state.
            }
            return block.getTranslatedName();
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.world != null;
        }
    }
}
