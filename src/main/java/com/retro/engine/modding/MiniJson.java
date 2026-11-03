package com.retro.engine.modding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal strict JSON reader for mod manifests ({@code mod.json}) and scene delta patches ({@code .rpatch}).
 * Produces {@link LinkedHashMap} (key order preserved), {@link ArrayList}, {@link String}, {@link Double},
 * {@link Boolean} and {@code null}. Line comments ({@code // ...}) are accepted so hand-written mod files can be
 * annotated; everything else follows RFC 8259. Load-time only: never called from a tick loop.
 */
public final class MiniJson {

    /** Malformed JSON, with a 1-based line and column. */
    public static final class ParseException extends Exception {
        public ParseException(String message) { super(message); }
    }

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    public static Object parse(String text) throws ParseException {
        MiniJson p = new MiniJson(text != null ? text : "");
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos < p.src.length()) throw p.error("Unexpected trailing content");
        return value;
    }

    /** Parses text whose root must be a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws ParseException {
        Object root = parse(text);
        if (!(root instanceof Map)) throw new ParseException("Expected a JSON object at the root");
        return (Map<String, Object>) root;
    }

    private Object readValue() throws ParseException {
        if (pos >= src.length()) throw error("Unexpected end of input");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield readNumber();
                throw error("Unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> readObject() throws ParseException {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWhitespace();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw error("Expected a quoted key");
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            if (map.containsKey(key)) throw error("Duplicate key \"" + key + "\"");
            map.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') return map;
            if (c != ',') throw error("Expected ',' or '}'");
        }
    }

    private List<Object> readArray() throws ParseException {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw error("Expected ',' or ']'");
        }
    }

    private String readString() throws ParseException {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw error("Unterminated string");
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c < 0x20) throw error("Control character in string");
            if (c != '\\') { sb.append(c); continue; }
            if (pos >= src.length()) throw error("Unterminated escape");
            char e = src.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) throw error("Truncated \\u escape");
                    try {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("Invalid \\u escape");
                    }
                    pos += 4;
                }
                default -> throw error("Invalid escape '\\" + e + "'");
            }
        }
    }

    private Double readNumber() throws ParseException {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
            else break;
        }
        try {
            return Double.parseDouble(src.substring(start, pos));
        } catch (NumberFormatException e) {
            pos = start;
            throw error("Invalid number");
        }
    }

    private Object readLiteral(String word, Object value) throws ParseException {
        if (!src.startsWith(word, pos)) throw error("Expected '" + word + "'");
        pos += word.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private char next() throws ParseException {
        if (pos >= src.length()) throw error("Unexpected end of input");
        return src.charAt(pos++);
    }

    private void expect(char c) throws ParseException {
        if (next() != c) {
            pos--;
            throw error("Expected '" + c + "'");
        }
    }

    private ParseException error(String message) {
        int line = 1, col = 1;
        for (int i = 0; i < pos && i < src.length(); i++) {
            if (src.charAt(i) == '\n') { line++; col = 1; } else { col++; }
        }
        return new ParseException(message + " at line " + line + ", column " + col);
    }

    // ------------------------------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------------------------------

    /**
     * Pretty-prints maps, lists, strings, numbers, booleans and null with two-space indentation. Whole-valued
     * numbers print without a fraction; floats print with the shortest exact float representation.
     */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.append('\n').toString();
    }

    private static void writeValue(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            writeString(sb, str);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Float f) {
            writeNumber(sb, f, Float.toString(f));
        } else if (v instanceof Number n) {
            writeNumber(sb, n.doubleValue(), Double.toString(n.doubleValue()));
        } else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                indent(sb, indent + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                writeValue(sb, e.getValue(), indent + 1);
                sb.append(++i < map.size() ? ",\n" : "\n");
            }
            indent(sb, indent);
            sb.append('}');
        } else if (v instanceof List<?> list) {
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(sb, indent + 1);
                writeValue(sb, list.get(i), indent + 1);
                sb.append(i + 1 < list.size() ? ",\n" : "\n");
            }
            indent(sb, indent);
            sb.append(']');
        } else {
            writeString(sb, v.toString());
        }
    }

    private static void writeNumber(StringBuilder sb, double d, String exact) {
        if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append("null"); return; }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
        else sb.append(exact);
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append("  ");
    }

    // ------------------------------------------------------------------------------------------
    // Typed accessors for parsed maps
    // ------------------------------------------------------------------------------------------

    /** First present key among {@code keys} (canonical name first, then aliases), or null. */
    public static Object first(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            if (map.containsKey(k)) return map.get(k);
        }
        return null;
    }

    public static String string(Map<String, Object> map, String fallback, String... keys) {
        Object v = first(map, keys);
        return (v instanceof String s) ? s : fallback;
    }

    public static int integer(Map<String, Object> map, int fallback, String... keys) {
        Object v = first(map, keys);
        return (v instanceof Number n) ? (int) Math.round(n.doubleValue()) : fallback;
    }

    public static boolean bool(Map<String, Object> map, boolean fallback, String... keys) {
        Object v = first(map, keys);
        return (v instanceof Boolean b) ? b : fallback;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> map, String... keys) {
        Object v = first(map, keys);
        return (v instanceof List) ? (List<Object>) v : List.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Map<String, Object> map, String... keys) {
        Object v = first(map, keys);
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }
}
