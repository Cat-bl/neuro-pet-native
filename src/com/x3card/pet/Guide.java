package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * 新手引导（同网页版 guide.js）：第一次打开（没记过 pet-guide）时，等宠物落地、开场看车况走完、没开着页面和设置，分几步介绍：
 * 两只宠物 → 时间（点了换表盘）→ 左右滑 / 底部导航切页 → 聊天键 → 齿轮 → 在车上时：宿主授权。用不上的步骤不讲。
 * 遮罩上留一个圆角亮框框住要讲的东西（外一圈呼吸光，换步骤时平滑移过去，开着时每帧跟着目标走），旁边一个说明气泡；夜里换深色。
 * 点哪里都是下一步，右上角关闭 = 跳过；看完或跳过就记住。看到一半被藏起来：不记成看过，回来从头讲
 */
final class Guide {
    private static final String KEY = "pet-guide";
    private static final Ease MOVE = new Ease(.2f, .8f, .2f, 1);
    private final App app;
    private final View view;
    private final ArrayList<Step> steps = new ArrayList<>();
    private int step = -1;
    private long t0 = SystemClock.uptimeMillis(), stepAt, startAt;
    private final Runnable waitR = this::waitCalm;

    interface Target { RectF rect(); }
    private static final class Step {
        final Target el; final float pad; final String title; final String text;
        Step(Target el, float pad, String title, String text) { this.el = el; this.pad = pad; this.title = title; this.text = text; }
    }

    Guide(Context c, App app, Layer host) {
        this.app = app;
        view = host.add(new GuideView(c));
        view.setVisibility(View.GONE);
        app.h.postDelayed(waitR, 500);
    }

    boolean seen() { return "1".equals(Store.get(KEY)); }
    private boolean petsOn() { return !"off".equals(app.settings.pets); }
    /** 可以开始了：宠物出场满几秒、开场看车况走完、没开着页面 / 设置 / 游戏 / 聊天输入栏 */
    private boolean calm() {
        if (petsOn() && (app.livePets().size() < 2 || SystemClock.uptimeMillis() - t0 < 6500)) return false;
        return !app.introActive() && app.page.name.isEmpty() && !app.page.busy && !app.panelOpen && !app.sheetOpen && !"game".equals(app.mode)
            && (app.ai == null || !app.ai.barOpen()) && !app.hidden;
    }
    private void waitCalm() {
        app.h.removeCallbacks(waitR);
        if (seen() || step >= 0) return;
        if (calm()) app.h.postDelayed(() -> { if (calm()) start(); else waitCalm(); }, 1200);
        else app.h.postDelayed(waitR, 500);
    }

    // ================= 要框的东西（dp） =================
    private RectF pets() {
        RectF r = null;
        for (Pet p : app.livePets()) {
            RectF b = p.spriteRect();
            if (b == null) continue;
            if (r == null) r = new RectF(b); else r.union(b);
        }
        return r;
    }
    private RectF clock() {
        float d = app.u.dens;
        if ("dial".equals(app.settings.clock)) {
            Layer b = app.clock.dialBox;
            float x = (b.getLeft() + b.getTranslationX()) / d, y = (b.getTop() + b.getTranslationY()) / d;
            return new RectF(x, y, x + b.getWidth() / d, y + b.getHeight() / d);
        }
        RectF r = app.clock.time.digitsRect();
        return r == null ? null : new RectF(r.left / d, r.top / d, r.right / d, r.bottom / d);
    }
    private RectF viewRect(View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        float d = app.u.dens;
        float x = (v.getLeft() + v.getTranslationX()) / d, y = (v.getTop() + v.getTranslationY()) / d;
        return new RectF(x, y, x + v.getWidth() / d, y + v.getHeight() / d);
    }
    private String pageNames() {
        ArrayList<String> n = new ArrayList<>();
        for (String id : app.PAGE_ORDER) if (!id.isEmpty()) n.add(id.equals("car") ? "车况" : id.equals("calendar") ? "日历" : "倒数日");
        return android.text.TextUtils.join("、", n);
    }

