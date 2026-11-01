package com.x3card.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 时钟下面的胶囊一行（同 .clock__extra / .pill）：倒数日、生日、陪伴时长、车况（告警、电量、油量、室外温度）、歌名；
 * 半透明胶囊 + 前面一个色点，放不下自动折行、每行居中。歌名胶囊能点（展开成小播放器：圆封面 + 歌名歌手 + 上一首 / 暂停 / 下一首，同 music.js）。
 * 一个 View 画完整行，只有内容变了才重画；只在歌名胶囊、小播放器上接触摸
 */
final class Pills extends View {
    /** 一个胶囊：cls 同网页版的类名（pill-ev lv-hi charging…），kind 0 普通 / 1 能点的歌名 / 2 小播放器 */
    static final class Pill {
        String cls, text;
        int kind;
        // 小播放器
        String title, sub;
        Bitmap cover;
        boolean playing, canPrev, canToggle, canNext, in;
        boolean paused;
        Pill(String cls, String text) { this.cls = cls; this.text = text; }
        boolean same(Pill o) {
            return o != null && kind == o.kind && TextUtils.equals(cls, o.cls) && TextUtils.equals(text, o.text) && TextUtils.equals(title, o.title) && TextUtils.equals(sub, o.sub)
                && cover == o.cover && playing == o.playing && canPrev == o.canPrev && canToggle == o.canToggle && canNext == o.canNext && paused == o.paused;
        }
    }
    interface Listener { void pillTap(String act); }

    private final U u;
    Listener listener;
    private List<Pill> list = new ArrayList<>();
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mt = new Paint(Paint.ANTI_ALIAS_FLAG), ms = new Paint(Paint.ANTI_ALIAS_FLAG), ic = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int scene;
    private boolean touchable = true;
    // 布局结果（像素）：每个胶囊的框，小播放器里几个键的圆心
    private final ArrayList<RectF> boxes = new ArrayList<>();
    private final ArrayList<String> rowText = new ArrayList<>();
    private float contentH, laidW;
    private final RectF miniBox = new RectF();
    private final float[] keyX = new float[3];
    private final String[] keyAct = new String[3];
    private float keyY, keyR, keyBigR;
    private int keyN;
    private String pressed;
    private long pressedAt, miniInAt;

    private static final Ease POP = new Ease(.2f, .9f, .3f, 1.1f);
    Pills(Context c, U u) {
        super(c);
        this.u = u;
        tp.setTypeface(Fonts.sys);
        mt.setTypeface(Fonts.sysBold);
        ms.setTypeface(Fonts.sys);
        ring.setStyle(Paint.Style.STROKE);
    }

    void setScene(int s) { if (s == scene) return; scene = s; invalidate(); }
    /** 开着页面时胶囊不接触摸（同 .mode-page .clock .ui） */
    void setTouchable(boolean t) { touchable = t; }

    /** 换一组胶囊（和上次一样就不动） */
    void set(List<Pill> l) {
        boolean same = l.size() == list.size();
        for (int i = 0; same && i < l.size(); i++) same = l.get(i).same(list.get(i));
        if (same) return;
        for (Pill p : l) if (p.kind == 2 && p.in) miniInAt = SystemClock.uptimeMillis();
        list = l;
        laidW = -1;
        requestLayoutish();
        invalidate();
    }
    private void requestLayoutish() { contentH = -1; }

    private float rem() { return u.px(u.rem); }
    /** 内容的高度（像素）：几行胶囊 */
    float contentH(float W) { if (laidW != W || contentH < 0) lay(W); return contentH; }

