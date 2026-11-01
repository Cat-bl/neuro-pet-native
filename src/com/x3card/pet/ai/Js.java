package com.x3card.pet.ai;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 照 JS 的规矩处理 JSON 值：真假、转字符串、取属性（对 null 取属性报错）、空白字符（JS 的 \s 比 Java 的多）。
 * Java 的 null 当 JS 的 undefined，JSONObject.NULL 当 JS 的 null。
 */
final class Js {
    private Js() {}

    /** JS 里对 null / undefined 取属性的 TypeError */
    static final class Err extends RuntimeException {
        Err(String m) { super(m); }
    }

    /** JS 正则 \s 的字符（放进 [] 里用） */
    static final String WSC = "\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";
    static final String WS = "[" + WSC + "]";
    /** JS 正则的 .（不含换行类字符） */
    static final String DOT = "[^\\n\\r\\u2028\\u2029]";
    private static final Pattern RUN = Pattern.compile(WS + "+");

    static boolean isWs(char c) {
        switch (c) {
            case '\t': case '\n': case 0x0B: case '\f': case '\r': case ' ': case 0xA0: case 0x1680:
            case 0x2028: case 0x2029: case 0x202F: case 0x205F: case 0x3000: case 0xFEFF:
                return true;
            default:
                return c >= 0x2000 && c <= 0x200A;
        }
    }

    static String trim(String s) {
        int a = 0, b = s.length();
        while (a < b && isWs(s.charAt(a))) a++;
        while (b > a && isWs(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    static String ltrim(String s) {
        int a = 0;
        while (a < s.length() && isWs(s.charAt(a))) a++;
        return s.substring(a);
    }

    /** s.replace(/\s+/g, ' ') */
    static String collapse(String s) { return RUN.matcher(s).replaceAll(" "); }

    /** s.slice(0, n) */
    static String head(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    static boolean truthy(Object v) {
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return !((String) v).isEmpty();
        if (v instanceof Number) { double d = ((Number) v).doubleValue(); return d != 0 && !Double.isNaN(d); }
        return true;
    }

    /** a || b || …：第一个真值，都不是就最后一个 */
    static Object or(Object... vs) {
        for (int i = 0; i < vs.length - 1; i++) if (truthy(vs[i])) return vs[i];
        return vs[vs.length - 1];
    }

    /** JS 的 String(v) */
    static String str(Object v) {
        if (v == null) return "undefined";
        if (v == JSONObject.NULL) return "null";
        if (v instanceof String) return (String) v;
        if (v instanceof Boolean) return ((Boolean) v) ? "true" : "false";
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) return Long.toString(((Number) v).longValue());
        if (v instanceof Number) return num(((Number) v).doubleValue());
        if (v instanceof JSONObject) return "[object Object]";
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) b.append(',');
                Object x = a.opt(i);
                if (x != null && x != JSONObject.NULL) b.append(str(x));
            }
            return b.toString();
        }
        return v.toString();
    }

    static String num(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        return Json.numText(d);
    }

