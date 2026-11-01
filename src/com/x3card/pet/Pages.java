package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 车况 / 日历 / 倒数日页面的面板（同网页版 .page-clip / .page）：面板立在地面上（底边贴地、上方圆角），从草地里升起、沉回草地（只画到地面线以上）。
 * 两页之间横向推过去（新页从一侧推进来，旧页同步推出另一侧）。同一时间只挂一个页面，关掉就销毁（同网页版卸载影子树）
 */
final class Pages {
    /** 页面告诉宠物发生了什么（同 api.onEvent 的 { page, type, … }） */
    static final class PageEvent {
        String page, type, dir;
        // 日历
        String lunar, fest, dayText;
        boolean today;
        String holidayName;
        boolean holidayNow;
        int holidayDays;
        // 倒数日
        Settings.Day ev;
        boolean evSet;
        String state, name;
        int days;
        // 车况
        String year;
        double km;
        PageEvent(String page, String type) { this.page = page; this.type = type; }
    }
    /** 给页面的接口（同网页版 mount(root, api)） */
    interface Api {
        void onEvent(PageEvent e);
        boolean dark();
        /** 车况页的数据（没有车机时 null） */
        Car car();
        /** 页面的 rem（像素）：页面的字和宠物页一样大 */
        float rem();
        App app();
    }
    /** 一个页面 */
    interface Page {
        View view();
        /** 车机切了日夜模式 */
        void theme();
        void destroy();
    }
    interface Factory { Page create(Context c, Api api); }

    static boolean has(String name) { return "calendar".equals(name) || "countdown".equals(name) || "car".equals(name); }

    private final App app;
    private final U u;
    final Clip clip;
    final Panel panel;
    private Frame cur, old;
    private boolean dark;

    Pages(Context c, App app, Layer host) {
        this.app = app;
        u = app.u;
        clip = host.add(new Clip(c));
        panel = new Panel(c);
        clip.addView(panel, new Layer.Lp());
        panel.setVisibility(View.INVISIBLE);
    }

    private final Api api = new Api() {
        @Override public void onEvent(PageEvent e) { app.pageEvent(e); }
        @Override public boolean dark() { return app.pageDark(); }
        @Override public Car car() { return app.car; }
        @Override public float rem() { return u.px(u.rem); }
        @Override public App app() { return app; }
    };

    private static Page make(Context c, String name, Api api) {
        switch (name) {
            case "calendar": return new CalendarPage(c, api);
            case "countdown": return new CountdownPage(c, api);
            default: return new CarPage(c, api);
        }
    }

    /** 面板顶边 top（像素以下都用 dp 进来）、往下沉 dy：同 placePage */
    private float top, dy;
    void place(float topDp, float dyDp) {
        top = topDp; dy = dyDp;
        float d = u.dens, W = u.px(u.W), H = u.px(u.H);
        float ground = H * .93f, pad = .14f * u.px(u.rem);
        Layer.place(clip, 0, 0, W, ground);
        Layer.place(panel, pad, topDp * d, W - 2 * pad, ground - topDp * d);
        panel.setTranslationY(dyDp * d);
        for (Frame f : new Frame[] { cur, old }) if (f != null) f.lay();
    }
    void setMoving(boolean m) { panel.setLayerType(m ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null); }
    void layout() { place(top, dy); }
    void theme(boolean d) {
        if (d != dark) { dark = d; panel.invalidate(); }
        if (cur != null) cur.page.theme();
    }

