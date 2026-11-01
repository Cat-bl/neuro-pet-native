package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/**
 * 场景（同网页版 index.html 的 .sky、.ground 和 style.css 的场景部分）：扁平分层风格。
 * 白天 / 傍晚 / 夜晚三张天空渐变按透明度切换；四层远山、草地、云同一套形状，切场景时颜色过渡；太阳、月亮、辉光、彩虹、亮星、流星。
 * 每个会动的东西是一个小 View：位置、透明度、缩放走 RenderNode 的属性（不重画内容，脏区只有它自己那块）；
 * 颜色变化（切场景）时才重画。不动不要帧：白天只有云在飘（每秒 20 帧），夜里加上亮星在闪（每秒 30 帧），开着页面时都停（同网页版 animation-play-state: paused）
 */
final class Sky implements Frames.Client {
    static final int DAY = 0, DUSK = 1, NIGHT = 2;
    // 远山、草地、云的颜色：[r1, r1s, haze1, r2, r2s, haze2, r3, haze3, r4, ground, cloud, cloudShade]
    private static final int[][] PAL = {
        { 0xffb7cde0, 0xffa2bcd4, 0xffdbe8ef, 0xff8eb0c9, 0xff7b9fba, 0xffc6d9e4, 0xff5d8d88, 0xffa3c0c0, 0xff3e6c5b, 0xff5b8550, 0xffffffff, 0xffd3e2ef },
        { 0xffe2807a, 0xffc9676f, 0xffffaa80, 0xffb04b63, 0xff963d58, 0xffde6f6c, 0xff5e2650, 0xffa4466a, 0xff33163a, 0xff2f1634, 0xffffcfa8, 0xffe08489 },
        { 0xff22406d, 0xff1c3660, 0xff2b4b7d, 0xff1a3159, 0xff162a4f, 0xff223f6e, 0xff11213f, 0xff1b3257, 0xff0b162b, 0xff0e1a31, 0xff2c4572, 0xff1f3358 }
    };
    private static final int[][] SKY_C = {
        { 0xff3a7bc4, 0xff5996d4, 0xff8ab8e2, 0xffb9d6ec, 0xffdbe9f0, 0xffeef0e6 },
        { 0xff1e2250, 0xff3d2b66, 0xff77396f, 0xffc14f68, 0xffec7b5c, 0xffffad70, 0xffffd498, 0xffffe6b8 },
        { 0xff040818, 0xff08112b, 0xff0f1f45, 0xff19325d, 0xff233f6e, 0xff233f6e }
    };
    private static final float[][] SKY_S = {
        { 0, .18f, .36f, .50f, .61f, 1 }, { 0, .15f, .31f, .45f, .55f, .63f, .71f, 1 }, { 0, .24f, .44f, .57f, .67f, 1 }
    };
    private static final Ease MOVE = new Ease(.2f, .8f, .2f, 1), RISE = new Ease(.16f, 1, .3f, 1);

    private final Context ctx;
    private final U u;
    private final Frames frames;
    private final SceneData data;
    private final Random rnd = new Random();
    /** 网页版的进场动画从这一刻算 */
    long introAt;

    // ---- 状态 ----
    int scene = -1;
    private boolean portrait, dial, lyric, paused, hidden, showSun = true, showCloud = true, showStars = true;
    private float lunarDay = 3;
    private boolean fullMoon;
    private final Tw[] bgA = { new Tw(0), new Tw(0), new Tw(0) };
    private final Tw milkyA = new Tw(0), fieldA = new Tw(0), starsA = new Tw(0), lightA = new Tw(0), rainbowA = new Tw(0);
    private final Tw sunA = new Tw(1), moonA = new Tw(0), duskK = new Tw(0);
    private final Tw sunR = new Tw(.12f), sunT = new Tw(.09f), sunS = new Tw(.82f), moonR = new Tw(.13f), moonT = new Tw(.09f), moonS = new Tw(.78f), glowOff = new Tw(.41f);
    private final int[] palFrom = new int[12], palTo = new int[12], pal = new int[12];
    private final Tw palK = new Tw(1), cloudK = new Tw(1);
    private final Tw[] cloudA = { new Tw(1), new Tw(.9f), new Tw(.75f) }, rainy = { new Tw(0), new Tw(0), new Tw(0) };

    // ---- 画面上的东西 ----
    final Layer back, front;
    private final BgView bg;
    private final StarDot[] stars = new StarDot[18];
    private final float[][] starPos = new float[18][];
    private final GlowView sunGlow, moonGlow;
    private final RainbowView rainbow;
    private final MoonView moon;
    private final SunView sun;
    private final FlareView flare;
    private final CloudView[] clouds = new CloudView[3];
    private final RidgeView[] ridges = new RidgeView[4];
    private final GroundView ground;
    private final ArrayList<Shoot> shooting = new ArrayList<>();

