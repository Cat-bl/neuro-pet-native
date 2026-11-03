package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 设置面板（同网页版 #panel）：齿轮点开，右下角弹出的白色圆角卡片，能上下滑；20 秒没碰自动关（正在打字时不关）。
 * 主页按"宠物 / 外观 / 提醒与事件 / 日子 / 一起玩"分组；"显示内容 ›"、"AI 聊天 ›"进二级页（每次打开都从主页开始）
 */
final class Panel extends FrameLayout {
    private final App app;
    private final U u;
    private final ScrollView scroll;
    private final LinearLayout body;
    private String sub = ""; // '' 主页 / 'show' 显示内容 / 'ai' AI 聊天
    private boolean open;
    private long openAt;
    private final Runnable autoClose = this::autoClose;
    private Kit k;
    private TextView lvN, lvE;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    Panel(Context c, App app) {
        super(c);
        this.app = app;
        u = app.u;
        setWillNotDraw(false);
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, -2));
        scroll.setOnScrollChangeListener((v, a, b, x, y) -> armClose());
        setVisibility(GONE);
        setClipToOutline(true);
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), .22f * u.px(u.rem)); }
        });
        setElevation(0);
    }

    boolean isOpen() { return open; }
    void open() { open = true; sub = ""; build(); setVisibility(VISIBLE); openAt = SystemClock.uptimeMillis(); animIn(); armClose(); }
    void close() { open = false; app.h.removeCallbacks(autoClose); setVisibility(GONE); body.removeAllViews(); hideKeyboard(); }
    void openAI() { sub = "ai"; build(); scroll.scrollTo(0, 0); armClose(); }
    void stopTimer() { app.h.removeCallbacks(autoClose); }
    void armClose() { app.h.removeCallbacks(autoClose); if (open) app.h.postDelayed(autoClose, 20000); }
    private void autoClose() { if (app.card.typing) armClose(); else app.closePanel(); }
    private void hideKeyboard() {
        View f = findFocus();
        if (f != null) { f.clearFocus(); ((android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(f.getWindowToken(), 0); }
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) { if (e.getActionMasked() == MotionEvent.ACTION_DOWN) armClose(); return super.dispatchTouchEvent(e); }

    private void animIn() {
        setPivotX(getWidth()); setPivotY(getHeight());
        setAlpha(0); setScaleX(.85f); setScaleY(.85f); setTranslationY(.2f * u.px(u.rem));
        animate().alpha(1).scaleX(1).scaleY(1).translationY(0).setDuration(300).setInterpolator(t -> Ease.POP10.at(t)).start();
    }

    /** 大小：宽 5rem（竖向 4.7rem），最宽 94%；右下角离齿轮上面一点；最高 100vh - 1.3rem */
    void layout() {
        float rem = u.px(u.rem), W = u.px(u.W), H = u.px(u.H);
        float w = Math.min((u.portrait ? 4.7f : 5f) * rem, W * .94f);
        int maxH = Math.round(H - 1.3f * rem);
        scroll.measure(MeasureSpec.makeMeasureSpec(Math.round(w), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
        float h = Math.min(maxH, scroll.getMeasuredHeight());
        Layer.place(this, W - .24f * rem - w, H - .92f * rem - h, w, h);
        if (open && k != null && Math.abs(k.rem - rem) > .5f) build();
    }
    @Override protected void onDraw(Canvas c) {
        float rem = u.px(u.rem);
        r.set(0, 0, getWidth(), getHeight());
        bg.setColor(0xf7ffffff);
        c.drawRoundRect(r, .22f * rem, .22f * rem, bg);
    }

    /** 按现在的页重建内容 */
    void build() {
        float rem = u.px(u.rem);
        k = new Kit(getContext(), rem);
        body.removeAllViews();
        body.setPadding(k.px(.2f), k.px(.06f), k.px(.2f), k.px(.16f));
        if ("show".equals(sub)) buildShow();
        else if ("ai".equals(sub) && app.ai != null) { back("AI 聊天"); app.ai.buildPanel(k, body); }
        else buildMain();
        requestLayout();
        post(this::layout);
    }
    private void back(String title) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, k.px(.04f), 0, k.px(.06f));
        r.setBackground(k.bottomLine());
        TextView b = k.link("‹ 返回", () -> { sub = ""; build(); scroll.scrollTo(0, 0); });
        b.setPadding(0, k.px(.08f), 0, k.px(.08f));
        LinearLayout.LayoutParams lp = Kit.lp(-2, -2);
        lp.rightMargin = k.px(.2f);
        r.addView(b, lp);
        r.addView(k.text(title, .2f, 0xff222222, Fonts.sysBold));
        body.addView(r, Kit.lp(-1, -2));
    }
    private boolean petsOn() { return !"off".equals(app.settings.pets); }

    private void buildMain() {
        Settings s = app.settings;
        String[] on = { "开", "关" }, ov = { "on", "off" };
        body.addView(k.header("宠物"));
        body.addView(k.row("显示宠物", k.seg(on, ov, s.pets, v -> { app.setPets(v); save(); })));
        if (petsOn()) {
            lvN = new TextView(getContext()); lvE = new TextView(getContext());
            body.addView(k.row(levelText("Neuro", "Neuron"), null));
            body.addView(k.row(levelText("Evil Neuro", "Eviling"), null));
            body.addView(k.row("宠物大小", k.seg(new String[] { "小", "中", "大" }, new String[] { "s", "m", "l" }, s.size, v -> { s.size = v; app.applyEngineConfig(); save(); })));
            if (app.ai != null && app.ai.available()) body.addView(k.row("AI 聊天", k.link(app.ai.label() + " ›", () -> { sub = "ai"; build(); scroll.scrollTo(0, 0); })));
        }
        body.addView(k.header("外观"));
        body.addView(k.row("场景", k.seg(new String[] { "自动", "白天", "傍晚", "夜晚" }, new String[] { "auto", "day", "dusk", "night" }, s.scene, v -> { s.scene = v; app.applyClasses(); save(); })));
        body.addView(k.row("时钟样式", k.seg(new String[] { "数字", "表盘" }, new String[] { "digital", "dial" }, s.clock, v -> { app.setClock(v); build(); })));
        if (!app.fsMode.isEmpty()) body.addView(k.row("全屏时收起状态栏", k.seg(on, ov, s.fsBar, v -> { s.fsBar = v; app.fsBarChanged(); save(); })));
        int hidden = 0;
        for (String[] row : Settings.SHOW) if ((row[4].isEmpty() || app.car != null) && !app.shown(row[0])) hidden++;
        body.addView(k.row("显示内容", k.link((hidden > 0 ? "已隐藏 " + hidden + " 项" : "全部显示") + " ›", () -> { sub = "show"; build(); scroll.scrollTo(0, 0); })));
        if (petsOn()) {
            body.addView(k.header("提醒与事件"));
            body.addView(k.row("说话气泡", k.seg(on, ov, s.bubble, v -> { s.bubble = v; if ("off".equals(v)) app.bubbles.clear(); app.scheduleChat((long) app.rand(20000, 40000)); if (app.ai != null) app.ai.settingsChanged(); app.ui.sync(); save(); })));
            body.addView(k.row("随机事件", k.seg(on, ov, s.events, v -> { s.events = v; if (app.play != null) app.play.eventsChanged(); save(); })));
            body.addView(k.row("每 2 小时提醒休息", k.seg(on, ov, s.rest, v -> { s.rest = v; save(); })));
            if (app.car != null) body.addView(k.row("开场看车况", k.seg(on, ov, s.intro, v -> { s.intro = v; save(); })));
        }
        body.addView(k.header("日子"));
        if (app.shown("pg-countdown")) body.addView(k.row("倒数日", k.link((s.countdown != null ? s.countdown.name + " · " + dateLabel(s.countdown, true) : "去添加") + " ›", () -> { app.closePanel(); app.openPage("countdown"); })));
        body.addView(k.row("我的生日", k.link(dateLabel(s.birthday, false) + " ›", () -> app.openSheet())));
        if (petsOn()) {
            body.addView(k.header("一起玩"));
            body.addView(k.acts(new String[] { "喂饼干", "接饼干游戏", "踢球" }, new Runnable[] {
                () -> { app.closePanel(); if (app.play != null) app.play.feed(); },
                () -> { app.closePanel(); if (app.play != null) app.play.startGame(); },
                () -> { app.closePanel(); if (app.play != null) app.play.ball(); } }));
            TextView tip = k.tip("点空地：宠物跑过去　长按空地：放一块饼干\n点宠物：说话　双击宠物：抛高高　拖它：扔出去\n往上划：一起跳　往下划：坐下\n点太阳、月亮、云朵或时间：有惊喜\n左右滑或底部导航：切到日历、倒数日（宠物会站在页面上陪你）\n日历里上下滑切换月份\n聊天键（和齿轮一起出现）：打字跟它们聊，要先在“AI 聊天”里填 API Key", .15f, 1.6f);
            LinearLayout.LayoutParams p = Kit.lp(-1, -2);
            p.topMargin = k.px(.1f);
            body.addView(tip, p);
        }
        if (app.guide != null) body.addView(k.row("新手引导", k.link("再看一遍 ›", () -> { app.closePanel(); app.later(() -> app.guide.start(), 350); })));
        String bound = app.boundHost();
        if (app.infoNeeded()) body.addView(k.row(bound.isEmpty() ? "车机数据" : "已连接 " + bound, k.link("申请授权 ›", () -> { app.closePanel(); app.authorize(); })));
        else if (!bound.isEmpty()) body.addView(k.row("已连接 " + bound, null));
    }
    private CharSequence levelText(String name, String pack) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(name + " ");
        int st = b.length();
        b.append("♥ Lv.").append(String.valueOf(App.levelOf(app.settings.love(pack))));
        b.setSpan(new android.text.style.ForegroundColorSpan(0xffff5d8f), st, b.length(), 0);
        b.setSpan(new android.text.style.AbsoluteSizeSpan(Math.round(.15f * k.rem)), st, b.length(), 0);
        return b;
    }
    String dateLabel(Settings.Day ev, boolean withYear) {
        if (ev == null) return "未设置";
        if (ev.lunar) return (withYear && !ev.repeat ? ev.y + "年" : "") + "农历" + Lunar.MONTH_NAMES[ev.m - 1] + "月" + Lunar.DAY_NAMES[ev.d - 1];
        return (withYear && !ev.repeat ? ev.y + "年" : "") + ev.m + "月" + ev.d + "日";
    }

    private void buildShow() {
        back("显示内容");
        TextView note = k.tip("点一下显示 / 隐藏，藏起来的部分也不再运行", .15f, 1.2f);
        LinearLayout.LayoutParams np = Kit.lp(-1, -2);
        np.topMargin = k.px(.1f);
        body.addView(note, np);
        String group = "";
        Kit.Flow flow = null;
        for (String[] row : Settings.SHOW) {
            if (!row[4].isEmpty() && app.car == null) continue;
            if (!row[2].equals(group)) {
                group = row[2];
                body.addView(k.header(group));
                flow = new Kit.Flow(getContext(), k.px(.1f), k.px(.1f));
                flow.setPadding(0, k.px(.06f), 0, k.px(.04f));
                flow.setBackground(k.bottomLine());
                body.addView(flow, Kit.lp(-1, -2));
            }
            final String id = row[0];
            flow.addView(k.chip(row[1], app.shown(id), true, .46f, () -> { app.toggleShow(id); build(); }));
        }
        TextView reset = k.text("全部恢复显示", .19f, Kit.BLUE, Fonts.sys);
        reset.setGravity(Gravity.CENTER);
        reset.setBackground(Kit.round(0xffffffff, k.px(.14f), Kit.BORDER));
        Kit.press(reset, () -> { app.showAll(); build(); });
        LinearLayout.LayoutParams rp = Kit.lp(-1, k.px(.5f));
        rp.topMargin = k.px(.14f);
        body.addView(reset, rp);
    }

    /** 外面改了设置：开着时重画（好感度等级、标签） */
    void sync() { if (open) build(); }
    void syncLevels() { if (open && "".equals(sub)) build(); }
    private void save() { app.save(); build(); }
}
