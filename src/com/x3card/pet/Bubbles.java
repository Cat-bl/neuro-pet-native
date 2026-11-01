package com.x3card.pet;

import android.graphics.PointF;

import java.util.ArrayList;
import java.util.List;

/**
 * 气泡管理（同 main.js 的 say / sayCtl / place / unstack）：气泡跟着宠物头顶走；头顶放不下（站在页面面板上时）放到身体侧面；
 * 两只站得近同时说话时，后放的挪到前一个上面。宠物不动时气泡也不动（位置没变不写）
 */
final class Bubbles implements Frames.Client {
    private final App app;
    private final Layer host;
    private final ArrayList<Bubble> list = new ArrayList<>();
    private final ArrayList<Bubble> leaving = new ArrayList<>();
    /** 最近一句话说完的时刻（墙上时间） */
    long talkEnd;
    /** 带按钮的气泡被点了（音乐键、大模型的选项）：交给创建它的人 */
    Bubble.Press onPress;

    Bubbles(App app, Layer host) { this.app = app; this.host = host; }

    int count() { return list.size(); }
    List<Bubble> all() { return list; }

    void say(Pet p, String text, int ms) {
        if (!"on".equals(app.settings.bubble) || p == null || !p.ready() || p.removed || text == null || text.isEmpty()) return;
        if (ctlOf(p) != null) return; // 这只头上是带按钮的气泡：别的话先不说，免得把按钮顶掉
        dismiss(p);
        Bubble b = make(p, text, null, ms <= 0 ? 3200 : ms, false);
        b.measureNow();
        start(b);
    }
    /** 带按钮的气泡：上面一句话，下面一排键；返回句柄（消失时调 b.onGone） */
    Bubble sayCtl(Pet p, String text, List<Bubble.Btn> row, int ms) {
        if (!"on".equals(app.settings.bubble) || p == null || !p.ready() || p.removed) return null;
        dismiss(p);
        Bubble b = make(p, text == null ? "" : text, row, ms, true);
        b.measureNow();
        start(b);
        return b;
    }
    private Bubble make(Pet p, String text, List<Bubble.Btn> row, int ms, boolean ctl) {
        Bubble b = new Bubble(host.getContext(), app.u, p, p.pack.equals("Eviling"));
        b.text = text;
        b.row = row;
        b.ctl = ctl;
        b.until = app.now() + ms;
        b.onPress = (bb, act) -> { if (onPress != null) onPress.press(bb, act); };
        return b;
    }
    private void start(Bubble b) {
        host.add(b);
        b.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
        list.add(b);
        talkEnd = Math.max(talkEnd, b.until);
        place(b);
        put(b);
        app.frames.add(this, 60);
    }
    /** 改带按钮气泡的话 / 按钮（null 不改），并从现在起再留 ms；气泡已经没了返回 false */
    boolean ctlSet(Bubble b, String text, List<Bubble.Btn> row, int ms) {
        if (b == null || !list.contains(b)) return false;
        boolean ch = false;
        if (text != null && !text.equals(b.text)) { b.text = text; ch = true; }
        if (row != null && row != b.row) { b.row = row; ch = true; }
        if (ch) b.measureNow();
        if (ms > 0) { b.until = app.now() + ms; talkEnd = Math.max(talkEnd, b.until); }
        app.frames.add(this, 60);
        return true;
    }
    boolean alive(Bubble b) { return b != null && list.contains(b); }
    void ctlClose(Bubble b) { if (b != null && list.contains(b)) fade(b); }
    Bubble ctlOf(Pet p) { for (Bubble b : list) if (b.pet == p && b.ctl) return b; return null; }
    void dismiss(Pet p) { for (Bubble b : new ArrayList<>(list)) if (b.pet == p) fade(b); }
    void clear() { for (Bubble b : new ArrayList<>(list)) fade(b); }
    private void fade(Bubble b) {
        list.remove(b);
        if (b.onGone != null) { Runnable f = b.onGone; b.onGone = null; f.run(); }
        b.gone = true;
        b.goneAt = android.os.SystemClock.uptimeMillis();
        leaving.add(b);
        app.frames.add(this, 60);
    }

    /** 气泡的位置（dp）：头顶，放不下就放侧面；x 夹在卡片里 */
    private void place(Bubble b) {
        Pet pet = (Pet) b.pet;
        PointF p = pet.position();
        if (p == null) return;
        float W = app.u.W, w = b.wDp, h = b.hDp + (b.side ? 0 : .08f * app.u.rem), sz = App.SPRITE * app.petScale();
        float x = p.x, y = p.y - sz - 10;
        boolean side = y < h + 6;
        if (side) {
            int dir = p.x + sz * 0.3f + w + 6 <= W ? 1 : -1;
            x = p.x + dir * (sz * 0.3f + w / 2);
            y = Math.max(h + 6, p.y - sz * 0.45f);
        }
        if (side != b.side) { b.side = side; b.invalidate(); }
        x = App.clamp(x, w / 2 + 6, W - w / 2 - 6);
        float hh = b.hDp + (side ? 0 : .08f * app.u.rem);
        b.l = x - w / 2; b.r = x + w / 2; b.t = y - hh; b.b = y;
    }
    /** 两只同时说话、站得近时：后放的挪到前一个上面 */
    private void unstack() {
        for (int i = 1; i < list.size(); i++) {
            Bubble a = list.get(i);
            for (int j = 0; j < i; j++) {
                Bubble c = list.get(j);
                if (a.r < c.l - 4 || a.l > c.r + 4 || a.b < c.t - 4 || a.t > c.b + 4) continue;
                float dy = a.b - (c.t - 6);
                a.t -= dy; a.b -= dy;
            }
        }
    }
    private void put(Bubble b) {
        float d = app.u.dens;
        float w = b.wDp * d, h = (b.hDp + (b.side ? 0 : .08f * app.u.rem)) * d;
        Layer.place(b, Math.round(b.l * d) - b.mg, Math.round(b.t * d) - b.mg, w + 2 * b.mg, h + 2 * b.mg);
        b.setPivotX(b.mg + w / 2); b.setPivotY(b.mg + h);
    }

    @Override public boolean frame(long now) {
        long t = app.now();
        for (Bubble b : new ArrayList<>(list)) {
            if (t > b.until || ((Pet) b.pet).removed) fade(b);
            else place(b);
        }
        unstack();
        boolean busy = false;
        for (Bubble b : list) { put(b); busy |= b.anim(now); }
        for (int i = leaving.size() - 1; i >= 0; i--) {
            Bubble b = leaving.get(i);
            if (!b.anim(now)) { host.removeView(b); leaving.remove(i); } else busy = true;
        }
        // 宠物在动时气泡跟着走：有气泡就一直要帧（宠物站着时位置没变，put 不改东西，开销很小）
        return busy || !list.isEmpty() || !leaving.isEmpty();
    }
}
