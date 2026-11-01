package com.x3card.pet;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 主逻辑（同网页版 main.js 和它给别的脚本的 window.PetApp）：设置、场景、时钟、农历节日、宠物管理、气泡、手势、模拟拖拽、好感度、闲聊、页面、开场看车况。
 * 别的部分（Play 玩法、Car 车机、Music 音乐、Ai 大模型、Guide 引导）通过这里的方法互相调用；没有的部分是 null。
 * 坐标一律是 dp（同网页版的 CSS 像素）
 */
final class App {
    static final int SPRITE = 128;
    static final String[] PACKS = { "Neuron", "Eviling" };
    static final Map<String, String> PACK_NAME = new HashMap<>();
    static { PACK_NAME.put("Neuron", "Neuro"); PACK_NAME.put("Eviling", "Evil Neuro"); }

    final Context ctx;
    final U u;
    final Frames frames;
    final Handler h = new Handler(Looper.getMainLooper());
    final Random rnd = new Random();
    final Settings settings;
    final Lines lines;
    final Card card;
    Sky sky;
    Clock clock;
    PetLayer petLayer;
    Bubbles bubbles;
    Panel panel;
    Sheet sheet;
    Ui ui;
    Play play;
    Fx fx;
    Car car;
    Music music;
    Ai ai;
    Guide guide;
    InfoBar info;
    MainActivity activity;
    /** 宿主（真的、调试的；连不上时也在，只是什么都拿不到） */
    Host host;
    /** 返回布尔值的一件事（本地台词说没说成、剧本演没演成） */
    interface Lp { boolean run(); }
    /** 全屏页（是 FullActivity）；fsMode 同网页版：'' 没有全屏功能 / 'full' 在小窗口里（键是全屏）/ 'small' 在全屏页里（键是恢复小屏） */
    String fsMode = "";

    boolean hidden;
    String currentScene = "day";
    String mode = "";
    boolean panelOpen, sheetOpen, guiding;

    App(Context c, U u, Frames frames, Card card) {
        ctx = c; this.u = u; this.frames = frames; this.card = card;
        settings = new Settings();
        lines = new Lines(c, () -> currentScene);
        loadCompanion();
        try { dnSaved = Store.get(DN_KEY); if (dnSaved == null) dnSaved = ""; } catch (Exception e) { dnSaved = ""; }
    }

    // ================= 小工具 =================
    long now() { return System.currentTimeMillis(); }
    float rand(float a, float b) { return a + rnd.nextFloat() * (b - a); }
    <T> T pick(List<T> l) { return l.isEmpty() ? null : l.get(rnd.nextInt(l.size())); }
    static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
    void later(Runnable r, long ms) { h.postDelayed(r, ms); }
    void cancel(Runnable r) { if (r != null) h.removeCallbacks(r); }
    void save() { settings.save(); }

    // ================= 场景 =================
    static final String DN_KEY = "pet-day-night";
    String dnSaved = "";
    /** 车机的日夜模式（'day' / 'night'；没有车机 ''）：car 还没连上时先用上次车机报的 */
    String carDayNight() {
        if (car == null) return "";
        String m = car.dayNight();
        if (m == null) return dnSaved;
        if (!m.equals(dnSaved)) { dnSaved = m; if (!m.isEmpty()) Store.set(DN_KEY, m); else Store.remove(DN_KEY); }
        return m;
    }
    String autoScene(int h) {
        String dn = carDayNight();
        boolean dusk = h >= 18 && h < 20;
        if ("night".equals(dn)) return "night";
        if ("day".equals(dn)) return dusk ? "dusk" : "day";
        return h >= 6 && h < 18 ? "day" : dusk ? "dusk" : "night";
    }
    /** 页面和面板底色用不用深色：车机的日夜模式优先，没有按钟点（19～6 点） */
    boolean pageDark() {
        String dn = carDayNight();
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return !dn.isEmpty() ? "night".equals(dn) : h >= 19 || h < 6;
    }
    /** 调试参数（同网页版的 ?scene=…&clock=…，不存进设置）：启动 / 再次启动时 intent 里带的 */
    String forceScene, forceClock;
    String sceneSetting() { return forceScene != null ? forceScene : settings.scene; }
    String clockStyle() { return forceClock != null ? forceClock : settings.clock; }
    static int sceneIndex(String s) { return "dusk".equals(s) ? Sky.DUSK : "night".equals(s) ? Sky.NIGHT : Sky.DAY; }
    /** 同 applyClasses：按设置和车机定场景，交给场景、时钟、页面 */
    void applyClasses() {
        String s = "auto".equals(sceneSetting()) ? autoScene(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) : sceneSetting();
        currentScene = s;
        int si = sceneIndex(s);
        sky.setScene(si);
        clock.setScene(si);
        sky.setLayout(u.portrait, "dial".equals(clockStyle()), hasLyric);
        clock.setMode(!page.name.isEmpty(), "game".equals(mode));
        sky.setPaused(!page.name.isEmpty());
        clock.pills.setTouchable(page.name.isEmpty());
        if (fx != null) fx.setPageMode(!page.name.isEmpty());
        if (ui != null) ui.sync();
        if (pages != null) pages.theme(pageDark());
        if (guide != null) guide.theme();
    }
    boolean hasLyric;
    void setHasLyric(boolean on) { if (hasLyric == on) return; hasLyric = on; sky.setLayout(u.portrait, "dial".equals(clockStyle()), hasLyric); }

    private final Runnable shootLoop = this::loopShooting;
    private boolean shootOn;
    void loopShooting() {
        if ("night".equals(currentScene) && !hidden) sky.shootingStar(Float.NaN, Float.NaN);
        h.removeCallbacks(shootLoop);
        shootOn = shown("stars");
        if (shootOn) h.postDelayed(shootLoop, (long) rand(8000, 20000));
    }
    void shootingStar(float x, float y) { if (shown("stars")) sky.shootingStar(x, y); }

    // ================= 节日特效 =================
    String festKind = "";
    List<String> festNames = new ArrayList<>();
    boolean isBirthday;
    static final Map<String, String> FEST_FX = new HashMap<>();
    static {
        String[][] f = { { "春节", "lantern" }, { "除夕", "lantern" }, { "元宵节", "lantern" }, { "小年", "lantern" }, { "中秋节", "moon" }, { "圣诞节", "snow" }, { "平安夜", "snow" },
            { "元旦", "fireworks" }, { "国庆节", "fireworks" }, { "劳动节", "fireworks" }, { "情人节", "hearts" }, { "七夕", "hearts" }, { "母亲节", "hearts" }, { "儿童节", "balloons" } };
        for (String[] x : f) FEST_FX.put(x[0], x[1]);
    }
    /** 调试：强制的节日特效（null 不强制） */
    String forceFest;
    void applyEffects() {
        String kind = "";
        if (forceFest != null) kind = forceFest;
        else if ("on".equals(settings.fest)) {
            if (isBirthday) kind = "confetti";
            for (int i = 0; kind.isEmpty() && i < festNames.size(); i++) { String k = FEST_FX.get(festNames.get(i)); kind = k == null ? "" : k; }
        }
        if (!kind.equals(festKind)) { festKind = kind; if (fx != null) fx.festival(kind); }
        sky.setMoon(today != null && today.lunar != null ? today.lunar.day : 0, "moon".equals(kind));
        applyClasses();
    }

