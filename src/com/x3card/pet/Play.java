package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 互动玩法（同网页版 play.js）：喂饼干、接饼干游戏、踢球、随机事件（纸飞机 / 蝴蝶 / 热气球 / 彩虹 / UFO / 流星雨 / 饼干雨 / 打盹 / 唱歌 / 对话）、
 * 点场景彩蛋（太阳 / 月亮 / 云）、滑动手势。
 * 饼干、球在道具层（PropsView），天上飞的在事件层（EventsView），UFO 在宠物上面一层；有东西在动时才逐帧
 */
final class Play implements Frames.Client {
    private final App app;
    private final U u;
    final PropsView props;
    final EventsView events;
    final UfoView ufoV;
    final Game gameV;

    Play(Context c, App app, Layer propsHost, Layer eventsHost, Layer topHost, Layer gameHost) {
        this.app = app;
        u = app.u;
        props = propsHost.add(new PropsView(c));
        events = eventsHost.add(new EventsView(c));
        ufoV = topHost.add(new UfoView(c));
        gameV = gameHost.add(new Game(c));
        gameV.setVisibility(View.GONE);
        scheduleEvent((long) app.rand(45000, 70000));
    }
    float W() { return u.W; }
    float H() { return u.H; }
    float rem() { return u.rem; }
    float spriteH() { return App.SPRITE * app.petScale(); }
    Pet sayPack(String pack, String key, int ms) { Pet p = app.randomPet(pack); if (p != null) { String t = app.line(p, key); app.say(p, t, ms); app.react(p, t); } return p; }
    Pet sayAny(String key, int ms) { return sayAny(key, ms, null); }
    Pet sayAny(String key, int ms, java.util.Map<String, Object> vars) { Pet p = app.randomPet(); if (p != null) { String t = app.line(p, key, vars); app.say(p, t, ms); app.react(p, t); } return p; }

    void layout() {
        float w = u.px(u.W), h = u.px(u.H);
        Layer.place(props, 0, 0, w, h);
        Layer.place(events, 0, 0, w, h);
        Layer.place(ufoV, 0, 0, w, h);
        Layer.place(gameV, 0, 0, w, h);
    }

    // ================= 统一的动画循环（有东西在动时才跑） =================
    interface Flyer { boolean update(long now); void draw(Canvas c); }
    private final ArrayList<Flyer> flyers = new ArrayList<>();
    private long lastT;
    private void startLoop() { lastT = SystemClock.uptimeMillis(); app.frames.add(this, 60); }
    @Override public boolean frame(long now) {
        float dt = Math.min(0.05f, (now - lastT) / 1000f);
        lastT = now;
        updateCookies(dt);
        updateBall(dt);
        updateGame();
        for (int i = flyers.size() - 1; i >= 0; i--) if (!flyers.get(i).update(now)) flyers.remove(i);
        props.invalidate();
        events.invalidate();
        ufoV.invalidate();
        return !cookies.isEmpty() || !dying.isEmpty() || ball != null || ballFade > 0 || (game != null && game.running) || !flyers.isEmpty() || ufo != null;
    }

    // ================= 饼干 =================
    static final class Cookie { float x, y, vy, r; String state = "fall"; long born; boolean game, gold, dead; String kill; long killAt; int killMs; }
    private final ArrayList<Cookie> cookies = new ArrayList<>(), dying = new ArrayList<>();
    float cookieR() { return rem() * 0.21f; }
    private Cookie makeCookie(float x, float y, boolean isGame, boolean gold) {
        float r = cookieR();
        Cookie c = new Cookie();
        c.x = App.clamp(x, r + 2, W() - r - 2); c.y = y; c.r = r; c.born = app.now(); c.game = isGame; c.gold = gold;
        cookies.add(c);
        startLoop();
        return c;
    }
    private void killCookie(Cookie c, String cls, int ms) {
        if (c.dead) return;
        c.dead = true;
        c.kill = cls; c.killAt = SystemClock.uptimeMillis(); c.killMs = ms;
        cookies.remove(c);
        dying.add(c);
    }
    /** 放一块饼干：从 (x, y) 落到地面，宠物跑过去吃 */
    Cookie dropCookie(float x, float y) {
        int n = 0;
        for (Cookie c : cookies) if (!c.game) n++;
        if (n >= 4) return null;
        return makeCookie(x, Math.min(y, app.floorY() - cookieR() - 1), false, false);
    }
    private long chaseAt;
    private final java.util.Map<String, Long> eatSayAt = new java.util.HashMap<>();
    private void updateCookies(float dt) {
        float fy = app.floorY(), sc = app.petScale();
        List<Pet> pets = app.livePets();
        Cookie feed = null;
        for (Cookie c : new ArrayList<>(cookies)) {
            if (c.dead) continue;
            if (c.game) {
                c.y += c.vy * dt;
                boolean caught = false;
                for (Pet p : pets) {
                    PointF q = app.pos(p);
                    if (q != null && Math.abs(c.x - q.x) < 46 * sc && c.y + c.r >= q.y - 112 * sc && c.y <= q.y) { gameScore(c, p); caught = true; break; }
                }
                if (caught) continue;
                if (c.y + c.r >= fy) killCookie(c, "crumble", 500);
                continue;
            }
            if ("fall".equals(c.state)) {
                c.vy += 2600 * dt;
                c.y += c.vy * dt;
                if (c.y >= fy - c.r) {
                    c.y = fy - c.r;
                    if (c.vy > 380) c.vy = -c.vy * 0.32f; else { c.vy = 0; c.state = "rest"; }
                }
            }
            if (app.now() - c.born > 25000) { killCookie(c, "fade", 600); continue; }
            if (feed == null) feed = c;
            for (Pet p : pets) {
                PointF q = app.pos(p);
                if (q != null && Math.abs(c.x - q.x) < 40 * sc && c.y > fy - spriteH() * 0.8f && app.onFloor(p)) { eat(c, p); break; }
            }
        }
        if (feed != null && game == null && app.now() - chaseAt > 700) {
            chaseAt = app.now();
            final Cookie target = feed;
            ArrayList<Pet> list = new ArrayList<>();
            for (Pet p : pets) { PointF q = app.pos(p); if (q != null && Math.abs(q.x - target.x) > 30 * sc && app.calm(p)) list.add(p); }
            app.chaseTo(target.x, list);
        }
    }
    private void eat(Cookie c, Pet p) {
        killCookie(c, "eaten", 500);
        app.love(p, 5);
        if (app.ai != null) app.ai.poke(p.pack, "feed");
        Long at = eatSayAt.get(p.pack);
        if (app.now() - (at == null ? 0 : at) > 2500) {
            eatSayAt.put(p.pack, app.now());
            String t = app.line(p, "eat");
            app.say(p, t, 2400);
            app.react(p, t);
        }
        app.pauseChat(6000);
    }

