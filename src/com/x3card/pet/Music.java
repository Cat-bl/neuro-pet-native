package com.x3card.pet;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 音乐控制（同网页版 music.js）：点歌名胶囊，它原地展开成小播放器（圆封面 + 歌名歌手 + 上一首 / 暂停 / 下一首），5 秒没碰收回；
 * 有歌的时候点宠物，它头上冒出带这三个键的气泡，按了它接话，7 秒没碰收起。两个入口互相接手。
 * 小剧情：连着点同一只（第三下罢工）、两只来回点（抢 → 抢回来 → 吵架 → 讲和轮流）、连着切歌（嫌你挑 → 讨论你的品味 → 认输）、
 * 切走又切回、连按上一首、来回暂停播放（跟着打拍子）、切完新歌报歌名、点了胶囊冷落宠物（吃醋 → 回来哄）。
 * 按键经 Car 交给宿主（media.play / pause / previous / next），宿主没有的方法不出键；"显示内容"里关掉"音乐按钮"后胶囊点不开、点宠物照常说话
 */
final class Music {
    private static final int MINI_MS = 5000, CTL_MS = 7000;
    static final String PREV = "M6 5h2.4v14H6z M19 5.6v12.8L9.4 12z", NEXT = "M15.6 5H18v14h-2.4z M5 5.6v12.8L14.6 12z",
        PAUSE = "M7 5h3.6v14H7z M13.4 5H17v14h-3.6z", PLAY = "M8 5.2v13.6L19 12z";
    private final App app;
    private final Car M;

    private String mode = "";  // '' / 'mini' 小播放器展开 / 'pet' 宠物头上的按钮气泡
    private Pet pet, jilted, sulk;
    private Bubble bub;
    private int pokes;
    private boolean acted, scriptBusy;
    private final ArrayList<Long> swaps = new ArrayList<>(), skips = new ArrayList<>(), toggles = new ArrayList<>();
    private long truceUntil, jiltedAt, sulkUntil, waitTrack, sideAt, miniAt, lastCmdAt;
    private int prevRun;
    private String lastCmd = "";
    private final Runnable closeMiniR = this::closeMini;
    private Runnable scriptR, newSongR;

    Music(App app, Car car) { this.app = app; this.M = car; }

    private long now() { return app.now(); }
    private int recent(ArrayList<Long> arr, long ms) { long t = now(); while (!arr.isEmpty() && t - arr.get(0) > ms) arr.remove(0); return arr.size(); }
    boolean enabled() { return app.shown("mcBtn") && (M.can("play") || M.can("pause") || M.can("next") || M.can("previous")); }
    private boolean petsOn() { return !"off".equals(app.settings.pets) && "on".equals(app.settings.bubble); }
    private boolean alive() { return "pet".equals(mode) && bub != null && app.bubbles.alive(bub); }
    private String line(Pet p, String key, Map<String, Object> v) { return p != null ? app.line(p, key, v) : ""; }
    private Map<String, Object> vars(Pet p) {
        Pet o = p == null ? null : app.other(p);
        Car.Song s = M.song();
        return App.vars("other", o != null ? (o.pack.equals("Neuron") ? "Neuro" : "Evil") : "", "title", s != null ? Car.shortText(s.title, 12) : "", "artist", s != null ? Car.shortText(s.artist, 8) : "");
    }
    /** 三个键（宿主没有的方法不出）；中间那个按现在的状态是暂停或播放 */
    private List<Bubble.Btn> buttons(Car.Song s) {
        ArrayList<Bubble.Btn> l = new ArrayList<>();
        String tog = s != null && s.playing ? "pause" : "play";
        if (M.can("previous")) l.add(Bubble.Btn.icon("mc:previous", PREV, false));
        if (M.can(tog)) l.add(Bubble.Btn.icon("mc:toggle", tog.equals("pause") ? PAUSE : PLAY, true));
        if (M.can("next")) l.add(Bubble.Btn.icon("mc:next", NEXT, false));
        return l;
    }