    // ================= 时钟 / 农历 =================
    static final class Today { int y, m, d; Lunar.Day lunar; String term; }
    Today today;
    String todayKey = "", dateText = "", lunarText = "";
    String[] lunarTags = new String[0];
    long lastTickAt;
    private final Runnable tickR = this::tick;
    /** 每秒：陪伴计时、写时间、跨天重算农历节日、每分钟刷新胶囊 */
    void tick() {
        Calendar d = Calendar.getInstance();
        long tNow = now(), gap = tNow - lastTickAt;
        lastTickAt = tNow;
        if (gap > 600000 || gap < -60000) { comp.session = 0; onResume(); } // 熄火休眠后再上车、或系统时间大幅调整：本次用车重新计时，总时长不清零
        else if (!hidden && gap > 0) { long add = Math.min(gap, 2000); comp.total += add; comp.session += add; }
        if (tNow - compSavedAt > 15000) saveCompanion();
        if (shown("time")) clock.tick(d);
        String key = d.get(Calendar.YEAR) + "-" + d.get(Calendar.DAY_OF_YEAR);
        if (!key.equals(todayKey)) { todayKey = key; computeToday(d); }
        if (d.get(Calendar.SECOND) == 0 || extraEmpty) updateExtra();
        h.removeCallbacks(tickR);
        if (!hidden) h.postDelayed(tickR, 1000 - (tNow % 1000) + 10);
    }
    void computeToday(Calendar d) {
        int y = d.get(Calendar.YEAR), m = d.get(Calendar.MONTH) + 1, day = d.get(Calendar.DAY_OF_MONTH);
        Lunar.Day l = Lunar.fromSolar(y, m, day);
        festNames = Lunar.festivals(y, m, day);
        String term = Lunar.term(y, m, day);
        isBirthday = false;
        if (settings.birthday != null || forceBirthday) {
            Settings.Day b = settings.birthday != null ? settings.birthday : null;
            if (b != null) {
                Lunar.Countdown occ = Lunar.eventDays(b.lunar, true, 0, b.m, b.d, y, m, day);
                isBirthday = occ != null && "today".equals(occ.state);
            }
            isBirthday |= forceBirthday;
        }
        today = new Today();
        today.y = y; today.m = m; today.d = day; today.lunar = l; today.term = term;
        dateText = m + "月" + day + "日 星期" + "日一二三四五六".charAt(d.get(Calendar.DAY_OF_WEEK) - 1);
        lunarText = l != null ? l.monthName + l.dayName + " · " + l.yearGanzhi + l.zodiac + "年" : "";
        ArrayList<String> tags = new ArrayList<>();
        if (isBirthday) tags.add("生日");
        for (int i = 0; i < Math.min(2, festNames.size()); i++) tags.add(festNames.get(i));
        if (term != null && !term.isEmpty()) tags.add(term);
        lunarTags = tags.toArray(new String[0]);
        applyShow();
        applyEffects();
    }
    boolean forceBirthday;

    // ================= 陪伴计时 =================
    static final class Comp { long total, session; }
    final Comp comp = new Comp();
    long compSavedAt;
    private void loadCompanion() {
        try {
            org.json.JSONObject c = new org.json.JSONObject(Store.get("pet-companion") == null ? "{}" : Store.get("pet-companion"));
            double total = c.optDouble("total", 0), session = c.optDouble("session", 0), at = c.optDouble("at", 0);
            comp.total = Double.isNaN(total) || total < 0 ? 0 : (long) total;
            comp.session = !Double.isNaN(session) && session > 0 && !Double.isNaN(at) && Math.abs(now() - at) < 600000 ? (long) session : 0;
        } catch (Exception e) { comp.total = comp.session = 0; }
        compSavedAt = now();
        lastTickAt = now();
    }
    void saveCompanion() {
        compSavedAt = now();
        try { Store.set("pet-companion", new org.json.JSONObject().put("total", comp.total).put("session", comp.session).put("at", now()).toString()); } catch (Exception ignored) { }
    }
    String companionText() {
        long mins = comp.total / 60000;
        if (mins < 1) return "刚刚见面";
        return "已陪伴 " + (mins >= 60 ? mins / 60 + " 小时 " + mins % 60 + " 分" : mins + " 分钟");
    }
    String countdownText() {
        Settings.Day ev = settings.countdown;
        if (ev == null || today == null) return "";
        Lunar.Countdown r = Lunar.eventDays(ev.lunar, ev.repeat, ev.y, ev.m, ev.d, today.y, today.m, today.d);
        if (r == null) return "";
        if ("today".equals(r.state)) return "今天是" + ev.name;
        if ("future".equals(r.state)) return "距" + ev.name + "还有 " + r.days + " 天";
        return ev.name + "已经 " + (-r.days) + " 天";
    }

    // ================= 显示内容 =================
    boolean shown(String id) { return settings.shown(id); }
    /** 关掉的部分藏起来（同 applyShow 的 hide-* 类） */
    void applyShow() {
        clock.setShown(shown("time"), shown("date"), shown("lunar"));
        if (shown("date")) clock.date.setText(dateText);
        if (shown("lunar")) clock.lunar.set(lunarText, lunarTags);
        sky.setShown(shown("sun"), shown("cloud"), shown("stars"));
        if (ui != null) ui.sync();
    }
    boolean extraEmpty = true;
    /** 胶囊一行：生日 / 倒数日 / 陪伴时长 / 车况（car）；藏起来的不放 */
    void updateExtra() {
        ArrayList<Pills.Pill> out = new ArrayList<>();
        if (isBirthday && shown("bd")) out.add(new Pills.Pill("pill-bd", "生日快乐"));
        String c = shown("cd") ? countdownText() : "";
        if (!c.isEmpty()) out.add(new Pills.Pill("pill-cd", c));
        if (shown("tm")) out.add(new Pills.Pill("pill-tm", companionText()));
        if (car != null) for (Pills.Pill p : car.pills()) if (shown(p.cls.split(" ")[0].substring(5))) out.add(p);
        extraEmpty = out.isEmpty();
        clock.pills.set(out);
        clock.wake();
    }
    void refreshExtra() { updateExtra(); }
    void refreshScene() { applyClasses(); }