    // ================= 接饼干游戏 =================
    static final class GameState { int score; boolean running; long next, start, end; java.util.Map<String, Long> saidAt = new java.util.HashMap<>(); }
    GameState game;
    private static final int GAME_MS = 30000;
    private Runnable resultTimer, countR;
    void startGame() {
        if (resultTimer != null) app.h.removeCallbacks(resultTimer);
        stopBall();
        for (Cookie c : new ArrayList<>(cookies)) if (c.game) killCookie(c, "fade", 500);
        game = new GameState();
        app.setMode("game");
        gameV.start();
        sayAny("gameStart", 2200);
        final int[] n = { 3 };
        final GameState g = game;
        if (countR != null) app.h.removeCallbacks(countR);
        countR = new Runnable() {
            @Override public void run() {
                if (game != g) return;
                gameV.count(n[0] > 0 ? String.valueOf(n[0]) : "开始！");
                if (n[0]-- > 0) { app.h.postDelayed(this, 800); return; }
                g.running = true;
                g.start = app.now();
                g.end = g.start + GAME_MS;
                startLoop();
            }
        };
        countR.run();
    }
    private void gameScore(Cookie c, Pet p) {
        int add = c.gold ? 3 : 1;
        game.score += add;
        gameV.invalidate();
        app.fx.plus(c.x, c.y - c.r, "+" + add);
        killCookie(c, p != null ? "eaten" : "pop", 400);
        if (p != null) {
            app.love(p, 1);
            Long at = game.saidAt.get(p.pack);
            if (app.now() - (at == null ? 0 : at) > 3500) { game.saidAt.put(p.pack, app.now()); app.say(p, app.line(p, "eat"), 1500); }
        }
    }
    private long gameChaseAt;
    private void updateGame() {
        if (game == null || !game.running) return;
        long t = app.now(), left = Math.max(0, game.end - t);
        float k = App.clamp((t - game.start) / (float) GAME_MS, 0, 1);
        gameV.invalidate();
        if (t >= game.next && left > 600) {
            float r = cookieR(), fy = app.floorY();
            Cookie c = makeCookie(app.rand(r + 8, W() - r - 8), -r, true, app.rnd.nextFloat() < 0.08f);
            c.vy = (fy + r) / (4.4f - 1.9f * k);
            game.next = t + (long) ((1000 - 520 * k) * app.rand(0.75f, 1.15f));
        }
        if (t - gameChaseAt > 650) {
            gameChaseAt = t;
            Cookie low = null;
            for (Cookie c : cookies) if (c.game && !c.dead && (low == null || c.y > low.y)) low = c;
            if (low != null) app.chaseTo(low.x);
        }
        if (left <= 0) endGame();
    }
    private void endGame() {
        game.running = false;
        for (Cookie c : new ArrayList<>(cookies)) if (c.game) killCookie(c, "fade", 500);
        int s = game.score;
        double best = app.settings.best;
        if (s > best) { app.settings.best = s; app.save(); }
        gameV.result(s + " 块饼干", s > best ? "新纪录！" : "最高纪录 " + (int) best + " 块");
        if (resultTimer != null) app.h.removeCallbacks(resultTimer);
        resultTimer = () -> { if (game != null && !game.running) quitGame(); };
        app.h.postDelayed(resultTimer, 20000);
        for (Pet p : app.livePets()) app.love(p, s / 5);
        sayAny("gameEnd", 4000, App.vars("s", s));
        if (s > best && s > 0) app.fx.burst(W() * .5f, H() * .3f, 0, 22, 1.4f);
    }
    void quitGame() {
        if (resultTimer != null) app.h.removeCallbacks(resultTimer);
        for (Cookie c : new ArrayList<>(cookies)) if (c.game) killCookie(c, "fade", 500);
        game = null;
        gameV.stop();
        app.setMode("");
    }

