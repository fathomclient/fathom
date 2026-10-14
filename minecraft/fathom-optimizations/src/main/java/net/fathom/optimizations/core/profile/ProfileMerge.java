package net.fathom.optimizations.core.profile;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Saving a profile without undoing somebody else's edits to it.
 *
 * <h2>The problem</h2>
 *
 * <p>The launcher keeps one collection of profiles for every installation, so
 * two games can be running against the same file — a second account, a second
 * instance for testing — and so can the launcher. A save used to write the
 * whole running client over the file, so whichever window closed its menu last
 * silently reverted everything the other had changed.
 *
 * <h2>What a save writes now</h2>
 *
 * <p>Only what <em>this</em> client changed since it last agreed with the file,
 * applied on top of what the file says now. Three documents go in:
 *
 * <ul>
 *   <li><b>base</b> — this client, captured when it last loaded or saved;
 *   <li><b>mine</b> — this client, captured now;
 *   <li><b>theirs</b> — the newest the file is known to hold.
 * </ul>
 *
 * <p>Every value that differs between base and mine is this client's edit, and
 * is written into a copy of theirs; every other value is left as theirs has it.
 * The comparison is per setting, per key, per widget and per preference, so two
 * windows that changed different things both keep their change, and two that
 * changed the same thing end with the later save's, which is the only answer
 * that does not need asking.
 *
 * <p>With nobody else writing, theirs is what this client last wrote, and the
 * result is exactly mine.
 */
public final class ProfileMerge {

    private ProfileMerge() {}

    public static FathomProfile merge(FathomProfile base, FathomProfile mine, FathomProfile theirs) {
        FathomProfile out = theirs.duplicate(theirs.id(), theirs.name());
        out.restoreTimes(theirs);

        // Modules, field by field.
        Set<String> moduleIds = new TreeSet<String>(base.modules().keySet());
        moduleIds.addAll(mine.modules().keySet());
        for (String id : moduleIds) {
            FathomProfile.ModuleState was = orEmpty(base.modules().get(id));
            FathomProfile.ModuleState now = orEmpty(mine.modules().get(id));
            FathomProfile.ModuleState into = out.module(id);
            if (!same(was.enabled, now.enabled)) {
                into.enabled = now.enabled;
            }
            if (was.favourite != now.favourite) {
                into.favourite = now.favourite;
            }
            mergeMap(was.settings, now.settings, into.settings);
            mergeMap(was.keys, now.keys, into.keys);
        }
        out.prune();

        // Widgets, whole placements.
        Set<String> widgets = new TreeSet<String>(base.hud().keySet());
        widgets.addAll(mine.hud().keySet());
        for (String id : widgets) {
            FathomProfile.HudEntry was = base.hud().get(id);
            FathomProfile.HudEntry now = mine.hud().get(id);
            if (same(was, now)) {
                continue;
            }
            if (now == null) {
                out.hud().remove(id);
            } else {
                out.hud().put(id, now.copy());
            }
        }

        // The client's own preferences.
        FathomProfile.Client was = base.client();
        FathomProfile.Client now = mine.client();
        FathomProfile.Client into = out.client();
        if (!same(was.hubKey, now.hubKey)) {
            into.hubKey = now.hubKey;
        }
        if (!same(was.uiScale, now.uiScale)) {
            into.uiScale = now.uiScale;
        }
        if (!same(was.highQualityUi, now.highQualityUi)) {
            into.highQualityUi = now.highQualityUi;
        }
        if (!same(was.hudFont, now.hudFont)) {
            into.hudFont = now.hudFont;
        }
        if (!same(was.rshiftBackground, now.rshiftBackground)) {
            into.rshiftBackground = now.rshiftBackground;
        }
        if (!same(was.rshiftBlur, now.rshiftBlur)) {
            into.rshiftBlur = now.rshiftBlur;
        }
        if (!same(was.rshiftDim, now.rshiftDim)) {
            into.rshiftDim = now.rshiftDim;
        }
        if (!same(was.menuBackground, now.menuBackground)) {
            into.menuBackground = now.menuBackground;
        }
        mergeMap(was.extra, now.extra, into.extra);

        // Performance switches.
        if (!same(base.performance().preset, mine.performance().preset)) {
            out.performance().preset = mine.performance().preset;
        }
        mergeMap(base.performance().switches, mine.performance().switches, out.performance().switches);
        return out;
    }

    /** Copy every key whose value changed from {@code was} to {@code now} into {@code into}. */
    private static <V> void mergeMap(Map<String, V> was, Map<String, V> now, TreeMap<String, V> into) {
        Set<String> keys = new TreeSet<String>(was.keySet());
        keys.addAll(now.keySet());
        for (String key : keys) {
            V before = was.get(key);
            V after = now.get(key);
            if (same(before, after)) {
                continue;
            }
            if (after == null) {
                into.remove(key);
            } else {
                into.put(key, after);
            }
        }
    }

    private static FathomProfile.ModuleState orEmpty(FathomProfile.ModuleState state) {
        return state == null ? new FathomProfile.ModuleState() : state;
    }

    private static boolean same(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean same(FathomProfile.HudEntry a, FathomProfile.HudEntry b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.anchor.equals(b.anchor) && a.x == b.x && a.y == b.y
                && Float.floatToIntBits(a.scale) == Float.floatToIntBits(b.scale)
                && Float.floatToIntBits(a.opacity) == Float.floatToIntBits(b.opacity)
                && a.hidden == b.hidden;
    }
}
