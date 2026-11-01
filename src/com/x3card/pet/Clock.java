package com.x3card.pet;

import android.content.Context;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.Calendar;

/**
 * 时钟（同网页版 .clock：时间 / 表盘、日期、农历一行、胶囊一行、歌词，封面铺在时间后面）。
 * 数字时间：Oswald 大号窄体，进场逐个上浮淡入，分钟变化时旧数字往上翻走、新数字从下面翻上来（几位一起变时依次错开），冒号慢慢呼吸。
 * 表盘：外圈像画笔一样画出来、刻度顺时针依次亮起、指针从 12 点扫到当前时间，小号数字时间在表盘下半部。
 * 时间这块变高变矮（切表盘）时下面几行跟着平滑移动（同网页版 height 过渡）。
 * 各部分是单独的小 View：冒号呼吸、指针走动只改那一小块的透明度 / 旋转
 */
final class Clock implements Frames.Client {
    interface Host {
        /** 点了时间（切数字 / 表盘）等交给外面：这里只管画 */
        void clockLaid(float bottomPx);
    }

    private static final Ease MOVE = new Ease(.2f, .8f, .2f, 1), RISE = new Ease(.16f, 1, .3f, 1);
    private static final Ease FLIP_OUT = new Ease(.55f, 0, .75f, .3f), FLIP_IN = new Ease(.2f, .9f, .3f, 1.12f), DIAL_OUT = new Ease(.5f, 0, .75f, 0), RING_IN = new Ease(.65f, 0, .25f, 1);
    private static final Ease SWEEP = new Ease(.16f, 1, .3f, 1), SEC = new Ease(.3f, 1.6f, .5f, 1), HAND = new Ease(.4f, 0, .2f, 1);
    private final Context ctx;
    private final U u;
    private final Frames frames;
    final Layer group;          // 整个时钟（开聊天室时整体挪到左半边、缩小；开页面时淡出）
    final Layer dialBox;        // 表盘（整体缩放淡入淡出）
    final TimeView time;
    final ColonView colon;
    final DialFace face;
    final HandView handH, handM, handS;
    final HubView hub;
    final LineView date;
    final LunarView lunar;
    Pills pills;
    Lyric lyric;
    Cover cover;

    // ---- 状态 ----
    int scene = Sky.DAY;
    boolean dialMode, portrait, hasCover, timeShown = true, dateShown = true, lunarShown = true, paused, hidden;
    final Tw top = new Tw(.9f), timeH = new Tw(2.6f), padTop = new Tw(0);
    final Tw groupA = new Tw(1), roomK = new Tw(0), timeFade = new Tw(1);
    long introAt;

