/*
 * 桌宠引擎（web-mascot 的 Java 移植），GPL-3.0-or-later，见 Js.java 开头
 */
package com.x3card.pet.mascot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * pack 里的小表达式（ExpressionEvaluator.ts 的移植）：编译一次成语法树，之后每次只求值。
 * 语法、短路、出错的时机都和 TS 版一样（TS 版边解析边求值；语法错的表达式这里整句不求值，pack 里没有这种）。
 */
final class Expr {
    /** 标识符的取值：返回 null 表示作用域里没有这个名字 */
    interface Scope {
        Object lookup(String name);
    }

    private static final Map<String, Expr> CACHE = new ConcurrentHashMap<>();

    private final Node root;
    private final String error;

    private Expr(Node root, String error) {
        this.root = root;
        this.error = error;
    }

    /** 编译（带缓存）；语法错误时求值总是抛错 */
    static Expr compile(String source) {
        Expr e = CACHE.get(source);
        if (e != null) return e;
        try {
            List<Tok> tokens = tokenize(source);
            validateLogical(tokens);
            Parser p = new Parser(tokens);
            Node n = p.conditional();
            if (p.peek().kind != EOF) throw new Js.EvalError("unexpected token");
            e = new Expr(n, null);
        } catch (Js.EvalError | NumberFormatException err) {
            e = new Expr(null, err.getMessage());
        }
        CACHE.put(source, e);
        return e;
    }

    Object eval(Scope scope) {
        if (root == null) throw new Js.EvalError(error);
        return root.eval(scope);
    }

    // ---------------- 词法 ----------------
    private static final int NUM = 0, STR = 1, ID = 2, OP = 3, EOF = 4;

    private static final class Tok {
        final int kind;
        final String text;
        final double num;

        Tok(int kind, String text, double num) {
            this.kind = kind;
            this.text = text;
            this.num = num;
        }

        boolean is(String op) { return kind == OP && text.equals(op); }
    }

    private static final String[] OPS = {"===", "!==", "==", "!=", "<=", ">=", "&&", "||", "??"};
    private static final String SINGLE = "()+-*/%!?<>=.,:";

