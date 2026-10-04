package net.fathom.optimizations.fabric.gfx;

import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * The compositor's two programs, GLSL 1.20 (OpenGL 2.1, which every driver
 * the game runs on has).
 *
 * <h2>The primitive program</h2>
 *
 * <p>Everything the interface draws is one quad, and what the quad shows is
 * decided per pixel from its attributes. A rounded rectangle is not corner
 * masks stamped round a centre: the fragment shader evaluates the exact
 * signed distance to the rounded box -- a quarter circle is a quarter circle
 * at any radius, any size, any position, any zoom -- and turns it into
 * coverage over one device pixel. A border is the same distance moved in by
 * its width, so it is one width all the way round, corners included. Text is
 * the median of a multi-channel distance field. Shadows are the rounded box
 * convolved with a Gaussian, integrated analytically. Every primitive carries
 * its own clip rectangle, anti-aliased, so a clip can never leak into the
 * next thing drawn and nothing has to flush to change it.
 *
 * <p>Kinds ({@code a_params.y}): 0 shape, 1 text, 2 mask (icons), 3 image,
 * 4 glass, 5 shadow, 6 bloom, 7 image mask (the wordmark).
 *
 * <h2>The blur program</h2>
 *
 * <p>One axis of a separable Gaussian, with linearly interpolated taps (two
 * texels per fetch), for the backdrop. See {@link SdfBackdrop}.
 */
final class SdfShaders {

    static final int A_POS = 0;
    static final int A_UV = 1;
    static final int A_LOCAL = 2;
    static final int A_HALF = 3;
    static final int A_RADII = 4;
    static final int A_PARAMS = 5;
    static final int A_CLIP = 6;
    static final int A_FILL = 7;
    static final int A_BORDER = 8;

    static final float KIND_SHAPE = 0f;
    static final float KIND_TEXT = 1f;
    static final float KIND_MASK = 2f;
    static final float KIND_IMAGE = 3f;
    static final float KIND_GLASS = 4f;
    static final float KIND_SHADOW = 5f;
    static final float KIND_BLOOM = 6f;
    static final float KIND_IMAGE_MASK = 7f;

    /** Taps per side the blur program can take; enough for sigma 10 at its resolution. */
    static final int BLUR_TAPS = 16;

    private static final String VERTEX = ""
            + "#version 120\n"
            + "uniform vec2 u_viewport;\n"
            + "attribute vec2 a_pos;\n"
            + "attribute vec2 a_uv;\n"
            + "attribute vec2 a_local;\n"
            + "attribute vec2 a_half;\n"
            + "attribute vec4 a_radii;\n"
            + "attribute vec4 a_params;\n"
            + "attribute vec4 a_clip;\n"
            + "attribute vec4 a_fill;\n"
            + "attribute vec4 a_border;\n"
            + "varying vec2 v_uv;\n"
            + "varying vec2 v_local;\n"
            + "varying vec2 v_half;\n"
            + "varying vec4 v_radii;\n"
            + "varying vec4 v_params;\n"
            + "varying vec4 v_clip;\n"
            + "varying vec4 v_fill;\n"
            + "varying vec4 v_border;\n"
            + "void main() {\n"
            // Device pixels, origin top left, to clip space.
            + "  gl_Position = vec4(a_pos.x / u_viewport.x * 2.0 - 1.0, 1.0 - a_pos.y / u_viewport.y * 2.0, 0.0, 1.0);\n"
            + "  v_uv = a_uv;\n"
            + "  v_local = a_local;\n"
            + "  v_half = a_half;\n"
            + "  v_radii = a_radii;\n"
            + "  v_params = a_params;\n"
            + "  v_clip = a_clip;\n"
            + "  v_fill = a_fill;\n"
            + "  v_border = a_border;\n"
            + "}\n";

