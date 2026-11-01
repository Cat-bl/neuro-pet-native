package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

/**
 * 整张卡片（同网页版 .card）：一层层叠起来的容器，顺序同 index.html（后面的画在上面）。
 * 触摸：先交给上面的界面元素（面板、按钮、页面、带按钮的气泡、小播放器……，同网页版带 ui 类的元素）；没有元素要的，当成场景上的手势（点 / 连点 / 长按 / 滑动 / 拖宠物），只跟踪第一根手指。
 * 尺寸：弹出键盘时窗口变矮，正在输入就保持原来的参考高度（同 viewport.js），场景、宠物的地板都不跟着动，只有输入栏跟着可见区域
 */
final class Card extends Layer {
    interface Sized { void sized(); }

    final U u;
    final Layer skyBack, skyFront, clockHost, festHost, eventsHost, propsHost, pageHost, petsHost, topHost, bubblesHost, rippleHost, bootHost, gameHost, uiHost, aiHost, panelHost, sheetHost, guideHost, infoHost;
    final Boot boot;
    App app;
    Sized onSized;
    /** 正在输入（输入框有焦点）：键盘弹出时不跟着变矮 */
    boolean typing;
    private int lastW, refH;

    Card(Context c, U u) {
        super(c);
        this.u = u;
        setBackgroundColor(0xff0b1430);
        skyBack = add(new Layer(c));
        skyFront = add(new Layer(c));
        clockHost = add(new Layer(c));
        festHost = add(new Layer(c));
        eventsHost = add(new Layer(c));
        propsHost = add(new Layer(c));
        pageHost = add(new Layer(c));
        petsHost = add(new Layer(c));
        topHost = add(new Layer(c));
        bubblesHost = add(new Layer(c));
        rippleHost = add(new Layer(c));
        bootHost = add(new Layer(c));
        boot = bootHost.add(new Boot(c));
        gameHost = add(new Layer(c));
        uiHost = add(new Layer(c));
        aiHost = add(new Layer(c));
        panelHost = add(new Layer(c));
        sheetHost = add(new Layer(c));
        guideHost = add(new Layer(c));
        infoHost = add(new Layer(c));
        setClipChildren(true);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        // 参考高度：宽度变了（拖中缝）、变高了、或者没在输入：直接用当前高度
        if (refH == 0 || w != lastW || h > refH || !typing) refH = h;
        lastW = w;
        float d = getResources().getDisplayMetrics().density;
        boolean ch = u.dens != d || Math.round(u.W * d) != w || Math.round(u.H * d) != refH || Math.round(u.visH * d) != h;
        u.dens = d;
        u.W = w / d;
        u.H = refH / d;
        u.visH = h / d;
        u.rem = u.H / 10.52f;
        u.portrait = w < refH;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof Layer && v != skyFront) Layer.place(v, 0, 0, w, refH); // 远山那层只有下面一截（Sky 自己摆）
        }
        Layer.place(boot, 0, 0, w, refH);
        super.onLayout(changed, l, t, r, b);
        if (ch && onSized != null) onSized.sized();
    }

    // ================= 触摸 =================
    private int pid = -1;
    private boolean mine;
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) mine = false;
        boolean r = super.dispatchTouchEvent(e);
        if (a == MotionEvent.ACTION_DOWN && !mine && r && app != null) app.uiTouched(e.getX() / u.dens, e.getY() / u.dens);
        return r;
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (app == null) return false;
        float d = u.dens;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mine = true;
                pid = e.getPointerId(0);
                if (app.gesture != null) app.cancelGesture(); // 上次的抬手事件丢了
                app.gStart(e.getX() / d, e.getY() / d);
                return true;
            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(pid);
                if (i >= 0) app.gMove(e.getX(i) / d, e.getY(i) / d);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                if (e.getPointerId(i) == pid) { app.gEnd(e.getX(i) / d, e.getY(i) / d); pid = -1; }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int i = e.findPointerIndex(pid);
                if (i >= 0) app.gEnd(e.getX(i) / d, e.getY(i) / d);
                pid = -1;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                app.cancelGesture();
                pid = -1;
                return true;
        }
        return true;
    }

    /** 开场黑幕：1.2 秒淡出后拿掉（同 .boot） */
    static final class Boot extends View {
        private long at = -1;
        Boot(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            long now = android.os.SystemClock.uptimeMillis();
            if (at < 0) at = now;
            float k = Ease.OUT.at(Ease.clamp((now - at) / 1200f, 0, 1));
            c.drawColor(Ease.alpha(0xff03061a, 1 - k));
            if (k < 1) postInvalidateOnAnimation();
            else post(() -> { if (getParent() instanceof Layer) ((Layer) getParent()).removeView(this); });
        }
    }
}