    Clock(Context c, U u, Frames frames, Layer host) {
        ctx = c; this.u = u; this.frames = frames;
        group = host.add(new Layer(c));
        cover = group.add(new Cover(c, u, frames));
        dialBox = group.add(new Layer(c));
        face = dialBox.add(new DialFace(c));
        handH = dialBox.add(new HandView(c, 0xffffffff));
        handM = dialBox.add(new HandView(c, 0xffffffff));
        handS = dialBox.add(new HandView(c, 0xffff7a4d));
        hub = dialBox.add(new HubView(c));
        time = group.add(new TimeView(c));
        colon = group.add(new ColonView(c));
        date = group.add(new LineView(c));
        lunar = group.add(new LunarView(c));
        pills = group.add(new Pills(c, u));
        lyric = group.add(new Lyric(c, u, frames));
        dialBox.setVisibility(View.GONE);
        for (View v : new View[] { time, colon, date, lunar, pills, face, handH, handM, handS, hub, cover }) v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    // ================= 外面改的状态 =================
    void setScene(int s) {
        if (s == scene) return;
        scene = s;
        styles();
        wake();
    }
    void setPortrait(boolean p) { if (portrait == p) return; portrait = p; layout(); }
    void setHasCover(boolean on) { if (hasCover == on) return; hasCover = on; styles(); wake(); }
    /** 开着页面时时钟藏起来（.mode-page .clock opacity 0）、玩游戏时淡到 .25 */
    void setMode(boolean page, boolean game) { groupA.to(page ? 0 : game ? .25f : 1, 500, Ease.EASE, SystemClock.uptimeMillis()); paused = page; wake(); }
    /** 聊天室开着（横屏）：时间挪到左半边正中、缩到 .78 */
    void setRoom(boolean on) { roomK.to(on && !portrait ? 1 : 0, 450, MOVE, SystemClock.uptimeMillis()); wake(); }
    void setHidden(boolean h) { hidden = h; if (!h) wake(); }
    void setShown(boolean t, boolean d, boolean l) {
        timeShown = t; dateShown = d; lunarShown = l;
        time.setVisibility(t ? View.VISIBLE : View.GONE);
        colon.setVisibility(t ? View.VISIBLE : View.GONE);
        if (!t) dialBox.setVisibility(View.GONE); else if (dialMode) dialBox.setVisibility(View.VISIBLE);
        date.setVisibility(d ? View.VISIBLE : View.GONE);
        lunar.setVisibility(l ? View.VISIBLE : View.GONE);
        layout();
    }

    private void styles() {
        int sh = scene == Sky.DUSK ? Ease.rgba(96, 28, 48, .32f) : scene == Sky.NIGHT ? Ease.rgba(0, 4, 24, .45f) : Ease.rgba(16, 46, 100, .3f);
        int edge = scene == Sky.DUSK ? Ease.rgba(80, 22, 44, 1) : Ease.rgba(12, 36, 84, 1);
        float rem = u.px(u.rem);
        if (dialMode && hasCover) time.shadows = new Txt.Shadow[] { new Txt.Shadow(0, .05f * rem, Ease.rgba(0, 0, 0, .55f)), new Txt.Shadow(.02f * rem, .12f * rem, Ease.rgba(0, 0, 0, .45f)) };
        else if (scene == Sky.NIGHT) time.shadows = new Txt.Shadow[] { new Txt.Shadow(.03f * rem, .26f * rem, sh) };
        else time.shadows = new Txt.Shadow[] { new Txt.Shadow(0, .05f * rem, Ease.alpha(edge, .3f)), new Txt.Shadow(.03f * rem, .26f * rem, sh) };
        colon.shadows = time.shadows;
        Txt.Shadow[] strong = { new Txt.Shadow(0, .04f * rem, Ease.alpha(edge, .6f)), new Txt.Shadow(.02f * rem, .12f * rem, Ease.alpha(edge, .45f)) };
        date.shadows = scene == Sky.NIGHT ? new Txt.Shadow[] { new Txt.Shadow(.02f * rem, .12f * rem, sh) } : strong;
        lunar.shadows = scene == Sky.NIGHT ? new Txt.Shadow[] { new Txt.Shadow(.01f * rem, .1f * rem, sh) } : strong;
        lunar.textAlpha = scene == Sky.NIGHT ? .82f : 1;
        int col = scene == Sky.DUSK ? 0xfffff8ef : 0xffffffff;
        time.color = colon.color = date.color = lunar.color = col;
        face.shadow = sh;
        handH.shadow = handM.shadow = handS.shadow = sh;
        pills.setScene(scene);
        lyric.setScene(scene, sh);
        time.invalidate(); colon.invalidate(); date.invalidate(); lunar.invalidate(); face.invalidate();
        handH.invalidate(); handM.invalidate(); handS.invalidate();
    }

    // ================= 数字时间 =================
    private long shineUntil;
    /** 每秒调一次：写数字（变了才翻）、表盘走针 */
    void tick(Calendar d) {
        if (!timeShown) return;
        String s = pad(d.get(Calendar.HOUR_OF_DAY)) + ":" + pad(d.get(Calendar.MINUTE));
        time.render(s, SystemClock.uptimeMillis());
        if (dialMode) updateDial(d, false);
        wake();
    }
    static String pad(int n) { return (n < 10 ? "0" : "") + n; }
    /** 数字逐个弹入（开场、切换数字 / 表盘时），delay 是整组的起始延时 */
    void digitsIn(int delay) {
        Calendar d = Calendar.getInstance();
        time.entrance(pad(d.get(Calendar.HOUR_OF_DAY)) + ":" + pad(d.get(Calendar.MINUTE)), SystemClock.uptimeMillis(), delay);
        timeFade.set(1);
        wake();
    }

    // ================= 表盘 =================
    private final float[] handDeg = { -1, -1, -1 };
    private final Tw[] hand = { new Tw(0), new Tw(0), new Tw(0) };
    private boolean sweep;
    private long sweepUntil;
    /** 角度累加（不回绕）：过渡只向前转，不会倒转一圈 */
    private void turn(int i, float deg, long now) {
        float prev = handDeg[i];
        if (prev >= 0) while (deg < prev - 0.01f) deg += 360;
        handDeg[i] = deg;
        boolean sw = now < sweepUntil;
        Ease e = sw ? SWEEP : i == 2 ? SEC : HAND;
        hand[i].to(deg, sw ? 1500 : i == 2 ? 300 : 500, e, now);
    }
    void updateDial(Calendar d, boolean jump) {
        long now = SystemClock.uptimeMillis();
        float s = d.get(Calendar.SECOND), m = d.get(Calendar.MINUTE) + s / 60, h = (d.get(Calendar.HOUR_OF_DAY) % 12) + m / 60;
        float[] base = { h * 30, m * 6, s * 6 };
        for (int i = 0; i < 3; i++) {
            float b = base[i] - (float) Math.floor(base[i] / 360) * 360;
            turn(i, handDeg[i] < 0 ? b : (float) Math.floor(handDeg[i] / 360) * 360 + b, now);
        }
        wake();
    }
    /** 表盘进场：外圈画出来、刻度依次亮起，指针从 12 点扫到当前时间 */
    void dialEntrance() {
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) { handDeg[i] = -1; hand[i].set(0); }
        face.enter(now, false);
        dialIn = now; dialOutAt = 0;
        sweepUntil = now + 1700;
        dialBox.setVisibility(timeShown ? View.VISIBLE : View.GONE);
        // 80ms 后指针开始扫到当前时间
        group.postDelayed(() -> updateDial(Calendar.getInstance(), false), 80);
        wake();
    }
    private long dialIn = -10000, dialOutAt;
    /** 表盘退场：刻度倒序熄灭、外圈收起、整个表盘缩小淡出 */
    void dialOut() {
        long now = SystemClock.uptimeMillis();
        face.enter(now, true);
        dialOutAt = now;
        wake();
    }