    // ================= 踢球 =================
    static final class Ball { float x, y, vx, vy, r, rot; long until, saidAt, chaseAt; long[] kickAt = new long[4]; Held held; }
    static final class Held { float x, y, px, py; long t, pt; }
    Ball ball;
    private float ballFade;
    private Ball fadingBall;
    void startBall(long ms) {
        if (ball != null || game != null) return;
        float r = rem() * 0.27f;
        Ball b = new Ball();
        b.x = W() * app.rand(0.3f, 0.7f); b.y = -r; b.vx = app.rand(-160, 160); b.r = r;
        b.until = app.now() + (ms > 0 ? ms : 45000);
        ball = b;
        startLoop();
        sayAny("ball", 2500);
    }
    void ball() { startBall(0); }
    private void stopBall() { if (ball == null) return; fadingBall = ball; ballFade = 1; ball = null; }
    private void updateBall(float dt) {
        if (ballFade > 0) { ballFade = Math.max(0, ballFade - dt / 0.5f); if (ballFade == 0) fadingBall = null; }
        if (ball == null) return;
        Ball b = ball;
        float fy = app.floorY(), w = W(), sc = app.petScale();
        if (b.held != null) {
            b.vx = 0; b.vy = 0;
            b.x = App.clamp(b.held.x, b.r, w - b.r);
            b.y = App.clamp(b.held.y, b.r, fy - b.r);
        } else {
            b.vy += 2200 * dt;
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            if (b.x < b.r) { b.x = b.r; b.vx = Math.abs(b.vx) * 0.7f; }
            if (b.x > w - b.r) { b.x = w - b.r; b.vx = -Math.abs(b.vx) * 0.7f; }
            if (b.y < b.r) { b.y = b.r; b.vy = Math.abs(b.vy) * 0.5f; }
            if (b.y >= fy - b.r) {
                b.y = fy - b.r;
                b.vy = Math.abs(b.vy) > 140 ? -Math.abs(b.vy) * 0.55f : 0;
                b.vx *= Math.pow(0.35, dt);
            }
            List<Pet> pets = app.livePets();
            for (int i = 0; i < pets.size() && i < 4; i++) {
                Pet p = pets.get(i);
                PointF q = app.pos(p);
                if (q == null || app.now() - b.kickAt[i] < 700) continue;
                if (Math.abs(b.x - q.x) < 42 * sc && b.y > q.y - spriteH() * 0.45f) {
                    b.kickAt[i] = app.now();
                    int dir = b.x == q.x ? (app.rnd.nextBoolean() ? -1 : 1) : (b.x > q.x ? 1 : -1);
                    b.vx = dir * app.rand(380, 700);
                    b.vy = -app.rand(420, 760);
                    if (app.now() - b.saidAt > 5000 && app.rnd.nextFloat() < 0.5f) { b.saidAt = app.now(); app.say(p, app.line(p, "ball"), 1800); }
                }
            }
            if (app.now() - b.chaseAt > 900 && Math.abs(b.vx) < 900) { b.chaseAt = app.now(); app.chaseTo(b.x); }
        }
        b.rot += b.vx * dt / b.r * 57.3f;
        if (app.now() > b.until && b.held == null) stopBall();
    }

    // ================= 天上飞的：纸飞机 / 蝴蝶 / 热气球 =================
    abstract class Fly implements Flyer {
        final long t0 = SystemClock.uptimeMillis();
        final int dur;
        final boolean chase;
        long chaseAt;
        float x, y, rot;
        boolean flip;
        Fly(int dur, boolean chase) { this.dur = dur; this.chase = chase; }
        abstract void path(float k);
        @Override public boolean update(long now) {
            float k = (now - t0) / (float) dur;
            if (k >= 1) return false;
            path(k);
            if (chase && now - chaseAt > 800 && x > 0 && x < W()) { chaseAt = now; app.chaseTo(x); }
            return true;
        }
    }
    private void flyer(Fly f) { flyers.add(f); startLoop(); }
    void plane() {
        final boolean fromLeft = app.rnd.nextBoolean();
        final float y0 = H() * app.rand(0.28f, 0.48f), amp = H() * 0.06f, w = W();
        flyer(new Fly(9000, true) {
            @Override void path(float k) {
                x = fromLeft ? -80 + (w + 160) * k : w + 80 - (w + 160) * k;
                y = y0 + (float) Math.sin(k * Math.PI * 3) * amp;
                double slope = Math.cos(k * Math.PI * 3) * amp * Math.PI * 3 / (w + 160) * (fromLeft ? 1 : -1);
                rot = (float) Math.atan(slope) * 57.3f;
                flip = !fromLeft;
            }
            @Override public void draw(Canvas c) { drawPlane(c, this); }
        });
        sayAny("plane", 2600);
    }
    void butterfly() {
        final boolean fromLeft = app.rnd.nextBoolean();
        final float w = W(), h = H(), a = app.rand(0, 6), b = app.rand(0, 6);
        flyer(new Fly(14000, true) {
            @Override void path(float k) {
                x = (fromLeft ? -40 + (w + 80) * k : w + 40 - (w + 80) * k) + (float) Math.sin(k * 11 + a) * w * 0.06f;
                y = h * 0.62f + (float) Math.sin(k * 7 + b) * h * 0.12f + (float) Math.sin(k * 23) * h * 0.02f;
                flip = !fromLeft;
            }
            @Override public void draw(Canvas c) { drawButterfly(c, this); }
        });
        sayAny("butterfly", 2600);
    }
    void hotair() {
        final boolean fromLeft = app.rnd.nextBoolean();
        final float w = W(), y0 = H() * app.rand(0.12f, 0.22f);
        flyer(new Fly(26000, false) {
            @Override void path(float k) { x = fromLeft ? -90 + (w + 180) * k : w + 90 - (w + 180) * k; y = y0 + (float) Math.sin(k * Math.PI * 2) * 14; }
            @Override public void draw(Canvas c) { drawHotair(c, this); }
        });
        app.later(() -> sayAny("balloon", 2800), 3000);
    }
    void rainbow() {
        app.sky.rainbow(true);
        app.later(() -> app.sky.rainbow(false), 25000);
        app.later(() -> sayAny("rainbow", 3000), 1500);
    }
    void meteorShower() {
        for (int i = 0; i < 9; i++) app.later(() -> app.shootingStar(app.rand(30, 98), app.rand(2, 32)), (long) (i * app.rand(220, 420)));
        app.later(() -> sayAny("meteor", 3200), 800);
    }
    void cookieRain() {
        for (int i = 0; i < 5; i++) app.later(() -> dropCookie(app.rand(W() * 0.08f, W() * 0.92f), -cookieR()), (long) (i * app.rand(250, 500)));
        sayAny("cookieRain", 2500);
    }
    void sing() {
        List<Pet> singers = app.livePets();
        for (int i = 0; i < Math.min(2, singers.size()); i++) {
            final Pet p = singers.get(i);
            app.later(() -> {
                String t = app.line(p, "sing");
                app.say(p, t, 3000);
                if (p.pack.equals("Neuron")) app.behave(p, "HeartHeartHeart");
                notes(p, 6);
            }, i * 1600L);
        }
    }
    /** 头上冒几个音符（隔 0.45 秒一个） */
    void notes(final Pet pet, int n) {
        for (int i = 0; i < n; i++) app.later(() -> {
            PointF q = app.pos(pet);
            if (q == null) return;
            app.fx.note(q.x + app.rand(-20, 20), q.y - spriteH() * 0.9f);
        }, i * 450L);
    }

