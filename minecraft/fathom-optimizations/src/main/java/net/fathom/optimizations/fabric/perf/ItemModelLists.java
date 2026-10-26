package net.fathom.optimizations.fabric.perf;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;

/**
 * {@code render.entity.item-model-lists}: a held item model's vertex data kept and handed
 * to the tessellator in one copy, instead of rebuilt quad by quad every frame.
 *
 * <h2>What vanilla does</h2>
 *
 * <p>Every item an entity holds goes through
 * {@code ItemRenderer.renderBakedItemModel(model, colour, stack)}: begin the tessellator,
 * then for every quad copy its vertex data in, write its colour and its normal, and draw.
 * A held item's generated model (a sprite extruded to 3D) is a few hundred quads.
 *
 * <h2>What this changes</h2>
 *
 * <p>The first time a model is drawn under a key, the vertex data the tessellator holds at
 * its draw is copied out. Later draws begin the tessellator with the same mode and format,
 * put that data back in one copy, and draw through the game's own path, so the upload --
 * vanilla's, OptiFine's or Forge's -- receives identical bytes and issues identical calls.
 *
 * <p>Keeping the vertices on the GPU instead (a display list, then a vertex buffer) was
 * tried first and failed the pixel gate even with byte-identical data: lit pixels came out
 * one or two levels apart in a quarter to a third of comparisons, because the driver
 * converts client-memory vertex data differently from buffer-resident data. That is also
 * why the item-frame model list failed (`multiplayer-2026-10-13.md`).
 *
 * <h2>The key</h2>
 *
 * <p>A quad's vertex colour is the colour asked for, or -- when that is -1 with a stack,
 * for a tinted quad -- the stack's tint. Most generated item models tint their one layer
 * (usually white; dyed leather and potions are not), so each copy is kept per tint as the
 * renderer computes it: the item's colour, then OptiFine's custom colours. A model with
 * two tint indices is drawn live.
 *
 * <h2>When it steps aside</h2>
 *
 * <p>When OptiFine has an emissive variant for any quad (its emissive pass is decided by a
 * flag the per-quad code sets); with OptiFine shaders, multitexture, quads-to-triangles or
 * smart animations (which marks sprites as drawn while quads are copied); in anaglyph mode.
 * A model reload releases everything kept. It runs only inside an entity's held-item layer.
 *
 * <p>With {@code -Dfathom.verify.caches=true}, one replay in 32 is drawn both ways into an
 * offscreen framebuffer and the images compared byte for byte.
 */
public final class ItemModelLists {

    private ItemModelLists() {}

    public static final boolean VERIFY = Boolean.getBoolean("fathom.verify.caches");
    /** Verification control: compare the live draw against itself, to show the check is deterministic. */
    public static final boolean CONTROL = Boolean.getBoolean("fathom.verify.itemControl");

    /** More distinct models than this means something is churning; start over. */
    private static final int CAPACITY = 512;

    /** {@link #key} for a draw that must happen live. */
    public static final long LIVE = Long.MIN_VALUE;

    /** One recorded draw. */
    public static final class Entry {
        final long key;
        final int[] data;
        final VertexFormat format;
        final int mode;
        final int count;
        final Entry next;

        Entry(long key, int[] data, VertexFormat format, int mode, int count, Entry next) {
            this.key = key;
            this.data = data;
            this.format = format;
            this.mode = mode;
            this.count = count;
            this.next = next;
        }
    }

    /** What is known about one model: whether it can be cached, its tint index, its draws. */
    private static final class Model {
        final boolean cacheable;
        /** The one tint index its quads use, or -1 for none. */
        final int tintIndex;
        Entry draws;

        Model(boolean cacheable, int tintIndex) {
            this.cacheable = cacheable;
            this.tintIndex = tintIndex;
        }
    }

    private static final Map<BakedModel, Model> MODELS = new IdentityHashMap<BakedModel, Model>();
    private static int size;

    /** True only while an entity's held item is drawn and the switch is on. */
    public static boolean active;

    /** Set once per frame: OptiFine state that makes every model draw live. */
    private static boolean stepAside;
    private static boolean customColors;

    public static long replays;
    public static long records;
    public static long live;
    public static long verified;
    public static long verifiedDrawn;
    public static long mismatches;
    public static long mismatchesGlint;
    public static long differingBytes;
    public static int maxDiff;
    public static boolean checkGlint;
    public static long bytesCompared;
    public static long bytesDiffered;
    public static String firstByteDiff;
    /** The entry being verified, while its live draw runs, and what it was drawn for. */
    public static Entry checking;
    public static BakedModel checkingModel;
    public static ItemStack checkingStack;
    public static int checkingColour;