    // ================= 歌名胶囊 / 小播放器 =================
    Pills.Pill pill() {
        Car.Song s = M.song();
        if (s == null) return null;
        boolean ok = enabled();
        String text = "♪ " + Car.shortText(s.title, 12) + (!s.artist.isEmpty() ? " · " + Car.shortText(s.artist, 6) : "");
        if (!ok) return s.playing ? new Pills.Pill("pill-music", text) : null; // 不能点：只在放歌时显示
        if (!s.playing && !s.known) return null;
        if ("mini".equals(mode)) {
            Pills.Pill p = new Pills.Pill("pill-music", text);
            p.kind = 2;
            p.title = Car.shortText(s.title, 16);
            String sub = Car.shortText(!s.artist.isEmpty() ? s.artist : s.app, 10);
            p.sub = sub + (s.playing ? "" : (!sub.isEmpty() ? " · " : "") + "已暂停");
            p.cover = s.cover;
            p.playing = s.playing;
            p.paused = !s.playing;
            String tog = s.playing ? "pause" : "play";
            p.canPrev = M.can("previous"); p.canToggle = M.can(tog); p.canNext = M.can("next");
            p.in = now() - miniAt < 300;
            return p;
        }
        Pills.Pill p = new Pills.Pill("pill-music" + (s.playing ? "" : " paused"), text + (s.playing ? "" : " · 已暂停"));
        p.kind = 1;
        return p;
    }
    /** 胶囊、小播放器被点了 */
    void pillTap(String act) {
        if ("open".equals(act)) openMini();
        else if ("mini".equals(act)) closeMini();
        else press(act, "mini");
    }
    private void openMini() {
        if (!enabled() || M.song() == null) return;
        Pet p = alive() ? pet : null;
        if (p != null) { closePet(); jealous(p); } // 宠物递着按钮呢，你却点了胶囊
        mode = "mini";
        miniAt = now();
        app.refreshExtra();
        keepMini();
        if (p == null && petsOn() && app.rnd.nextFloat() < 0.35f) side("mcMini", null, true);
    }
    private void keepMini() { app.h.removeCallbacks(closeMiniR); app.h.postDelayed(closeMiniR, MINI_MS); }
    private void closeMini() {
        app.h.removeCallbacks(closeMiniR);
        if (!"mini".equals(mode)) return;
        mode = "";
        app.refreshExtra();
    }

    // ================= 宠物头上的按钮气泡 =================
    private void openPet(Pet p, String text) {
        Car.Song s = M.song();
        if (s == null) return;
        if ("mini".equals(mode)) closeMini();
        final Bubble b = app.sayCtl(p, text, buttons(s), CTL_MS);
        if (b == null) return;
        mode = "pet"; pet = p; bub = b; pokes = 0; acted = false;
        app.react(p, text);
        b.onGone = () -> gone(p, b);
    }
    private void gone(Pet p, Bubble b) {
        if (bub != b) return;
        boolean idle = !acted && now() >= b.until - 100;
        mode = ""; pet = null; bub = null;
        if (idle && app.rnd.nextFloat() < 0.5f) app.later(() -> app.say(p, line(p, "mcIdle", null), 2600), 350);
    }
    private void closePet() { Bubble b = bub; bub = null; pet = null; if ("pet".equals(mode)) mode = ""; app.ctlClose(b); }
    private void setText(String text) { if (text != null && !text.isEmpty() && bub != null) { app.ctlSet(bub, text, null, CTL_MS); app.react(pet, text); } }

