/*
 * 桌宠引擎（web-mascot 的 Java 移植），GPL-3.0-or-later，见 Js.java 开头
 */
package com.x3card.pet.mascot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一张卡片上的全部宠物共用的东西（网页版里 MascotEngine 的静态成员和 window 上的事件）：
 * 卡片尺寸、动画配置、时钟、随机数、互动广播、仿真参数，以及发给所有宠物的指针移动 / 抬起。
 * 只在一个线程里用（应用的绘制线程或主线程），不加锁。
 */
public final class World {
    /** 时间（毫秒，单调递增），对应 performance.now() */
    public interface Clock {
        double now();
    }

    /** [0, 1) 的随机数，对应 Math.random()；引擎用到随机数的先后同网页版 */
    public interface Random {
        double next();
    }

    final Clock clock;
    final Random rng;
    double viewW, viewH;
    AnimConfig.Resolved config = new AnimConfig.Resolved(null);
    final Broadcast.Manager broadcasts = new Broadcast.Manager();
    final LinkedHashSet<MascotEngine> live = new LinkedHashSet<>();
    final Scopes.MathObj math;
    int nextId = 1;
    /** 最后一次指针事件的位置（pointerCancel 用） */
    double lastPointerX, lastPointerY;

    // ---------------- 仿真参数（RuntimeOptions，默认值同网页版） ----------------
    /** 一个逻辑节拍的毫秒数 */
    public double tickMs = 40;
    public double gravity = 2, resistanceX = 0.05, resistanceY = 0.1;
    public double stayDurationTicks = 250, dragResistTicks = 250, dragResistAbortPx = 5;
    public double dragOffsetX = 0, dragOffsetY = 120;
    public double broadcastServeTimeoutTicks = 600;

    /**
     * @param clock 时间来源（指针事件的时间戳要和它同一个基准）
     * @param rng 随机数
     * @param width 卡片宽（页面像素，安卓上用 dp）
     * @param height 卡片高
     */
    public World(Clock clock, Random rng, double width, double height) {
        this.clock = clock;
        this.rng = rng;
        this.viewW = width;
        this.viewH = height;
        this.math = new Scopes.MathObj(rng);
    }

    /** 卡片尺寸变了（拖中缝）。之后过 200ms 调一次 {@link #resized()}，同网页版 resize 的防抖 */
    public void setViewport(double width, double height) {
        viewW = width;
        viewH = height;
    }

    public double width() { return viewW; }

    public double height() { return viewH; }

    /** 换动画配置（同 MascotEngine.setAnimationConfig），随时可以改：地板升降、右墙、缩放都马上生效 */
    public void setAnimationConfig(AnimConfig file) {
        config = new AnimConfig.Resolved(file);
    }

    /** 系统要求减少动效时套上配置里的 reducedMotion 那层 */
    public void setReducedMotion(boolean active) {
        config.setReducedMotion(active);
    }

    /** 活着的宠物（出生顺序） */
    public List<MascotEngine> engines() { return new ArrayList<>(live); }

    private MascotEngine[] snapshot() { return live.toArray(new MascotEngine[0]); }

    /**
     * 指针移动（对应 window 的 mousemove，发给所有宠物）。
     * 手指按着宠物拖动时 buttons 传 1；只是把"光标"挪到某处（让宠物跑过去、坐下看着它）时传 0。
     */
    public void pointerMove(double x, double y, int buttons, double t) {
        lastPointerX = x;
        lastPointerY = y;
        for (MascotEngine e : snapshot()) e.onWindowMove(x, y, buttons, t);
    }

    /** 指针抬起（对应 window 的 mouseup，发给所有宠物）：被拿着的宠物被扔出去，按在热区上没怎么动、没按久的算点击 */
    public void pointerUp(double x, double y, int buttons, double t) {
        lastPointerX = x;
        lastPointerY = y;
        for (MascotEngine e : snapshot()) e.onWindowUp(buttons, x, y, t);
    }

    /** 触摸被系统抢走（touchcancel）：同卡片的做法，在手指最后的位置松手 */
    public void pointerCancel(double t) {
        pointerUp(lastPointerX, lastPointerY, 0, t);
    }


    /** 卡片藏起来 / 回来（对应 visibilitychange）：藏起来时被拿着的宠物松手被扔出、按着的作废、全部暂停 */
    public void setHidden(boolean hidden) {
        for (MascotEngine e : snapshot()) e.onHidden(hidden);
    }

    /** resize 防抖之后（200ms）的处理：清缓存、宠物和目标夹回活动范围、脚下没了就掉下去 */
    public void resized() {
        for (MascotEngine e : snapshot()) e.onResized();
    }
}
