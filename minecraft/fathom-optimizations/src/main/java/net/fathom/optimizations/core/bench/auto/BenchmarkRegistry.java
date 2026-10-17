package net.fathom.optimizations.core.bench.auto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Every subsystem the benchmark knows how to switch.
 *
 * <h2>Restoring state</h2>
 *
 * <p>{@link #snapshot} and {@link #restore} are the reason benchmark mode can be
 * described as not changing anything: the manager takes a snapshot before the
 * first pass and restores it in a {@code finally}, so a pass that throws leaves
 * the client exactly as it found it. Reversibility is a property of this class,
 * not of each caller remembering.
 */
public final class BenchmarkRegistry {

    private final Map<String, BenchmarkSubsystem> byId =
            new LinkedHashMap<String, BenchmarkSubsystem>();

    /** Register, ignoring a duplicate id rather than throwing during startup. */
    public boolean add(BenchmarkSubsystem subsystem) {
        if (subsystem == null || byId.containsKey(subsystem.id())) {
            return false;
        }
        byId.put(subsystem.id(), subsystem);
        return true;
    }

    public List<BenchmarkSubsystem> all() {
        return Collections.unmodifiableList(new ArrayList<BenchmarkSubsystem>(byId.values()));
    }

    /** Present, non-essential, and switchable at runtime: the measurable set. */
    public List<BenchmarkSubsystem> measurable() {
        List<BenchmarkSubsystem> out = new ArrayList<BenchmarkSubsystem>();
        for (BenchmarkSubsystem each : byId.values()) {
            if (each.present() && !each.essential() && each.runtimeToggle()) {
                out.add(each);
            }
        }
        return out;
    }

    /** Present but not switchable without a restart. Reported, never measured. */
    public List<BenchmarkSubsystem> unswitchable() {
        List<BenchmarkSubsystem> out = new ArrayList<BenchmarkSubsystem>();
        for (BenchmarkSubsystem each : byId.values()) {
            if (each.present() && !each.essential() && !each.runtimeToggle()) {
                out.add(each);
            }
        }
        return out;
    }

    /** Absent in this build, with the reason. */
    public List<BenchmarkSubsystem> absent() {
        List<BenchmarkSubsystem> out = new ArrayList<BenchmarkSubsystem>();
        for (BenchmarkSubsystem each : byId.values()) {
            if (!each.present()) {
                out.add(each);
            }
        }
        return out;
    }

    /** The distinct groups across the measurable set, in registration order. */
    public List<String> groups() {
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (BenchmarkSubsystem each : measurable()) {
            seen.add(each.group());
        }
        return new ArrayList<String>(seen);
    }

    public List<BenchmarkSubsystem> inGroup(String group) {
        List<BenchmarkSubsystem> out = new ArrayList<BenchmarkSubsystem>();
        for (BenchmarkSubsystem each : measurable()) {
            if (each.group().equals(group)) {
                out.add(each);
            }
        }
        return out;
    }

    public BenchmarkSubsystem find(String id) {
        return byId.get(id);
    }

    public int size() {
        return byId.size();
    }

    /** Every subsystem's current state, for restoring afterwards. */
    public Map<String, Boolean> snapshot() {
        Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
        for (BenchmarkSubsystem each : byId.values()) {
            if (each.present() && each.runtimeToggle()) {
                out.put(each.id(), Boolean.valueOf(each.enabled()));
            }
        }
        return out;
    }

    /**
     * Put every recorded state back.
     *
     * <p>Each one is attempted independently: a subsystem that throws on the way
     * back must not stop the rest being restored, because the alternative is a
     * client left half-configured by a diagnostic.
     */
    public List<String> restore(Map<String, Boolean> snapshot) {
        List<String> failures = new ArrayList<String>();
        if (snapshot == null) {
            return failures;
        }
        for (Map.Entry<String, Boolean> entry : snapshot.entrySet()) {
            BenchmarkSubsystem subsystem = byId.get(entry.getKey());
            if (subsystem == null) {
                continue;
            }
            try {
                subsystem.setEnabled(entry.getValue().booleanValue());
            } catch (Throwable failure) {
                failures.add(entry.getKey() + ": " + failure);
            }
        }
        return failures;
    }

    /** Switch every measurable subsystem off, or on. */
    public void setAllMeasurable(boolean on) {
        for (BenchmarkSubsystem each : measurable()) {
            try {
                each.setEnabled(on);
            } catch (Throwable ignored) {
                // Reported through the restore path; one stubborn subsystem is
                // not a reason to abandon the pass.
                continue;
            }
        }
    }
}
