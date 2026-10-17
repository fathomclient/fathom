package net.fathom.optimizations.fabric.diag;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import com.mojang.blaze3d.platform.GlStateManager;

/**
 * What a piece of drawing changed about the GL state it was handed, and whether
 * it left {@code GlStateManager}'s cache telling a different story from GL.
 *
 * <h2>The two ways a HUD pass leaks</h2>
 *
 * <ul>
 *   <li><b>State left changed.</b> A buffer still bound, a matrix still pushed,
 *       a client array still enabled: the next thing vanilla draws inherits it.
 *       {@link #snapshot} reads the state a HUD pass can plausibly touch, raw,
 *       from GL, so the before and after can be compared.
 *   <li><b>A cache that lies.</b> {@code GlStateManager} skips calls it thinks
 *       redundant. A raw GL call it did not see leaves it believing, say, blending
 *       is off when it is on, and the next {@code disableBlend()} is skipped.
 *       {@link #desync} compares every cached capability, the bound texture and
 *       the current colour with GL.
 * </ul>
 *
 * <p>Development and QA only: reading GL state stalls the pipeline. The cache is
 * found by its shape rather than its field names, which differ between the
 * development and the production mappings.
 */
public final class GlLeakCheck {

    private static final IntBuffer INTS = BufferUtils.createIntBuffer(16);
    private static final FloatBuffer FLOATS = BufferUtils.createFloatBuffer(16);
    private static final ByteBuffer BYTES = BufferUtils.createByteBuffer(16);

    private GlLeakCheck() {}

