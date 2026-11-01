package com.x3card.pet;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 绝对定位的容器（同 CSS 的 position: absolute）：每个孩子记着自己的框（像素），布局时照放；
 * 会动的东西用 translation / scale / alpha（只改 RenderNode 的属性，不重画内容），框只在大小变了时改。
 * 自己不接触摸（孩子要接的自己开 clickable）
 */
class Layer extends ViewGroup {
    static final class Lp extends ViewGroup.LayoutParams {
        int x, y;
        Lp() { super(0, 0); }
    }

    Layer(Context c) { super(c); setClipChildren(false); setClipToPadding(false); }

    /** 加一个孩子（框之后用 place 定） */
    <T extends View> T add(T v) { addView(v, new Lp()); return v; }

    /** 把孩子放到 (x, y)，大小 w × h（像素，取整）；大小变了才重新量 */
    static void place(View v, float x, float y, float w, float h) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (!(p instanceof Lp)) return;
        Lp l = (Lp) p;
        int X = Math.round(x), Y = Math.round(y), Wd = Math.max(0, Math.round(w)), Ht = Math.max(0, Math.round(h));
        if (l.x == X && l.y == Y && l.width == Wd && l.height == Ht && !v.isLayoutRequested()) return;
        l.x = X; l.y = Y; l.width = Wd; l.height = Ht;
        v.measure(MeasureSpec.makeMeasureSpec(Wd, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Ht, MeasureSpec.EXACTLY));
        v.layout(X, Y, X + Wd, Y + Ht);
    }

    @Override protected void onMeasure(int ws, int hs) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            ViewGroup.LayoutParams p = v.getLayoutParams();
            int w = p.width >= 0 ? p.width : MeasureSpec.getSize(ws), h = p.height >= 0 ? p.height : MeasureSpec.getSize(hs);
            v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            ViewGroup.LayoutParams p = v.getLayoutParams();
            int x = p instanceof Lp ? ((Lp) p).x : 0, y = p instanceof Lp ? ((Lp) p).y : 0;
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }
    }

    @Override protected LayoutParams generateDefaultLayoutParams() { return new Lp(); }
    @Override public boolean shouldDelayChildPressedState() { return false; }
}
