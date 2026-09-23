package net.fathom.optimizations.core.module;

import net.fathom.optimizations.core.module.FathomModule.Icon;
import net.fathom.optimizations.core.opt.perf.EntityDistances;
import net.fathom.optimizations.core.opt.perf.EntityKind;
import net.fathom.optimizations.core.opt.perf.PerfPreset;
import net.fathom.optimizations.core.opt.perf.TileDistances;

/**
 * The settings of Fathom's own performance layer, as one module.
 *
 * <p>A module rather than a separate settings file so it is stored, profiled
 * and reset exactly like every other Fathom setting, and so the master switch
 * is the module's own enable. The Performance page draws these settings; the
 * hooks never read them directly — {@link Snapshot} is copied out once when a
 * setting changes and the hooks read plain fields.
 *
 * <h2>Presets and sliders</h2>
 *
 * <p>The sliders always show the distances in force. Choosing a preset writes
 * its distances into them; moving a slider switches the preset to Custom. So
 * there is never a slider that says one thing while another is being applied.
 */
public final class PerformanceLayer {

    public static final String ID = "performance";

    public static final String ENTITY_PRESET = "entity.preset";
    public static final String PLAYER_DISTANCE = "entity.players";
    public static final String MOB_DISTANCE = "entity.mobs";
    public static final String ITEM_DISTANCE = "entity.items";
    public static final String MISC_DISTANCE = "entity.misc";
    public static final String FAST_NAMETAGS = "nametags.fast";
    public static final String TILE_PRESET = "tiles.preset";
    public static final String TILE_DISTANCE = "tiles.distance";
    public static final String HUD_CACHE = "hud.cache";
    public static final String STATS = "stats";

    public static final PerfPreset DEFAULT_ENTITY_PRESET = PerfPreset.BALANCED;
    public static final PerfPreset DEFAULT_TILE_PRESET = PerfPreset.CONSERVATIVE;

    private PerformanceLayer() {}

    public static FathomModule declare() {
        String[] presets = PerfPreset.labels();
        return FathomModule.builder(ID)
                .name("Performance layer")
                .description("Draw less of what you cannot see.")
                .category(Category.PERFORMANCE)
                .icon(Icon.GAUGE)
                .implementation(Implementation.WORKING)
                .behaviour(Behaviour.PERFORMANCE_LAYER)
                .setting(new Setting.Choice(ENTITY_PRESET, "Entity optimization",
                        "How far away each kind of entity stops being drawn.",
                        DEFAULT_ENTITY_PRESET.label(), presets))
                .setting(new Setting.Whole(PLAYER_DISTANCE, "Player distance",
                        "Players, arrows and other projectiles. Never under 48 blocks.",
                        slider(EntityKind.PLAYER, DEFAULT_ENTITY_PRESET),
                        EntityDistances.floor(EntityKind.PLAYER), EntityDistances.MAXIMUM_BLOCKS, 8,
                        "blocks"))
                .setting(new Setting.Whole(MOB_DISTANCE, "Mob distance",
                        "Hostile and passive mobs.",
                        slider(EntityKind.HOSTILE, DEFAULT_ENTITY_PRESET),
                        EntityDistances.floor(EntityKind.PASSIVE), EntityDistances.MAXIMUM_BLOCKS, 8,
                        "blocks"))
                .setting(new Setting.Whole(ITEM_DISTANCE, "Item distance",
                        "Dropped items. Vanilla already stops at 16 blocks.",
                        slider(EntityKind.ITEM, DEFAULT_ENTITY_PRESET),
                        EntityDistances.floor(EntityKind.ITEM), 64, 4, "blocks"))
                .setting(new Setting.Whole(MISC_DISTANCE, "Misc entity distance",
                        "Armor stands, item frames, minecarts and the rest.",
                        slider(EntityKind.MISC, DEFAULT_ENTITY_PRESET),
                        EntityDistances.floor(EntityKind.MISC), EntityDistances.MAXIMUM_BLOCKS, 8,
                        "blocks"))
                .setting(new Setting.Toggle(FAST_NAMETAGS, "Fast nametags",
                        "Replays cached nametag text instead of redrawing every glyph.", true))
                .setting(new Setting.Choice(TILE_PRESET, "Tile entity optimization",
                        "How far away chests, signs, skulls and banners stop being drawn.",
                        DEFAULT_TILE_PRESET.label(), presets))
                .setting(new Setting.Whole(TILE_DISTANCE, "Tile entity distance",
                        "For Custom. Beacons, end portals and pistons are never limited.",
                        48, TileDistances.FLOOR_BLOCKS, TileDistances.VANILLA_BLOCKS, 4, "blocks"))
                .setting(new Setting.Toggle(HUD_CACHE, "HUD caching",
                        "Redraws slow-changing HUD widgets 20 times a second instead of every frame.",
                        true))
                .setting(new Setting.Toggle(STATS, "Show performance statistics",
                        "A small readout of what the performance layer skipped.", false))
                .onChange(LISTENER)
                .onByDefault()
                .build();
    }