    Sky(Context c, U u, Frames frames, Layer back, Layer front) {
        ctx = c; this.u = u; this.frames = frames; this.back = back; this.front = front;
        data = SceneData.get(c);
        bg = back.add(new BgView(c));
        for (int i = 0; i < 18; i++) {
            boolean big = i < 4;
            float size = big ? rf(0.05f, 0.065f) : rf(0.025f, 0.045f);
            // left%, top%, 大小 rem, 进场延时, 闪烁延时, 周期
            starPos[i] = new float[] { rf(2, 98), rf(2, 50), size, rf(0.3f, 1.6f), rf(0, 3), rf(2.6f, 5) };
            stars[i] = back.add(new StarDot(c, big));
        }
        sunGlow = back.add(new GlowView(c, true));
        rainbow = back.add(new RainbowView(c));
        moonGlow = back.add(new GlowView(c, false));
        moon = back.add(new MoonView(c));
        sun = back.add(new SunView(c));
        flare = back.add(new FlareView(c));
        flare.setAlpha(0);
        for (int i = 0; i < 3; i++) clouds[i] = back.add(new CloudView(c, i));
        for (int i = 0; i < 4; i++) ridges[i] = front.add(new RidgeView(c, i));
        ground = front.add(new GroundView(c));
        for (int i = 0; i < 12; i++) palFrom[i] = palTo[i] = pal[i] = PAL[DAY][i];
    }
    private float rf(float a, float b) { return a + rnd.nextFloat() * (b - a); }

    // ================= 外面改的状态 =================
    /** 切场景（第一次直接到位） */
    void setScene(int s) {
        if (s == scene) return;
        boolean first = scene < 0;
        long now = SystemClock.uptimeMillis();
        scene = s;
        for (int i = 0; i < 3; i++) to(bgA[i], i == s ? 1 : 0, 1600, Ease.EASE, now, first);
        to(milkyA, s == NIGHT ? 1 : 0, 1600, Ease.EASE, now, first);
        to(fieldA, s == NIGHT ? 1 : s == DUSK ? .3f : 0, 1600, Ease.EASE, now, first);
        to(starsA, s == NIGHT ? 1 : s == DUSK ? .3f : 0, 1600, Ease.EASE, now, first);
        to(lightA, s == NIGHT ? .95f : s == DUSK ? .6f : 0, 2000, Ease.EASE, now, first);
        to(sunA, s == NIGHT ? 0 : 1, 1400, Ease.EASE, now, first);
        to(moonA, s == NIGHT ? 1 : 0, 1400, Ease.EASE, now, first);
        if (s != NIGHT) to(duskK, s == DUSK ? 1 : 0, 1400, Ease.EASE, now, first);
        for (int i = 0; i < 3; i++) to(cloudA[i], s == NIGHT ? (i == 2 ? .35f : .5f) : (i == 0 ? 1 : i == 1 ? .9f : .75f), 1400, Ease.EASE, now, first);
        for (int i = 0; i < 12; i++) { palFrom[i] = pal[i]; palTo[i] = PAL[s][i]; }
        if (first) { System.arraycopy(palTo, 0, pal, 0, 12); System.arraycopy(palTo, 0, palFrom, 0, 12); palK.set(1); cloudK.set(1); }
        else { palK.set(0); palK.to(1, 1600, Ease.EASE, now); cloudK.set(0); cloudK.to(1, 1400, Ease.EASE, now); }
        positions(now, first);
        dirtyAll();
        wake();
    }
    private static void to(Tw t, float v, int ms, Ease e, long now, boolean jump) { if (jump) t.set(v); else t.to(v, ms, e, now); }

    /** 竖向、表盘、竖向放歌词：太阳月亮换位置（同 style.css 的 .portrait / .clock-dial / .has-lyric） */
    void setLayout(boolean portrait, boolean dial, boolean lyric) {
        if (this.portrait == portrait && this.dial == dial && this.lyric == lyric) return;
        this.portrait = portrait; this.dial = dial; this.lyric = lyric;
        positions(SystemClock.uptimeMillis(), scene < 0);
        wake();
    }
    private void positions(long now, boolean jump) {
        boolean dusk = scene == DUSK;
        float sr, st, ss, mr, mt, ms;
        if (!portrait) { sr = dusk ? .24f : .12f; st = dusk ? .5f : .09f; ss = dusk ? 1.25f : .82f; mr = .13f; mt = .09f; ms = .78f; }
        else {
            if (dial && dusk) { sr = .22f; st = .55f; ss = 1.1f; }
            else if (dial || lyric) { sr = .04f; st = .025f; ss = .6f; }
            else if (dusk) { sr = .22f; st = .49f; ss = 1.25f; }
            else { sr = .10f; st = .47f; ss = .82f; }
            if (dial || lyric) { mr = .04f; mt = .025f; ms = .6f; } else { mr = .12f; mt = .47f; ms = .78f; }
        }
        to(sunR, sr, 1200, MOVE, now, jump); to(sunT, st, 1200, MOVE, now, jump); to(sunS, ss, 1200, MOVE, now, jump);
        to(moonR, mr, 1200, MOVE, now, jump); to(moonT, mt, 1200, MOVE, now, jump); to(moonS, ms, 1200, MOVE, now, jump);
        to(glowOff, dusk ? .625f : .41f, 1200, MOVE, now, jump);
    }
    /** 显示内容里的太阳月亮 / 云 / 星空 */
    void setShown(boolean sun, boolean cloud, boolean stars) {
        showSun = sun; showCloud = cloud; showStars = stars;
        int vs = sun ? View.VISIBLE : View.GONE, vc = cloud ? View.VISIBLE : View.GONE;
        this.sun.setVisibility(vs); this.moon.setVisibility(vs); sunGlow.setVisibility(vs); moonGlow.setVisibility(vs); flare.setVisibility(vs);
        for (CloudView v : clouds) v.setVisibility(vc);
        for (StarDot d : this.stars) d.setVisibility(stars ? View.VISIBLE : View.GONE);
        if (!stars) for (Shoot s : shooting) back.removeView(s.v);
        if (!stars) shooting.clear();
        bg.invalidate();
        wake();
    }
    /** 开着页面：云、星星停（被面板挡住，同网页版暂停动画） */
    void setPaused(boolean p) { if (paused == p) return; paused = p; wake(); }
    /** 卡片藏起来：什么都不动 */
    void setHidden(boolean h) { if (hidden == h) return; hidden = h; wake(); }
    /** 月相：按农历日画（最细画成初三左右的月牙），中秋满月 */
    void setMoon(int day, boolean full) {
        float age = full ? 14.77f : Ease.clamp((day <= 0 ? 3 : day) - 1, 2.2f, 27.3f);
        if (Math.abs(age - lunarDay) < 0.05f && full == fullMoon) return;
        lunarDay = age; fullMoon = full;
        moon.phase(age);
    }
    void rainbow(boolean on) { rainbowA.to(on ? 1 : 0, 3000, Ease.EASE, SystemClock.uptimeMillis()); wake(); }
    boolean rainbowOn() { return rainbowA.target() > 0; }

