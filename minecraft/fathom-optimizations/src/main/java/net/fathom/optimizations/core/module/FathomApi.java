package net.fathom.optimizations.core.module;

import net.fathom.optimizations.core.hud.Placement;

/**
 * The surface a Fathom-compatible mod registers itself through.
 *
 * <h2>What this is for, and what it is not</h2>
 *
 * <p>It is for a mod that wants to <em>be part of the client</em>: to appear in
 * the hub's Mods list, be searchable, be switched on and off there, have its
 * settings rendered in the same rows as Fathom's own, have its HUD widgets
 * arrangeable in the same editor, and have all of it persist into the profile's
 * config alongside everything else.
 *
 * <p>It is not required. An ordinary Fabric mod works exactly as it always did;
 * Fathom loads it, the resolver checks it, and it never appears in this list.
 * That is the correct outcome for the overwhelming majority of mods, and an API
 * that had to be adopted to be tolerated would be a worse client.
 *
 * <h2>How a mod uses it</h2>
 *
 * <pre>
 * FathomApi.get().register(
 *     FathomModule.builder("mymod.thing")
 *         .name("Thing")
 *         .description("Draws a thing on your screen.")
 *         .category(Category.HUD)
 *         .owner("My Mod")
 *         .widget("mymod.thing")
 *         .setting(new Setting.Toggle("bold", "Bold", "", false))
 *         .build(),
 *     new MyThingWidget());
 * </pre>
 *
 * <p>Registration is open during client initialisation and closes before
 * anything renders. Registering late is refused and recorded rather than
 * throwing, because a mod getting its ordering wrong should cost that mod its
 * feature and not cost the player their client.
 *
 * <h2>What a registrant cannot do</h2>
 *
 * <p>Reach the hub's renderer, add a section, replace a screen, read another
 * feature's settings, or write to the layout of a widget it does not own.
 * Everything a feature can express is expressible through {@link FathomModule}, and
 * that is deliberate: a client whose menu could be rearranged by any installed
 * mod is a client with no design.
 *
 * <h2>Why the widget is passed as an object</h2>
 *
 * <p>A widget has to draw, and drawing needs the game. The declaration
 * ({@link FathomModule}) has no Minecraft types in it and is testable without the
 * game; the renderer is an opaque handle to this layer, kept as
 * {@link Object} so {@code core} stays free of Minecraft imports. The Fabric
 * side checks the type and refuses anything else.
 */
public final class FathomApi {

    /** How a registration turned out. */
    public enum Result {
        /** Registered, and it will appear in the hub. */
        ACCEPTED,
        /** Refused: the id was already taken. */
        DUPLICATE,
        /** Refused: registration had already closed. */
        TOO_LATE,
        /** Refused: the client is not initialised yet. */
        NOT_READY,
        /** Refused: the widget was not usable. */
        BAD_WIDGET
    }

    /** What the API is backed by, once the client has started. */
    public interface Host {
        Result register(FathomModule feature, Object widgetRenderer, Placement defaultPlacement);

        ModuleRegistry registry();
    }

    private static volatile Host host;

    private FathomApi() {}

    /** Wired once by the client initialiser. */
    public static void install(Host newHost) {
        host = newHost;
    }

    /**
     * Whether the client is up and accepting registrations.
     *
     * <p>Worth asking: a mod that lists Fathom as an optional dependency runs
     * whether or not Fathom is installed, and a hard call into a null host
     * would turn "Fathom is absent" into a crash.
     */
    public static boolean available() {
        return host != null;
    }

    /** The API, or a no-op that refuses everything when Fathom is not up. */
    public static Api get() {
        return new Api(host);
    }

    /** The methods a registrant calls. A value type so the null case is total. */
    public static final class Api {
        private final Host host;

        Api(Host host) {
            this.host = host;
        }

        /** A feature with no HUD widget: settings, keybinds, or both. */
        public Result register(FathomModule feature) {
            return register(feature, null, null);
        }

        /** A feature that draws, starting in the top-left corner until it is moved. */
        public Result register(FathomModule feature, Object widgetRenderer) {
            return register(feature, widgetRenderer, null);
        }

        /**
         * A feature that draws, saying where its widget starts out.
         *
         * @param widgetRenderer must implement the Fabric layer's widget
         *     interface. Anything else is refused with {@link Result#BAD_WIDGET}
         *     rather than failing later inside a render loop.
         */
        public Result register(FathomModule feature, Object widgetRenderer, Placement defaultPlacement) {
            Host current = host;
            if (current == null) {
                return Result.NOT_READY;
            }
            return current.register(feature, widgetRenderer, defaultPlacement);
        }

        /**
         * Read-only access to what is registered.
         *
         * <p>Returns null when Fathom is not up. A registrant that wants to know
         * whether a feature of its own is enabled should hold its own
         * {@link FathomModule} reference and ask that, which needs no lookup and
         * cannot see anybody else's.
         */
        public ModuleRegistry registry() {
            Host current = host;
            return current == null ? null : current.registry();
        }
    }
}
