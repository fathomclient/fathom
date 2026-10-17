package net.fathom.optimizations.core.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fathom.optimizations.core.hud.Sensitivity;

/**
 * Every feature the client knows about, first-party and registered.
 *
 * <p>One registry, not two. A third-party feature registered through
 * {@link FathomApi} lands in the same map as Keystrokes and is rendered by the
 * same code — which is the only way to be sure the API is good enough, because
 * it is the API our own features use.
 *
 * <h2>Registration is open until the hub first opens</h2>
 *
 * <p>Mods initialise in an order nobody controls, so the registry accepts
 * additions during start-up and {@link #seal seals} before anything renders.
 * After that a late registration is refused rather than half-applied: a feature
 * that appeared while a list was being drawn would be a feature whose settings
 * were never loaded from disk.
 */
public final class ModuleRegistry {

    private final Map<String, FathomModule> byId = new LinkedHashMap<String, FathomModule>();
    private final List<String> complaints = new ArrayList<String>();
    private boolean sealed;

    /** The built-in set. */
    public static ModuleRegistry standard() {
        ModuleRegistry registry = new ModuleRegistry();
        for (FathomModule feature : Modules.all()) {
            registry.register(feature);
        }
        return registry;
    }

    /** An empty registry, for tests. */
    public static ModuleRegistry empty() {
        return new ModuleRegistry();
    }

    /**
     * Add a feature.
     *
     * @return whether it was accepted. A duplicate id, or a registration after
     *     the registry sealed, is refused and recorded rather than thrown: a mod
     *     that registers badly should not take the client down with it.
     */
    public boolean register(FathomModule feature) {
        if (feature == null) {
            complaints.add("a null feature was registered");
            return false;
        }
        if (sealed) {
            complaints.add(feature.id() + " registered too late; the hub had already opened");
            return false;
        }
        if (byId.containsKey(feature.id())) {
            complaints.add(feature.id() + " is registered twice; the second was ignored");
            return false;
        }
        byId.put(feature.id(), feature);
        widgetOwners = null;
        return true;
    }

    /**
     * Widget id to the first feature that declares it, built on first use.
     *
     * <p>The HUD asks who owns each widget several times a frame. Scanning
     * every feature's widget list for each question was the HUD's single
     * largest fixed cost with nothing enabled. A feature's widget list is
     * immutable, so the map only changes when a feature is registered.
     */
    private Map<String, FathomModule> widgetOwners;

    private Map<String, FathomModule> widgetOwners() {
        Map<String, FathomModule> owners = widgetOwners;
        if (owners == null) {
            owners = new java.util.HashMap<String, FathomModule>();
            for (FathomModule feature : byId.values()) {
                for (String widget : feature.widgets()) {
                    if (!owners.containsKey(widget)) {
                        owners.put(widget, feature);
                    }
                }
            }
            widgetOwners = owners;
        }
        return owners;
    }

    /** Stop accepting registrations. Called once, before the first render. */
    public void seal() {
        sealed = true;
    }

    public boolean sealed() {
        return sealed;
    }

    /** Anything refused, for the start-up log and the Diagnostics section. */
    public List<String> complaints() {
        return Collections.unmodifiableList(complaints);
    }

    public FathomModule find(String id) {
        return byId.get(id);
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    public int size() {
        return byId.size();
    }

    public List<FathomModule> all() {
        return Collections.unmodifiableList(new ArrayList<FathomModule>(byId.values()));
    }

    public List<FathomModule> enabled() {
        List<FathomModule> out = new ArrayList<FathomModule>();
        for (FathomModule feature : byId.values()) {
            if (feature.enabled()) {
                out.add(feature);
            }
        }
        return out;
    }

    public List<FathomModule> inCategory(Category category) {
        List<FathomModule> out = new ArrayList<FathomModule>();
        for (FathomModule feature : byId.values()) {
            if (feature.category() == category) {
                out.add(feature);
            }
        }
        return out;
    }

    /**
     * Search, with favourites first.
     *
     * <p>Pinning is only worth having if a pinned thing is easier to reach, and
     * the place it has to be easier to reach is the list somebody is looking at.
     * Within each group the registry's own order is kept, so the list does not
     * reshuffle as things are pinned and unpinned.
     */
    public List<FathomModule> matching(String query) {
        List<FathomModule> pinned = new ArrayList<FathomModule>();
        List<FathomModule> rest = new ArrayList<FathomModule>();
        for (FathomModule feature : byId.values()) {
            if (!feature.matches(query)) {
                continue;
            }
            (feature.favourite() ? pinned : rest).add(feature);
        }
        pinned.addAll(rest);
        return pinned;
    }

    /** Search within one category. */
    public List<FathomModule> matching(String query, Category category) {
        List<FathomModule> out = new ArrayList<FathomModule>();
        for (FathomModule feature : matching(query)) {
            if (feature.category() == category) {
                out.add(feature);
            }
        }
        return out;
    }

    /** Which feature owns a HUD widget, or null when nothing does. */
    public FathomModule owningWidget(String widgetId) {
        return widgetOwners().get(widgetId);
    }

    /**
     * Whether a widget should be drawn.
     *
     * <p>A widget whose feature is switched off is not drawn, whatever the
     * layout says — otherwise switching Keystrokes off in Mods would leave the
     * keystrokes on screen until the user also found the HUD tab, which is two
     * places to say one thing.
     *
     * <p>A widget nothing owns is left to the layout, because it belongs to
     * something outside this registry and second-guessing it would hide it.
     */
    public boolean widgetAllowed(String widgetId) {
        FathomModule owner = owningWidget(widgetId);
        return owner == null || owner.enabled();
    }

    /** Every keybind on every feature, for the key handler. */
    public List<Keybind> keybinds() {
        List<Keybind> out = new ArrayList<Keybind>();
        for (FathomModule feature : byId.values()) {
            out.addAll(feature.keybinds());
        }
        return out;
    }

    /** The feature a keybind belongs to. */
    public FathomModule owningKeybind(Keybind keybind) {
        for (FathomModule feature : byId.values()) {
            if (feature.keybinds().contains(keybind)) {
                return feature;
            }
        }
        return null;
    }

    /** How many features are on, for the hub header. */
    public int enabledCount() {
        return enabled().size();
    }

    /** Put everything back the way it shipped. */
    public void resetAll() {
        for (FathomModule feature : byId.values()) {
            feature.resetToDefaults();
        }
    }

    /**
     * The rule the classification exists for, checked over the whole set.
     *
     * <p>Returns the ids of anything on by default that should not be. Used by
     * the tests rather than by the client, because the client's job is to
     * enforce it — {@link FathomModule.Builder} already refuses — and the test's job
     * is to prove the enforcement works.
     */
    public List<String> wronglyDefaultedOn() {
        List<String> out = new ArrayList<String>();
        for (FathomModule feature : byId.values()) {
            if (feature.defaultEnabled() && feature.sensitivity() != Sensitivity.SAFE) {
                out.add(feature.id());
            }
        }
        return out;
    }
}
