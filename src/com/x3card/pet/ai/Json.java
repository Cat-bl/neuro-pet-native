package com.x3card.pet.ai;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 照 JS 的 JSON.parse / JSON.stringify 写的解析和输出：严格的语法（org.json 自带的解析太宽松），
 * 数字按 JS 的写法输出，键按 JS 的顺序（像下标的键在前、从小到大，其余按加进去的顺序）。
 * 解析结果：JSONObject / JSONArray / String / Long（整数，不超过 2^53）/ Double / Boolean / JSONObject.NULL。
 */
public final class Json {
    private Json() {}

    /** 解析出错 */
    public static final class Bad extends RuntimeException {
        Bad(String m) { super(m); }
    }

    public static Object parse(String s) {
        if (s == null) throw new Bad("undefined");
        P p = new P(s);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i < s.length()) throw new Bad("多余的内容 " + p.i);
        return v;
    }

    private static final class P {
        final String s;
        int i;
        P(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
            }
        }

        Bad bad() { return new Bad("JSON 不对：位置 " + i); }

        Object value(int depth) {
            if (depth > 512) throw bad();
            if (i >= s.length()) throw bad();
            char c = s.charAt(i);
            if (c == '{') return obj(depth);
            if (c == '[') return arr(depth);
            if (c == '"') return str();
            if (c == '-' || (c >= '0' && c <= '9')) return num();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return JSONObject.NULL; }
            throw bad();
        }

        JSONObject obj(int depth) {
            JSONObject o = new JSONObject();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return o; }
            for (;;) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw bad();
                String k = str();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw bad();
                i++;
                ws();
                Js.put(o, k, value(depth + 1));
                ws();
                if (i >= s.length()) throw bad();
                char c = s.charAt(i++);
                if (c == '}') return o;
                if (c != ',') throw bad();
            }
        }

        JSONArray arr(int depth) {
            JSONArray a = new JSONArray();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            for (;;) {
                ws();
                a.put(value(depth + 1));
                ws();
                if (i >= s.length()) throw bad();
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') throw bad();
            }
        }

        String str() {
            i++;
            StringBuilder b = null;
            int from = i;
            for (;;) {
                if (i >= s.length()) throw bad();
                char c = s.charAt(i);
                if (c == '"') {
                    String r = b == null ? s.substring(from, i) : b.append(s, from, i).toString();
                    i++;
                    return r;
                }
                if (c < 0x20) throw bad();
                if (c != '\\') { i++; continue; }
                if (b == null) b = new StringBuilder();
                b.append(s, from, i);
                if (i + 1 >= s.length()) throw bad();
                char e = s.charAt(i + 1);
                i += 2;
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u': {
                        if (i + 4 > s.length()) throw bad();
                        int v = 0;
                        for (int k = 0; k < 4; k++) {
                            int d = Character.digit(s.charAt(i + k), 16);
                            if (d < 0) throw bad();
                            v = v * 16 + d;
                        }
                        b.append((char) v);
                        i += 4;
                        break;
                    }
                    default: throw bad();
                }
                from = i;
            }
        }

        Object num() {
            int st = i;
            if (s.charAt(i) == '-') i++;
            if (i >= s.length()) throw bad();
            if (s.charAt(i) == '0') i++;
            else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') { while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++; }
            else throw bad();
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                int d = i;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                if (i == d) throw bad();
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                int d = i;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                if (i == d) throw bad();
            }
            double v = Double.parseDouble(s.substring(st, i));
            return Js.number(v);
        }
    }

    /** JS 的 JSON.stringify；v 是 null（JS 的 undefined）时返回 null */
    public static String stringify(Object v) {
        if (v == null) return null;
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null || v == JSONObject.NULL) { b.append("null"); return; }
        if (v instanceof String) { quote(b, (String) v); return; }
        if (v instanceof Boolean) { b.append(((Boolean) v) ? "true" : "false"); return; }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) b.append(((Number) v).longValue());
            else b.append(Js.num(d));
            return;
        }
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            b.append('{');
            boolean first = true;
            for (String k : Js.keys(o)) {
                Object x = o.opt(k);
                if (x == null) continue;
                if (!first) b.append(',');
                first = false;
                quote(b, k);
                b.append(':');
                write(b, x);
            }
            b.append('}');
            return;
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            b.append('[');
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) b.append(',');
                write(b, a.opt(i));
            }
            b.append(']');
            return;
        }
        quote(b, v.toString());
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static void quote(StringBuilder b, String s) {
        b.append('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); continue;
                case '\\': b.append("\\\\"); continue;
                case '\b': b.append("\\b"); continue;
                case '\f': b.append("\\f"); continue;
                case '\n': b.append("\\n"); continue;
                case '\r': b.append("\\r"); continue;
                case '\t': b.append("\\t"); continue;
                default: break;
            }
            boolean lone = false;
            if (Character.isHighSurrogate(c)) lone = i + 1 >= n || !Character.isLowSurrogate(s.charAt(i + 1));
            else if (Character.isLowSurrogate(c)) lone = i == 0 || !Character.isHighSurrogate(s.charAt(i - 1));
            if (c < 0x20 || lone) {
                b.append("\\u").append(HEX[(c >> 12) & 15]).append(HEX[(c >> 8) & 15]).append(HEX[(c >> 4) & 15]).append(HEX[c & 15]);
            } else b.append(c);
        }
        b.append('"');
    }

    /** JS 的 Number → 字符串（最短能还原的写法） */
    static String numText(double d) {
        if (d == 0) return "0";
        boolean neg = d < 0;
        double a = Math.abs(d);
        BigDecimal exact = new BigDecimal(a), r = null;
        for (int p = 1; p <= 17; p++) {
            BigDecimal t = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
            if (t.doubleValue() == a) { r = t; break; }
        }
        if (r == null) r = exact.round(new MathContext(17, RoundingMode.HALF_EVEN));
        r = r.stripTrailingZeros();
        String s = r.unscaledValue().toString();
        int k = s.length(), n = k - r.scale();
        StringBuilder b = new StringBuilder();
        if (neg) b.append('-');
        if (k <= n && n <= 21) {
            b.append(s);
            for (int i = 0; i < n - k; i++) b.append('0');
        } else if (0 < n && n <= 21) {
            b.append(s, 0, n).append('.').append(s, n, k);
        } else if (-6 < n && n <= 0) {
            b.append("0.");
            for (int i = 0; i < -n; i++) b.append('0');
            b.append(s);
        } else {
            int e = n - 1;
            b.append(s.charAt(0));
            if (k > 1) b.append('.').append(s, 1, k);
            b.append('e').append(e < 0 ? '-' : '+').append(Math.abs(e));
        }
        return b.toString();
    }
}