    // ================= UFO 抓走一只宠物，再扔下来 =================
    final class Ufo { float x, y; boolean beam; long beamAt; }
    Ufo ufo;
    boolean ufoEvent() {
        ArrayList<Pet> victims = new ArrayList<>();
        for (Pet p : app.livePets()) if (app.onFloor(p) && app.calm(p)) victims.add(p);
        if (victims.isEmpty()) return false;
        final Pet pet = app.pick(victims);
        PointF q = app.pos(pet);
        final float w = W();
        final boolean fromLeft = q.x > w / 2;
        final float x0 = fromLeft ? -120 : w + 120, y0 = H() * 0.08f, hover = H() * 0.2f;
        final Ufo U = new Ufo();
        ufo = U;
        final long[] t0 = { SystemClock.uptimeMillis() };
        final String[] phase = { "in" };
        final float[] tx = { q.x };
        flyers.add(new Flyer() {
            @Override public boolean update(long now) {
                float t = now - t0[0];
                if ("in".equals(phase[0])) {
                    float k = Math.min(1, t / 1800f), e = 1 - (float) Math.pow(1 - k, 3);
                    PointF p = app.pos(pet);
                    if (p != null) tx[0] = p.x;
                    U.x = x0 + (tx[0] - x0) * e; U.y = y0 + (hover - y0) * e;
                    if (k >= 1) { phase[0] = "beam"; t0[0] = now; U.beam = true; U.beamAt = now; grab(); }
                } else if ("out".equals(phase[0])) {
                    float k2 = Math.min(1, t / 1500f), e2 = k2 * k2;
                    U.x = tx[0] + ((fromLeft ? w + 140 : -140) - tx[0]) * e2;
                    U.y = hover - (hover + 160) * e2;
                    if (k2 >= 1) { if (ufo == U) ufo = null; return false; }
                }
                return true;
            }
            void leave() { if (U.beam) { U.beam = false; U.beamAt = SystemClock.uptimeMillis(); } phase[0] = "out"; t0[0] = SystemClock.uptimeMillis(); }
            void grab() {
                PointF p = app.pos(pet);
                RectF r = pet.spriteRect();
                if (p == null || r == null) { leave(); return; }
                float cy = r.top + r.height() * 0.55f, dy = hover + rem() * 0.5f + r.height() * 0.45f - cy;
                app.later(() -> app.say(pet, app.line(pet, "ufo"), 2600), 300);
                final Pet other = app.other(pet);
                if (other != null) app.later(() -> app.say(other, app.line(other, other.pack.equals(pet.pack) ? "ufoWitnessSame" : "ufoWitness"), 2600), 1500);
                app.synthDrag(pet, new App.Step[] { new App.Step(0, -20, 300), new App.Step(8, dy * 0.5f, 900), new App.Step(-8, dy, 900) }, 900, ok -> leave());
            }
            @Override public void draw(Canvas c) { }
        });
        startLoop();
        return true;
    }

    // ================= 两只对话 =================
    private boolean talking;
    boolean dialogue() {
        Pet n = app.randomPet("Neuron"), ev = app.randomPet("Eviling");
        if (talking || n == null || ev == null || app.lines.dialogues.length() == 0 || !"on".equals(app.settings.bubble)) return false;
        if (app.ai != null && app.ai.dialogue(this::playScript)) return true; // 配了大模型：让模型现编一段（失败了它再演本地剧本）
        return playScript();
    }
    /** 演一段本地剧本 */
    boolean playScript() {
        final Pet n = app.randomPet("Neuron"), ev = app.randomPet("Eviling");
        if (talking || n == null || ev == null || app.lines.dialogues.length() == 0 || !"on".equals(app.settings.bubble)) return false;
        Object s = app.fresh("dialogue", app.lines.dialogues);
        if (!(s instanceof JSONArray)) return false;
        final JSONArray script = (JSONArray) s;
        talking = true;
        app.pauseChat(script.length() * 2700L + 4000);
        final int[] i = { 0 };
        app.h.post(new Runnable() {
            @Override public void run() {
                JSONArray item = script.optJSONArray(i[0]++);
                if (item == null) { talking = false; app.love(n, 1); app.love(ev, 1); return; }
                Pet who = "N".equals(item.optString(0)) ? n : ev;
                app.say(who, item.optString(1), 2500);
                String b = item.optString(2, "");
                if (!b.isEmpty()) app.behave(who, b); else app.react(who, item.optString(1));
                app.h.postDelayed(this, 2700);
            }
        });
        return true;
    }

    // ================= 点场景彩蛋 =================
    boolean tapScene(float x, float y) {
        float d = u.dens;
        if (!"night".equals(app.currentScene) && app.shown("sun") && app.sky.hitSun(x * d, y * d, 12 * d)) { app.sky.flare(); sayAny("sun", 2200); return true; }
        if ("night".equals(app.currentScene) && app.shown("sun") && app.sky.hitMoon(x * d, y * d, 18 * d)) { meteorShower(); return true; }
        int ci = app.sky.hitCloud(x * d, y * d, 8 * d);
        if (ci >= 0) { rainCloud(ci, false); return true; }
        return false;
    }
    private void rainCloud(int i, boolean silent) {
        app.fx.rain(app.sky.rainCloud(i));
        if (!silent) sayAny("cloud", 2200);
    }
    boolean fxFlare() { return app.sky.flare(); }
    boolean fxRain() { int n = app.sky.cloudCount(); if (n == 0) return false; rainCloud(app.rnd.nextInt(n), true); return true; }

    // ================= 滑动手势、长按、喂 =================
    void swipe(String dir) {
        List<Pet> pets = app.livePets();
        if (pets.isEmpty() || game != null) return;
        if ("up".equals(dir)) { for (Pet p : pets) app.toss(p, app.rand(0.8f, 1.1f)); sayAny("swipeUp", 1800); }
        else if ("down".equals(dir)) { for (Pet p : pets) if (app.onFloor(p)) app.behave(p, "SitDown"); sayAny("swipeDown", 1800); }
        else { app.chaseTo("left".equals(dir) ? W() * 0.08f : W() * 0.92f); sayAny("swipeSide", 1800); }
    }
    void longPress(float x, float y) { if (!app.livePets().isEmpty()) dropCookie(x, y); }
    void feed() { dropCookie(W() / 2 + app.rand(-W() * 0.25f, W() * 0.25f), -cookieR()); }

