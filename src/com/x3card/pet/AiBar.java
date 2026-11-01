package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/**
 * 聊天输入栏（同 .ai-bar）：白色胶囊，左边对象键（两只 / Neuro / Evil），中间输入框，右边"聊天记录"键和"发送"。
 * 平时浮在底部（可见区域的底边，键盘弹出时跟着上来），从下面升起；开着聊天室时放在聊天室最底下（不播升起、阴影淡一点、没有"聊天记录"键）
 */
final class AiBar extends ViewGroup {
    static final String LOG = "M21 6h-2v9H6v2c0 .55.45 1 1 1h11l4 4V7c0-.55-.45-1-1-1zm-4 6V3c0-.55-.45-1-1-1H3c-.55 0-1 .45-1 1v14l4-4h10c.55 0 1-.45 1-1z";

    private final Ai ai;
    private final U u;
    final EditText input;
    private final Key to, log, send;
    private boolean inRoom;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    AiBar(Context c, Ai ai) {
        super(c);
        this.ai = ai;
        u = ai.app.u;
        setWillNotDraw(false);
        setClipChildren(false);
        setClickable(true); // 按在输入栏上不算点到画面
        to = new Key(c, 't', ai::nextTo);
        log = new Key(c, 'l', ai::openRoom);
        send = new Key(c, 's', this::submit);
        input = new EditText(c);
        input.setBackground(null);
        input.setPadding(0, 0, 0, 0);
        input.setSingleLine(true);
        input.setTextColor(0xff222222);
        input.setHintTextColor(0xff9aa0aa);
        input.setIncludeFontPadding(false);
        input.setGravity(android.view.Gravity.CENTER_VERTICAL);
        input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(300) });
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        // 横屏车机上输入法不全屏（不然整个画面被输入法的编辑框盖住）
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        // 输入法的"发送"键不一定是 SEND：回车、完成、前往都当发送
        input.setOnEditorActionListener((v, id, ev) -> {
            if (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER) { if (ev.getAction() == KeyEvent.ACTION_DOWN) submit(); return true; }
            if (id == EditorInfo.IME_ACTION_SEND || id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_NULL) { submit(); return true; }
            return false;
        });
        input.setOnFocusChangeListener((v, f) -> { ai.app.card.typing = f; }); // 输入时卡片保持原来的高度（键盘弹出不缩小）
        addView(to);
        addView(input);
        addView(log);
        addView(send);
        setVisibility(GONE);
    }

    private void submit() {
        String t = input.getText().toString();
        input.setText("");
        ai.submit(t);
    }
    boolean focused() { return input.isFocused(); }
    void focusInput() {
        input.requestFocus();
        post(() -> {
            if (!input.isFocused()) input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, 0);
        });
    }
    /** 收起键盘（输入框失去焦点） */
    void blur() {
        if (!input.isFocused()) return;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        ai.app.card.typing = false;
    }
    void setTo(String who) {
        to.who = who;
        to.label = "both".equals(who) ? "两只" : "Neuron".equals(who) ? "Neuro" : "Evil";
        input.setHint("both".equals(who) ? "和 Neuro、Evil 说点什么…" : "只和 " + to.label + " 说…");
        requestLayout();
        to.invalidate();
    }
    void setRoom(boolean on) {
        if (inRoom == on) return;
        inRoom = on;
        log.setVisibility(on ? GONE : VISIBLE);
        if (on) { animate().cancel(); setAlpha(1); setTranslationY(0); }
        requestLayout();
        invalidate();
    }
    /** 从下面升起（同 aiBarIn） */
    void animIn() {
        float rem = u.px(u.rem);
        animate().cancel();
        setAlpha(0);
        setTranslationY(.3f * rem);
        animate().alpha(1).translationY(0).setDuration(300).setInterpolator(t -> Ease.POP10.at(t)).start();
    }

    @Override protected void onMeasure(int ws, int hs) {
        int W = MeasureSpec.getSize(ws), H = MeasureSpec.getSize(hs);
        float rem = u.px(u.rem);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, .26f * rem);
        int keyH = Math.round(.6f * rem), logS = Math.round(.56f * rem);
        to.measure(MeasureSpec.makeMeasureSpec(Math.round(to.want(rem)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(keyH, MeasureSpec.EXACTLY));
        send.measure(MeasureSpec.makeMeasureSpec(Math.round(send.want(rem)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(keyH, MeasureSpec.EXACTLY));
        log.measure(MeasureSpec.makeMeasureSpec(logS, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(logS, MeasureSpec.EXACTLY));
        int side = Math.round(.1f * rem), gap = Math.round(.14f * rem);
        int used = 2 * side + to.getMeasuredWidth() + send.getMeasuredWidth() + 2 * gap + (log.getVisibility() == VISIBLE ? logS + Math.round(.1f * rem) : 0);
        input.measure(MeasureSpec.makeMeasureSpec(Math.max(0, W - used), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(H, MeasureSpec.EXACTLY));
        setMeasuredDimension(W, H);
    }
    @Override protected void onLayout(boolean ch, int l, int t, int rr, int b) {
        float rem = u.px(u.rem);
        int H = b - t, x = Math.round(.1f * rem), gap = Math.round(.14f * rem);
        x = put(to, x, H);
        x += gap;
        x = put(input, x, H);
        x += gap;
        if (log.getVisibility() == VISIBLE) { x = put(log, x, H); x += Math.round(.1f * rem); }
        put(send, x, H);
    }
    private static int put(View v, int x, int H) {
        int w = v.getMeasuredWidth(), h = v.getMeasuredHeight(), y = (H - h) / 2;
        v.layout(x, y, x + w, y + h);
        return x + w;
    }
    @Override protected void onDraw(Canvas c) {
        float rem = u.px(u.rem), h = getHeight();
        r.set(0, 0, getWidth(), h);
        bg.setColor(Ease.rgba(255, 255, 255, .97f));
        if (inRoom) Fonts.shadow(bg, .02f * rem, .12f * rem, Ease.rgba(0, 0, 0, .12f));
        else Fonts.shadow(bg, .06f * rem, .26f * rem, Ease.rgba(0, 0, 0, .28f));
        c.drawRoundRect(r, .4f * rem, .4f * rem, bg);
        bg.clearShadowLayer();
    }

    /** 输入栏里的键：t 对象键（圆点 + 名字）、l 聊天记录（圆形图标）、s 发送。按下缩一点；不抢输入框的焦点 */
    final class Key extends View {
        final char kind;
        String who = "both", label = "两只";
        private final Runnable click;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Matrix m = new Matrix();
        Key(Context c, char kind, Runnable click) { super(c); this.kind = kind; this.click = click; }
        float want(float rem) {
            p.setTypeface(Fonts.sysBold);
            if (kind == 't') { p.setTextSize(.23f * rem); return .14f * rem + .26f * rem + .08f * rem + p.measureText(label) + .2f * rem; }
            p.setTextSize(.24f * rem);
            return .6f * rem + p.measureText("发送");
        }
        @Override protected void onDraw(Canvas cv) {
            float rem = u.px(u.rem), w = getWidth(), h = getHeight();
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            if (kind == 'l') {
                p.setColor(0xffeef1f6);
                cv.drawCircle(w / 2, h / 2, w / 2, p);
                float pad = .13f * rem, k = (w - 2 * pad) / 24;
                m.setScale(k, k);
                m.postTranslate(pad, pad);
                SvgPath.of(LOG).transform(m, path);
                p.setColor(0xff4a5568);
                cv.drawPath(path, p);
                return;
            }
            if (kind == 's') {
                p.setColor(Kit.BLUE);
                cv.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
                p.setColor(0xffffffff);
                p.setTypeface(Fonts.sysBold);
                p.setTextSize(.24f * rem);
                float tw = p.measureText("发送");
                cv.drawText("发送", (w - tw) / 2, h / 2 - (p.descent() + p.ascent()) / 2, p);
                return;
            }
            boolean n = "Neuron".equals(who), e = "Eviling".equals(who);
            p.setColor(n ? 0xffffe6ef : e ? 0xff2b1d2f : 0xffeef1f6);
            cv.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
            float d = .26f * rem, cx = .14f * rem + d / 2, cy = h / 2;
            p.setColor(0xffffffff);
            cv.drawCircle(cx, cy, d / 2 + .02f * rem, p);
            if (n || e) { p.setColor(n ? 0xffff8fb4 : 0xffe0455f); cv.drawCircle(cx, cy, d / 2, p); }
            else { // 两只：左粉右黑各一半
                p.setColor(0xffff8fb4);
                cv.drawArc(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2, 90, 180, true, p);
                p.setColor(0xff3a2140);
                cv.drawArc(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2, -90, 180, true, p);
            }
            p.setColor(n ? 0xffc2185b : e ? 0xffffd6e0 : 0xff333333);
            p.setTypeface(Fonts.sysBold);
            p.setTextSize(.23f * rem);
            cv.drawText(label, .14f * rem + d + .08f * rem, h / 2 - (p.descent() + p.ascent()) / 2, p);
        }
        @Override public boolean onTouchEvent(MotionEvent ev) {
            float s = kind == 'l' ? .92f : .95f;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: setScaleX(s); setScaleY(s); return true;
                case MotionEvent.ACTION_UP:
                    setScaleX(1); setScaleY(1);
                    if (ev.getX() >= 0 && ev.getY() >= 0 && ev.getX() <= getWidth() && ev.getY() <= getHeight()) post(click);
                    return true;
                case MotionEvent.ACTION_CANCEL: setScaleX(1); setScaleY(1); return true;
            }
            return true;
        }
    }
}
