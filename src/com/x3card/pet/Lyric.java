package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

/**
 * 实时歌词（同 .lyric）：当前一句（KTV 从左往右填色）+ 下一句。换句时旧的往上淡出、新的从下面浮上来。
 * 填色：底下一层淡色字、上面一层亮色字，亮色那层只露出唱过的部分（改 clipBounds，不重画字）；
 * 放不下的长句先缩字号（最小 3/4），还放不下整句跟着填色往左移
 */
final class Lyric extends Layer implements Frames.Client {
    private static final Ease MOVE = new Ease(.2f, .8f, .2f, 1);
    private final U u;
    private final Frames frames;
    private Line cur, old;
    private final NextView next;
    /** 当前句的框（同 .lyric__now 的 overflow: hidden）：新句从下面浮上来、旧句往上走都裁在这一行里 */
    private final Layer box;
    private boolean on;
    private float onAt = -1;
    private long shownAt;
    int scene;
    int shadowColor;
    private float W, top;

    Lyric(Context c, U u, Frames frames) {
        super(c);
        this.u = u;
        this.frames = frames;
        box = add(new Layer(c));
        box.setClipChildren(true);
        next = add(new NextView(c));
        next.setLayerType(LAYER_TYPE_HARDWARE, null);
        setVisibility(GONE);
    }

    void setScene(int s, int shadow) {
        scene = s; shadowColor = shadow;
        if (cur != null) cur.style();
        if (old != null) old.style();
        next.invalidate();
    }

    /** 显示 / 收起整块（同 .lyric.on，出现时淡入 0.5 秒） */
    void show(boolean v) {
        if (v == on) return;
        on = v;
        setVisibility(v ? VISIBLE : GONE);
        if (v) { shownAt = SystemClock.uptimeMillis(); wake(); }
    }
    boolean shown() { return on; }

    /** 摆在时钟的 y（像素）处 */
    void placeAt(float y, float W) {
        float rem = u.px(u.rem);
        this.W = W; top = y;
        Layer.place(this, 0, y, W, (.6f + .4f) * rem);
        Layer.place(box, 0, 0, W, .6f * rem);
        if (cur != null) cur.lay();
        if (old != null) old.lay();
        Layer.place(next, 0, .6f * rem, W, .4f * rem);
    }

    /** 换一句：ms > 0 时用这么久从左扫到右（延时 0.15 秒）；ms = 0 整句直接亮；instant 不播进出场 */
    void line(String t, int ms, boolean instant) {
        long now = SystemClock.uptimeMillis();
        if (old != null) { box.removeView(old); old = null; }
        if (cur != null) {
            if (instant) box.removeView(cur);
            else { old = cur; old.outAt = now; }
        }
        cur = new Line(getContext(), t);
        box.addView(cur, new Lp());
        cur.inAt = instant ? 0 : now;
        cur.fillMs = ms;
        cur.fillAt = now + 150;
        cur.lay();
        // 第一帧就是进场的起点（透明、在下面、还没填色），不然加进来那一帧整句亮色闪一下
        if (!instant) { cur.setAlpha(0); cur.setTranslationY(.3f * u.px(u.rem)); }
        cur.fill(now);
        wake();
    }
    void clear() {
        if (cur != null) box.removeView(cur);
        if (old != null) box.removeView(old);
        cur = old = null;
        next.set("");
    }
    void setNext(String t) { next.set(t); wake(); }

    void wake() { frames.add(this, 60); }

    @Override public boolean frame(long now) {
        if (!on) return false;
        float rem = u.px(u.rem);
        boolean busy = false;
        float k = Ease.clamp((now - shownAt) / 500f, 0, 1);
        setAlpha(Ease.EASE.at(k));
        busy |= k < 1;
        if (old != null) {
            float t = Ease.clamp((now - old.outAt) / 400f, 0, 1), e = Ease.IN.at(t);
            old.setAlpha(1 - e);
            old.setTranslationY(-.3f * rem * e);
            if (t >= 1) { box.removeView(old); old = null; } else busy = true;
        }
        boolean filling = false;
        if (cur != null) {
            if (cur.inAt > 0) {
                float t = Ease.clamp((now - cur.inAt) / 450f, 0, 1), e = MOVE.at(t);
                cur.setAlpha(e);
                cur.setTranslationY(.3f * rem * (1 - e));
                busy |= t < 1;
            }
            filling = cur.fill(now);
        }
        busy |= next.step(now);
        if (busy) return true;
        if (filling) { frames.add(this, 30); return true; }
        return false;
    }