    /** The state a HUD pass could leave changed, read from GL. */
    public static Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        ContextCapabilities caps = GLContext.getCapabilities();
        integer(out, "array buffer", GL15.GL_ARRAY_BUFFER_BINDING);
        integer(out, "element buffer", GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        if (caps.OpenGL30) {
            integer(out, "vertex array", GL30.GL_VERTEX_ARRAY_BINDING);
            integer(out, "framebuffer", GL30.GL_FRAMEBUFFER_BINDING);
        }
        if (caps.OpenGL20) {
            integer(out, "program", GL20.GL_CURRENT_PROGRAM);
        }
        integer(out, "active texture", GL13.GL_ACTIVE_TEXTURE);
        integer(out, "matrix mode", GL11.GL_MATRIX_MODE);
        integer(out, "modelview depth", GL11.GL_MODELVIEW_STACK_DEPTH);
        integer(out, "projection depth", GL11.GL_PROJECTION_STACK_DEPTH);
        integer(out, "texture matrix depth", GL11.GL_TEXTURE_STACK_DEPTH);
        integer(out, "blend equation", GL14.GL_BLEND_EQUATION);
        enabled(out, "vertex array client state", GL11.GL_VERTEX_ARRAY);
        enabled(out, "colour array client state", GL11.GL_COLOR_ARRAY);
        enabled(out, "texcoord array client state", GL11.GL_TEXTURE_COORD_ARRAY);
        enabled(out, "normal array client state", GL11.GL_NORMAL_ARRAY);
        enabled(out, "scissor", GL11.GL_SCISSOR_TEST);
        enabled(out, "stencil", GL11.GL_STENCIL_TEST);
        enabled(out, "depth test", GL11.GL_DEPTH_TEST);
        enabled(out, "cull", GL11.GL_CULL_FACE);
        enabled(out, "lighting", GL11.GL_LIGHTING);
        enabled(out, "fog", GL11.GL_FOG);
        BYTES.clear();
        GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK, BYTES);
        out.put("depth mask", String.valueOf(BYTES.get(0)));
        BYTES.clear();
        GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, BYTES);
        out.put("colour mask", BYTES.get(0) + "," + BYTES.get(1) + "," + BYTES.get(2) + "," + BYTES.get(3));
        INTS.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, INTS);
        out.put("viewport", INTS.get(0) + "," + INTS.get(1) + "," + INTS.get(2) + "," + INTS.get(3));
        return out;
    }

    /** Keys whose value differs between two snapshots, with both values. */
    public static List<String> diff(Map<String, String> before, Map<String, String> after) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> entry : before.entrySet()) {
            String now = after.get(entry.getKey());
            if (now != null && !now.equals(entry.getValue())) {
                out.add(entry.getKey() + " " + entry.getValue() + " -> " + now);
            }
        }
        return out;
    }

    /**
     * Every place {@code GlStateManager}'s cache disagrees with GL right now.
     *
     * @return descriptions, or a single "cache not found" note when the class
     *     has a shape this does not recognise
     */
    public static List<String> desync() {
        List<String> out = new ArrayList<String>();
        int found = 0;
        try {
            for (Field field : GlStateManager.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                if (value == null) {
                    continue;
                }
                if (value.getClass().isArray()) {
                    Object[] states = (Object[]) value;
                    for (int index = 0; index < states.length; index++) {
                        found += checkState(states[index], field.getName() + "[" + index + "]", index, out);
                    }
                } else {
                    found += checkState(value, field.getName(), -1, out);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            out.add("cache unreadable: " + unreadable);
        }
        if (found == 0) {
            out.add("cache not found");
        }
        return out;
    }

    /**
     * One cached state object. A capability (an int and a boolean) is compared
     * with {@code glIsEnabled}; a state holding one ({@code capState}) is
     * searched for it; a texture unit (a capability and an int) also has its
     * bound texture compared; the colour (four floats) with the current colour.
     */
    private static int checkState(Object state, String name, int unit, List<String> out)
            throws IllegalAccessException {
        if (state == null) {
            return 0;
        }
        Field[] fields = state.getClass().getDeclaredFields();
        Integer capability = null;
        Boolean enabled = null;
        int floats = 0;
        float[] colour = new float[4];
        Object nested = null;
        Integer intValue = null;
        int ints = 0;
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == int.class) {
                ints++;
                intValue = Integer.valueOf(field.getInt(state));
                if (capability == null) {
                    capability = intValue;
                }
            } else if (type == boolean.class) {
                enabled = Boolean.valueOf(field.getBoolean(state));
            } else if (type == float.class && floats < 4) {
                colour[floats++] = field.getFloat(state);
            } else if (!type.isPrimitive() && !type.isArray()) {
                nested = field.get(state);
            }
        }
        if (ints == 1 && enabled != null && nested == null && floats == 0) {
            // A capability: the int is the GL enum.
            boolean actual = GL11.glIsEnabled(capability.intValue());
            if (actual != enabled.booleanValue()) {
                out.add(name + " cached " + enabled + " but GL has " + actual);
            }
            return 1;
        }
        if (floats == 4 && ints == 0 && nested == null && name.indexOf('.') < 0) {
            // The current colour, at the top level only: the clear colour has the same shape.
            FLOATS.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, FLOATS);
            for (int index = 0; index < 4; index++) {
                if (Math.abs(FLOATS.get(index) - colour[index]) > 0.002f) {
                    out.add(name + " cached colour " + colour[0] + "," + colour[1] + "," + colour[2] + ","
                            + colour[3] + " but GL has " + FLOATS.get(0) + "," + FLOATS.get(1) + ","
                            + FLOATS.get(2) + "," + FLOATS.get(3));
                    break;
                }
            }
            return 1;
        }
        int counted = 0;
        if (unit >= 0 && ints == 1 && nested != null && intValue != null) {
            // A texture unit: its enable bit and its bound texture are per unit,
            // so both are read with that unit active.
            int previous = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            counted += checkState(nested, name + ".cap", -1, out);
            int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(previous);
            if (bound != intValue.intValue()) {
                out.add(name + " cached texture " + intValue + " but GL has " + bound);
            }
            return counted + 1;
        }
        if (nested != null) {
            counted += checkState(nested, name + ".cap", -1, out);
        }
        return counted;
    }

    private static void integer(Map<String, String> out, String name, int what) {
        out.put(name, String.valueOf(GL11.glGetInteger(what)));
    }

    private static void enabled(Map<String, String> out, String name, int capability) {
        out.put(name, String.valueOf(GL11.glIsEnabled(capability)));
    }
}