    void start() {
        app.h.removeCallbacks(waitR);
        if (step >= 0) return;
        if (!app.page.name.isEmpty()) app.closePage(true);
        app.setGuiding(true); // 齿轮、聊天键、导航先显示出来，再看哪些步骤用得上
        steps.clear();
        boolean onCar = app.host instanceof Uiplus;
        if (petsOn()) steps.add(new Step(this::pets, .08f, "这是 Neuro 和 Evil", "点它们会说话，双击抛高高，按住还能拖起来扔出去；长按空地放一块饼干，它们会跑去吃。往上划一起跳，往下划坐下。"));
        if (app.shown("time") && clock() != null) steps.add(new Step(this::clock, .1f, "点时间换表盘", "点一下大数字换成表盘，再点换回来。太阳、月亮、云朵也能点，有小惊喜。"));
        if (app.PAGE_ORDER.size() > 1 && app.shown("nav")) steps.add(new Step(() -> viewRect(app.ui.nav), .08f, "左右滑，换一页", "左右滑，或者点底部导航，切到" + pageNames() + "，它们会站在页面上陪你。"));
        if (app.ui.chat.getVisibility() == View.VISIBLE) steps.add(new Step(() -> viewRect(app.ui.chat), .12f, "和它们聊天",
            "点这里打字跟它们聊，先要在设置里填大模型的 API Key。输入栏左边的键选跟两只、Neuro 还是 Evil 说，话里叫名字也行。" + (app.car != null ? "它们还能帮你开空调、切歌、查车况。" : "")));
        steps.add(new Step(() -> viewRect(app.ui.gear), .12f, "设置", "“AI 聊天”里选大模型、填 API Key；宠物大小、场景、时钟样式、显示内容（不想看的都能关）也在这里。想再看一遍引导，设置最下面有按钮。"));
        if (onCar) steps.add(new Step(null, 0, "在车上用", "车况、音乐要先在宿主弹出的确认页里允许「读取数据」；想让它们帮你开空调、切歌，还要允许「执行操作」。"));
        startAt = SystemClock.uptimeMillis();
        view.setVisibility(View.VISIBLE);
        show(0, true);
        app.frames.add(track);
    }
    private final Frames.Client track = now -> { if (step < 0) return false; invalidateView(); return true; };
    private void invalidateView() { view.invalidate(); }
    private void show(int i, boolean first) {
        step = i;
        stepAt = SystemClock.uptimeMillis();
        holeFrom = first ? null : new RectF(holeNow);
        view.invalidate();
    }
    private void next() { if (step < steps.size() - 1) show(step + 1, false); else finish(); }
    private void finish() {
        step = -1;
        view.setVisibility(View.GONE);
        Store.set(KEY, "1");
        app.setGuiding(false);
    }
    /** 藏起来：先收起来（不记成看过），回来再等合适的时候从头讲 */
    void setHidden(boolean h) {
        if (h) { app.h.removeCallbacks(waitR); if (step >= 0) { step = -1; view.setVisibility(View.GONE); app.setGuiding(false); } }
        else { t0 = SystemClock.uptimeMillis(); waitCalm(); }
    }
    boolean back() { if (step < 0) return false; finish(); return true; }
    void theme() { view.invalidate(); }
    void layout() { Layer.place(view, 0, 0, app.u.px(app.u.W), app.u.px(app.u.H)); view.invalidate(); }

    // ================= 画 =================
    private RectF holeFrom;
    private final RectF holeNow = new RectF(), tipBox = new RectF(), skipBox = new RectF(), nextBox = new RectF();

