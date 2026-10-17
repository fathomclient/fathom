package net.fathom.optimizations.core.registry;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What has to be true for an optimization to be applicable at all.
 *
 * <p>Separate from {@link Conflict}: a conflict is "this and that fight", whereas
 * compatibility is "this cannot even apply here". A mod for a different Minecraft version
 * is not conflicting with anything; it simply has nothing to attach to.
 */
public final class Compatibility {

    private final Set<String> minecraftVersions;
    private final Set<String> loaders;
    private final int minimumJavaMajor;
    private final boolean requiresOpenGl21;

    private Compatibility(Builder builder) {
        this.minecraftVersions = Collections.unmodifiableSet(
                new LinkedHashSet<String>(builder.minecraftVersions));
        this.loaders = Collections.unmodifiableSet(new LinkedHashSet<String>(builder.loaders));
        this.minimumJavaMajor = builder.minimumJavaMajor;
        this.requiresOpenGl21 = builder.requiresOpenGl21;
    }

    /**
     * The baseline this project targets: 1.8.9 on Legacy Fabric or Forge, Java 8.
     * The Forge build is the same classes renamed (docs/forge.md), so an
     * optimization runs on both unless its entry narrows it to one.
     *
     * <p>Java 8 is not a preference, it is what the version manifest declares
     * ({@code javaVersion.majorVersion = 8}). {@code performance.md} §5.1 is explicit that
     * most modern JVM tuning advice does not apply here, which is why the floor is stated
     * rather than assumed.
     */
    public static Compatibility baseline() {
        return builder().minecraft("1.8.9").loader("legacy-fabric").loader("forge").java(8).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Set<String> minecraftVersions() {
        return minecraftVersions;
    }

    public Set<String> loaders() {
        return loaders;
    }

    public int minimumJavaMajor() {
        return minimumJavaMajor;
    }

    public boolean requiresOpenGl21() {
        return requiresOpenGl21;
    }

    /** Whether this optimization could apply to the given environment. */
    public boolean applies(String minecraftVersion, String loader, int javaMajor) {
        return minecraftVersions.contains(minecraftVersion)
                && loaders.contains(loader)
                && javaMajor >= minimumJavaMajor;
    }

    /** Why it does not apply, or {@code null} when it does. */
    public String whyNotApplicable(String minecraftVersion, String loader, int javaMajor) {
        if (!minecraftVersions.contains(minecraftVersion)) {
            return "built for " + String.join(", ", minecraftVersions)
                    + ", not " + minecraftVersion;
        }
        if (!loaders.contains(loader)) {
            return "built for " + String.join(", ", loaders) + ", not " + loader;
        }
        if (javaMajor < minimumJavaMajor) {
            return "needs Java " + minimumJavaMajor + " or newer; running on " + javaMajor;
        }
        return null;
    }

    public static final class Builder {
        private final Set<String> minecraftVersions = new LinkedHashSet<String>();
        private final Set<String> loaders = new LinkedHashSet<String>();
        private int minimumJavaMajor = 8;
        private boolean requiresOpenGl21;

        public Builder minecraft(String version) {
            minecraftVersions.add(Objects.requireNonNull(version));
            return this;
        }

        public Builder loader(String loader) {
            loaders.add(Objects.requireNonNull(loader));
            return this;
        }

        public Builder java(int major) {
            this.minimumJavaMajor = major;
            return this;
        }

        public Builder requiresOpenGl21() {
            this.requiresOpenGl21 = true;
            return this;
        }

        public Compatibility build() {
            if (minecraftVersions.isEmpty() || loaders.isEmpty()) {
                throw new IllegalStateException(
                        "compatibility must name at least one Minecraft version and one loader");
            }
            return new Compatibility(this);
        }
    }
}