    /** Verification: the live draw's bytes against the kept ones. */
    public static void compareBytes(BufferBuilder builder) {
        Entry entry = checking;
        if (entry == null) {
            return;
        }
        bytesCompared++;
        IntBuffer live = ints(builder);
        if (builder.getVertexCount() != entry.count || builder.getFormat() != entry.format) {
            bytesDiffered++;
            if (firstByteDiff == null) {
                firstByteDiff = "count " + builder.getVertexCount() + " vs " + entry.count + ", format "
                        + (builder.getFormat() == entry.format);
            }
            return;
        }
        int stride = entry.format.getVertexSizeInteger();
        for (int at = 0; at < entry.data.length; at++) {
            if (live.get(at) != entry.data[at]) {
                bytesDiffered++;
                if (firstByteDiff == null) {
                    int[] liveData = new int[entry.data.length];
                    live.position(0);
                    live.get(liveData);
                    int[] a = entry.data.clone();
                    java.util.Arrays.sort(a);
                    java.util.Arrays.sort(liveData);
                    firstByteDiff = "vertex " + (at / stride) + " int " + (at % stride) + ": live " + live.get(at)
                            + " kept " + entry.data[at] + "; model " + checkingModel.getClass().getName()
                            + ", colour " + checkingColour + ", stack " + checkingStack + ", vertices " + entry.count
                            + ", same multiset " + java.util.Arrays.equals(a, liveData);
                }
                return;
            }
        }
    }

