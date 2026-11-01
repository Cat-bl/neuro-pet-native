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

import java.util.ArrayList;
import java.util.List;

/**
 * 宠物头上的说话气泡（同 .bubble）：白底（Evil 深底），圆角，底边中间一个小尾巴（放在宠物侧面时没有尾巴）；
 * 弹出时从 0.6 倍弹到原样，收起时缩到 0.8 倍淡出。
 * 带按钮的气泡（同 .bubble.ctl）：上面一句话，下面一排按钮——音乐的圆形图标键、大模型的选项键（蓝底 / 灰底）；
 * 模型说的话右下角挂一个小"AI"标；模型在想时是三个跳动的点
 */
final class Bubble extends View {
    /** 气泡里的一个键：图标键（icon 是 24×24 的路径）或文字键 */
    static final class Btn {
        final String act, label, icon;
        final boolean big, ghost;
        Btn(String act, String label, String icon, boolean big, boolean ghost) { this.act = act; this.label = label; this.icon = icon; this.big = big; this.ghost = ghost; }
        static Btn icon(String act, String path, boolean big) { return new Btn(act, null, path, big, false); }
        static Btn text(String act, String label, boolean ghost) { return new Btn(act, label, null, false, ghost); }
    }
    interface Press { void press(Bubble b, String act); }

