package net.fathom.optimizations.core;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fathom.optimizations.core.ui.render.Layer;
import net.fathom.optimizations.core.ui.render.LayerRecorder;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.core.ui.style.Elevation;
import net.fathom.optimizations.core.ui.style.Material;
import net.fathom.optimizations.core.ui.text.FontAtlas;

/**
 * The interface compositor's model, without a GPU: how a frame's primitives
 * are stacked and batched, and what its text measures.
 *
 * <ul>
 *   <li>Layer order decides stacking; recording order only decides it
 *       within a layer.
 *   <li>A draw call breaks only where two primitives need different
 *       textures, and a steady frame records into the same arrays.
 *   <li>The shipped glyph atlas measures text exactly as Chromium does --
 *       the launcher's WebView -- with its advances, GPOS kerning and
 *       standard ligatures: {@code text-metrics.json} is Chromium's own
 *       measurement of the same strings ({@code desktop/proof/metrics.html}).
 *   <li>The materials are the launcher's, value for value, read from its
 *       generated CSS.
 * </ul>
 */
public final class RendererTests {

    private static int passed;

    public static void main(String[] args) throws Exception {
        layersDecideStackingNotCallOrder();
        drawCallsBreakOnlyOnTextureChanges();
        aSteadyFrameRecordsIntoTheSameArrays();
        theAtlasCarriesGeistsOwnMetrics();
        textMeasuresAsChromiumShapesIt();
        ligaturesFollowCss();
        trackingIsPartOfAType();
        materialsAreTheLaunchersCss();
        rowsPlaceChildrenFromPaddingsAndGaps();
        growSharesWhatIsLeft();
        justifyAndAlign();
        aScrollViewportClampsItsOffset();
        floatingBoxesStayOnScreen();
        anUnchangedTreeIsNotLaidOutAgain();

        System.out.println();
        System.out.println(passed + " passed, 0 failed");
    }

    // -----------------------------------------------------------------------
    // Layers and batching
    // -----------------------------------------------------------------------

    private static void record(LayerRecorder recorder, int layer, int texture, int marker) {
        int[] data = recorder.begin(layer, texture);
        data[recorder.offset()] = marker;
    }

    static void layersDecideStackingNotCallOrder() {
        LayerRecorder recorder = new LayerRecorder(1);
        // A popover recorded first, then the card it must cover, then a tooltip.
        record(recorder, Layer.POPOVER, LayerRecorder.ANY, 1);
        record(recorder, Layer.SURFACE, LayerRecorder.ANY, 2);
        record(recorder, Layer.TOOLTIP, LayerRecorder.ANY, 3);
        record(recorder, Layer.SURFACE, LayerRecorder.ANY, 4);
        StringBuilder order = new StringBuilder();
        for (int index = 0; index < Layer.COUNT; index++) {
            for (int at = 0; at < recorder.used(index); at++) {
                order.append(recorder.data(index)[at]);
            }
        }
        check("2413".equals(order.toString()), "submitted in layer order, then call order: " + order);
        check(Layer.index(Layer.BACKGROUND) < Layer.index(Layer.BACKDROP)
                && Layer.index(Layer.BACKDROP) < Layer.index(Layer.SURFACE)
                && Layer.index(Layer.MODAL) < Layer.index(Layer.DEBUG), "the layers' order");
        pass("layers decide stacking, not call order");
    }

    static void drawCallsBreakOnlyOnTextureChanges() {
        LayerRecorder recorder = new LayerRecorder(1);
        int s = Layer.SURFACE;
        record(recorder, s, LayerRecorder.ANY, 0); // shape
        record(recorder, s, 5, 0);                 // glass: takes the run
        record(recorder, s, LayerRecorder.ANY, 0); // text
        record(recorder, s, 5, 0);                 // glass again: same texture
        record(recorder, s, 7, 0);                 // an image: breaks
        record(recorder, s, LayerRecorder.ANY, 0); // joins the image's run
        int index = Layer.index(s);
        check(recorder.commands(index) == 2, "two draw calls, got " + recorder.commands(index));
        check(recorder.commandTexture(index, 0) == 5 && recorder.commandCount(index, 0) == 4, "the first run");
        check(recorder.commandTexture(index, 1) == 7 && recorder.commandStart(index, 1) == 4
                && recorder.commandCount(index, 1) == 2, "the second run");
        record(recorder, Layer.POPOVER, LayerRecorder.ANY, 0);
        check(recorder.drawCalls() == 3, "a second layer is one more call");
        pass("draw calls break only where the texture changes");
    }

