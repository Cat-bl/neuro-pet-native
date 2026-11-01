package com.x3card.pet;

/** 一个会过渡的数（同 CSS 的 transition）：to() 从现在的值出发、按曲线走到目标；get(现在) 取当前值 */
final class Tw {
    private float from, to;
    private long t0;
    private int ms, delay;
    private Ease ease = Ease.EASE;

    Tw(float v) { from = to = v; }

    /** 直接跳到 v（不过渡） */
    void set(float v) { from = to = v; ms = 0; }
    void to(float target, int ms, Ease e, long now) { to(target, ms, 0, e, now); }
    /** 过渡到 target（已经在去 target 的路上就不重来） */
    void to(float target, int ms, int delay, Ease e, long now) {
        if (target == to && (this.ms > 0 || from == to)) return;
        from = get(now);
        to = target;
        t0 = now;
        this.ms = Math.max(1, ms);
        this.delay = delay;
        ease = e;
        if (ms <= 0) { from = target; this.ms = 0; }
    }
    float get(long now) {
        if (ms <= 0) return to;
        long t = now - t0 - delay;
        if (t <= 0) return from;
        if (t >= ms) { ms = 0; from = to; return to; }
        return from + (to - from) * ease.at(t / (float) ms);
    }
    float target() { return to; }
    boolean moving(long now) { if (ms <= 0) return false; if (now - t0 - delay >= ms) { ms = 0; from = to; return false; } return true; }
}