    // ================= 切数字 / 表盘（同 main.js 的 setClock） =================
    private Runnable swapJob;
    /** 返回切换期间封面要先藏起来的毫秒数 */
    int setDial(boolean toDial, boolean fromDial) {
        long now = SystemClock.uptimeMillis();
        if (swapJob != null) group.removeCallbacks(swapJob);
        if (!toDial && fromDial) { // 刻度倒序熄灭、外圈收起、表盘缩小淡出，再把大数字弹回来
            dialOut();
            timeFade.to(0, 220, Ease.EASE, now);
            swapJob = () -> { dialMode = false; layout(); styles(); dialBox.setVisibility(View.GONE); digitsIn(150); };
            group.postDelayed(swapJob, 520);
        } else if (toDial && !fromDial) { // 大数字先淡出，表盘画出来，小号数字随后出现在表盘下半部
            timeFade.to(0, 220, Ease.EASE, now);
            swapJob = () -> { dialMode = true; layout(); styles(); dialEntrance(); digitsIn(650); };
            group.postDelayed(swapJob, 220);
        } else {
            dialMode = toDial;
            layout(); styles();
            if (timeFade.target() < 1) digitsIn(toDial ? 300 : 150);
            if (toDial && dialOutAt > 0) dialEntrance();
        }
        wake();
        return fromDial ? 700 : 450;
    }
    /** 直接设成某种样式（开场） */
    void initDial(boolean on) {
        dialMode = on;
        layout(); styles();
        if (on) dialEntrance(); else dialBox.setVisibility(View.GONE);
    }

    // ================= 布局 =================
    void layout() {
        long now = SystemClock.uptimeMillis();
        float rem = u.rem;
        boolean jump = introAt == 0;
        float tTop = dialMode ? .66f : .9f, tH = dialMode ? (portrait ? 3.7f : 3.4f) : (portrait ? 2.05f : 2.6f), tPad = dialMode ? (portrait ? 2.42f : 2.22f) : 0;
        if (jump) { top.set(tTop); timeH.set(tH); padTop.set(tPad); }
        else { top.to(tTop, 700, MOVE, now); timeH.to(tH, 700, MOVE, now); padTop.to(tPad, 700, MOVE, now); }
        time.setSize(dialMode ? (portrait ? .38f : .34f) * u.px(rem) : (portrait ? 2.05f : 2.6f) * u.px(rem), dialMode);
        colon.font = time.paint;
        float W = u.px(u.W);
        Layer.place(group, 0, 0, W, u.px(u.H));
        float D = (portrait ? 3.7f : 3.4f) * u.px(rem);
        Layer.place(dialBox, W / 2 - D / 2, 0, D, D);
        Layer.place(face, 0, 0, D, D);
        float pad = .1f * u.px(rem);
        placeHand(handH, D, .09f, .24f, pad);
        placeHand(handM, D, .06f, .36f, pad);
        placeHand(handS, D, .025f, .46f, pad);
        float hb = .14f * u.px(rem) + 2 * .03f * u.px(rem) + 2;
        Layer.place(hub, D / 2 - hb / 2, D / 2 - hb / 2, hb, hb);
        date.setFont(Fonts.sysMedium, .36f * u.px(rem), .14f);
        lunar.setFont(.27f * u.px(rem), .23f * u.px(rem));
        cover.layoutFor(dialMode, portrait);
        styles(); // 投影大小按 rem 算
        wake();
    }
    private void placeHand(HandView h, float D, float wRem, float lenK, float pad) {
        float w = wRem * u.px(u.rem), len = D * lenK;
        h.w = w; h.len = len; h.pad = pad;
        Layer.place(h, D / 2 - w / 2 - pad, D / 2 - len - pad, w + 2 * pad, len + 2 * pad);
        h.setPivotX(w / 2 + pad); h.setPivotY(len + pad);
        h.invalidate();
    }