    // ================= 随机事件 =================
    private final Runnable eventR = this::runEvent;
    private boolean eventOn;
    void scheduleEvent(long ms) {
        app.h.removeCallbacks(eventR);
        eventOn = "on".equals(app.settings.events) && !"off".equals(app.settings.pets);
        if (eventOn) app.h.postDelayed(eventR, ms);
    }
    /** 设置里开关随机事件 / 宠物 */
    void eventsChanged() { if (!eventOn || !"on".equals(app.settings.events) || "off".equals(app.settings.pets)) scheduleEvent((long) app.rand(45000, 70000)); }
    private int month() { return Calendar.getInstance().get(Calendar.MONTH) + 1; }
    private void runEvent() {
        if ("on".equals(app.settings.events) && !app.hidden && !app.quiet() && !app.busy() && game == null && ball == null && flyers.isEmpty() && !talking && !app.livePets().isEmpty()) {
            String sc = app.currentScene;
            ArrayList<Object[]> list = new ArrayList<>();
            list.add(new Object[] { (Runnable) this::plane, 3 });
            if ("day".equals(sc) && month() >= 3 && month() <= 10) list.add(new Object[] { (Runnable) this::butterfly, 3 });
            if ("day".equals(sc)) list.add(new Object[] { (Runnable) this::hotair, 2 });
            if ("day".equals(sc) && !app.sky.rainbowOn()) list.add(new Object[] { (Runnable) this::rainbow, 1 });
            if (!"day".equals(sc)) list.add(new Object[] { (Runnable) this::ufoEvent, 2 });
            if ("night".equals(sc) && app.shown("stars")) list.add(new Object[] { (Runnable) this::meteorShower, 2 });
            list.add(new Object[] { (Runnable) this::cookieRain, 2 });
            list.add(new Object[] { (Runnable) () -> app.nap(app.randomPet()), 1 });
            list.add(new Object[] { (Runnable) () -> startBall(40000), 2 });
            list.add(new Object[] { (Runnable) this::sing, 2 });
            if (!app.petsOf("Neuron").isEmpty() && !app.petsOf("Eviling").isEmpty()) list.add(new Object[] { (Runnable) this::dialogue, 3 });
            int total = 0;
            for (Object[] e : list) total += (Integer) e[1];
            float r = app.rnd.nextFloat() * total;
            for (Object[] e : list) { r -= (Integer) e[1]; if (r <= 0) { ((Runnable) e[0]).run(); break; } }
        }
        scheduleEvent((long) app.rand(80000, 160000));
    }
    /** 调试：直接触发一个事件 */
    boolean debugEvent(String name) {
        switch (name) {
            case "plane": plane(); return true;
            case "butterfly": butterfly(); return true;
            case "hotair": hotair(); return true;
            case "rainbow": rainbow(); return true;
            case "ufo": return ufoEvent();
            case "meteor": meteorShower(); return true;
            case "cookieRain": cookieRain(); return true;
            case "ball": startBall(0); return true;
            case "sing": sing(); return true;
            case "dialogue": return dialogue();
            case "game": startGame(); return true;
        }
        return false;
    }
    void destroy() { app.h.removeCallbacks(eventR); app.frames.remove(this); }

