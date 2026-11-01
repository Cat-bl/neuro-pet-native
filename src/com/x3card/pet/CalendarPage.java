package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.LineHeightSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UpdateAppearance;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 日历页（同网页版 pages/calendar）：万年历。月视图（周一开头，固定 6 行）+ 农历 + 节气 + 节日 + 法定假日休/班 + 选中日详情。
 * 在月历上左右、上下滑都切月份；月历以外的地方左右滑交给宠物页切页。嵌在宠物页里，不播自己的进场动画（calm）。
 * 横向：月历在左、详情在右；竖向（页面宽 < 高）：月历在上、详情在下（只留常用几行）
 */
final class CalendarPage implements Pages.Page {
    private static final Ease OUT = new Ease(.16f, 1, .3f, 1);
    private static final String WEEK = "日一二三四五六", HEAD_WEEK = "一二三四五六日";
    private static final String[] NUM = new String[32];
    static { for (int i = 0; i < 32; i++) NUM[i] = String.valueOf(i); }
    // 配色：{ 浅色, 深色 }
    private static final int[] PANEL = { 0xffffffff, 0xff161d2b }, BG = { 0xfff4f5f9, 0xff0d121d }, TEXT = { 0xff1c2230, 0xffe8ebf3 },
        SUB = { 0xff7a8293, 0xff8c95a8 }, LINE = { 0xffeceef3, 0xff232c3d }, ACCENT = { 0xffff5a4f, 0xffff6b5f },
        BLUE = { 0xff2f7cf6, 0xff5b9bff }, GREEN = { 0xff1f9d6b, 0xff3ccf95 }, WE = { 0xffe2554b, 0xffff8a80 };
    private static final int WORK = 0xff6b7385, OFF_BG = 0x12ff5a4f, FEST_BG = 0x1fff5a4f, TERM_BG = 0x1f1f9d6b;
    private static final int T_TODAY = 100, T_PREV = 101, T_NEXT = 102;

    private final Pages.Api api;
    private final App app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Root root;
    private final Head head;
    private final Grid grid;
    private final Info info;
    private final Typeface light;
    private float rem;
    private boolean portrait, narrow;

    private int viewY, viewM;
    private Lunar.Ymd sel, now;
    private String clockText = "";

    // 深浅色：0 浅、1 深，切换时 0.8 秒过渡
    private final Tw dk = new Tw(0);
    private float dkv;
    private boolean themed;
    private final Frames.Client themeAnim = t -> { dkv = dk.get(t); invalidateAll(); return dk.moving(t); };
    private final Runnable tick = this::tick;

    CalendarPage(Context c, Pages.Api api) {
        this.api = api;
        app = api.app();
        rem = api.rem();
        light = Typeface.create(Typeface.DEFAULT, 300, false);
        root = new Root(c);
        head = new Head(c);
        grid = new Grid(c);
        info = new Info(c);
        root.addView(head);
        root.addView(info);
        root.addView(grid);
        now = Lunar.today();
        viewY = now.y; viewM = now.m;
        sel = now;
        theme();
        renderGrid(0);
        renderInfo();
        tick();
        Pages.PageEvent e = new Pages.PageEvent("calendar", "open");
        dayInfo(now, e);
        Holidays.Next hol = Holidays.next(now.y, now.m, now.d);
        if (hol != null) { e.holidayName = hol.name; e.holidayNow = hol.now; e.holidayDays = hol.now ? 0 : hol.days; }
        api.onEvent(e);
    }

    @Override public View view() { return root; }

    /** 深浅色听宠物页的（车机的日夜模式优先，没有按钟点）；第一次直接定，之后 0.8 秒过渡 */
    @Override public void theme() {
        boolean d = api.dark();
        long t = SystemClock.uptimeMillis();
        if (!themed) { themed = true; dk.set(d ? 1 : 0); dkv = dk.get(t); invalidateAll(); return; }
        dk.to(d ? 1 : 0, 800, Ease.EASE, t);
        if (dk.moving(t)) app.frames.add(themeAnim);
    }

    @Override public void destroy() {
        h.removeCallbacksAndMessages(null);
        app.frames.remove(themeAnim);
        app.frames.remove(head.anim);
        app.frames.remove(info.anim);
        grid.animate().cancel();
    }

    private int col(int[] p) { return Ease.mix(p[0], p[1], dkv); }
    private void invalidateAll() { head.invalidate(); grid.invalidate(); info.invalidate(); }
    private static boolean same(Lunar.Ymd a, int y, int m, int d) { return a != null && a.y == y && a.m == m && a.d == d; }
    private static String pad(int n) { return (n < 10 ? "0" : "") + n; }

