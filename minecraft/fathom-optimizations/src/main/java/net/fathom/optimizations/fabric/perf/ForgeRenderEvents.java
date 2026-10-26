package net.fathom.optimizations.fabric.perf;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * OptiFine's Forge render events, posted only when something listens
 * ({@code render.forge.unheard-render-events}).
 *
 * <h2>What OptiFine does</h2>
 *
 * <p>OptiFine replaces {@code RendererLivingEntity}, and with it Forge's own
 * patch of that class, so on Forge it posts Forge's events itself, through
 * reflection: for every living entity drawn, every frame,
 * {@code RenderLivingEvent.Pre} and {@code .Post} around the body and
 * {@code RenderLivingEvent.Specials.Pre} and {@code .Post} around the name
 * label -- hologram lines included, since they are named armor stands. Each
 * post boxes three doubles into an argument array, builds the event through
 * {@code Constructor.newInstance}, fetches the event bus through a reflective
 * field read and calls {@code EventBus.post} through {@code Method.invoke}.
 * Before any of that it asks {@code ReflectorConstructor.exists()}; when that
 * answers false it goes straight on as if the event had not been cancelled.
 *
 * <h2>What this changes</h2>
 *
 * <p>That {@code exists()} answers false as well when the event's listener
 * list on Forge's bus is empty, read as {@code EventBus.post} itself reads
 * it, on every call. With no listener, {@code post} runs no code and returns
 * false (nothing can cancel the event), which is exactly the path OptiFine
 * takes when {@code exists()} is false, so the output is the same by
 * construction. A listener registered at any time, at any priority, on the
 * event or a parent event, is in that list and the event is posted as before.
 * If anything about Forge's bus cannot be read, nothing is skipped.
 *
 * <p>{@code -Dfathom.verify.caches=true} checks both directions in play: one
 * skip in 32 is posted for real and must come back uncancelled with no
 * listener run, and once per event a listener of Fathom's own is registered
 * for 512 decisions, every one of which must be posted and delivered to it.
 */
public final class ForgeRenderEvents {

    /** Check both directions in play (QA only). */
    public static final boolean VERIFY = Boolean.getBoolean("fathom.verify.caches");

    private static final int VERIFY_SKIP_EVERY = 32;
    private static final int VERIFY_LISTEN_AFTER = 4096;
    private static final int VERIFY_LISTEN_FOR = 512;

    private static final MethodType EXISTS_TYPE = MethodType.methodType(boolean.class);
    private static final MethodType LISTENERS = MethodType.methodType(Object[].class, int.class);

    /** One per OptiFine event constructor seen: at most four. */
    private static Gate[] gates = new Gate[0];
    /** Forge's bus could not be read: never skip. */
    private static boolean broken;
    private static int busId;
    private static Object bus;
    private static Method post;

    private ForgeRenderEvents() {}

    /**
     * The answer for OptiFine's {@code constructor.exists()} at an event post
     * in {@code RendererLivingEntity}: its own answer, and false when the event
     * has no listener.
     */
    public static boolean exists(Object constructor) {
        Gate gate = gate(constructor);
        if (gate == null) {
            return original(constructor);
        }
        try {
            if (!(boolean) Original.EXISTS.invokeExact(constructor)) {
                return false;
            }
            Object[] listeners = (Object[]) gate.listeners.invokeExact(busId);
            if (VERIFY) {
                gate.verify(listeners.length == 0);
            }
            if (listeners.length == 0) {
                gate.skipped++;
                net.fathom.optimizations.core.bench.view.ViewProbe.engaged(
                        net.fathom.optimizations.core.bench.view.ViewCounter.ENGAGED_FORGE_EVENTS);
                return false;
            }
            gate.posted++;
            return true;
        } catch (Throwable unreadable) {
            fail(unreadable);
            return original(constructor);
        }
    }