    /** 点了宠物：有歌就递上带按钮的气泡（返回 true 表示接手了） */
    boolean tapPet(Pet p) {
        Car.Song s = M.song();
        long t = now();
        if (!enabled() || !petsOn() || s == null || (!s.playing && !s.known) || scriptBusy) return false;
        if (sulk == p && t < sulkUntil) return false; // 刚被戳烦了：照常说话
        if (alive()) { if (pet == p) poke(p); else swap(p); return true; }
        if ("mini".equals(mode)) { openPet(p, line(p, "mcFromMini", vars(p))); return true; }
        if (jilted == p && t - jiltedAt < 30000) { jilted = null; openPet(p, line(p, "mcReturn", vars(p))); return true; }
        boolean first = Store.get("pet-mc") == null;
        Store.set("pet-mc", "1");
        openPet(p, line(p, first ? "mcFirst" : s.playing ? "mcOpen" : "mcOpenPaused", vars(p)));
        return true;
    }
    /** 气泡开着又点同一只：第一下指按钮，第二下不耐烦，第三下罢工 */
    private void poke(Pet p) {
        pokes++;
        if (pokes < 3) { setText(line(p, pokes == 1 ? "mcPoke1" : "mcPoke2", vars(p))); return; }
        final String text = line(p, "mcPoke3", vars(p));
        closePet();
        sulk = p; sulkUntil = now() + 10000;
        app.later(() -> { app.say(p, text, 3000); app.behave(p, p.pack.equals("Neuron") ? "Wink" : "StandBlush"); }, 120);
    }
    /** 气泡开着点了另一只：抢过去 → 抢回来 → 吵一架 → 越抢越凶 → 讲和、之后轮流 */
    private void swap(final Pet p) {
        final Pet old = pet;
        long t = now();
        swaps.add(t);
        final int n = recent(swaps, 45000);
        closePet();
        if (t < truceUntil) {
            truceUntil = t + 60000;
            openPet(p, line(p, "mcTurn", vars(p)));
            later(() -> app.say(old, line(old, "mcTurnOther", vars(old)), 2600), 900);
            return;
        }
        if (n == 3) { script("fight", () -> openPet(p, line(p, "mcTake2", vars(p)))); return; }
        if (n >= 5) { truceUntil = t + 60000; swaps.clear(); script("truce", () -> openPet(p, line(p, "mcTurn", vars(p)))); return; }
        openPet(p, line(p, n == 2 ? "mcTakeBack" : n == 4 ? "mcTake2" : "mcTake", vars(p)));
        later(() -> { app.say(old, line(old, n == 4 ? "mcLost2" : "mcLost", vars(old)), 2600); app.react(old, ""); }, 700);
    }
    private void jealous(final Pet p) {
        jilted = p; jiltedAt = now();
        later(() -> app.say(p, line(p, "mcJealous", vars(p)), 2800), 200);
    }

