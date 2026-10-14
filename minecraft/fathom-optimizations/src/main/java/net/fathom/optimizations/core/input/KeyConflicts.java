package net.fathom.optimizations.core.input;

import java.util.ArrayList;
import java.util.List;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Keybind;

/**
 * What else a key is bound to, for the warning under a keybind row.
 *
 * <p>Shown and not enforced. Two actions on one key is usually a mistake and
 * occasionally exactly what somebody meant, and a client that refused the
 * binding would be wrong in the second case with no way round it. Fathom
 * consumes no key, so a shared key fires every action bound to it -- which is
 * worth knowing before wondering why zooming also drops an item.
 *
 * <p>Pure, so the warning the Mods page shows is the one the tests check: other
 * modules' keys, the Hub key, and Minecraft's own bindings, by name, once each.
 */
public final class KeyConflicts {

    /** The name the Hub key is listed under. */
    public static final String HUB = "Open Fathom Hub";

    private KeyConflicts() {}

    /**
     * Everything else on {@code asking}'s key, in a stable order: modules as
     * registered, then the Hub key, then Minecraft's bindings as listed.
     *
     * @param vanillaNames Minecraft's bindings' display names, parallel to
     *     {@code vanillaCodes}; either may be null for none
     */
    public static List<String> of(Keybind asking, Iterable<FathomModule> modules, Keybind hubKey,
            String[] vanillaNames, int[] vanillaCodes) {
        List<String> others = new ArrayList<String>();
        if (asking == null || !asking.isBound()) {
            return others;
        }
        int key = asking.key();
        for (FathomModule module : modules) {
            for (Keybind other : module.keybinds()) {
                if (other != asking && other.isBound() && other.key() == key && !others.contains(module.name())) {
                    others.add(module.name());
                }
            }
        }
        if (hubKey != null && hubKey != asking && hubKey.isBound() && hubKey.key() == key && !others.contains(HUB)) {
            others.add(HUB);
        }
        if (vanillaNames != null && vanillaCodes != null) {
            for (int index = 0; index < Math.min(vanillaNames.length, vanillaCodes.length); index++) {
                String name = vanillaNames[index];
                if (name != null && vanillaCodes[index] == key && !others.contains(name)) {
                    others.add(name);
                }
            }
        }
        return others;
    }

    /** "This key is also used by A, B and C.", or empty when nothing else uses it. */
    public static String sentence(List<String> others) {
        if (others.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("This key is also used by ");
        for (int index = 0; index < others.size(); index++) {
            if (index > 0) {
                out.append(index == others.size() - 1 ? " and " : ", ");
            }
            out.append(others.get(index));
        }
        return out.append('.').toString();
    }
}
