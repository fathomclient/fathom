package net.fathom.optimizations.core.ui.render;

import java.util.Arrays;

/**
 * A frame's primitives, sorted by {@link Layer} as they are recorded, and the
 * draw calls that will submit them.
 *
 * <p>Each layer keeps its own array of vertex data -- {@code ints} ints per
 * primitive, opaque here -- and its own list of draw commands. A command is a
 * run of consecutive primitives that can share one draw call: every primitive
 * names the one per-draw texture it needs, or {@link #ANY} when it needs none
 * (shapes, text and icons sample textures every draw has bound), and a
 * command breaks only where two primitives need different ones.
 *
 * <p>Submission order is layer order, then recording order within a layer.
 * That is the whole stacking model: nothing depends on which component drew
 * first unless both are in the same layer.
 *
 * <p>Arrays grow by doubling and are reused frame after frame; a steady frame
 * allocates nothing. No GL here, so the model is tested on its own.
 */
public final class LayerRecorder {

    /** A primitive that needs no per-draw texture. */
    public static final int ANY = -1;

    private final int ints;
    private final int[][] data = new int[Layer.COUNT][];
    private final int[] used = new int[Layer.COUNT];
    private final int[][] commandTexture = new int[Layer.COUNT][];
    private final int[][] commandStart = new int[Layer.COUNT][];
    private final int[][] commandCount = new int[Layer.COUNT][];
    private final int[] commands = new int[Layer.COUNT];

    /** @param intsPerPrimitive the size of one primitive's vertex data */
    public LayerRecorder(int intsPerPrimitive) {
        this.ints = intsPerPrimitive;
        for (int index = 0; index < Layer.COUNT; index++) {
            data[index] = new int[intsPerPrimitive * 64];
            commandTexture[index] = new int[16];
            commandStart[index] = new int[16];
            commandCount[index] = new int[16];
        }
    }

    /** Forget the last frame, keeping the arrays. */
    public void clear() {
        Arrays.fill(used, 0);
        Arrays.fill(commands, 0);
    }

    /**
     * Make room for one primitive in {@code layer} needing {@code texture},
     * and return the array and offset to write its {@code ints} ints at.
     * Call {@link #offset} for the offset; the array is {@link #array}.
     */
    public int[] begin(int layer, int texture) {
        int index = Layer.index(layer);
        int count = commands[index];
        if (count == 0 || (texture != ANY && commandTexture[index][count - 1] != ANY
                && commandTexture[index][count - 1] != texture)) {
            if (count == commandTexture[index].length) {
                commandTexture[index] = Arrays.copyOf(commandTexture[index], count * 2);
                commandStart[index] = Arrays.copyOf(commandStart[index], count * 2);
                commandCount[index] = Arrays.copyOf(commandCount[index], count * 2);
            }
            commandTexture[index][count] = texture;
            commandStart[index][count] = used[index] / ints;
            commandCount[index][count] = 0;
            commands[index] = ++count;
        } else if (texture != ANY && commandTexture[index][count - 1] == ANY) {
            // The run so far needed no texture; it can share this one's.
            commandTexture[index][count - 1] = texture;
        }
        commandCount[index][count - 1]++;
        if (used[index] + ints > data[index].length) {
            data[index] = Arrays.copyOf(data[index], data[index].length * 2);
        }
        int offset = used[index];
        used[index] += ints;
        lastOffset = offset;
        return data[index];
    }

    private int lastOffset;

    /** Where {@link #begin} said to write. */
    public int offset() {
        return lastOffset;
    }

    /** Ints recorded in every layer together. */
    public int totalInts() {
        int total = 0;
        for (int index = 0; index < Layer.COUNT; index++) {
            total += used[index];
        }
        return total;
    }

    /** Primitives recorded in the layer with slot {@code index}. */
    public int primitives(int index) {
        return used[index] / ints;
    }

    public int[] data(int index) {
        return data[index];
    }

    public int used(int index) {
        return used[index];
    }

    public int commands(int index) {
        return commands[index];
    }

    public int commandTexture(int index, int command) {
        return commandTexture[index][command];
    }

    /** The command's first primitive, counted within its layer. */
    public int commandStart(int index, int command) {
        return commandStart[index][command];
    }

    public int commandCount(int index, int command) {
        return commandCount[index][command];
    }

    /** Draw calls the frame will take: the commands of every layer. */
    public int drawCalls() {
        int total = 0;
        for (int index = 0; index < Layer.COUNT; index++) {
            total += commands[index];
        }
        return total;
    }
}
