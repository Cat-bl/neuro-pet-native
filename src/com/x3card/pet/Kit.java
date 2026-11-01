package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置面板的小部件（同 style.css 的 .panel__h / .panel__row / .seg / .link / .show-tags / .ai-chips / .ai-field / .panel__acts / .panel__tip）。
 * 大小都按 rem 算（1rem = 卡片高 / 10.52），面板每次打开按当前大小重新建
 */
final class Kit {
    final Context c;
    final float rem;   // 像素
    static final int BLUE = 0xff2f7cf6, LINE = 0xffeeeeee, BORDER = 0xffd5d8de, MUTE = 0xff8a8f99;

    Kit(Context c, float remPx) { this.c = c; this.rem = remPx; }

    int px(float r) { return Math.round(r * rem); }
    TextView text(String s, float sizeRem, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeRem * rem);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }
    static GradientDrawable round(int fill, float radiusPx, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (stroke != 0) g.setStroke(1, stroke);
        return g;
    }
    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    /** 分组标题 */
    TextView header(String s) {
        TextView t = text(s, .16f, BLUE, Fonts.sysBold);
        t.setLetterSpacing(.06f);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = px(.12f); p.bottomMargin = px(.02f);
        t.setLayoutParams(p);
        return t;
    }
    /** 一行：左边说明、右边控件，底下一条细线 */
    LinearLayout row(CharSequence label, View right) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(.07f), 0, px(.07f));
        r.setBackground(bottomLine());
        TextView l = text("", .2f, 0xff555555, Fonts.sys);
        l.setText(label);
        l.setSingleLine(true);
        LinearLayout.LayoutParams lp = lp(0, -2);
        lp.weight = 1;
        lp.rightMargin = px(.2f);
        r.addView(l, lp);
        if (right != null) r.addView(right, lp(-2, -2));
        r.setLayoutParams(lp(-1, -2));
        return r;
    }
    android.graphics.drawable.Drawable bottomLine() {
        return new android.graphics.drawable.Drawable() {
            final Paint p = new Paint();
            @Override public void draw(Canvas cv) { p.setColor(LINE); android.graphics.Rect b = getBounds(); cv.drawRect(b.left, b.bottom - 1, b.right, b.bottom, p); }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }
    /** 蓝色文字键（同 .link） */
    TextView link(String s, Runnable click) {
        TextView t = text(s, .19f, BLUE, Fonts.sys);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(px(3));
        t.setPadding(px(.1f), px(.08f), 0, px(.08f));
        press(t, click);
        return t;
    }
    /** 按下缩一点，抬起时点（按在别处抬起不算） */
    static void press(View v, Runnable click) {
        v.setClickable(true);
        v.setOnTouchListener((w, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: w.setScaleX(.95f); w.setScaleY(.95f); break;
                case MotionEvent.ACTION_UP:
                    w.setScaleX(1); w.setScaleY(1);
                    if (e.getX() >= 0 && e.getY() >= 0 && e.getX() <= w.getWidth() && e.getY() <= w.getHeight() && click != null) w.post(click);
                    break;
                case MotionEvent.ACTION_CANCEL: w.setScaleX(1); w.setScaleY(1); break;
            }
            return true;
        });
    }

    /** 一组分段按钮（同 .seg）：点了回调值 */
    interface Pick { void pick(String v); }
    Seg seg(String[] labels, String[] values, String on, Pick pick) { return new Seg(this, labels, values, on, pick); }

    static final class Seg extends View {
        private final Kit k;
        final String[] labels, values;
        String on;
        private final Pick pick;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float[] x, w;
        private int pressed = -1;
        private final RectF r = new RectF();
        Seg(Kit k, String[] labels, String[] values, String on, Pick pick) {
            super(k.c);
            this.k = k; this.labels = labels; this.values = values; this.on = on; this.pick = pick;
            t.setTextSize(.19f * k.rem);
            t.setTypeface(Fonts.sys);
        }
        void set(String v) { on = v; invalidate(); }
        @Override protected void onMeasure(int ws, int hs) {
            x = new float[labels.length]; w = new float[labels.length];
            float cx = 0;
            for (int i = 0; i < labels.length; i++) {
                cx += .08f * k.rem;
                w[i] = Math.max(.6f * k.rem, t.measureText(labels[i]) + .28f * k.rem);
                x[i] = cx;
                cx += w[i];
            }
            setMeasuredDimension(Math.round(cx) + 1, Math.round(.48f * k.rem) + 2);
        }
        @Override protected void onDraw(Canvas c) {
            float h = .48f * k.rem;
            for (int i = 0; i < labels.length; i++) {
                boolean sel = values[i].equals(on);
                float s = i == pressed ? .95f : 1;
                c.save();
                c.scale(s, s, x[i] + w[i] / 2, 1 + h / 2);
                r.set(x[i] + .5f, 1.5f, x[i] + w[i] - .5f, h + .5f);
                p.setStyle(Paint.Style.FILL);
                p.setColor(sel ? BLUE : 0xffffffff);
                c.drawRoundRect(r, .12f * k.rem, .12f * k.rem, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1);
                p.setColor(sel ? BLUE : BORDER);
                c.drawRoundRect(r, .12f * k.rem, .12f * k.rem, p);
                t.setColor(sel ? 0xffffffff : 0xff333333);
                float tw = t.measureText(labels[i]);
                c.drawText(labels[i], x[i] + (w[i] - tw) / 2, 1 + h / 2 - (t.descent() + t.ascent()) / 2, t);
                c.restore();
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            int hit = -1;
            for (int i = 0; i < labels.length; i++) if (e.getX() >= x[i] && e.getX() <= x[i] + w[i]) hit = i;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: pressed = hit; invalidate(); return true;
                case MotionEvent.ACTION_UP:
                    int p0 = pressed;
                    pressed = -1; invalidate();
                    if (hit >= 0 && hit == p0 && pick != null) { final String v = values[hit]; post(() -> pick.pick(v)); }
                    return true;
                case MotionEvent.ACTION_CANCEL: pressed = -1; invalidate(); return true;
            }
            return true;
        }
    }

    /** 自动换行的一排（同 flex-wrap） */
    static final class Flow extends ViewGroup {
        final int gapX, gapY;
        Flow(Context c, int gapX, int gapY) { super(c); this.gapX = gapX; this.gapY = gapY; }
        @Override protected void onMeasure(int ws, int hs) {
            int maxW = MeasureSpec.getSize(ws), x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                v.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                if (x > 0 && x + v.getMeasuredWidth() > maxW) { x = 0; y += lineH + gapY; lineH = 0; }
                x += v.getMeasuredWidth() + gapX;
                lineH = Math.max(lineH, v.getMeasuredHeight());
            }
            setMeasuredDimension(maxW, y + lineH);
        }
        @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
            int maxW = r - l, x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                if (x > 0 && x + v.getMeasuredWidth() > maxW) { x = 0; y += lineH + gapY; lineH = 0; }
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
                x += v.getMeasuredWidth() + gapX;
                lineH = Math.max(lineH, v.getMeasuredHeight());
            }
        }
    }
    /** 一个标签键（同 .show-tags button / .ai-chips button）：on 蓝底白字 */
    TextView chip(String s, boolean on, boolean grayOff, float heightRem, Runnable click) {
        TextView t = text(s, .19f, on ? 0xffffffff : grayOff ? 0xff9aa0aa : 0xff444444, Fonts.sys);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(px(heightRem > .46f ? .18f : .16f), 0, px(heightRem > .46f ? .18f : .16f), 0);
        t.setHeight(px(heightRem));
        t.setMinimumHeight(px(heightRem));
        t.setBackground(round(on ? BLUE : 0xffffffff, px(heightRem) / 2f, on ? BLUE : BORDER));
        press(t, click);
        return t;
    }
    /** 小按钮（同 .ai-mini） */
    TextView mini(String s, boolean warn, Runnable click) {
        TextView t = text(s, .19f, warn ? 0xffd9534f : BLUE, Fonts.sys);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(px(.18f), 0, px(.18f), 0);
        t.setHeight(px(.48f));
        t.setBackground(round(0xffffffff, px(.12f), warn ? 0xffd9534f : BORDER));
        press(t, click);
        return t;
    }
    /** 输入框（同 .ai-field input） */
    EditText field(String hint, boolean password) {
        EditText e = new EditText(c);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, .2f * rem);
        e.setTextColor(0xff222222);
        e.setHintTextColor(0xff9aa0aa);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setPadding(px(.14f), 0, px(.14f), 0);
        e.setHeight(px(.52f));
        e.setIncludeFontPadding(false);
        e.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        GradientDrawable bg = round(0xffffffff, px(.12f), BORDER);
        e.setBackground(bg);
        e.setOnFocusChangeListener((v, f) -> bg.setStroke(1, f ? BLUE : BORDER));
        return e;
    }
    /** 一行橙色 / 蓝色的大按钮（同 .panel__acts）：最后一个蓝色 */
    LinearLayout acts(String[] labels, Runnable[] clicks) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            TextView t = text(labels[i], .19f, 0xffffffff, Fonts.sysBold);
            t.setGravity(Gravity.CENTER);
            boolean last = i == labels.length - 1;
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, last ? new int[] { 0xff6cc3ff, 0xff3d8cff } : new int[] { 0xffffb057, 0xffff8a3d });
            g.setCornerRadius(px(.14f));
            t.setBackground(g);
            press(t, clicks[i]);
            LinearLayout.LayoutParams p = lp(0, px(.58f));
            p.weight = 1;
            if (!last) p.rightMargin = px(.1f);
            r.addView(t, p);
        }
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = px(.08f); p.bottomMargin = px(.04f);
        r.setLayoutParams(p);
        return r;
    }
    /** 灰色小字说明（同 .panel__tip / .panel__note） */
    TextView tip(CharSequence s, float sizeRem, float lineK) {
        TextView t = text("", sizeRem, MUTE, Fonts.sys);
        t.setText(s);
        t.setLineSpacing(0, lineK);
        return t;
    }
    /** 一组说明 + 小字（同 .ai-lb small） */
    LinearLayout label(String main, List<CharSequence> smalls, List<TextView> out) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(text(main, .2f, 0xff555555, Fonts.sys));
        if (smalls != null) for (CharSequence s : smalls) {
            TextView t = text("", .18f, MUTE, Fonts.sys);
            t.setText(s);
            t.setLineSpacing(0, 1.45f);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = px(.02f);
            l.addView(t, p);
            if (out != null) out.add(t);
        }
        return l;
    }
    static List<CharSequence> list(CharSequence... s) { ArrayList<CharSequence> l = new ArrayList<>(); for (CharSequence x : s) if (x != null) l.add(x); return l; }
}
