package dev.dsadebug.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal dependency-free JSON parser and writer (RFC 8259). */
public final class Json {
    private Json() {}

    public static final class ParseException extends RuntimeException {
        public final int pos;

        public ParseException(String msg, int pos) {
            super(msg + " at position " + pos);
            this.pos = pos;
        }
    }

    /** Returns Map (LinkedHashMap), List, String, Long, Double, Boolean or null. */
    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.i != s.length()) throw new ParseException("Unexpected trailing characters", p.i);
        return v;
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 512;
        final String s;
        int i = 0;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw new ParseException("Nesting too deep", i);
            if (i >= s.length()) throw new ParseException("Unexpected end of input", i);
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw new ParseException("Unexpected character '" + c + "'", i);
            }
        }

        Object literal(String word, Object val) {
            if (!s.startsWith(word, i)) throw new ParseException("Invalid literal", i);
            i += word.length();
            return val;
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') throw new ParseException("Expected string key", i);
                String k = string();
                skipWs();
                if (peek() != ':') throw new ParseException("Expected ':'", i);
                i++;
                skipWs();
                m.put(k, value(depth + 1));
                skipWs();
                char c = peek();
                i++;
                if (c == ',') continue;
                if (c == '}') return m;
                throw new ParseException("Expected ',' or '}'", i - 1);
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++; // [
            skipWs();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value(depth + 1));
                skipWs();
                char c = peek();
                i++;
                if (c == ',') continue;
                if (c == ']') return l;
                throw new ParseException("Expected ',' or ']'", i - 1);
            }
        }

        /** Returns current char, or NUL-safe sentinel error at end of input. */
        char peek() {
            if (i >= s.length()) throw new ParseException("Unexpected end of input", i);
            return s.charAt(i);
        }

        String string() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new ParseException("Unterminated string", i);
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw new ParseException("Control character in string", i - 1);
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) throw new ParseException("Unterminated escape", i);
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new ParseException("Bad unicode escape", i);
                        int cp = 0;
                        for (int k = 0; k < 4; k++) {
                            int d = Character.digit(s.charAt(i + k), 16);
                            if (d < 0) throw new ParseException("Bad unicode escape", i + k);
                            cp = cp * 16 + d;
                        }
                        i += 4;
                        sb.append((char) cp);
                        break;
                    default:
                        throw new ParseException("Invalid escape '\\" + e + "'", i - 1);
                }
            }
        }

        Object number() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            if (i >= s.length()) throw new ParseException("Invalid number", start);
            if (s.charAt(i) == '0') {
                i++;
            } else if (digit(i)) {
                while (digit(i)) i++;
            } else {
                throw new ParseException("Invalid number", start);
            }
            boolean isDouble = false;
            if (i < s.length() && s.charAt(i) == '.') {
                isDouble = true;
                i++;
                if (!digit(i)) throw new ParseException("Invalid number", start);
                while (digit(i)) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                isDouble = true;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (!digit(i)) throw new ParseException("Invalid number", start);
                while (digit(i)) i++;
            }
            String t = s.substring(start, i);
            if (!isDouble) {
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException ex) {
                    // too large for long: fall through to double
                }
            }
            return Double.parseDouble(t);
        }

        boolean digit(int idx) {
            return idx < s.length() && s.charAt(idx) >= '0' && s.charAt(idx) <= '9';
        }
    }

    /** Streaming JSON writer; manages commas and escapes strings. */
    public static final class Writer {
        private final StringBuilder sb = new StringBuilder();
        private int bytes = 0;
        // true when the next value at the current level needs a leading comma
        private final java.util.ArrayDeque<Boolean> needComma = new java.util.ArrayDeque<>();
        private boolean afterKey = false;

        public Writer() {
            needComma.push(false);
        }

        private void append(String t) {
            sb.append(t);
            bytes += utf8Len(t);
        }

        private static int utf8Len(String t) {
            return t.getBytes(StandardCharsets.UTF_8).length;
        }

        private void beforeValue() {
            if (afterKey) {
                afterKey = false;
                return;
            }
            if (needComma.peek()) append(",");
            needComma.pop();
            needComma.push(true);
        }

        public Writer beginObj() {
            beforeValue();
            append("{");
            needComma.push(false);
            return this;
        }

        public Writer endObj() {
            needComma.pop();
            append("}");
            return this;
        }

        public Writer beginArr() {
            beforeValue();
            append("[");
            needComma.push(false);
            return this;
        }

        public Writer endArr() {
            needComma.pop();
            append("]");
            return this;
        }

        public Writer key(String k) {
            if (needComma.peek()) append(",");
            needComma.pop();
            needComma.push(true);
            append(quote(k));
            append(":");
            afterKey = true;
            return this;
        }

        public Writer str(String v) {
            beforeValue();
            append(quote(v));
            return this;
        }

        public Writer num(long v) {
            beforeValue();
            append(Long.toString(v));
            return this;
        }

        public Writer num(double v) {
            beforeValue();
            // JSON has no NaN/Infinity
            append(Double.isNaN(v) || Double.isInfinite(v) ? "null" : Double.toString(v));
            return this;
        }

        public Writer bool(boolean v) {
            beforeValue();
            append(v ? "true" : "false");
            return this;
        }

        public Writer nul() {
            beforeValue();
            append("null");
            return this;
        }

        /** Appends an already-serialized JSON value verbatim. */
        public Writer raw(String json) {
            beforeValue();
            append(json);
            return this;
        }

        /** Bytes (UTF-8) written so far. */
        public int size() {
            return bytes;
        }

        @Override
        public String toString() {
            return sb.toString();
        }

        private static String quote(String v) {
            StringBuilder o = new StringBuilder(v.length() + 2).append('"');
            for (int k = 0; k < v.length(); k++) {
                char c = v.charAt(k);
                switch (c) {
                    case '"': o.append("\\\""); break;
                    case '\\': o.append("\\\\"); break;
                    case '\n': o.append("\\n"); break;
                    case '\r': o.append("\\r"); break;
                    case '\t': o.append("\\t"); break;
                    case '\b': o.append("\\b"); break;
                    case '\f': o.append("\\f"); break;
                    default:
                        if (c < 0x20) o.append(String.format("\\u%04x", (int) c));
                        else o.append(c);
                }
            }
            return o.append('"').toString();
        }
    }
}
