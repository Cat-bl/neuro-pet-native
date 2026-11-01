package com.x3card.pet;

import android.graphics.PointF;
import android.graphics.RectF;

import com.x3card.pet.mascot.MascotEngine;
import com.x3card.pet.mascot.Pack;

/**
 * 一只宠物（同网页版 pets 数组里的一格：引擎 + 卡片自己记的点了几下、什么时候喂过、打盹到几点……）。
 * 引擎是 MascotEngine 的子类：换行为都走 setBehavior，跑到目标后接的"坐下看着光标"换成站起来（同 main.js 的改写：一跑一停就在跑和蹲之间来回切，很突兀）
 */
final class Pet {
    final String pack;
    final PetLayer layer;
    final MascotEngine engine;
    PetLayer.PetView view;
    boolean removed;
    long tapAt, loveAt, napUntil, bornAt;
    int tapCount;
    // 撞墙看门狗
    double stallX = Double.NaN, stallY = Double.NaN;
    long stallSince;

    Pet(PetLayer layer, String pack, Pack p) {
        this.layer = layer;
        this.pack = pack;
        engine = new MascotEngine(layer.world, p) {
            @Override public void setBehavior(String name, String reason, int id) {
                String cur = behavior();
                if ("SitAndFaceMouse".equals(name) && "ChaseMouse".equals(cur)) { name = "StandUp"; id = ANY_ID; }
                super.setBehavior(name, reason, id);
                layer.wake();
            }
        };
    }

    boolean ready() { return !engine.isDestroyed(); }
    MascotEngine.Snapshot snapshot() { return removed ? null : engine.snapshot(); }
    /** 脚底（锚点）的位置，dp */
    PointF position() { return removed ? null : new PointF((float) engine.pageX(), (float) engine.pageY()); }
    boolean dragging() { return engine.isDragging(); }
    boolean setBehavior(String name) {
        try { engine.setBehavior(name, "pet-card"); return true; } catch (RuntimeException e) { return false; }
    }
    /** 图片在画面上的框（dp）；不显示时 null */
    RectF spriteRect() {
        double[] r = new double[4];
        return engine.spriteRect(r) ? new RectF((float) r[0], (float) r[1], (float) r[2], (float) r[3]) : null;
    }

    // ================= 手指 / 模拟拖拽（dp，时间戳同 World 的时钟；坐标取整，同 Chromium 74 的鼠标事件） =================
    void down(float x, float y, long t, boolean synthetic) { engine.pointerDown(Math.floor(x), Math.floor(y), 1, t); layer.wake(); }
    void move(float x, float y, long t) { layer.world.pointerMove(Math.floor(x), Math.floor(y), 1, t); layer.wake(); }
    void up(float x, float y, long t) { layer.world.pointerUp(Math.floor(x), Math.floor(y), 0, t); layer.wake(); }
    /** 被系统抢走（touchcancel）：在最后的位置松手 */
    void cancel(long t) { layer.world.pointerCancel(t); layer.wake(); }
    /** 只挪"光标"（让宠物跑过去：ChaseMouse 看它） */
    void cursor(float x, float y) { layer.world.pointerMove(x, y, 0, android.os.SystemClock.uptimeMillis()); }
}
