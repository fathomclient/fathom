package net.fathom.optimizations.fabric.bench.auto;

import net.fathom.optimizations.core.bench.auto.BenchmarkRegistry;
import net.fathom.optimizations.core.bench.auto.BenchmarkSubsystem;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Builds the benchmark's view of what Fathom is made of.
 *
 * <h2>Derived, not listed</h2>
 *
 * <p>Every Fathom module becomes a subsystem automatically, so a module added
 * next month is benchmarked without anybody editing this file. Only the pieces
 * that have no module -- the renderers themselves -- are named here, and each
 * one says whether it can actually be switched.
 *
 * <h2>The absent ones are listed on purpose</h2>
 *
 * <p>Cosmetics, custom nametag rendering, a custom scoreboard and custom
 * particles are all things a client like this usually has, and all things this
 * one does not. They are registered as absent so the report says "not present"
 * instead of omitting them, because an omission reads as "measured, costs
 * nothing" to anybody comparing against another client.
 */
public final class FathomSubsystems {

    private FathomSubsystems() {}

    public static BenchmarkRegistry build() {
        BenchmarkRegistry registry = new BenchmarkRegistry();

        // -- every module, automatically ------------------------------------
        ModuleRegistry modules = Runtime.features();
        if (modules != null) {
            for (FathomModule module : modules.all()) {
                registry.add(new ModuleSubsystem(module));
            }
        }

        // -- rendering infrastructure ---------------------------------------
        registry.add(new ToggleSubsystem(
                "render.hud-pass", "Fathom HUD pass (all widgets)", "render",
                true, true, false,
                new Toggle() {
                    @Override
                    public boolean get() {
                        return BenchmarkState.hudPassEnabled();
                    }

                    @Override
                    public void set(boolean on) {
                        BenchmarkState.setHudPassEnabled(on);
                    }
                },
                ""));

        registry.add(new ToggleSubsystem(
                "render.fathom-font", "Fathom typeface in the HUD", "render",
                true, true, false,
                new Toggle() {
                    @Override
                    public boolean get() {
                        return BenchmarkState.fathomFontEnabled();
                    }

                    @Override
                    public void set(boolean on) {
                        BenchmarkState.setFathomFontEnabled(on);
                    }
                },
                ""));

        // The compositor is selected at startup and also owns the live HUD, so
        // it cannot be toggled safely in the middle of an automated run.
        registry.add(new ToggleSubsystem(
                "render.compositor", "SDF interface compositor", "render",
                true, false, false, null,
                "chosen at class load from -Dfathom.forceGlRenderer; used by Fathom screens and HUD"));

        registry.add(new ToggleSubsystem(
                "render.gl-state-guard", "GL state capture around Fathom frames", "render",
                true, false, true, null,
                "correctness machinery: Fathom must hand the game's GL state back "
                        + "unchanged, and the capture is how it knows what to restore"));

        // -- things this build does not have --------------------------------
        absent(registry, "render.cosmetics", "Cosmetics", "Fathom has no cosmetics system.");
        absent(registry, "render.nametags", "Custom nametag rendering",
                "Fathom does not render nametags; vanilla and OptiFine own that path.");
        absent(registry, "render.scoreboard", "Custom scoreboard rendering",
                "Fathom does not render the scoreboard.");
        absent(registry, "render.particles", "Custom particles",
                "Fathom adds no particles.");
        absent(registry, "render.entity-hooks", "Entity/player render hooks",
                "Fathom's entity mixins stand down when OptiFine is present, which it is.");
        absent(registry, "render.event-bus", "Render event bus",
                "Fathom dispatches from Mixin injection points directly; there is no event bus.");

        return registry;
    }

    private static void absent(
            BenchmarkRegistry registry, String id, String label, String reason) {
        registry.add(new ToggleSubsystem(id, label, "absent", false, false, false, null, reason));
    }

    // -----------------------------------------------------------------------

    interface Toggle {
        boolean get();

        void set(boolean on);
    }

    /** A Fathom module, seen as something to switch off and measure. */
    private static final class ModuleSubsystem implements BenchmarkSubsystem {
        private final FathomModule module;

        ModuleSubsystem(FathomModule module) {
            this.module = module;
        }

        @Override
        public String id() {
            return "module." + module.id();
        }

        @Override
        public String label() {
            return module.name();
        }

        @Override
        public String group() {
            return "hud";
        }

        @Override
        public boolean present() {
            return true;
        }

        @Override
        public String absenceReason() {
            return "";
        }

        @Override
        public boolean runtimeToggle() {
            // A module that cannot be switched off in the interface cannot be
            // switched off here either; pretending otherwise would leave the
            // client in a state the user cannot reach or undo.
            return module.switchable();
        }

        @Override
        public boolean enabled() {
            return module.enabled();
        }

        @Override
        public void setEnabled(boolean on) {
            module.setEnabled(on);
        }

        @Override
        public boolean essential() {
            return false;
        }
    }

    /** Infrastructure, with an explicit toggle or an explicit reason there is none. */
    private static final class ToggleSubsystem implements BenchmarkSubsystem {
        private final String id;
        private final String label;
        private final String group;
        private final boolean present;
        private final boolean runtimeToggle;
        private final boolean essential;
        private final Toggle toggle;
        private final String reason;

        ToggleSubsystem(String id, String label, String group, boolean present,
                boolean runtimeToggle, boolean essential, Toggle toggle, String reason) {
            this.id = id;
            this.label = label;
            this.group = group;
            this.present = present;
            this.runtimeToggle = runtimeToggle;
            this.essential = essential;
            this.toggle = toggle;
            this.reason = reason;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String group() {
            return group;
        }

        @Override
        public boolean present() {
            return present;
        }

        @Override
        public String absenceReason() {
            return reason;
        }

        @Override
        public boolean runtimeToggle() {
            return runtimeToggle && toggle != null;
        }

        @Override
        public boolean enabled() {
            return toggle != null && toggle.get();
        }

        @Override
        public void setEnabled(boolean on) {
            if (toggle != null) {
                toggle.set(on);
            }
        }

        @Override
        public boolean essential() {
            return essential;
        }
    }
}
