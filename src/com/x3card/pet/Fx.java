package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/**
 * 视觉特效（同网页版 effects.js 和几处小动画）：节日特效（灯笼、烟花、爱心、气球、彩带、雪）、爆裂（升级、烟花、歌词彩蛋）、
 * 音符、"+1" 飘字、点空地的涟漪、长按的蓄力圈。
 * 下落 / 上升的粒子按远近分三层（远的小、淡、慢、摆幅小），运动同网页版 fall-keyframes 生成的关键帧（左右摆 + 翻转）。
 * 节日特效有东西在动就每秒 30 帧；开着页面时藏起来、停（同 .mode-page .fx）；短暂的特效放完就不要帧
 */
final class Fx {
    private static final String[] FW = { "#ffd93d", "#ff6b6b", "#6bcb77", "#4d96ff", "#ff9ff3", "#ffffff", "#ff9f43" };
    private final U u;
    private final Frames frames;
    private final Random rnd = new Random();
    final FestView fest;
    final TopView top;
    final RippleView ripples;
    private boolean pageMode, hidden;

    Fx(Context c, U u, Frames frames, Layer festHost, Layer topHost, Layer rippleHost) {
        this.u = u; this.frames = frames;
        fest = festHost.add(new FestView(c));
        top = topHost.add(new TopView(c));
        ripples = rippleHost.add(new RippleView(c));
    }
    private float rf(float a, float b) { return a + rnd.nextFloat() * (b - a); }
    static int color(String hex) { return android.graphics.Color.parseColor(hex); }

    void layout() {
        float W = u.px(u.W), H = u.px(u.H);
        Layer.place(fest, 0, 0, W, H);
        Layer.place(top, 0, 0, W, H);
        Layer.place(ripples, 0, 0, W, H);
    }
    void setPageMode(boolean on) {
        if (pageMode == on) return;
        pageMode = on;
        fest.fadeTo(on ? 0 : 1);
    }
    void setHidden(boolean h) {
        hidden = h;
        if (h) fest.removeCallbacks(fwTimer);
        else { if ("fireworks".equals(kind)) { fest.removeCallbacks(fwTimer); fest.postDelayed(fwTimer, 900); } fest.wake(); top.wake(); }
    }
    void destroy() { fest.removeCallbacks(fwTimer); frames.remove(fest); frames.remove(top); frames.remove(ripples); }

    // ================= 节日 =================
    /** 粒子：tier 远近 1～3，anim sf 雪 / up 上升 / cf 彩带 */
    static final class P {
        int anim, tier, shape, color;
        float left, dur, delay, size, ratio, op;
    }
    private String kind = "";
    private final ArrayList<P> ps = new ArrayList<>();
    private boolean lanterns;
    private long festAt;
    private final Runnable fwTimer = new Runnable() {
        @Override public void run() {
            if (!hidden && !pageMode) burstPct(rf(15, 85), rf(10, 42), color(FW[rnd.nextInt(FW.length)]), 18, 0, fest.bursts);
            fest.postDelayed(this, (long) rf(1600, 3200));
        }
    };
    static final int SHAPE_FLAKE = 0, SHAPE_HEART = 1, SHAPE_BALLOON = 2, SHAPE_CONFETTI = 3;
    static final int SF = 0, UP = 1, CF = 2;
    /** 三层（远 / 中 / 近）左右摆幅（rem），同 fall-keyframes 生成的关键帧 */
    private static final float[] AMP_SF = { .07f, .15f, .26f }, AMP_UP = { .07f, .14f, .24f }, AMP_CF = { .08f, .16f, .28f };