    // ================= 点场景 =================
    boolean hitSun(float x, float y, float pad) { return scene != NIGHT && showSun && hit(sun, sunBox(), x, y, pad); }
    boolean hitMoon(float x, float y, float pad) { return scene == NIGHT && showSun && hit(moon, moonBox(), x, y, pad); }
    /** 点到了哪朵云（-1 没有） */
    int hitCloud(float x, float y, float pad) {
        if (!showCloud) return -1;
        for (int i = 0; i < 3; i++) {
            CloudView v = clouds[i];
            float l = v.getLeft() + v.getTranslationX(), t = v.getTop() + v.getTranslationY();
            if (x >= l - pad && x <= l + v.getWidth() + pad && y >= t - pad && y <= t + v.getHeight() + pad) return i;
        }
        return -1;
    }
    private static boolean hit(View v, RectF box, float x, float y, float pad) {
        return v.getVisibility() == View.VISIBLE && x >= box.left - pad && x <= box.right + pad && y >= box.top - pad && y <= box.bottom + pad;
    }
    private final RectF tmpBox = new RectF();
    /** 太阳在画面上的框（像素） */
    RectF sunBox() { long n = SystemClock.uptimeMillis(); return box(sunR.get(n), sunT.get(n), sunS.get(n)); }
    RectF moonBox() { long n = SystemClock.uptimeMillis(); return box(moonR.get(n), moonT.get(n), moonS.get(n)); }
    private RectF box(float right, float top, float size) {
        float W = u.px(u.W), H = u.px(u.H), s = u.px(size * u.rem), r = W * (1 - right);
        tmpBox.set(r - s, H * top, r, H * top + s);
        return tmpBox;
    }
    /** 太阳闪一下（点太阳、歌词里唱到太阳） */
    boolean flare() {
        if (scene == NIGHT || !showSun) return false;
        flareAt = SystemClock.uptimeMillis();
        wake();
        return true;
    }
    private long flareAt = -10000;
    /** 云下一阵雨：云变灰（返回云在画面上的框，给雨滴用） */
    RectF rainCloud(int i) {
        long n = SystemClock.uptimeMillis();
        rainy[i].to(1, 300, Ease.EASE, n);
        rainUntil[i] = n + 1800;
        wake();
        CloudView v = clouds[i];
        float l = v.getLeft() + v.getTranslationX(), t = v.getTop() + v.getTranslationY();
        return new RectF(l, t, l + v.getWidth(), t + v.getHeight());
    }
    private final long[] rainUntil = new long[3];
    int cloudCount() { return showCloud ? 3 : 0; }

    /** 一颗流星（x%、y% 是起点；不给就随机） */
    void shootingStar(float xPct, float yPct) {
        if (!showStars) return;
        Shoot s = new Shoot();
        s.x = Float.isNaN(xPct) ? rf(45, 95) : xPct;
        s.y = Float.isNaN(yPct) ? rf(4, 30) : yPct;
        s.t0 = SystemClock.uptimeMillis();
        s.v = new ShootView(ctx);
        back.addView(s.v, back.indexOfChild(ridges.length > 0 ? clouds[2] : bg) + 1, new Layer.Lp());
        shooting.add(s);
        layoutShoot(s);
        wake();
    }
    private static final class Shoot { float x, y; long t0; ShootView v; }