    // ================= 按键 =================
    /** 气泡里的键被按了 */
    void bubblePress(String act) { press(act.substring(3), "pet"); }
    private void press(String act, final String from) {
        Car.Song s = M.song();
        if (s == null) return;
        String cmd = "toggle".equals(act) ? (s.playing ? "pause" : "play") : act;
        if (!M.can(cmd)) return;
        M.hush(9000); // 接下来几秒的换歌、暂停由这里接话
        if ("mini".equals(from)) keepMini(); else acted = true;
        M.send(cmd, code -> { if (code != null) { refresh(); say(from, "mcFail", true); } });
        refresh();
        react(cmd, from);
    }
    private void refresh() {
        app.refreshExtra();
        if (alive()) app.ctlSet(bub, null, buttons(M.song()), 0);
    }
    private static final Pattern MUST = Pattern.compile("^mc(Picky|Back|Repeat|Beat)");
    private void react(String cmd, String from) {
        long t = now();
        String key;
        if ("next".equals(cmd)) {
            skips.add(t);
            int n = recent(skips, 40000);
            waitTrack = t;
            prevRun = 0;
            if (n == 6 && petsOn()) { remember(cmd, t); closePet(); script("picky", null); return; }
            key = n >= 10 ? "mcPicky3" : n >= 6 ? "mcPicky2" : n >= 3 ? "mcPicky" : "mcNext";
        } else if ("previous".equals(cmd)) {
            prevRun = "previous".equals(lastCmd) && t - lastCmdAt < 15000 ? prevRun + 1 : 1;
            waitTrack = t;
            key = "next".equals(lastCmd) && t - lastCmdAt < 12000 ? "mcBack" : prevRun >= 3 ? "mcRepeat" : "mcPrev";
        } else {
            toggles.add(t);
            int n = recent(toggles, 8000);
            if (n == 6 && petsOn()) { remember(cmd, t); closePet(); script("beat", null); return; }
            key = n >= 3 ? "mcBeat" : "pause".equals(cmd) ? "mcPause" : "mcPlay";
            if (n >= 3 && app.play != null) for (Pet p : app.livePets()) app.play.notes(p, 2); // 跟着打拍子
        }
        remember(cmd, t);
        say(from, key, MUST.matcher(key).find());
    }
    private void remember(String cmd, long t) { lastCmd = cmd; lastCmdAt = t; }
    private void say(String from, String key, boolean must) {
        if ("pet".equals(from) && alive()) { setText(line(pet, key, vars(pet))); return; }
        side(key, null, must);
    }
    private void side(String key, Pet p, boolean must) {
        long t = now();
        if (!petsOn() || scriptBusy || (!must && (t - sideAt < 3500 || app.rnd.nextFloat() > 0.45f))) return;
        Pet q = p != null ? p : app.randomPet();
        if (q == null) return;
        sideAt = t;
        String text = line(q, key, vars(q));
        app.say(q, text, 2800);
        app.react(q, text);
    }
    /** 两只的小剧情（PET_MC_SCRIPTS[key]，洗牌轮流），演完再 done */
    private void script(String key, final Runnable done) {
        final Pet n = app.randomPet("Neuron"), e = app.randomPet("Eviling");
        JSONArray list = app.lines.mc.optJSONArray(key);
        if (n == null || e == null || list == null || list.length() == 0 || scriptBusy || !petsOn()) { if (done != null) done.run(); return; }
        Object sc = app.fresh("mc." + key, list);
        if (!(sc instanceof JSONArray)) { if (done != null) done.run(); return; }
        final JSONArray s = (JSONArray) sc;
        scriptBusy = true;
        app.pauseChat(s.length() * 2500L + 4000);
        final int[] i = { 0 };
        scriptR = new Runnable() {
            @Override public void run() {
                JSONArray it = s.optJSONArray(i[0]++);
                if (it == null) { scriptBusy = false; if (done != null) done.run(); return; }
                Pet who = "N".equals(it.optString(0)) ? n : e;
                app.say(who, it.optString(1), 2400);
                String b = it.optString(2, "");
                if (!b.isEmpty()) app.behave(who, b); else app.react(who, it.optString(1));
                app.h.postDelayed(this, 2500);
            }
        };
        scriptR.run();
    }
    private void later(Runnable fn, long ms) { app.h.postDelayed(() -> { if (!app.hidden) fn.run(); }, ms); }

    // ================= 宿主推来新状态（Car 调） =================
    void changed(boolean newTrack) {
        if (alive()) app.ctlSet(bub, null, buttons(M.song()), 0);
        if (!newTrack || waitTrack == 0 || now() - waitTrack > 8000) return;
        waitTrack = 0;
        if (newSongR != null) app.h.removeCallbacks(newSongR);
        if (app.rnd.nextFloat() < 0.65f) {
            newSongR = () -> {
                if (M.song() == null || app.hidden) return;
                if (alive()) setText(line(pet, "mcNewSong", vars(pet)));
                else side("mcNewSong", null, true);
            };
            app.h.postDelayed(newSongR, 1400);
        }
    }
    boolean busy() { return !mode.isEmpty() || scriptBusy; }
    /** 藏起来：都收起 */
    void hide() {
        closeMini();
        closePet();
        if (scriptR != null) app.h.removeCallbacks(scriptR);
        if (newSongR != null) app.h.removeCallbacks(newSongR);
        scriptBusy = false;
    }
}
