package com.x3card.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * 歌曲封面（同 .cover）：放歌时铺在时间后面——数字时间是和数字一样高的圆角方形，表盘是表盘里的圆（上面压一层 28% 暗色）。
 * 两张图交叉淡化（换歌时新图到了一起一个淡入一个淡出），换下来的那张淡完就交还（onDrop），最多拿着两张
 */
final class Cover extends View {
    interface Drop { void drop(Bitmap b); }

    private final U u;
    private final Frames frames;
    Drop onDrop;
    private Bitmap front, back;
    private String frontKey = "", backKey = "";
    private long swapAt = -10000;
    private final Tw shownK = new Tw(0), swapHide = new Tw(1);
    private boolean dial, portrait;
    private float box, offY;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), sh = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final RectF r = new RectF();

    Cover(Context c, U u, Frames frames) { super(c); this.u = u; this.frames = frames; setAlpha(0); }

    String key() { return frontKey; }
    Bitmap current() { return front; }
    boolean on() { return shownK.target() > 0; }

    /** 显示 / 收起（0.6 秒淡入淡出、往下沉一点） */
    void show(boolean v) { shownK.to(v ? 1 : 0, 600, Ease.EASE, SystemClock.uptimeMillis()); }
    /** 换数字 / 表盘：先藏 0.2 秒，换好形状 ms 后再出来 */
    void swapHide(int ms) {
        long now = SystemClock.uptimeMillis();
        swapHide.to(0, 200, Ease.EASE, now);
        postDelayed(() -> swapHide.to(1, 600, Ease.EASE, SystemClock.uptimeMillis()), ms);
    }
    /** 新图：放进后面那层，和前面那层交叉淡化（0.7 秒） */
    void image(Bitmap b, String key) {
        if (back != null && back != b && onDrop != null) onDrop.drop(back);
        back = front;
        backKey = frontKey;
        front = b;
        frontKey = key;
        swapAt = SystemClock.uptimeMillis();
        invalidate();
        postDelayed(this::dropBack, 800);
    }
    private void dropBack() {
        if (SystemClock.uptimeMillis() - swapAt < 700 || back == null) return;
        if (onDrop != null) onDrop.drop(back);
        back = null; backKey = "";
        invalidate();
    }
    /** 关掉封面：两张都交还 */
    void clear() {
        if (front != null && onDrop != null) onDrop.drop(front);
        if (back != null && onDrop != null) onDrop.drop(back);
        front = back = null; frontKey = backKey = "";
        invalidate();
    }

    void layoutFor(boolean dial, boolean portrait) { this.dial = dial; this.portrait = portrait; invalidate(); }

    /** 摆在时钟顶 y0（像素）下面：大小、位置按数字 / 表盘、横竖 */
    void placeAt(float y0, float W, long now) {
        float rem = u.px(u.rem);
        float size = dial ? (portrait ? 3.1f : 2.84f) : (portrait ? 2.5f : 2.6f), top = dial ? (portrait ? .3f : .28f) : (portrait ? -.34f : 0);
        box = size * rem;
        float pad = .6f * rem;
        Layer.place(this, W / 2 - box / 2 - pad, y0 + top * rem - pad, box + 2 * pad, box + 2 * pad);
        float k = shownK.get(now) * swapHide.get(now);
        setAlpha(k);
        setTranslationY((1 - k) * .15f * rem);
        setVisibility(k < 0.003f ? INVISIBLE : VISIBLE);
    }
    boolean animating(long now) { return shownK.moving(now) || swapHide.moving(now) || now - swapAt < 750; }

    @Override protected void onDraw(Canvas c) {
        float rem = u.px(u.rem), pad = .6f * rem;
        r.set(pad, pad, pad + box, pad + box);
        float rad = dial ? box / 2 : .3f * rem;
        sh.setColor(0xff000000);
        Fonts.shadow(sh, .06f * rem, .3f * rem, Ease.rgba(0, 0, 0, .35f));
        sh.setColor(0x01000000);
        c.drawRoundRect(r, rad, rad, sh);
        sh.clearShadowLayer();
        long now = SystemClock.uptimeMillis();
        float k = Ease.clamp((now - swapAt) / 700f, 0, 1), e = Ease.EASE.at(k);
        if (back != null && k < 1) img(c, back, 1 - e, rad);
        if (front != null) img(c, front, back != null ? e : 1, rad);
        if (dial) { sh.setColor(Ease.rgba(0, 0, 0, .28f)); c.drawRoundRect(r, rad, rad, sh); }
        if (k < 1) postInvalidateOnAnimation();
    }
    private void img(Canvas c, Bitmap b, float a, float rad) {
        if (b == null || b.isRecycled() || a <= 0.003f) return;
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float sc = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight()); // object-fit: cover
        m.setScale(sc, sc);
        m.postTranslate(r.centerX() - b.getWidth() * sc / 2, r.centerY() - b.getHeight() * sc / 2);
        s.setLocalMatrix(m);
        p.setShader(s);
        p.setAlpha(Math.round(a * 255));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
    }
}