    static void aSteadyFrameRecordsIntoTheSameArrays() {
        LayerRecorder recorder = new LayerRecorder(88);
        for (int frame = 0; frame < 3; frame++) {
            recorder.clear();
            for (int quad = 0; quad < 500; quad++) {
                record(recorder, Layer.SURFACE, LayerRecorder.ANY, quad);
            }
        }
        int[] before = recorder.data(Layer.index(Layer.SURFACE));
        recorder.clear();
        for (int quad = 0; quad < 500; quad++) {
            record(recorder, Layer.SURFACE, LayerRecorder.ANY, quad);
        }
        check(recorder.data(Layer.index(Layer.SURFACE)) == before, "the same array, nothing reallocated");
        check(recorder.totalInts() == 500 * 88, "every primitive recorded");
        pass("a steady frame records into the same arrays");
    }

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    private static FontAtlas atlas() throws Exception {
        InputStream stream = RendererTests.class.getResourceAsStream("/assets/fathom/font/atlas/ui.atlas");
        check(stream != null, "the atlas ships with the mod");
        try {
            return FontAtlas.read(stream);
        } finally {
            stream.close();
        }
    }

    static void theAtlasCarriesGeistsOwnMetrics() throws Exception {
        FontAtlas atlas = atlas();
        check(atlas.faces.length == 5, "five faces");
        FontAtlas.Face regular = atlas.face("regular");
        check(close(regular.ascender, 1.005f, 1e-4f) && close(regular.descender, 0.295f, 1e-4f),
                "Geist's typographic ascender and descender: " + regular.ascender + " " + regular.descender);
        check(close(regular.capHeight, 0.710f, 1e-4f) && close(regular.xHeight, 0.530f, 1e-4f),
                "cap height and x-height");
        check(regular.glyph('é') != null && regular.glyph('…') != null, "Latin-1 and punctuation");
        check(regular.glyphOrFallback(0x4E00) == regular.glyph('?'), "a missing character draws as '?'");
        check(regular.kerning('T', 'o') < 0f && regular.kerning('A', 'V') < 0f, "GPOS kerning: To, AV");
        check(regular.kerning(' ', ' ') == 0f && regular.kerningPairs() > 1000, "the font's pair table, and nothing where it has none");
        pass("the atlas carries Geist's own metrics, kerning and coverage");
    }

