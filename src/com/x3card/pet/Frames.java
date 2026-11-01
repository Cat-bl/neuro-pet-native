package com.x3card.pet;

import android.view.Choreographer;

import java.util.ArrayList;

/**
 * 逐帧回调（主线程，跟着屏幕刷新）：有东西在动时才跑，没人要帧就停（同网页版"有东西在动时才 requestAnimationFrame"）。
 * 每个用户带一个最高帧率：30 帧的隔一帧叫一次（慢慢变的东西——星星闪、云飘——不用 60 帧）。
 * frame(现在毫秒) 返回 false 表示不再要帧（会被移出）；要再动时重新 add。
 */
final class Frames implements Choreographer.FrameCallback {
    interface Client { boolean frame(long now); }

    private static final class E { Client c; int fps; long last; }
    private final ArrayList<E> list = new ArrayList<>();
    private final ArrayList<E> run = new ArrayList<>();
    private boolean posted, paused;

    /** 要帧（已经在要了就只更新帧率） */
    void add(Client c, int fps) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).c == c) { list.get(i).fps = fps; post(); return; }
        E e = new E();
        e.c = c; e.fps = fps;
        list.add(e);
        post();
    }
    void add(Client c) { add(c, 60); }
    void remove(Client c) { for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).c == c) list.remove(i); }
    boolean has(Client c) { for (int i = 0; i < list.size(); i++) if (list.get(i).c == c) return true; return false; }
    /** 页面藏起来：停帧（回来接着跑，别的东西照常要帧） */
    void pause(boolean p) {
        paused = p;
        if (p) { if (posted) Choreographer.getInstance().removeFrameCallback(this); posted = false; }
        else post();
    }
    void clear() { list.clear(); }

    private void post() {
        if (posted || paused || list.isEmpty()) return;
        posted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private long last60 = -1000, last30 = -1000, last20 = -1000;
    @Override public void doFrame(long nanos) {
        posted = false;
        long now = android.os.SystemClock.uptimeMillis();
        // 同一档帧率的都在同一帧里跑（不然两个 30 帧的错开半拍，合起来就是 60 帧）；高刷屏上最多 60 帧。留几毫秒余量，60Hz 屏上不会因为抖动漏帧
        boolean t60 = now - last60 >= 12, t30 = now - last30 >= 28, t20 = now - last20 >= 45;
        if (!t60) { post(); return; }
        last60 = now;
        if (t30) last30 = now;
        if (t20) last20 = now;
        run.clear();
        run.addAll(list);
        for (int i = 0; i < run.size(); i++) {
            E e = run.get(i);
            if (!list.contains(e)) continue;
            if (e.fps <= 20 ? !t20 : e.fps <= 30 && !t30) continue;
            e.last = now;
            boolean more;
            try { more = e.c.frame(now); } catch (RuntimeException ex) { android.util.Log.w(Uiplus.TAG, "逐帧回调出错", ex); more = false; }
            if (!more) list.remove(e);
        }
        run.clear();
        post();
    }
}
