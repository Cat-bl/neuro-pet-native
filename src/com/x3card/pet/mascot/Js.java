/*
 * 桌宠引擎（web-mascot 的 Java 移植）
 * web-mascot：https://github.com/CommentOut64/web-mascot ，移植自 libshijima / Shijima-Qt / NeurolingsCE-Qt
 * 许可：GPL-3.0-or-later（同上游，见 pet/LICENSE-web-mascot.txt、pet/NOTICE-web-mascot.txt）
 */
package com.x3card.pet.mascot;

import java.math.BigDecimal;

/** 表达式和配置用到的 JS 取值规则：undefined / null、真假、转数字、转字符串、V8 的 Math.round / hypot */
final class Js {
    private Js() {}

    /** JS 的 undefined */
    static final Object UNDEF = new Object() {
        @Override public String toString() { return "undefined"; }
    };
    /** JS 的 null（Java 的 null 在内部表示"没有这个键"） */
    static final Object NULL = new Object() {
        @Override public String toString() { return "null"; }
    };

    /** 有属性的 JS 对象：get 返回 null 表示没有这个自有属性 */
    interface Obj {
        Object get(String key);
    }

    /** JS 函数 */
    interface Fn extends Obj {
        Object call(Object[] args);
    }

    /** 表达式求值出错（同 TS 里 throw 的各种 Error），不带调用栈 */
    static final class EvalError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EvalError(String message) { super(message, null, false, false); }
    }

    static boolean isNullish(Object v) { return v == UNDEF || v == NULL; }

    static boolean truthy(Object v) {
        if (v == UNDEF || v == NULL || v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Double) {
            double d = (Double) v;
            return d != 0 && d == d;
        }
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    static String typeOf(Object v) {
        if (v == UNDEF) return "undefined";
        if (v instanceof Double) return "number";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof String) return "string";
        if (v instanceof Fn) return "function";
        return "object";
    }

    /** ToPrimitive：对象变成字符串（普通对象 "[object Object]"） */
    static Object primitive(Object v) {
        if (v instanceof Obj) return v instanceof Fn ? "function () { [native code] }" : "[object Object]";
        return v;
    }

    static double toNumber(Object v) {
        if (v instanceof Double) return (Double) v;
        if (v == UNDEF) return Double.NaN;
        if (v == NULL) return 0;
        if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
        if (v instanceof String) return parseNumber((String) v);
        return Double.NaN;
    }

    static String toStr(Object v) {
        if (v instanceof String) return (String) v;
        if (v instanceof Double) return numberToString((Double) v);
        if (v instanceof Boolean) return (Boolean) v ? "true" : "false";
        if (v == UNDEF) return "undefined";
        if (v == NULL) return "null";
        return (String) primitive(v);
    }

    /** === */
    static boolean strictEquals(Object a, Object b) {
        if (a instanceof Double && b instanceof Double) return ((Double) a).doubleValue() == ((Double) b).doubleValue();
        if (a instanceof String && b instanceof String) return a.equals(b);
        if (a instanceof Boolean && b instanceof Boolean) return a.equals(b);
        return a == b;
    }

    /** a + b */
    static Object add(Object a, Object b) {
        Object pa = primitive(a), pb = primitive(b);
        if (pa instanceof String || pb instanceof String) return toStr(pa) + toStr(pb);
        return toNumber(pa) + toNumber(pb);
    }

    /** a < b（JS 的抽象关系比较；NaN 时为 false） */
    static boolean less(Object a, Object b) {
        Object pa = primitive(a), pb = primitive(b);
        if (pa instanceof String && pb instanceof String) return ((String) pa).compareTo((String) pb) < 0;
        return toNumber(pa) < toNumber(pb);
    }

    /** a <= b */
    static boolean lessEq(Object a, Object b) {
        Object pa = primitive(a), pb = primitive(b);
        if (pa instanceof String && pb instanceof String) return ((String) pa).compareTo((String) pb) <= 0;
        return toNumber(pa) <= toNumber(pb);
    }

    static boolean isWhite(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == 0x0C || c == 0xA0
            || c == 0xFEFF || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029
            || c == 0x202F || c == 0x205F || c == 0x3000;
    }

    /** String.prototype.trim */
    static String trim(String s) {
        int a = 0, b = s.length();
        while (a < b && isWhite(s.charAt(a))) a++;
        while (b > a && isWhite(s.charAt(b - 1))) b--;
        return a == 0 && b == s.length() ? s : s.substring(a, b);
    }

    /** Number(string) */
    static double parseNumber(String s) {
        String t = trim(s);
        if (t.isEmpty()) return 0;
        if (t.length() > 2 && t.charAt(0) == '0') {
            char k = Character.toLowerCase(t.charAt(1));
            int radix = k == 'x' ? 16 : k == 'o' ? 8 : k == 'b' ? 2 : 0;
            if (radix != 0) {
                double v = 0;
                for (int i = 2; i < t.length(); i++) {
                    int d = Character.digit(t.charAt(i), radix);
                    if (d < 0) return Double.NaN;
                    v = v * radix + d;
                }
                return v;
            }
        }
        int i = 0;
        boolean neg = false;
        if (t.charAt(0) == '+' || t.charAt(0) == '-') { neg = t.charAt(0) == '-'; i = 1; }
        if (t.startsWith("Infinity", i) && t.length() == i + 8) return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        int end = decimalEnd(t, i);
        if (end != t.length() || end == i) return Double.NaN;
        return Double.parseDouble(t);
    }

    /** parseFloat(string)：读出最长的十进制数前缀 */
    static double parseFloat(String s) {
        if (s == null) return Double.NaN;
        int a = 0;
        while (a < s.length() && isWhite(s.charAt(a))) a++;
        String t = s.substring(a);
        int i = 0;
        boolean neg = false;
        if (t.length() > 0 && (t.charAt(0) == '+' || t.charAt(0) == '-')) { neg = t.charAt(0) == '-'; i = 1; }
        if (t.startsWith("Infinity", i)) return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        int end = decimalEnd(t, i);
        if (end == i) return Double.NaN;
        return Double.parseDouble(t.substring(0, end));
    }

    /** 从 i 起的十进制数字面量（\d+\.?\d*|\.\d+ 后跟可选指数）结束位置；不是数字时返回 i */
    static int decimalEnd(String t, int i) {
        int n = t.length(), p = i, digits = 0;
        while (p < n && t.charAt(p) >= '0' && t.charAt(p) <= '9') { p++; digits++; }
        if (p < n && t.charAt(p) == '.') {
            int q = p + 1, frac = 0;
            while (q < n && t.charAt(q) >= '0' && t.charAt(q) <= '9') { q++; frac++; }
            if (digits == 0 && frac == 0) return i;
            p = q;
            digits += frac;
        }
        if (digits == 0) return i;
        if (p < n && (t.charAt(p) == 'e' || t.charAt(p) == 'E')) {
            int q = p + 1;
            if (q < n && (t.charAt(q) == '+' || t.charAt(q) == '-')) q++;
            int e = q;
            while (q < n && t.charAt(q) >= '0' && t.charAt(q) <= '9') q++;
            if (q > e) p = q;
        }
        return p;
    }

    /** Number.prototype.toString() */
    static String numberToString(double d) {
        if (d != d) return "NaN";
        if (d == 0) return "0";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d < 0) return "-" + numberToString(-d);
        BigDecimal b = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        String digits = b.unscaledValue().toString();
        int k = digits.length(), n = k - b.scale();
        StringBuilder s = new StringBuilder();
        if (k <= n && n <= 21) {
            s.append(digits);
            for (int i = k; i < n; i++) s.append('0');
        } else if (0 < n && n <= 21) {
            s.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            s.append("0.");
            for (int i = n; i < 0; i++) s.append('0');
            s.append(digits);
        } else {
            s.append(digits.charAt(0));
            if (k > 1) s.append('.').append(digits, 1, k);
            s.append('e').append(n - 1 >= 0 ? "+" : "-").append(Math.abs(n - 1));
        }
        return s.toString();
    }

    /** Math.round（V8：向上取整，比原数大出半个以上再减一） */
    static double round(double x) {
        if (x != x || Double.isInfinite(x)) return x;
        double r = Math.ceil(x);
        if (r - 0.5 > x) r -= 1.0;
        return r;
    }

    static double trunc(double x) {
        return x < 0 ? Math.ceil(x) : Math.floor(x);
    }

    /** Math.hypot（V8：按最大值归一后 Kahan 求和） */
    static double hypot(double a, double b) {
        if (a != a || b != b) {
            if (Double.isInfinite(a) || Double.isInfinite(b)) return Double.POSITIVE_INFINITY;
            return Double.NaN;
        }
        double x = Math.abs(a), y = Math.abs(b);
        double max = Math.max(x, y);
        if (max == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY;
        if (max == 0) return 0;
        double sum = 0, compensation = 0;
        double n = x / max;
        double summand = n * n - compensation;
        double preliminary = sum + summand;
        compensation = (preliminary - sum) - summand;
        sum = preliminary;
        n = y / max;
        summand = n * n - compensation;
        preliminary = sum + summand;
        sum = preliminary;
        return Math.sqrt(sum) * max;
    }

    static boolean finite(Object v) {
        return v instanceof Double && !((Double) v).isNaN() && !((Double) v).isInfinite();
    }
}
