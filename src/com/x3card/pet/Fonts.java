package com.x3card.pet;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;

/** 字体：时钟数字 Oswald 500（数字时间的大号窄体）、Outfit 300 / 500（表盘、小号数字）；中文跟系统字体 */
final class Fonts {
    static Typeface tall, digits, digitsLight, sys, sysMedium, sysBold;

    static void init(Context c) {
        if (tall != null) return;
        tall = load(c, "Oswald.ttf", 500);
        digits = load(c, "Outfit.ttf", 500);
        digitsLight = load(c, "Outfit.ttf", 300);
        sys = Typeface.DEFAULT;
        sysMedium = Typeface.create(Typeface.DEFAULT, 500, false);
        sysBold = Typeface.create(Typeface.DEFAULT, 600, false);
    }
    private static Typeface load(Context c, String f, int w) {
        try { return new Typeface.Builder(c.getAssets(), f).setFontVariationSettings("'wght' " + w).build(); }
        catch (Exception e) { return Typeface.DEFAULT_BOLD; }
    }

    /**
     * CSS 的 text-shadow: 0 dy blur color（Paint 的阴影半径换算：Skia 的 σ = 0.57735 × 半径 + 0.5，CSS 的 σ = blur / 2）
     */
    static void shadow(Paint p, float dy, float blur, int color) {
        if (blur <= 0 && dy == 0) { p.clearShadowLayer(); return; }
        float r = Math.max(0.1f, (blur / 2 - 0.5f) / 0.57735f);
        p.setShadowLayer(r, 0, dy, color);
    }

    /** 行高 normal 时一行的高度、基线离行顶多远（同浏览器：按字体的上下伸） */
    static float lineH(Paint p) { Paint.FontMetrics m = p.getFontMetrics(); return m.descent - m.ascent; }
    static float base(Paint p) { return -p.getFontMetrics().ascent; }
    /** line-height 固定为 lh 时的基线（半行距可能是负的，同浏览器） */
    static float baseIn(Paint p, float lh) { Paint.FontMetrics m = p.getFontMetrics(); return (lh - (m.descent - m.ascent)) / 2 - m.ascent; }
}