    // ---------- 月视图 ----------
    /** dir：1 下个月（从右边滑进来）、-1 上个月、0 不动 */
    private void renderGrid(int dir) {
        int lead = (Lunar.weekday(viewY, viewM, 1) + 6) % 7; // 周一开头
        int t0 = Lunar.epochDay(viewY, viewM, 1) - lead;
        for (int i = 0; i < 42; i++) {
            Lunar.Ymd d = Lunar.ofEpochDay(t0 + i);
            grid.cy[i] = d.y; grid.cm[i] = d.m; grid.cd[i] = d.d;
            Lunar.Day l = Lunar.fromSolar(d.y, d.m, d.d);
            List<String> f = Lunar.festivals(d.y, d.m, d.d);
            String term = Lunar.term(d.y, d.m, d.d);
            if (!f.isEmpty()) { grid.raw[i] = f.get(0); grid.kind[i] = 1; }
            else if (!term.isEmpty()) { grid.raw[i] = term; grid.kind[i] = 2; }
            else if (l != null) { grid.raw[i] = l.day == 1 ? l.monthName : l.dayName; grid.kind[i] = (byte) (l.day == 1 ? 3 : 0); }
            else { grid.raw[i] = ""; grid.kind[i] = 0; }
            Holidays.Mark hm = Holidays.at(d.y, d.m, d.d);
            grid.hol[i] = (byte) (hm == null ? 0 : hm.off ? 1 : 2);
        }
        grid.relabel();
        head.titleS = viewY + "年" + viewM + "月";
        int last = Lunar.epochDay(viewY, viewM + 1, 1) - Lunar.epochDay(viewY, viewM, 1);
        Lunar.Day l1 = Lunar.fromSolar(viewY, viewM, 1), l2 = Lunar.fromSolar(viewY, viewM, last);
        head.subtitle = l1 != null && l2 != null
            ? (l1.yearGanzhi.equals(l2.yearGanzhi) ? l1.yearGanzhi + l1.zodiac + "年" : l1.yearGanzhi + l1.zodiac + " / " + l2.yearGanzhi + l2.zodiac + "年")
              + " " + l1.monthName + (!l1.monthName.equals(l2.monthName) ? " – " + l2.monthName : "")
            : "";
        head.setToday(!(viewY == now.y && viewM == now.m));
        head.fitSub();
        head.invalidate();
        grid.invalidate();
        if (dir != 0) {
            grid.animate().cancel();
            grid.setTranslationX(dir * 1.6f * rem);
            grid.setAlpha(0);
            grid.animate().translationX(0).alpha(1).setDuration(450).setInterpolator(OUT::at).withLayer().start();
        }
    }