    // ================= 宠物 =================
    final ArrayList<Pet> pets = new ArrayList<>();
    List<Pet> livePets() { ArrayList<Pet> l = new ArrayList<>(); for (Pet p : pets) if (p.ready() && !p.removed) l.add(p); return l; }
    List<Pet> petsOf(String pack) { ArrayList<Pet> l = new ArrayList<>(); for (Pet p : livePets()) if (p.pack.equals(pack)) l.add(p); return l; }
    Pet randomPet(String pack) { return pick(pack != null ? petsOf(pack) : livePets()); }
    Pet randomPet() { return randomPet(null); }
    Pet other(Pet p) { for (Pet q : livePets()) if (q != p) return q; return null; }
    float refHeight() { return u.H; }
    float baseScale() { return clamp(refHeight() / 700f, 0.6f, 1.5f); }
    float petScale() {
        float f = "s".equals(settings.size) ? 0.8f : "l".equals(settings.size) ? 1.25f : 1;
        return Math.round(baseScale() * f * 100) / 100f;
    }
    float groundPx() { return Math.round(u.H * 0.07f); }
    float groundTop() { return u.H - groundPx(); }
    Float floorTop;   // 页面面板打开时，地板抬到面板顶边（宠物站在面板上）
    Float zoneRight;  // 横屏开着聊天室（右半边）：右墙挪到聊天室左边
    float floorY() { return floorTop == null ? groundTop() : Math.round(floorTop); }
    /** 缩放、地板、右墙、卡片大小交给引擎（整个 World 一份；还没有宠物时也要设，不然第一只出生时按 0×0 的卡片算位置，落在左墙上） */
    void applyEngineConfig() { if (u.W > 0 && u.H > 0) petLayer.config(petScale(), floorY(), zoneRight, u.W, u.H); }
    void zoneRight(Float px) {
        Float v = px != null && px > 0 ? (float) Math.round(px) : null;
        if (v == null ? zoneRight == null : v.equals(zoneRight)) return;
        zoneRight = v;
        applyEngineConfig();
    }

    private final java.util.HashSet<String> spawning = new java.util.HashSet<>();
    void spawnPet(String pack) {
        if (!spawning.add(pack)) return;
        petLayer.spawn(pack, p -> {
            spawning.remove(pack);
            if (p == null) { showError(PACK_NAME.get(pack) + " 加载失败"); return; }
            if ("off".equals(settings.pets)) { p.removed = true; petLayer.remove(p); return; } // 解析期间关掉了宠物
            p.bornAt = now();
            pets.add(p);
        });
    }
    void setPets(String v) {
        settings.pets = v;
        if ("off".equals(v)) {
            bubbles.clear();
            for (Pet p : pets) { p.removed = true; petLayer.remove(p); }
            pets.clear();
        } else syncPets();
        scheduleChat((long) rand(20000, 40000));
        if (play != null) play.eventsChanged();
        if (ai != null) ai.settingsChanged();
        if (ui != null) ui.sync();
        if (panel != null) panel.sync();
    }
    /** 固定两只：一只 Neuro、一只 Evil；错开 600ms 出场（解析行为 XML 是一个长任务，叠在一起会卡一下） */
    void syncPets() {
        for (int i = 0; i < PACKS.length; i++) {
            final String pack = PACKS[i];
            later(() -> {
                if ("off".equals(settings.pets)) return;
                for (Pet p : pets) if (p.pack.equals(pack)) return;
                spawnPet(pack);
            }, i * 600L);
        }
    }
    com.x3card.pet.mascot.MascotEngine.Snapshot state(Pet p) { return p == null ? null : p.snapshot(); }
    PointF pos(Pet p) { return p == null ? null : p.position(); }
    boolean onFloor(Pet p) { PointF q = pos(p); return q != null && Math.abs(q.y - floorY()) < 3; }
    /** 可以安排新动作：没被拖着；掉落 / 被扔的行为只要已经落到地面就算空闲 */
    boolean calm(Pet p) {
        com.x3card.pet.mascot.MascotEngine.Snapshot s = state(p);
        if (s == null || p.dragging() || "Dragged".equals(s.behavior)) return false;
        if ("Thrown".equals(s.behavior) || "Fall".equals(s.behavior)) return Math.abs(s.y - floorY()) < 3;
        return true;
    }
    /** 触发 pack 里的行为（Wink / HeartHeartHeart / ChaseMouse…）；宠物正被拖拽或在掉落时不打断 */
    boolean behave(Pet p, String name, boolean force) {
        if (p == null || p.removed || !p.ready()) return false;
        if (!force && !calm(p)) return false;
        return p.setBehavior(name);
    }
    boolean behave(Pet p, String name) { return behave(p, name, false); }

    // 撞墙看门狗：引擎自带的保护要 6 秒才放弃推不动的移动，Move 动作 1.2 秒没位移就站起来
    private final Runnable stallR = new Runnable() {
        @Override public void run() {
            if (!hidden) for (Pet p : livePets()) {
                com.x3card.pet.mascot.MascotEngine.Snapshot s = state(p);
                if (s == null) continue;
                if ("Move".equals(s.nodeType) && s.x == p.stallX && s.y == p.stallY) {
                    if (now() - p.stallSince > 1200) { behave(p, "StandUp"); p.stallSince = now(); }
                } else { p.stallX = s.x; p.stallY = s.y; p.stallSince = now(); }
            }
            h.postDelayed(this, 300);
        }
    };

