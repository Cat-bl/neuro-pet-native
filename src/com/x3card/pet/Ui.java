package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

/**
 * 浮在最上面的小部件（同 .gear / .ai-btn / .fs-btn / .dots / .nav / .err）：
 * 齿轮、聊天键、全屏键点屏幕才出现（5 秒没碰藏起来），横向一排在右下角、竖向小容器竖着叠；
 * 顶上的分页圆点、地面带里的底部导航（宠物页点屏幕才出现，打开页面时一直显示）；出错时底部一条红色说明
 */
final class Ui implements Frames.Client {
    static final String GEAR = "M19.4 13a7.7 7.7 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.6 7.6 0 0 0-1.7-1L15 3H9l-.3 2.9a7.6 7.6 0 0 0-1.7 1l-2.5-1-2 3.5L4.6 11a7.7 7.7 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1a7.6 7.6 0 0 0 1.7 1L9 21h6l.3-2.9a7.6 7.6 0 0 0 1.7-1l2.5 1 2-3.5L19.4 13zM12 15.5A3.5 3.5 0 1 1 12 8.5a3.5 3.5 0 0 1 0 7z";
    static final String CHAT = "M12 3.5c-5 0-9 3.3-9 7.4 0 2.3 1.3 4.4 3.3 5.8-.2 1.4-.9 2.7-1.9 3.6 2 .1 3.9-.6 5.3-1.7.8.2 1.5.2 2.3.2 5 0 9-3.3 9-7.4S17 3.5 12 3.5zm-4.3 8.6a1.2 1.2 0 1 1 0-2.4 1.2 1.2 0 0 1 0 2.4zm4.3 0a1.2 1.2 0 1 1 0-2.4 1.2 1.2 0 0 1 0 2.4zm4.3 0a1.2 1.2 0 1 1 0-2.4 1.2 1.2 0 0 1 0 2.4z";
    static final String FULL = "M4 9.5V4h5.5M14.5 4H20v5.5M20 14.5V20h-5.5M9.5 20H4v-5.5", SMALL = "M9.5 4v5.5H4M20 9.5h-5.5V4M14.5 20v-5.5H20M4 14.5h5.5V20";

    private final App app;
    private final U u;
    final RoundBtn gear, chat, fs;
    final Dots dots;
    final Nav nav;
    final ErrView err;
    private final Tw gearK = new Tw(0), navK = new Tw(0);
    private boolean gearOn, navOn;
    private final Runnable hideGear = this::hideGear, hideNav = this::hideNav;
    boolean typing, roomOpen;
    Runnable onGear, onChat, onFs;

    Ui(Context c, App app, Layer host) {
        this.app = app;
        u = app.u;
        dots = host.add(new Dots(c));
        nav = host.add(new Nav(c));
        gear = host.add(new RoundBtn(c, GEAR, false, () -> { if (onGear != null) onGear.run(); }));
        chat = host.add(new RoundBtn(c, CHAT, false, () -> { if (onChat != null) onChat.run(); }));
        fs = host.add(new RoundBtn(c, FULL, true, () -> { if (onFs != null) onFs.run(); }));
        err = host.add(new ErrView(c));
        err.setVisibility(View.GONE);
    }

    // ================= 齿轮这一组 =================
    void showGear() {
        showNav();
        gearOn = true;
        gearK.to(1, 300, Ease.EASE, SystemClock.uptimeMillis());
        app.h.removeCallbacks(hideGear);
        if (!app.panelOpen) app.h.postDelayed(hideGear, 5000);
        sync();
    }
    void hideGear() {
        if (app.panelOpen || app.guiding) return;
        gearOn = false;
        gearK.to(0, 300, Ease.EASE, SystemClock.uptimeMillis());
        sync();
    }
    /** 新手引导开着：齿轮、聊天键、导航一直显示；关了照常 5 秒后藏 */
    void setGuiding(boolean on) {
        if (on) { app.h.removeCallbacks(hideGear); app.h.removeCallbacks(hideNav); gearOn = navOn = true; gearK.to(1, 300, Ease.EASE, SystemClock.uptimeMillis()); navK.to(1, 300, Ease.EASE, SystemClock.uptimeMillis()); sync(); }
        else showGear();
    }
    void showNav() {
        navOn = true;
        navK.to(1, 300, Ease.EASE, SystemClock.uptimeMillis());
        app.h.removeCallbacks(hideNav);
        app.h.postDelayed(hideNav, 5000);
        sync();
    }
    private void hideNav() {
        if (!app.page.name.isEmpty() || app.panelOpen || app.guiding) return;
        navOn = false;
        navK.to(0, 300, Ease.EASE, SystemClock.uptimeMillis());
        sync();
    }

