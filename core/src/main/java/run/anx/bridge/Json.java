package run.anx.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON writer and parser, so the agent has no dependency that could clash with the host platform. */
public final class Json {
    private Json() {}

    public static String write(Object v) { StringBuilder b = new StringBuilder(); write(b, v); return b.toString(); }

    private static void write(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof String s) str(b, s);
        else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) b.append(v);
        else if (v instanceof Number n) b.append(n.longValue());
        else if (v instanceof Map<?, ?> m) {
            b.append('{'); boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) { if (!first) b.append(','); first = false; str(b, String.valueOf(e.getKey())); b.append(':'); write(b, e.getValue()); }
            b.append('}');
        } else if (v instanceof Iterable<?> it) {
            b.append('['); boolean first = true;
            for (Object o : it) { if (!first) b.append(','); first = false; write(b, o); }
            b.append(']');
        } else str(b, v.toString());
    }

    private static void str(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        b.append('"');
    }

    public static Object parse(String s) { P p = new P(s); Object v = p.value(); p.ws(); if (p.i != s.length()) throw new IllegalArgumentException("trailing data"); return v; }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) { return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) { return v instanceof List<?> l ? (List<Object>) l : List.of(); }
    public static String text(Object v) { return v == null ? null : String.valueOf(v); }

    private static final class P {
        final String s; int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { if (i >= s.length()) throw new IllegalArgumentException("unexpected end"); return s.charAt(i); }
        Object value() {
            ws();
            char c = peek();
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); ws(); if (peek() == '}') { i++; return m; }
                while (true) { ws(); String k = string(); ws(); expect(':'); m.put(k, value()); ws(); if (peek() == ',') { i++; continue; } expect('}'); return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws(); if (peek() == ']') { i++; return l; }
                while (true) { l.add(value()); ws(); if (peek() == ',') { i++; continue; } expect(']'); return l; } }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("bad token at " + i);
            String n = s.substring(st, i);
            return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        }
        void expect(char c) { if (peek() != c) throw new IllegalArgumentException("expected " + c + " at " + i); i++; }
        String string() {
            expect('"'); StringBuilder b = new StringBuilder();
            while (true) {
                char c = peek(); i++;
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = peek(); i++;
                switch (e) {
                    case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r'); case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            }
        }
    }
}
