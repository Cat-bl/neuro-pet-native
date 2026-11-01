package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

/**
 * 生日表单（同网页版 #sheet）：全用 − ＋ 按钮，不依赖系统的日期弹窗。历法（公历 / 农历）、月、日，下面一行预览"距生日还有 N 天"；
 * 清除 / 取消 / 保存。90 秒没碰自动关
 */
final class Sheet extends FrameLayout {
    private final App app;
    private final U u;
    private final LinearLayout box;
    private Settings.Day draft;
    private boolean open;
    private final Runnable autoClose = this::close;
    private final Paint dim = new Paint();

    Sheet(Context c, App app) {
        super(c);
        this.app = app;
        u = app.u;
        setWillNotDraw(false);
        dim.setColor(Ease.rgba(10, 15, 30, .45f));
        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        addView(box, new LayoutParams(-2, -2, Gravity.CENTER));
        setVisibility(GONE);
    }

    boolean isOpen() { return open; }
    void open() {
        Calendar t = Calendar.getInstance();
        Settings.Day src = app.settings.birthday;
        draft = src != null ? src.copy() : new Settings.Day();
        if (src == null) { draft.m = t.get(Calendar.MONTH) + 1; draft.d = t.get(Calendar.DAY_OF_MONTH); }
        draft.y = t.get(Calendar.YEAR); draft.repeat = true; draft.name = "生日";
        open = true;
        setVisibility(VISIBLE);
        setAlpha(0);
        animate().alpha(1).setDuration(200).start();
        app.closePanelTimerOnly();
        arm();
        build();
    }
    void close() {
        if (!open) return;
        open = false;
        app.h.removeCallbacks(autoClose);
        setVisibility(GONE);
        box.removeAllViews();
        app.sheetClosed();
    }
    private void arm() { app.h.removeCallbacks(autoClose); app.h.postDelayed(autoClose, 90000); }
    void layout() { Layer.place(this, 0, 0, u.px(u.W), u.px(u.H)); if (open) build(); }

    @Override protected void onDraw(Canvas c) { c.drawRect(0, 0, getWidth(), getHeight(), dim); }
    @Override public boolean onTouchEvent(MotionEvent e) { return true; } // 盖住下面
    @Override public boolean dispatchTouchEvent(MotionEvent e) { if (e.getActionMasked() == MotionEvent.ACTION_DOWN) arm(); return super.dispatchTouchEvent(e); }