    /** 哪些显示（同 CSS 里 mode-game / mode-page / hide-* / ai-typing 那几条） */
    void sync() {
        boolean game = "game".equals(app.mode), pg = !app.page.name.isEmpty();
        gear.setVisibility(game || pg || typing ? View.GONE : View.VISIBLE);
        chat.setVisibility(game || pg || typing || !app.shown("chat") || app.ai == null || !app.ai.available() ? View.GONE : View.VISIBLE);
        fs.setVisibility(game || pg || typing || !app.shown("fs") || app.fsMode.isEmpty() ? View.GONE : View.VISIBLE);
        fs.setIcon("small".equals(app.fsMode) ? SMALL : FULL);
        boolean one = app.PAGE_ORDER.size() < 2;
        dots.setVisibility(!app.shown("dots") || one || game || roomOpen ? View.GONE : View.VISIBLE);
        nav.setVisibility(!app.shown("nav") || one || game || typing ? View.GONE : View.VISIBLE);
        layout();
        app.frames.add(this, 60);
    }
    void layout() {
        float rem = u.px(u.rem), W = u.px(u.W), H = u.px(u.H), b = .56f * rem;
        float right = .24f * rem, bottom = .22f * rem;
        place(gear, W - right - b, H - bottom - b, b);
        boolean chatOn = chat.getVisibility() == View.VISIBLE;
        if (u.portrait) {
            place(chat, W - right - b, H - 1.14f * rem - b, b);
            place(fs, W - right - b, H - (chatOn ? 2.06f : 1.14f) * rem - b, b);
        } else {
            place(chat, W - 1.18f * rem - b, H - bottom - b, b);
            place(fs, W - (chatOn ? 2.12f : 1.18f) * rem - b, H - bottom - b, b);
        }
        dots.lay();
        nav.lay();
        float ew = W - .4f * rem;
        err.lay(ew);
        Layer.place(err, .2f * rem, H - .2f * rem - err.h, ew, err.h);
    }
    private static void place(View v, float x, float y, float s) { Layer.place(v, x, y, s, s); v.setPivotX(s / 2); v.setPivotY(s / 2); }

    void syncPages() { dots.n = app.PAGE_ORDER.size(); nav.sync(); sync(); }
    void syncNav() { dots.cur = app.PAGE_ORDER.indexOf(app.page.name); dots.at = SystemClock.uptimeMillis(); nav.sync(); app.frames.add(this, 60); }
    void error(String msg) { err.text = msg; err.setVisibility(View.VISIBLE); layout(); err.invalidate(); }

    @Override public boolean frame(long now) {
        float g = gearK.get(now), n = navK.get(now);
        for (RoundBtn v : new RoundBtn[] { gear, chat, fs }) {
            v.setAlpha(g);
            float s = .8f + .2f * Ease.POP20.at(g);
            if (!v.down) { v.setScaleX(s); v.setScaleY(s); }
            v.setClickable(g > .5f);
        }
        float na = !app.page.name.isEmpty() ? 1 : n;
        nav.setAlpha(na);
        nav.setClickable(na > .5f);
        boolean busy = gearK.moving(now) | navK.moving(now);
        busy |= dots.step(now);
        busy |= nav.step(now);
        return busy;
    }