    final Object pet;
    final boolean evil;
    private final U u;
    String text;
    List<Btn> row;
    boolean gen, dots, ctl;
    Press onPress;
    /** 气泡说完的时刻 */
    long until;
    Runnable onGone;
    boolean side, gone;
    long bornAt, goneAt;
    /** 定位：底边中点在 (bx, by)；框（dp，同网页版 b.box） */
    float l, t, r, b;
    float wDp, hDp;
    /** 四周留出的边（px）：投影和右下角的"AI"小标伸出气泡，画在这圈里（硬件层不画到 View 外面） */
    float mg;

    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG), bp = new TextPaint(Paint.ANTI_ALIAS_FLAG), ap = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), ic = new Paint(Paint.ANTI_ALIAS_FLAG);
    private StaticLayout lay;
    private float padX, padY, rad, tail;
    private final ArrayList<RectF> keys = new ArrayList<>();
    private String pressed;
    private long pressedAt;
    private final Path path = new Path(), icp = new Path();
    private final Matrix m = new Matrix();
    private final RectF box = new RectF();

    Bubble(Context c, U u, Object pet, boolean evil) {
        super(c);
        this.u = u; this.pet = pet; this.evil = evil;
        tp.setTypeface(Fonts.sys);
        bp.setTypeface(Fonts.sys);
        ap.setTypeface(Fonts.sysBold);
        ap.setColor(0xffffffff);
        bornAt = SystemClock.uptimeMillis();
        setPivotY(0);
    }

    /** 量尺寸（dp）：字和按钮排好，返回 true 表示变了 */
    boolean measureNow() {
        float rem = u.rem, d = u.dens;
        tp.setTextSize(.24f * rem * d);
        bp.setTextSize(.22f * rem * d);
        padX = (ctl ? .16f : .18f) * rem * d;
        padY = (ctl ? .1f : .09f) * rem * d;
        rad = .2f * rem * d;
        mg = Math.round(.2f * rem * d);
        tail = .08f * rem * d;
        float maxW = 3.9f * rem * d - 2 * padX;
        float textW = 0;
        String s = text == null ? "" : text;
        if (!s.isEmpty()) {
            float natural = 0;
            for (String ln : s.split("\n")) natural = Math.max(natural, Layout.getDesiredWidth(ln, tp));
            int w = (int) Math.ceil(Math.min(maxW, natural));
            lay = StaticLayout.Builder.obtain(s, 0, s.length(), tp, Math.max(1, w)).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.35f * tp.getTextSize() / Math.max(1, Fonts.lineH(tp))).setIncludePad(false).build();
            textW = w;
        } else lay = null;
        // 按钮一排：图标键 .56rem（中间大的 .66rem），文字键高 .5rem；文字键放不下自动折行
        keys.clear();
        float rowW = 0, rowH = 0;
        if (row != null && !row.isEmpty()) {
            float x = 0, y = 0, lineH = 0, gapIcon = .06f * rem * d, maxRow = 3.9f * rem * d - 2 * padX;
            ArrayList<float[]> sizes = new ArrayList<>();
            for (Btn k : row) {
                float bw, bh, mx;
                if (k.icon != null) { bw = bh = (k.big ? .66f : .56f) * rem * d; mx = gapIcon; }
                else { bw = bp.measureText(k.label) + .4f * rem * d; bh = .5f * rem * d; mx = .05f * rem * d; }
                sizes.add(new float[] { bw, bh, mx });
            }
            ArrayList<float[]> pos = new ArrayList<>();
            float[] rowWs = new float[row.size() + 1];
            int line = 0;
            for (float[] sz : sizes) {
                float need = sz[0] + 2 * sz[2];
                if (x > 0 && x + need > maxRow) { rowWs[line++] = x; x = 0; y += lineH; lineH = 0; }
                pos.add(new float[] { x + sz[2], y, sz[0], sz[1], line });
                x += need;
                lineH = Math.max(lineH, sz[1] + (row.get(0).icon == null ? .06f * rem * d : 0));
            }
            rowWs[line] = x;
            rowH = y + lineH;
            for (int i = 0; i <= line; i++) rowW = Math.max(rowW, rowWs[i]);
            for (int i = 0; i < pos.size(); i++) {
                float[] p = pos.get(i);
                float lw = rowWs[(int) p[4]];
                keys.add(new RectF(p[0] + (rowW - lw) / 2, p[1], p[0] + (rowW - lw) / 2 + p[2], p[1] + p[3]));
            }
        }
        if (dots) { rowW = Math.max(rowW, .46f * rem * d); rowH = Math.max(rowH, .24f * rem * d); }
        float th = lay != null ? lay.getHeight() : 0;
        float gapRow = th > 0 && (rowH > 0) ? (row != null && !row.isEmpty() && row.get(0).icon == null ? .02f : .08f) * rem * d : 0;
        float W = Math.max(textW, rowW) + 2 * padX, H = th + gapRow + rowH + 2 * padY;
        float nw = W / d, nh = H / d;
        boolean ch = Math.abs(nw - wDp) > .5f || Math.abs(nh - hDp) > .5f;
        wDp = nw; hDp = nh;
        rowTop = padY + th + gapRow;
        rowLeft = (W - rowW) / 2;
        invalidate();
        return ch;
    }
    private float rowTop, rowLeft;

    /** 弹出 / 收起的缩放、透明度；返回还在动 */
    boolean anim(long now) {
        if (gone) {
            float k = Ease.clamp((now - goneAt) / 250f, 0, 1), e = Ease.IN.at(k);
            setAlpha(1 - e);
            float s = 1 - .2f * e;
            setScaleX(s); setScaleY(s);
            return k < 1;
        }
        float k = Ease.clamp((now - bornAt) / 350f, 0, 1), e = Ease.POP15.at(k);
        setAlpha(Ease.clamp(e, 0, 1));
        float s = .6f + .4f * e;
        setScaleX(s); setScaleY(s);
        boolean busy = k < 1;
        if (dots) { invalidate(); busy = true; }
        if (pressed != null && now - pressedAt < 160) busy = true;
        return busy;
    }

    @Override protected void onDraw(Canvas c) {
        int sc = c.save();
        c.translate(mg, mg);
        body(c);
        c.restoreToCount(sc);
    }
    private void body(Canvas c) {
        float d = u.dens, W = getWidth() - 2 * mg, H = getHeight() - 2 * mg - (side ? 0 : tail);
        int bgc = evil ? 0xff2b1d2f : 0xffffffff, fg = evil ? 0xffffd6e0 : 0xff222222;
        box.set(0, 0, W, H);
        bg.setColor(bgc);
        Fonts.shadow(bg, .04f * u.rem * d, .16f * u.rem * d, Ease.rgba(0, 0, 0, .15f));
        path.reset();
        path.addRoundRect(box, rad, rad, Path.Direction.CW);
        if (!side) { // 尾巴
            float cx = W / 2;
            path.moveTo(cx - tail, H - 1);
            path.lineTo(cx + tail, H - 1);
            path.lineTo(cx, H + tail);
            path.close();
        }
        c.drawPath(path, bg);
        bg.clearShadowLayer();
        if (lay != null) {
            tp.setColor(fg);
            c.save();
            c.translate((W - lay.getWidth()) / 2f, padY);
            lay.draw(c);
            c.restore();
        }
        long now = SystemClock.uptimeMillis();
        if (row != null) for (int i = 0; i < row.size() && i < keys.size(); i++) {
            Btn k = row.get(i);
            RectF kr = keys.get(i);
            float x = rowLeft + kr.left, y = rowTop + kr.top, w = kr.width(), h = kr.height();
            float s = k.act.equals(pressed) && now - pressedAt < 160 ? (k.icon != null ? .88f : .95f) : 1;
            c.save();
            c.scale(s, s, x + w / 2, y + h / 2);
            if (k.icon != null) {
                int kb = k.big ? (evil ? 0xffe0455f : 0xffff6b9d) : (evil ? Ease.rgba(255, 214, 224, .12f) : 0xfff1f3f8);
                int kf = k.big ? 0xffffffff : (evil ? 0xffffd6e0 : 0xff2a2f3d);
                ic.setColor(kb);
                c.drawCircle(x + w / 2, y + h / 2, w / 2, ic);
                float half = w / 2 - (k.big ? .18f : .15f) * u.rem * d;
                m.setScale(half / 12, half / 12);
                m.postTranslate(x + w / 2 - half, y + h / 2 - half);
                SvgPath.of(k.icon).transform(m, icp);
                ic.setColor(kf);
                c.drawPath(icp, ic);
            } else {
                int kb = k.ghost ? (evil ? Ease.rgba(255, 214, 224, .14f) : 0xffeef0f4) : (evil ? 0xffe0455f : 0xff2f7cf6);
                int kf = k.ghost ? (evil ? 0xffffd6e0 : 0xff555555) : 0xffffffff;
                ic.setColor(kb);
                float yy = y + .06f * u.rem * d;
                c.drawRoundRect(x, yy, x + w, yy + h, h / 2, h / 2, ic);
                bp.setColor(kf);
                float tw = bp.measureText(k.label);
                c.drawText(k.label, x + (w - tw) / 2, yy + h / 2 - (bp.descent() + bp.ascent()) / 2, bp);
            }
            c.restore();
        }
        if (dots) { // 三个点：每个 1.2 秒一亮，依次错开 0.2 秒
            float r0 = .05f * u.rem * d, cy = rowTop + .12f * u.rem * d, gap = .18f * u.rem * d, cx = W / 2 - gap;
            for (int i = 0; i < 3; i++) {
                float ph = ((now - bornAt - i * 200) % 1200 + 1200) % 1200 / 1200f;
                float a = ph < .3f ? .25f + .65f * Ease.IN_OUT.at(ph / .3f) : ph < .6f ? .9f - .65f * Ease.IN_OUT.at((ph - .3f) / .3f) : .25f;
                ic.setColor(Ease.alpha(fg, a));
                c.drawCircle(cx + i * gap, cy, r0, ic);
            }
        }
        if (gen) { // "AI" 小标：中心落在右下圆角弧线的 45° 点上
            float bw = .26f * u.rem * d, bh = .18f * u.rem * d, ox = .059f * u.rem * d;
            float cx = W - ox, cy = H - ox;
            ic.setColor(evil ? 0xffe0455f : 0xffff6b9d);
            Fonts.shadow(ic, .01f * u.rem * d, .04f * u.rem * d, Ease.rgba(0, 0, 0, .2f));
            c.drawRoundRect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, bh / 2, bh / 2, ic);
            ic.clearShadowLayer();
            ap.setTextSize(.12f * u.rem * d);
            float tw = ap.measureText("AI");
            c.drawText("AI", cx - tw / 2, cy - (ap.descent() + ap.ascent()) / 2, ap);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (gone || row == null || row.isEmpty()) return false;
        float x = e.getX() - mg, y = e.getY() - mg;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && (x < 0 || y < 0 || x > getWidth() - 2 * mg || y > getHeight() - 2 * mg)) return false; // 按在四周留的边上：交给下面
        String act = null;
        for (int i = 0; i < row.size() && i < keys.size(); i++) {
            RectF kr = keys.get(i);
            float pad = .04f * u.rem * u.dens;
            if (x >= rowLeft + kr.left - pad && x <= rowLeft + kr.right + pad && y >= rowTop + kr.top - pad && y <= rowTop + kr.bottom + .06f * u.rem * u.dens + pad) act = row.get(i).act;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { if (act == null) return true; pressed = act; pressedAt = SystemClock.uptimeMillis(); invalidate(); return true; }
        if (e.getActionMasked() == MotionEvent.ACTION_UP && act != null && act.equals(pressed) && onPress != null) { final String a = act; post(() -> onPress.press(this, a)); }
        return true;
    }
}