    private void fonts() {
        float rem = rem();
        tp.setTextSize(.25f * rem); tp.setLetterSpacing(.04f);
        mt.setTextSize(.23f * rem); ms.setTextSize(.18f * rem);
    }
    private float pillW(Pill p) {
        float rem = rem();
        if (p.kind == 2) return miniW(p);
        float w = .17f * rem + .09f * rem + .1f * rem + tp.measureText(p.text) + .19f * rem;
        if (p.cls.startsWith("pill-music")) w = Math.min(w, 3.9f * rem);
        return w;
    }
    private float miniW(Pill p) {
        float rem = rem();
        float tw = Math.min(2.4f * rem, Math.max(mt.measureText(p.title), ms.measureText(p.sub)));
        int n = (p.canPrev ? 1 : 0) + (p.canToggle ? 1 : 0) + (p.canNext ? 1 : 0);
        return .07f * rem + .56f * rem + .12f * rem + tw + .1f * rem + n * .55f * rem + (p.canToggle ? .04f * rem : 0) + .1f * rem;
    }
    private void lay(float W) {
        fonts();
        laidW = W;
        boxes.clear();
        float rem = rem(), gap = .12f * rem, ph = Fonts.lineH(tp) + .13f * rem, rowH = ph + .08f * rem;
        ArrayList<int[]> rows = new ArrayList<>();
        ArrayList<Float> rowW = new ArrayList<>();
        int start = 0;
        float x = 0;
        for (int i = 0; i < list.size(); i++) {
            float w = pillW(list.get(i)) + gap;
            if (x > 0 && x + w > W) { rows.add(new int[] { start, i }); rowW.add(x); start = i; x = 0; }
            x += w;
        }
        if (start < list.size()) { rows.add(new int[] { start, list.size() }); rowW.add(x); }
        float y = 0;
        for (int r = 0; r < rows.size(); r++) {
            int[] rg = rows.get(r);
            float rx = (W - rowW.get(r)) / 2 + gap / 2;
            for (int i = rg[0]; i < rg[1]; i++) {
                Pill p = list.get(i);
                float w = pillW(p);
                if (p.kind == 2) { // 上下用负边距：展开时这一行不变高
                    float mh = .56f * rem + .14f * rem + 2;
                    boxes.add(new RectF(rx, y + (ph - mh) / 2 - .04f * rem, rx + w, y + (ph - mh) / 2 - .04f * rem + mh));
                } else boxes.add(new RectF(rx, y, rx + w, y + ph));
                rx += w + gap;
            }
            y += rowH;
        }
        contentH = rows.isEmpty() ? 0 : y;
    }

    private int dotColor(String cls) {
        if (cls.contains("lv-hi")) return 0xff5fe08a;
        if (cls.contains("lv-mid")) return 0xffffb547;
        if (cls.contains("lv-low")) return 0xffff5a5a;
        if (cls.startsWith("pill-cd")) return 0xffffb36b;
        if (cls.startsWith("pill-bd")) return 0xffff8fb1;
        if (cls.startsWith("pill-ev")) return 0xff5fe08a;
        if (cls.startsWith("pill-fuel")) return 0xffffd36b;
        if (cls.startsWith("pill-temp")) return 0xff8fd3ff;
        if (cls.startsWith("pill-music")) return cls.contains("paused") ? 0xffa3abbd : 0xffc9a7ff;
        if (cls.startsWith("pill-warn")) return 0xffff6b6b;
        return 0xff7ee0b5;
    }