    // ================= 每帧 =================
    /** 有东西变了：下一帧整块重新摆一次 */
    private boolean dirty = true;
    void wake() { dirty = true; if (!hidden) frames.add(this, 60); }

    @Override public boolean frame(long now) {
        if (hidden) return false;
        if (!dirty) { // 只有冒号在呼吸：只改它的透明度
            if (!timeShown || paused || groupA.get(now) < 0.003f) return false;
            float ph = (now % 2000) / 2000f, half = ph < .5f ? ph * 2 : (1 - ph) * 2;
            colon.breath = 1 - .7f * Ease.EASE.at(half);
            colon.applyAlpha(colon.lastAlpha);
            return true;
        }
        float rem = u.px(u.rem), W = u.px(u.W);
        boolean busy = false;
        long it = now - introAt;
        boolean intro = introAt > 0 && it < 1900;
        busy |= intro;
        // 整块：淡入淡出、开聊天室时挪到左边缩小
        float ga = groupA.get(now), rk = roomK.get(now);
        busy |= groupA.moving(now) | roomK.moving(now);
        group.setAlpha(ga);
        group.setPivotX(W / 2); group.setPivotY(0);
        float sc = 1 - .22f * rk;
        group.setScaleX(sc); group.setScaleY(sc);
        group.setTranslationX(-W * .25f * rk);
        group.setVisibility(ga < 0.003f ? View.INVISIBLE : View.VISIBLE);
        // 竖排：时钟顶、时间高度
        float y0 = top.get(now) * rem, th = timeH.get(now) * rem, pt = padTop.get(now) * rem;
        busy |= top.moving(now) | timeH.moving(now) | padTop.moving(now);
        // 时间
        float tf = timeFade.get(now);
        busy |= timeFade.moving(now);
        float tAlpha = tf * (hasCover && !dialMode ? .5f : 1);
        if (time.place(y0 + pt, W)) busy = true;
        time.setAlpha(tAlpha);
        busy |= time.animating(now);
        colon.place(time, now);
        colon.setAlpha(tAlpha);
        boolean breathe = timeShown && !paused && ga > 0.003f;
        if (breathe) {
            float ph = (now % 2000) / 2000f, half = ph < .5f ? ph * 2 : (1 - ph) * 2;
            colon.breath = 1 - .7f * Ease.EASE.at(half);
            colon.applyAlpha(tAlpha);
        }
        // 表盘
        if (dialBox.getVisibility() == View.VISIBLE) {
            dialBox.setTranslationY(y0);
            float k, s;
            if (dialOutAt > 0) {
                float o = Ease.clamp((now - dialOutAt) / 450f, 0, 1), e = DIAL_OUT.at(o);
                k = 1 - e; s = 1 - .1f * e;
                if (o >= 1 && !dialMode) dialBox.setVisibility(View.GONE);
                busy |= o < 1;
            } else {
                float o = Ease.clamp((now - dialIn) / 900f, 0, 1), e = MOVE.at(o);
                k = e; s = .86f + .14f * e;
                busy |= o < 1;
            }
            dialBox.setPivotX(dialBox.getWidth() / 2f); dialBox.setPivotY(dialBox.getHeight() / 2f);
            dialBox.setAlpha(k); dialBox.setScaleX(s); dialBox.setScaleY(s);
            float inner = hasCover ? .85f : 1;
            face.setAlpha(inner);
            busy |= face.animating(now);
            float ha = face.handAlpha(now) * inner;
            for (int i = 0; i < 3; i++) {
                HandView h = i == 0 ? handH : i == 1 ? handM : handS;
                h.setRotation(hand[i].get(now));
                h.setAlpha(ha);
                busy |= hand[i].moving(now);
            }
            hub.setAlpha(ha);
        }
        // 下面几行：日期、农历、胶囊、歌词
        float y = y0 + th;
        if (dateShown) {
            y += .14f * rem;
            float rise = intro ? RISE.at(Ease.clamp((it - 650) / 900f, 0, 1)) : 1;
            float dw = date.tightW();
            Layer.place(date, (W - dw) / 2, y, dw, date.lineH());
            date.setTranslationY((1 - rise) * .35f * rem);
            date.setAlpha(rise);
            y += date.lineH();
        }
        if (lunarShown) {
            y += .08f * rem;
            float rise = intro ? RISE.at(Ease.clamp((it - 780) / 900f, 0, 1)) : 1;
            float lw = lunar.tightW();
            Layer.place(lunar, (W - lw) / 2, y, lw, lunar.lineH());
            lunar.setTranslationY((1 - rise) * .35f * rem);
            lunar.setAlpha(rise);
            y += lunar.lineH();
        }
        y += .12f * rem;
        {
            float rise = intro ? RISE.at(Ease.clamp((it - 900) / 900f, 0, 1)) : 1;
            float ph = Math.max(.42f * rem, pills.contentH(W)), pp = pills.pad();
            Layer.place(pills, 0, y - pp, W, ph + 2 * pp);
            pills.setTranslationY((1 - rise) * .35f * rem);
            pills.setAlpha(rise);
            y += ph;
        }
        y += .1f * rem;
        lyric.placeAt(y, W);
        cover.placeAt(y0, W, now);
        busy |= cover.animating(now);
        colon.lastAlpha = tAlpha;
        dirty = busy;
        if (busy) return true;
        if (breathe) { frames.add(this, 30); return true; }
        return false;
    }

