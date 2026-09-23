package net.fathom.optimizations.core.bench.auto;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One configuration to measure.
 *
 * <h2>The three kinds</h2>
 *
 * <ul>
 *   <li><b>Normal</b> -- every subsystem as the player had it. The reference for
 *       "what does Fathom cost me right now".
 *   <li><b>Minimal</b> -- every measurable subsystem off. The floor: Minecraft,
 *       OptiFine and the Fathom infrastructure that cannot be removed without
 *       removing Fathom. The difference between this and Normal is the total
 *       Fathom is responsible for, and it bounds everything a single-subsystem
 *       pass can explain.
 *   <li><b>Isolate</b> -- Minimal plus exactly one subsystem or group. Its cost
 *       is measured against Minimal, not against Normal: a pass measured against
 *       Normal would report what <em>removing everything else</em> saved.
 * </ul>
 */
public final class BenchmarkPass {

    public enum Kind {
        NORMAL,
        MINIMAL,
        ISOLATE
    }

    private final String id;
    private final String label;
    private final Kind kind;
    private final Set<String> enable;

    private BenchmarkPass(String id, String label, Kind kind, Set<String> enable) {
        this.id = id;
        this.label = label;
        this.kind = kind;
        this.enable = Collections.unmodifiableSet(enable);
    }

    public static BenchmarkPass normal() {
        return new BenchmarkPass("normal", "Normal Fathom", Kind.NORMAL,
                new LinkedHashSet<String>());
    }

    public static BenchmarkPass minimal() {
        return new BenchmarkPass("minimal", "Minimal Fathom", Kind.MINIMAL,
                new LinkedHashSet<String>());
    }

    /** Minimal, plus the named subsystems. */
    public static BenchmarkPass isolate(String id, String label, Set<String> subsystemIds) {
        return new BenchmarkPass(id, label, Kind.ISOLATE,
                new LinkedHashSet<String>(subsystemIds));
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public Kind kind() {
        return kind;
    }

    /** Subsystem ids to switch on for this pass. Empty for Normal and Minimal. */
    public Set<String> enable() {
        return enable;
    }

    /**
     * Put the registry into this pass's configuration.
     *
     * <p>Normal is applied by restoring the snapshot the manager took, because
     * "as the player had it" is not something a set of ids can express -- some
     * of their modules were already off, and a pass that switched those on would
     * be measuring a configuration nobody runs.
     */
    public void apply(BenchmarkRegistry registry, java.util.Map<String, Boolean> original) {
        switch (kind) {
            case NORMAL:
                registry.restore(original);
                break;
            case MINIMAL:
                registry.setAllMeasurable(false);
                break;
            case ISOLATE:
            default:
                registry.setAllMeasurable(false);
                for (String each : enable) {
                    BenchmarkSubsystem subsystem = registry.find(each);
                    if (subsystem != null) {
                        try {
                            subsystem.setEnabled(true);
                        } catch (Throwable ignored) {
                            continue;
                        }
                    }
                }
                break;
        }
    }
}