    /** 一句：底下淡色字、上面亮色字（clipBounds 露出唱过的部分） */
    final class Line extends Layer {
        final String text;
        final Txt2 base, ink;
        long inAt, outAt, fillAt;
        int fillMs;
        float runW, room, over;
        private final Rect clip = new Rect();
        Line(Context c, String t) {
            super(c);
            text = t;
            base = add(new Txt2(c, false));
            ink = add(new Txt2(c, true));
            base.setLayerType(LAYER_TYPE_HARDWARE, null);
            ink.setLayerType(LAYER_TYPE_HARDWARE, null);
            style();
        }
        void style() { base.invalidate(); ink.invalidate(); }
        void lay() {
            float rem = u.px(u.rem);
            Layer.place(this, 0, 0, W, .6f * rem);
            float fs = .4f * rem;
            base.p.setTextSize(fs); ink.p.setTextSize(fs);
            room = W * .96f;
            runW = base.p.measureText(text);
            if (runW > room && runW > 0) { // 缩字号（最小 3/4）
                fs = Math.max(fs * room / runW, fs * .75f);
                base.p.setTextSize(fs); ink.p.setTextSize(fs);
                runW = base.p.measureText(text);
            }
            over = Math.max(0, runW - room);
            float w = Math.min(runW, room), x = (W - w) / 2;
            Layer.place(base, x, 0, w, .6f * rem);
            Layer.place(ink, x, 0, w, .6f * rem);
            base.invalidate(); ink.invalidate();
        }
        /** 走一帧填色；返回还在填 */
        boolean fill(long now) {
            float k;
            if (fillMs <= 0) k = 1;
            else k = Ease.clamp((now - fillAt) / (float) fillMs, 0, 1);
            float run = -over * k;
            base.run = ink.run = run;
            int w = ink.getWidth(), h = ink.getHeight();
            // 亮色的窗口：整句宽度 × k（按整句算，长句往左移时颜色和字对得上）
            int right = Math.round(run + runW * k);
            clip.set(0, 0, Math.max(0, Math.min(w, right)), h);
            ink.setClipBounds(clip);
            if (over > 0) { base.invalidate(); ink.invalidate(); }
            return k < 1;
        }
    }

    /** 一层字（淡色 / 亮色） */
    final class Txt2 extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final boolean ink;
        float run;
        Txt2(Context c, boolean ink) {
            super(c);
            this.ink = ink;
            p.setTypeface(Fonts.sysBold);
            p.setLetterSpacing(.04f);
        }
        @Override protected void onDraw(Canvas c) {
            Line l = (Line) getParent();
            if (l == null) return;
            float rem = u.px(u.rem);
            int col = ink ? (scene == Sky.NIGHT ? 0xffffd6f0 : scene == Sky.DUSK ? 0xffffc76a : 0xffffd84a)
                : Ease.alpha(0xffffffff, scene == Sky.NIGHT ? .5f : .72f);
            p.setColor(col);
            Txt.Shadow[] sh = shadows(rem);
            float by = Fonts.baseIn(p, .6f * rem);
            Txt.draw(c, l.text, run + p.getLetterSpacing() * p.getTextSize() / 2, by, p, sh, 1);
        }
    }
    private Txt.Shadow[] sh;
    private int shKey = -1;
    Txt.Shadow[] shadows(float rem) {
        int key = scene * 1000 + Math.round(rem);
        if (key != shKey) {
            shKey = key;
            int edge = scene == Sky.DUSK ? Ease.rgba(80, 22, 44, 1) : Ease.rgba(12, 36, 84, 1);
            sh = scene == Sky.NIGHT ? new Txt.Shadow[] { new Txt.Shadow(.02f * rem, .12f * rem, shadowColor) }
                : new Txt.Shadow[] { new Txt.Shadow(0, .05f * rem, Ease.alpha(edge, .65f)), new Txt.Shadow(.02f * rem, .14f * rem, Ease.alpha(edge, .5f)) };
        }
        return sh;
    }

    /** 下一句：小一号、淡一点，放不下省略；换了就淡入（延时 0.1 秒） */
    final class NextView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint ep = new android.text.TextPaint();
        private String text = "";
        private long at;
        NextView(Context c) { super(c); p.setTypeface(Fonts.sys); }
        void set(String t) { if (t.equals(text)) return; text = t; at = SystemClock.uptimeMillis(); setAlpha(0); invalidate(); } // 换了马上透明，再按 step 淡入
        boolean step(long now) {
            float k = Ease.clamp((now - at - 100) / 450f, 0, 1);
            setAlpha(Ease.EASE.at(k));
            return k < 1;
        }
        @Override protected void onDraw(Canvas c) {
            if (text.isEmpty()) return;
            float rem = u.px(u.rem);
            p.setTextSize(.28f * rem);
            p.setColor(Ease.alpha(0xffffffff, scene == Sky.NIGHT ? .55f : .8f));
            ep.set(p);
            CharSequence t = android.text.TextUtils.ellipsize(text, ep, getWidth(), android.text.TextUtils.TruncateAt.END);
            float w = p.measureText(t, 0, t.length());
            Txt.Shadow[] s = scene == Sky.NIGHT ? new Txt.Shadow[] { new Txt.Shadow(.01f * rem, .08f * rem, shadowColor) } : shadows(rem);
            Txt.draw(c, t, (getWidth() - w) / 2, Fonts.baseIn(p, .4f * rem), p, s, 1);
        }
    }
}
