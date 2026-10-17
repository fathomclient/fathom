package net.fathom.optimizations.core.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for a profile document.
 *
 * <h2>Why not the one-key reader {@code LaunchSession} uses</h2>
 *
 * <p>That reader finds {@code "key"} and reads what follows, which is correct for
 * a flat document written by one function in one shape. A profile is nested —
 * modules containing settings, a HUD containing entries — and it is written by
 * two programs, one of which is {@code serde_json}. That needs a real parser.
 *
 * <h2>Why not a library</h2>
 *
 * <p>{@code core} has no dependencies, on purpose: it is compiled and tested with
 * a bare JDK. The parser is a couple of hundred lines and it is tested against a
 * document shaped exactly as the Rust side writes it, which is the only test
 * that actually matters.
 *
 * <p>Objects preserve key order, so a document read and written back keeps the
 * order it was written in — which keeps a profile diffable.
 */
public final class Json {

    private Json() {}

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    /**
     * Parse a document into maps, lists, strings, {@code Long}s, {@code Double}s,
     * {@code Boolean}s and nulls.
     */
    public static Object parse(String text) throws ProfileException {
        Parser parser = new Parser(text == null ? "" : text);
        parser.skipWhitespace();
        Object value = parser.value();
        parser.skipWhitespace();
        if (parser.position != parser.text.length()) {
            throw new ProfileException("unexpected text after the document at " + parser.position);
        }
        return value;
    }

    private static final class Parser {
        final String text;
        int position;

        Parser(String text) {
            this.text = text;
        }

        void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        char peek() throws ProfileException {
            if (position >= text.length()) {
                throw new ProfileException("the document ends too early");
            }
            return text.charAt(position);
        }

        Object value() throws ProfileException {
            skipWhitespace();
            char next = peek();
            switch (next) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    return number();
            }
        }

        Map<String, Object> object() throws ProfileException {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            position++;
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return out;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new ProfileException("expected a key at " + position);
                }
                String key = string();
                skipWhitespace();
                if (peek() != ':') {
                    throw new ProfileException("expected ':' at " + position);
                }
                position++;
                out.put(key, value());
                skipWhitespace();
                char next = peek();
                position++;
                if (next == ',') {
                    continue;
                }
                if (next == '}') {
                    return out;
                }
                throw new ProfileException("expected ',' or '}' at " + (position - 1));
            }
        }

        List<Object> array() throws ProfileException {
            List<Object> out = new ArrayList<Object>();
            position++;
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return out;
            }
            while (true) {
                out.add(value());
                skipWhitespace();
                char next = peek();
                position++;
                if (next == ',') {
                    continue;
                }
                if (next == ']') {
                    return out;
                }
                throw new ProfileException("expected ',' or ']' at " + (position - 1));
            }
        }

        String string() throws ProfileException {
            position++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (position >= text.length()) {
                    throw new ProfileException("a string is not closed");
                }
                char character = text.charAt(position++);
                if (character == '"') {
                    return out.toString();
                }
                if (character != '\\') {
                    out.append(character);
                    continue;
                }
                if (position >= text.length()) {
                    throw new ProfileException("a string ends in the middle of an escape");
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(escaped);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (position + 4 > text.length()) {
                            throw new ProfileException("a unicode escape is cut short");
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        } catch (NumberFormatException notHex) {
                            throw new ProfileException("a unicode escape is not hexadecimal at " + position);
                        }
                        position += 4;
                        break;
                    default:
                        throw new ProfileException("\\" + escaped + " is not an escape");
                }
            }
        }

        void literal(String word) throws ProfileException {
            if (!text.startsWith(word, position)) {
                throw new ProfileException("unexpected text at " + position);
            }
            position += word.length();
        }

        Number number() throws ProfileException {
            int start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            String raw = text.substring(start, position);
            if (raw.isEmpty()) {
                throw new ProfileException("unexpected '" + text.charAt(start) + "' at " + start);
            }
            try {
                if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
                    return Double.valueOf(raw);
                }
                return Long.valueOf(raw);
            } catch (NumberFormatException notANumber) {
                throw new ProfileException("'" + raw + "' is not a number");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------

    /** Two-space indented, trailing newline, the shape {@code serde_json} pretty-prints. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        out.append('\n');
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            quote(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Float) {
            float number = ((Float) value).floatValue();
            // Validation keeps these out of a profile. If one arrives anyway,
            // null is valid JSON and "NaN" is not.
            out.append(Float.isNaN(number) || Float.isInfinite(number) ? "null" : Float.toString(number));
        } else if (value instanceof Double) {
            double number = ((Double) value).doubleValue();
            out.append(Double.isNaN(number) || Double.isInfinite(number) ? "null" : Double.toString(number));
        } else if (value instanceof Number) {
            out.append(((Number) value).longValue());
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(out, depth + 1);
                quote(out, String.valueOf(entry.getKey()));
                out.append(": ");
                write(out, entry.getValue(), depth + 1);
                if (++index < map.size()) {
                    out.append(',');
                }
                out.append('\n');
            }
            indent(out, depth);
            out.append('}');
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int index = 0; index < list.size(); index++) {
                indent(out, depth + 1);
                write(out, list.get(index), depth + 1);
                if (index + 1 < list.size()) {
                    out.append(',');
                }
                out.append('\n');
            }
            indent(out, depth);
            out.append(']');
        } else {
            quote(out, String.valueOf(value));
        }
    }

    private static void indent(StringBuilder out, int depth) {
        for (int level = 0; level < depth; level++) {
            out.append("  ");
        }
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", Integer.valueOf(character)));
                    } else {
                        out.append(character);
                    }
            }
        }
        out.append('"');
    }
}