    // ================= 布局 =================
    /** 卡片大小变了：各个东西按网页版的百分比、rem 重新摆 */
    void layout() {
        float W = u.px(u.W), H = u.px(u.H), rem = u.px(u.rem);
        Layer.place(bg, 0, 0, W, H);
        bg.sized();
        for (int i = 0; i < 18; i++) {
            float[] p = starPos[i];
            float s = p[2] * rem, box = stars[i].big ? s * 6 : s * 3;
            Layer.place(stars[i], W * p[0] / 100 + s / 2 - box / 2, H * p[1] / 100 + s / 2 - box / 2, box, box);
            stars[i].size = s;
        }
        float g = 6 * rem;
        Layer.place(sunGlow, 0, 0, g, g);
        Layer.place(moonGlow, 0, 0, 3.2f * rem, 3.2f * rem);
        float rw = W * 1.4f, rh = W * 0.7f;
        Layer.place(rainbow, W / 2 - rw / 2, H * 0.97f - rh, rw, rh);
        float sb = 8 * rem;
        Layer.place(sun, 0, 0, sb, sb);
        Layer.place(flare, 0, 0, 1.25f * 2.2f * rem, 1.25f * 2.2f * rem);
        Layer.place(moon, 0, 0, .78f * rem * 1.5f, .78f * rem * 1.5f);
        boolean pt = portrait;
        float[][] cl = pt ? new float[][] { { .03f, .34f }, { .60f, .42f }, { .14f, .49f } } : new float[][] { { .06f, .13f }, { .74f, .32f }, { .26f, .41f } };
        float[][] cs = { { 2.1f, .9f }, { 1.5f, .77f }, { 1f, .51f } };
        for (int i = 0; i < 3; i++) Layer.place(clouds[i], W * cl[i][0], H * cl[i][1], cs[i][0] * rem, cs[i][1] * rem);
        // 远山、草地整层一张硬件层（只包住下面这一截）：几百个多边形只在切场景、进场时重画，平时直接贴
        float rTop = H * (1 - .069f - .46f), rH = H * .46f;
        Layer.place(front, 0, rTop, W, H - rTop);
        front.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        for (int i = 0; i < 4; i++) Layer.place(ridges[i], 0, 0, W, rH);
        float gh = H * .07f, grass = .13f * rem;
        Layer.place(ground, 0, H - gh - grass - rTop, W, gh + grass);
        ground.grassH = grass;
        for (Shoot s : shooting) layoutShoot(s);
        dirtyAll();
        wake();
    }
    private void layoutShoot(Shoot s) {
        float W = u.px(u.W), H = u.px(u.H), rem = u.px(u.rem);
        float w = 1.4f * rem, h = Math.max(1, .025f * rem);
        Layer.place(s.v, W * s.x / 100, H * s.y / 100, w, h);
        s.v.setPivotX(w); s.v.setPivotY(h / 2);
    }
    private void dirtyAll() {
        bg.invalidate(); sun.invalidate(); moon.invalidate(); sunGlow.invalidate(); moonGlow.invalidate(); rainbow.invalidate();
        for (CloudView v : clouds) v.invalidate();
        for (RidgeView v : ridges) v.invalidate();
        ground.invalidate();
    }

    // ================= 每帧 =================
    void wake() {
        if (hidden) return;
        frames.add(this, 60);
    }

