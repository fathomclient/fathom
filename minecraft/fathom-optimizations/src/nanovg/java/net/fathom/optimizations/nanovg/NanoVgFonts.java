package net.fathom.optimizations.nanovg;

import static org.lwjgl.nanovg.NanoVG.nvgCreateFontMem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import net.fathom.optimizations.core.ui.render.FontFaces;
import net.fathom.optimizations.core.ui.render.Type;

/**
 * The fonts of one NanoVG context: loaded once, resolved per weight, never
 * reloaded.
 *
 * <p>Each weight in {@link FontFaces} is read from the classpath into a direct
 * buffer that lives as long as the context (NanoVG is told not to copy or free
 * it). A weight whose file is missing or refused is resolved to the nearest
 * weight that did load, and said so once in the log. If none loaded,
 * {@link #available} is false and the renderer draws no text rather than
 * crashing a frame -- a menu without labels is a bug report, a native crash in
 * fontstash is an unexplained game exit.
 */
final class NanoVgFonts {

    private final int[] ids = new int[Type.Weight.values().length];
    /** Kept reachable: NanoVG reads glyph data straight out of these. */
    private final ByteBuffer[] data = new ByteBuffer[Type.Weight.values().length];
    private boolean available;

    NanoVgFonts(long vg) {
        Type.Weight[] weights = Type.Weight.values();
        for (Type.Weight weight : weights) {
            ids[weight.ordinal()] = load(vg, weight);
        }
        for (Type.Weight weight : weights) {
            if (ids[weight.ordinal()] >= 0) {
                available = true;
                continue;
            }
            for (Type.Weight substitute : FontFaces.fallbackOrder(weight)) {
                if (ids[substitute.ordinal()] >= 0) {
                    ids[weight.ordinal()] = ids[substitute.ordinal()];
                    System.err.println("[Fathom] NanoVG: " + weight + " font unavailable, using "
                            + substitute);
                    break;
                }
            }
        }
        if (!available) {
            System.err.println("[Fathom] NanoVG: no font could be loaded from " + FontFaces.ROOT
                    + "; text will not be drawn");
        }
    }

    boolean available() {
        return available;
    }

    /** The face for a weight, or -1 when there is none at all. */
    int id(Type.Weight weight) {
        return ids[weight.ordinal()];
    }

    private int load(long vg, Type.Weight weight) {
        String resource = FontFaces.resource(weight);
        byte[] bytes = read(resource);
        if (bytes == null) {
            System.err.println("[Fathom] NanoVG: missing font " + resource);
            return -1;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes).flip();
        int id = nvgCreateFontMem(vg, weight.name(), buffer, false);
        if (id < 0) {
            System.err.println("[Fathom] NanoVG: font refused " + resource);
            return -1;
        }
        data[weight.ordinal()] = buffer;
        return id;
    }

    private static byte[] read(String resource) {
        InputStream stream = NanoVgFonts.class.getResourceAsStream(resource);
        if (stream == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        } catch (IOException failure) {
            System.err.println("[Fathom] NanoVG: could not read " + resource + ": " + failure);
            return null;
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Nothing useful to do with a failure to close a resource stream.
            }
        }
    }
}
