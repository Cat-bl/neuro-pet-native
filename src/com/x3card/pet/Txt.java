package com.x3card.pet;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.SparseArray;

/** 带几层投影的字（同 CSS 的 text-shadow: a, b）：投影用模糊遮罩单独画在下面，字画在最上面 */
final class Txt {
    /** 一层投影：偏移、模糊（CSS 的 blur，像素）、颜色 */
    static final class Shadow {
        final float dy, blur;
        final int color;
        Shadow(float dy, float blur, int color) { this.dy = dy; this.blur = blur; this.color = color; }
    }

    private static final SparseArray<BlurMaskFilter> BLUR = new SparseArray<>();
    private static BlurMaskFilter blur(float cssBlur) {
        int key = Math.max(1, Math.round(cssBlur * 4));
        BlurMaskFilter f = BLUR.get(key);
        if (f == null) {
            float r = Math.max(0.5f, (key / 4f / 2 - 0.5f) / 0.57735f);
            f = new BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL);
            BLUR.put(key, f);
        }
        return f;
    }
    private static final Paint SH = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 画一行字（x 是起点，y 是基线），先画投影再画字；alpha 乘到投影和字上 */
    static void draw(Canvas c, CharSequence s, float x, float y, Paint p, Shadow[] sh, float alpha) {
        if (sh != null) {
            SH.set(p);
            SH.clearShadowLayer();
            for (Shadow d : sh) {
                if (d == null) continue;
                SH.setColor(Ease.alpha(d.color, alpha));
                SH.setMaskFilter(d.blur > 0.3f ? blur(d.blur) : null);
                c.drawText(s, 0, s.length(), x, y + d.dy, SH);
            }
            SH.setMaskFilter(null);
        }
        int col = p.getColor();
        if (alpha < 1) p.setColor(Ease.alpha(col, alpha));
        c.drawText(s, 0, s.length(), x, y, p);
        p.setColor(col);
    }
}
