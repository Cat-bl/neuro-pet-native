package com.x3card.pet;

/** 缓动曲线（同 CSS 的 cubic-bezier / ease / ease-in-out）和几个插值小工具 */
final class Ease {
    final float x1, y1, x2, y2;
    Ease(float x1, float y1, float x2, float y2) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; }

    static final Ease LINEAR = new Ease(0, 0, 1, 1);
    static final Ease EASE = new Ease(.25f, .1f, .25f, 1);
    static final Ease IN = new Ease(.42f, 0, 1, 1);
    static final Ease OUT = new Ease(0, 0, .58f, 1);
    static final Ease IN_OUT = new Ease(.42f, 0, .58f, 1);
    /** 弹出（稍微冲过头） */
    static final Ease POP10 = new Ease(.2f, .9f, .3f, 1.1f), POP15 = new Ease(.2f, .9f, .3f, 1.15f), POP20 = new Ease(.2f, .9f, .3f, 1.2f);
    /** 滑块、圆点跟过去（回弹） */
    static final Ease SPRING = new Ease(.3f, 1.25f, .5f, 1);
    /** 两页之间推过去 */
    static final Ease SLIDE = new Ease(.65f, 0, .25f, 1);

    private static float c(float a, float b, float t) { float u = 1 - t; return 3 * a * t * u * u + 3 * b * t * t * u + t * t * t; }

    /** 进度 x（0～1）→ 位置：先按 x 求曲线参数 t（牛顿法，几步不收敛再二分），再取 y */
    float at(float x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        if (this == LINEAR) return x;
        float t = x;
        for (int i = 0; i < 6; i++) {
            float u = 1 - t, dx = 3 * x1 * u * u + 6 * (x2 - x1) * t * u + 3 * (1 - x2) * t * t;
            float e = c(x1, x2, t) - x;
            if (Math.abs(e) < 1e-4f) return c(y1, y2, t);
            if (Math.abs(dx) < 1e-5f) break;
            t -= e / dx;
            if (t < 0 || t > 1) break;
        }
        float lo = 0, hi = 1;
        t = x;
        for (int i = 0; i < 20; i++) { t = (lo + hi) / 2; if (c(x1, x2, t) < x) lo = t; else hi = t; }
        return c(y1, y2, t);
    }

    static float clamp(float v, float a, float b) { return v < a ? a : v > b ? b : v; }
    static float lerp(float a, float b, float k) { return a + (b - a) * k; }
    /** 两个颜色（ARGB）按 k 混合 */
    static int mix(int a, int b, float k) {
        if (k <= 0) return a;
        if (k >= 1) return b;
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * k) << 24 | Math.round(ar + (br - ar) * k) << 16 | Math.round(ag + (bg - ag) * k) << 8 | Math.round(ab + (bb - ab) * k);
    }
    /** 颜色乘透明度 */
    static int alpha(int c, float a) { return Math.round((c >>> 24) * clamp(a, 0, 1)) << 24 | (c & 0xffffff); }
    /** "#rrggbb" 或 rgba 写法的数字 → ARGB */
    static int rgba(int r, int g, int b, float a) { return Math.round(a * 255) << 24 | r << 16 | g << 8 | b; }
}
