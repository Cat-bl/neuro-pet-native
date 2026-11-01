package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * 倒数日页（同网页版 pages/countdown）：大卡片显示选中的日子（数字从 0 滚到目标天数），列表按"未来近→远、过去的纪念日在后"排，点选切换，可增删改。
 * 支持公历 / 农历、每年重复；未来"还有 N 天"，过去"已经 N 天"，当天"就是今天"。数据存 countdown-events（同网页版的 JSON）。
 * 横向：大卡片在左、列表在右；竖向：大卡片按内容高度在上，列表占剩下的。嵌在宠物页里，不播自己的进场动画（calm）
 */
final class CountdownPage implements Pages.Page {
    private static final String KEY = "countdown-events", WEEK = "日一二三四五六";
    private static final int[][] COLORS = {
        { 0xffff7a59, 0xffff4f6d }, { 0xff5b8cff, 0xff7b5bff }, { 0xff18b67a, 0xff0f9bb8 },
        { 0xffffb43c, 0xffff7a2f }, { 0xffe05bd8, 0xff8a5bff }, { 0xff3a4660, 0xff1d2433 }
    };
    private static final int[] EMPTY = { 0xff8e98ab, 0xff5b6478 };
    // 配色：{ 浅色, 深色 }
    private static final int[] PANEL = { 0xffffffff, 0xff161d2b }, BG = { 0xfff4f5f9, 0xff0d121d }, TEXT = { 0xff1c2230, 0xffe8ebf3 },
        SUB = { 0xff7a8293, 0xff8c95a8 }, LINE = { 0xffeceef3, 0xff232c3d };
    private static final String[] CHIPS = { "生日", "纪念日", "买车纪念日", "放假", "旅行", "发工资", "回家", "考试" };
    private static final Ease BEAT = Ease.IN_OUT;

    /** 一个日子（同网页版 list 里的一项） */
    static final class Ev {
        long id;
        String name = "";
        boolean lunar, repeat;
        int y, m, d, color;
        Ev copy() { Ev o = new Ev(); o.id = id; o.name = name; o.lunar = lunar; o.repeat = repeat; o.y = y; o.m = m; o.d = d; o.color = color; return o; }
    }

    private final Context ctx;
    private final Pages.Api api;
    private final App app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Root root;
    private final Hero hero;
    private final ListBox lbox;
    private Form form;
    private float rem;
    private boolean portrait;

    private final ArrayList<Ev> list = new ArrayList<>();
    private long selId;
    private Lunar.Ymd now;

    private final Tw dk = new Tw(0);
    private float dkv;
    private boolean themed;
    private final Frames.Client themeAnim = this::themeFrame;
    private final Runnable every = this::every;

    CountdownPage(Context c, Pages.Api api) {
        ctx = c;
        this.api = api;
        app = api.app();
        rem = api.rem();
        root = new Root(c);
        hero = new Hero(c);
        lbox = new ListBox(c);
        root.addView(hero);
        root.addView(lbox);
        now = Lunar.today();
        load();
        theme();
        renderAll(true);
        h.postDelayed(every, 30000);
        tell("open", null);
    }

    @Override public View view() { return root; }

    private boolean themeFrame(long t) { dkv = dk.get(t); lbox.invalidate(); lbox.items.invalidate(); return dk.moving(t); }
    @Override public void theme() {
        boolean d = api.dark();
        long t = SystemClock.uptimeMillis();
        if (!themed) { themed = true; dk.set(d ? 1 : 0); dkv = dk.get(t); lbox.invalidate(); lbox.items.invalidate(); return; }
        dk.to(d ? 1 : 0, 800, Ease.EASE, t);
        if (dk.moving(t)) app.frames.add(themeAnim);
    }

    @Override public void destroy() {
        h.removeCallbacksAndMessages(null);
        app.frames.remove(themeAnim);
        app.frames.remove(hero.count);
        app.frames.remove(hero.beat);
        if (form != null) form.shut();
    }

    private int col(int[] p) { return Ease.mix(p[0], p[1], dkv); }

    /** 每 30 秒：重新定深浅色；跨日了整页刷新 */
    private void every() {
        theme();
        Lunar.Ymd t = Lunar.today();
        if (t.d != now.d || t.m != now.m || t.y != now.y) { now = t; renderAll(true); }
        h.postDelayed(every, 30000);
    }