    /** 上下留出的余量（像素）：小播放器上下伸出这一行，View 要比内容高一点才不被裁掉 */
    float pad() { return .2f * rem(); }
    @Override protected void onDraw(Canvas c) {
        float W = getWidth();
        if (laidW != W || contentH < 0) lay(W);
        float rem = rem();
        c.translate(0, pad());
        long now = SystemClock.uptimeMillis();
        boolean anim = false;
        for (int i = 0; i < list.size() && i < boxes.size(); i++) {
            Pill p = list.get(i);
            RectF b = boxes.get(i);
            if (p.kind == 2) { anim |= drawMini(c, p, b, rem, now); continue; }
            float r = b.height() / 2;
            bg.setColor(p.cls.startsWith("pill-warn") ? Ease.rgba(220, 50, 50, .45f) : scene == Sky.DUSK ? Ease.rgba(84, 26, 58, .42f) : scene == Sky.NIGHT ? Ease.rgba(255, 255, 255, .08f) : Ease.rgba(16, 48, 104, .42f));
            float k = p.kind == 1 && p.text.equals(pressed) && now - pressedAt < 160 ? .95f : 1;
            c.save();
            c.scale(k, k, b.centerX(), b.centerY());
            c.drawRoundRect(b, r, r, bg);
            ring.setStrokeWidth(1);
            ring.setColor(Ease.rgba(255, 255, 255, .22f));
            c.drawRoundRect(b.left + .5f, b.top + .5f, b.right - .5f, b.bottom - .5f, r, r, ring);
            float dx = b.left + .17f * rem, dr = .045f * rem, cy = b.top + .06f * rem + Fonts.lineH(tp) / 2;
            int dc = dotColor(p.cls);
            if (p.cls.contains("charging")) { dot.setColor(dc); Fonts.shadow(dot, 0, .07f * rem * 2, dc); }
            dot.setColor(dc);
            c.drawCircle(dx + dr, cy + .01f * rem, dr, dot);
            dot.clearShadowLayer();
            tp.setColor(0xffffffff);
            float tx = dx + .09f * rem + .1f * rem, maxW = b.right - .19f * rem - tx;
            CharSequence t = p.cls.startsWith("pill-music") ? TextUtils.ellipsize(p.text, ellip(tp), maxW + 2, TextUtils.TruncateAt.END) : p.text;
            c.drawText(t, 0, t.length(), tx, b.top + .06f * rem + Fonts.base(tp), tp);
            c.restore();
            if (k < 1) anim = true;
        }
        if (anim) postInvalidateOnAnimation();
    }