    /** What a slider shows for a preset: the distance of the kind it is named for. */
    static int slider(EntityKind kind, PerfPreset preset) {
        int blocks = EntityDistances.blocks(kind, preset);
        return blocks < 0 ? EntityDistances.MAXIMUM_BLOCKS : blocks;
    }

    /** Keeps preset and sliders telling the same story. */
    public static final FathomModule.ChangeListener LISTENER = new FathomModule.ChangeListener() {
        @Override
        public void changed(FathomModule module, Setting setting) {
            if (setting == null) {
                return;
            }
            String id = setting.id();
            if (ENTITY_PRESET.equals(id)) {
                PerfPreset preset = PerfPreset.parse(((Setting.Choice) setting).get(), DEFAULT_ENTITY_PRESET);
                if (preset != PerfPreset.CUSTOM && preset != PerfPreset.OFF) {
                    whole(module, PLAYER_DISTANCE).set(slider(EntityKind.PLAYER, preset));
                    whole(module, MOB_DISTANCE).set(slider(EntityKind.HOSTILE, preset));
                    whole(module, ITEM_DISTANCE).set(slider(EntityKind.ITEM, preset));
                    whole(module, MISC_DISTANCE).set(slider(EntityKind.MISC, preset));
                }
            } else if (PLAYER_DISTANCE.equals(id) || MOB_DISTANCE.equals(id)
                    || ITEM_DISTANCE.equals(id) || MISC_DISTANCE.equals(id)) {
                ((Setting.Choice) module.setting(ENTITY_PRESET)).set(PerfPreset.CUSTOM.label());
            } else if (TILE_DISTANCE.equals(id)) {
                ((Setting.Choice) module.setting(TILE_PRESET)).set(PerfPreset.CUSTOM.label());
            }
        }
    };

    private static Setting.Whole whole(FathomModule module, String id) {
        return (Setting.Whole) module.setting(id);
    }

    /**
     * Everything the hooks need, copied out of the settings.
     *
     * <p>Rebuilt only when a setting changes, never per frame, and read through
     * plain fields: a hook that runs per entity must not look up a setting by
     * name.
     */
    public static final class Snapshot {
        public boolean enabled;
        public PerfPreset entityPreset = DEFAULT_ENTITY_PRESET;
        public PerfPreset tilePreset = DEFAULT_TILE_PRESET;
        public final double[] entityLimitsSquared = new double[EntityKind.COUNT];
        public final double[] tileLimitsSquared = new double[TileDistances.Kind.COUNT];
        public boolean fastNametags;
        public boolean hudCache;
        public boolean stats;

        /** Everything off: the state the benchmark's A arm and a missing module use. */
        public Snapshot off() {
            enabled = false;
            entityPreset = PerfPreset.OFF;
            tilePreset = PerfPreset.OFF;
            java.util.Arrays.fill(entityLimitsSquared, EntityDistances.UNLIMITED);
            java.util.Arrays.fill(tileLimitsSquared, TileDistances.UNLIMITED);
            fastNametags = false;
            hudCache = false;
            stats = false;
            return this;
        }

        /** Copy the module's settings in. A null module reads as everything off. */
        public Snapshot read(FathomModule module) {
            if (module == null) {
                return off();
            }
            enabled = module.enabled();
            entityPreset = PerfPreset.parse(choice(module, ENTITY_PRESET), DEFAULT_ENTITY_PRESET);
            tilePreset = PerfPreset.parse(choice(module, TILE_PRESET), DEFAULT_TILE_PRESET);
            if (!enabled) {
                entityPreset = PerfPreset.OFF;
                tilePreset = PerfPreset.OFF;
            }
            EntityDistances.fill(entityLimitsSquared, entityPreset,
                    whole(module, PLAYER_DISTANCE).get(), whole(module, MOB_DISTANCE).get(),
                    whole(module, ITEM_DISTANCE).get(), whole(module, MISC_DISTANCE).get());
            TileDistances.fill(tileLimitsSquared, tilePreset, whole(module, TILE_DISTANCE).get());
            fastNametags = enabled && toggle(module, FAST_NAMETAGS);
            hudCache = enabled && toggle(module, HUD_CACHE);
            stats = enabled && toggle(module, STATS);
            return this;
        }

        public boolean entitiesLimited() {
            return entityPreset != PerfPreset.OFF;
        }

        public boolean tilesLimited() {
            return tilePreset != PerfPreset.OFF;
        }

        private static String choice(FathomModule module, String id) {
            return ((Setting.Choice) module.setting(id)).get();
        }

        private static boolean toggle(FathomModule module, String id) {
            return ((Setting.Toggle) module.setting(id)).get();
        }
    }
}