    // ---------- 数据（同网页版：读的时候校验，坏了回到默认的三个日子） ----------
    private void defaults() {
        list.clear();
        list.add(ev(1, "春节", true, true, 2026, 1, 1, 0));
        list.add(ev(2, "元旦", false, true, 2026, 1, 1, 1));
        list.add(ev(3, "中秋节", true, true, 2026, 8, 15, 3));
        selId = 1;
    }
    private static Ev ev(long id, String name, boolean lunar, boolean repeat, int y, int m, int d, int color) {
        Ev e = new Ev();
        e.id = id; e.name = name; e.lunar = lunar; e.repeat = repeat; e.y = y; e.m = m; e.d = d; e.color = color;
        return e;
    }
    private void load() {
        try {
            String s = Store.get(KEY);
            if (s != null && clean(new JSONObject(s))) return;
        } catch (Exception ignored) { }
        defaults();
    }
    /** 同 JS 的一元 +：没有这个键是 NaN，null 是 0 */
    private static double num(Object o) {
        if (o == null) return Double.NaN;
        if (o == JSONObject.NULL) return 0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.isEmpty()) return 0;
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }
    /** 同 JS 的真假 */
    private static boolean truthy(Object o) {
        if (o == null || o == JSONObject.NULL) return false;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) { double v = ((Number) o).doubleValue(); return v != 0 && !Double.isNaN(v); }
        if (o instanceof String) return !((String) o).isEmpty();
        return true;
    }
    private boolean clean(JSONObject raw) {
        JSONArray arr = raw.optJSONArray("list");
        if (arr == null) return false;
        list.clear();
        HashSet<Long> seen = new HashSet<>();
        long base = System.currentTimeMillis();
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            double m = num(e.opt("m")), d = num(e.opt("d")), y = num(e.opt("y"));
            if (!(m >= 1 && m <= 12 && d >= 1 && d <= 31)) continue;
            // 以前版本的默认示例，没改过就去掉
            if ("买车纪念日".equals(e.opt("name")) && y == 2025 && m == 6 && d == 1 && !truthy(e.opt("lunar")) && !truthy(e.opt("repeat"))) continue;
            double idv = num(e.opt("id"));
            long id = idv != 0 && !Double.isNaN(idv) ? (long) idv : base + i;
            while (seen.contains(id)) id++;
            seen.add(id);
            Object nm = e.opt("name");
            String name = truthy(nm) ? String.valueOf(nm) : "我的日子";
            double cv = num(e.opt("color"));
            list.add(ev(id, name.length() > 10 ? name.substring(0, 10) : name, truthy(e.opt("lunar")), truthy(e.opt("repeat")),
                y >= Lunar.MIN_YEAR && y <= Lunar.MAX_YEAR ? (int) Math.floor(y) : thisYear, (int) Math.floor(m), (int) Math.floor(d),
                cv >= 0 && cv < COLORS.length ? (int) Math.floor(cv) : 0));
        }
        double s = num(raw.opt("sel"));
        selId = s != 0 && !Double.isNaN(s) ? (long) s : 0;
        return true;
    }
    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Ev e : list) a.put(new JSONObject().put("id", e.id).put("name", e.name).put("lunar", e.lunar).put("repeat", e.repeat)
                .put("y", e.y).put("m", e.m).put("d", e.d).put("color", e.color));
            Store.set(KEY, new JSONObject().put("sel", selId).put("list", a).toString());
        } catch (Exception ignored) { }
    }
    private Ev byId(long id) { for (Ev e : list) if (e.id == id) return e; return null; }
    private Lunar.Countdown info(Ev e) { return Lunar.eventDays(e.lunar, e.repeat, e.y, e.m, e.d, now.y, now.m, now.d); }
    private static int[] colors(Ev e) { return e.color >= 0 && e.color < COLORS.length ? COLORS[e.color] : COLORS[0]; }
    private String dateText(Ev ev, Lunar.Countdown r) {
        if (r == null) return "日期无效";
        String s = r.y + "年" + r.m + "月" + r.d + "日 星期" + WEEK.charAt(Lunar.weekday(r.y, r.m, r.d));
        if (ev.lunar) { Lunar.Day l = Lunar.fromSolar(r.y, r.m, r.d); if (l != null) s += " · 农历" + l.monthName + l.dayName; }
        return s;
    }
    /** 未来的在前（近→远），过去的纪念日在后（近→远），日期无效的最后 */
    private List<Ev> sorted(List<Lunar.Countdown> outInfo) {
        final ArrayList<Ev> s = new ArrayList<>(list);
        final java.util.HashMap<Ev, Lunar.Countdown> r = new java.util.HashMap<>();
        for (Ev e : s) r.put(e, info(e));
        Collections.sort(s, (a, b) -> {
            Lunar.Countdown ra = r.get(a), rb = r.get(b);
            if (ra == null || rb == null) return ra == rb ? 0 : ra == null ? 1 : -1;
            boolean pa = ra.days < 0, pb = rb.days < 0;
            if (pa != pb) return pa ? 1 : -1;
            return pa ? Integer.compare(rb.days, ra.days) : Integer.compare(ra.days, rb.days);
        });
        if (outInfo != null) for (Ev e : s) outInfo.add(r.get(e));
        return s;
    }

    // ---------- 渲染 ----------
    private void renderAll(boolean animate) { renderHero(animate); renderList(); }

    private void renderHero(boolean animate) {
        Ev ev = byId(selId);
        if (ev == null) { List<Ev> s = sorted(null); ev = s.isEmpty() ? null : s.get(0); }
        Hero v = hero;
        if (ev == null) {
            v.empty = true;
            v.grad = EMPTY;
            v.editOn = false;
            v.sName = "还没有日子";
            v.sState = "点右边的 ＋ 添加一个";
            v.sDate = v.sSub = "";
            v.setToday(false);
            v.setNum(0, false);
            root.relayout();
            return;
        }
        selId = ev.id;
        Lunar.Countdown r = info(ev);
        v.empty = false;
        v.grad = colors(ev);
        v.editOn = true;
        v.sName = ev.name;
        v.sState = r == null ? "" : Lunar.TODAY.equals(r.state) ? "就是今天" : Lunar.FUTURE.equals(r.state) ? "距离" + ev.name + "还有" : ev.name + "已经";
        int n = r != null ? Math.abs(r.days) : 0;
        v.sDate = dateText(ev, r);
        v.sSub = r == null ? "" : Lunar.PAST.equals(r.state) ? "从 " + r.y + " 年 " + r.m + " 月 " + r.d + " 日算起" + (n >= 365 ? "，约 " + String.format(java.util.Locale.US, "%.1f", n / 365.25) + " 年" : "")
            : ev.repeat ? "每年" + (ev.lunar ? "农历" + Lunar.MONTH_NAMES[ev.m - 1] + "月" + Lunar.DAY_NAMES[ev.d - 1] : ev.m + "月" + ev.d + "日")
            : Lunar.TODAY.equals(r.state) ? "" : "约 " + (int) Math.ceil(n / 7.0) + " 周";
        v.setToday(r != null && Lunar.TODAY.equals(r.state));
        v.setNum(n, animate);
        root.relayout();
    }

    private void renderList() {
        ArrayList<Lunar.Countdown> rs = new ArrayList<>();
        List<Ev> s = sorted(rs);
        Items it = lbox.items;
        it.rows.clear();
        for (int i = 0; i < s.size(); i++) {
            Ev ev = s.get(i);
            Lunar.Countdown r = rs.get(i);
            Row w = new Row();
            w.id = ev.id;
            w.c = colors(ev);
            w.name = ev.name;
            w.rep = ev.repeat;
            w.date = r != null ? r.y + "." + r.m + "." + r.d + (ev.lunar ? " 农历" : "") : "日期无效";
            boolean today = r != null && Lunar.TODAY.equals(r.state);
            w.state = r == null ? "" : today ? "今天" : Lunar.FUTURE.equals(r.state) ? "还有" : "已经";
            w.days = today ? "★" : r != null ? String.valueOf(Math.abs(r.days)) : "-";
            w.tian = r != null && !today;
            it.rows.add(w);
        }
        it.rebuild();
        root.relayout();
    }

    /** 告诉宠物：选中的日子（时钟下面那行显示它）、刚做了什么，宠物来接话 */
    private void tell(String type, String name) {
        Ev ev = byId(selId);
        Lunar.Countdown r = ev != null ? info(ev) : null;
        Pages.PageEvent e = new Pages.PageEvent("countdown", type);
        e.name = name != null && !name.isEmpty() ? name : ev != null ? ev.name : "";
        if (ev != null) {
            Settings.Day d = new Settings.Day();
            d.name = ev.name; d.lunar = ev.lunar; d.repeat = ev.repeat; d.y = ev.y; d.m = ev.m; d.d = ev.d;
            e.ev = d;
        }
        e.evSet = true;
        e.state = r != null ? r.state : "";
        e.days = r != null ? Math.abs(r.days) : 0;
        api.onEvent(e);
    }
    private void select(long id) {
        if (id == selId) return;
        selId = id;
        save();
        lbox.items.invalidate();
        renderHero(true);
        tell("select", null);
    }

    // ================= 视图 =================
    /** 放大卡片、列表和编辑表单；左右滑（编辑表单开着时不算）交给宠物页切页 */
    private final class Root extends ViewGroup {
        private float sx, sy;
        private long st;
        private boolean sw;

        Root(Context c) { super(c); setClipChildren(false); }
        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs)); }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { lay(r - l, b - t); }
        void relayout() {
            hero.forceLayout(); lbox.forceLayout(); lbox.scroll.forceLayout(); lbox.items.forceLayout();
            if (getWidth() > 0) lay(getWidth(), getHeight());
        }

        /** 横向：大卡片（剩下的全给它）+ 列表 40%；竖向：大卡片按内容高度在上，列表占剩下的 */
        private void lay(int w, int hh) {
            rem = api.rem();
            portrait = w < hh;
            float R = rem, pad = (portrait ? .14f : .26f) * R;
            if (!portrait) {
                float avail = w - 2 * pad, lw = .4f * avail, hw = avail - lw - .24f * R;
                place(hero, pad, pad, hw, hh - 2 * pad);
                place(lbox, pad + hw + .24f * R, pad, lw, hh - 2 * pad);
            } else {
                float iw = w - 2 * pad, top = pad + hero.need(Math.round(iw)) + .14f * R;
                place(hero, pad, pad, iw, top - .14f * R - pad);
                place(lbox, pad, top, iw, hh - pad - top);
            }
            if (form != null) place(form, 0, 0, w, hh);
        }
        void place(View v, float x, float y, float w, float h) {
            int X = Math.round(x), Y = Math.round(y), W = Math.max(0, Math.round(w)), H = Math.max(0, Math.round(h));
            v.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(H, MeasureSpec.EXACTLY));
            v.layout(X, Y, X + W, Y + H);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sw = form == null || !form.open;
                    sx = e.getX(); sy = e.getY(); st = SystemClock.uptimeMillis();
                    break;
                case MotionEvent.ACTION_UP:
                    if (sw) {
                        sw = false;
                        float dx = e.getX() - sx, dy = e.getY() - sy;
                        if (SystemClock.uptimeMillis() - st <= 1500 && Math.abs(dx) >= 60 * app.u.dens && Math.abs(dx) >= Math.abs(dy) * 1.2f) {
                            Pages.PageEvent ev = new Pages.PageEvent("countdown", "swipe");
                            ev.dir = dx < 0 ? "left" : "right";
                            api.onEvent(ev);
                        }
                    }
                    break;
                case MotionEvent.ACTION_CANCEL: sw = false; break;
            }
            return super.dispatchTouchEvent(e);
        }
        @Override public boolean onTouchEvent(MotionEvent e) { return true; }
    }

    /** 大卡片：渐变底 + 两个半透明圆、名字和"编辑"、状态、大数字（打开 / 切换时从 0 滚上去，当天轻轻跳动）、日期、补充一句 */
    private final class Hero extends ViewGroup {
        final NumV num;
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), circ = new Paint(Paint.ANTI_ALIAS_FLAG), nameP = new Paint(Paint.ANTI_ALIAS_FLAG),
            editP = new Paint(Paint.ANTI_ALIAS_FLAG), stateP = new Paint(Paint.ANTI_ALIAS_FLAG), numP = new Paint(Paint.ANTI_ALIAS_FLAG),
            tianP = new Paint(Paint.ANTI_ALIAS_FLAG), dateP = new Paint(Paint.ANTI_ALIAS_FLAG), subP = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint nameM = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF(), editR = new RectF();
        final android.graphics.Path clip = new android.graphics.Path();
        String sName = "", sState = "", sDate = "", sSub = "", nameShown = "", shownStr = "0";
        int[] grad = COLORS[0];
        boolean empty, editOn, today;
        private int target, shown, countDur;
        private long count0, beat0;
        private float fs, padX, numTop, numBase, stateBase, dateBase, subBase, nameX, nameBase, contentH;
        private Shader shader;
        private int[] shaderFor;
        private int shaderW, shaderH;
        private boolean pressEdit, tapOk;
        private float px, py;
        private final float slop;
        // 数字从 0 滚到目标（缓出，最长 1.4 秒）
        final Frames.Client count = t -> {
            float k = Math.min(1, (t - count0) / (float) countDur), e = 1 - (1 - k) * (1 - k) * (1 - k);
            int v = Math.round(target * e);
            if (v != shown) setShown(v);
            return k < 1;
        };
        // 当天：数字 1.6 秒一下轻轻放大到 1.06 倍（只改缩放，不重画）
        final Frames.Client beat = this::beatFrame;
        private boolean beatFrame(long t) {
            float ph = ((t - beat0) % 1600) / 1600f, s = ph < .5f ? 1 + .06f * BEAT.at(ph * 2) : 1 + .06f * (1 - BEAT.at((ph - .5f) * 2));
            num.setScaleX(s); num.setScaleY(s);
            return true;
        }

        Hero(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            nameP.setTypeface(Fonts.sysBold); nameP.setLetterSpacing(.04f); nameP.setColor(0xffffffff);
            nameM.setTypeface(Fonts.sysBold); nameM.setLetterSpacing(.04f);
            editP.setTypeface(Fonts.sys);
            stateP.setTypeface(Fonts.sys); stateP.setColor(0xe6ffffff);
            numP.setTypeface(Fonts.sysBold); numP.setLetterSpacing(-.02f); numP.setFontFeatureSettings("tnum"); numP.setColor(0xffffffff);
            tianP.setTypeface(Fonts.sys); tianP.setColor(0xe6ffffff);
            dateP.setTypeface(Fonts.sys); dateP.setColor(0xebffffff);
            subP.setTypeface(Fonts.sys); subP.setColor(0xbfffffff);
            num = new NumV(c);
            addView(num);
        }

        void setNum(int n, boolean animate) {
            target = n;
            app.frames.remove(count);
            if (animate) {
                count0 = SystemClock.uptimeMillis();
                countDur = Math.min(1400, 500 + n * 2);
                setShown(0);
                app.frames.add(count);
            } else setShown(n);
        }
        private void setShown(int v) {
            shown = v;
            shownStr = String.valueOf(v);
            num.setPivotX(numP.measureText(shownStr) / 2);
            num.invalidate();
            invalidate();
        }
        void setToday(boolean on) {
            if (on == today) return;
            today = on;
            if (on) { beat0 = SystemClock.uptimeMillis(); app.frames.add(beat, 30); }
            else { app.frames.remove(beat); num.setScaleX(1); num.setScaleY(1); }
        }

        /** 竖向时大卡片按内容高度：宽 w 时要多高 */
        float need(int w) { build(w, 0); return contentH; }

        /** 字号、各行位置；大数字放不下时缩小（同 fitNum：按最终的数字量） */
        private void build(int w, int hh) {
            float R = rem;
            boolean p = portrait;
            padX = (p ? .3f : .44f) * R;
            float padT = (p ? .2f : .4f) * R, padBot = (p ? .24f : .4f) * R;
            nameP.setTextSize((p ? .3f : .34f) * R);
            nameM.setTextSize(nameP.getTextSize());
            editP.setTextSize(.18f * R);
            stateP.setTextSize((p ? .22f : .26f) * R);
            tianP.setTextSize((p ? .3f : .4f) * R);
            dateP.setTextSize((p ? .18f : .22f) * R);
            subP.setTextSize((p ? .16f : .18f) * R);
            float def = (p ? 1.4f : 2.2f) * R, avail = w - 2 * padX;
            numP.setTextSize(def);
            float need = numP.measureText(String.valueOf(target)) + .12f * R + tianP.measureText("天");
            fs = need > avail && need > 0 && avail > 0 ? def * avail / need * .97f : def;
            numP.setTextSize(fs);
            // 名字和"编辑"一行：横向浮在左上，竖向排在最上面
            float editW = editP.measureText("编辑") + .44f * R, editH = Fonts.lineH(editP) + .16f * R;
            float rowH = Math.max(Fonts.lineH(nameP), editH), rowTop = p ? padT : .34f * R, rowL = p ? padX : .44f * R, rowR = w - (p ? padX : .3f * R);
            editR.set(rowR - editW, rowTop + (rowH - editH) / 2, rowR, rowTop + (rowH + editH) / 2);
            nameX = rowL;
            nameBase = rowTop + (rowH - Fonts.lineH(nameP)) / 2 + Fonts.base(nameP);
            float nameW = rowR - rowL - (editOn ? editW : 0);
            nameShown = nameW <= 0 ? "" : TextUtils.ellipsize(sName, nameM, nameW, TextUtils.TruncateAt.END).toString();
            float stH = sState.isEmpty() ? 0 : Fonts.lineH(stateP), dH = sDate.isEmpty() ? 0 : Fonts.lineH(dateP), sbH = sSub.isEmpty() ? 0 : Fonts.lineH(subP);
            float mt = (p ? .02f : .04f) * R, mb = (p ? .08f : .1f) * R;
            float colH = stH + mt + fs + mb + dH + .06f * R + sbH;
            float top = p ? rowTop + rowH + .1f * R : padT + (hh - padT - padBot - colH) / 2; // 横向上下居中
            stateBase = top + Fonts.base(stateP);
            numTop = top + stH + mt;
            numBase = Fonts.baseIn(numP, fs);
            dateBase = numTop + fs + mb + Fonts.base(dateP);
            subBase = numTop + fs + mb + dH + .06f * R + Fonts.base(subP);
            contentH = top + colH + padBot;
        }

        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs)); }
        @Override protected void onLayout(boolean changed, int l, int t, int rr, int b) {
            int w = rr - l, hh = b - t;
            build(w, hh);
            int W = Math.max(0, Math.round(w - 2 * padX)), H = Math.max(1, Math.round(fs));
            num.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(H, MeasureSpec.EXACTLY));
            num.layout(Math.round(padX), Math.round(numTop), Math.round(padX) + W, Math.round(numTop) + H);
            num.setPivotX(numP.measureText(shownStr) / 2);
            num.setPivotY(fs / 2);
            num.invalidate();
            clip.reset();
            r.set(0, 0, w, hh);
            clip.addRoundRect(r, .32f * rem, .32f * rem, android.graphics.Path.Direction.CW);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float R = rem, w = getWidth(), hh = getHeight(), rad = .32f * R;
            if (shader == null || shaderFor != grad || shaderW != (int) w || shaderH != (int) hh) { // 135° 渐变
                shaderFor = grad; shaderW = (int) w; shaderH = (int) hh;
                float L = .7071f * (w + hh), k = .7071f * L / 2;
                shader = new LinearGradient(w / 2 - k, hh / 2 - k, w / 2 + k, hh / 2 + k, grad[0], grad[1], Shader.TileMode.CLAMP);
            }
            r.set(0, 0, w, hh);
            if (!empty) {
                bg.setColor(grad[0]);
                Fonts.shadow(bg, .14f * R, .4f * R, 0x40ff5a5a);
                c.drawRoundRect(r, rad, rad, bg);
                bg.clearShadowLayer();
            }
            bg.setShader(shader);
            c.drawRoundRect(r, rad, rad, bg);
            bg.setShader(null);
            c.save();
            c.clipPath(clip);
            circ.setColor(0x1fffffff);
            c.drawCircle(w - .8f * R, .8f * R, 2 * R, circ);
            circ.setColor(0x14ffffff);
            c.drawCircle(w - 3.2f * R, 3.6f * R, 1.2f * R, circ);
            c.drawText(nameShown, nameX, nameBase, nameP);
            if (editOn) {
                editP.setColor(pressEdit ? 0x59ffffff : 0x38ffffff);
                c.drawRoundRect(editR, .3f * R, .3f * R, editP);
                editP.setColor(0xffffffff);
                c.drawText("编辑", editR.left + .22f * R, editR.top + .08f * R + Fonts.base(editP), editP);
            }
            c.drawText(sState, padX, stateBase, stateP);
            c.drawText("天", padX + numP.measureText(shownStr) + .12f * R, numTop + numBase, tianP);
            c.drawText(sDate, padX, dateBase, dateP);
            c.drawText(sSub, padX, subBase, subP);
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!editOn || !editR.contains(x, y)) return false;
                    pressEdit = true; tapOk = true; px = x; py = y;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (tapOk && Math.hypot(x - px, y - py) > slop) { tapOk = false; pressEdit = false; invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP:
                    pressEdit = false;
                    invalidate();
                    if (tapOk && editR.contains(x, y)) { Ev ev = byId(selId); if (ev != null) openSheet(ev); }
                    tapOk = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressEdit = false; tapOk = false;
                    invalidate();
                    return true;
            }
            return true;
        }

        /** 大数字（单独一层：当天跳动只改它的缩放） */
        final class NumV extends View {
            NumV(Context c) { super(c); }
            @Override protected void onDraw(Canvas c) { c.drawText(shownStr, 0, numBase, numP); }
        }
    }

    /** 列表卡片："我的日子" + 添加键，下面能上下滑的列表 */
    private final class ListBox extends ViewGroup {
        final ScrollView scroll;
        final Items items;
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), head = new Paint(Paint.ANTI_ALIAS_FLAG), add = new Paint(Paint.ANTI_ALIAS_FLAG), plus = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        private float addCx, addCy, padX, headBase;
        private Shader addShader;
        private boolean pressAdd, tapOk;
        private float px, py;
        private final float slop;

        ListBox(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            head.setTypeface(Fonts.sysBold);
            plus.setTypeface(Fonts.sys);
            plus.setColor(0xffffffff);
            plus.setTextAlign(Paint.Align.CENTER);
            scroll = new ScrollView(c);
            scroll.setOverScrollMode(OVER_SCROLL_NEVER);
            scroll.setVerticalScrollBarEnabled(true);
            items = new Items(c);
            scroll.addView(items, new ScrollView.LayoutParams(-1, -2));
            addView(scroll);
        }
        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs)); }
        @Override protected void onLayout(boolean changed, int l, int t, int rr, int b) {
            float R = rem, w = rr - l, hh = b - t, padT = (portrait ? .16f : .22f) * R, headPb = (portrait ? .06f : .12f) * R;
            padX = .2f * R;
            head.setTextSize(.24f * R);
            plus.setTextSize(.34f * R);
            addCx = w - padX - .1f * R - .3f * R;
            addCy = padT + .3f * R;
            headBase = addCy - (head.descent() + head.ascent()) / 2;
            addShader = new LinearGradient(addCx - .3f * R, addCy - .3f * R, addCx + .3f * R, addCy + .3f * R, 0xff5b8cff, 0xff7b5bff, Shader.TileMode.CLAMP); // 135°
            float top = padT + .6f * R + headPb;
            int W = Math.max(0, Math.round(w - 2 * padX)), H = Math.max(0, Math.round(hh - .1f * R - top));
            scroll.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(H, MeasureSpec.EXACTLY));
            scroll.layout(Math.round(padX), Math.round(top), Math.round(padX) + W, Math.round(top) + H);
        }
        @Override protected void onDraw(Canvas c) {
            float R = rem, w = getWidth(), hh = getHeight();
            r.set(0, 0, w, hh);
            bg.setColor(col(PANEL));
            Fonts.shadow(bg, .04f * R, .2f * R, 0x0f141e3c);
            c.drawRoundRect(r, .32f * R, .32f * R, bg);
            bg.clearShadowLayer();
            head.setColor(col(TEXT));
            c.drawText("我的日子", padX + .1f * R, headBase, head);
            float ar = .3f * R;
            c.save();
            if (pressAdd) c.scale(.92f, .92f, addCx, addCy);
            add.setColor(0xff5b8cff);
            Fonts.shadow(add, .06f * R, .16f * R, 0x665b8cff);
            c.drawCircle(addCx, addCy, ar, add);
            add.clearShadowLayer();
            add.setShader(addShader);
            c.drawCircle(addCx, addCy, ar, add);
            add.setShader(null);
            c.drawText("＋", addCx, addCy - (plus.descent() + plus.ascent()) / 2, plus);
            c.restore();
        }
        private boolean onAdd(float x, float y) { return Math.hypot(x - addCx, y - addCy) <= .3f * rem; }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!onAdd(x, y)) return false;
                    pressAdd = true; tapOk = true; px = x; py = y;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (tapOk && Math.hypot(x - px, y - py) > slop) { tapOk = false; pressAdd = false; invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP:
                    pressAdd = false;
                    invalidate();
                    if (tapOk && onAdd(x, y)) openSheet(null);
                    tapOk = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressAdd = false; tapOk = false;
                    invalidate();
                    return true;
            }
            return true;
        }
    }

    /** 列表里的一项（排好序、文字都备好） */
    static final class Row {
        long id;
        int[] c;
        String name, date, state, days, nameShown = "";
        boolean rep, repShown, tian;
        float daysW;
        Shader dot;
    }

    /** 列表（一个 View 画全部项，放在 ScrollView 里）：色块、名字（每年重复带 ↻）、日期，右边"还有 / 已经 N 天" */
    private final class Items extends View {
        final ArrayList<Row> rows = new ArrayList<>();
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), nameP = new Paint(Paint.ANTI_ALIAS_FLAG), repP = new Paint(Paint.ANTI_ALIAS_FLAG),
            dateP = new Paint(Paint.ANTI_ALIAS_FLAG), smallP = new Paint(Paint.ANTI_ALIAS_FLAG), daysP = new Paint(Paint.ANTI_ALIAS_FLAG), tipP = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint nameM = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        private float itemH, gap, mainY, dateY, daysY;
        private int laidW = -1;
        private float laidRem;
        private int pressed = -1;
        private boolean tapOk;
        private float px, py;
        private final float slop;

        Items(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            nameP.setTypeface(Fonts.sysBold);
            nameM.setTypeface(Fonts.sysBold);
            repP.setTypeface(Fonts.sys);
            dateP.setTypeface(Fonts.sys);
            smallP.setTypeface(Fonts.sys);
            daysP.setTypeface(Fonts.sysBold);
            daysP.setFontFeatureSettings("tnum");
            tipP.setTypeface(Fonts.sys);
            tipP.setTextAlign(Paint.Align.CENTER);
        }
        /** 列表内容变了 */
        void rebuild() { laidW = -1; invalidate(); }

        /** 按宽度排：每项一样高，名字放不下用省略号 */
        private void lay(int w) {
            laidW = w;
            laidRem = rem;
            float R = rem;
            nameP.setTextSize(.23f * R); nameM.setTextSize(.23f * R);
            repP.setTextSize(.17f * R);
            dateP.setTextSize(.15f * R);
            smallP.setTextSize(.14f * R);
            daysP.setTextSize(.34f * R);
            tipP.setTextSize(.18f * R);
            float nl = Fonts.lineH(nameP), dl = Fonts.lineH(dateP), yl = Fonts.lineH(daysP);
            float content = Math.max(.44f * R, Math.max(nl + .03f * R + dl, yl));
            itemH = content + .32f * R;
            gap = .04f * R;
            float mainH = nl + .03f * R + dl;
            mainY = .16f * R + (content - mainH) / 2 + Fonts.base(nameP);
            dateY = .16f * R + (content - mainH) / 2 + nl + .03f * R + Fonts.base(dateP);
            daysY = .16f * R + (content - yl) / 2 + Fonts.base(daysP);
            float x0 = .12f * R + .44f * R + .16f * R, repW = nameP.measureText(" ") + repP.measureText("↻");
            float d = .44f * R, k = d / 2;
            for (Row w0 : rows) {
                w0.daysW = smallP.measureText(w0.state) + .08f * R + daysP.measureText(w0.days) + (w0.tian ? smallP.measureText("天") : 0);
                float avail = w - .12f * R - w0.daysW - .1f * R - x0, nw = nameP.measureText(w0.name);
                // 放不下时整行末尾用省略号（↻ 先被截掉，同网页版）
                if (nw + (w0.rep ? repW : 0) <= avail) { w0.nameShown = w0.name; w0.repShown = w0.rep; }
                else { w0.nameShown = avail <= 0 ? "" : TextUtils.ellipsize(w0.name, nameM, avail, TextUtils.TruncateAt.END).toString(); w0.repShown = false; }
                w0.dot = new LinearGradient(d / 2 - k, d / 2 - k, d / 2 + k, d / 2 + k, w0.c[0], w0.c[1], Shader.TileMode.CLAMP);
            }
        }
        private float contentH() { return rows.isEmpty() ? .8f * rem + Fonts.lineH(tipP) : rows.size() * itemH + (rows.size() - 1) * gap; }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            if (w != laidW || laidRem != rem) lay(w);
            setMeasuredDimension(w, Math.round(contentH()));
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            if (w != laidW || laidRem != rem) lay(w);
            float R = rem;
            if (rows.isEmpty()) {
                tipP.setColor(col(SUB));
                c.drawText("点右上角 ＋ 添加倒数日或纪念日", w / 2f, .4f * R + Fonts.base(tipP), tipP);
                return;
            }
            int text = col(TEXT), sub = col(SUB);
            float x0 = .12f * R + .44f * R + .16f * R, d = .44f * R;
            for (int i = 0; i < rows.size(); i++) {
                Row w0 = rows.get(i);
                float y = i * (itemH + gap);
                if (pressed == i || w0.id == selId) {
                    fill.setColor(pressed == i ? col(LINE) : col(BG));
                    r.set(0, y, w, y + itemH);
                    c.drawRoundRect(r, .2f * R, .2f * R, fill);
                }
                c.save();
                c.translate(.12f * R, y + (itemH - d) / 2);
                fill.setShader(w0.dot);
                r.set(0, 0, d, d);
                c.drawRoundRect(r, .14f * R, .14f * R, fill);
                fill.setShader(null);
                c.restore();
                nameP.setColor(text);
                c.drawText(w0.nameShown, x0, y + mainY, nameP);
                if (w0.repShown) {
                    repP.setColor(sub);
                    c.drawText("↻", x0 + nameP.measureText(w0.nameShown) + nameP.measureText(" "), y + mainY, repP);
                }
                dateP.setColor(sub);
                c.drawText(w0.date, x0, y + dateY, dateP);
                float x = w - .12f * R - w0.daysW;
                smallP.setColor(sub);
                c.drawText(w0.state, x, y + daysY, smallP);
                x += smallP.measureText(w0.state) + .04f * R;
                daysP.setColor(w0.c[1]);
                c.drawText(w0.days, x, y + daysY, daysP);
                if (w0.tian) c.drawText("天", x + daysP.measureText(w0.days) + .04f * R, y + daysY, smallP);
            }
        }

        private int hit(float y) {
            if (rows.isEmpty() || y < 0) return -1;
            int i = (int) (y / (itemH + gap));
            return i < rows.size() && y - i * (itemH + gap) <= itemH ? i : -1;
        }
        private void press(int i) { if (pressed != i) { pressed = i; invalidate(); } }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    px = x; py = y; tapOk = true;
                    press(hit(y));
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (tapOk && Math.hypot(x - px, y - py) > slop) { tapOk = false; press(-1); }
                    return true;
                case MotionEvent.ACTION_UP: {
                    int i = pressed;
                    press(-1);
                    if (tapOk && i >= 0 && hit(y) == i) select(rows.get(i).id);
                    tapOk = false;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    tapOk = false;
                    press(-1);
                    return true;
            }
            return true;
        }
    }

    // ---------- 编辑表单：全用按钮，不依赖系统的日期弹窗 ----------
    private Ev draft;
    private long editingId;

    private void openSheet(Ev ev) {
        if (form != null) dropForm();
        editingId = ev != null ? ev.id : 0;
        draft = ev != null ? ev.copy() : ev(0, "", false, false, now.y, now.m, now.d, list.size() % COLORS.length);
        form = new Form(ctx, ev != null);
        root.addView(form);
        root.place(form, 0, 0, root.getWidth(), root.getHeight());
        form.name.setText(draft.name);
        renderSheet();
        form.show();
    }
    private void closeSheet() { if (form != null) dropForm(); }
    private void dropForm() {
        Form f = form;
        form = null;
        f.shut();
        root.removeView(f);
    }
    private int maxDay() {
        if (draft.lunar) return 30;
        int y = draft.repeat ? 2000 : draft.y;
        return Lunar.epochDay(y, draft.m + 1, 1) - Lunar.epochDay(y, draft.m, 1);
    }
    private String draftName() { return form == null ? "" : form.name.getText().toString().trim(); }
    private void renderSheet() {
        Form f = form;
        if (f == null || draft == null) return;
        draft.d = Math.max(1, Math.min(draft.d, maxDay()));
        f.cal.set(draft.lunar ? "lunar" : "solar");
        f.rep.set(draft.repeat ? "on" : "off");
        f.sw.set(draft.color);
        f.rowY.setVisibility(draft.repeat ? View.GONE : View.VISIBLE);
        f.ys.setLabel(String.valueOf(draft.y));
        f.ms.setLabel(draft.lunar ? Lunar.MONTH_NAMES[draft.m - 1] + "月" : draft.m + "月");
        f.ds.setLabel(draft.lunar ? Lunar.DAY_NAMES[draft.d - 1] : draft.d + "日");
        Lunar.Countdown r = info(draft);
        String nm = draftName();
        if (nm.isEmpty()) nm = "这一天";
        f.preview.setText(r == null ? "这个日期不存在"
            : (Lunar.TODAY.equals(r.state) ? "就是今天" : Lunar.FUTURE.equals(r.state) ? "距" + nm + "还有 " + r.days + " 天" : nm + "已经 " + (-r.days) + " 天")
              + (draft.lunar ? "（公历 " + r.y + "年" + r.m + "月" + r.d + "日）" : ""));
    }
    /** 表单里点了什么（同网页版 #sheet 的点击处理） */
    private void step(char k, int dlt) {
        if (k == 'y') draft.y = Math.max(Lunar.MIN_YEAR, Math.min(Lunar.MAX_YEAR, draft.y + dlt));
        if (k == 'm') draft.m = (draft.m + dlt + 11) % 12 + 1;
        if (k == 'd') { int mx = maxDay(); draft.d = (draft.d + dlt + mx - 1) % mx + 1; }
        renderSheet();
    }
    private void chip(String s) {
        if (form == null) return;
        form.name.setText(s);
        form.name.setSelection(form.name.length());
        if (s.contains("生日") || s.contains("节")) draft.repeat = true;
        if (s.contains("纪念")) draft.repeat = false;
        renderSheet();
    }
    private void saveDraft() {
        if (info(draft) == null) return;
        String nm = draftName();
        draft.name = nm.isEmpty() ? "我的日子" : nm;
        if (editingId != 0) {
            Ev e = byId(editingId);
            if (e != null) { e.name = draft.name; e.lunar = draft.lunar; e.repeat = draft.repeat; e.y = draft.y; e.m = draft.m; e.d = draft.d; e.color = draft.color; }
        } else {
            draft.id = System.currentTimeMillis();
            list.add(draft);
        }
        selId = editingId != 0 ? editingId : draft.id;
        finish("saved", draft.name);
    }
    /** 删除要点两下（第一下变成"确认删除？"） */
    private void deleteDraft() {
        if (!form.confirm) { form.confirm = true; form.del.setText("确认删除？"); return; }
        Ev gone = byId(editingId);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id == editingId) list.remove(i);
        if (selId == editingId) selId = 0;
        finish("deleted", gone != null ? gone.name : "");
    }
    private void finish(String type, String name) {
        save();
        closeSheet();
        renderAll(true);
        tell(type, name);
    }

    /** 编辑表单（同网页版 .sheet）：半透明遮罩 + 居中的白色卡片（放不下能上下滑），点遮罩关掉 */
    private final class Form extends ViewGroup {
        boolean open, confirm;
        final ScrollView box;
        final LinearLayout body, rowY;
        final TextView preview, del;
        final EditText name;
        final SegV cal, rep, ys, ms, ds;
        final Swatches sw;
        private final Paint dim = new Paint();
        private final GradientDrawable nameBg;
        private final float slop, R = rem;
        private boolean tapOk;
        private float px, py;
        private long box0;
        // 卡片弹出：0.92 倍、偏下 4% → 原位（0.3 秒，稍微冲过头）
        private final Frames.Client boxIn = this::boxFrame;
        private boolean boxFrame(long t) {
            float k = Ease.POP10.at(Ease.clamp((t - box0) / 300f, 0, 1));
            box.setScaleX(.92f + .08f * k); box.setScaleY(.92f + .08f * k);
            box.setTranslationY(.04f * box.getHeight() * (1 - k));
            box.setAlpha(Ease.clamp(k, 0, 1));
            return t - box0 < 300;
        }

        Form(Context c, boolean editing) {
            super(c);
            setWillNotDraw(false);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            dim.setColor(Ease.rgba(10, 15, 30, .45f));
            box = new ScrollView(c);
            box.setOverScrollMode(OVER_SCROLL_NEVER);
            box.setBackground(Kit.round(0xffffffff, .24f * R, 0));
            box.setClipToOutline(true);
            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(px(.24f), px(.16f), px(.24f), px(.16f));
            body.setClipChildren(false);
            box.addView(body, new ScrollView.LayoutParams(-1, -2));
            addView(box);

            TextView title = text(editing ? "编辑日子" : "添加日子", .26f, 0xff222222, Fonts.sysBold);
            LinearLayout.LayoutParams tp = lp(-1, -2);
            tp.bottomMargin = px(.06f);
            body.addView(title, tp);

            name = new EditText(c);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, .2f * R);
            name.setTextColor(0xff222222);
            name.setTypeface(Fonts.sys);
            name.setSingleLine(true);
            name.setIncludeFontPadding(false);
            name.setGravity(Gravity.CENTER_VERTICAL);
            name.setPadding(px(.12f), 0, px(.12f), 0);
            name.setInputType(InputType.TYPE_CLASS_TEXT);
            name.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            name.setFilters(new InputFilter[] { new InputFilter.LengthFilter(10) });
            nameBg = Kit.round(0xffffffff, .1f * R, 0);
            nameBg.setStroke(Math.max(1, Math.round(app.u.dens)), 0xffd5d8de);
            name.setBackground(nameBg);
            name.setOnFocusChangeListener((v, f) -> {
                app.card.typing = f;
                nameBg.setStroke(Math.max(1, Math.round(app.u.dens)), f ? 0xff3a7bfd : 0xffd5d8de);
            });
            name.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
                @Override public void afterTextChanged(Editable s) { renderSheet(); }
            });
            body.addView(row("名称", name, px(2.8f), px(.42f)));

            Kit.Flow chips = new Kit.Flow(c, px(.06f), px(.06f));
            chips.setPadding(0, px(.07f), 0, px(.07f));
            for (String s : CHIPS) {
                TextView t = text(s, .17f, 0xff444455, Fonts.sys);
                t.setSingleLine(true);
                t.setPadding(px(.15f), px(.05f), px(.15f), px(.05f));
                t.setBackground(Kit.round(0xfff0f3f8, .3f * R, 0));
                Kit.press(t, () -> chip(s));
                chips.addView(t);
            }
            body.addView(chips, lp(-1, -2));

            sw = new Swatches(c);
            body.addView(row("颜色", sw, -2, -2));
            cal = new SegV(c, new String[] { "公历", "农历" }, new String[] { "solar", "lunar" }, v -> { draft.lunar = "lunar".equals(v); renderSheet(); });
            body.addView(row("历法", cal, -2, -2));
            rep = new SegV(c, new String[] { "是", "否" }, new String[] { "on", "off" }, v -> { draft.repeat = "on".equals(v); renderSheet(); });
            body.addView(row("每年重复", rep, -2, -2));
            ys = stepper('y');
            rowY = row("年", ys, -2, -2);
            body.addView(rowY);
            ms = stepper('m');
            body.addView(row("月", ms, -2, -2));
            ds = stepper('d');
            body.addView(row("日", ds, -2, -2));

            preview = text("", .18f, 0xff2f5bd2, Fonts.sys);
            preview.setBackground(Kit.round(0xfff2f6ff, .12f * R, 0));
            preview.setPadding(px(.14f), px(.1f), px(.14f), px(.1f));
            preview.setLineSpacing(0, 1.2f);
            LinearLayout.LayoutParams pp = lp(-1, -2);
            pp.topMargin = px(.08f); pp.bottomMargin = px(.02f);
            body.addView(preview, pp);

            LinearLayout btns = new LinearLayout(c);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            del = button("删除", 0, 0xffd9534f, CountdownPage.this::deleteDraft);
            del.setVisibility(editing ? VISIBLE : INVISIBLE);
            btns.addView(del, lp(-2, px(.48f)));
            LinearLayout.LayoutParams gap = lp(0, 1);
            gap.weight = 1;
            btns.addView(new View(c), gap);
            btns.addView(button("取消", 0xffeef0f4, 0xff333333, CountdownPage.this::closeSheet), btnLp());
            btns.addView(button("保存", 0xff2f7cf6, 0xffffffff, CountdownPage.this::saveDraft), btnLp());
            LinearLayout.LayoutParams bp = lp(-1, -2);
            bp.topMargin = px(.08f);
            body.addView(btns, bp);
            setVisibility(INVISIBLE);
        }
        private int px(float r) { return Math.round(r * R); }
        private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
        private TextView text(String s, float size, int color, android.graphics.Typeface tf) {
            TextView t = new TextView(getContext());
            t.setText(s);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * R);
            t.setTextColor(color);
            t.setTypeface(tf);
            t.setIncludeFontPadding(false);
            return t;
        }
        /** 一行：左边说明、右边控件，底下一条细线 */
        private LinearLayout row(String label, View right, int w, int h) {
            LinearLayout r = new LinearLayout(getContext());
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, px(.04f), 0, px(.04f));
            r.setClipChildren(false);
            final int line = Math.max(1, Math.round(app.u.dens));
            r.setBackground(new Drawable() {
                final Paint p = new Paint();
                @Override public void draw(Canvas c) { p.setColor(0xffeeeeee); android.graphics.Rect b = getBounds(); c.drawRect(b.left, b.bottom - line, b.right, b.bottom, p); }
                @Override public void setAlpha(int a) { }
                @Override public void setColorFilter(android.graphics.ColorFilter f) { }
                @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
            });
            TextView l = text(label, .2f, 0xff555555, Fonts.sys);
            l.setSingleLine(true);
            LinearLayout.LayoutParams lp = lp(0, -2);
            lp.weight = 1;
            lp.rightMargin = px(.2f);
            r.addView(l, lp);
            r.addView(right, lp(w, h));
            r.setLayoutParams(lp(-1, -2));
            return r;
        }
        private SegV stepper(char k) {
            return new SegV(getContext(), new String[] { "－", "", "＋" }, new String[] { "-", null, "+" }, v -> step(k, "+".equals(v) ? 1 : -1));
        }
        private TextView button(String s, int bg, int fg, Runnable click) {
            TextView t = text(s, .2f, fg, Fonts.sys);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setMinWidth(px(1));
            t.setPadding(px(.16f), 0, px(.16f), 0);
            if (bg != 0) t.setBackground(Kit.round(bg, .14f * R, 0));
            t.setSoundEffectsEnabled(false);
            t.setOnClickListener(v -> click.run());
            return t;
        }
        private LinearLayout.LayoutParams btnLp() { LinearLayout.LayoutParams p = lp(-2, px(.48f)); p.leftMargin = px(.1f); return p; }

        void show() {
            open = true;
            setVisibility(VISIBLE);
            setAlpha(0);
            animate().alpha(1).setDuration(200).setInterpolator(Ease.EASE::at).start();
            box.setAlpha(0);
            box0 = SystemClock.uptimeMillis();
            app.frames.add(boxIn);
        }
        /** 收键盘、停动画（关掉表单、页面销毁时） */
        void shut() {
            open = false;
            animate().cancel();
            app.frames.remove(boxIn);
            if (name.hasFocus()) {
                InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null) im.hideSoftInputFromWindow(name.getWindowToken(), 0);
                name.clearFocus();
            }
            app.card.typing = false;
        }

        @Override protected void onMeasure(int ws, int hs) {
            int W = MeasureSpec.getSize(ws), H = MeasureSpec.getSize(hs);
            box.measure(MeasureSpec.makeMeasureSpec(Math.round(Math.min(5.4f * R, .96f * W)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.round(.96f * H), MeasureSpec.AT_MOST));
            setMeasuredDimension(W, H);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int bw = box.getMeasuredWidth(), bh = box.getMeasuredHeight(), x = (r - l - bw) / 2, y = (b - t - bh) / 2;
            box.layout(x, y, x + bw, y + bh);
            box.setPivotX(bw / 2f);
            box.setPivotY(bh / 2f);
        }
        @Override protected void onDraw(Canvas c) { c.drawRect(0, 0, getWidth(), getHeight(), dim); }
        /** 遮罩：点一下关掉表单 */
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: tapOk = true; px = e.getX(); py = e.getY(); break;
                case MotionEvent.ACTION_MOVE: if (Math.hypot(e.getX() - px, e.getY() - py) > slop) tapOk = false; break;
                case MotionEvent.ACTION_UP: if (tapOk) h.post(CountdownPage.this::closeSheet); tapOk = false; break;
                case MotionEvent.ACTION_CANCEL: tapOk = false; break;
            }
            return true;
        }
    }

    /** 一组按钮（同 .seg）：value 为 null 的是中间显示值的位置（年 / 月 / 日的 − 值 ＋） */
    private final class SegV extends View {
        private final String[] labels, values;
        private String on = "", label = "";
        private final Kit.Pick pick;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG), v = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float[] x, w;
        private int pressed = -1;
        private boolean tapOk;
        private float px, py;
        private final float slop;

        SegV(Context c, String[] labels, String[] values, Kit.Pick pick) {
            super(c);
            this.labels = labels; this.values = values; this.pick = pick;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            t.setTypeface(Fonts.sys);
            v.setTypeface(Fonts.sysMedium);
            v.setColor(0xff222222);
            x = new float[labels.length];
            w = new float[labels.length];
        }
        void set(String val) { if (!val.equals(on)) { on = val; invalidate(); } }
        void setLabel(String s) {
            if (s.equals(label)) return;
            label = s;
            requestLayout();
            invalidate();
        }
        @Override protected void onMeasure(int ws, int hs) {
            float R = rem, cx = 0;
            t.setTextSize(.19f * R);
            v.setTextSize(.2f * R);
            for (int i = 0; i < labels.length; i++) {
                if (values[i] == null) { w[i] = Math.max(1.1f * R, v.measureText(label)); x[i] = cx; }
                else { cx += .08f * R; w[i] = Math.max(.6f * R, t.measureText(labels[i]) + .28f * R); x[i] = cx; }
                cx += w[i];
            }
            setMeasuredDimension(Math.round(cx) + 1, Math.round(.42f * R) + 1);
        }
        @Override protected void onDraw(Canvas c) {
            float R = rem, hh = .42f * R, line = Math.max(1, Math.round(app.u.dens));
            for (int i = 0; i < labels.length; i++) {
                if (values[i] == null) {
                    c.drawText(label, x[i] + (w[i] - v.measureText(label)) / 2, hh / 2 - (v.descent() + v.ascent()) / 2, v);
                    continue;
                }
                boolean sel = values[i].equals(on);
                float s = i == pressed ? .95f : 1;
                c.save();
                c.scale(s, s, x[i] + w[i] / 2, hh / 2);
                r.set(x[i] + line / 2, line / 2, x[i] + w[i] - line / 2, hh - line / 2);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(sel ? 0xff2f7cf6 : 0xffffffff);
                c.drawRoundRect(r, .12f * R, .12f * R, fill);
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(line);
                fill.setColor(sel ? 0xff2f7cf6 : 0xffd5d8de);
                c.drawRoundRect(r, .12f * R, .12f * R, fill);
                t.setColor(sel ? 0xffffffff : 0xff333333);
                c.drawText(labels[i], x[i] + (w[i] - t.measureText(labels[i])) / 2, hh / 2 - (t.descent() + t.ascent()) / 2, t);
                c.restore();
            }
        }
        private int hit(float ex) { for (int i = 0; i < labels.length; i++) if (values[i] != null && ex >= x[i] && ex <= x[i] + w[i]) return i; return -1; }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: px = e.getX(); py = e.getY(); tapOk = true; pressed = hit(px); invalidate(); return true;
                case MotionEvent.ACTION_MOVE:
                    if (tapOk && Math.hypot(e.getX() - px, e.getY() - py) > slop) { tapOk = false; pressed = -1; invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP: {
                    int p0 = pressed;
                    pressed = -1;
                    invalidate();
                    if (tapOk && p0 >= 0 && hit(e.getX()) == p0) { final String val = values[p0]; h.post(() -> pick.pick(val)); }
                    tapOk = false;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: pressed = -1; tapOk = false; invalidate(); return true;
            }
            return true;
        }
    }

    /** 六种配色的圆（选中的外面一圈白、一圈蓝） */
    private final class Swatches extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Shader[] sh = new Shader[COLORS.length];
        private int on = -1;
        private boolean tapOk;
        private float px, py;
        private final float slop;

        Swatches(Context c) { super(c); slop = ViewConfiguration.get(c).getScaledTouchSlop(); }
        void set(int i) { if (i != on) { on = i; invalidate(); } }
        private float cx(int i) { return (i * .5f + .1f + .2f) * rem; }
        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(Math.round(COLORS.length * .5f * rem), Math.round(.4f * rem)); }
        @Override protected void onSizeChanged(int w, int hh, int ow, int oh) {
            float r = .2f * rem;
            for (int i = 0; i < COLORS.length; i++) sh[i] = new LinearGradient(cx(i) - r, 0, cx(i) + r, 2 * r, COLORS[i][0], COLORS[i][1], Shader.TileMode.CLAMP);
        }
        @Override protected void onDraw(Canvas c) {
            float r = .2f * rem, cy = getHeight() / 2f;
            for (int i = 0; i < COLORS.length; i++) {
                if (i == on) {
                    p.setColor(0xff2f7cf6);
                    c.drawCircle(cx(i), cy, r + .06f * rem, p);
                    p.setColor(0xffffffff);
                    c.drawCircle(cx(i), cy, r + .03f * rem, p);
                }
                p.setShader(sh[i]);
                c.drawCircle(cx(i), cy, r, p);
                p.setShader(null);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: px = e.getX(); py = e.getY(); tapOk = true; return true;
                case MotionEvent.ACTION_MOVE: if (Math.hypot(e.getX() - px, e.getY() - py) > slop) tapOk = false; return true;
                case MotionEvent.ACTION_UP:
                    if (tapOk) for (int i = 0; i < COLORS.length; i++) if (Math.abs(e.getX() - cx(i)) <= .2f * rem) { final int k = i; h.post(() -> { draft.color = k; renderSheet(); }); }
                    tapOk = false;
                    return true;
            }
            return true;
        }
    }
}