    private int maxDay() {
        if (draft.lunar) return 30;
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(draft.repeat ? 2000 : draft.y, draft.m - 1, 1);
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }
    private void build() {
        draft.d = Math.max(1, Math.min(draft.d, maxDay()));
        float rem = u.px(u.rem);
        Kit k = new Kit(getContext(), rem);
        box.removeAllViews();
        box.setBackground(Kit.round(0xffffffff, k.px(.24f), 0));
        box.setPadding(k.px(.26f), k.px(.2f), k.px(.26f), k.px(.2f));
        float w = Math.min(5.2f * rem, u.px(u.W) * .94f);
        box.setLayoutParams(new LayoutParams(Math.round(w), -2, Gravity.CENTER));
        TextView title = k.text("我的生日", .26f, 0xff222222, Fonts.sysBold);
        LinearLayout.LayoutParams tp = Kit.lp(-1, -2);
        tp.bottomMargin = k.px(.06f);
        box.addView(title, tp);
        box.addView(k.row("历法", k.seg(new String[] { "公历", "农历" }, new String[] { "solar", "lunar" }, draft.lunar ? "lunar" : "solar", v -> { draft.lunar = "lunar".equals(v); build(); })));
        box.addView(k.row("月", stepper(k, draft.lunar ? Lunar.MONTH_NAMES[draft.m - 1] + "月" : draft.m + "月", -1, () -> { draft.m = (draft.m - 1 + 11) % 12 + 1; build(); }, () -> { draft.m = draft.m % 12 + 1; build(); })));
        box.addView(k.row("日", stepper(k, draft.lunar ? Lunar.DAY_NAMES[draft.d - 1] : draft.d + "日", -1, () -> { int mx = maxDay(); draft.d = (draft.d - 2 + mx) % mx + 1; build(); }, () -> { int mx = maxDay(); draft.d = draft.d % mx + 1; build(); })));
        Calendar t = Calendar.getInstance();
        Lunar.Countdown r = Lunar.eventDays(draft.lunar, true, draft.y, draft.m, draft.d, t.get(Calendar.YEAR), t.get(Calendar.MONTH) + 1, t.get(Calendar.DAY_OF_MONTH));
        String pv = r == null ? "这个日期不存在" : ("today".equals(r.state) ? "就是今天" : "future".equals(r.state) ? "距生日还有 " + r.days + " 天" : "生日已经 " + (-r.days) + " 天")
            + (draft.lunar && r != null ? "（公历 " + r.y + "年" + r.m + "月" + r.d + "日）" : "");
        TextView preview = k.text(pv, .18f, 0xff2f5bd2, Fonts.sys);
        preview.setBackground(Kit.round(0xfff2f6ff, k.px(.12f), 0));
        preview.setPadding(k.px(.14f), k.px(.1f), k.px(.14f), k.px(.1f));
        LinearLayout.LayoutParams pp = Kit.lp(-1, -2);
        pp.topMargin = k.px(.12f); pp.bottomMargin = k.px(.04f);
        box.addView(preview, pp);
        LinearLayout btns = new LinearLayout(getContext());
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        TextView clear = button(k, "清除", 0, 0xffd9534f, () -> { app.settings.birthday = null; finish(); });
        LinearLayout.LayoutParams cp = Kit.lp(-2, k.px(.52f));
        cp.weight = 1;
        clear.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        btns.addView(clear, cp);
        btns.addView(button(k, "取消", 0xffeef0f4, 0xff333333, this::close), btnLp(k));
        btns.addView(button(k, "保存", Kit.BLUE, 0xffffffff, () -> {
            Calendar n = Calendar.getInstance();
            if (Lunar.eventDays(draft.lunar, true, draft.y, draft.m, draft.d, n.get(Calendar.YEAR), n.get(Calendar.MONTH) + 1, n.get(Calendar.DAY_OF_MONTH)) == null) return;
            Settings.Day b = new Settings.Day();
            b.lunar = draft.lunar; b.m = draft.m; b.d = draft.d;
            app.settings.birthday = b;
            finish();
        }), btnLp(k));
        LinearLayout.LayoutParams bp = Kit.lp(-1, -2);
        bp.topMargin = k.px(.12f);
        box.addView(btns, bp);
    }
    private LinearLayout.LayoutParams btnLp(Kit k) { LinearLayout.LayoutParams p = Kit.lp(-2, k.px(.52f)); p.leftMargin = k.px(.1f); return p; }
    private TextView button(Kit k, String s, int bg, int fg, Runnable click) {
        TextView t = k.text(s, .2f, fg, Fonts.sys);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(k.px(1));
        if (bg != 0) t.setBackground(Kit.round(bg, k.px(.14f), 0));
        Kit.press(t, click);
        return t;
    }
    /** − 值 ＋ */
    private View stepper(Kit k, String val, int unused, Runnable minus, Runnable plus) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(k.seg(new String[] { "－" }, new String[] { "-" }, "", v -> minus.run()));
        TextView v = k.text(val, .2f, 0xff222222, Fonts.sysMedium);
        v.setGravity(Gravity.CENTER);
        v.setMinWidth(k.px(1.1f));
        r.addView(v, Kit.lp(-2, -2));
        r.addView(k.seg(new String[] { "＋" }, new String[] { "+" }, "", x -> plus.run()));
        return r;
    }
    private void finish() { close(); app.sheetSaved(); }
}