    /** OptiFine's own {@code constructor.exists()}, as cheap as its direct call once compiled. */
    public static boolean original(Object constructor) {
        try {
            return (boolean) Original.EXISTS.invokeExact(constructor);
        } catch (RuntimeException | Error thrown) {
            throw thrown;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    /** Resolved on first use, which only OptiFine's class can reach. A constant to the compiler. */
    private static final class Original {
        static final MethodHandle EXISTS = resolve();

        private static MethodHandle resolve() {
            try {
                Class<?> type = Class.forName("net.optifine.reflect.ReflectorConstructor", false,
                        ForgeRenderEvents.class.getClassLoader());
                return MethodHandles.publicLookup().findVirtual(type, "exists", EXISTS_TYPE)
                        .asType(MethodType.methodType(boolean.class, Object.class));
            } catch (ReflectiveOperationException absent) {
                throw new IllegalStateException("OptiFine's ReflectorConstructor is not reachable", absent);
            }
        }
    }

    private static Gate gate(Object constructor) {
        Gate[] current = gates;
        for (Gate gate : current) {
            if (gate.constructor == constructor) {
                return gate;
            }
        }
        if (broken) {
            return null;
        }
        try {
            Gate gate = new Gate(constructor);
            Gate[] grown = new Gate[current.length + 1];
            System.arraycopy(current, 0, grown, 0, current.length);
            grown[current.length] = gate;
            gates = grown;
            return gate;
        } catch (Throwable unreadable) {
            fail(unreadable);
            return null;
        }
    }

    private static void fail(Throwable why) {
        if (!broken) {
            broken = true;
            gates = new Gate[0];
            System.out.println("[Fathom] Forge render events: posting every event as OptiFine does (" + why + ")");
        }
    }

    /** Forge's event bus and its id, read once. */
    private static void bus(ClassLoader loader) throws Exception {
        if (bus != null) {
            return;
        }
        Object found = Class.forName("net.minecraftforge.common.MinecraftForge", true, loader)
                .getField("EVENT_BUS").get(null);
        Class<?> type = Class.forName("net.minecraftforge.fml.common.eventhandler.EventBus", true, loader);
        Field id = type.getDeclaredField("busID");
        id.setAccessible(true);
        busId = id.getInt(found);
        post = type.getMethod("post", Class.forName("net.minecraftforge.fml.common.eventhandler.Event", true, loader));
        bus = found;
    }

    /** Totals for the log. */
    public static String summary() {
        StringBuilder out = new StringBuilder();
        for (Gate gate : gates) {
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(gate.name).append(": ").append(gate.skipped).append(" skipped, ").append(gate.posted)
                    .append(" posted");
        }
        return out.length() == 0 ? (broken ? "off (Forge's bus unreadable)" : "none seen") : out.toString();
    }

    /** One OptiFine event constructor, the event class's listener list, and the QA tallies. */
    private static final class Gate {
        final Object constructor;
        final MethodHandle listeners;
        final Constructor<?> target;
        final Object list;
        final String name;
        long skipped;
        long posted;

        // QA
        long decisions;
        long reposted;
        long repostCancelled;
        int listening = -1;
        long listenDecisions;
        long listenPosted;
        long delivered;
        Object listener;

        Gate(Object constructor) throws Throwable {
            this.constructor = constructor;
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> type = constructor.getClass();
            this.target = (Constructor<?>) type.getMethod("getTargetConstructor").invoke(constructor);
            if (target == null) {
                throw new IllegalStateException("no target constructor");
            }
            ClassLoader loader = target.getDeclaringClass().getClassLoader();
            bus(loader);
            // The event class's static listener list, which the event's first
            // construction creates. Constructing one here does what OptiFine's
            // first post would have done; the fields are never read.
            Object sample = target.newInstance(defaults(target.getParameterTypes()));
            this.list = sample.getClass().getMethod("getListenerList").invoke(sample);
            Method get = list.getClass().getMethod("getListeners", int.class);
            this.listeners = lookup.unreflect(get).bindTo(list).asType(LISTENERS);
            String simple = target.getDeclaringClass().getName();
            this.name = simple.substring(simple.lastIndexOf('.') + 1);
        }

        private static Object[] defaults(Class<?>[] types) {
            Object[] values = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];
                if (t == double.class) {
                    values[i] = 0d;
                } else if (t == float.class) {
                    values[i] = 0f;
                } else if (t == int.class) {
                    values[i] = 0;
                } else if (t == long.class) {
                    values[i] = 0L;
                } else if (t == boolean.class) {
                    values[i] = false;
                } else if (t.isPrimitive()) {
                    throw new IllegalStateException("unexpected parameter " + t);
                }
            }
            return values;
        }

        /** QA, before the decision is used. {@code unheard}: no listener at all. */
        void verify(boolean unheard) throws Throwable {
            decisions++;
            if (listening > 0) {
                listening--;
                listenDecisions++;
                if (!unheard) {
                    listenPosted++;
                }
                return;
            }
            if (listening == 0) {
                // After the last listened decision's post has been delivered.
                stopListening();
                return;
            }
            if (unheard && skipped % VERIFY_SKIP_EVERY == 0) {
                // What the post OptiFine would have made returns: false, with no listener run.
                Object event = target.newInstance(defaults(target.getParameterTypes()));
                reposted++;
                if ((Boolean) post.invoke(bus, event)) {
                    repostCancelled++;
                }
            }
            if (decisions == VERIFY_LISTEN_AFTER && listener == null) {
                startListening();
            }
        }

        private void startListening() throws Throwable {
            ClassLoader loader = target.getDeclaringClass().getClassLoader();
            Class<?> iface = Class.forName("net.minecraftforge.fml.common.eventhandler.IEventListener", true, loader);
            Class<?> priority = Class.forName("net.minecraftforge.fml.common.eventhandler.EventPriority", true, loader);
            final Gate self = this;
            listener = Proxy.newProxyInstance(loader, new Class<?>[] {iface}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "invoke":
                        self.delivered++;
                        return null;
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        return "FathomVerifyListener";
                }
            });
            Object normal = priority.getField("NORMAL").get(null);
            list.getClass().getMethod("register", int.class, priority, iface).invoke(list, busId, normal, listener);
            listening = VERIFY_LISTEN_FOR;
        }

        private void stopListening() throws Throwable {
            ClassLoader loader = target.getDeclaringClass().getClassLoader();
            Class<?> iface = Class.forName("net.minecraftforge.fml.common.eventhandler.IEventListener", true, loader);
            list.getClass().getMethod("unregister", int.class, iface).invoke(list, busId, listener);
            listening = -1;
            System.out.println("[Fathom] Forge render events verify: " + name + " with a listener: "
                    + listenDecisions + " decisions, " + listenPosted + " posted, " + delivered
                    + " delivered (all three must match); without: " + reposted + " skips posted for real, "
                    + repostCancelled + " came back cancelled (must be 0)");
        }
    }
}
