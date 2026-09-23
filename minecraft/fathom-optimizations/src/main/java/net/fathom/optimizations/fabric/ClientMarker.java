package net.fathom.optimizations.fabric;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.Locale;

import net.fabricmc.loader.api.FabricLoader;

/** Proof for the launcher that this module reached and completed its entrypoint. */
final class ClientMarker {
    static final String RELATIVE_PATH = "fathom/client-loaded.json";

    private ClientMarker() {}

    /**
     * Write only after registries, HUD and public API are installed. A marker
     * therefore means more than "Fabric noticed the jar": Fathom's client
     * entrypoint reached a usable state.
     */
    static void write(File gameDirectory, int features) {
        try {
            String version = FabricLoader.getInstance()
                    .getModContainer("fathom-optimizations")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
            String jarSha1 = codeSourceSha1();
            String json = "{\"schema\":1,\"version\":\"" + json(version)
                    + "\",\"build\":\"" + json(net.fathom.optimizations.fabric.diag.BuildId.commit())
                    + "\",\"jarSha1\":\"" + json(jarSha1)
                    + "\",\"features\":" + Math.max(0, features) + "}";

            Path target = gameDirectory.toPath().resolve(RELATIVE_PATH);
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.write(temporary, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable failure) {
            // The launcher treats a missing marker as a failed Fathom launch.
            // Keep this in the game log as the concrete reason without turning
            // a diagnostic write failure into an unrelated Fabric crash.
            System.err.println("[Fathom] Could not write client load marker: " + failure);
        }
    }

    private static String codeSourceSha1() throws Exception {
        CodeSource source = FathomOptimizationsClient.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IOException("client code source is unavailable");
        }
        URI location = source.getLocation().toURI();
        File file = new File(location);
        if (file.isDirectory()) {
            return "development";
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] buffer = new byte[64 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(40);
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
