/*
 * 桌宠引擎（web-mascot 的 Java 移植），GPL-3.0-or-later，见 Js.java 开头
 */
package com.x3card.pet.mascot;

/** pack 表达式能读到的对象：活动范围、边框、光标、Math（网页版 environment() / mascotScope() 里拼的那些） */
final class Scopes {
    private Scopes() {}

    static final int TOP = 0, RIGHT = 1, BOTTOM = 2, LEFT = 3;

    /** 活动范围（虚拟单位：1 = 精灵图的 1 像素） */
    static final class Area {
        final double top, right, bottom, left;

        Area(double top, double right, double bottom, double left) {
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.left = left;
        }
    }

    /** 点在不在活动范围的某条边上（地板往下越过也算） */
    static boolean borderIsOn(int side, double px, double py, Area a) {
        if (side == TOP || side == BOTTOM) {
            double y = side == TOP ? a.top : a.bottom;
            boolean crossed = side == BOTTOM && py >= y;
            boolean near = Math.abs(py - y) < 1;
            return (crossed || near) && px >= a.left && px <= a.right;
        }
        double x = side == LEFT ? a.left : a.right;
        return Math.abs(px - x) < 1 && py >= a.top && py <= a.bottom;
    }

    /** {x, y} 这类只有数值属性的对象 */
    static final class Point implements Js.Obj {
        final double x, y;

        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }

