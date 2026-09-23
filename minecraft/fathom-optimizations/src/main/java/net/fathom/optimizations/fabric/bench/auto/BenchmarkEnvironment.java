package net.fathom.optimizations.fabric.bench.auto;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.bench.Environment;
import net.fathom.optimizations.fabric.diag.BuildId;
import net.fathom.optimizations.fabric.diag.RuntimeTrace;

/**
 * What the machine and the client were, for the report's header.
 *
 * <h2>Built on the existing capture</h2>
 *
 * <p>{@code Capture.environment} already records resolution, render distance,
 * VBO, VSync, the frame cap, heap, Java and the loader -- everything a
 * comparison must hold constant. This adds what the automated benchmark needs
 * on top: the GPU, whether OptiFine is genuinely loaded, and which Fathom build
 * is running.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>No username, uuid, access token, session, machine id or address. A
 * benchmark report is the sort of file somebody pastes into an issue, and the
 * only defence that works is for the identifying fields never to be collected.
 */
public final class BenchmarkEnvironment {

    private BenchmarkEnvironment() {}

    public static Environment capture(MinecraftClient client) {
        Environment out = net.fathom.optimizations.fabric.bench.Capture.environment(client);

        out.set("gpu", gpu());
        out.set("optifine", RuntimeTrace.optifineActive() ? describeOptiFine() : "not active");
        out.set("optifabric", RuntimeTrace.optifabricActive() ? "active" : "not active");
        out.set("fathomBuild", BuildId.describe());
        out.set("optifineSettings", optifineSettings(client));
        return out;
    }

    private static String describeOptiFine() {
        String version = RuntimeTrace.optifineVersion();
        if (version == null || version.isEmpty()) {
            return "active";
        }
        return "active " + version;
    }

    /**
     * The GL renderer string.
     *
     * <p>Read through LWJGL rather than reflected out of OptiFine, so it works
     * whether or not OptiFine is present. It names the adapter and the driver,
     * both of which change what a frame costs.
     */
    private static String gpu() {
        try {
            String renderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            String version = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
            if (renderer == null) {
                return "unknown";
            }
            return version == null ? renderer : renderer + " (" + version + ")";
        } catch (Throwable unavailable) {
            return "unknown";
        }
    }

    /**
     * The OptiFine settings that cost frames, read from its own options file.
     *
     * <p>From the file rather than through reflection into OptiFine's classes:
     * the field names change between builds and a reflective read that silently
     * returns nothing is worse than reading the file everybody can also read.
     */
    private static String optifineSettings(MinecraftClient client) {
        if (client == null || client.runDirectory == null) {
            return "";
        }
        java.io.File file = new java.io.File(client.runDirectory, "optionsof.txt");
        if (!file.isFile()) {
            return "no optionsof.txt";
        }
        String[] wanted = {
            "ofFastRender", "ofFastMath", "ofSmoothFps", "ofAaLevel", "ofAfLevel",
            "ofDynamicLights", "ofConnectedTextures", "ofBetterGrass",
            "ofCustomEntityModels", "ofRenderRegions", "ofSmartAnimations",
        };
        StringBuilder out = new StringBuilder(160);
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    for (String key : wanted) {
                        if (line.startsWith(key + ":")) {
                            if (out.length() > 0) {
                                out.append(' ');
                            }
                            out.append(line.trim());
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable unreadable) {
            return "unreadable";
        }
        return out.toString();
    }
}