    // ---------- 详情 ----------
    private void renderInfo() {
        Lunar.Ymd s = sel;
        Lunar.Day l = Lunar.fromSolar(s.y, s.m, s.d);
        Info v = info;
        v.sDay = String.valueOf(s.d);
        v.sDate = s.y + "年" + s.m + "月" + s.d + "日";
        v.sWeek = "星期" + WEEK.charAt(Lunar.weekday(s.y, s.m, s.d));
        v.sLunar = l != null ? "农历" + l.monthName + l.dayName : "";
        v.sGz = l != null ? l.yearGanzhi + "年 " + Lunar.monthGanzhi(s.y, s.m, s.d) + "月 " + Lunar.dayGanzhi(s.y, s.m, s.d) + "日 属" + l.zodiac : "";
        v.tags.clear(); v.tagKind.clear();
        for (String f : Lunar.festivals(s.y, s.m, s.d)) { v.tags.add(f); v.tagKind.add(0); }
        String term = Lunar.term(s.y, s.m, s.d);
        if (!term.isEmpty()) { v.tags.add(term); v.tagKind.add(1); }
        Holidays.Mark hm = Holidays.at(s.y, s.m, s.d);
        if (hm != null) { v.tags.add(hm.off ? hm.name + "假期" : "调休上班"); v.tagKind.add(hm.off ? 2 : 3); }
        int t = Lunar.epochDay(s.y, s.m, s.d), diff = t - Lunar.epochDay(now.y, now.m, now.d);
        SpannableStringBuilder from = new SpannableStringBuilder();
        if (diff == 0) big(from, "今天");
        else { big(from, String.valueOf(Math.abs(diff))); from.append(diff > 0 ? " 天后" : " 天前"); }
        v.rFrom = from;
        Lunar.Term next = Lunar.nextTerm(s.y, s.m, s.d);
        SpannableStringBuilder tm = new SpannableStringBuilder();
        if (next != null) {
            tm.append(next.days == 0 ? "今天" : "下个节气").append(' ');
            bold(tm, next.name);
            tm.append(' ').append(String.valueOf(next.m)).append("月").append(String.valueOf(next.d)).append("日");
            if (next.days != 0) tm.append(" · ").append(String.valueOf(next.days)).append(" 天后");
        }
        v.rTerm = tm;
        Holidays.Next hol = Holidays.next(s.y, s.m, s.d);
        SpannableStringBuilder hs = new SpannableStringBuilder();
        if (hol != null && hol.now) {
            hs.append("正在放假 ");
            bold(hs, hol.name);
            hs.append(" 第 ").append(String.valueOf(hol.day)).append(" 天 · 共 ").append(String.valueOf(hol.total)).append(" 天");
        } else if (hol != null) {
            hs.append("下个假期 ");
            bold(hs, hol.name);
            hs.append(' ').append(String.valueOf(hol.date.m)).append("月").append(String.valueOf(hol.date.d)).append("日 · ").append(hol.days == 0 ? "今天" : hol.days + " 天后");
            if (hol.estimate) {
                int a = hs.length();
                hs.append("（放假安排未公布）");
                hs.setSpan(new Small(), a, hs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else hs.append(" · 放 ").append(String.valueOf(hol.total)).append(" 天");
        }
        v.rHol = hs;
        int yLen = Lunar.isLeapYear(s.y) ? 366 : 365, passed = t - Lunar.epochDay(s.y, 1, 1) + 1;
        v.sYear = s.y + " 年第 " + passed + " 天，还剩 " + (yLen - passed) + " 天";
        v.setBar(passed / (float) yLen);
        v.dirty = true;
        if (portrait) root.relayout(); // 竖向时详情的高度跟着内容变
        v.invalidate();
    }
    private void bold(SpannableStringBuilder b, String s) {
        int a = b.length();
        b.append(s);
        b.setSpan(new TypefaceSpan(Fonts.sysBold), a, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new Ink(TEXT), a, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    private void big(SpannableStringBuilder b, String s) {
        int a = b.length();
        bold(b, s);
        b.setSpan(new Big(), a, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void go(int delta) {
        int m = viewM + delta, y = viewY;
        if (m < 1) { m = 12; y--; } else if (m > 12) { m = 1; y++; }
        if (y < Lunar.MIN_YEAR || y > Lunar.MAX_YEAR) return;
        viewY = y; viewM = m;
        renderGrid(delta > 0 ? 1 : -1);
    }
    private void backToday() {
        now = Lunar.today();
        int dir = Lunar.epochDay(viewY, viewM, 1) > Lunar.epochDay(now.y, now.m, 1) ? -1 : 1;
        viewY = now.y; viewM = now.m;
        sel = now;
        renderGrid(dir);
        renderInfo();
    }
    /** 点了第 i 格 */
    private void pick(int i) {
        int y = grid.cy[i], m = grid.cm[i], d = grid.cd[i];
        if (y < Lunar.MIN_YEAR || y > Lunar.MAX_YEAR) return; // 超出农历数据范围（1900–2100）
        sel = new Lunar.Ymd(y, m, d);
        if (m != viewM || y != viewY) {
            int dir = Lunar.epochDay(y, m, 1) > Lunar.epochDay(viewY, viewM, 1) ? 1 : -1;
            viewY = y; viewM = m;
            renderGrid(dir);
        } else grid.invalidate();
        renderInfo();
        Pages.PageEvent e = new Pages.PageEvent("calendar", "pick");
        dayInfo(sel, e);
        api.onEvent(e);
    }
    /** 告诉宠物这一天：几月几日、农历、节日 / 节气 / 假期、是不是今天 */
    private void dayInfo(Lunar.Ymd s, Pages.PageEvent e) {
        Lunar.Day l = Lunar.fromSolar(s.y, s.m, s.d);
        List<String> f = Lunar.festivals(s.y, s.m, s.d);
        Holidays.Mark hm = Holidays.at(s.y, s.m, s.d);
        String term = Lunar.term(s.y, s.m, s.d);
        e.dayText = s.m + "月" + s.d + "日";
        e.lunar = l != null ? "农历" + l.monthName + l.dayName : "";
        e.fest = !f.isEmpty() ? f.get(0) : !term.isEmpty() ? term : hm != null && hm.off ? hm.name + "假期" : "";
        e.today = same(now, s.y, s.m, s.d);
    }
    private void swipe(boolean left) {
        Pages.PageEvent e = new Pages.PageEvent("calendar", "swipe");
        e.dir = left ? "left" : "right";
        api.onEvent(e);
    }

    /** 每秒：时钟、跨日刷新（今天的标记移到新的一天，原本在看当月就跟着翻月），整分钟重新定深浅色 */
    private void tick() {
        Calendar c = Calendar.getInstance();
        String s = pad(c.get(Calendar.HOUR_OF_DAY)) + ":" + pad(c.get(Calendar.MINUTE));
        if (!s.equals(clockText)) { clockText = s; head.fitSub(); head.invalidate(); }
        Lunar.Ymd t = Lunar.today();
        if (t.d != now.d || t.m != now.m || t.y != now.y) {
            boolean wasToday = same(sel, now.y, now.m, now.d), wasViewing = viewY == now.y && viewM == now.m;
            now = t;
            if (wasToday) sel = t;
            if (wasViewing && (viewY != t.y || viewM != t.m)) { viewY = t.y; viewM = t.m; renderGrid(1); } else renderGrid(0);
            renderInfo();
        }
        if (c.get(Calendar.SECOND) < 2) theme();
        h.postDelayed(tick, 1000 - System.currentTimeMillis() % 1000 + 10);
    }

    // ================= 视图 =================
    /** 放两张卡片和月历格子；触摸都在这里分：按钮、格子的点按，月历上滑动切月，别处左右滑交给宠物页 */
    private final class Root extends ViewGroup {
        private final float slop;
        private float sx, sy;
        private long st;
        private boolean down, onGrid, tapOk;
        private int target = -1;

        Root(Context c) {
            super(c);
            setClipChildren(false);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }
        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs)); }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { lay(r - l, b - t); }
        void relayout() { if (getWidth() > 0) lay(getWidth(), getHeight()); }

        /** 横向：月历 62%（剩下的全给它）+ 详情 34%；竖向：详情按内容高度放在下面，月历占剩下的 */
        private void lay(int w, int hgt) {
            float r0 = rem;
            rem = api.rem();
            boolean p = w < hgt;
            if (p != portrait || r0 != rem) info.dirty = true;
            portrait = p;
            narrow = app.u.W / Math.max(1f, app.u.H) <= .5f;
            float pad = (p ? .14f : .26f) * rem;
            if (!p) {
                float avail = w - 2 * pad, iw = .34f * avail, cw = avail - iw - .24f * rem;
                place(head, pad, pad, cw, hgt - 2 * pad);
                place(info, pad + cw + .24f * rem, pad, iw, hgt - 2 * pad);
            } else {
                float iw = w - 2 * pad, ih = info.need(Math.round(iw)), top = hgt - pad - ih;
                place(info, pad, top, iw, ih);
                place(head, pad, pad, iw, top - .08f * rem - pad);
            }
            head.fit();
            place(grid, head.getLeft() + head.padL, head.getTop() + head.gridTop, head.getWidth() - head.padL - head.padR, head.getHeight() - head.gridTop - head.padB);
            invalidateAll();
        }
        private void place(View v, float x, float y, float w, float h) {
            int X = Math.round(x), Y = Math.round(y), W = Math.max(0, Math.round(w)), H = Math.max(0, Math.round(h));
            v.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(H, MeasureSpec.EXACTLY));
            v.layout(X, Y, X + W, Y + H);
        }

        /** 点到了什么：今 / ‹ / › 按钮，或者第几格（0～41），都没有 -1 */
        private int hit(float x, float y) {
            float hx = x - head.getLeft(), hy = y - head.getTop();
            if (head.todayOn && head.inBtn(head.todayX, hx, hy)) return T_TODAY;
            if (head.inBtn(head.prevX, hx, hy)) return T_PREV;
            if (head.inBtn(head.nextX, hx, hy)) return T_NEXT;
            float gx = x - grid.getLeft(), gy = y - grid.getTop();
            if (gx < 0 || gy < 0 || gx >= grid.getWidth() || gy >= grid.getHeight()) return -1;
            int col = Math.min(6, (int) (gx / (grid.getWidth() / 7f))), row = Math.min(5, (int) (gy / (grid.getHeight() / 6f)));
            return row * 7 + col;
        }
        private void press(int t) {
            int hp = t >= T_TODAY ? t : -1, gp = t >= 0 && t < 42 ? t : -1;
            if (head.pressed != hp) { head.pressed = hp; head.invalidate(); }
            if (grid.pressed != gp) { grid.pressed = gp; grid.invalidate(); }
        }
        private void click(int t) {
            if (t == T_TODAY) backToday();
            else if (t == T_PREV) go(-1);
            else if (t == T_NEXT) go(1);
            else if (t >= 0 && t < 42) pick(t);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = x; sy = y; st = SystemClock.uptimeMillis();
                    down = true; tapOk = true;
                    onGrid = x >= grid.getLeft() && x < grid.getRight() && y >= grid.getTop() && y < grid.getBottom();
                    target = hit(x, y);
                    press(target);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (tapOk && Math.hypot(x - sx, y - sy) > slop) { tapOk = false; press(-1); }
                    return true;
                case MotionEvent.ACTION_UP: {
                    if (!down) return true;
                    down = false;
                    press(-1);
                    float dx = x - sx, dy = y - sy, min = 60 * app.u.dens;
                    long dt = SystemClock.uptimeMillis() - st;
                    if (dt <= 1500 && Math.max(Math.abs(dx), Math.abs(dy)) >= min) {
                        // 月历上左右、上下滑都切月份；月历以外的地方左右滑交给宠物页切页
                        if (Math.abs(dx) > Math.abs(dy)) { if (onGrid) go(dx < 0 ? 1 : -1); else swipe(dx < 0); }
                        else if (onGrid) go(dy < 0 ? 1 : -1);
                    } else if (tapOk && target >= 0 && hit(x, y) == target) click(target);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    down = false;
                    press(-1);
                    return true;
            }
            return true;
        }
    }

    /** 月历卡片：底色、标题（年月 + 干支生肖、农历月）、时钟、今 / ‹ / › 按钮、星期行；格子是单独的 Grid */
    private final class Head extends View {
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), title = new Paint(Paint.ANTI_ALIAS_FLAG), sub = new Paint(Paint.ANTI_ALIAS_FLAG),
            clock = new Paint(Paint.ANTI_ALIAS_FLAG), btn = new Paint(Paint.ANTI_ALIAS_FLAG), arrow = new Paint(Paint.ANTI_ALIAS_FLAG),
            todayT = new Paint(Paint.ANTI_ALIAS_FLAG), week = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint subM = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final Path left = new Path(), right = new Path();
        String titleS = "", subtitle = "", subShown = "";
        float padL, padR, padT, padB, headH, bw, ml, btnCy, gridTop, weekTop, titleBase, subBase, clockX;
        float prevX, nextX, todayX;
        int pressed = -1;
        boolean todayOn;
        final Tw todayA = new Tw(0), todayS = new Tw(0);
        final Frames.Client anim = t -> { invalidate(); return todayA.moving(t) || todayS.moving(t); };

        Head(Context c) {
            super(c);
            title.setTypeface(Fonts.sysBold);
            title.setLetterSpacing(.02f);
            sub.setTypeface(Fonts.sys);
            subM.setTypeface(Fonts.sys);
            clock.setTypeface(light);
            clock.setFontFeatureSettings("tnum");
            arrow.setStyle(Paint.Style.STROKE);
            arrow.setStrokeCap(Paint.Cap.ROUND);
            arrow.setStrokeJoin(Paint.Join.ROUND);
            arrow.setStrokeWidth(2.6f);
            todayT.setTypeface(Fonts.sys);
            todayT.setColor(0xffffffff);
            todayT.setTextAlign(Paint.Align.CENTER);
            week.setTypeface(Fonts.sys);
            week.setTextAlign(Paint.Align.CENTER);
            // 箭头（24 × 24 的 SVG 路径）
            left.moveTo(14.5f, 5.5f); left.lineTo(8, 12); left.lineTo(14.5f, 18.5f);
            right.moveTo(9.5f, 5.5f); right.lineTo(16, 12); right.lineTo(9.5f, 18.5f);
        }

        /** 今 按钮：出现时淡入 0.3 秒、从 0.6 倍放大 0.15 秒；藏起来是一下子没了（宽度变 0） */
        void setToday(boolean on) {
            if (on == todayOn) return;
            todayOn = on;
            if (on) {
                long t = SystemClock.uptimeMillis();
                todayA.set(0); todayS.set(0);
                todayA.to(1, 300, Ease.EASE, t);
                todayS.to(1, 150, Ease.EASE, t);
                app.frames.add(anim);
            }
        }
        boolean inBtn(float bx, float x, float y) { return x >= bx && x <= bx + bw && y >= btnCy - bw / 2 && y <= btnCy + bw / 2; }

        /** 按当前大小、横竖算各部分的位置和字号 */
        void fit() {
            float R = rem, w = getWidth();
            boolean p = portrait;
            padL = padR = (p ? .12f : .24f) * R;
            padT = (p ? .12f : .2f) * R;
            padB = (p ? .06f : .16f) * R;
            headH = (p ? .62f : .8f) * R;
            title.setTextSize((p ? .4f : narrow ? .28f : .44f) * R);
            sub.setTextSize((p ? .19f : narrow ? .13f : .17f) * R);
            subM.setTextSize(sub.getTextSize());
            clock.setTextSize(.3f * R);
            bw = (p ? .52f : narrow ? .46f : .62f) * R;
            ml = (narrow ? .06f : .1f) * R;
            todayT.setTextSize((narrow ? .18f : .22f) * R);
            week.setTextSize((p ? .2f : .18f) * R);
            btnCy = padT + headH / 2;
            nextX = w - padR - bw;
            prevX = nextX - ml - bw;
            todayX = prevX - ml - bw;
            float tl = Fonts.lineH(title), sl = Fonts.lineH(sub), bh = tl + .02f * R + sl, top = padT + (headH - bh) / 2;
            titleBase = top + Fonts.base(title);
            subBase = top + tl + .02f * R + Fonts.base(sub);
            weekTop = padT + headH + (p ? .04f : .1f) * R;
            gridTop = weekTop + Fonts.lineH(week) + (p ? 0 : .04f * R);
            fitSub();
        }
        /** 标题栏左边能放多宽（右边是时钟和按钮），副标题放不下用省略号 */
        void fitSub() {
            float btnsL = todayOn ? todayX : prevX - ml;
            if (!portrait) { clockX = btnsL - .16f * rem - clock.measureText(clockText); btnsL = clockX; }
            float avail = btnsL - padL;
            subShown = avail <= 0 || subtitle.isEmpty() ? "" : TextUtils.ellipsize(subtitle, subM, avail, TextUtils.TruncateAt.END).toString();
        }

        @Override protected void onDraw(Canvas c) {
            long t = SystemClock.uptimeMillis();
            float R = rem, w = getWidth(), hh = getHeight();
            r.set(0, 0, w, hh);
            bg.setColor(col(PANEL));
            Fonts.shadow(bg, .04f * R, .2f * R, 0x0f141e3c);
            c.drawRoundRect(r, .28f * R, .28f * R, bg);
            bg.clearShadowLayer();
            title.setColor(col(TEXT));
            c.drawText(titleS, padL, titleBase, title);
            sub.setColor(col(SUB));
            c.drawText(subShown, padL, subBase, sub);
            if (!portrait) {
                clock.setColor(col(SUB));
                c.drawText(clockText, clockX, btnCy - (clock.descent() + clock.ascent()) / 2, clock);
            }
            drawArrow(c, prevX, left, pressed == T_PREV);
            drawArrow(c, nextX, right, pressed == T_NEXT);
            if (todayOn) {
                float a = todayA.get(t), s = (.6f + .4f * todayS.get(t)) * (pressed == T_TODAY ? .9f : 1), cx = todayX + bw / 2;
                btn.setColor(Ease.alpha(col(ACCENT), a));
                c.drawCircle(cx, btnCy, bw / 2 * s, btn);
                c.save();
                c.scale(s, s, cx, btnCy);
                todayT.setAlpha(Math.round(255 * a));
                c.drawText("今", cx, btnCy - (todayT.descent() + todayT.ascent()) / 2, todayT);
                c.restore();
            }
            float cw = (w - padL - padR) / 7, wb = weekTop + Fonts.base(week);
            int subC = col(SUB), weC = col(WE);
            for (int i = 0; i < 7; i++) {
                week.setColor(i >= 5 ? weC : subC);
                c.drawText(HEAD_WEEK, i, i + 1, padL + (i + .5f) * cw, wb, week);
            }
        }
        private void drawArrow(Canvas c, float x, Path p, boolean down) {
            float cx = x + bw / 2, s = down ? .9f : 1;
            btn.setColor(col(BG));
            c.drawCircle(cx, btnCy, bw / 2 * s, btn);
            float k = .46f * bw / 24 * s;
            c.save();
            c.translate(cx - 12 * k, btnCy - 12 * k);
            c.scale(k, k);
            arrow.setColor(col(TEXT));
            c.drawPath(p, arrow);
            c.restore();
        }
    }

    /** 月历格子（6 行 × 7 列，一个 View 画完）：日期圆、农历 / 节日 / 节气小字、休 / 班角标；今天红圈、选中蓝圈 */
    private final class Grid extends View {
        final int[] cy = new int[42], cm = new int[42], cd = new int[42];
        final String[] raw = new String[42], shown = new String[42];
        final byte[] kind = new byte[42], hol = new byte[42]; // kind：0 普通、1 节日、2 节气、3 农历月初；hol：1 休、2 班
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), num = new Paint(Paint.ANTI_ALIAS_FLAG),
            small = new Paint(Paint.ANTI_ALIAS_FLAG), em = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint measure = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        int pressed = -1;
        private int labeledW = -1;
        private float labeledSize;

        Grid(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            num.setTypeface(Fonts.sysMedium);
            num.setFontFeatureSettings("tnum");
            num.setTextAlign(Paint.Align.CENTER);
            small.setTypeface(Fonts.sys);
            small.setTextAlign(Paint.Align.CENTER);
            measure.setTypeface(Fonts.sys);
            em.setTypeface(Fonts.sys);
            em.setColor(0xffffffff);
            for (int i = 0; i < 42; i++) { raw[i] = ""; shown[i] = ""; }
        }
        private float smallSize() { return (portrait ? .18f : narrow ? .12f : .15f) * rem; }
        /** 小字按格子宽度加省略号（换月、改大小时算一次，画的时候不再分配） */
        void relabel() {
            float cw = getWidth() / 7f;
            labeledW = getWidth();
            labeledSize = smallSize();
            measure.setTextSize(labeledSize);
            for (int i = 0; i < 42; i++) shown[i] = cw <= 0 ? raw[i] : TextUtils.ellipsize(raw[i], measure, cw, TextUtils.TruncateAt.END).toString();
        }

        @Override protected void onDraw(Canvas c) {
            if (labeledW != getWidth() || labeledSize != smallSize()) relabel();
            float R = rem, cw = getWidth() / 7f, ch = getHeight() / 6f;
            boolean p = portrait;
            float padTop = (p ? .02f : .1f) * R, rad = (p ? .12f : .18f) * R, bd = (p || narrow ? .42f : .58f) * R;
            float smT = (p ? 0 : .02f) * R, emSize = (p ? .15f : .13f) * R, emTop = (p ? .01f : .06f) * R, emRight = p ? .01f : .08f;
            num.setTextSize((p ? .28f : narrow ? .22f : .32f) * R);
            small.setTextSize(smallSize());
            em.setTextSize(emSize);
            float numBase = Fonts.baseIn(num, bd), smallBase = p ? Fonts.baseIn(small, 1.1f * small.getTextSize()) : Fonts.base(small);
            float emBase = Fonts.baseIn(em, emSize), otherA = Ease.lerp(.32f, .3f, dkv);
            int text = col(TEXT), sub = col(SUB), accent = col(ACCENT), blue = col(BLUE), green = col(GREEN), we = col(WE), line = col(LINE);
            for (int i = 0; i < 42; i++) {
                float x = (i % 7) * cw, y = (i / 7) * ch, a = cm[i] != viewM ? otherA : 1;
                if (pressed == i || hol[i] == 1) {
                    fill.setColor(pressed == i ? Ease.alpha(line, a) : Ease.alpha(OFF_BG, a));
                    r.set(x, y, x + cw, y + ch);
                    c.drawRoundRect(r, rad, rad, fill);
                }
                float cx = x + cw / 2, top = y + padTop, ccy = top + bd / 2;
                boolean today = same(now, cy[i], cm[i], cd[i]);
                if (today) {
                    fill.setColor(Ease.alpha(accent, a));
                    Fonts.shadow(fill, .04f * R, .12f * R, Ease.rgba(255, 90, 79, .4f * a));
                    c.drawCircle(cx, ccy, bd / 2, fill);
                    fill.clearShadowLayer();
                } else if (same(sel, cy[i], cm[i], cd[i])) {
                    ring.setStrokeWidth(.03f * R);
                    ring.setColor(Ease.alpha(blue, a));
                    c.drawCircle(cx, ccy, bd / 2 - .015f * R, ring);
                }
                num.setColor(Ease.alpha(today ? 0xffffffff : i % 7 >= 5 ? we : text, a));
                c.drawText(NUM[cd[i]], cx, top + numBase, num);
                if (!shown[i].isEmpty()) {
                    int k = kind[i];
                    small.setColor(Ease.alpha(k == 1 ? accent : k == 2 ? green : k == 3 ? blue : sub, a));
                    c.drawText(shown[i], cx, top + bd + smT + smallBase, small);
                }
                if (hol[i] != 0) { // 休 / 班 角标（画在最上面，同网页版的绝对定位）
                    String s = hol[i] == 1 ? "休" : "班";
                    float tw = em.measureText(s), bx1 = x + cw * (1 - emRight), bx0 = bx1 - tw - .08f * R, by0 = y + emTop;
                    fill.setColor(Ease.alpha(hol[i] == 1 ? accent : WORK, a));
                    r.set(bx0, by0, bx1, by0 + emSize + .06f * R);
                    c.drawRoundRect(r, .05f * R, .05f * R, fill);
                    em.setAlpha(Math.round(255 * a));
                    c.drawText(s, bx0 + .04f * R, by0 + .03f * R + emBase, em);
                }
            }
        }
    }
    /** 详情卡片：大号日子 + 公历星期、农历、干支生肖、标签（节日 / 节气 / 假期 / 调休）、距今天数、下个节气、下个假期、年度进度 */
    private final class Info extends View {
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), day = new Paint(Paint.ANTI_ALIAS_FLAG), date = new Paint(Paint.ANTI_ALIAS_FLAG),
            lunar = new Paint(Paint.ANTI_ALIAS_FLAG), gz = new Paint(Paint.ANTI_ALIAS_FLAG), tag = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), year = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint rowP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final Matrix m = new Matrix();
        String sDay = "", sDate = "", sWeek = "", sLunar = "", sGz = "", sYear = "";
        final ArrayList<String> tags = new ArrayList<>();
        final ArrayList<Integer> tagKind = new ArrayList<>(); // 0 节日、1 节气、2 放假、3 调休上班
        CharSequence rFrom = "", rTerm = "", rHol = "";
        boolean dirty = true;
        // 排版结果（像素）
        private int builtW = -1;
        private boolean builtP;
        private float padX, padB, dayBase, dateX, date1, date2, lunarBase, gzBase, tagH, tagPadX, tagPadY, rowsEnd, contentH;
        private float[] tagX = new float[4], tagY = new float[4], tagW = new float[4];
        private final StaticLayout[] rows = new StaticLayout[3];
        private final float[] rowY = new float[3], rowTop = new float[3];
        private final boolean[] rowOn = new boolean[3];
        // 年度进度条：宽度 0.8 秒过渡（第一次直接定）
        private final Tw barK = new Tw(0);
        private boolean barInit;
        private LinearGradient barShader;
        private int barAccent;
        final Frames.Client anim = t -> { invalidate(); return barK.moving(t); };

        Info(Context c) {
            super(c);
            day.setTypeface(Fonts.sysBold);
            day.setFontFeatureSettings("tnum");
            date.setTypeface(Fonts.sys);
            lunar.setTypeface(Fonts.sysBold);
            gz.setTypeface(Fonts.sys);
            tag.setTypeface(Fonts.sys);
            year.setTypeface(Fonts.sys);
            rowP.setTypeface(Fonts.sys);
        }

        void setBar(float v) {
            if (!barInit) { barInit = true; barK.set(v); return; }
            barK.to(v, 800, OUT, SystemClock.uptimeMillis());
            app.frames.add(anim);
        }
        /** 竖向时详情按内容高度：宽 w 时要多高 */
        float need(int w) { ensure(w); return contentH; }
        private void ensure(int w) { if (dirty || w != builtW || builtP != portrait) build(w); }

        private void build(int w) {
            float R = rem;
            boolean p = portrait;
            builtW = w; builtP = p; dirty = false;
            padX = (p ? .2f : .3f) * R;
            padB = (p ? .06f : .26f) * R;
            float iw = Math.max(1, w - 2 * padX);
            day.setTextSize((p ? .6f : 1.1f) * R);
            date.setTextSize((p ? .2f : .19f) * R);
            lunar.setTextSize((p ? .32f : .42f) * R);
            gz.setTextSize(.19f * R);
            tag.setTextSize((p ? .19f : .17f) * R);
            year.setTextSize(.17f * R);
            rowP.setTextSize((p ? .2f : .19f) * R);
            // 大号日子和公历 / 星期两行：横向底边对齐，竖向居中对齐
            float dayH = .95f * day.getTextSize(), dateLH = (p ? 1.35f : 1.5f) * date.getTextSize(), dateH = 2 * dateLH + (p ? 0 : .06f * R);
            float topH = Math.max(dayH, dateH), y = (p ? .08f : .3f) * R;
            float dayTop = p ? y + (topH - dayH) / 2 : y + topH - dayH, dateTop = p ? y + (topH - dateH) / 2 : y + topH - dateH;
            dayBase = dayTop + Fonts.baseIn(day, dayH);
            date1 = dateTop + Fonts.baseIn(date, dateLH);
            date2 = date1 + dateLH;
            dateX = padX + day.measureText(sDay) + .16f * R;
            y += topH + (p ? .02f : .22f) * R;
            lunarBase = y + Fonts.base(lunar);
            if (!sLunar.isEmpty()) y += Fonts.lineH(lunar);
            if (!p) {
                y += .06f * R;
                gzBase = y + Fonts.base(gz);
                if (!sGz.isEmpty()) y += Fonts.lineH(gz);
            }
            int n = tags.size();
            if (tagX.length < n) { tagX = new float[n]; tagY = new float[n]; tagW = new float[n]; }
            if (!(p && n == 0)) { // 竖向没有标签时整行不占地方
                y += (p ? .06f : .14f) * R;
                tagPadX = (p ? .12f : .14f) * R;
                tagPadY = (p ? .03f : .05f) * R;
                tagH = Fonts.lineH(tag) + 2 * tagPadY;
                float mb = (p ? .04f : .08f) * R, mr = .08f * R, x = 0, ty = 0;
                for (int i = 0; i < n; i++) {
                    float tw = tag.measureText(tags.get(i)) + 2 * tagPadX;
                    if (x > 0 && x + tw > iw) { x = 0; ty += tagH + mb; }
                    tagX[i] = padX + x; tagY[i] = y + ty; tagW[i] = tw;
                    x += tw + mr;
                }
                float th = n == 0 ? 0 : ty + tagH + mb;
                y += p ? th : Math.max(.4f * R, th);
            }
            y += (p ? .02f : .06f) * R;
            float rp = (p ? .03f : .12f) * R, border = app.u.dens;
            CharSequence[] src = { rFrom, rTerm, rHol };
            for (int k = 0; k < 3; k++) {
                rowOn[k] = !(p && k == 0); // 竖向不显示距今天数
                rows[k] = null;
                if (!rowOn[k]) continue;
                rowY[k] = y;
                y += border + rp;
                rowTop[k] = y;
                // 行高 1.5 倍；距今天数那行的大号数字 .3rem
                rows[k] = layout(src[k], Math.round(iw), Math.round(1.5f * (k == 0 ? .3f * R : rowP.getTextSize())));
                if (rows[k] != null) y += rows[k].getHeight();
                y += rp;
            }
            rowsEnd = y;
            contentH = y + padB;
        }
        private StaticLayout layout(CharSequence s, int w, int lh) {
            if (s == null || s.length() == 0) return null;
            SpannableStringBuilder b = new SpannableStringBuilder(s);
            b.setSpan(new Lh(lh), 0, b.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            return StaticLayout.Builder.obtain(b, 0, b.length(), rowP, w).setIncludePad(false).setLineSpacing(0, 1).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        }

        @Override protected void onDraw(Canvas c) {
            ensure(getWidth());
            long t = SystemClock.uptimeMillis();
            float R = rem, w = getWidth(), hh = getHeight();
            boolean p = portrait;
            r.set(0, 0, w, hh);
            bg.setColor(col(PANEL));
            Fonts.shadow(bg, .04f * R, .2f * R, 0x0f141e3c);
            c.drawRoundRect(r, .28f * R, .28f * R, bg);
            bg.clearShadowLayer();
            int text = col(TEXT), sub = col(SUB), accent = col(ACCENT);
            day.setColor(accent);
            c.drawText(sDay, padX, dayBase, day);
            date.setColor(sub);
            c.drawText(sDate, dateX, date1, date);
            c.drawText(sWeek, dateX, date2, date);
            lunar.setColor(text);
            c.drawText(sLunar, padX, lunarBase, lunar);
            if (!p) { gz.setColor(sub); c.drawText(sGz, padX, gzBase, gz); }
            float tb = tagPadY + Fonts.base(tag);
            for (int i = 0; i < tags.size(); i++) {
                int k = tagKind.get(i);
                fill.setColor(k == 0 ? FEST_BG : k == 1 ? TERM_BG : k == 2 ? accent : WORK);
                r.set(tagX[i], tagY[i], tagX[i] + tagW[i], tagY[i] + tagH);
                c.drawRoundRect(r, .3f * R, .3f * R, fill);
                tag.setColor(k == 0 ? accent : k == 1 ? col(GREEN) : 0xffffffff);
                c.drawText(tags.get(i), tagX[i] + tagPadX, tagY[i] + tb, tag);
            }
            float border = app.u.dens;
            fill.setColor(col(LINE));
            rowP.setColor(sub);
            for (int k = 0; k < 3; k++) {
                if (!rowOn[k]) continue;
                c.drawRect(padX, rowY[k], w - padX, rowY[k] + border, fill);
                if (rows[k] == null) continue;
                c.save();
                c.translate(padX, rowTop[k]);
                rows[k].draw(c);
                c.restore();
            }
            if (!p) { // 年度进度贴在卡片底部（放不下就接在下面）
                float yl = Fonts.lineH(year), top = Math.max(rowsEnd, hh - padB - (.14f * R + yl + .16f * R));
                year.setColor(sub);
                c.drawText(sYear, padX, top + .14f * R + Fonts.base(year), year);
                float bt = top + .14f * R + yl + .08f * R, bwid = w - 2 * padX, rad = .04f * R;
                r.set(padX, bt, padX + bwid, bt + .08f * R);
                fill.setColor(col(LINE));
                c.drawRoundRect(r, rad, rad, fill);
                float fw = bwid * Ease.clamp(barK.get(t), 0, 1);
                if (fw > 0) {
                    if (barShader == null || barAccent != accent) { barAccent = accent; barShader = new LinearGradient(0, 0, 1, 0, 0xffffb057, accent, Shader.TileMode.CLAMP); }
                    m.setScale(fw, 1);
                    m.postTranslate(padX, 0);
                    barShader.setLocalMatrix(m);
                    bar.setShader(barShader);
                    r.set(padX, bt, padX + fw, bt + .08f * R);
                    float fr = Math.min(rad, fw / 2);
                    c.drawRoundRect(r, fr, fr, bar);
                }
            }
        }
    }

    /** 颜色跟着深浅色走的字（画的时候取当前颜色，过渡时不用重新排版） */
    private final class Ink extends CharacterStyle implements UpdateAppearance {
        private final int[] pair;
        Ink(int[] pair) { this.pair = pair; }
        @Override public void updateDrawState(TextPaint tp) { tp.setColor(col(pair)); }
    }
    /** 距今天数的大号数字（.3rem） */
    private final class Big extends MetricAffectingSpan {
        @Override public void updateMeasureState(TextPaint tp) { tp.setTextSize(.3f * rem); }
        @Override public void updateDrawState(TextPaint tp) { tp.setTextSize(.3f * rem); }
    }
    /** "（放假安排未公布）"的小字 */
    private final class Small extends MetricAffectingSpan {
        @Override public void updateMeasureState(TextPaint tp) { tp.setTextSize((portrait ? .16f : .15f) * rem); }
        @Override public void updateDrawState(TextPaint tp) { tp.setTextSize((portrait ? .16f : .15f) * rem); }
    }
    /** 固定行高（同 CSS 的 line-height：多出来的上下平分） */
    private static final class Lh implements LineHeightSpan {
        private final int h;
        Lh(int h) { this.h = h; }
        @Override public void chooseHeight(CharSequence t, int s, int e, int v, int lh, Paint.FontMetricsInt fm) {
            int extra = h - (fm.descent - fm.ascent);
            fm.ascent -= extra / 2;
            fm.descent = fm.ascent + h;
            fm.top = fm.ascent;
            fm.bottom = fm.descent;
        }
    }
}