    // ================= 画 =================
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private Shader ballShader, cookieN, cookieG;
    private float cookieNr, cookieGr;
    private Shader cookieShader(float r, boolean gold) {
        if (gold ? cookieG == null || cookieGr != r : cookieN == null || cookieNr != r) {
            int[] cols = gold ? new int[] { 0xfffff6c2, 0xffffd23f, 0xffe0a800 } : new int[] { 0xfff3c886, 0xffd9954a, 0xffb8742f };
            Shader s = new RadialGradient(-r * .3f, -r * .4f, r * 1.6f, cols, new float[] { 0, .6f, 1 }, Shader.TileMode.CLAMP);
            if (gold) { cookieG = s; cookieGr = r; } else { cookieN = s; cookieNr = r; }
        }
        return gold ? cookieG : cookieN;
    }
    private final RectF rr = new RectF();
    private void drawCookie(Canvas c, Cookie k, long now) {
        float d = u.dens, x = k.x * d, y = k.y * d, r = k.r * d, s = 1, sx = 1, sy = 1, a = 1, rot = 0, dy = 0;
        if (k.kill != null) {
            float t = Ease.clamp((now - k.killAt) / (float) (k.kill.equals("fade") ? 500 : k.kill.equals("pop") ? 350 : k.kill.equals("crumble") ? 500 : 450), 0, 1);
            switch (k.kill) {
                case "eaten": { float e = Ease.IN.at(t); if (t < .3f) s = 1 + .15f * (t / .3f); else { float q = (t - .3f) / .7f; s = 1.15f * (1 - q); a = 1 - q; rot = 90 * q; } break; }
                case "pop": { float e = Ease.OUT.at(t); s = 1 + .8f * e; a = 1 - e; break; }
                case "crumble": { float e = Ease.OUT.at(t); sx = 1 + .5f * e; sy = 1 - .8f * e; dy = .1f * u.px(u.rem) * e; a = 1 - e; break; }
                default: a = 1 - Ease.EASE.at(t);
            }
        }
        if (a <= 0.003f) return;
        c.save();
        c.translate(x, y + dy);
        c.rotate(rot);
        c.scale(s * sx, s * sy);
        Shader sh = cookieShader(r, k.gold);
        if (k.gold) { p.setShadowLayer(.18f * u.px(u.rem) / 2, 0, 0, Ease.alpha(Ease.rgba(255, 210, 60, .95f), a)); }
        else p.setShadowLayer(.06f * u.px(u.rem) / 2, 0, .03f * u.px(u.rem), Ease.alpha(Ease.rgba(0, 0, 0, .25f), a));
        p.setShader(sh);
        p.setAlpha(Math.round(a * 255));
        c.drawCircle(0, 0, r, p);
        p.setShader(null);
        p.clearShadowLayer();
        if (!k.gold) {
            p.setColor(Ease.alpha(0xff5b3418, a));
            float cr = r * .18f;
            float[][] chips = { { .24f, .30f }, { .58f, .22f }, { .45f, .60f } };
            for (float[] q : chips) c.drawCircle(-r + 2 * r * q[0] + cr, -r + 2 * r * q[1] + cr, cr, p);
        }
        c.restore();
    }
    private void drawBall(Canvas c, Ball b, float alpha) {
        float d = u.dens, x = b.x * d, y = b.y * d, r = b.r * d;
        c.save();
        c.translate(x, y);
        c.rotate(b.rot);
        p.setShadowLayer(.06f * u.px(u.rem) / 2, 0, .03f * u.px(u.rem), Ease.alpha(Ease.rgba(0, 0, 0, .25f), alpha));
        if (ballShader == null) ballShader = new SweepGradient(0, 0, new int[] { 0xffff5a5a, 0xffff5a5a, 0xffffffff, 0xffffffff, 0xff4d96ff, 0xff4d96ff, 0xffffffff, 0xffffffff, 0xffffd93d, 0xffffd93d, 0xffffffff, 0xffffffff },
            new float[] { 0, 1 / 6f, 1 / 6f, 2 / 6f, 2 / 6f, 3 / 6f, 3 / 6f, 4 / 6f, 4 / 6f, 5 / 6f, 5 / 6f, 1 });
        p.setShader(ballShader);
        c.rotate(-90);
        p.setAlpha(Math.round(alpha * 255));
        c.drawCircle(0, 0, r, p);
        p.setShader(null);
        p.clearShadowLayer();
        p.setColor(Ease.alpha(0xffffffff, alpha));
        c.drawCircle(0, 0, r * .24f, p);
        c.restore();
    }
    private void drawPlane(Canvas c, Fly f) {
        float d = u.dens, rem = u.px(u.rem), w = .6f * rem, h = .3f * rem;
        c.save();
        c.translate(f.x * d, f.y * d);
        c.rotate(f.rot);
        if (f.flip) c.scale(-1, 1);
        c.translate(-w / 2, -h / 2);
        path.reset(); path.moveTo(0, 0); path.lineTo(w, h / 2); path.lineTo(0, h); path.close();
        p.setColor(0xffffffff);
        p.setShadowLayer(.03f * rem, 0, .03f * rem, Ease.rgba(0, 0, 0, .25f));
        c.drawPath(path, p);
        p.clearShadowLayer();
        path.reset(); path.moveTo(0, h / 2); path.lineTo(w, h / 2); path.lineTo(0, h); path.close();
        p.setColor(0xffd6deec);
        c.drawPath(path, p);
        c.restore();
    }
    private void drawButterfly(Canvas c, Fly f) {
        float d = u.dens, rem = u.px(u.rem), ww = .19f * rem, wh = .27f * rem;
        float flap = 1 - .75f * Math.abs(((SystemClock.uptimeMillis() % 440) / 220f) - 1);
        flap = .25f + .75f * (1 - Ease.IN_OUT.at(1 - flap));
        c.save();
        c.translate(f.x * d, f.y * d);
        if (f.flip) c.scale(-1, 1);
        p.setShader(new RadialGradient(0, -wh * .1f, ww * 1.2f, new int[] { 0xffffe08a, 0xffff9f43, 0xffe8590c }, new float[] { 0, .6f, 1 }, Shader.TileMode.CLAMP));
        rr.set(-ww * flap, -wh / 2, 0, wh / 2);
        c.drawOval(rr, p);
        rr.set(0, -wh / 2, ww * flap, wh / 2);
        c.drawOval(rr, p);
        p.setShader(null);
        p.setColor(0xff3b2a1a);
        c.drawRoundRect(-.015f * rem, -.13f * rem, .015f * rem, .11f * rem, .02f * rem, .02f * rem, p);
        c.restore();
    }
    private void drawHotair(Canvas c, Fly f) {
        float d = u.dens, rem = u.px(u.rem), ew = .7f * rem, eh = .82f * rem;
        c.save();
        c.translate(f.x * d - ew / 2, f.y * d);
        rr.set(0, 0, ew, eh);
        c.save();
        path.reset(); path.addOval(rr, Path.Direction.CW);
        c.clipPath(path);
        float sw = .12f * rem;
        for (float x = 0; x < ew; x += sw) { p.setColor(((int) (x / sw) % 2 == 0) ? 0xffff6b6b : 0xffffd93d); c.drawRect(x, 0, x + sw, eh, p); }
        p.setShader(new RadialGradient(ew * .35f, eh * .35f, ew, new int[] { 0x00000000, 0x00000000, 0x33000000 }, new float[] { 0, .6f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(rr, p);
        p.setShader(null);
        c.restore();
        p.setColor(0xff6b4a2b);
        float bx = ew / 2 - .1f * rem, by = eh + .1f * rem;
        c.drawRect(bx - .05f * rem, eh, bx - .04f * rem, by, p);
        c.drawRect(bx + .24f * rem, eh, bx + .25f * rem, by, p);
        p.setColor(0xff8b5a2b);
        c.drawRoundRect(bx, by, bx + .2f * rem, by + .13f * rem, .03f * rem, .03f * rem, p);
        c.restore();
    }
    private void drawUfo(Canvas c, Ufo U, long now) {
        float d = u.dens, rem = u.px(u.rem), x = U.x * d - .6f * rem, y = U.y * d;
        c.save();
        c.translate(x, y);
        float bk = Ease.clamp((now - U.beamAt) / 500f, 0, 1), bv = U.beam ? Ease.EASE.at(bk) : 1 - Ease.EASE.at(bk);
        if (bv > 0.003f) { // 光柱：上窄下宽
            float bh = u.px(u.H) * .78f * bv;
            c.save();
            c.translate(-.2f * rem, .42f * rem);
            path.reset();
            path.moveTo(1.6f * rem * .38f, 0); path.lineTo(1.6f * rem * .62f, 0); path.lineTo(1.6f * rem, bh); path.lineTo(0, bh); path.close();
            p.setShader(new LinearGradient(0, 0, 0, Math.max(1, bh), Ease.rgba(180, 255, 240, .6f * bv), Ease.rgba(180, 255, 240, 0), Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            p.setShader(null);
            c.restore();
        }
        rr.set(0, .18f * rem, 1.2f * rem, .48f * rem);
        p.setShader(new LinearGradient(0, rr.top, 0, rr.bottom, 0xffd8e0ea, 0xff7d8ea3, Shader.TileMode.CLAMP));
        p.setShadowLayer(.1f * rem / 2, 0, .04f * rem, Ease.rgba(0, 0, 0, .35f));
        c.drawOval(rr, p);
        p.clearShadowLayer();
        rr.set(.36f * rem, 0, .84f * rem, .6f * rem);
        p.setShader(new RadialGradient(.36f * rem + .48f * rem * .4f, .12f * rem, .3f * rem, Ease.rgba(225, 255, 250, .95f), Ease.rgba(120, 220, 210, .75f), Shader.TileMode.CLAMP));
        c.save();
        c.clipRect(0, 0, 1.2f * rem, .3f * rem);
        c.drawOval(rr, p);
        c.restore();
        p.setShader(null);
        boolean on = (now / 250) % 2 == 0;
        p.setColor(Ease.alpha(0xffffe066, on ? 1 : .3f));
        for (float lx = .18f * rem; lx + .06f * rem <= 1.02f * rem; lx += .18f * rem) c.drawRect(lx, .3f * rem, lx + .06f * rem, .36f * rem, p);
        c.restore();
    }

    final class PropsView extends View {
        PropsView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            for (Cookie k : cookies) drawCookie(c, k, now);
            for (int i = dying.size() - 1; i >= 0; i--) {
                Cookie k = dying.get(i);
                if (now - k.killAt > k.killMs + 50) { dying.remove(i); continue; }
                drawCookie(c, k, now);
            }
            if (ball != null) drawBall(c, ball, 1);
            if (fadingBall != null && ballFade > 0) drawBall(c, fadingBall, ballFade);
        }
        /** 只接球（拖着甩）和游戏饼干（点了算分）；别处交给下面 */
        @Override public boolean onTouchEvent(MotionEvent e) {
            float d = u.dens, x = e.getX() / d, y = e.getY() / d;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (ball != null && Math.hypot(x - ball.x, y - ball.y) <= ball.r + 6) {
                    Held h = new Held();
                    h.x = h.px = x; h.y = h.py = y; h.t = h.pt = app.now();
                    ball.held = h;
                    app.showGear();
                    return true;
                }
                if (game != null && game.running) {
                    float pad = .16f * u.rem;
                    for (Cookie k : new ArrayList<>(cookies)) if (k.game && !k.dead && Math.hypot(x - k.x, y - k.y) <= k.r + pad) { gameScore(k, null); return true; }
                }
                return false;
            }
            if (ball == null || ball.held == null) return false;
            Held h = ball.held;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    long t = app.now();
                    if (t - h.t > 16) { h.px = h.x; h.py = h.y; h.pt = h.t; }
                    h.x = x; h.y = y; h.t = t;
                    break;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                    float dt = Math.max(0.016f, (h.t - h.pt) / 1000f);
                    boolean still = app.now() - h.t > 80;
                    ball.vx = still ? 0 : App.clamp((h.x - h.px) / dt, -1800, 1800);
                    ball.vy = still ? 0 : App.clamp((h.y - h.py) / dt, -1800, 1800);
                    ball.held = null;
                    break;
                }
            }
            return true;
        }
    }
    final class EventsView extends View {
        EventsView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) { for (Flyer f : flyers) f.draw(c); }
    }
    final class UfoView extends View {
        UfoView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) { if (ufo != null) drawUfo(c, ufo, SystemClock.uptimeMillis()); }
    }

    /** 接饼干游戏：顶上的分数和倒计时、开局的 3 2 1、结束时的结果卡片（再来一局 / 结束） */
    final class Game extends View {
        private String count = "";
        private long countAt;
        private String res, best;
        private long resAt;
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF card = new RectF(), quit = new RectF(), again = new RectF();
        private int pressed;
        Game(Context c) { super(c); }
        void start() { res = null; count = ""; setVisibility(VISIBLE); invalidate(); }
        void stop() { setVisibility(GONE); res = null; }
        void count(String s) { count = s; countAt = SystemClock.uptimeMillis(); postInvalidateOnAnimation(); }
        void result(String r, String b) { res = r; best = b; resAt = SystemClock.uptimeMillis(); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float rem = u.px(u.rem), W = getWidth(), H = getHeight();
            boolean anim = false;
            if (game != null) { // 分数、剩几秒
                long left = game.running ? Math.max(0, game.end - app.now()) : game.start == 0 ? GAME_MS : 0;
                String a = "饼干 ", sc = String.valueOf(game.score), b = "     ", sec = String.valueOf((left + 999) / 1000), d = " 秒";
                tp.setTypeface(Fonts.sys);
                tp.setTextSize(.22f * rem);
                float wa = tp.measureText(a), wb = tp.measureText(b), wd = tp.measureText(d);
                tp.setTextSize(.32f * rem);
                float ws = tp.measureText(sc), wsec = tp.measureText(sec);
                float w = wa + ws + wb + wsec + wd + .6f * rem, h = .48f * rem, x = W / 2 - w / 2, y = .25f * rem;
                bg.setColor(Ease.rgba(0, 0, 0, .38f));
                c.drawRoundRect(x, y, x + w, y + h, .3f * rem, .3f * rem, bg);
                float by = y + h / 2 + .1f * rem;
                x += .3f * rem;
                tp.setColor(0xffffffff);
                tp.setTextSize(.22f * rem); c.drawText(a, x, by, tp); x += wa;
                tp.setTextSize(.32f * rem); c.drawText(sc, x, by, tp); x += ws + wb;
                c.drawText(sec, x, by, tp); x += wsec;
                tp.setTextSize(.22f * rem); c.drawText(d, x, by, tp);
                if (game.running) anim = true;
            }
            float ct = (now - countAt) / 780f;
            if (!count.isEmpty() && ct < 1) { // 倒数：放大弹进来，停一下，缩小淡出
                float s, a;
                if (ct < .25f) { float e = Ease.POP20.at(ct / .25f); s = 2.2f - 1.2f * e; a = e; }
                else if (ct < .8f) { s = 1; a = 1; }
                else { float e = (ct - .8f) / .2f; s = 1 - .2f * e; a = 1 - e; }
                tp.setTypeface(Fonts.sysBold);
                tp.setTextSize(1.2f * rem);
                tp.setColor(Ease.alpha(0xffffffff, Ease.clamp(a, 0, 1)));
                tp.setShadowLayer(.2f * rem / 2, 0, .04f * rem, Ease.rgba(0, 0, 0, .45f * Ease.clamp(a, 0, 1)));
                float tw = tp.measureText(count);
                c.save();
                c.scale(s, s, W / 2, H * .38f + .6f * rem);
                c.drawText(count, W / 2 - tw / 2, H * .38f + 1.0f * rem, tp);
                c.restore();
                tp.clearShadowLayer();
                anim = true;
            }
            if (res != null) { // 结果卡片
                float rt = Ease.clamp((now - resAt) / 400f, 0, 1), e = Ease.POP15.at(rt);
                // 同 .game__result：上 .28rem、标题 .24、分数 .5（上 .06）、纪录 .2（上 .04）、按钮 .58（上 .2）、说明 .15（上 .14）、下 .24
                float l1 = .24f * rem * 1.4f, l2 = .5f * rem * 1.3f, l3 = .2f * rem * 1.4f, l4 = .15f * rem * 1.4f, bh = .58f * rem;
                float cw = Math.min(4.4f * rem, W * .88f), ch = .28f * rem + l1 + .06f * rem + l2 + .04f * rem + l3 + .2f * rem + bh + .14f * rem + l4 + .24f * rem;
                card.set(W / 2 - cw / 2, H / 2 - ch / 2, W / 2 + cw / 2, H / 2 + ch / 2);
                c.save();
                float s = .8f + .2f * e;
                c.scale(s, s, W / 2, H / 2);
                bg.setColor(Ease.alpha(Ease.rgba(255, 255, 255, .97f), Ease.clamp(e, 0, 1)));
                bg.setShadowLayer(.4f * rem / 2, 0, .1f * rem, Ease.rgba(0, 0, 0, .3f));
                c.drawRoundRect(card, .3f * rem, .3f * rem, bg);
                bg.clearShadowLayer();
                float y = card.top + .28f * rem;
                tp.setTypeface(Fonts.sys); tp.setTextSize(.24f * rem); tp.setColor(0xff888888);
                centeredIn(c, "接饼干", y, l1);
                y += l1 + .06f * rem;
                tp.setTypeface(Fonts.sysBold); tp.setTextSize(.5f * rem); tp.setColor(0xffd9822b);
                centeredIn(c, res, y, l2);
                y += l2 + .04f * rem;
                tp.setTypeface(Fonts.sys); tp.setTextSize(.2f * rem); tp.setColor(0xff666666);
                centeredIn(c, best, y, l3);
                y += l3 + .2f * rem;
                float bw = (cw - .6f * rem - .12f * rem) / 2, bx = card.left + .3f * rem;
                quit.set(bx, y, bx + bw, y + bh);
                again.set(bx + bw + .12f * rem, y, bx + 2 * bw + .12f * rem, y + bh);
                bg.setColor(0xffeef0f4);
                c.drawRoundRect(quit, .16f * rem, .16f * rem, bg);
                bg.setShader(new LinearGradient(0, again.top, 0, again.bottom, 0xffffb057, 0xffff8a3d, Shader.TileMode.CLAMP));
                c.drawRoundRect(again, .16f * rem, .16f * rem, bg);
                bg.setShader(null);
                tp.setTextSize(.21f * rem);
                tp.setColor(0xff333333); tp.setTypeface(Fonts.sys);
                c.drawText("结束", quit.centerX() - tp.measureText("结束") / 2, quit.centerY() - (tp.descent() + tp.ascent()) / 2, tp);
                tp.setColor(0xffffffff); tp.setTypeface(Fonts.sysBold);
                c.drawText("再来一局", again.centerX() - tp.measureText("再来一局") / 2, again.centerY() - (tp.descent() + tp.ascent()) / 2, tp);
                y += bh + .14f * rem;
                tp.setTypeface(Fonts.sys); tp.setTextSize(.15f * rem); tp.setColor(0xff999999);
                centeredIn(c, "点掉落的饼干得分，宠物接住也算。请在停车时游玩", y, l4);
                c.restore();
                if (rt < 1) anim = true;
            }
            if (anim) postInvalidateOnAnimation();
        }
        private void centered(Canvas c, String s, float y) { c.drawText(s, getWidth() / 2f - tp.measureText(s) / 2, y, tp); }
        /** 在 top 开始、高 lh 的一行里居中 */
        private void centeredIn(Canvas c, String s, float top, float lh) { c.drawText(s, getWidth() / 2f - tp.measureText(s) / 2, top + lh / 2 - (tp.descent() + tp.ascent()) / 2, tp); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (res == null) return false; // 游戏中只有饼干接触摸（道具层）
            float x = e.getX(), y = e.getY();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { pressed = quit.contains(x, y) ? 1 : again.contains(x, y) ? 2 : 0; return card.contains(x, y); }
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                if (pressed == 1 && quit.contains(x, y)) post(Play.this::quitGame);
                if (pressed == 2 && again.contains(x, y)) post(Play.this::startGame);
            }
            return true;
        }
    }
}