    void festival(String k) {
        kind = k == null ? "" : k;
        fest.removeCallbacks(fwTimer);
        ps.clear();
        lanterns = "lantern".equals(kind) || "moon".equals(kind);
        switch (kind) {
            case "hearts": fallers(SHAPE_HEART, 12, UP, 10, 17, .18f, .36f, 1, .5f, .95f, "#ff6b8b", "#ff8fab", "#ff4d6d", "#ffb3c6"); break;
            case "balloons": fallers(SHAPE_BALLOON, 8, UP, 14, 24, .26f, .44f, 1.22f, .6f, .95f, "#ff6b6b", "#ffd93d", "#6bcb77", "#4d96ff", "#c77dff", "#ff9f43"); break;
            case "confetti":
                fallers(SHAPE_CONFETTI, 26, CF, 6, 12, .05f, .1f, 1.8f, .6f, 1, "#ff6b6b", "#ffd93d", "#6bcb77", "#4d96ff", "#c77dff", "#ff9f43", "#ffffff");
                fallers(SHAPE_BALLOON, 5, UP, 16, 24, .28f, .4f, 1.22f, .6f, .95f, "#ff6b6b", "#ffd93d", "#4d96ff", "#c77dff");
                break;
            case "snow": fallers(SHAPE_FLAKE, 46, SF, 8, 18, .05f, .17f, 1, .5f, 1); break;
            case "fireworks": fest.postDelayed(fwTimer, 900); break;
        }
        festAt = SystemClock.uptimeMillis();
        fest.wake();
    }
    /** 数量随宽高比缩放（竖向小容器不会挤成一团） */
    private float density() { float r = u.W / Math.max(1, u.H); return Math.max(0.55f, Math.min(1, r / 1.16f)); }
    private void fallers(int shape, int n, int anim, float min, float max, float s0, float s1, float ratio, float o0, float o1, String... colors) {
        n = Math.round(n * density());
        for (int i = 0; i < n; i++) {
            float z = (i + rnd.nextFloat()) / n;
            P p = new P();
            p.shape = shape; p.anim = anim;
            p.size = s0 + (s1 - s0) * z * z;
            p.dur = (max - (max - min) * z) * rf(0.9f, 1.1f);
            p.op = o0 + (o1 - o0) * z;
            p.tier = z < 0.34f ? 1 : z < 0.7f ? 2 : 3;
            p.color = colors.length > 0 ? color(colors[rnd.nextInt(colors.length)]) : 0xffffffff;
            p.ratio = ratio;
            p.left = rf(-2, 98);
            p.delay = rf(0, p.dur); // 负延时：一开始就分布在画面里
            ps.add(p);
        }
    }