    private static final String FRAGMENT = ""
            + "#version 120\n"
            + "uniform sampler2D u_atlas;\n"
            + "uniform sampler2D u_masks;\n"
            + "uniform sampler2D u_image;\n"
            + "uniform vec2 u_viewport;\n"
            + "uniform float u_textGamma;\n"
            + "uniform float u_trueSlack;\n"
            + "uniform float u_textBoost;\n"
            + "varying vec2 v_uv;\n"
            + "varying vec2 v_local;\n"
            + "varying vec2 v_half;\n"
            + "varying vec4 v_radii;\n"
            + "varying vec4 v_params;\n"
            + "varying vec4 v_clip;\n"
            + "varying vec4 v_fill;\n"
            + "varying vec4 v_border;\n"
            // Exact signed distance to a rounded box, a radius per corner
            // (top left, top right, bottom right, bottom left; y down).
            + "float roundBox(vec2 p, vec2 b, vec4 r) {\n"
            + "  float rad = p.x > 0.0 ? (p.y > 0.0 ? r.z : r.y) : (p.y > 0.0 ? r.w : r.x);\n"
            + "  rad = min(rad, min(b.x, b.y));\n"
            + "  vec2 q = abs(p) - b + rad;\n"
            + "  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;\n"
            + "}\n"
            // Coverage of a pixel by the inside of a distance (box filter, one device pixel).
            + "float cover(float d) {\n"
            + "  return clamp(0.5 - d, 0.0, 1.0);\n"
            + "}\n"
            + "vec4 premul(vec4 c) {\n"
            + "  return vec4(c.rgb * c.a, c.a);\n"
            + "}\n"
            // Evan Wallace's analytic shadow of a rounded box: exact along x
            // (erf), four samples of the Gaussian along y.
            + "vec2 erf2(vec2 x) {\n"
            + "  vec2 s = sign(x);\n"
            + "  vec2 a = abs(x);\n"
            + "  x = 1.0 + (0.278393 + (0.230389 + 0.078108 * (a * a)) * a) * a;\n"
            + "  x *= x;\n"
            + "  return s - s / (x * x);\n"
            + "}\n"
            + "float gauss(float x, float sigma) {\n"
            + "  return exp(-(x * x) / (2.0 * sigma * sigma)) / (2.50662827 * sigma);\n"
            + "}\n"
            + "float shadowX(float x, float y, float sigma, float corner, vec2 hb) {\n"
            + "  float delta = min(hb.y - corner - abs(y), 0.0);\n"
            + "  float curved = hb.x - corner + sqrt(max(0.0, corner * corner - delta * delta));\n"
            + "  vec2 integral = 0.5 + 0.5 * erf2((x + vec2(-curved, curved)) * (0.70710678 / sigma));\n"
            + "  return integral.y - integral.x;\n"
            + "}\n"
            + "float boxShadow(vec2 p, vec2 hb, float sigma, float corner) {\n"
            + "  float low = p.y - hb.y;\n"
            + "  float high = p.y + hb.y;\n"
            + "  float start = clamp(-3.0 * sigma, low, high);\n"
            + "  float end = clamp(3.0 * sigma, low, high);\n"
            + "  float step = (end - start) / 4.0;\n"
            + "  float y = start + step * 0.5;\n"
            + "  float value = 0.0;\n"
            + "  for (int i = 0; i < 4; i++) {\n"
            + "    value += shadowX(p.x, p.y - y, sigma, corner, hb) * gauss(y, sigma) * step;\n"
            + "    y += step;\n"
            + "  }\n"
            + "  return value;\n"
            + "}\n"
            + "void main() {\n"
            + "  vec2 frag = vec2(gl_FragCoord.x, u_viewport.y - gl_FragCoord.y);\n"
            + "  vec2 lo = frag - v_clip.xy;\n"
            + "  vec2 hi = v_clip.zw - frag;\n"
            + "  float clipCover = clamp(min(min(lo.x, hi.x), min(lo.y, hi.y)) + 0.5, 0.0, 1.0);\n"
            + "  float kind = v_params.y;\n"
            + "  vec4 colour;\n"
            + "  if (kind < 0.5) {\n"
            // Shape: CSS's background under the whole border box, and over it
            // the ring between that box and the same box shrunk by the width
            // and moved by v_uv -- a border when v_uv is zero, an inset
            // box-shadow (the launcher's material rims) when it is not.
            + "    float d = roundBox(v_local, v_half, v_radii);\n"
            + "    float outer = cover(d);\n"
            + "    float width = v_params.x;\n"
            + "    vec4 fill = premul(v_fill) * outer;\n"
            + "    if (width > 0.0 || v_uv.x != 0.0 || v_uv.y != 0.0) {\n"
            + "      float inner = roundBox(v_local - v_uv, v_half - vec2(width), max(v_radii - vec4(width), vec4(0.0)));\n"
            + "      float ring = clamp(outer - cover(inner), 0.0, 1.0);\n"
            + "      vec4 border = premul(v_border) * ring;\n"
            + "      colour = border + fill * (1.0 - border.a);\n"
            + "    } else {\n"
            + "      colour = fill;\n"
            + "    }\n"
            + "  } else if (kind < 1.5) {\n"
            // Text: the median of the field, over a ramp one screen pixel wide.
            + "    vec4 s = texture2D(u_atlas, v_uv);\n"
            // The median keeps corners sharp; clamped to within a texel of the
            // true distance (alpha), so a channel extended along an edge's line
            // far from the glyph can never add ink that is not there.
            + "    float sd = max(min(s.r, s.g), min(max(s.r, s.g), s.b));\n"
            + "    sd = clamp(sd, s.a - u_trueSlack, s.a + u_trueSlack);\n"
            // Near-white text gets a little more weight, as a browser's
            // contrast hook gives light-on-dark text: grey text needs none.
            + "    float luma = dot(v_fill.rgb, vec3(0.2126, 0.7152, 0.0722));\n"
            + "    float boost = u_textBoost * smoothstep(0.75, 0.95, luma);\n"
            + "    float c = clamp((sd - 0.5) * v_params.z + 0.5 + v_params.w + boost, 0.0, 1.0);\n"
            + "    colour = premul(v_fill) * pow(c, u_textGamma);\n"
            + "  } else if (kind < 2.5) {\n"
            + "    colour = premul(v_fill) * texture2D(u_masks, v_uv).a;\n"
            + "  } else if (kind < 3.5) {\n"
            // An image in a rounded rectangle, its corners anti-aliased like any shape.
            + "    vec4 t = texture2D(u_image, v_uv);\n"
            + "    colour = premul(t) * v_fill * cover(roundBox(v_local, v_half, v_radii));\n"
            + "  } else if (kind < 4.5) {\n"
            // Glass: the blurred backdrop at this pixel, adjusted as CSS's
            // saturate() and brightness() would, opaque inside the shape.
            + "    vec3 b = texture2D(u_image, gl_FragCoord.xy / u_viewport).rgb;\n"
            + "    float grey = dot(b, vec3(0.2126, 0.7152, 0.0722));\n"
            + "    b = mix(vec3(grey), b, v_params.z) * v_params.w;\n"
            + "    colour = vec4(b, 1.0) * v_fill.a * cover(roundBox(v_local, v_half, v_radii));\n"
            + "  } else if (kind < 5.5) {\n"
            // Shadow: v_uv is its offset, v_params.z sigma, v_params.w spread;
            // painted outside the element only, as box-shadow is.
            + "    float sigma = max(v_params.z, 0.01);\n"
            + "    vec2 shadowHalf = v_half + v_params.w;\n"
            + "    float corner = min(v_radii.x + v_params.w, min(shadowHalf.x, shadowHalf.y));\n"
            + "    float value = boxShadow(v_local - v_uv, shadowHalf, sigma, max(corner, 0.0));\n"
            + "    float inside = cover(roundBox(v_local, v_half, v_radii));\n"
            + "    colour = premul(v_fill) * value * (1.0 - inside);\n"
            + "  } else if (kind < 6.5) {\n"
            // Bloom: a round pool, quadratic falloff to nothing at the radius.
            + "    float t = clamp(length(v_local) / max(v_half.x, 0.001), 0.0, 1.0);\n"
            + "    float f = 1.0 - t;\n"
            + "    colour = premul(v_fill) * (f * f);\n"
            + "  } else {\n"
            + "    colour = premul(v_fill) * texture2D(u_image, v_uv).a;\n"
            + "  }\n"
            + "  gl_FragColor = colour * clipCover;\n"
            + "}\n";