    final class GuideView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), x = new Path();
        private final RectF r = new RectF();
        private float downX, downY;
        private final TextPaint bp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private StaticLayout title, body;
        private int layStep = -1;
        private float layRem, layW;
        GuideView(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            if (step < 0 || step >= steps.size()) return;
            long now = SystemClock.uptimeMillis();
            float d = app.u.dens, rem = app.u.px(app.u.rem), W = getWidth(), H = getHeight();
            boolean dark = "night".equals(app.currentScene);
            int acc = dark ? 0xff5d9bff : 0xff2f7cf6, mask = dark ? Ease.rgba(6, 8, 12, .7f) : Ease.rgba(18, 22, 30, .5f);
            int ink = dark ? 0xffeef1f6 : 0xff14171c, mute = dark ? Ease.rgba(238, 241, 246, .62f) : Ease.rgba(20, 23, 28, .6f);
            int top = dark ? 0xff262a34 : 0xffffffff, bottom = dark ? 0xff111317 : 0xfff1f3f6, line = dark ? Ease.rgba(255, 255, 255, .08f) : Ease.rgba(20, 23, 28, .08f);
            float fadeIn = Ease.EASE.at(Ease.clamp((now - startAt) / 450f, 0, 1));
            setAlpha(fadeIn);
            Step s = steps.get(step);
            RectF b = s.el != null ? s.el.rect() : null;
            float hx, hy, hw, hh;
            if (b != null && b.width() > 0) {
                float pad = s.pad * app.u.rem;
                hx = Math.max(4, (b.left - pad)) * d; hy = Math.max(4, (b.top - pad)) * d;
                hw = Math.min(W - 8 - hx, (b.width() + pad * 2) * d); hh = Math.min(H - 8 - hy, (b.height() + pad * 2) * d);
            } else { hx = W / 2; hy = H * .36f; hw = 0; hh = 0; }
            // 换步骤：亮框从上一个位置平滑移过来（0.45 秒）；同一步里跟着目标直接走
            float k = holeFrom == null ? 1 : MOVE.at(Ease.clamp((now - stepAt) / 450f, 0, 1));
            if (holeFrom != null && k < 1) {
                hx = Ease.lerp(holeFrom.left, hx, k); hy = Ease.lerp(holeFrom.top, hy, k);
                hw = Ease.lerp(holeFrom.width(), hw, k); hh = Ease.lerp(holeFrom.height(), hh, k);
            }
            holeNow.set(hx, hy, hx + hw, hy + hh);
            float rad = Math.min(hh / 2, .3f * rem);
            // 遮罩（亮框处挖空）
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, W, H, Path.Direction.CW);
            if (hw > 0) path.addRoundRect(holeNow, rad, rad, Path.Direction.CW);
            p.setStyle(Paint.Style.FILL);
            p.setColor(mask);
            c.drawPath(path, p);
            if (hw > 0) { // 亮框边 + 外面一圈呼吸光
                r.set(holeNow);
                r.inset(-.04f * rem, -.04f * rem);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(.03f * rem);
                p.setColor(acc);
                p.setShadowLayer(.2f * rem / 2, 0, 0, acc);
                c.drawRoundRect(r, rad + .04f * rem, rad + .04f * rem, p);
                p.clearShadowLayer();
                float ph = ((now - startAt) % 1800) / 1800f, sc = 1 + .12f * Ease.OUT.at(ph);
                c.save();
                c.scale(sc, sc, r.centerX(), r.centerY());
                p.setColor(Ease.alpha(acc, .8f * (1 - Ease.OUT.at(ph))));
                c.drawRoundRect(r, rad + .04f * rem, rad + .04f * rem, p);
                c.restore();
            }
            // 说明气泡
            float tw = Math.min(4.8f * rem, W - .4f * rem), padL = .3f * rem, padR = .2f * rem;
            if (layStep != step || layRem != rem || layW != tw) { // 字排好就留着，换步骤、换大小才重排
                layStep = step; layRem = rem; layW = tw;
                tp.setTypeface(Fonts.sysBold);
                tp.setTextSize(.34f * rem);
                tp.setLetterSpacing(.04f);
                title = StaticLayout.Builder.obtain(s.title, 0, s.title.length(), tp, Math.round(tw - padL - padR - .1f * rem)).build();
                bp.set(tp);
                bp.setTypeface(Fonts.sys);
                bp.setTextSize(.24f * rem);
                bp.setLetterSpacing(0);
                body = StaticLayout.Builder.obtain(s.text, 0, s.text.length(), bp, Math.round(tw - padL - padR - .1f * rem)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.55f * .24f * rem / Fonts.lineH(bp)).build();
            }
            float th = .22f * rem + .5f * rem + .02f * rem + title.getHeight() + .1f * rem + body.getHeight() + .2f * rem + .6f * rem + .24f * rem;
            float gap = .22f * rem, m = .2f * rem;
            boolean below = hy + hh + gap + th <= H - m;
            float ty = hw == 0 ? Math.max(m, (H - th) / 2) : below ? hy + hh + gap : Math.max(m, hy - gap - th);
            float tx = Math.max(m, Math.min(W - tw - m, hx + hw / 2 - tw / 2));
            float in = MOVE.at(Ease.clamp((now - stepAt) / 450f, 0, 1));
            c.save();
            c.translate(0, (1 - in) * .16f * rem);
            int alpha = Math.round(255 * in);
            tipBox.set(tx, ty, tx + tw, ty + th);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(tx, ty, tx + tw * .4f, ty + th, top, bottom, Shader.TileMode.CLAMP));
            p.setAlpha(alpha);
            p.setShadowLayer(.7f * rem / 2, 0, .24f * rem, dark ? Ease.rgba(0, 0, 0, .45f) : Ease.rgba(20, 30, 50, .24f));
            c.drawRoundRect(tipBox, .32f * rem, .32f * rem, p);
            p.clearShadowLayer();
            p.setShader(null);
            if (hw > 0) { // 箭头指向亮框
                float ax = Math.max(tx + .3f * rem, Math.min(tx + tw - .3f * rem, hx + hw / 2));
                float as = .18f * rem / 1.414f;
                x.reset();
                if (below) { x.moveTo(ax - as, ty + 1); x.lineTo(ax, ty - as + 1); x.lineTo(ax + as, ty + 1); }
                else { x.moveTo(ax - as, ty + th - 1); x.lineTo(ax, ty + th + as - 1); x.lineTo(ax + as, ty + th - 1); }
                x.close();
                p.setColor(below ? top : bottom);
                p.setAlpha(alpha);
                c.drawPath(x, p);
            }
            p.setShader(new LinearGradient(tx + .3f * rem, 0, tx + tw - .3f * rem, 0, new int[] { 0, acc, 0 }, null, Shader.TileMode.CLAMP));
            p.setAlpha(alpha);
            c.drawRect(tx + .3f * rem, ty, tx + tw - .3f * rem, ty + .03f * rem, p);
            p.setShader(null);
            float y = ty + .22f * rem;
            // 第几步 + 关闭
            tp.setTypeface(Fonts.sys); tp.setTextSize(.2f * rem); tp.setLetterSpacing(.18f); tp.setColor(mute); tp.setAlpha(Math.round(tp.getAlpha() * in));
            c.drawText((step + 1) + " / " + steps.size(), tx + padL, y + .25f * rem - (tp.descent() + tp.ascent()) / 2, tp);
            float xs = .5f * rem;
            skipBox.set(tx + tw - padR - xs, y, tx + tw - padR, y + xs);
            p.setColor(dark ? Ease.rgba(255, 255, 255, .08f) : Ease.rgba(20, 23, 28, .06f));
            p.setAlpha(Math.round(p.getAlpha() * in));
            c.drawOval(skipBox, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(.03f * rem); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(ink); p.setAlpha(alpha);
            float cx = skipBox.centerX(), cy = skipBox.centerY(), q = .1f * rem;
            c.drawLine(cx - q, cy - q, cx + q, cy + q, p); c.drawLine(cx - q, cy + q, cx + q, cy - q, p);
            p.setStyle(Paint.Style.FILL);
            y += .5f * rem + .02f * rem;
            tp.setTypeface(Fonts.sysBold); tp.setTextSize(.34f * rem); tp.setLetterSpacing(.04f); tp.setColor(ink); tp.setAlpha(alpha);
            c.save(); c.translate(tx + padL, y); title.draw(c); c.restore();
            y += title.getHeight() + .1f * rem;
            bp.setColor(mute); bp.setAlpha(Math.round(bp.getAlpha() * in));
            c.save(); c.translate(tx + padL, y); body.draw(c); c.restore();
            y += body.getHeight() + .2f * rem;
            // 进度点 + 下一步
            float dx = tx + padL;
            for (int i = 0; i < steps.size(); i++) {
                float w = i == step ? .3f * rem : .1f * rem;
                p.setColor(i == step ? acc : dark ? Ease.rgba(255, 255, 255, .22f) : Ease.rgba(20, 23, 28, .16f));
                p.setAlpha(Math.round(p.getAlpha() * in));
                c.drawRoundRect(dx, y + .25f * rem, dx + w, y + .35f * rem, .05f * rem, .05f * rem, p);
                dx += w + .08f * rem;
            }
            String nt = step == steps.size() - 1 ? "开始吧" : "下一步";
            tp.setTypeface(Fonts.sysBold); tp.setTextSize(.24f * rem); tp.setLetterSpacing(0);
            float nw = tp.measureText(nt) + .6f * rem, nh = .24f * rem * 1.3f + .2f * rem;
            nextBox.set(tx + tw - padR - .1f * rem - nw, y + .3f * rem - nh / 2, tx + tw - padR - .1f * rem, y + .3f * rem + nh / 2);
            p.setColor(acc);
            p.setAlpha(alpha);
            p.setShadowLayer(.16f * rem / 2, 0, .04f * rem, Ease.rgba(0, 0, 0, .18f));
            c.drawRoundRect(nextBox, nh / 2, nh / 2, p);
            p.clearShadowLayer();
            tp.setColor(0xffffffff); tp.setAlpha(alpha);
            c.drawText(nt, nextBox.centerX() - tp.measureText(nt) / 2, nextBox.centerY() - (tp.descent() + tp.ascent()) / 2, tp);
            c.restore();
        }

        /** 点哪里都是下一步（手指没怎么挪动就算），右上角关闭 = 跳过 */
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (step < 0) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = e.getX(); downY = e.getY(); }
            else if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(e.getX() - downX) <= 12 * app.u.dens && Math.abs(e.getY() - downY) <= 12 * app.u.dens) {
                RectF sb = new RectF(skipBox);
                sb.inset(-8, -8);
                if (sb.contains(e.getX(), e.getY())) finish(); else next();
            }
            return true;
        }
    }
}