    // ================= 爆裂 / 音符 / 飘字 / 涟漪 =================
    static final class Burst { float x, y, dist; int n, color; long t0; float[] ang, delay; int[] cols; }
    /** 一朵爆裂：在 (x, y) dp 处，count 个点向外散开（color 0 时每个点随机一色），1.4 秒后结束 */
    void burst(float x, float y, int color, int count, float sizeRem) { burstAt(x, y, color, count, sizeRem, top.bursts); top.wake(); }
    private void burstPct(float xp, float yp, int color, int count, float sizeRem, ArrayList<Burst> to) { burstAt(u.W * xp / 100, u.H * yp / 100, color, count, sizeRem, to); fest.wake(); }
    private void burstAt(float x, float y, int color, int count, float sizeRem, ArrayList<Burst> to) {
        Burst b = new Burst();
        b.x = x; b.y = y;
        b.n = count <= 0 ? 16 : count;
        float r = sizeRem > 0 ? sizeRem : rf(0.8f, 1.3f);
        b.dist = r > 1.05f ? 1.35f : .85f;
        b.color = color;
        b.t0 = SystemClock.uptimeMillis();
        b.ang = new float[b.n]; b.delay = new float[b.n]; b.cols = new int[b.n];
        for (int i = 0; i < b.n; i++) { b.ang[i] = 360f / b.n * i + rf(-6, 6); b.delay[i] = rf(0, 80); b.cols[i] = color != 0 ? color : color(FW[rnd.nextInt(FW.length)]); }
        to.add(b);
    }
    static final class Note { float x, y; long t0; String ch; float rot; }
    /** 音符（♪ / ♫）从 (x, y) dp 往上飘 */
    void note(float x, float y) {
        Note n = new Note();
        n.x = x; n.y = y; n.t0 = SystemClock.uptimeMillis(); n.ch = rnd.nextBoolean() ? "♪" : "♫";
        top.notes.add(n);
        top.wake();
    }
    static final class Plus { float x, y; long t0; String text; }
    /** "+1" 飘字（接饼干） */
    void plus(float x, float y, String text) {
        Plus p = new Plus();
        p.x = x; p.y = y; p.t0 = SystemClock.uptimeMillis(); p.text = text;
        top.pluses.add(p);
        top.wake();
    }
    static final class Drop { float x, y; long t0; float delay; }
    /** 云下一阵雨：12 滴从云底掉下来（rect 是云的框，像素） */
    void rain(RectF r) {
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 12; i++) {
            Drop d = new Drop();
            d.x = (r.left + rf(.1f, .9f) * r.width()) / u.dens;
            d.y = (r.bottom - 4 * u.dens) / u.dens;
            d.t0 = t; d.delay = rf(0, 900);
            fest.drops.add(d);
        }
        fest.wake();
    }
    void ripple(float x, float y) { ripples.ripple = new float[] { x, y }; ripples.rippleAt = SystemClock.uptimeMillis(); ripples.wake(); }
    /** 长按的蓄力圈 */
    void ring(float x, float y) { ripples.ring = new float[] { x, y }; ripples.ringAt = SystemClock.uptimeMillis(); ripples.wake(); }
    void ring(boolean on) { if (!on && ripples.ring != null) { ripples.ring = null; ripples.invalidate(); } }

    // ================= 节日层 =================
    final class FestView extends View implements Frames.Client {
        final ArrayList<Burst> bursts = new ArrayList<>();
        final ArrayList<Drop> drops = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Tw alpha = new Tw(1);
        private final Path heart = new Path();
        private final RectF r = new RectF();
        private Shader flake;
        private final Shader[] lanBody = new Shader[2], lanTassel = new Shader[2];
        private final float[] lanKey = new float[2];
        FestView(Context c) { super(c); tp.setTextAlign(Paint.Align.CENTER); }
        void fadeTo(float a) { alpha.to(a, a > 0 ? 1200 : 500, Ease.EASE, SystemClock.uptimeMillis()); wake(); }
        void wake() { if (!hidden) frames.add(this, 60); }
        boolean active() { return !ps.isEmpty() || lanterns || !bursts.isEmpty() || !drops.isEmpty(); }

        @Override public boolean frame(long now) {
            if (hidden) return false;
            float a = alpha.get(now);
            // 进场：开场 2 秒淡入（同 .fx-festival 的 appear）
            float in = Ease.EASE.at(Ease.clamp((now - festAt - 800) / 2000f, 0, 1));
            setAlpha(a * (festAt > 0 ? in : 1));
            setVisibility(a < 0.003f ? INVISIBLE : VISIBLE);
            invalidate();
            if (alpha.moving(now) || !bursts.isEmpty() || !drops.isEmpty()) return true;
            if (a < 0.003f || pageMode) return false; // 开着页面：停（粒子被挡住，不画）
            if (active()) { frames.add(this, 30); return true; }
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float d = u.dens, rem = u.px(u.rem), W = getWidth(), H = getHeight(), vh = H / 100;
            for (P q : ps) drawP(c, q, now, rem, W, vh);
            if (lanterns) { lantern(c, now, u.portrait ? W * .03f : W * .06f, 0, rem, false); lantern(c, now, u.portrait ? W * .97f - .62f * rem : W * .78f - .62f * rem, 0, rem, true); }
            drawBursts(c, bursts, now, d, rem, p);
            for (int i = drops.size() - 1; i >= 0; i--) {
                Drop dr = drops.get(i);
                float t = (now - dr.t0 - dr.delay) / 900f;
                if (t > 1) { drops.remove(i); continue; }
                if (t < 0) continue;
                float e = Ease.IN.at(t), op = t < .15f ? t / .15f : 1 - (t - .15f) / .85f;
                p.setColor(Ease.alpha(Ease.rgba(110, 160, 255, .85f), op));
                float x = dr.x * d, y = dr.y * d + 2.2f * rem * e, w = .025f * rem, h = .14f * rem;
                c.drawRoundRect(x, y, x + w, y + h, w, w, p);
            }
        }
        private void drawP(Canvas c, P q, long now, float rem, float W, float vh) {
            float t = (((now - festAt) / 1000f + q.delay) % q.dur) / q.dur;
            float x = W * q.left / 100, y, rot = 0, rx = 0, op;
            float[] amp = q.anim == SF ? AMP_SF : q.anim == UP ? AMP_UP : AMP_CF;
            float A = amp[q.tier - 1] * rem;
            if (q.anim == UP) {
                x += A * (float) Math.sin(2 * Math.PI * t / .5f);
                y = (104 - 122 * t) * vh;
                rot = 8 * (float) Math.cos(2 * Math.PI * t / .5f);
                op = t < .07f ? t / .07f : t > .86f ? 1 - (t - .86f) / .14f : 1;
            } else {
                float end = q.tier == 1 ? 92.6f : q.tier == 2 ? 94.2f : 96f, start = q.anim == CF ? -8 : -6;
                float k = Math.min(1, t / .84f);
                x += A * (float) Math.sin(2 * Math.PI * Math.min(t, .84f) / .21f);
                y = (start + (end - start) * k) * vh;
                if (q.anim == CF) { rot = 40 * (float) Math.cos(2 * Math.PI * Math.min(t, .84f) / .21f); rx = 720 * k; }
                op = t < .06f ? t / .06f : t > .91f ? 1 - (t - .91f) / .09f : 1;
            }
            op = Ease.clamp(op, 0, 1) * q.op;
            if (op <= 0.003f) return;
            float w = q.size * rem, h = w * q.ratio;
            c.save();
            c.translate(x, y);
            if (rot != 0) c.rotate(rot, w / 2, h / 2);
            if (rx != 0) c.scale(1, (float) Math.cos(Math.toRadians(rx)), w / 2, h / 2);
            switch (q.shape) {
                case SHAPE_FLAKE: {
                    if (flake == null) flake = new RadialGradient(0, 0, 1, new int[] { 0xffffffff, 0xffffffff, 0x8cffffff, 0x00ffffff }, new float[] { 0, .3f, .48f, .7f }, Shader.TileMode.CLAMP);
                    c.save();
                    c.translate(w / 2, h / 2);
                    c.scale(w / 2 * 1.41421356f, w / 2 * 1.41421356f);
                    p.setShader(flake);
                    p.setAlpha(Math.round(op * 255));
                    c.drawCircle(0, 0, .7f, p);
                    p.setShader(null);
                    c.restore();
                    break;
                }
                case SHAPE_HEART:
                    tp.setTextSize(w);
                    tp.setColor(Ease.alpha(q.color, op));
                    tp.setShadowLayer(.06f * rem, 0, 0, Ease.rgba(255, 255, 255, .5f * op));
                    c.drawText("♥", w / 2, w * .85f, tp);
                    tp.clearShadowLayer();
                    break;
                case SHAPE_BALLOON: {
                    p.setColor(Ease.alpha(q.color, op));
                    r.set(0, 0, w, h);
                    c.drawOval(r, p);
                    p.setColor(Ease.rgba(0, 0, 0, .12f * op));
                    c.drawOval(r.left + w * .25f, r.top + h * .3f, r.right, r.bottom, p);
                    p.setColor(Ease.alpha(q.color, op));
                    c.drawOval(r.left, r.top, r.right - w * .12f, r.bottom - h * .12f, p);
                    p.setColor(Ease.rgba(255, 255, 255, .55f * op));
                    c.drawOval(w * .22f, h * .16f, w * .44f, h * .42f, p);
                    p.setColor(Ease.rgba(255, 255, 255, .7f * op));
                    c.drawRect(w / 2 - .5f, h, w / 2 + .5f, h + .45f * rem, p);
                    break;
                }
                default:
                    p.setColor(Ease.alpha(q.color, op));
                    c.drawRect(0, 0, w, h, p);
            }
            c.restore();
        }
        /** 灯笼：绳子、红灯笼（福字、上下金边）、穗子，绕顶端来回摆 ±6° */
        private void lantern(Canvas c, long now, float x, float y, float rem, boolean second) {
            float per = 3200, t = ((now - festAt) + (second ? 1600 : 0)) % (per * 2) / per;
            float k = t < 1 ? t : 2 - t, rot = -6 + 12 * Ease.IN_OUT.at(k);
            float w = .62f * rem, rope = .5f * rem, bh = .52f * rem;
            c.save();
            c.rotate(rot, x + w / 2, y);
            p.setColor(0xff8b5a2b);
            c.drawRect(x + w / 2 - .01f * rem, y, x + w / 2 + .01f * rem, y + rope, p);
            float by = y + rope;
            int li = second ? 1 : 0;
            if (lanBody[li] == null || lanKey[li] != x + w * 1000) {
                lanKey[li] = x + w * 1000;
                lanBody[li] = new RadialGradient(x + w / 2, by + bh * .45f, w * .75f, new int[] { 0xffff6b5b, 0xffd7261e, 0xffa8160f }, new float[] { 0, .6f, 1 }, Shader.TileMode.CLAMP);
                lanTassel[li] = new android.graphics.LinearGradient(0, by + bh + .04f * rem, 0, by + bh + .34f * rem, 0xffe3b23c, 0xffd7261e, Shader.TileMode.CLAMP);
            }
            p.setShader(lanBody[li]);
            p.setShadowLayer(.3f * rem / 2, 0, 0, Ease.rgba(255, 80, 40, .55f));
            r.set(x, by, x + w, by + bh);
            c.drawRoundRect(r, w * .45f, bh * .45f, p);
            p.setShader(null);
            p.clearShadowLayer();
            p.setColor(0xffe3b23c);
            c.drawRoundRect(x + w * .22f, by - .04f * rem, x + w * .78f, by + .03f * rem, .03f * rem, .03f * rem, p);
            c.drawRoundRect(x + w * .22f, by + bh - .03f * rem, x + w * .78f, by + bh + .04f * rem, .03f * rem, .03f * rem, p);
            tp.setColor(0xffffd76a);
            tp.setTextSize(.24f * rem);
            tp.setTypeface(Fonts.sysBold);
            c.drawText("福", x + w / 2, by + bh / 2 - (tp.descent() + tp.ascent()) / 2, tp);
            float ty = by + bh + .04f * rem;
            p.setShader(lanTassel[li]);
            c.drawRoundRect(x + w / 2 - .025f * rem, ty, x + w / 2 + .025f * rem, ty + .3f * rem, .03f * rem, .03f * rem, p);
            p.setShader(null);
            c.restore();
        }
    }

    /** 一组爆裂：每个点从中心沿自己的方向飞出去、缩小、淡出（1.3 秒），点外一圈同色柔光 */
    private static void drawBursts(Canvas c, ArrayList<Burst> bursts, long now, float d, float rem, Paint p) {
        for (int i = bursts.size() - 1; i >= 0; i--) {
            Burst b = bursts.get(i);
            float t0 = now - b.t0;
            if (t0 > 1450) { bursts.remove(i); continue; }
            for (int k = 0; k < b.n; k++) {
                float t = Ease.clamp((t0 - b.delay[k]) / 1300f, 0, 1);
                if (t0 < b.delay[k]) continue;
                float e = BURST.at(t), dist = b.dist * rem * e, s = 1.3f - e;
                float op = t < .7f ? 1 - .1f * (t / .7f) : .9f * (1 - (t - .7f) / .3f);
                double a = Math.toRadians(b.ang[k]);
                float x = b.x * d + (float) Math.cos(a) * dist, y = b.y * d + (float) Math.sin(a) * dist, rr = .035f * rem * s;
                p.setColor(Ease.alpha(b.cols[k], op * .45f));
                c.drawCircle(x, y, rr + .04f * rem * s, p);
                p.setColor(Ease.alpha(b.cols[k], op));
                c.drawCircle(x, y, rr, p);
            }
        }
    }
    private static final Ease BURST = new Ease(.1f, .8f, .3f, 1);

    // ================= 宠物上面一层：爆裂、音符、飘字 =================
    final class TopView extends View implements Frames.Client {
        final ArrayList<Burst> bursts = new ArrayList<>();
        final ArrayList<Note> notes = new ArrayList<>();
        final ArrayList<Plus> pluses = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        TopView(Context c) { super(c); tp.setTextAlign(Paint.Align.CENTER); tp.setTypeface(Fonts.sysBold); }
        void wake() { if (!hidden) frames.add(this, 60); }
        @Override public boolean frame(long now) { invalidate(); return !bursts.isEmpty() || !notes.isEmpty() || !pluses.isEmpty(); }
        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float d = u.dens, rem = u.px(u.rem);
            drawBursts(c, bursts, now, d, rem, p);
            for (int i = notes.size() - 1; i >= 0; i--) {
                Note n = notes.get(i);
                float t = (now - n.t0) / 2200f;
                if (t >= 1) { notes.remove(i); continue; }
                float e = Ease.OUT.at(t);
                float op = t < .15f ? Ease.OUT.at(t / .15f) : 1 - Ease.OUT.at((t - .15f) / .85f);
                float dy = .2f * rem + (-1.2f * rem - .2f * rem) * e;
                tp.setTextSize(.3f * rem);
                tp.setColor(Ease.alpha(0xffff6b9d, op));
                tp.setShadowLayer(.06f * rem / 2, 0, 0, Ease.alpha(0xffffffff, op));
                c.save();
                c.rotate(15 * e, n.x * d, n.y * d + dy);
                c.drawText(n.ch, n.x * d, n.y * d + dy + .3f * rem, tp);
                c.restore();
                tp.clearShadowLayer();
            }
            for (int i = pluses.size() - 1; i >= 0; i--) {
                Plus pl = pluses.get(i);
                float t = (now - pl.t0) / 800f;
                if (t >= 1) { pluses.remove(i); continue; }
                float e = Ease.OUT.at(t), s = .8f + .4f * e;
                tp.setTextSize(.3f * rem * s);
                tp.setColor(Ease.alpha(0xffffffff, 1 - e));
                tp.setShadowLayer(.06f * rem / 2, 0, .02f * rem, Ease.rgba(0, 0, 0, .45f * (1 - e)));
                c.drawText(pl.text, pl.x * d, pl.y * d - .6f * rem * e + .3f * rem, tp);
                tp.clearShadowLayer();
            }
        }
    }

    /** 涟漪（点空地）、蓄力圈（长按）：在气泡上面 */
    final class RippleView extends View implements Frames.Client {
        float[] ripple, ring;
        long rippleAt, ringAt;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RippleView(Context c) { super(c); p.setStyle(Paint.Style.STROKE); }
        void wake() { frames.add(this, 60); }
        @Override public boolean frame(long now) {
            invalidate();
            boolean r = ripple != null && now - rippleAt < 560, g = ring != null && now - ringAt < 460;
            if (ripple != null && !r) ripple = null;
            return r || g;
        }
        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float d = u.dens, rem = u.px(u.rem);
            if (ripple != null) {
                float t = Ease.clamp((now - rippleAt) / 550f, 0, 1), e = Ease.OUT.at(t), s = .3f + 1.3f * e;
                p.setStrokeWidth(.03f * rem * s);
                p.setColor(Ease.alpha(0xffffffff, .9f * (1 - e)));
                p.setShadowLayer(.1f * rem / 2, 0, 0, Ease.rgba(0, 0, 0, .15f * (1 - e)));
                c.drawCircle(ripple[0] * d, ripple[1] * d, .3f * rem * s, p);
                p.clearShadowLayer();
            }
            if (ring != null) {
                float t = Ease.clamp((now - ringAt) / 450f, 0, 1), s = 1.5f - t;
                p.setStrokeWidth(.035f * rem * s);
                p.setColor(Ease.alpha(Ease.rgba(255, 220, 150, .95f), t));
                p.setShadowLayer(.12f * rem / 2, 0, 0, Ease.rgba(255, 180, 80, .5f * t));
                c.drawCircle(ring[0] * d, ring[1] * d, .45f * rem * s, p);
                p.clearShadowLayer();
            }
        }
    }
}