    private static final String BLUR_VERTEX = ""
            + "#version 120\n"
            + "attribute vec2 a_pos;\n"
            + "varying vec2 v_uv;\n"
            + "void main() {\n"
            + "  v_uv = a_pos * 0.5 + 0.5;\n"
            + "  gl_Position = vec4(a_pos, 0.0, 1.0);\n"
            + "}\n";

    private static final String BLUR_FRAGMENT = ""
            + "#version 120\n"
            + "uniform sampler2D u_source;\n"
            + "uniform vec2 u_step;\n"
            + "uniform float u_offsets[" + BLUR_TAPS + "];\n"
            + "uniform float u_weights[" + BLUR_TAPS + "];\n"
            + "uniform int u_taps;\n"
            + "varying vec2 v_uv;\n"
            + "void main() {\n"
            + "  vec3 sum = texture2D(u_source, v_uv).rgb * u_weights[0];\n"
            + "  for (int i = 1; i < " + BLUR_TAPS + "; i++) {\n"
            + "    if (i >= u_taps) break;\n"
            + "    vec2 o = u_step * u_offsets[i];\n"
            + "    sum += (texture2D(u_source, v_uv + o).rgb + texture2D(u_source, v_uv - o).rgb) * u_weights[i];\n"
            + "  }\n"
            + "  gl_FragColor = vec4(sum, 1.0);\n"
            + "}\n";