    private static boolean idStart(char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' || c == '$'; }
    private static boolean idPart(char c) { return idStart(c) || (c >= '0' && c <= '9'); }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Js.isWhite(c)) { i++; continue; }
            if (digit(c) || (c == '.' && i + 1 < n && digit(s.charAt(i + 1)))) {
                int end = Js.decimalEnd(s, i);
                if (end == i) throw new Js.EvalError("invalid number");
                double v = Double.parseDouble(s.substring(i, end));
                if (Double.isInfinite(v) || v != v) throw new Js.EvalError("invalid number");
                out.add(new Tok(NUM, null, v));
                i = end;
                continue;
            }
            if (idStart(c)) {
                int j = i + 1;
                while (j < n && idPart(s.charAt(j))) j++;
                out.add(new Tok(ID, s.substring(i, j), 0));
                i = j;
                continue;
            }
            if (c == '\'' || c == '"') {
                StringBuilder v = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char cur = s.charAt(i++);
                    if (cur == c) { closed = true; break; }
                    if (cur == '\\') {
                        if (i >= n) break;
                        v.append(s.charAt(i++));
                    } else v.append(cur);
                }
                if (!closed) throw new Js.EvalError("unterminated string");
                out.add(new Tok(STR, v.toString(), 0));
                continue;
            }
            String op = null;
            for (String o : OPS) if (s.startsWith(o, i)) { op = o; break; }
            if (op == null && SINGLE.indexOf(c) >= 0) op = String.valueOf(c);
            if (op == null) throw new Js.EvalError("unsupported token '" + c + "'");
            out.add(new Tok(OP, op, 0));
            i += op.length();
        }
        out.add(new Tok(EOF, null, 0));
        return out;
    }

    /** JS 不许 ?? 和 || / && 在同一层括号里混用 */
    private static void validateLogical(List<Tok> tokens) {
        Map<Integer, int[]> state = new HashMap<>();
        int depth = 0;
        for (Tok t : tokens) {
            if (t.kind != OP) continue;
            if (t.text.equals("(")) { depth++; continue; }
            if (t.text.equals(")")) { depth--; continue; }
            if (!t.text.equals("??") && !t.text.equals("||") && !t.text.equals("&&")) continue;
            int[] cur = state.get(depth);
            if (cur == null) state.put(depth, cur = new int[2]);
            if (t.text.equals("??")) cur[0] = 1;
            else cur[1] = 1;
            if (cur[0] == 1 && cur[1] == 1) throw new Js.EvalError("cannot mix ?? with || or && without parentheses");
        }
    }

    // ---------------- 语法树 ----------------
    private abstract static class Node {
        abstract Object eval(Scope s);

        /** TS 版 evaluate=false 时这一段解析出来的值（只在 undefined ?? x 时用得到） */
        Object dry() { return Js.UNDEF; }

        /** 带接收者的取值（成员调用用）；默认没有接收者 */
        Object evalRef(Scope s, Object[] receiver) {
            receiver[0] = Js.UNDEF;
            return eval(s);
        }
    }

    private static final class Lit extends Node {
        final Object v;

        Lit(Object v) { this.v = v; }

        @Override Object eval(Scope s) { return v; }
        @Override Object dry() { return v; }
    }

    private static final class Ident extends Node {
        final String name;

        Ident(String name) { this.name = name; }

        @Override Object eval(Scope s) {
            Object v = s.lookup(name);
            if (v == null) throw new Js.EvalError("unknown identifier '" + name + "'");
            return v;
        }
    }

    private static final class Member extends Node {
        final Node obj;
        final String prop;

        Member(Node obj, String prop) {
            this.obj = obj;
            this.prop = prop;
        }

        @Override Object eval(Scope s) {
            return read(obj.eval(s), prop);
        }

        @Override Object evalRef(Scope s, Object[] receiver) {
            Object target = obj.eval(s);
            receiver[0] = target;
            return read(target, prop);
        }
    }

    private static boolean forbidden(String p) {
        switch (p) {
            case "__proto__": case "prototype": case "constructor": case "call": case "apply": case "bind":
                return true;
            default:
                return false;
        }
    }

    /** 只开放自有属性（同 TS 的 readProperty） */
    static Object read(Object target, String prop) {
        if (forbidden(prop) || Js.isNullish(target)) throw new Js.EvalError("property access denied");
        if (!(target instanceof Js.Obj)) throw new Js.EvalError("unknown property '" + prop + "'");
        Object v = ((Js.Obj) target).get(prop);
        if (v == null) throw new Js.EvalError("unknown property '" + prop + "'");
        return v;
    }

    private static final class Call extends Node {
        final Node callee;
        final Node[] args;

        Call(Node callee, Node[] args) {
            this.callee = callee;
            this.args = args;
        }

        @Override Object eval(Scope s) {
            Object[] recv = new Object[1];
            Object f = callee.evalRef(s, recv);
            Object[] values = new Object[args.length];
            for (int i = 0; i < args.length; i++) values[i] = args[i].eval(s);
            if (!(f instanceof Js.Fn)) throw new Js.EvalError("value is not callable");
            return ((Js.Fn) f).call(values);
        }
    }

    private static final class TypeOf extends Node {
        final Node arg;

        TypeOf(Node arg) { this.arg = arg; }

        @Override Object eval(Scope s) { return Js.typeOf(arg.eval(s)); }
    }

    private static final class Unary extends Node {
        final char op;
        final Node arg;

        Unary(char op, Node arg) {
            this.op = op;
            this.arg = arg;
        }

        @Override Object eval(Scope s) {
            Object v = arg.eval(s);
            if (op == '!') return !Js.truthy(v);
            double d = Js.toNumber(v);
            return op == '+' ? d : -d;
        }
    }

    private static final int ADD = 0, SUB = 1, MUL = 2, DIV = 3, MOD = 4, LT = 5, LE = 6, GT = 7, GE = 8,
        SEQ = 9, SNE = 10, EQ = 11, NE = 12;

    private static final class Binary extends Node {
        final int op;
        final Node l, r;

        Binary(int op, Node l, Node r) {
            this.op = op;
            this.l = l;
            this.r = r;
        }

        @Override Object eval(Scope s) {
            Object a = l.eval(s), b = r.eval(s);
            switch (op) {
                case ADD: return Js.add(a, b);
                case SUB: return Js.toNumber(a) - Js.toNumber(b);
                case MUL: return Js.toNumber(a) * Js.toNumber(b);
                case DIV: return Js.toNumber(a) / Js.toNumber(b);
                case MOD: return Js.toNumber(a) % Js.toNumber(b);
                case LT: return Js.less(a, b);
                case LE: return Js.lessEq(a, b);
                case GT: return Js.less(b, a);
                case GE: return Js.lessEq(b, a);
                case SEQ: return Js.strictEquals(a, b);
                case SNE: return !Js.strictEquals(a, b);
                default: {
                    boolean eq = Js.strictEquals(a, b) || (Js.isNullish(a) && Js.isNullish(b)) || Js.toStr(a).equals(Js.toStr(b));
                    return op == EQ ? eq : !eq;
                }
            }
        }

        @Override Object dry() { return l.dry(); }
    }

    private static final int AND = 0, OR = 1, NULLISH = 2;

    private static final class Logical extends Node {
        final int op;
        final Node l, r;

        Logical(int op, Node l, Node r) {
            this.op = op;
            this.l = l;
            this.r = r;
        }

        @Override Object eval(Scope s) {
            Object v = l.eval(s);
            if (op == AND) return Js.truthy(v) ? r.eval(s) : v;
            if (op == OR) return Js.truthy(v) ? v : r.eval(s);
            if (v == Js.NULL) return r.eval(s);
            if (v == Js.UNDEF) return r.dry();
            return v;
        }
    }

    private static final class Cond extends Node {
        final Node c, a, b;

        Cond(Node c, Node a, Node b) {
            this.c = c;
            this.a = a;
            this.b = b;
        }

        @Override Object eval(Scope s) {
            return Js.truthy(c.eval(s)) ? a.eval(s) : b.eval(s);
        }
    }

    // ---------------- 解析（结构同 TS 的递归下降） ----------------
    private static final class Parser {
        private final List<Tok> tokens;
        private int index;

        Parser(List<Tok> tokens) { this.tokens = tokens; }

        Tok peek() { return tokens.get(index); }

        private Tok take(String op) {
            Tok t = peek();
            if (op != null && !t.is(op)) throw new Js.EvalError("expected '" + op + "'");
            index++;
            return t;
        }

        Node conditional() {
            Node c = logicalOr();
            if (!peek().is("?")) return c;
            take("?");
            Node a = conditional();
            take(":");
            Node b = conditional();
            return new Cond(c, a, b);
        }

        private Node logicalOr() {
            Node v = logicalAnd();
            while (peek().is("||")) {
                take("||");
                v = new Logical(OR, v, logicalAnd());
            }
            while (peek().is("??")) {
                take("??");
                v = new Logical(NULLISH, v, logicalAnd());
            }
            return v;
        }

        private Node logicalAnd() {
            Node v = equality();
            while (peek().is("&&")) {
                take("&&");
                v = new Logical(AND, v, equality());
            }
            return v;
        }

        private Node equality() {
            Node v = comparison();
            while (true) {
                Tok t = peek();
                int op = t.is("===") ? SEQ : t.is("!==") ? SNE : t.is("==") ? EQ : t.is("!=") ? NE : -1;
                if (op < 0) return v;
                index++;
                v = new Binary(op, v, comparison());
            }
        }

        private Node comparison() {
            Node v = additive();
            while (true) {
                Tok t = peek();
                int op = t.is("<") ? LT : t.is("<=") ? LE : t.is(">") ? GT : t.is(">=") ? GE : -1;
                if (op < 0) return v;
                index++;
                v = new Binary(op, v, additive());
            }
        }

        private Node additive() {
            Node v = multiplicative();
            while (true) {
                Tok t = peek();
                int op = t.is("+") ? ADD : t.is("-") ? SUB : -1;
                if (op < 0) return v;
                index++;
                v = new Binary(op, v, multiplicative());
            }
        }

        private Node multiplicative() {
            Node v = unary();
            while (true) {
                Tok t = peek();
                int op = t.is("*") ? MUL : t.is("/") ? DIV : t.is("%") ? MOD : -1;
                if (op < 0) return v;
                index++;
                v = new Binary(op, v, unary());
            }
        }

        private Node unary() {
            Tok t = peek();
            if (t.is("!") || t.is("+") || t.is("-")) {
                index++;
                return new Unary(t.text.charAt(0), unary());
            }
            return postfix();
        }

        private Node postfix() {
            Node ref = primary();
            while (true) {
                if (peek().is(".")) {
                    take(".");
                    Tok p = take(null);
                    if (p.kind != ID) throw new Js.EvalError("expected property");
                    ref = new Member(ref, p.text);
                    continue;
                }
                if (peek().is("(")) {
                    take("(");
                    List<Node> args = new ArrayList<>();
                    if (!peek().is(")")) {
                        while (true) {
                            args.add(conditional());
                            if (!peek().is(",")) break;
                            take(",");
                        }
                    }
                    take(")");
                    ref = new Call(ref, args.toArray(new Node[0]));
                    continue;
                }
                return ref;
            }
        }

        private Node primary() {
            Tok t = take(null);
            if (t.kind == NUM) return new Lit(t.num);
            if (t.kind == STR) return new Lit(t.text);
            if (t.kind == ID) {
                switch (t.text) {
                    case "true": return new Lit(Boolean.TRUE);
                    case "false": return new Lit(Boolean.FALSE);
                    case "null": return new Lit(Js.NULL);
                    case "undefined": return new Lit(Js.UNDEF);
                    case "typeof": return new TypeOf(unary());
                    default: return new Ident(t.text);
                }
            }
            if (t.is("(")) {
                Node v = conditional();
                take(")");
                return v;
            }
            throw new Js.EvalError("expected value");
        }
    }
}