    @Override public boolean frame(long now) {
        if (hidden) return false;
        boolean busy = false; // 有过渡、进场、流星、闪光在走：60 帧
        float W = u.px(u.W), H = u.px(u.H), rem = u.px(u.rem);
        long it = now - introAt;
        boolean intro = it < 2400;
        busy |= intro;
        // 天空渐变、银河、星空（颜色、透明度在变时重画）
        boolean bgMoving = bgA[0].moving(now) | bgA[1].moving(now) | bgA[2].moving(now) | milkyA.moving(now) | fieldA.moving(now) | (intro && it < 2100);
        if (bgMoving || bgDirty) { bg.invalidate(); bgDirty = bgMoving; busy = true; } // 停下来再画一次：落在终点
        // 调色板
        boolean pm = palK.moving(now), cm = cloudK.moving(now);
        float pk = palK.get(now), ck = cloudK.get(now);
        for (int i = 0; i < 10; i++) pal[i] = Ease.mix(palFrom[i], palTo[i], pk);
        for (int i = 10; i < 12; i++) pal[i] = Ease.mix(palFrom[i], palTo[i], ck);
        boolean lm = lightA.moving(now);
        if (pm || lm || ridgesDirty) { for (RidgeView r : ridges) r.invalidate(); ground.invalidate(); ridgesDirty = pm || lm; busy = true; }
        // 太阳、月亮、辉光
        float sR = sunR.get(now), sT = sunT.get(now), sS = sunS.get(now), dk = duskK.get(now), go = glowOff.get(now);
        float mR = moonR.get(now), mT = moonT.get(now), mS = moonS.get(now);
        boolean sunMoving = sunR.moving(now) | sunT.moving(now) | sunS.moving(now) | duskK.moving(now) | glowOff.moving(now) | sunA.moving(now)
            | moonR.moving(now) | moonT.moving(now) | moonS.moving(now) | moonA.moving(now);
        busy |= sunMoving;
        float rise = intro ? ip(it, 100, 1500, RISE) : 1, appear = intro ? ip(it, 200, 1800, Ease.EASE) : 1;
        float sunCx = W * (1 - sR) - sS * rem / 2, sunCy = H * sT + sS * rem / 2 + (1 - rise) * .9f * rem;
        sun.setTranslationX(sunCx - sun.getWidth() / 2f); sun.setTranslationY(sunCy - sun.getHeight() / 2f);
        if (sun.set(sS * rem / 2, dk, rem)) sun.invalidate();
        sun.setAlpha(sunA.get(now) * rise);
        float gcx = W * (1 - sR) - go * rem, gcy = H * sT + go * rem;
        sunGlow.setTranslationX(gcx - sunGlow.getWidth() / 2f); sunGlow.setTranslationY(gcy - sunGlow.getHeight() / 2f);
        if (sunGlow.set(dk)) sunGlow.invalidate();
        sunGlow.setAlpha(sunA.get(now) * appear);
        float mcx = W * (1 - mR) - mS * rem / 2, mcy = H * mT + mS * rem / 2 + (1 - rise) * .9f * rem;
        float mScale = mS / .78f;
        moon.setTranslationX(mcx - moon.getWidth() / 2f); moon.setTranslationY(mcy - moon.getHeight() / 2f);
        moon.setScaleX(mScale); moon.setScaleY(mScale);
        moon.setAlpha(moonA.get(now) * rise);
        float mgx = W * (1 - mR) - .39f * rem, mgy = H * mT + .39f * rem;
        moonGlow.setTranslationX(mgx - moonGlow.getWidth() / 2f); moonGlow.setTranslationY(mgy - moonGlow.getHeight() / 2f);
        moonGlow.setAlpha(moonA.get(now) * appear);
        // 太阳闪光：1.2 秒放大淡出
        long ft = now - flareAt;
        if (ft < 1300) {
            float k = Ease.OUT.at(Math.min(1, ft / 1200f));
            float fs = 2.2f * sS * rem;
            flare.setTranslationX(sunCx - flare.getWidth() / 2f); flare.setTranslationY(sunCy - flare.getHeight() / 2f);
            float sc = (.4f + 2f * k) * fs / flare.getWidth();
            flare.setScaleX(sc); flare.setScaleY(sc);
            flare.setAlpha((1 - k) * sunA.get(now));
            busy = true;
        } else if (flare.getAlpha() != 0) flare.setAlpha(0);
        // 彩虹
        boolean rbm = rainbowA.moving(now);
        rainbow.setAlpha(rainbowA.get(now));
        busy |= rbm;
        // 云：颜色、透明度、下雨、进场，再加上一直在飘
        boolean ambient = false;
        float[] drift = { 80, 110, 65 };
        for (int i = 0; i < 3; i++) {
            CloudView v = clouds[i];
            if (rainUntil[i] > 0 && now > rainUntil[i]) { rainUntil[i] = 0; rainy[i].to(0, 1400, Ease.EASE, now); }
            boolean rm = rainy[i].moving(now) || rainUntil[i] > 0;
            if (cm || rm) { v.invalidate(); busy = true; }
            float ci = intro ? ip(it, 450 + i * 150, 1300, RISE) : 1;
            busy |= cloudA[i].moving(now);
            v.setAlpha(cloudA[i].get(now) * ci);
            float dx = 0;
            if (showCloud && !paused) {
                // drift：0 → 1.6rem → 0 一圈，每半圈 ease-in-out；第二朵反着走
                float per = drift[i] * 1000, ph = (now % (long) per) / per;
                if (i == 1) ph = 1 - ph;
                float half = ph < .5f ? ph * 2 : (1 - ph) * 2;
                dx = Ease.IN_OUT.at(half) * 1.6f * rem;
                ambient = true;
            } else dx = v.drift;
            v.drift = dx;
            v.setTranslationX(dx - (1 - ci) * .6f * rem);
        }
        // 亮星：外层（整层）透明度 × 进场 × 闪烁
        float sa = starsA.get(now);
        busy |= starsA.moving(now);
        boolean twinkle = showStars && sa > 0.001f && !paused;
        for (int i = 0; i < 18; i++) {
            float[] p = starPos[i];
            float in = intro ? ip(it, p[3] * 1000, 800, Ease.OUT) : 1;
            float tw = stars[i].tw;
            if (twinkle) {
                float per = p[5] * 1000, ph = ((now - introAt - p[4] * 1000) % per + per) % per / per;
                float half = ph < .5f ? ph * 2 : (1 - ph) * 2;
                tw = .4f + .6f * Ease.IN_OUT.at(half);
                stars[i].tw = tw;
            }
            stars[i].setAlpha(sa * in * tw);
        }
        ambient |= twinkle;
        // 远山、草地进场：从下面浮起
        if (intro) {
            float vh = H / 100;
            for (int i = 0; i < 4; i++) {
                float k = ip(it, 200 + i * 100, 1300 - i * 100, RISE);
                ridges[i].setTranslationY((1 - k) * 13.8f * vh);
                ridges[i].setAlpha(k);
            }
            float gk = ip(it, 450, 900, RISE);
            ground.setTranslationY((1 - gk) * 7 * vh);
            ground.setAlpha(gk);
        } else if (ridges[0].getAlpha() != 1 || ground.getAlpha() != 1) {
            for (RidgeView r : ridges) { r.setTranslationY(0); r.setAlpha(1); }
            ground.setTranslationY(0); ground.setAlpha(1);
        }
        // 流星
        for (int i = shooting.size() - 1; i >= 0; i--) {
            Shoot s = shooting.get(i);
            float t = (now - s.t0) / 1100f;
            if (t >= 1.18f) { back.removeView(s.v); shooting.remove(i); continue; }
            float k = Ease.OUT.at(Math.min(1, t));
            float op = t < .12f ? Ease.OUT.at(t / .12f) : 1 - Ease.OUT.at(Math.min(1, (t - .12f) / .88f));
            s.v.setRotation(-28);
            s.v.setScaleX(.2f + .8f * k);
            s.v.setTranslationX(-3.2f * rem * k);
            s.v.setTranslationY(1.7f * rem * k);
            s.v.setAlpha(Math.max(0, op));
            busy = true;
        }
        if (busy) return true;
        if (ambient) { frames.add(this, twinkle ? 30 : 20); return true; }
        return false;
    }
    private boolean bgDirty;
    private boolean ridgesDirty;
    /** 进场动画进度（延时、时长毫秒，曲线；backwards：延时期间停在起点） */
    private static float ip(long t, float delay, float dur, Ease e) { return e.at(Ease.clamp((t - delay) / dur, 0, 1)); }
    float appear(long now) { long it = now - introAt; return it < 2100 ? ip(it, 200, 1800, Ease.EASE) : 1; }