    /** 解析出来的数：整数（不超过 2^53）用 Long，别的用 Double；无穷大存不进 JSONObject，当 null */
    static Object number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return JSONObject.NULL;
        if (v == Math.rint(v) && Math.abs(v) <= 9007199254740992.0) return (long) v;
        return v;
    }

    static boolean isNum(Object v) { return v instanceof Number; }

    /** === */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a == JSONObject.NULL || b == JSONObject.NULL) return a == b;
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        if (a instanceof String || a instanceof Boolean) return a.equals(b);
        return a == b;
    }

    /** 数组下标那样的键（"0"、"12"，不带前导 0） */
    static boolean isIndex(String k) {
        int n = k.length();
        if (n == 0 || n > 10) return false;
        if (n > 1 && k.charAt(0) == '0') return false;
        for (int i = 0; i < n; i++) { char c = k.charAt(i); if (c < '0' || c > '9') return false; }
        return Long.parseLong(k) < 4294967295L;
    }

    /** 对象的键，按 JS 的顺序 */
    static List<String> keys(JSONObject o) {
        List<String> idx = new ArrayList<String>(), rest = new ArrayList<String>();
        for (Iterator<?> it = o.keys(); it.hasNext(); ) {
            String k = (String) it.next();
            (isIndex(k) ? idx : rest).add(k);
        }
        if (idx.isEmpty()) return rest;
        Collections.sort(idx, new Comparator<String>() {
            @Override public int compare(String a, String b) { return Long.compare(Long.parseLong(a), Long.parseLong(b)); }
        });
        idx.addAll(rest);
        return idx;
    }

    /** for (k in v) 的键：对象的键、数组的下标 */
    static List<String> props(Object v) {
        if (v instanceof JSONObject) return keys((JSONObject) v);
        List<String> r = new ArrayList<String>();
        if (v instanceof JSONArray) for (int i = 0; i < ((JSONArray) v).length(); i++) r.add(Integer.toString(i));
        return r;
    }

    /** o.k：对 null / undefined 取属性报错，字符串和数组只认 length 和下标 */
    static Object get(Object o, String k) {
        if (o == null || o == JSONObject.NULL) throw new Err("Cannot read properties of " + (o == null ? "undefined" : "null") + " (reading '" + k + "')");
        if (o instanceof JSONObject) return ((JSONObject) o).opt(k);
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            if ("length".equals(k)) return (long) a.length();
            return isIndex(k) ? a.opt((int) Math.min(Integer.MAX_VALUE, Long.parseLong(k))) : null;
        }
        if (o instanceof String) {
            String s = (String) o;
            if ("length".equals(k)) return (long) s.length();
            if (isIndex(k)) { long i = Long.parseLong(k); return i < s.length() ? String.valueOf(s.charAt((int) i)) : null; }
        }
        return null;
    }

    /** target[k] = v（对象和数组；Java null 当 undefined） */
    static void set(Object target, String k, Object v) {
        if (target instanceof JSONObject) put((JSONObject) target, k, v);
        else if (target instanceof JSONArray && isIndex(k)) {
            long i = Long.parseLong(k);
            if (i > 100000) return;
            try { ((JSONArray) target).put((int) i, v == null ? JSONObject.NULL : v); } catch (Exception e) { /* 存不进去的数 */ }
        }
    }

    /** o.put(k, v)；v 是 Java null 时去掉这个键（和 JS 的 undefined 输出时一样） */
    static JSONObject put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception e) { /* NaN、无穷大存不进去 */ }
        return o;
    }

    /** { k1: v1, k2: v2, … }（按顺序） */
    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    /** [ v1, v2, … ]（Java null 输出成 null） */
    static JSONArray arr(Object... vs) {
        JSONArray a = new JSONArray();
        for (Object v : vs) a.put(v == null ? JSONObject.NULL : v);
        return a;
    }

    static JSONArray arr(List<String> vs) {
        JSONArray a = new JSONArray();
        for (String v : vs) a.put(v);
        return a;
    }

    /** [].concat(a, b)：数组展开，别的原样放进去 */
    static JSONArray concat(Object a, Object b) {
        JSONArray r = new JSONArray();
        for (Object x : new Object[] { a, b }) {
            if (x instanceof JSONArray) { JSONArray y = (JSONArray) x; for (int i = 0; i < y.length(); i++) r.put(y.opt(i)); }
            else r.put(x == null ? JSONObject.NULL : x);
        }
        return r;
    }

    /** 一个值的浅拷贝（对象拷一层） */
    static JSONObject copy(JSONObject o) {
        JSONObject r = new JSONObject();
        for (String k : keys(o)) put(r, k, o.opt(k));
        return r;
    }

    /** encodeURIComponent */
    static String enc(String s) {
        StringBuilder b = new StringBuilder();
        byte[] u;
        try { u = s.getBytes("UTF-8"); } catch (UnsupportedEncodingException e) { throw new IllegalStateException(e); }
        for (byte x : u) {
            int c = x & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-_.!~*'()".indexOf(c) >= 0) b.append((char) c);
            else b.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
        }
        return b.toString();
    }
}