    static void textMeasuresAsChromiumShapesIt() throws Exception {
        FontAtlas atlas = atlas();
        String json = new String(Files.readAllBytes(new File(RendererTests.class
                .getResource("/text-metrics.json").toURI()).toPath()), StandardCharsets.UTF_8);
        int compared = 0;
        float worst = 0f;
        String worstWhat = "";
        for (FontAtlas.Face face : atlas.faces) {
            Matcher block = Pattern.compile("\"" + Pattern.quote(face.name) + "\":\\s*\\{([^}]*)\\}").matcher(json);
            check(block.find(), "Chromium measured " + face.name);
            Matcher entry = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\":\\s*([0-9.]+)").matcher(block.group(1));
            while (entry.find()) {
                String text = entry.group(1).replace("\\u00b7", "·");
                float chromium = Float.parseFloat(entry.group(2));
                float ours = face.width(text, false, 0f);
                // Per glyph: Medium and SemiBold here are static instances of
                // the variable font the launcher uses, and an instance rounds
                // each interpolated advance to a whole font unit (0.001 em):
                // up to half a unit a glyph. Regular and Mono are exact.
                float error = Math.abs(ours - chromium) / text.length();
                if (error > worst) {
                    worst = error;
                    worstWhat = face.name + " \"" + text + "\" ours " + ours + " chromium " + chromium;
                }
                compared++;
            }
        }
        check(compared >= 60, "compared " + compared + " strings");
        // Half a font unit a glyph, plus Chromium's own 1/64 px at 100px.
        check(worst < 0.0005f + 0.00016f, String.format(Locale.ROOT,
                "within half a font unit a glyph of Chromium; worst %s", worstWhat));
        System.out.printf(Locale.ROOT, "       %d strings, five faces, worst %.6f em a glyph%n", compared, worst);
        pass("text measures as Chromium shapes it (advances, kerning, ligatures)");
    }

    static void ligaturesFollowCss() throws Exception {
        FontAtlas.Face regular = atlas().face("regular");
        check(regular.ligatures() > 0, "Geist's standard ligatures are in the atlas");
        long ff = regular.ligatureAt("office", 1, false);
        check(ff >= 0L && (ff & 0xFF) == 2, "\"ff\" in office");
        check(regular.ligatureAt("OFFICE", 1, true) == ff, "found the same in lowercased text");
        check(regular.ligatureAt("office", 0, false) < 0L, "nothing starts at the o");
        check(regular.ligatureAt("settings", 2, false) >= 0L, "\"tt\" in settings");
        // CSS: no optional ligatures where letter-spacing is set. A spacing too
        // small to measure turns them off and adds nothing.
        float unligated = regular.width("settings", false, 1e-30f);
        float spaced = regular.width("settings", false, 0.01f);
        check(close(spaced, unligated + 8 * 0.01f, 1e-5f), "spacing after each of the eight letters");
        pass("ligatures are applied as a browser applies them, and not under letter-spacing");
    }

    static void trackingIsPartOfAType() {
        Type a = new Type(14f, Type.Weight.MEDIUM, -0.005f);
        Type b = new Type(14f, Type.Weight.MEDIUM);
        check(!a.equals(b) && a.equals(b.tracked(-0.005f)) && a.hashCode() == b.tracked(-0.005f).hashCode(),
                "tracking distinguishes types");
        check(a.at(13f).tracking() == -0.005f && a.weighing(Type.Weight.REGULAR).tracking() == -0.005f,
                "a type keeps its tracking when resized or reweighted");
        pass("letter-spacing is part of a type");
    }

    // -----------------------------------------------------------------------
    // Materials
    // -----------------------------------------------------------------------

    static void materialsAreTheLaunchersCss() throws Exception {
        File css = new File("../../desktop/src/lib/theme.generated.css");
        check(css.isFile(), "the launcher's generated CSS is beside the game: " + css.getAbsolutePath());
        String text = new String(Files.readAllBytes(css.toPath()), StandardCharsets.UTF_8);
        Material[] materials = {Material.CLEAR, Material.LIGHT, Material.STANDARD, Material.DENSE};
        for (Material material : materials) {
            String name = material.name;
            check(material.tint == colour(value(text, "--mat-" + name + "-tint")), name + " tint");
            check(material.blur == Float.parseFloat(value(text, "--mat-" + name + "-blur").replace("px", "")),
                    name + " blur");
            String filter = value(text, "--mat-" + name + "-filter");
            check(filter.contains("saturate(" + trim(material.saturate) + ")")
                    && filter.contains("brightness(" + trim(material.brightness) + ")"), name + " filter: " + filter);
            String rim = value(text, "--mat-" + name + "-rim");
            check(rim.contains(alphaOf(material.rimTop)) && rim.contains(alphaOf(material.rimEdge)),
                    name + " rims: " + rim);
        }
        String md = value(text, "--shadow-md");
        check(md.startsWith("0 " + trim(Elevation.MD.offsetY) + "px " + trim(Elevation.MD.blur) + "px"),
                "the medium elevation: " + md);
        pass("the materials are the launcher's CSS, value for value");
    }

    private static String value(String css, String property) {
        Matcher m = Pattern.compile(Pattern.quote(property) + ":\\s*([^;]+);").matcher(css);
        check(m.find(), property + " is in the CSS");
        return m.group(1).trim();
    }

    private static int colour(String rgba) {
        Matcher m = Pattern.compile("rgba\\((\\d+),\\s*(\\d+),\\s*(\\d+),\\s*([\\d.]+)\\)").matcher(rgba);
        check(m.find(), "an rgba colour: " + rgba);
        int alpha = Math.round(Float.parseFloat(m.group(4)) * 255f);
        return alpha << 24 | Integer.parseInt(m.group(1)) << 16 | Integer.parseInt(m.group(2)) << 8
                | Integer.parseInt(m.group(3));
    }

    private static String alphaOf(int argb) {
        float alpha = (argb >>> 24) / 255f;
        return trim(Math.round(alpha * 100f) / 100f) + ")";
    }

    private static String trim(float value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text;
    }

    // -----------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------

    private static final net.fathom.optimizations.core.ui.render.Renderer UI = new UiTests.FakeRenderer();

    private static void at(net.fathom.optimizations.core.ui.layout.Box box, float x, float y, float w, float h) {
        net.fathom.optimizations.core.ui.layout.Rect r = box.rect();
        check(close(r.x, x, 0.01f) && close(r.y, y, 0.01f) && close(r.width, w, 0.01f) && close(r.height, h, 0.01f),
                box.id + " at " + x + "," + y + " " + w + "x" + h + ", was " + r);
    }

    static void rowsPlaceChildrenFromPaddingsAndGaps() {
        net.fathom.optimizations.core.ui.layout.Box row =
                net.fathom.optimizations.core.ui.layout.Box.row("row").padding(24f, 10f, 12f, 10f).gap(12f);
        net.fathom.optimizations.core.ui.layout.Box a = row.add(net.fathom.optimizations.core.ui.layout.Box.fixed("a", 64f, 14f));
        net.fathom.optimizations.core.ui.layout.Box b = row.add(net.fathom.optimizations.core.ui.layout.Box.fixed("b", 36f, 36f));
        row.layout(UI, 0f, 0f, 400f, 56f);
        // Stretch is the default across: a fixed height stays fixed.
        at(a, 24f, 10f, 64f, 14f);
        at(b, 24f + 64f + 12f, 10f, 36f, 36f);
        net.fathom.optimizations.core.ui.layout.Box column =
                net.fathom.optimizations.core.ui.layout.Box.column("column").padding(6f).gap(4f);
        net.fathom.optimizations.core.ui.layout.Box first = column.add(net.fathom.optimizations.core.ui.layout.Box.fixed("1", 36f, 36f));
        net.fathom.optimizations.core.ui.layout.Box second = column.add(net.fathom.optimizations.core.ui.layout.Box.fixed("2", 36f, 36f));
        column.layout(UI, 12f, 60f, 48f, 300f);
        at(first, 18f, 66f, 36f, 36f);
        at(second, 18f, 106f, 36f, 36f);
        pass("rows and columns place children from paddings and gaps");
    }

    static void growSharesWhatIsLeft() {
        net.fathom.optimizations.core.ui.layout.Box row = net.fathom.optimizations.core.ui.layout.Box.row("row");
        net.fathom.optimizations.core.ui.layout.Box fixed = row.add(net.fathom.optimizations.core.ui.layout.Box.fixed("fixed", 100f, 10f));
        net.fathom.optimizations.core.ui.layout.Box one = row.add(net.fathom.optimizations.core.ui.layout.Box.column("one").width(0f).grow(1f));
        net.fathom.optimizations.core.ui.layout.Box two = row.add(net.fathom.optimizations.core.ui.layout.Box.column("two").width(0f).grow(2f));
        row.layout(UI, 0f, 0f, 400f, 20f);
        at(fixed, 0f, 0f, 100f, 10f);
        at(one, 100f, 0f, 100f, 20f);
        at(two, 200f, 0f, 200f, 20f);
        pass("grow shares what is left, in proportion");
    }

    static void justifyAndAlign() {
        net.fathom.optimizations.core.ui.layout.Box row = net.fathom.optimizations.core.ui.layout.Box.row("row")
                .justify(net.fathom.optimizations.core.ui.layout.Box.Align.BETWEEN)
                .align(net.fathom.optimizations.core.ui.layout.Box.Align.CENTRE);
        net.fathom.optimizations.core.ui.layout.Box a = row.add(net.fathom.optimizations.core.ui.layout.Box.fixed("a", 10f, 10f));
        net.fathom.optimizations.core.ui.layout.Box b = row.add(net.fathom.optimizations.core.ui.layout.Box.fixed("b", 10f, 20f));
        net.fathom.optimizations.core.ui.layout.Box c = row.add(net.fathom.optimizations.core.ui.layout.Box.fixed("c", 10f, 10f));
        row.layout(UI, 0f, 0f, 100f, 40f);
        at(a, 0f, 15f, 10f, 10f);
        at(b, 45f, 10f, 10f, 20f);
        at(c, 90f, 15f, 10f, 10f);
        net.fathom.optimizations.core.ui.layout.Box end = net.fathom.optimizations.core.ui.layout.Box.column("end")
                .padding(104f, 0f, 0f, 40f).justify(net.fathom.optimizations.core.ui.layout.Box.Align.END)
                .align(net.fathom.optimizations.core.ui.layout.Box.Align.START);
        net.fathom.optimizations.core.ui.layout.Box card = end.add(net.fathom.optimizations.core.ui.layout.Box.fixed("card", 440f, 248f));
        end.layout(UI, 0f, 0f, 1280f, 720f);
        at(card, 104f, 432f, 440f, 248f);
        pass("justify and align: between, centre, end -- the launcher's Home card lands at 104,432");
    }

    static void aScrollViewportClampsItsOffset() {
        net.fathom.optimizations.core.ui.layout.Box list =
                net.fathom.optimizations.core.ui.layout.Box.column("list").scroll(true);
        for (int index = 0; index < 8; index++) {
            list.add(net.fathom.optimizations.core.ui.layout.Box.column("row" + index).height(56f));
        }
        list.scrollTo(1000f);
        list.layout(UI, 0f, 0f, 400f, 300f);
        check(close(list.scrollRange(), 8 * 56f - 300f, 0.01f), "the range is the overflow: " + list.scrollRange());
        check(close(list.scrollOffset(), list.scrollRange(), 0.01f), "the offset is clamped to it");
        at(list.find("row0"), 0f, -(8 * 56f - 300f), 400f, 56f);
        check(list.isClip(), "a viewport clips");
        pass("a scroll viewport moves its rows and clamps its offset");
    }

    static void floatingBoxesStayOnScreen() {
        net.fathom.optimizations.core.ui.layout.Box screen = net.fathom.optimizations.core.ui.layout.Box.stack("screen");
        net.fathom.optimizations.core.ui.layout.Box bar = screen.add(net.fathom.optimizations.core.ui.layout.Box.column("bar")
                .padding(1000f, 10f, 12f, 0f).align(net.fathom.optimizations.core.ui.layout.Box.Align.END));
        net.fathom.optimizations.core.ui.layout.Box anchor = bar.add(net.fathom.optimizations.core.ui.layout.Box.fixed("anchor", 110f, 36f));
        net.fathom.optimizations.core.ui.layout.Box panel = screen.addFloating(
                net.fathom.optimizations.core.ui.layout.Box.fixed("panel", 300f, 200f), anchor,
                net.fathom.optimizations.core.ui.layout.Box.Place.BELOW_END, 8f);
        screen.layout(UI, 0f, 0f, 1280f, 720f);
        at(anchor, 1158f, 10f, 110f, 36f);
        at(panel, 1268f - 300f, 54f, 300f, 200f);
        // Near the bottom, below would leave the screen: it goes above instead.
        net.fathom.optimizations.core.ui.layout.Box low = net.fathom.optimizations.core.ui.layout.Box.stack("low");
        net.fathom.optimizations.core.ui.layout.Box lowBar = low.add(net.fathom.optimizations.core.ui.layout.Box.column("lowbar")
                .padding(20f, 650f, 0f, 0f).align(net.fathom.optimizations.core.ui.layout.Box.Align.START));
        net.fathom.optimizations.core.ui.layout.Box lowAnchor = lowBar.add(net.fathom.optimizations.core.ui.layout.Box.fixed("lowanchor", 100f, 36f));
        net.fathom.optimizations.core.ui.layout.Box menu = low.addFloating(
                net.fathom.optimizations.core.ui.layout.Box.fixed("menu", 200f, 160f), lowAnchor,
                net.fathom.optimizations.core.ui.layout.Box.Place.BELOW_START, 6f);
        low.layout(UI, 0f, 0f, 1280f, 720f);
        at(menu, 20f, 650f - 6f - 160f, 200f, 160f);
        pass("floating boxes sit against their anchor, flipped and kept on screen");
    }

    static void anUnchangedTreeIsNotLaidOutAgain() {
        net.fathom.optimizations.core.ui.layout.Box root = net.fathom.optimizations.core.ui.layout.Box.column("root");
        net.fathom.optimizations.core.ui.layout.TextLeaf label =
                net.fathom.optimizations.core.ui.layout.TextLeaf.of("label", new Type(13f, Type.Weight.REGULAR), 16f);
        root.add(label.box());
        label.set("balanced");
        root.layout(UI, 0f, 0f, 400f, 100f);
        int after = root.layouts();
        for (int frame = 0; frame < 100; frame++) {
            label.set("balanced");
            root.layout(UI, 0f, 0f, 400f, 100f);
        }
        check(root.layouts() == after, "a hundred unchanged frames, no layout");
        label.set("maximum fps");
        root.layout(UI, 0f, 0f, 400f, 100f);
        check(root.layouts() == after + 1, "a changed string lays it out once");
        root.layout(UI, 0f, 0f, 500f, 100f);
        check(root.layouts() == after + 2, "so does a changed size");
        pass("an unchanged tree is not laid out again");
    }

    // -----------------------------------------------------------------------

    private static boolean close(float a, float b, float tolerance) {
        return Math.abs(a - b) <= tolerance;
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAILED: " + what);
            System.exit(1);
        }
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  ok  " + name);
    }
}