    int program;
    int viewport;
    int textGamma;
    int trueSlack;
    int textBoost;
    int atlas;
    int masks;
    int image;

    int blurProgram;
    int blurSource;
    int blurStep;
    int blurOffsets;
    int blurWeights;
    int blurTaps;

    private final FloatBuffer uniformScratch = BufferUtils.createFloatBuffer(BLUR_TAPS);

    /** Compile and link both programs. Throws, with the driver's log, when either fails. */
    void create() {
        program = link(VERTEX, FRAGMENT, new String[] {
            "a_pos", "a_uv", "a_local", "a_half", "a_radii", "a_params", "a_clip", "a_fill", "a_border"});
        viewport = GL20.glGetUniformLocation(program, "u_viewport");
        textGamma = GL20.glGetUniformLocation(program, "u_textGamma");
        trueSlack = GL20.glGetUniformLocation(program, "u_trueSlack");
        textBoost = GL20.glGetUniformLocation(program, "u_textBoost");
        atlas = GL20.glGetUniformLocation(program, "u_atlas");
        masks = GL20.glGetUniformLocation(program, "u_masks");
        image = GL20.glGetUniformLocation(program, "u_image");

        blurProgram = link(BLUR_VERTEX, BLUR_FRAGMENT, new String[] {"a_pos"});
        blurSource = GL20.glGetUniformLocation(blurProgram, "u_source");
        blurStep = GL20.glGetUniformLocation(blurProgram, "u_step");
        blurOffsets = GL20.glGetUniformLocation(blurProgram, "u_offsets");
        blurWeights = GL20.glGetUniformLocation(blurProgram, "u_weights");
        blurTaps = GL20.glGetUniformLocation(blurProgram, "u_taps");
    }

    void setBlurKernel(float[] offsets, float[] weights, int taps) {
        uniformScratch.clear();
        uniformScratch.put(offsets, 0, BLUR_TAPS).flip();
        GL20.glUniform1(blurOffsets, uniformScratch);
        uniformScratch.clear();
        uniformScratch.put(weights, 0, BLUR_TAPS).flip();
        GL20.glUniform1(blurWeights, uniformScratch);
        GL20.glUniform1i(blurTaps, taps);
    }

    void delete() {
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
        if (blurProgram != 0) {
            GL20.glDeleteProgram(blurProgram);
            blurProgram = 0;
        }
    }

    private static int link(String vertexSource, String fragmentSource, String[] attributes) {
        int vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);
        int linked = GL20.glCreateProgram();
        GL20.glAttachShader(linked, vertex);
        GL20.glAttachShader(linked, fragment);
        for (int index = 0; index < attributes.length; index++) {
            GL20.glBindAttribLocation(linked, index, attributes[index]);
        }
        GL20.glLinkProgram(linked);
        GL20.glDetachShader(linked, vertex);
        GL20.glDetachShader(linked, fragment);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(linked, 4096);
            GL20.glDeleteProgram(linked);
            throw new IllegalStateException("UI shader did not link: " + log);
        }
        return linked;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("UI shader did not compile: " + log);
        }
        return shader;
    }
}