        @Override public Object get(String key) {
            if (key.equals("x")) return x;
            if (key.equals("y")) return y;
            return null;
        }
    }

    /** environment.cursor */
    static final class Cursor implements Js.Obj {
        final double x, y, dx, dy;

        Cursor(double x, double y, double dx, double dy) {
            this.x = x;
            this.y = y;
            this.dx = dx;
            this.dy = dy;
        }

        @Override public Object get(String key) {
            switch (key) {
                case "x": return x;
                case "y": return y;
                case "dx": return dx;
                case "dy": return dy;
                default: return null;
            }
        }
    }

    /** 某条边（或几条边之一）的 isOn(point) */
    static final class IsOn implements Js.Fn {
        static final int FLOOR = 10, CEILING = 11, WALL = 12, ANY = 13, NEVER = 14;
        final int kind;
        final Area area;

        IsOn(int kind, Area area) {
            this.kind = kind;
            this.area = area;
        }

        @Override public Object get(String key) { return null; }

        @Override public Object call(Object[] args) {
            if (kind == NEVER) return Boolean.FALSE;
            Object p = args.length > 0 ? args[0] : Js.UNDEF;
            if (Js.isNullish(p)) throw new Js.EvalError("Cannot read property of " + p);
            double px = Double.NaN, py = Double.NaN;
            if (p instanceof Js.Obj) {
                Object vx = ((Js.Obj) p).get("x"), vy = ((Js.Obj) p).get("y");
                px = Js.toNumber(vx == null ? Js.UNDEF : vx);
                py = Js.toNumber(vy == null ? Js.UNDEF : vy);
            }
            switch (kind) {
                case FLOOR: return borderIsOn(BOTTOM, px, py, area);
                case CEILING: return borderIsOn(TOP, px, py, area);
                case WALL: return borderIsOn(LEFT, px, py, area) || borderIsOn(RIGHT, px, py, area);
                case ANY:
                    return borderIsOn(TOP, px, py, area) || borderIsOn(RIGHT, px, py, area)
                        || borderIsOn(BOTTOM, px, py, area) || borderIsOn(LEFT, px, py, area);
                default: return borderIsOn(kind, px, py, area);
            }
        }
    }

    /** { isOn } */
    static final class Border implements Js.Obj {
        final IsOn isOn;

        Border(IsOn isOn) { this.isOn = isOn; }

        @Override public Object get(String key) { return key.equals("isOn") ? isOn : null; }
    }

    /** environment.workArea / screen */
    static final class WorkArea implements Js.Obj {
        final Area a;

        WorkArea(Area a) { this.a = a; }

        @Override public Object get(String key) {
            switch (key) {
                case "top": return a.top;
                case "right": return a.right;
                case "bottom": return a.bottom;
                case "left": return a.left;
                case "width": return a.right - a.left;
                case "height": return a.bottom - a.top;
                case "topBorder": return new Border(new IsOn(TOP, a));
                case "rightBorder": return new Border(new IsOn(RIGHT, a));
                case "bottomBorder": return new Border(new IsOn(BOTTOM, a));
                case "leftBorder": return new Border(new IsOn(LEFT, a));
                case "isOn": return new IsOn(IsOn.ANY, a);
                default: return null;
            }
        }
    }

    /** environment.activeIE：网页里没有"活动窗口"，永远不可见 */
    static final Js.Obj ACTIVE_IE = new Js.Obj() {
        final IsOn never = new IsOn(IsOn.NEVER, null);
        final Border border = new Border(never);
        final Double zero = 0.0;

        @Override public Object get(String key) {
            switch (key) {
                case "visible": return Boolean.FALSE;
                case "left": case "right": case "top": case "bottom": case "width": case "height": return zero;
                case "topBorder": case "bottomBorder": case "leftBorder": case "rightBorder": return border;
                case "isOn": return never;
                default: return null;
            }
        }
    };

    /** mascot.environment */
    static final class Env implements Js.Obj {
        final Area a;
        final Cursor cursor;

        Env(Area a, Cursor cursor) {
            this.a = a;
            this.cursor = cursor;
        }

        @Override public Object get(String key) {
            switch (key) {
                case "floor": return new Border(new IsOn(IsOn.FLOOR, a));
                case "ceiling": return new Border(new IsOn(IsOn.CEILING, a));
                case "wall": return new Border(new IsOn(IsOn.WALL, a));
                case "workArea": case "screen": return new WorkArea(a);
                case "activeIE": return ACTIVE_IE;
                case "cursor": return cursor;
                default: return null;
            }
        }
    }

    /** Math（random 用 World 的随机数，其余同 JS） */
    static final class MathObj implements Js.Obj {
        private final World.Random rng;
        private final java.util.Map<String, Object> members = new java.util.HashMap<>();

        MathObj(World.Random rng) {
            this.rng = rng;
            members.put("PI", Math.PI);
            members.put("E", Math.E);
            members.put("random", (F) a -> this.rng.next());
            members.put("abs", (F) a -> Math.abs(arg(a, 0)));
            members.put("floor", (F) a -> Math.floor(arg(a, 0)));
            members.put("ceil", (F) a -> Math.ceil(arg(a, 0)));
            members.put("round", (F) a -> Js.round(arg(a, 0)));
            members.put("trunc", (F) a -> Js.trunc(arg(a, 0)));
            members.put("sign", (F) a -> { double v = arg(a, 0); return v > 0 ? 1.0 : v < 0 ? -1.0 : v; });
            members.put("sqrt", (F) a -> Math.sqrt(arg(a, 0)));
            members.put("pow", (F) a -> Math.pow(arg(a, 0), arg(a, 1)));
            members.put("sin", (F) a -> StrictMath.sin(arg(a, 0)));
            members.put("cos", (F) a -> StrictMath.cos(arg(a, 0)));
            members.put("tan", (F) a -> StrictMath.tan(arg(a, 0)));
            members.put("atan", (F) a -> StrictMath.atan(arg(a, 0)));
            members.put("atan2", (F) a -> StrictMath.atan2(arg(a, 0), arg(a, 1)));
            members.put("exp", (F) a -> StrictMath.exp(arg(a, 0)));
            members.put("log", (F) a -> StrictMath.log(arg(a, 0)));
            members.put("hypot", (F) a -> Js.hypot(arg(a, 0), arg(a, 1)));
            members.put("min", (F) a -> minMax(a, true));
            members.put("max", (F) a -> minMax(a, false));
        }

        private interface F extends Js.Fn {
            @Override default Object get(String key) { return null; }
        }

        private static double arg(Object[] a, int i) { return Js.toNumber(i < a.length ? a[i] : Js.UNDEF); }

        private static double minMax(Object[] a, boolean min) {
            double r = min ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            boolean nan = false;
            for (Object o : a) {
                double v = Js.toNumber(o);
                if (v != v) { nan = true; continue; }
                if (min ? (v < r || (v == 0 && r == 0 && 1 / v < 0)) : (v > r || (v == 0 && r == 0 && 1 / v > 0))) r = v;
            }
            return nan ? Double.NaN : r;
        }

        @Override public Object get(String key) { return members.get(key); }
    }
}
