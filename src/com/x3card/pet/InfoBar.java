package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 宿主连上了、还没给读数据的授权时，顶上一张小卡片（车况、音乐要宿主授权；宠物、时钟照常）：
 * "申请授权"弹宿主的确认页，"以后再说"这次不再提（设置里还有一行"车机数据 › 申请授权"）
 */
final class InfoBar extends View {
    private final App app;
    private boolean dismissed;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF(), ask = new RectF(), later = new RectF();
    private int pressed;

    InfoBar(Context c, App app) { super(c); this.app = app; setVisibility(GONE); }

    void sync() { setVisibility(!dismissed && app.infoNeeded() ? VISIBLE : GONE); invalidate(); }
    void layout() { Layer.place(this, 0, 0, app.u.px(app.u.W), app.u.px(app.u.H)); invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float rem = app.u.px(app.u.rem), W = getWidth();
        float w = Math.min(5.6f * rem, W * .92f), h = 1.5f * rem, x = (W - w) / 2, y = .3f * rem;
        box.set(x, y, x + w, y + h);
        p.setColor(0xf7ffffff);
        p.setShadowLayer(.3f * rem / 2, 0, .06f * rem, 0x40000000);
        c.drawRoundRect(box, .24f * rem, .24f * rem, p);
        p.clearShadowLayer();
        p.setTypeface(Fonts.sysBold);
        p.setTextSize(.26f * rem);
        p.setColor(0xff222222);
        c.drawText("要读取车况和音乐", x + .3f * rem, y + .5f * rem, p);
        p.setTypeface(Fonts.sys);
        p.setTextSize(.19f * rem);
        p.setColor(0xff8a8f99);
        c.drawText("在宿主弹出的确认页里允许；宠物和时钟照常", x + .3f * rem, y + .86f * rem, p);
        float bh = .5f * rem, bw = 1.5f * rem, by = y + h - bh - .2f * rem;
        ask.set(x + w - .3f * rem - bw, by, x + w - .3f * rem, by + bh);
        later.set(ask.left - .2f * rem - bw, by, ask.left - .2f * rem, by + bh);
        p.setColor(0xff2f7cf6);
        c.drawRoundRect(ask, bh / 2, bh / 2, p);
        p.setColor(0xffeef0f4);
        c.drawRoundRect(later, bh / 2, bh / 2, p);
        p.setTextSize(.2f * rem);
        p.setColor(0xffffffff);
        c.drawText("申请授权", ask.centerX() - p.measureText("申请授权") / 2, ask.centerY() - (p.descent() + p.ascent()) / 2, p);
        p.setColor(0xff555555);
        c.drawText("以后再说", later.centerX() - p.measureText("以后再说") / 2, later.centerY() - (p.descent() + p.ascent()) / 2, p);
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { pressed = ask.contains(x, y) ? 1 : later.contains(x, y) ? 2 : 0; return box.contains(x, y); }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            if (pressed == 1 && ask.contains(x, y)) app.authorize();
            if (pressed == 2 && later.contains(x, y)) { dismissed = true; sync(); }
        }
        return true;
    }
}