    // ================= 台词 =================
    String line(Pet p, String key, Map<String, ?> vars) {
        if (!"on".equals(settings.bubble)) return "";
        return lines.line(p != null ? p.pack : "Neuron", key, vars);
    }
    String line(Pet p, String key) { return line(p, key, null); }
    static Map<String, Object> vars(Object... kv) { HashMap<String, Object> m = new HashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]); return m; }
    Object fresh(String bag, JSONArray arr) { return lines.fresh(bag, arr); }
    void say(Pet p, String text, int ms) { bubbles.say(p, text, ms); }
    void say(Pet p, String text) { bubbles.say(p, text, 3200); }
    Bubble sayCtl(Pet p, String text, List<Bubble.Btn> row, int ms) { return bubbles.sayCtl(p, text, row, ms); }
    boolean ctlSet(Bubble b, String text, List<Bubble.Btn> row, int ms) { return bubbles.ctlSet(b, text, row, ms); }
    void ctlClose(Bubble b) { bubbles.ctlClose(b); }
    /** 台词里带 heart/❤ 就比心，带 wink 就眨眼（Evil 没有这两个动作，用脸红代替） */
    void react(Pet p, String text) {
        if (p == null || text == null) return;
        String t = text.toLowerCase();
        boolean heart = t.contains("heart") || text.contains("❤") || text.contains("爱") || text.contains("喜欢"), wink = t.contains("wink");
        if (p.pack.equals("Neuron")) { if (heart) behave(p, "HeartHeartHeart"); else if (wink) behave(p, "Wink"); }
        else if (heart) behave(p, "StandBlush");
    }

    // ================= 好感度 =================
    static final int[] LEVELS = { 0, 15, 40, 80, 140, 220, 330, 480, 680, 950 };
    static int levelOf(double points) { int lv = 0; for (int i = 0; i < LEVELS.length; i++) if (points >= LEVELS[i]) lv = i + 1; return lv; }
    void love(Pet p, int n) {
        if (p == null) return;
        int before = levelOf(settings.love(p.pack));
        settings.addLove(p.pack, n);
        final int after = levelOf(settings.love(p.pack));
        save();
        if (panel != null) panel.syncLevels();
        if (after > before) later(() -> {
            say(p, line(p, "levelup", vars("n", after)), 4200);
            react(p, "heart");
            PointF q = pos(p);
            if (q != null && fx != null) fx.burst(q.x, q.y - SPRITE * petScale() * 0.6f, p.pack.equals("Eviling") ? 0xffff4d6d : 0xffff8fab, 14, 0.9f);
        }, 600);
    }

    // ================= 模拟拖拽（抛高高、UFO 抓走）：排队执行，用户一碰屏幕立即松手 =================
    static final class Step { final float dx, dy; final int ms; Step(float dx, float dy, int ms) { this.dx = dx; this.dy = dy; this.ms = ms; } }
    interface Done { void done(boolean ok); }
    private static final class Job { Pet pet; Step[] path; int hold; Done done; int tries; }
    private final ArrayList<Job> dragQueue = new ArrayList<>();
    private Job synth;
    private float synthX, synthY;
    long lastInputAt;
    void synthDrag(Pet p, Step[] path, int hold, Done done) {
        Job j = new Job();
        j.pet = p; j.path = path; j.hold = hold; j.done = done;
        dragQueue.add(j);
        runDrag();
    }
    private final Runnable runDragR = this::runDrag;
    void runDrag() {
        if (synth != null || dragQueue.isEmpty()) return;
        Job job = dragQueue.remove(0);
        if (!job.pet.ready() || job.pet.removed) { if (job.done != null) job.done.done(false); runDrag(); return; }
        // 用户正按着屏幕、刚松手不久（引擎要在下一步处理上一次点击）、或宠物还没落稳：稍后重试，约 2 秒后放弃
        if (gesture != null || now() - lastInputAt < 160 || !calm(job.pet)) {
            job.tries++;
            if (job.tries > 14) { if (job.done != null) job.done.done(false); runDrag(); return; }
            dragQueue.add(0, job);
            h.removeCallbacks(runDragR);
            h.postDelayed(runDragR, 150);
            return;
        }
        RectF r = job.pet.spriteRect();
        if (r == null) { if (job.done != null) job.done.done(false); runDrag(); return; }
        final float x0 = r.centerX(), y0 = r.top + r.height() * 0.55f;
        synth = job;
        synthX = x0; synthY = y0;
        job.pet.down(x0, y0, SystemClock.uptimeMillis(), true);
        final long[] seg0 = { SystemClock.uptimeMillis() };
        final int[] i = { 0 };
        final float[] from = { 0, 0 };
        frames.add(new Frames.Client() {
            @Override public boolean frame(long t) {
                if (synth != job) return false;
                if (gesture != null) { finish(false); return false; }
                if (i[0] >= job.path.length) {
                    if (job.hold > 0 && t - seg0[0] < job.hold) return true;
                    finish(true);
                    return false;
                }
                Step to = job.path[i[0]];
                float k = Math.min(1, (t - seg0[0]) / (float) Math.max(1, to.ms));
                float x = x0 + from[0] + (to.dx - from[0]) * k, y = y0 + from[1] + (to.dy - from[1]) * k;
                synthX = x; synthY = y;
                job.pet.move(x, y, t);
                if (k >= 1) { from[0] = to.dx; from[1] = to.dy; i[0]++; seg0[0] = t; }
                return true;
            }
        });
    }
    private void finish(boolean ok) {
        Job j = synth;
        synth = null;
        if (j != null) { j.pet.up(synthX, synthY, SystemClock.uptimeMillis()); if (j.done != null) j.done.done(ok); }
        h.removeCallbacks(runDragR);
        h.postDelayed(runDragR, 120);
    }
    void abortSynth() { if (synth != null) finish(false); }
    boolean synthBusy() { return synth != null; }
    void toss(Pet p, float power) {
        float k = refHeight() / 1052f, pw = (power <= 0 ? 1 : power) * k, side = rand(-60, 60) * k;
        synthDrag(p, new Step[] { new Step(0, -18 * k, 40), new Step(side * 0.4f, -70 * pw, 50), new Step(side, -150 * pw, 45) }, 0, ok -> { if (ok) say(p, line(p, "toss"), 1800); });
    }
    /** 让宠物跑向 x（借用 pack 的 ChaseMouse：先把引擎记录的光标放到那里）。用户正在按住宠物或模拟拖拽时不做 */
    boolean chaseTo(float x, List<Pet> list) {
        if ((gesture != null && gesture.pet != null) || synth != null) return false;
        petLayer.cursor(x, floorY() - 20);
        for (Pet p : list != null ? list : livePets()) {
            com.x3card.pet.mascot.MascotEngine.Snapshot s = state(p);
            if (s != null && !"ChaseMouse".equals(s.behavior)) behave(p, "ChaseMouse");
        }
        return true;
    }
    boolean chaseTo(float x) { return chaseTo(x, null); }

    // ================= 手势：轻点 / 连点 / 长按 / 滑动 =================
    static final class Gesture { float x, y, lx, ly; long t; float moved; Pet pet; boolean lng; Runnable timer, ringShow; }
    Gesture gesture;
    /** 手指按下（不在界面元素上） */
    void gStart(float x, float y) {
        showGear();
        if (ai != null) ai.outside(); // 点了输入栏外面：收起聊天输入栏
        if (panelOpen) closePanel();
        abortSynth(); // 用户一碰屏幕，模拟拖拽立即松手
        final Gesture g = new Gesture();
        g.x = g.lx = x; g.y = g.ly = y; g.t = now();
        g.pet = petLayer.petAt(x, y);
        gesture = g;
        if (g.pet != null) g.pet.down(x, y, SystemClock.uptimeMillis(), false);
        else {
            g.timer = () -> {
                if (gesture != g || g.moved > 12) return;
                g.lng = true;
                fx.ring(false);
                if (play != null) play.longPress(g.x, g.y);
            };
            h.postDelayed(g.timer, 600);
            g.ringShow = () -> { if (gesture == g && g.moved <= 12 && !g.lng) fx.ring(g.x, g.y); };
            h.postDelayed(g.ringShow, 150);
        }
    }
    void gMove(float x, float y) {
        Gesture g = gesture;
        if (g == null) return;
        g.lx = x; g.ly = y;
        g.moved = Math.max(g.moved, Math.max(Math.abs(x - g.x), Math.abs(y - g.y)));
        if (g.moved > 12) fx.ring(false);
        if (g.pet != null) g.pet.move(x, y, SystemClock.uptimeMillis());
    }
    void gEnd(float x, float y) {
        Gesture g = gesture;
        gesture = null;
        lastInputAt = now();
        if (g == null) return;
        h.removeCallbacks(g.timer); h.removeCallbacks(g.ringShow);
        fx.ring(false);
        if (g.pet != null) g.pet.up(x, y, SystemClock.uptimeMillis());
        long dt = now() - g.t;
        float dx = x - g.x, dy = y - g.y;
        if (g.lng) return;
        if (dt < 400 && g.moved < 12) { tap(g.pet, x, y); return; }
        if (g.pet == null && dt < 1500 && Math.max(Math.abs(dx), Math.abs(dy)) > 70) {
            String dir = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "right" : "left") : (dy > 0 ? "down" : "up");
            if ((dir.equals("left") || dir.equals("right")) && pageSwipe(dir)) return;
            if (play != null) play.swipe(dir);
        }
        h.removeCallbacks(runDragR);
        h.postDelayed(runDragR, 200);
    }
    /** 被系统抢走：只松手、清状态，不当成点击或滑动 */
    void cancelGesture() {
        Gesture g = gesture;
        gesture = null;
        lastInputAt = now();
        if (g == null) return;
        h.removeCallbacks(g.timer); h.removeCallbacks(g.ringShow);
        fx.ring(false);
        if (g.pet != null) g.pet.cancel(SystemClock.uptimeMillis());
    }

    void tap(Pet p, float x, float y) {
        if (p != null) {
            long t = now();
            p.tapCount = t - p.tapAt < 380 ? p.tapCount + 1 : 1;
            p.tapAt = t;
            if (p.napUntil > t) { p.napUntil = 0; behave(p, "StandUp"); say(p, line(p, "wake")); return; }
            if (p.tapCount >= 2) ctlClose(bubbles.ctlOf(p)); // 双击、连点：先把头上带按钮的气泡收掉
            if (p.tapCount == 2) { toss(p, 1); if (ai != null) ai.poke(p.pack, "toss"); return; }
            if (p.tapCount >= 3) { say(p, line(p, "annoyed")); react(p, p.pack.equals("Neuron") ? "wink" : ""); return; }
            if (ai != null) ai.poke(p.pack, "tap");
            if (music != null && music.tapPet(p)) return; // 有歌的时候：递上带按钮的气泡
            String l = levelOf(settings.love(p.pack)) >= 3 && rnd.nextFloat() < 0.2f ? line(p, "love") : line(p, tapKey(p));
            say(p, l);
            react(p, l);
            if (t - p.loveAt > 4000) { p.loveAt = t; love(p, 1); }
            return;
        }
        // 时间数字：切换数字 / 表盘
        if (hitDigits(x, y) && !"game".equals(mode) && page.name.isEmpty()) { setClock("dial".equals(clockStyle()) ? "digital" : "dial"); return; }
        if (play != null && play.tapScene(x, y)) return;
        // 点空地：涟漪 + 所有宠物跑过去
        fx.ripple(x, y);
        if ("game".equals(mode)) return;
        if (chaseTo(x)) { Pet who = randomPet(); if (who != null && rnd.nextFloat() < 0.6f) say(who, line(who, "chase"), 2000); }
    }
    boolean hitDigits(float x, float y) {
        if (!shown("time")) return false;
        float d = u.dens;
        if ("dial".equals(clockStyle())) {
            Layer b = clock.dialBox;
            if (b.getVisibility() != android.view.View.VISIBLE) return false;
            float cx = (b.getLeft() + b.getWidth() / 2f + b.getTranslationX()) / d, cy = (b.getTop() + b.getHeight() / 2f + b.getTranslationY()) / d, r = b.getWidth() / 2f / d;
            return (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r;
        }
        RectF r = clock.time.digitsRect();
        if (r == null) return false;
        return x >= r.left / d - 10 && x <= r.right / d + 10 && y >= r.top / d - 10 && y <= r.bottom / d + 10;
    }

    // ================= 问候 / 闲聊 / 整点 / 久驾提醒 / 夜里打盹 =================
    void setMode(String m) { mode = m; applyClasses(); }
    int lastChimeHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    long lastRestHour, chatPaused;
    static String slot(int h) { return h < 5 ? "night" : h < 11 ? "morning" : h < 14 ? "noon" : h < 18 ? "afternoon" : h < 22 ? "evening" : "night"; }
    int greetTries;
    final Runnable greetR = this::greet;
    void greet() {
        Pet p = randomPet();
        if (p == null) { if (greetTries++ < 6) later(greetR, 1500); return; }
        if (introWanted) { introWanted = false; introBegin(); return; }
        greetLine(p);
    }
    void greetLine(Pet p) {
        String text;
        if (isBirthday) text = line(p, "birthday");
        else if (!festNames.isEmpty()) text = line(p, "festival", vars("f", festNames.get(0)));
        else text = line(p, slot(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)));
        say(p, text, 4500);
        react(p, isBirthday || !festNames.isEmpty() ? "heart" : text);
    }
    /** 熄火很久后再上车：也去看一眼车况 */
    void onResume() { lastRestHour = 0; greetTries = 0; introWanted = introAllowed(); h.removeCallbacks(greetR); later(greetR, 2000); }
    String tierOf(Pet p) { int lv = levelOf(settings.love(p.pack)); return lv <= 2 ? "new" : lv >= 6 ? "close" : ""; }
    String idleKey(Pet p) {
        float r = rnd.nextFloat();
        String tier = tierOf(p);
        Calendar d = Calendar.getInstance();
        if (!tier.isEmpty() && r < 0.3f) return "idle_" + tier;
        if (r < 0.55f) return "idle_" + slot(d.get(Calendar.HOUR_OF_DAY));
        if (r < 0.7f) return "idle_" + Lines.seasonOf(d.get(Calendar.MONTH) + 1);
        return "idle";
    }
    String tapKey(Pet p) { String tier = tierOf(p); return !tier.isEmpty() && rnd.nextFloat() < 0.4f ? "tap_" + tier : "tap"; }
    boolean quiet() { return "game".equals(mode) || panelOpen || sheetOpen || !page.name.isEmpty() || page.busy || now() < chatPaused || musicBusy() || aiBusy() || guiding; }
    boolean aiBusy() { return ai != null && ai.busy(); }
    boolean musicBusy() { return music != null && music.busy(); }
    boolean busy() { return gesture != null || synth != null || musicBusy(); }
    private final Runnable chatR = this::chatter;
    boolean talkable() { return !"off".equals(settings.pets) && "on".equals(settings.bubble); }
    void scheduleChat(long ms) { h.removeCallbacks(chatR); if (talkable()) h.postDelayed(chatR, ms); }
    void chatter() {
        if (!quiet() && !hidden) {
            Pet p = randomPet();
            if (p != null && !(play != null && !petsOf("Neuron").isEmpty() && !petsOf("Eviling").isEmpty() && rnd.nextFloat() < 0.45f && play.dialogue())) {
                String text = levelOf(settings.love(p.pack)) >= 3 && rnd.nextFloat() < 0.25f ? line(p, "love") : line(p, idleKey(p));
                say(p, text);
                react(p, text);
            }
        }
        scheduleChat((long) rand(40000, 80000));
    }
    private final Runnable minuteR = new Runnable() { @Override public void run() { everyMinute(); h.postDelayed(this, 60000); } };
    void everyMinute() {
        applyClasses();
        Calendar d = Calendar.getInstance();
        int hr = d.get(Calendar.HOUR_OF_DAY);
        if (d.get(Calendar.MINUTE) == 0 && hr != lastChimeHour) {
            lastChimeHour = hr;
            Pet p = randomPet();
            if (p != null && !"game".equals(mode)) { say(p, line(p, "hour", vars("h", hr)), 4000); react(p, "wink"); }
        }
        final long hours = comp.session / 3600000;
        if ("on".equals(settings.rest) && hours >= 2 && hours % 2 == 0 && hours != lastRestHour) {
            lastRestHour = hours;
            final Pet q = randomPet();
            Runnable restLine = () -> { if (q != null) say(q, line(q, "rest", vars("n", hours)), 6000); };
            if (!(ai != null && ai.carLine("rest", vars("n", hours), null, () -> { restLine.run(); return true; }))) restLine.run();
        }
        if ("on".equals(settings.events) && (hr >= 23 || hr < 5) && rnd.nextFloat() < 0.25f && !quiet()) nap(randomPet());
    }
    boolean nap(Pet p) {
        if (p == null || !onFloor(p) || !calm(p)) return false;
        if (!behave(p, rnd.nextFloat() < 0.5f ? "SitDown" : "LieDown")) return false;
        p.napUntil = now() + 20000;
        say(p, line(p, "nap"), 5000);
        return true;
    }
    void pauseChat(long ms) { chatPaused = now() + ms; }

    // ================= 齿轮、面板 =================
    void showGear() { if (ui != null) ui.showGear(); }
    void openPanel() { panelOpen = true; panel.open(); showGear(); }
    void closePanel() { if (!panelOpen) return; panelOpen = false; panel.close(); showGear(); }
    void openAIPanel() { if (!panelOpen) openPanel(); panel.openAI(); }
    void setGuiding(boolean on) { guiding = on; if (on && panelOpen) closePanel(); if (ui != null) ui.setGuiding(on); }
    /** 换时钟样式（同 setClock）：切换期间封面先藏起来，换好形状再出来 */
    void setClock(String v) {
        boolean fromDial = clock.dialMode;
        settings.clock = v;
        forceClock = null;
        int ms = clock.setDial("dial".equals(v), fromDial);
        clock.cover.swapHide(ms);
        applyClasses();
        save();
        if (panel != null) panel.sync();
    }
    void openSheet() { if (sheet != null) { sheetOpen = true; sheet.open(); } }
    void sheetClosed() { sheetOpen = false; if (panelOpen) panel.armClose(); }
    void sheetSaved() { save(); computeToday(Calendar.getInstance()); updateExtra(); if (panel != null) panel.sync(); }

    // ================= 页面（车况 / 日历 / 倒数日） =================
    static final class PageState { String name = ""; float k = 1; boolean busy; Pages.PageEvent pending; }
    final PageState page = new PageState();
    Pages pages;
    final ArrayList<String> PAGE_ORDER = new ArrayList<>();
    { PAGE_ORDER.add("car"); PAGE_ORDER.add(""); PAGE_ORDER.add("calendar"); PAGE_ORDER.add("countdown"); }
    static final String[] ALL_PAGES = { "car", "", "calendar", "countdown" };
    long lastPageTalk;
    List<String> pagesList() { return new ArrayList<>(PAGE_ORDER); }
    float pageTop() { return Math.round(SPRITE * petScale() * 0.92f + refHeight() * 0.025f); }
    private long floorSetAt;
    /** k：0 完全升起 → 1 顶边落到地面；地板跟着面板顶边（每 40ms 更新一次，开始、结束时一定更新） */
    void placePage(float k, boolean force) {
        page.k = k;
        float top = pageTop(), travel = groundTop() - top;
        pages.place(top, k * travel);
        floorTop = k >= 1 ? null : top + k * travel;
        if (force || now() - floorSetAt >= 40) { floorSetAt = now(); applyEngineConfig(); }
    }
    static final Ease EASE_OUT = new Ease(.25f, 1, .5f, 1), EASE_IN_OUT = new Ease(.65f, 0, .35f, 1);
    void animatePage(final float from, final float to, final int ms, final Ease ease, final Runnable done) {
        final long t0 = SystemClock.uptimeMillis();
        placePage(from, true);
        pages.setMoving(true);
        frames.add(new Frames.Client() {
            @Override public boolean frame(long t) {
                float k = Math.min(1, (t - t0) / (float) ms);
                placePage(from + (to - from) * ease.at(k), k >= 1);
                if (k < 1) return true;
                pages.setMoving(false);
                if (done != null) done.run();
                return false;
            }
        });
    }
    void openPage(String name) {
        if (!Pages.has(name) || name.equals(page.name) || page.busy || "game".equals(mode)) return;
        if (panelOpen) closePanel();
        if (sheetOpen && sheet != null) sheet.close();
        page.busy = true;
        boolean first = page.name.isEmpty();
        boolean fwd = PAGE_ORDER.indexOf(name) > PAGE_ORDER.indexOf(page.name);
        page.name = name;
        applyClasses();
        if (ui != null) ui.syncNav();
        pages.open(name, first, fwd, () -> {
            if (first) animatePage(1, 0, 700, EASE_OUT, this::pageReady);
            else pageReady();
        });
    }
    void pageReady() {
        page.busy = false;
        if (page.pending != null) { Pages.PageEvent d = page.pending; page.pending = null; pageTalk(d); }
    }
    void closePage(boolean quietly) {
        if (page.name.isEmpty() || page.busy) return;
        page.busy = true;
        page.name = "";
        applyClasses();
        if (ui != null) ui.syncNav();
        animatePage(page.k, 1, 520, EASE_IN_OUT, () -> {
            pages.closeAll();
            page.busy = false;
            page.pending = null;
            floorTop = null;
            applyEngineConfig();
            if (!quietly) later(() -> { Pet p = randomPet(); if (p != null && page.name.isEmpty()) say(p, line(p, "pageBack"), 2400); }, 1100);
        });
    }
    void pageTalk(Pages.PageEvent d) {
        Pet p = randomPet();
        long t = now();
        if (p == null) return;
        if (("pick".equals(d.type) || "select".equals(d.type)) && t - lastPageTalk < 2500) return;
        lastPageTalk = t;
        String text = "";
        if ("calendar".equals(d.page)) {
            if ("open".equals(d.type)) text = d.holidayName != null && !d.holidayNow && d.holidayDays > 0 && rnd.nextFloat() < 0.6f ? line(p, "calHoliday", vars("h", d.holidayName, "n", d.holidayDays)) : line(p, "calOpen", vars("lunar", d.lunar == null ? "" : d.lunar));
            else if ("pick".equals(d.type)) text = line(p, d.fest != null && !d.fest.isEmpty() ? "calPick" : "calPickPlain", vars("d", d.today ? "今天" : d.dayText, "f", d.fest == null ? "" : d.fest, "lunar", d.lunar == null ? "" : d.lunar));
        } else if ("countdown".equals(d.page)) {
            if ("saved".equals(d.type)) text = line(p, "cdSaved");
            else if ("deleted".equals(d.type)) text = line(p, "cdDeleted");
            else if ("open".equals(d.type) && (d.ev == null || rnd.nextFloat() < 0.4f)) text = line(p, "cdOpen");
            else if (d.ev != null) text = line(p, "today".equals(d.state) ? "cdToday" : "future".equals(d.state) ? "cdFuture" : "cdPast", vars("name", d.name, "n", d.days));
        } else if ("car".equals(d.page) && "open".equals(d.type)) {
            float r = rnd.nextFloat();
            if (d.year != null && r < 0.3f) text = line(p, "carYear", vars("y", d.year));
            else if (d.km >= 1 && r < 0.6f) text = line(p, "carKm", vars("km", Math.round(d.km)));
            else text = line(p, "carPage");
        }
        if (text.isEmpty()) return;
        say(p, text, 3600);
        react(p, "today".equals(d.state) || "saved".equals(d.type) ? "heart" : text);
    }
    /** 倒数日页面选中的日子 → 时钟下面那行 */
    void syncCountdown(Settings.Day ev) {
        settings.countdown = ev != null && ev.m >= 1 && ev.m <= 12 && ev.d >= 1 && ev.d <= 31 ? ev.copy() : null;
        if (settings.countdown != null) { if (settings.countdown.name.length() > 10) settings.countdown.name = settings.countdown.name.substring(0, 10); if (settings.countdown.name.isEmpty()) settings.countdown.name = "倒数日"; }
        save();
        updateExtra();
        if (panel != null) panel.sync();
    }
    void pageEvent(Pages.PageEvent d) {
        if (d == null || !d.page.equals(page.name)) return;
        if ("touch".equals(d.type)) { introCancel(); return; }
        if (intro != null && "open".equals(d.type)) return;
        if ("swipe".equals(d.type)) { pageSwipe("left".equals(d.dir) ? "left" : "right"); return; }
        if ("countdown".equals(d.page) && d.evSet) syncCountdown(d.ev);
        if (page.busy) page.pending = d; else pageTalk(d);
    }
    /** 左右滑切页：左滑去下一页、右滑回上一页；宠物跟着往那边跑；返回 false 表示这一下不切页 */
    boolean pageSwipe(String dir) {
        if ("game".equals(mode) || page.busy) return false;
        introCancel();
        int i = PAGE_ORDER.indexOf(page.name), j = i + ("left".equals(dir) ? 1 : -1);
        final float runX = "left".equals(dir) ? u.W * 0.1f : u.W * 0.9f;
        Pet p = randomPet();
        if (j < 0 || j >= PAGE_ORDER.size()) {
            chaseTo(runX);
            if (p != null) say(p, line(p, "pageEdge"), 2200);
            return true;
        }
        if (ui != null) ui.showNav();
        chaseTo(runX);
        if (p != null && rnd.nextFloat() < 0.5f) say(p, line(p, "pageSwipe"), 1800);
        if (!PAGE_ORDER.get(j).isEmpty()) openPage(PAGE_ORDER.get(j));
        else { closePage(false); later(() -> chaseTo(runX), 1300); }
        return true;
    }
    /** 显示内容里关掉的页面不在导航里（宠物页一直在）；正开着的被关掉就回宠物页 */
    void syncPages() {
        PAGE_ORDER.clear();
        for (String n : ALL_PAGES) if (n.isEmpty() || (shown("pg-" + n) && (!n.equals("car") || car != null))) PAGE_ORDER.add(n);
        if (!page.name.isEmpty() && !PAGE_ORDER.contains(page.name)) closePage(true);
        if (ui != null) { ui.syncPages(); ui.syncNav(); }
        if (panel != null) panel.sync();
    }

    // ================= 开场：先在时间页播完进场、宠物落地，再升起车况页报车况，报完自动回时间页 =================
    static final class Intro { Runnable timer; boolean reported, back = true; }
    Intro intro;
    boolean introWanted;
    String forcePage;
    boolean introAllowed() { return !"off".equals(settings.intro) && !"off".equals(settings.pets) && shown("pg-car") && forcePage == null && car != null; }
    boolean landed() {
        List<Pet> l = livePets();
        if (l.size() < PACKS.length) return false;
        for (Pet p : l) if (now() - p.bornAt <= 3000) return false;
        return true;
    }
    void introBegin() {
        final long t0 = now();
        h.post(new Runnable() {
            @Override public void run() {
                if ("game".equals(mode) || !page.name.isEmpty() || panelOpen || sheetOpen) { Pet q = randomPet(); if (q != null) greetLine(q); return; }
                boolean quietNow = bubbles.count() == 0 && (now() - bubbles.talkEnd > 1500 || now() - t0 > 20000);
                if (car.ready() && landed() && gesture == null && synth == null && quietNow) {
                    intro = new Intro();
                    openPage("car");
                    final Intro it = intro;
                    it.timer = () -> { if (intro == it && "car".equals(page.name)) { it.reported = true; car.report(App.this::introDone); } else introCancel(); };
                    later(it.timer, 1400);
                    return;
                }
                if (!car.ready() && now() - t0 > 8000) { Pet p = randomPet(); if (p != null) greetLine(p); return; }
                if (now() - t0 > 90000) return;
                h.postDelayed(this, 300);
            }
        });
    }
    void introDone(boolean ok) {
        if (intro == null) return;
        if (!ok) { Pet p = randomPet(); if (p != null) greetLine(p); }
        if (!intro.back) { intro = null; return; }
        cancel(intro.timer);
        intro.timer = this::introEnd;
        later(intro.timer, ok ? 3000 : 4500);
    }
    void introEnd() {
        Intro i = intro;
        intro = null;
        if (i == null || !"car".equals(page.name) || page.busy || "game".equals(mode)) return;
        closePage(true);
    }
    void introCancel() {
        if (intro == null) return;
        intro.back = false;
        if (intro.reported) { cancel(intro.timer); intro = null; }
    }
    boolean introActive() { return intro != null || introWanted; }

    /** 显示内容里一项改了：对应的部分停下或重新开始 */
    void showChanged(String id) {
        applyShow();
        if ("lyric".equals(id) && car != null) car.lyricSetting();
        if ("cover".equals(id) && car != null) car.syncCover();
        if ("fest".equals(id)) computeToday(Calendar.getInstance());
        if ("stars".equals(id) && shown("stars") && !shootOn) { shootOn = true; h.postDelayed(shootLoop, (long) rand(8000, 20000)); }
        if ("time".equals(id) && shown("time")) clock.tick(Calendar.getInstance());
        if (id.startsWith("pg-") || "dots".equals(id) || "nav".equals(id)) syncPages();
        if ("lyricNext".equals(id) && car != null) car.lyricNextSetting();
        if (ui != null) ui.sync();
        updateExtra();
    }
    void toggleShow(String id) { if (settings.setShown(id, !shown(id))) { save(); showChanged(id); } }
    void showAll() {
        for (String[] r : Settings.SHOW) if ((r[4].isEmpty() || car != null) && settings.setShown(r[0], true)) showChanged(r[0]);
        save();
    }
    String todayText() {
        ArrayList<String> l = new ArrayList<>();
        if (today != null && today.lunar != null) l.add("农历" + today.lunar.monthName + today.lunar.dayName);
        if (!festNames.isEmpty()) l.add("今天是" + android.text.TextUtils.join("、", festNames));
        if (isBirthday) l.add("今天是车主生日");
        if (today != null && today.term != null && !today.term.isEmpty()) l.add("节气：" + today.term);
        return android.text.TextUtils.join("，", l);
    }

    // ================= 尺寸、返回键、宿主 =================
    /** 卡片大小变了（拖中缝、横竖切换、弹出键盘）：各部分重新摆；正在输入时地板、宠物不动（同网页版 resize） */
    void layoutAll() {
        sky.layout();
        clock.portrait = u.portrait;
        clock.layout();
        fx.layout();
        petLayer.layout();
        ui.layout();
        panel.layout();
        sheet.layout();
        play.layout();
        if (ai != null) ai.layout();
        if (guide != null) guide.layout();
        info.layout();
        if (!card.typing) {
            if (!page.name.isEmpty() && !page.busy) placePage(0, true); else { applyEngineConfig(); pages.layout(); }
        }
        applyClasses();
        updateExtra();
        ui.syncNav();
    }
    /** 返回键：先收起最上面那层；都没有返回 false（交给系统） */
    boolean back() {
        if (guide != null && guide.back()) return true;
        if (sheetOpen && sheet != null) { sheet.close(); return true; }
        if (ai != null && ai.back()) return true;
        if (panelOpen) { closePanel(); return true; }
        if (play != null && play.game != null) { play.quitGame(); return true; }
        if (!page.name.isEmpty()) { closePage(false); return true; }
        return false;
    }
    /** 回到前台：全屏页和小窗口共用一份设置，另一边改过的跟上 */
    void reloadSettings() {
        String clockBefore = settings.clock, petsBefore = settings.pets, sizeBefore = settings.size;
        settings.load();
        if (!settings.clock.equals(clockBefore)) { clock.setDial("dial".equals(settings.clock), "dial".equals(clockBefore)); }
        if (!settings.pets.equals(petsBefore)) setPets(settings.pets);
        if (!settings.size.equals(sizeBefore)) applyEngineConfig();
        applyShow();
        syncPages();
        applyClasses();
        updateExtra();
        if (panel != null) panel.sync();
        if (ai != null) ai.settingsChanged();
        if (activity != null) activity.applyBar();
    }
    void hostChanged() {
        if (car != null) car.hostChanged();
        if (info != null) info.sync();
        if (ui != null) ui.sync();
        if (ai != null) ai.hostChanged();
    }
    /** 宿主连上了但还没给读数据的授权（车况、音乐都拿不到） */
    boolean infoNeeded() { return host != null && host.ready() && !host.canRead(); }
    void authorize() { if (activity != null) activity.authorize(); }
    void toast(String s) { android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_LONG).show(); }
    /** 手指按在界面元素上（面板、按钮、页面……）：齿轮跟着出来；音乐键、带按钮的气泡不弹（同网页版 [data-mc]） */
    void uiTouched(float x, float y) {
        float d = u.dens;
        String act = clock.pills.getVisibility() == android.view.View.VISIBLE ? clock.pills.hitAct(x * d - clock.pills.getLeft() - clock.group.getTranslationX(), y * d - clock.pills.getTop() - clock.pills.getTranslationY() - clock.pills.pad()) : null;
        if (act != null) return;
        for (Bubble b : bubbles.all()) if (b.ctl && x * d >= b.getLeft() && x * d <= b.getRight() && y * d >= b.getTop() && y * d <= b.getBottom()) return;
        showGear();
    }
    void fsBarChanged() { if (activity != null) activity.applyBar(); }
    /** 生日表单打开时：面板的自动关闭先停 */
    void closePanelTimerOnly() { if (panel != null) panel.stopTimer(); }

    /** 调试（同网页版的地址参数）：scene=day|dusk|night、clock=dial|digital、fest=lantern|moon|fireworks|hearts|balloons|confetti|snow|''、
        birthday=1、event=plane|butterfly|hotair|rainbow|ufo|meteor|cookieRain|ball|sing|dialogue|game、page=car|calendar|countdown、intro=0 */
    void debug(android.os.Bundle b) {
        if (b == null) return;
        String v;
        if ((v = b.getString("scene")) != null) { forceScene = v.matches("day|dusk|night") ? v : null; applyClasses(); }
        if ((v = b.getString("clock")) != null && v.matches("digital|dial")) { forceClock = v; boolean from = clock.dialMode; clock.setDial("dial".equals(v), from); applyClasses(); }
        if ((v = b.getString("fest")) != null) { forceFest = v; applyEffects(); }
        if ("1".equals(b.getString("birthday"))) { forceBirthday = true; computeToday(Calendar.getInstance()); updateExtra(); }
        if ("0".equals(b.getString("intro"))) { introWanted = false; forcePage = ""; }
        if ((v = b.getString("page")) != null && Pages.has(v)) { forcePage = v; introWanted = false; final String pg = v; later(() -> openPage(pg), 1200); }
        if ((v = b.getString("event")) != null && play != null) {
            final String ev = v;
            final int[] tries = { 0 };
            later(new Runnable() { @Override public void run() { if (!play.debugEvent(ev) && tries[0]++ < 15) later(this, 800); } }, 3000);
        }
    }

    // ================= 出错（车机上看不到日志） =================
    void showError(String msg) { if (ui != null) ui.error(msg); android.util.Log.w(Uiplus.TAG, msg); }

    // ================= 启动、藏起来、回来 =================
    void boot() {
        applyShow();
        syncPages();
        applyEngineConfig();
        if (car == null && panel != null) panel.sync();
        introWanted = introAllowed();
        computeToday(Calendar.getInstance());
        clock.initDial("dial".equals(clockStyle()));
        clock.digitsIn(150);
        tick();
        updateExtra();
        h.postDelayed(minuteR, 60000);
        h.postDelayed(stallR, 300);
        loopShooting();
        petLayer.preload(PACKS);
        // 先播完进场动画，再让宠物落下来（加载宠物不和进场动画抢），落地后打招呼
        later(() -> { if (!"off".equals(settings.pets)) syncPets(); }, 2300);
        later(greetR, 4800);
        scheduleChat(35000);
    }
    void setHidden(boolean hd) {
        if (hidden == hd) return;
        hidden = hd;
        if (hd) { saveCompanion(); lines.flush(); settings.saveNow(); h.removeCallbacks(tickR); }
        else tick();
        sky.setHidden(hd);
        clock.setHidden(hd);
        if (music != null && hd) music.hide();
        petLayer.setHidden(hd);
        if (fx != null) fx.setHidden(hd);
        if (car != null) car.setHidden(hd);
        if (ai != null) ai.setHidden(hd);
        if (guide != null) guide.setHidden(hd);
    }
    void destroy() {
        h.removeCallbacksAndMessages(null);
        frames.clear();
        if (car != null) car.quit();
        if (play != null) play.destroy();
        if (fx != null) fx.destroy();
        if (ai != null) ai.destroy();
        petLayer.destroy();
        saveCompanion();
        lines.flush();
        settings.saveNow();
    }
}