    // ================= 各个 View =================
    /** 天空：三张渐变按透明度叠，银河、静态星空 */
    final class BgView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Shader[] sh = new Shader[3];
        private Shader milky;
        private final Matrix m = new Matrix();
        BgView(Context c) { super(c); dot.setStyle(Paint.Style.STROKE); dot.setStrokeCap(Paint.Cap.ROUND); dot.setColor(0xffffffff); }
        void sized() {
            float H = getHeight();
            for (int i = 0; i < 3; i++) sh[i] = Grad.vertical(0, H, SKY_C[i], SKY_S[i]);
            float W = getWidth(), w = W * 1.4f, rx = w / 2 * 1.41421356f;
            milky = Grad.radial(0, 0, rx, new int[] { Ease.rgba(175, 195, 255, .17f), Ease.rgba(175, 195, 255, .06f), Ease.rgba(175, 195, 255, 0) }, new float[] { 0, .4f, .7f });
        }
        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float W = getWidth(), H = getHeight();
            if (sh[0] == null) sized();
            // 先画最底下的不透明那张（夜里的颜色铺满），再按透明度叠：同网页版 .card 的底色 #0b1430
            c.drawColor(0xff0b1430);
            for (int i = 0; i < 3; i++) {
                float a = bgA[i].get(now);
                if (a <= 0.003f) continue;
                p.setShader(sh[i]);
                p.setAlpha(Math.round(a * 255));
                c.drawRect(0, 0, W, H, p);
            }
            p.setShader(null);
            float ap = appear(now);
            float ma = milkyA.get(now) * ap;
            if (ma > 0.003f) { // 银河：-20% / 18% 起、140% × 34% 的框，绕中心转 -18.6°，椭圆径向渐变
                float bw = W * 1.4f, bh = H * .34f, cx = -W * .2f + bw / 2, cy = H * .18f + bh / 2;
                c.save();
                c.translate(cx, cy);
                c.rotate(-18.6f);
                c.clipRect(-bw / 2, -bh / 2, bw / 2, bh / 2);
                c.scale(1, bh / bw);
                p.setShader(milky);
                p.setAlpha(Math.round(ma * 255));
                c.drawRect(-bw / 2, -bw / 2, bw / 2, bw / 2, p);
                p.setShader(null);
                c.restore();
            }
            float fa = fieldA.get(now) * ap;
            if (fa > 0.003f && showStars) { // 静态星空：xMidYMin slice
                float s = Math.max(W / data.sfW, H / data.sfH), ox = (W - data.sfW * s) / 2;
                c.save();
                c.translate(ox, 0);
                c.scale(s, s);
                for (int t = 0; t < 6; t++) {
                    if (data.starPts[t].length == 0) continue;
                    dot.setStrokeWidth(data.starR[t] * 2);
                    dot.setAlpha(Math.round(255 * data.starA[t] * fa));
                    c.drawPoints(data.starPts[t], dot);
                }
                c.restore();
            }
        }
    }

    /** 会闪的亮星：一个圆点加一圈柔光，大的带十字星芒（同 .star / .star.big） */
    static final class StarDot extends View {
        final boolean big;
        float size = 2, tw = 1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader glow, lineH;
        private float glowR, sizedFor;
        StarDot(Context c, boolean big) { super(c); this.big = big; }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = size / 2;
            if (sizedFor != size || glow == null) {
                sizedFor = size;
                float[] out = new float[1];
                glow = Grad.glow(cx, cy, r, size * 0.05f / 0.035f, 0, 0xe6ffffff, out); // box-shadow 0 0 .05rem：按星星大小折算
                glowR = out[0];
                if (big) lineH = new android.graphics.LinearGradient(cx - size * 3, 0, cx + size * 3, 0, new int[] { 0x00ffffff, 0xb3ffffff, 0x00ffffff }, null, Shader.TileMode.CLAMP);
            }
            p.setShader(glow);
            c.drawCircle(cx, cy, glowR, p);
            p.setShader(null);
            p.setColor(0xffffffff);
            c.drawCircle(cx, cy, r, p);
            if (big) {
                p.setShader(lineH);
                float hw = Math.max(1, getResources().getDisplayMetrics().density) / 2;
                c.drawRect(cx - size * 3, cy - hw, cx + size * 3, cy + hw, p);
                c.save();
                c.rotate(90, cx, cy);
                c.drawRect(cx - size * 3, cy - hw, cx + size * 3, cy + hw, p);
                c.restore();
                p.setShader(null);
            }
        }
    }

    /** 太阳：柔光圆盘（径向渐变）+ 两圈柔光（box-shadow）；傍晚变大变橙。r 是圆盘半径（像素），dk 是傍晚的程度 */
    final class SunView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float r = -1, dk = -1, rem;
        private Shader disc, g1, g2;
        private final float[] o1 = new float[1], o2 = new float[1];
        SunView(Context c) { super(c); }
        boolean set(float r, float dk, float rem) {
            if (Math.abs(r - this.r) < 0.05f && Math.abs(dk - this.dk) < 0.002f) return false;
            this.r = r; this.dk = dk; this.rem = rem;
            disc = null;
            return true;
        }
        @Override protected void onDraw(Canvas c) {
            if (r <= 0) return;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (disc == null) {
                float k = dk;
                int a = Ease.mix(0xfffffef6, 0xfffff6dc, k), b = Ease.mix(0xfffff8dc, 0xffffe2a6, k), d = Ease.mix(0xffffeeb4, 0xffffc27a, k);
                disc = Grad.radial(cx, cy, r * 1.41421356f, new int[] { a, b, d }, new float[] { 0, Ease.lerp(.55f, .5f, k), 1 });
                g1 = Grad.glow(cx, cy, r, Ease.lerp(.22f, .35f, k) * rem, Ease.lerp(.06f, .1f, k) * rem, Ease.mix(Ease.rgba(255, 250, 225, .95f), Ease.rgba(255, 214, 150, .9f), k), o1);
                g2 = Grad.glow(cx, cy, r, Ease.lerp(.9f, 1.6f, k) * rem, Ease.lerp(.3f, .6f, k) * rem, Ease.mix(Ease.rgba(255, 238, 180, .45f), Ease.rgba(255, 150, 90, .45f), k), o2);
            }
            p.setShader(g2); c.drawCircle(cx, cy, o2[0], p);
            p.setShader(g1); c.drawCircle(cx, cy, o1[0], p);
            p.setShader(disc); c.drawCircle(cx, cy, r, p);
            p.setShader(null);
        }
    }

    /** 点太阳的闪光（.sun .flare） */
    static final class FlareView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader s;
        private int w;
        FlareView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float r = getWidth() / 2f;
            if (s == null || w != getWidth()) { w = getWidth(); s = Grad.radial(r, r, r * 1.41421356f, new int[] { Ease.rgba(255, 245, 190, .9f), Ease.rgba(255, 245, 190, 0) }, new float[] { 0, .62f }); }
            p.setShader(s);
            c.drawCircle(r, r, r, p);
        }
    }

    /** 太阳 / 月亮背后大范围的天空辉光 */
    final class GlowView extends View {
        private final boolean isSun;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader s;
        private float dk = -1;
        private int w;
        GlowView(Context c, boolean sun) { super(c); isSun = sun; }
        boolean set(float dk) { if (Math.abs(dk - this.dk) < 0.002f) return false; this.dk = dk; s = null; return true; }
        @Override protected void onDraw(Canvas c) {
            float r = getWidth() / 2f;
            if (s == null || w != getWidth()) {
                w = getWidth();
                float R = r * 1.41421356f, k = Math.max(0, dk);
                if (isSun) s = Grad.radial(r, r, R,
                    new int[] { Ease.mix(Ease.rgba(255, 250, 225, .5f), Ease.rgba(255, 196, 130, .6f), k), Ease.mix(Ease.rgba(255, 250, 225, .16f), Ease.rgba(255, 150, 100, .25f), k), Ease.mix(Ease.rgba(255, 250, 225, 0), Ease.rgba(255, 140, 100, 0), k) },
                    new float[] { 0, Ease.lerp(.3f, .32f, k), Ease.lerp(.68f, .7f, k) });
                else s = Grad.radial(r, r, R, new int[] { Ease.rgba(225, 232, 255, .28f), Ease.rgba(200, 215, 255, .1f), Ease.rgba(200, 215, 255, 0) }, new float[] { 0, .32f, .66f });
            }
            p.setShader(s);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
        }
    }

    /** 月亮：按月相画亮面，暗面半透明，亮面里几个陨石坑（同 drawMoon 的 SVG） */
    static final class MoonView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Path lit = new Path();
        private final Path craters = new Path();
        MoonView(Context c) {
            super(c);
            float[][] cr = { { -15, -12, 9 }, { 13, 10, 7 }, { -5, 22, 5 }, { 20, -18, 4 }, { -26, 12, 4 } };
            for (float[] q : cr) craters.addCircle(q[0], q[1], q[2], Path.Direction.CW);
            phase(2.2f);
        }
        void phase(float age) {
            double th = age / 29.53 * Math.PI * 2, cs = Math.cos(th);
            float rx = (float) Math.abs(cs) * 50;
            boolean waxing = age < 14.77f;
            int limb = waxing ? 1 : 0, term = waxing ? (cs > 0 ? 0 : 1) : (cs > 0 ? 1 : 0);
            lit = SvgPath.parse("M0,-50A50,50 0 0 " + limb + " 0,50A" + rx + ",50 0 0 " + term + " 0,-50Z");
            invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            float box = getWidth() / 1.5f, s = box / 100f;
            c.save();
            c.translate(getWidth() / 2f, getHeight() / 2f);
            c.scale(s, s);
            p.setColor(0xff16244a); p.setAlpha(Math.round(.45f * 255));
            c.drawCircle(0, 0, 49, p);
            p.setColor(0xfff6f0da);
            c.drawPath(lit, p);
            c.clipPath(lit);
            p.setColor(0xffd9d0b4); p.setAlpha(Math.round(.55f * 255));
            c.drawPath(craters, p);
            c.restore();
        }
    }

    /** 彩虹（随机事件）：远山背后的半圆，柔和低饱和 */
    static final class RainbowView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader s;
        private int w;
        private final Path clip = new Path();
        RainbowView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            if (s == null || w != getWidth()) {
                w = getWidth();
                float R = (float) Math.hypot(W / 2, H);
                s = Grad.radial(W / 2, H, R, new int[] { 0x00ffffff, Ease.rgba(148, 80, 211, .3f), Ease.rgba(70, 90, 230, .3f), Ease.rgba(40, 180, 90, .3f), Ease.rgba(255, 225, 60, .3f), Ease.rgba(255, 150, 40, .3f), Ease.rgba(255, 70, 70, .3f), 0x00ffffff },
                    new float[] { .6f, .61f, .64f, .67f, .70f, .73f, .76f, .78f });
                clip.reset();
                clip.addRoundRect(new RectF(0, 0, W, H * 2), new float[] { H, H, H, H, 0, 0, 0, 0 }, Path.Direction.CW);
            }
            p.setShader(s);
            c.save();
            c.clipPath(clip);
            c.drawRect(0, 0, W, H, p);
            c.restore();
        }
    }

    /** 一朵云：平底积云，底边一条背光色；颜色跟场景、下雨时变灰 */
    final class CloudView extends View {
        private final int i;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float drift;
        CloudView(Context c, int i) { super(c); this.i = i; }
        @Override protected void onDraw(Canvas c) {
            SceneData.Cloud C = data.clouds[i];
            float s = Math.min(getWidth() / C.vbW, getHeight() / C.vbH);
            float k = rainy[i].get(SystemClock.uptimeMillis());
            c.save();
            c.translate((getWidth() - C.vbW * s) / 2, (getHeight() - C.vbH * s) / 2);
            c.scale(s, s);
            p.setColor(Ease.mix(pal[11], 0xff8592a6, k));
            c.drawPath(C.shade, p);
            p.setColor(Ease.mix(pal[10], 0xffa9b4c6, k));
            c.drawPath(C.body, p);
            c.restore();
        }
    }

    /** 一层远山（xMidYMax slice：按高度缩放、居中裁宽）：受光面、背光面、小屋灯、山脚雾 */
    final class RidgeView extends View {
        private final int i;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader mist;
        private int mistColor, mistW, mistH;
        RidgeView(Context c, int i) { super(c); this.i = i; }
        @Override protected void onDraw(Canvas c) {
            SceneData.Ridge R = data.ridges[i];
            float W = getWidth(), H = getHeight(), s = Math.max(W / R.vbW, H / R.vbH);
            int rf = pal[i == 0 ? 0 : i == 1 ? 3 : i == 2 ? 6 : 8], rs = pal[i == 0 ? 1 : i == 1 ? 4 : 6], haze = i < 3 ? pal[i == 0 ? 2 : i == 1 ? 5 : 7] : 0;
            float la = lightA.get(SystemClock.uptimeMillis());
            c.save();
            c.translate((W - R.vbW * s) / 2, H - R.vbH * s);
            c.scale(s, s);
            for (SceneData.Shape sh : R.items) {
                switch (sh.cls) {
                    case "rf": p.setColor(rf); c.drawPath(sh.path, p); break;
                    case "rs": p.setColor(rs); c.drawPath(sh.path, p); break;
                    case "lt":
                        if (la > 0.003f) { p.setColor(0xffffc56a); p.setAlpha(Math.round(la * 255)); c.drawRoundRect(sh.rect, sh.rx, sh.rx, p); }
                        break;
                    case "mist": {
                        if (mist == null || mistColor != haze || mistW != getWidth() || mistH != getHeight()) {
                            mistColor = haze; mistW = getWidth(); mistH = getHeight();
                            mist = new android.graphics.LinearGradient(0, sh.rect.top, 0, sh.rect.bottom, Ease.alpha(haze, 0), haze, Shader.TileMode.CLAMP);
                        }
                        p.setShader(mist);
                        p.setAlpha(Math.round(255 * (i == 0 ? .85f : i == 1 ? .8f : .7f)));
                        c.drawRect(sh.rect, p);
                        p.setShader(null);
                        p.setAlpha(255);
                        break;
                    }
                }
            }
            c.restore();
        }
    }

    /** 地面带：底色 + 上浅下深的一层，上沿一排草叶 */
    final class GroundView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader shade;
        private int sh;
        float grassH;
        GroundView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), g = grassH, top = g;
            int col = pal[9];
            p.setColor(col);
            c.drawRect(0, top, W, H, p);
            if (shade == null || sh != getHeight()) { sh = getHeight(); shade = new android.graphics.LinearGradient(0, top, 0, H, 0x0dffffff, 0x40000000, Shader.TileMode.CLAMP); }
            p.setShader(shade);
            c.drawRect(0, top, W, H, p);
            p.setShader(null);
            // 草叶：viewBox 拉伸铺满宽度，底边压进地面 1 像素
            c.save();
            c.translate(0, 1);
            c.scale(W / data.grassW, g / data.grassH);
            p.setColor(col);
            c.drawPath(data.grass, p);
            c.restore();
        }
    }

    /** 流星：一道从透明到白的细线，绕右端转 -28°，往左下划出去 */
    static final class ShootView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader s;
        private int w;
        ShootView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            if (s == null || w != getWidth()) { w = getWidth(); s = new android.graphics.LinearGradient(0, 0, W, 0, 0x00ffffff, 0xffffffff, Shader.TileMode.CLAMP); }
            p.setShader(s);
            c.drawRoundRect(0, 0, W, H, H, H, p);
        }
    }
}