    // ================= 圆形图标键 =================
    final class RoundBtn extends View {
        private String icon;
        private final boolean stroke;
        private final Runnable click;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Matrix m = new Matrix();
        boolean down;
        RoundBtn(Context c, String icon, boolean stroke, Runnable click) { super(c); this.icon = icon; this.stroke = stroke; this.click = click; }
        void setIcon(String i) { if (!i.equals(icon)) { icon = i; invalidate(); } }
        @Override protected void onDraw(Canvas c) {
            float s = getWidth(), pad = s * .12f / .56f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ease.rgba(40, 50, 70, down ? .5f : .28f));
            c.drawCircle(s / 2, s / 2, s / 2, p);
            float k = (s - 2 * pad) / 24;
            m.setScale(k, k);
            m.postTranslate(pad, pad);
            SvgPath.of(icon).transform(m, path);
            p.setColor(0xffffffff);
            if (stroke) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f * k); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); }
            c.drawPath(path, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (getAlpha() < .5f) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: down = true; setScaleX(.92f); setScaleY(.92f); invalidate(); return true;
                case MotionEvent.ACTION_UP:
                    down = false; setScaleX(1); setScaleY(1); invalidate();
                    if (e.getX() >= 0 && e.getY() >= 0 && e.getX() <= getWidth() && e.getY() <= getHeight()) post(click);
                    return true;
                case MotionEvent.ACTION_CANCEL: down = false; setScaleX(1); setScaleY(1); invalidate(); return true;
            }
            return true;
        }
    }

    // ================= 分页圆点：当前页是拉长的白色胶囊 =================
    final class Dots extends View {
        int n = 4, cur = 1, prevCur = 1;
        long at;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Dots(Context c) { super(c); }
        void lay() {
            float rem = u.px(u.rem), w = (n - 1) * (.08f + .09f) * rem + (.24f + .09f) * rem + 8;
            Layer.place(this, u.px(u.W) / 2 - w / 2, .36f * rem - 4, w, .08f * rem + 8);
        }
        boolean step(long now) { if (now - at < 400) { invalidate(); return true; } prevCur = cur; return false; }
        @Override protected void onDraw(Canvas c) {
            float rem = u.px(u.rem), d = .08f * rem, x = 4 + .045f * rem, y = 4;
            long now = SystemClock.uptimeMillis();
            float k = Ease.SPRING.at(Ease.clamp((now - at) / 350f, 0, 1));
            p.setShadowLayer(.04f * rem / 2, 0, 0, Ease.rgba(0, 0, 0, .25f));
            for (int i = 0; i < n; i++) {
                float on = i == cur ? k : i == prevCur ? 1 - k : 0;
                float w = d + (.24f * rem - d) * on;
                p.setColor(Ease.mix(Ease.rgba(255, 255, 255, .45f), 0xffffffff, on));
                c.drawRoundRect(x, y, x + w, y + d, .04f * rem, .04f * rem, p);
                x += w + .09f * rem;
            }
        }
    }

    // ================= 底部导航：胶囊分段按钮，白色滑块跟着选中项移动 =================
    final class Nav extends View {
        private final TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String[] names = {}, keys = {};
        private float[] bx = {}, bw = {};
        private final Tw dotX = new Tw(0), dotW = new Tw(0);
        private float pad;
        private int pressed = -1;
        Nav(Context c) { super(c); }
        void sync() {
            java.util.ArrayList<String> k = new java.util.ArrayList<>(), n = new java.util.ArrayList<>();
            for (String s : app.PAGE_ORDER) { k.add(s); n.add(s.isEmpty() ? "宠物" : s.equals("car") ? "车况" : s.equals("calendar") ? "日历" : "倒数日"); }
            keys = k.toArray(new String[0]); names = n.toArray(new String[0]);
            lay();
            invalidate();
        }
        void lay() {
            float rem = u.px(u.rem);
            p.setTextSize(.26f * rem);
            pad = .04f * rem;
            bx = new float[keys.length]; bw = new float[keys.length];
            float x = pad;
            for (int i = 0; i < keys.length; i++) { bw[i] = p.measureText(names[i]) + .52f * rem; bx[i] = x; x += bw[i]; }
            float w = x + pad, h = .46f * rem + 2 * pad;
            Layer.place(this, u.px(u.W) / 2 - w / 2, u.px(u.H) * (1 - .035f) - h / 2, w, h);
            int cur = java.util.Arrays.asList(keys).indexOf(app.page.name);
            if (cur >= 0) { long now = SystemClock.uptimeMillis(); dotX.to(bx[cur], 400, Ease.SPRING, now); dotW.to(bw[cur], 400, Ease.SPRING, now); if (dotW.get(now) == 0) { dotX.set(bx[cur]); dotW.set(bw[cur]); } }
        }
        boolean step(long now) { boolean m = dotX.moving(now) | dotW.moving(now); if (m) invalidate(); return m; }
        @Override protected void onDraw(Canvas c) {
            float rem = u.px(u.rem), h = getHeight();
            long now = SystemClock.uptimeMillis();
            bg.setColor(Ease.rgba(16, 20, 32, .5f));
            bg.setShadowLayer(.12f * rem / 2, 0, .02f * rem, Ease.rgba(0, 0, 0, .25f));
            c.drawRoundRect(0, 0, getWidth(), h, .3f * rem, .3f * rem, bg);
            bg.clearShadowLayer();
            bg.setColor(0xffffffff);
            float dx = dotX.get(now), dw = dotW.get(now);
            if (dw > 0) c.drawRoundRect(dx, pad, dx + dw, pad + .46f * rem, .23f * rem, .23f * rem, bg);
            int cur = java.util.Arrays.asList(keys).indexOf(app.page.name);
            for (int i = 0; i < keys.length; i++) {
                boolean on = i == cur;
                p.setColor(on ? 0xff1d2433 : Ease.rgba(255, 255, 255, .88f));
                p.setTypeface(on ? Fonts.sysBold : Fonts.sys);
                float tw = p.measureText(names[i]);
                c.drawText(names[i], bx[i] + (bw[i] - tw) / 2, pad + .23f * rem - (p.descent() + p.ascent()) / 2, p);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (getAlpha() < .5f) return false;
            int hit = -1;
            for (int i = 0; i < keys.length; i++) if (e.getX() >= bx[i] && e.getX() <= bx[i] + bw[i]) hit = i;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) pressed = hit;
            if (e.getActionMasked() == MotionEvent.ACTION_UP && hit >= 0 && hit == pressed) {
                final String k = keys[hit];
                post(() -> { app.introCancel(); if (!k.isEmpty()) app.openPage(k); else app.closePage(false); showNav(); });
            }
            return true;
        }
    }

    // ================= 出错时底部一条（车机上看不到日志） =================
    final class ErrView extends View {
        String text = "";
        float h;
        private final TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private StaticLayout lay;
        ErrView(Context c) { super(c); bg.setColor(0xffb3261e); p.setColor(0xffffffff); }
        void lay(float w) {
            float rem = u.px(u.rem);
            p.setTextSize(.16f * rem);
            int tw = Math.max(1, Math.round(w - .32f * rem));
            lay = StaticLayout.Builder.obtain(text, 0, text.length(), p, tw).build();
            h = lay.getHeight() + .2f * rem;
        }
        @Override protected void onDraw(Canvas c) {
            float rem = u.px(u.rem);
            c.drawRoundRect(0, 0, getWidth(), getHeight(), .08f * rem, .08f * rem, bg);
            if (lay == null) return;
            c.save();
            c.translate(.16f * rem, .1f * rem);
            lay.draw(c);
            c.restore();
        }
        @Override public boolean onTouchEvent(MotionEvent e) { if (e.getActionMasked() == MotionEvent.ACTION_UP) setVisibility(GONE); return true; }
    }
}