    /** Once per frame. */
    public static void frame() {
        boolean aside = PerfLayer.shadersActive || flag(MULTI_TEXTURE) || flag(SMART_ANIMATIONS)
                || flag(QUADS_TO_TRIANGLES);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.options != null && client.options.anaglyph3d) {
            aside = true;
        }
        stepAside = aside;
        customColors = flag(CUSTOM_COLORS);
    }

    /**
     * The key a draw of {@code model} in {@code colour} for {@code stack} is cached under, or
     * {@link #LIVE}.
     */
    public static long key(BakedModel model, int colour, ItemStack stack) {
        if (stepAside) {
            return LIVE;
        }
        Model info = MODELS.get(model);
        if (info == null) {
            if (MODELS.size() >= CAPACITY) {
                release();
            }
            info = inspect(model);
            MODELS.put(model, info);
        }
        if (!info.cacheable) {
            return LIVE;
        }
        long tint = 0x100000000L;
        if (colour == -1 && stack != null && info.tintIndex >= 0) {
            int computed = stack.getItem().getDisplayColor(stack, info.tintIndex);
            if (customColors) {
                computed = customColour(stack, info.tintIndex, computed);
            }
            tint = (computed | 0xFF000000) & 0xFFFFFFFFL;
        }
        return ((long) colour << 33) ^ tint;
    }

    /** The draw kept under {@code key}, or null. */
    public static Entry find(BakedModel model, long key) {
        Model info = MODELS.get(model);
        for (Entry at = info == null ? null : info.draws; at != null; at = at.next) {
            if (at.key == key) {
                return at;
            }
        }
        return null;
    }

    /**
     * Keep what {@code builder} is about to draw, before the uploader draws it. The vertex
     * data is copied as it is; the builder is not touched.
     */
    public static void keep(BakedModel model, long key, BufferBuilder builder) {
        Model info = MODELS.get(model);
        int count = builder.getVertexCount();
        if (info == null || count <= 0) {
            return;
        }
        VertexFormat format = builder.getFormat();
        int[] data = new int[count * format.getVertexSizeInteger()];
        ints(builder).get(data);
        info.draws = new Entry(key, data, format, builder.getDrawMode(), count, info.draws);
        size++;
        records++;
    }

    /**
     * The builder's vertex data as ints from the start. Before the tessellator's {@code end()}
     * the byte buffer's position is wherever the last write left it, so the view is taken
     * from a duplicate rewound to 0.
     */
    private static IntBuffer ints(BufferBuilder builder) {
        ByteBuffer source = builder.getByteBuffer().duplicate().order(builder.getByteBuffer().order());
        source.clear();
        return source.asIntBuffer();
    }

    /**
     * Draw a kept model: its vertex data handed to the tessellator in one copy and drawn by
     * the game's own upload path -- vanilla's, OptiFine's or Forge's, whichever is installed.
     */
    public static void draw(Entry entry) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder builder = tessellator.getBuffer();
        builder.begin(entry.mode, entry.format);
        builder.putArray(entry.data);
        tessellator.draw();
    }

    private static Model inspect(BakedModel model) {
        int[] tint = {-1};
        for (Direction direction : Direction.values()) {
            if (!inspect(model.getByDirection(direction), tint)) {
                return new Model(false, -1);
            }
        }
        return new Model(inspect(model.getQuads(), tint), tint[0]);
    }

    private static boolean inspect(List<BakedQuad> quads, int[] tint) {
        if (quads == null) {
            return true;
        }
        for (int at = 0, n = quads.size(); at < n; at++) {
            BakedQuad quad = quads.get(at);
            if (emissive(quad)) {
                return false;
            }
            if (quad.hasColor()) {
                int index = quad.getColorIndex();
                if (tint[0] >= 0 && tint[0] != index) {
                    // Two tint indices: more than one colour per stack (spawn eggs). Drawn live.
                    return false;
                }
                tint[0] = index;
            }
        }
        return true;
    }

    /** Model reload, switch off, or the table full. */
    public static void release() {
        MODELS.clear();
        size = 0;
    }

    public static int size() {
        return size;
    }

    // -- OptiFine -----------------------------------------------------------------

    private static final MethodHandle MULTI_TEXTURE = staticFlag("Config", "isMultiTexture");
    private static final MethodHandle QUADS_TO_TRIANGLES = staticFlag("Config", "isQuadsToTriangles");
    private static final MethodHandle SMART_ANIMATIONS = staticFlag("net.optifine.SmartAnimations", "isActive");
    private static final MethodHandle CUSTOM_COLORS = staticFlag("Config", "isCustomColors");
    private static final MethodHandle CUSTOM_COLOUR = customColourHandle();
    private static final Method QUAD_EMISSIVE = quadEmissive();

    private static MethodHandle staticFlag(String owner, String name) {
        try {
            return MethodHandles.publicLookup().findStatic(Class.forName(owner), name,
                    MethodType.methodType(boolean.class));
        } catch (Throwable absent) {
            return null;
        }
    }

    private static boolean flag(MethodHandle handle) {
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invokeExact();
        } catch (Throwable failure) {
            return true;
        }
    }

    private static MethodHandle customColourHandle() {
        try {
            return MethodHandles.publicLookup().findStatic(Class.forName("net.optifine.CustomColors"),
                    "getColorFromItemStack", MethodType.methodType(int.class, ItemStack.class, int.class, int.class));
        } catch (Throwable absent) {
            return null;
        }
    }

    private static int customColour(ItemStack stack, int index, int colour) {
        if (CUSTOM_COLOUR == null) {
            return colour;
        }
        try {
            return (int) CUSTOM_COLOUR.invokeExact(stack, index, colour);
        } catch (Throwable failure) {
            return colour;
        }
    }

    private static Method quadEmissive() {
        try {
            return BakedQuad.class.getMethod("getQuadEmissive");
        } catch (Throwable absent) {
            return null;
        }
    }

    private static boolean emissive(BakedQuad quad) {
        if (QUAD_EMISSIVE == null) {
            return false;
        }
        try {
            return QUAD_EMISSIVE.invoke(quad) != null;
        } catch (Throwable failure) {
            return true;
        }
    }

    // -- verification -------------------------------------------------------------

    private static Framebuffer target;
    private static ByteBuffer replayed;
    private static ByteBuffer drawnLive;
    private static int checkTexture;

    public static boolean dueForCheck() {
        return VERIFY && (replays & 31) == 31;
    }

    /** Draw {@code entry} offscreen; the caller then draws live offscreen and calls {@link #compareLive}. */
    public static void beginCheck(Entry entry) {
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.width;
        int height = client.height;
        if (target == null || target.textureWidth != width || target.textureHeight != height) {
            if (target != null) {
                target.delete();
            }
            target = new Framebuffer(width, height, true);
            replayed = BufferUtils.createByteBuffer(width * height * 4);
            drawnLive = BufferUtils.createByteBuffer(width * height * 4);
        }
        int texture = GlStateCache.boundTexture();
        clearAndBind(texture);
        if (entry != null) {
            draw(entry);
            read(replayed, width, height);
        }
        clearAndBind(texture);
        checkTexture = texture;
    }

    /** Control only: what the first live draw left in the target becomes the reference. */
    public static void controlFirstDrawn() {
        MinecraftClient client = MinecraftClient.getInstance();
        read(replayed, client.width, client.height);
        clearAndBind(checkTexture);
    }

    /** After the live draw went into the offscreen target: compare, and return to the main framebuffer. */
    public static void compareLive() {
        MinecraftClient client = MinecraftClient.getInstance();
        read(drawnLive, client.width, client.height);
        verified++;
        if (!replayed.equals(drawnLive)) {
            mismatches++;
            if (checkGlint) {
                mismatchesGlint++;
            }
            for (int at = 0, end = drawnLive.limit(); at < end; at++) {
                int diff = Math.abs((replayed.get(at) & 0xFF) - (drawnLive.get(at) & 0xFF));
                if (diff != 0) {
                    differingBytes++;
                    maxDiff = Math.max(maxDiff, diff);
                }
            }
        }
        for (int at = 0, end = drawnLive.limit(); at < end; at += 4) {
            if (drawnLive.getInt(at) != 0) {
                verifiedDrawn++;
                break;
            }
        }
        client.getFramebuffer().bind(true);
        if (checkTexture >= 0) {
            GlStateManager.bindTexture(checkTexture);
        }
    }

    private static void clearAndBind(int texture) {
        target.setClearColor(0f, 0f, 0f, 0f);
        target.clear();
        target.bind(true);
        if (texture >= 0) {
            GlStateManager.bindTexture(texture);
        }
    }

    private static void read(ByteBuffer into, int width, int height) {
        into.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, into);
        into.rewind();
    }
}