    // ================= 各个 View =================
    /** 数字时间：每一位单独画，翻页（3D 翻滚）、进场上浮 */
    final class TimeView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Txt.Shadow[] shadows;
        int color = 0xffffffff;
        private boolean small;
        private float size = 10;
        private final StringBuilder chars = new StringBuilder();
        private final char[] prev = new char[8];
        private final long[] flipAt = new long[8], inAt = new long[8];
        private final Camera cam = new Camera();
        private final Matrix m = new Matrix();
        private float x0, lineTop;
        TimeView(Context c) { super(c); paint.setTextAlign(Paint.Align.LEFT); }

        void setSize(float px, boolean dial) {
            small = dial; size = px;
            paint.setTypeface(dial ? Fonts.digits : Fonts.tall);
            paint.setTextSize(px);
            paint.setLetterSpacing(dial ? .08f : .02f);
            invalidate();
        }
        /** 换数字：位数变了整组重来（进场），否则只翻变了的那几位（依次错开 80ms） */
        void render(String s, long now) {
            if (chars.length() != s.length()) { entrance(s, now, 150); return; }
            int k = 0;
            for (int i = 0; i < s.length(); i++) {
                if (chars.charAt(i) == s.charAt(i)) continue;
                prev[i] = chars.charAt(i);
                chars.setCharAt(i, s.charAt(i));
                flipAt[i] = now + k * 80L;
                k++;
            }
            if (k > 0) invalidate();
        }
        void entrance(String s, long now, int delay) {
            chars.setLength(0);
            chars.append(s);
            for (int i = 0; i < s.length(); i++) { flipAt[i] = 0; inAt[i] = now + delay + i * 70L; }
            invalidate();
        }
        String text() { return chars.toString(); }
        /** 摆在 top（像素）这一行，水平居中；返回大小变了没有 */
        boolean place(float top, float W) {
            lineTop = top;
            float w = width(), pad = size * .45f;
            x0 = pad;
            Layer.place(this, (W - w) / 2 - pad, top - size * .6f, w + 2 * pad, size * 2.2f);
            return false;
        }
        private float widthOf = -1, widthSize;
        private String widthText = "";
        /** 整组数字的宽度（按字缓存：字没变、字号没变就不重新量） */
        float width() {
            String t = chars.toString();
            if (t.equals(widthText) && widthSize == size && widthOf >= 0) return widthOf;
            float w = 0;
            for (int i = 0; i < chars.length(); i++) w += adv(chars.charAt(i));
            widthText = t; widthSize = size; widthOf = w;
            return w;
        }
        /** 数字实际占的范围（像素，卡片坐标） */
        RectF digitsRect() {
            if (chars.length() == 0 || getVisibility() != VISIBLE) return null;
            float w = width();
            float top = getTop() + getTranslationY() + size * .6f, left = getLeft() + x0 + group.getTranslationX();
            return new RectF(left, top, left + w, top + size);
        }
        private final char[] one = new char[1];
        float adv(char ch) { one[0] = ch; return paint.measureText(one, 0, 1) + (ch == ':' ? size * .04f : 0); }
        float colonX() {
            float x = x0;
            for (int i = 0; i < chars.length(); i++) { if (chars.charAt(i) == ':') return x + size * .02f; x += adv(chars.charAt(i)); }
            return -1;
        }
        float baseline() { return size * .6f + Fonts.baseIn(paint, size); }
        boolean animating(long now) {
            for (int i = 0; i < chars.length(); i++) {
                if (inAt[i] > 0 && now - inAt[i] < 900) return true;
                if (flipAt[i] > 0 && now - flipAt[i] < 800) return true;
            }
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float x = x0, by = baseline();
            boolean anim = false;
            paint.setColor(color);
            for (int i = 0; i < chars.length(); i++) {
                char ch = chars.charAt(i);
                float w = adv(ch);
                if (ch == ':') { x += w; continue; } // 冒号单独一个 View（呼吸）
                float cx = x + w / 2;
                // 进场：从下面 .32em 浮上来淡入
                float ink = 1, dy = 0;
                if (inAt[i] > 0) {
                    float k = Ease.clamp((now - inAt[i]) / 900f, 0, 1), e = MOVE.at(k);
                    ink = e; dy = (1 - e) * .32f * size;
                    if (k < 1) anim = true; else inAt[i] = 0;
                }
                if (flipAt[i] > 0 && now >= flipAt[i] - 1) {
                    float t = now - flipAt[i];
                    if (t < 800) {
                        anim = true;
                        float ko = Ease.clamp(t / 500f, 0, 1), eo = FLIP_OUT.at(ko);
                        if (ko < 1) digit(c, prev[i], x, by + dy, cx, -.34f * size * eo, 80 * eo, (1 - eo) * ink);
                        float ki = Ease.clamp(t / 800f, 0, 1), ei = FLIP_IN.at(ki);
                        digit(c, ch, x, by + dy, cx, .36f * size * (1 - ei), -80 * (1 - ei), Ease.clamp(ei, 0, 1) * ink);
                    } else { flipAt[i] = 0; digit(c, ch, x, by + dy, cx, 0, 0, ink); }
                } else if (flipAt[i] > 0) { // 排着队还没轮到：旧数字照常显示
                    anim = true;
                    digit(c, prev[i], x, by + dy, cx, 0, 0, ink);
                } else digit(c, ch, x, by + dy, cx, 0, 0, ink);
                x += w;
            }
            if (anim) postInvalidateOnAnimation();
        }
        /** 画一位数字：上下平移 ty、绕水平轴转 rx 度（透视 2.4em，同 CSS perspective） */
        private void digit(Canvas c, char ch, float x, float y, float cx, float ty, float rx, float a) {
            if (a <= 0.003f) return;
            String s = String.valueOf(ch);
            if (rx != 0) {
                c.save();
                float cy = y - size * .36f;
                cam.save();
                cam.setLocation(0, 0, -2.4f * size / 72f);
                cam.rotateX(-rx);
                cam.getMatrix(m);
                cam.restore();
                m.preTranslate(-cx, -cy);
                m.postTranslate(cx, cy + ty);
                c.concat(m);
                Txt.draw(c, s, x, y, paint, shadows, a);
                c.restore();
            } else Txt.draw(c, s, x, y + ty, paint, shadows, a);
        }
    }

    /** 冒号：单独一个小 View，慢慢呼吸（只改透明度） */
    static final class ColonView extends View {
        Paint font;
        Txt.Shadow[] shadows;
        int color = 0xffffffff;
        float breath = 1, lastAlpha = 1;
        private float base = 1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ColonView(Context c) { super(c); }
        void place(TimeView t, long now) {
            float x = t.colonX();
            if (x < 0 || font == null || t.getVisibility() != VISIBLE) { setVisibility(INVISIBLE); return; }
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            float w = font.measureText(":"), pad = font.getTextSize() * .4f;
            Layer.place(this, t.getLeft() + x - pad, t.getTop(), w + 2 * pad, t.getHeight());
            // 冒号的进场 / 翻页跟着时间那一位走
            float dy = 0, a = 1;
            int i = t.text().indexOf(':');
            if (i >= 0 && t.inAt[i] > 0) { float k = Ease.clamp((now - t.inAt[i]) / 900f, 0, 1), e = MOVE.at(k); a = e; dy = (1 - e) * .32f * font.getTextSize(); }
            setTranslationY(dy);
            base = a;
        }
        void applyAlpha(float a) { setAlpha(a * base * breath); }
        @Override public void setAlpha(float a) { super.setAlpha(Ease.clamp(a, 0, 1)); }
        @Override protected void onDraw(Canvas c) {
            if (font == null) return;
            p.set(font);
            p.setColor(color);
            float size = font.getTextSize(), pad = size * .4f;
            float by = size * .6f + Fonts.baseIn(p, size) - .06f * size;
            Txt.draw(c, ":", pad, by, p, shadows, 1);
        }
    }

    /** 表盘的盘面：外圈、60 个刻度、12 / 3 / 6 / 9；进场外圈画出来、刻度顺时针亮起，退场倒序熄灭 */
    final class DialFace extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] tk = new float[240];
        private final RectF oval = new RectF();
        private long at = -10000;
        private boolean out;
        int shadow;
        DialFace(Context c) {
            super(c);
            for (int i = 0; i < 60; i++) {
                double a = Math.toRadians(i * 6);
                float r1 = i % 5 == 0 ? 84 : 89, r2 = 96;
                tk[i * 4] = (float) (100 + r1 * Math.sin(a)); tk[i * 4 + 1] = (float) (100 - r1 * Math.cos(a));
                tk[i * 4 + 2] = (float) (100 + r2 * Math.sin(a)); tk[i * 4 + 3] = (float) (100 - r2 * Math.cos(a));
            }
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        void enter(long now, boolean out) { at = now; this.out = out; invalidate(); }
        boolean animating(long now) { return now - at < (out ? 600 : 1300); }
        float handAlpha(long now) {
            float t = now - at;
            return out ? 1 - Ease.IN.at(Ease.clamp(t / 220f, 0, 1)) : Ease.EASE.at(Ease.clamp((t - 250) / 400f, 0, 1));
        }
        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float t = now - at, s = getWidth() / 200f;
            c.save();
            c.scale(s, s);
            // 外圈
            float ring, fill;
            if (out) { float k = DIAL_OUT.at(Ease.clamp(t / 450f, 0, 1)); ring = 1 - k; fill = 1 - k; }
            else { float k = RING_IN.at(Ease.clamp(t / 1100f, 0, 1)); ring = k; fill = k; }
            oval.set(1, 1, 199, 199);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ease.alpha(0xffffffff, .07f * fill));
            c.drawCircle(100, 100, 99, p);
            if (ring > 0.001f) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.1f);
                p.setColor(Ease.alpha(0xffffffff, .5f));
                if (out) c.drawArc(oval, -90 + 360 * (1 - ring), 360 * ring, false, p);
                else c.drawArc(oval, -90, 360 * ring, false, p);
            }
            // 刻度
            for (int i = 0; i < 60; i++) {
                float a = out ? 1 - Ease.IN.at(Ease.clamp((t - (59 - i) * 4) / 200f, 0, 1)) : Ease.EASE.at(Ease.clamp((t - 120 - i * 13) / 300f, 0, 1));
                if (a <= 0.003f) continue;
                boolean big = i % 5 == 0;
                p.setStrokeWidth(big ? 2.6f : 1);
                p.setColor(big ? Ease.alpha(0xffffffff, a) : Ease.alpha(0xffffffff, .42f * a));
                c.drawLine(tk[i * 4], tk[i * 4 + 1], tk[i * 4 + 2], tk[i * 4 + 3], p);
            }
            // 12 / 3 / 6 / 9
            float na = out ? 1 - Ease.IN.at(Ease.clamp(t / 220f, 0, 1)) : Ease.EASE.at(Ease.clamp((t - 750) / 500f, 0, 1));
            if (na > 0.003f) {
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(Fonts.digits);
                p.setTextSize(13);
                p.setLetterSpacing(0);
                p.setTextAlign(Paint.Align.CENTER);
                p.setColor(Ease.alpha(0xffffffff, .92f * na));
                c.drawText("12", 100, 30, p); c.drawText("3", 172, 106, p); c.drawText("6", 100, 181, p); c.drawText("9", 28, 106, p);
            }
            c.restore();
            if (animating(now)) postInvalidateOnAnimation();
        }
    }

    /** 一根指针：圆角细条，绕表盘中心转（旋转走 View 的属性） */
    static final class HandView extends View {
        float w, len, pad;
        int color, shadow;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        HandView(Context c, int color) { super(c); this.color = color; }
        @Override protected void onDraw(Canvas c) {
            float r = getResources().getDisplayMetrics().density * 2;
            p.setColor(color);
            Fonts.shadow(p, pad * .1f, pad * .6f, shadow);
            c.drawRoundRect(pad, pad, pad + w, pad + len, Math.min(r, w / 2), Math.min(r, w / 2), p);
        }
    }

    /** 表盘中心的小圆点（橙色 + 白边） */
    static final class HubView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        HubView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, r = (getWidth() - 2) / 2f, inner = r / (1 + 2 * .03f / .14f);
            p.setColor(0xffffffff);
            c.drawCircle(cx, cx, r, p);
            p.setColor(0xffff7a4d);
            c.drawCircle(cx, cx, inner, p);
        }
    }

    /** 一行居中的字（日期）：字距 letter-spacing、首行缩进同字距（同 CSS 的 letter-spacing + text-indent，居中时左右对称） */
    static final class LineView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Txt.Shadow[] shadows;
        int color = 0xffffffff;
        String text = "";
        LineView(Context c) { super(c); }
        void setFont(android.graphics.Typeface tf, float size, float spacing) { p.setTypeface(tf); p.setTextSize(size); p.setLetterSpacing(spacing); invalidate(); }
        void setText(String s) { if (s.equals(text)) return; text = s; invalidate(); requestLayoutish(); }
        private void requestLayoutish() { if (getParent() instanceof View) ((View) getParent()).invalidate(); }
        float lineH() { return Fonts.lineH(p); }
        /** 字宽加左右留给投影的余量 */
        float tightW() { return p.measureText(text) + p.getTextSize() * .8f; }
        @Override protected void onDraw(Canvas c) {
            p.setColor(color);
            float ls = p.getLetterSpacing() * p.getTextSize();
            float w = p.measureText(text);
            Txt.draw(c, text, (getWidth() - w) / 2f + ls / 2, Fonts.base(p), p, shadows, 1);
        }
    }

    /** 农历一行：文字 + 节日 / 节气 / 生日小胶囊（红底） */
    static final class LunarView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tagP = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        Txt.Shadow[] shadows;
        int color = 0xffffffff;
        float textAlpha = .82f;
        String text = "";
        String[] tags = new String[0];
        private float rem1;
        private final RectF r = new RectF();
        LunarView(Context c) { super(c); p.setTypeface(Fonts.sys); tagP.setTypeface(Fonts.sysMedium); bg.setColor(Ease.rgba(255, 112, 92, .88f)); }
        void setFont(float size, float tagSize) {
            p.setTextSize(size); p.setLetterSpacing(.12f);
            tagP.setTextSize(tagSize); tagP.setLetterSpacing(.06f);
            rem1 = size / .27f;
            invalidate();
        }
        void set(String t, String[] tg) {
            if (t.equals(text) && java.util.Arrays.equals(tg, tags)) return;
            text = t; tags = tg;
            invalidate();
        }
        float lineH() { return Fonts.lineH(p); }
        float tightW() {
            float w = p.measureText(text), gap = .12f * rem1, padX = .12f * rem1;
            for (String t : tags) w += gap + tagP.measureText(t) + 2 * padX;
            return w + p.getTextSize() * .8f;
        }
        @Override protected void onDraw(Canvas c) {
            float ls = p.getLetterSpacing() * p.getTextSize();
            float w = p.measureText(text), gap = .12f * rem1, padX = .12f * rem1;
            float[] tw = new float[tags.length];
            for (int i = 0; i < tags.length; i++) { tw[i] = tagP.measureText(tags[i]) + 2 * padX; w += gap + tw[i]; }
            float x = (getWidth() - w) / 2f + ls / 2, by = Fonts.base(p);
            p.setColor(color);
            Txt.draw(c, text, x, by, p, shadows, textAlpha);
            x += p.measureText(text);
            float th = Fonts.lineH(tagP) + .05f * rem1;
            for (int i = 0; i < tags.length; i++) {
                x += gap;
                float top = by - Fonts.base(tagP) - .02f * rem1 + .01f * rem1;
                r.set(x, top, x + tw[i], top + th);
                c.drawRoundRect(r, .2f * rem1, .2f * rem1, bg);
                tagP.setColor(color);
                c.drawText(tags[i], x + padX, top + .02f * rem1 + Fonts.base(tagP), tagP);
                x += tw[i];
            }
        }
    }
}