    /** 挂一个页面：第一次（从宠物页）直接挂上、面板升起由 App 做；两页之间横向推过去 */
    void open(String name, boolean first, boolean fwd, Runnable ready) {
        Frame f = new Frame(name);
        if (first) {
            dropAll();
            cur = f;
            panel.addView(f);
            f.lay();
            panel.setVisibility(View.VISIBLE);
            ready.run();
            return;
        }
        if (old != null) drop(old);
        old = cur;
        cur = f;
        panel.addView(f);
        f.lay();
        final float w = panel.getWidth();
        final Frame o = old;
        final long t0 = SystemClock.uptimeMillis();
        final Ease e = Ease.SLIDE;
        f.setTranslationX(fwd ? w : -w);
        app.frames.add(now -> {
            float k = e.at(Ease.clamp((now - t0) / 500f, 0, 1));
            f.setTranslationX((fwd ? w : -w) * (1 - k));
            if (o != null) o.setTranslationX((fwd ? -w : w) * k);
            if (k < 1) return true;
            if (o != null) drop(o);
            if (old == o) old = null;
            ready.run();
            return false;
        });
    }
    /** 面板落下后：拿掉页面 */
    void closeAll() { dropAll(); panel.setVisibility(View.INVISIBLE); }
    private void dropAll() { if (cur != null) drop(cur); if (old != null) drop(old); cur = old = null; }
    private void drop(Frame f) {
        try { f.page.destroy(); } catch (RuntimeException e) { android.util.Log.w(Uiplus.TAG, "页面关掉时出错", e); }
        panel.removeView(f);
    }
    /** 正开着的页面 */
    Page current() { return cur == null ? null : cur.page; }
    /** 这一点（dp）在不在面板上 */
    boolean contains(float x, float y) {
        if (panel.getVisibility() != View.VISIBLE) return false;
        float d = u.dens;
        return x * d >= panel.getLeft() && x * d <= panel.getRight() && y * d >= panel.getTop() + panel.getTranslationY() && y * d <= clip.getBottom();
    }

    /** 一个页面的容器（铺满面板） */
    final class Frame extends FrameLayout {
        final String name;
        final Page page;
        Frame(String name) {
            super(panel.getContext());
            this.name = name;
            Page p;
            try { p = make(getContext(), name, api); }
            catch (RuntimeException e) { app.showError(name + " 页面出错：" + e.getMessage()); p = new Empty(getContext()); }
            page = p;
            addView(page.view(), new LayoutParams(-1, -1));
        }
        void lay() {
            int w = panel.getWidth(), h = panel.getHeight();
            measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            layout(0, 0, w, h);
        }
    }
    static final class Empty implements Page {
        private final View v;
        Empty(Context c) { v = new View(c); }
        @Override public View view() { return v; }
        @Override public void theme() { }
        @Override public void destroy() { }
    }

    /** 只画到地面线以上的裁切层 */
    static final class Clip extends Layer {
        Clip(Context c) { super(c); setClipChildren(true); }
    }

    /** 面板：上方大圆角、下方小圆角，浅色 #f4f6fa / 深色 #0d121d（0.8 秒过渡），上沿一圈阴影 */
    final class Panel extends ViewGroup {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF r = new RectF();
        private final Tw darkK = new Tw(0);
        Panel(Context c) { super(c); setWillNotDraw(false); setClipChildren(true); }
        @Override protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs));
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
        }
        @Override protected void onLayout(boolean ch, int l, int t, int rr, int b) { for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(0, 0, rr - l, b - t); }
        /** 面板上的触摸都归页面（同网页版 .page 是 ui）：页面没接的也不交给卡片当手势 */
        @Override public boolean onTouchEvent(android.view.MotionEvent e) { return getVisibility() == VISIBLE; }
        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            darkK.to(dark ? 1 : 0, 800, Ease.EASE, now);
            float rem = u.px(u.rem), big = .26f * rem, small = .06f * rem;
            r.set(0, 0, getWidth(), getHeight());
            clipPath.reset();
            clipPath.addRoundRect(r, new float[] { big, big, big, big, small, small, small, small }, Path.Direction.CW);
            p.setColor(Ease.mix(0xfff4f6fa, 0xff0d121d, darkK.get(now)));
            p.setShadowLayer(.3f * rem / 2, 0, -.02f * rem, Ease.rgba(0, 0, 0, .28f));
            c.drawPath(clipPath, p);
            p.clearShadowLayer();
            if (darkK.moving(now)) postInvalidateOnAnimation();
        }
        @Override protected void dispatchDraw(Canvas c) {
            c.save();
            c.clipPath(clipPath);
            super.dispatchDraw(c);
            c.restore();
        }
    }
}