    private final android.text.TextPaint ep = new android.text.TextPaint();
    private android.text.TextPaint ellip(Paint src) { ep.set(src); return ep; }
    private final Path icPath = new Path();
    private final Matrix icM = new Matrix();
    private BitmapShader coverShader;
    private Bitmap coverOf;
    private final Matrix coverM = new Matrix();
    private boolean drawMini(Canvas c, Pill p, RectF b, float rem, long now) {
        float t = (now - miniInAt) / 280f;
        boolean anim = false;
        c.save();
        if (t < 1) { // 展开：从 0.9 倍弹出来
            float k = POP.at(Math.max(0, t));
            float s = .9f + .1f * k;
            c.scale(s, s, b.centerX(), b.centerY());
            anim = true;
        }
        float r = b.height() / 2;
        bg.setColor(Ease.rgba(16, 20, 32, .62f));
        Fonts.shadow(bg, .04f * rem, .2f * rem, Ease.rgba(0, 0, 0, .3f));
        c.drawRoundRect(b, r, r, bg);
        bg.clearShadowLayer();
        ring.setStrokeWidth(1);
        ring.setColor(Ease.rgba(255, 255, 255, .14f));
        c.drawRoundRect(b.left + .5f, b.top + .5f, b.right - .5f, b.bottom - .5f, r, r, ring);
        float cx = b.left + .07f * rem + .28f * rem, cy = b.centerY(), cr = .28f * rem;
        if (p.cover != null && !p.cover.isRecycled()) {
            if (coverOf != p.cover) { coverOf = p.cover; coverShader = new BitmapShader(p.cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); }
            float s = 2 * cr / Math.min(p.cover.getWidth(), p.cover.getHeight());
            coverM.setScale(s, s);
            coverM.postTranslate(cx - p.cover.getWidth() * s / 2, cy - p.cover.getHeight() * s / 2);
            coverShader.setLocalMatrix(coverM);
            dot.setShader(coverShader);
            c.drawCircle(cx, cy, cr, dot);
            dot.setShader(null);
        } else {
            dot.setColor(0xff3a3f55);
            c.drawCircle(cx, cy, cr, dot);
            icon(c, "M10 4v10.6A3.5 3.5 0 1 0 12 17.7V8h6V4z", cx, cy, cr - .14f * rem, 0xffc9a7ff);
        }
        ring.setStrokeWidth(.02f * rem);
        ring.setColor(Ease.rgba(255, 255, 255, .25f));
        c.drawCircle(cx, cy, cr + .01f * rem, ring);
        float tx = cx + cr + .12f * rem, tw = Math.min(2.4f * rem, Math.max(mt.measureText(p.title), ms.measureText(p.sub)));
        float lh1 = .23f * rem * 1.25f, lh2 = .18f * rem * 1.25f, ty = cy - (lh1 + lh2) / 2;
        mt.setColor(0xffffffff);
        CharSequence t1 = TextUtils.ellipsize(p.title, ellip(mt), tw + 2, TextUtils.TruncateAt.END);
        c.drawText(t1, 0, t1.length(), tx, ty + (lh1 - Fonts.lineH(mt)) / 2 + Fonts.base(mt), mt);
        ms.setColor(Ease.alpha(0xffffffff, .7f));
        CharSequence t2 = TextUtils.ellipsize(p.sub, ellip(ms), tw + 2, TextUtils.TruncateAt.END);
        c.drawText(t2, 0, t2.length(), tx, ty + lh1 + (lh2 - Fonts.lineH(ms)) / 2 + Fonts.base(ms), ms);
        float x = tx + tw + .1f * rem;
        keyN = 0;
        keyY = cy; keyR = .26f * rem; keyBigR = .28f * rem;
        String[] acts = { p.canPrev ? "previous" : null, p.canToggle ? "toggle" : null, p.canNext ? "next" : null };
        for (String a : acts) {
            if (a == null) continue;
            boolean big = a.equals("toggle");
            float kr = big ? keyBigR : keyR, kx = x + .03f * rem + kr;
            float s = a.equals(pressed) && now - pressedAt < 160 ? .88f : 1;
            if (s < 1) anim = true;
            c.save();
            c.scale(s, s, kx, cy);
            if (big) { dot.setColor(0xffffffff); c.drawCircle(kx, cy, kr, dot); }
            String d = a.equals("previous") ? "M6 5h2.4v14H6z M19 5.6v12.8L9.4 12z" : a.equals("next") ? "M15.6 5H18v14h-2.4z M5 5.6v12.8L14.6 12z" : p.playing ? "M7 5h3.6v14H7z M13.4 5H17v14h-3.6z" : "M8 5.2v13.6L19 12z";
            icon(c, d, kx, cy, kr - (big ? .15f : .13f) * rem, big ? 0xff1d2433 : 0xffffffff);
            c.restore();
            keyX[keyN] = kx; keyAct[keyN] = a; keyN++;
            x = kx + kr;
        }
        miniBox.set(b);
        c.restore();
        return anim;
    }
    /** 24×24 的图标画在 (cx, cy)，半边长 half */
    private void icon(Canvas c, String d, float cx, float cy, float half, int color) {
        Path src = SvgPath.of(d);
        icM.setScale(half / 12, half / 12);
        icM.postTranslate(cx - half, cy - half);
        src.transform(icM, icPath);
        ic.setColor(color);
        c.drawPath(icPath, ic);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!touchable) return false;
        float x = e.getX(), y = e.getY() - pad();
        String act = hitAct(x, y);
        if (act == null) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { pressed = act; pressedAt = SystemClock.uptimeMillis(); invalidate(); }
        if (e.getActionMasked() == MotionEvent.ACTION_UP && listener != null) { String a = act; post(() -> listener.pillTap(a)); }
        return true;
    }
    /** 这一点点到了什么：小播放器的键 / 小播放器别处（收回）/ 歌名胶囊（展开）；都不是返回 null */
    String hitAct(float x, float y) {
        float rem = rem();
        for (int i = 0; i < list.size() && i < boxes.size(); i++) {
            Pill p = list.get(i);
            RectF b = boxes.get(i);
            if (p.kind == 2 && b.contains(x, y)) {
                for (int k = 0; k < keyN; k++) if (Math.abs(x - keyX[k]) <= keyR + .04f * rem && Math.abs(y - keyY) <= keyR + .06f * rem) return keyAct[k];
                return "mini";
            }
            if (p.kind == 1 && b.contains(x, y)) return "open";
        }
        return null;
    }
}
