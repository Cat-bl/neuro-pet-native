package com.x3card.pet;

import android.content.Context;
import android.util.Log;
import android.widget.LinearLayout;

import com.x3card.pet.ai.Done;
import com.x3card.pet.ai.Err;
import com.x3card.pet.ai.Json;
import com.x3card.pet.ai.PetChat;
import com.x3card.pet.ai.PetSearch;
import com.x3card.pet.ai.Stop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接大模型（同网页版 ai.js）：两只宠物（Neuro / Evil）按自己的性格跟车主聊天。没填 API Key 时这里什么都不做，宠物照旧说 lines.json 里的台词。
 * - 聊天：齿轮旁边的聊天键 → 底部输入栏（AiBar）；输入栏左边的对象键在「两只 / Neuro / Evil」之间切换（记住），话里叫名字时只让那只回答。
 *   模型每行开头写 "N：" / "E：" 表示谁说（PetChat 解析），一行一个气泡，两只轮流逐字打出来；【动作】让说这行的那只做动作，[[a|b]] 是选项按钮。
 * - 模型能做的：查车况和音乐、操作车机（工具调用，模型不支持时用文字指令〔方法 参数〕；安全分级见 AiControl）、打开页面、联网搜索（PetSearch）。
 * - 没事时找车主聊天（关 / 少 / 中 / 多，约 15 / 8 / 4 分钟）、两只对话（Play 的 dialogue 先问这里）、车况变化时让模型开口（Car.talk 先问 carLine）。
 * - 宠物或说话气泡关掉时全部停掉；Key 留着，打开就恢复。语音播报停用（两只用的是车机同一个声音）。
 * - 聊天室（AiRoom）：输入栏里的"聊天记录"键打开，对话记录像群聊一样看。
 * 存储同网页版：pet-ai（设置）、pet-ai-chat（对话记录）、pet-ai-pro（主动开口的额度）、pet-ai-notools（不支持工具调用的模型）、pet-apps（AiControl）
 */
final class Ai {
    static final String TAG = Uiplus.TAG;
    static final Map<String, String> NAME = new HashMap<>();
    static { NAME.put("Neuron", "Neuro"); NAME.put("Eviling", "Evil"); }

    final App app;
    final Context ctx;
    final Layer host;
    final AiControl ctl;
    final AiRoom room;
    AiBar bar;
    private boolean dead;
    /** 联网的回调都回到主线程 */
    final Executor ex;

    Ai(Context c, App app, Layer host) {
        this.app = app;
        this.ctx = c;
        this.host = host;
        ex = r -> app.h.post(() -> { if (!dead) r.run(); });
        ctl = new AiControl(app);
        set = loadSettings();
        history = loadHistory();
        noTools = loadNoTools();
        loadPro();
        room = new AiRoom(this);
        lastOn = enabled();
        app.h.post(this::sync);
    }

    // ================= 设置（白名单校验，坏了回默认） =================
    static final String SKEY = "pet-ai";
    // 记忆长度：每次带给模型最近几轮对话（一问一答算一轮），另有字数上限兜底；聊天、闲聊、两只对话、车况事件共用一份记录
    static final String[] MEM_ID = { "short", "mid", "long" }, MEM_NAME = { "短", "中", "长" };
    static final int[] MEM_TURNS = { 20, 50, 120 }, MEM_CHARS = { 8000, 20000, 45000 };
    static final String[] CHATTY_ID = { "off", "low", "mid", "high" }, CHATTY_NAME = { "关", "少", "中", "多" };
    static final List<String> TARGETS = Arrays.asList("both", "Neuron", "Eviling");
    static final class Cfg {
        String provider = "deepseek", base = "", format = "chat", memory = "mid", searchBy = PetSearch.PROVIDERS.get(0).id, chatty = "mid", to = "both";
        Map<String, String> keys = new HashMap<>(), models = new HashMap<>(), searchKeys = new HashMap<>();
        boolean searchOn, proactive = true;
    }
    Cfg set;
    private static boolean hasProvider(String id) { for (PetChat.Provider p : PetChat.PROVIDERS) if (p.id.equals(id)) return true; return false; }
    private static boolean hasFormat(String id) { for (PetChat.Format f : PetChat.FORMATS) if (f.id.equals(id)) return true; return false; }
    private static boolean hasSearch(String id) { for (PetSearch.Provider p : PetSearch.PROVIDERS) if (p.id.equals(id)) return true; return false; }
    private interface Has { boolean has(String id); }
    private static Map<String, String> dict(Object v, Has list, int max) {
        Map<String, String> r = new HashMap<>();
        if (v instanceof JSONObject) for (Iterator<String> it = ((JSONObject) v).keys(); it.hasNext(); ) {
            String k = it.next();
            Object x = ((JSONObject) v).opt(k);
            if (list.has(k) && x instanceof String && ((String) x).length() <= max) r.put(k, (String) x);
        }
        return r;
    }
    static Cfg loadSettings() {
        Cfg r = new Cfg();
        Object o = null;
        try { o = Json.parse(Store.get(SKEY)); } catch (RuntimeException ignored) { }
        JSONObject raw = o instanceof JSONObject ? (JSONObject) o : new JSONObject();
        Object v;
        if ((v = raw.opt("provider")) instanceof String && hasProvider((String) v)) r.provider = (String) v;
        r.keys = dict(raw.opt("keys"), Ai::hasProvider, 400);
        r.models = dict(raw.opt("models"), Ai::hasProvider, 200);
        if ((v = raw.opt("base")) instanceof String && ((String) v).length() <= 300) r.base = (String) v;
        if ((v = raw.opt("format")) instanceof String && hasFormat((String) v)) r.format = (String) v;
        if ((v = raw.opt("memory")) instanceof String && Arrays.asList(MEM_ID).contains(v)) r.memory = (String) v;
        if ((v = raw.opt("searchOn")) instanceof Boolean) r.searchOn = (Boolean) v;
        if ((v = raw.opt("searchBy")) instanceof String && hasSearch((String) v)) r.searchBy = (String) v;
        r.searchKeys = dict(raw.opt("searchKeys"), Ai::hasSearch, 300);
        if ((v = raw.opt("proactive")) instanceof Boolean) r.proactive = (Boolean) v;
        if ((v = raw.opt("chatty")) instanceof String && Arrays.asList(CHATTY_ID).contains(v)) r.chatty = (String) v;
        if ((v = raw.opt("to")) instanceof String && TARGETS.contains(v)) r.to = (String) v;
        return r;
    }
    void saveSettings() {
        try {
            JSONObject o = new JSONObject();
            o.put("provider", set.provider).put("keys", new JSONObject(set.keys)).put("models", new JSONObject(set.models)).put("base", set.base).put("format", set.format)
                .put("memory", set.memory).put("searchOn", set.searchOn).put("searchBy", set.searchBy).put("searchKeys", new JSONObject(set.searchKeys))
                .put("proactive", set.proactive).put("chatty", set.chatty).put("to", set.to);
            Store.set(SKEY, Json.stringify(o));
        } catch (Exception ignored) { }
    }
    static String str(Map<String, String> m, String k) { String v = m.get(k); return v == null ? "" : v; }

    /** 当前服务商的请求配置；没填 Key（或自定义没填地址、模型）时返回 null */
    PetChat.Cfg chatConfig() {
        PetChat.Provider p = PetChat.find(set.provider);
        String key = AiControl.trim(str(set.keys, p.id));
        boolean custom = p.id.equals("custom");
        String base = custom ? AiControl.trim(set.base) : p.base, model = AiControl.trim(str(set.models, p.id));
        if (model.isEmpty()) model = p.model;
        if (key.isEmpty() || base.isEmpty() || model.isEmpty()) return null;
        return new PetChat.Cfg(p.id, custom ? set.format : p.format, base, key, model, p.extra);
    }
    PetSearch.Cfg searchConfig() {
        String key = AiControl.trim(str(set.searchKeys, set.searchBy));
        return set.searchOn && !key.isEmpty() ? new PetSearch.Cfg(set.searchBy, key) : null;
    }
    static final String NEED_MODEL = "先填模型（或点“列表”选一个）";
    String missing() {
        PetChat.Provider p = PetChat.find(set.provider);
        if (p.id.equals("custom") && AiControl.trim(set.base).isEmpty()) return "先填接口地址";
        if (AiControl.trim(str(set.keys, p.id)).isEmpty()) return "先填 API Key";
        if (AiControl.trim(str(set.models, p.id)).isEmpty() && p.model.isEmpty()) return NEED_MODEL;
        return "";
    }
    /** 宠物和说话气泡都开着（关了任何一个，AI 全停） */
    boolean enabled() { return !"off".equals(app.settings.pets) && "on".equals(app.settings.bubble); }
    boolean ready() { return enabled() && chatConfig() != null; }
    /** 在车上（真宿主） */
    boolean onCar() { return app.host instanceof Uiplus; }

    // ================= 车况（Car 的数据；没有宿主时没有） =================
    private Object cv(String k) { return app.car != null ? app.car.get(k) : null; }
    boolean carOk() { return app.car != null && app.car.caps != null && !app.car.stale(); }
    Map<String, Object> carNow() {
        Map<String, Object> m = new HashMap<>();
        m.put("driving.speed", cv("driving.speed")); m.put("driving.gear", cv("driving.gear")); m.put("display.dayNightMode", cv("display.dayNightMode"));
        return m;
    }
    private static final Map<String, String> GEAR = new HashMap<>(), POWER = new HashMap<>(), CHG = new HashMap<>(), OPM = new HashMap<>(), AMB = new HashMap<>();
    private static void fill(Map<String, String> m, String... kv) { for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]); }
    static {
        fill(GEAR, "P", "P 挡", "R", "倒挡", "N", "空挡", "D", "D 挡");
        fill(POWER, "off", "熄火", "acc", "ACC 档", "on", "通电", "ready", "已启动", "fakeReady", "已启动");
        fill(CHG, "charging", "正在充电", "finished", "充满了", "terminated", "充电中断");
        fill(OPM, "eco", "经济模式", "sport", "运动模式", "comfort", "舒适模式");
        fill(AMB, "iceBlue", "冰蓝", "coldWhite", "冷白", "warmWhite", "暖白", "redOrange", "橙红", "red", "红色", "redPurple", "紫红", "darkBlue", "深蓝");
    }
    private static final String[][] DOORS = { { "body.doors.frontLeft.state", "左前门" }, { "body.doors.frontRight.state", "右前门" }, { "body.doors.rearLeft.state", "左后门" }, { "body.doors.rearRight.state", "右后门" } };
    private static final String[][] TYRES = { { "tyre.frontLeft", "左前" }, { "tyre.frontRight", "右前" }, { "tyre.rearLeft", "左后" }, { "tyre.rearRight", "右后" } };
    private Double num(String k) { Object v = cv(k); if (v instanceof Number) { double d = ((Number) v).doubleValue(); if (!Double.isNaN(d) && !Double.isInfinite(d)) return d; } return null; }
    private static String r(Double d) { return String.valueOf(Math.round(d)); }
    private static String or(Map<String, String> m, Object k) { String v = k == null ? null : m.get(String.valueOf(k)); return v != null ? v : String.valueOf(k); }
    private boolean truthy(Object v) { return v != null && !"".equals(v) && !Boolean.FALSE.equals(v) && !(v instanceof Number && ((Number) v).doubleValue() == 0); }
    /** 给模型看的车况摘要（只写宿主给了的） */
    String carSummary() {
        if (!carOk()) return "";
        ArrayList<String> s = new ArrayList<>();
        Double v;
        if (truthy(cv("power.level"))) s.add(or(POWER, cv("power.level")));
        if (truthy(cv("driving.gear"))) s.add(or(GEAR, cv("driving.gear")));
        if ((v = num("driving.speed")) != null) s.add(v > 0 ? "车速 " + r(v) + " km/h" : "停着");
        if ((v = num("energy.batteryPercent")) != null) s.add("电量 " + r(v) + "%" + (num("energy.electricRange") != null ? "（纯电续航 " + r(num("energy.electricRange")) + " km）" : ""));
        if ((v = num("energy.fuelPercent")) != null) s.add("油量 " + r(v) + "%" + (num("energy.fuelRange") != null ? "（燃油续航 " + r(num("energy.fuelRange")) + " km）" : ""));
        Object cs = cv("energy.chargingState");
        if (cs != null && CHG.containsKey(String.valueOf(cs))) s.add(CHG.get(String.valueOf(cs)));
        if ((v = num("ac.outdoorTemperature")) != null) s.add("车外 " + r(v) + "°C");
        if ((v = num("ac.indoorTemperature")) != null) s.add("车内 " + r(v) + "°C");
        if (Boolean.TRUE.equals(cv("ac.power"))) s.add("空调开着" + (num("ac.driverTemperature") != null ? " " + r(num("ac.driverTemperature")) + "°C" : ""));
        else if (Boolean.FALSE.equals(cv("ac.power"))) s.add("空调关着");
        if (Boolean.TRUE.equals(cv("ac.rear.power"))) s.add("后排空调开着");
        ArrayList<String> o = new ArrayList<>();
        boolean anyDoor = false;
        for (String[] d : DOORS) { if ("open".equals(cv(d[0]))) o.add(d[1]); if (cv(d[0]) != null) anyDoor = true; }
        if ("open".equals(cv("body.tailgate.state"))) o.add("尾门");
        if ("open".equals(cv("body.hood.state"))) o.add("前机舱盖");
        if (!o.isEmpty()) s.add(android.text.TextUtils.join("、", o) + "开着");
        else if (anyDoor) s.add("车门都关着");
        if (Boolean.TRUE.equals(cv("body.windows.anyOpen"))) s.add("有车窗开着");
        if ((v = num("body.sunroof.position")) != null && v > 0) s.add("天窗开着");
        if ((v = num("body.sunshade.position")) != null && v > 0) s.add("遮阳帘开着");
        if (Boolean.TRUE.equals(cv("light.lowBeam"))) s.add("大灯开着");
        if (Boolean.FALSE.equals(cv("seat.driver.beltFastened"))) s.add("主驾没系安全带");
        ArrayList<String> bad = new ArrayList<>();
        boolean anyTyre = false;
        for (String[] t : TYRES) {
            Object st = cv(t[0] + ".state"), p = cv(t[0] + ".pressure");
            if ("burst".equals(p) || "fault".equals(p) || (truthy(st) && !"normal".equals(st))) bad.add(t[1]);
            if (st != null) anyTyre = true;
        }
        if (!bad.isEmpty()) s.add(android.text.TextUtils.join("、", bad) + "轮胎压异常");
        else if (anyTyre) s.add("胎压正常");
        if (Boolean.TRUE.equals(cv("warning.lowVoltageBatteryFault"))) s.add("低压供电异常");
        if (Boolean.TRUE.equals(cv("warning.keyBatteryLow"))) s.add("钥匙电池电量低");
        if (truthy(cv("ac.circulation"))) s.add("inside".equals(cv("ac.circulation")) ? "内循环" : "外循环");
        if (Boolean.TRUE.equals(cv("ac.frontDefrost"))) s.add("前挡除雾开着");
        if (Boolean.TRUE.equals(cv("ac.rearDefrost"))) s.add("后挡除雾开着");
        String[][] seats = { { "seat.driver.heating", "主驾座椅加热" }, { "seat.passenger.heating", "副驾座椅加热" }, { "seat.driver.ventilation", "主驾座椅通风" }, { "seat.passenger.ventilation", "副驾座椅通风" } };
        for (String[] x : seats) { Double lv = num(x[0]); if (lv != null && lv > 0) s.add(x[1] + (lv == 1 ? "低档" : "高档")); }
        if (truthy(cv("light.ambient.color"))) { String a = AMB.get(String.valueOf(cv("light.ambient.color"))); s.add("氛围灯" + (a != null ? a : "")); }
        Object om = cv("energy.operationMode");
        if (om != null && OPM.containsKey(String.valueOf(om))) s.add(OPM.get(String.valueOf(om)));
        if (truthy(cv("energy.mode"))) s.add("ev".equals(cv("energy.mode")) ? "纯电模式" : "混动模式");
        if ((v = num("audio.volume")) != null) s.add("音量 " + r(v));
        return android.text.TextUtils.join("、", s);
    }
    private JSONObject media() { return app.car != null ? app.car.media : null; }
    String song() {
        JSONObject m = media();
        String t = m == null ? null : Car.str(m, "title");
        if (t == null || t.isEmpty()) return "";
        String a = Car.str(m, "artist");
        return (m.optBoolean("playing") ? "在放" : "暂停了") + "《" + t + "》" + (a != null && !a.isEmpty() ? "（" + a + "）" : "");
    }

    // ================= 说话：一段回复 = 若干段（一段 = 一只宠物的一个气泡：文字、动作、操作、打开页面）。
    // 一次只说一段：在那只头上冒气泡（带按钮的那种，期间别的台词不会顶掉它），逐字打出来（每秒约 20 字），动作和直接做的操作打到那里时做；
    // 说完按字数停一会儿再换下一段；最后有要确认的操作就放"好 / 算了"按钮，有选项就放选项按钮 =================
    static final int CPS = 20;
    static final class Part {
        final String t;
        final StringBuilder s = new StringBuilder();
        List<String> w;
        AiControl.Op op;
        String id;
        Part(String t) { this.t = t; }
    }
    static final class Seg { String who; final ArrayList<Part> parts = new ArrayList<>(); int len; boolean closed, ask; }
    /** 气泡里的一个按钮 */
    static final class AskItem {
        final String text;
        final boolean ghost, send;
        final Runnable fn;
        AskItem(String text, boolean ghost, boolean send, Runnable fn) { this.text = text; this.ghost = ghost; this.send = send; this.fn = fn; }
    }
    final class Line {
        final ArrayList<Seg> segs = new ArrayList<>();
        boolean done, mute, event, love, gen;
        List<String> ask;
        List<AskItem> btns;
        final ArrayList<AiControl.Op> confirm = new ArrayList<>();
        ArrayList<AiControl.Op> refused = new ArrayList<>();
        final String to;
        String who = "", first, search;
        final Set<String> spoke = new HashSet<>();
        Car.PetAfter after;
        final StringBuilder raw = new StringBuilder();
        Line(String to) { this.to = to == null || to.isEmpty() ? "both" : to; }
    }
    final class Player {
        final Line L;
        int si, pi, ci;
        final StringBuilder text = new StringBuilder();
        float budget = 1;
        long hold;
        Bubble b;
        Pet pet;
        boolean asked, first = true;
        List<AiControl.Op> confirming;
        Player(Line L) { this.L = L; }
    }
    private final ArrayList<Line> queue = new ArrayList<>();
    private Player P;
    private List<AskItem> askItems;
    private Pet petFor(String who) {
        Pet p = who != null && !who.isEmpty() ? first(app.petsOf(who)) : null;
        return p != null ? p : app.randomPet();
    }
    private static Pet first(List<Pet> l) { return l.isEmpty() ? null : l.get(0); }
    /** 第一段谁说：指定了就是她；两只一起时随机（模型没写名字标签时也用这只） */
    String firstWho(Line L) {
        if (!L.to.equals("both")) return L.to;
        if (L.first == null) L.first = app.rnd.nextFloat() < 0.5f ? "Neuron" : "Eviling";
        return L.first;
    }
    private Seg openSeg(Line L) {
        Seg s = L.segs.isEmpty() ? null : L.segs.get(L.segs.size() - 1);
        if (s == null || s.closed) { s = new Seg(); s.who = !L.who.isEmpty() ? L.who : firstWho(L); L.segs.add(s); }
        return s;
    }
    private void closeSeg(Line L) { Seg s = L.segs.isEmpty() ? null : L.segs.get(L.segs.size() - 1); if (s != null && !s.parts.isEmpty()) s.closed = true; }
    static final String WSC = AiControl.WSC;
    static final Pattern SEARCH = Pattern.compile("^[" + WSC + "]*(?:web\\.)?search\\b[" + WSC + "]*\\(?[" + WSC + "]*(.*?)[" + WSC + "]*\\)?[" + WSC + "]*$", Pattern.CASE_INSENSITIVE);
    static final Pattern PAGE = Pattern.compile("^[" + WSC + "]*(?:page|页面|打开)(?![a-z_.])[" + WSC + "]*[:：]?[" + WSC + "]*\\(?[" + WSC + "]*(.*?)[" + WSC + "]*\\)?[" + WSC + "]*$", Pattern.CASE_INSENSITIVE);
    static final Pattern QUOTES = Pattern.compile("^[\"'“”]+|[\"'“”]+$");
    static boolean isWs(char c) {
        return c == ' ' || c == 0x09 || c == 0x0a || c == 0x0d || c == 0x0b || c == 0x0c || c == 0xa0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200a)
            || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f || c == 0x3000 || c == 0xfeff;
    }
    static String head(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
    void addToken(Line L, PetChat.Tok tok) {
        switch (tok.t) {
            case "who": closeSeg(L); L.who = tok.w; L.mute = !L.to.equals("both") && !tok.w.equals(L.to); return; // 只对一只说时，另一只的话不显示
            case "br": closeSeg(L); return;
            case "ask": L.ask = tok.list; return;
            case "op": {
                Matcher sq = SEARCH.matcher(tok.raw);
                if (sq.find()) { if (L.search == null && !sq.group(1).isEmpty()) L.search = head(QUOTES.matcher(sq.group(1)).replaceAll(""), 60); return; }
                Matcher pg = PAGE.matcher(tok.raw);
                if (pg.find()) {
                    PetChat.Page p = PetChat.findPage(pg.group(1));
                    if (p != null && pageOk(p.id)) { Part pt = new Part("page"); pt.id = p.id; openSeg(L).parts.add(pt); }
                    else Log.w(TAG, "不认识的页面，没打开：" + tok.raw);
                    return;
                }
                addOp(L, tok.raw);
                return;
            }
        }
        if (L.mute) return;
        if ("act".equals(tok.t)) { Part pt = new Part("act"); pt.w = tok.list; openSeg(L).parts.add(pt); return; }
        if ("text".equals(tok.t) && tok.s != null) addText(L, tok.s);
    }
    private void addText(Line L, String s) {
        for (int i = 0; i < s.length(); i++) {
            Seg b = openSeg(L);
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') { if (b.len > 0) b.closed = true; continue; }
            if (b.len == 0 && isWs(c)) continue;
            Part pt = b.parts.isEmpty() ? null : b.parts.get(b.parts.size() - 1);
            if (pt == null || !"text".equals(pt.t)) { pt = new Part("text"); b.parts.add(pt); }
            pt.s.append(c); b.len++;
            if ((b.len >= 14 && "。！？!?~～…".indexOf(c) >= 0) || (b.len >= 30 && "，,；;、".indexOf(c) >= 0) || b.len >= 44) b.closed = true; // 一行太长：气泡放不下，句末换下一个
        }
    }
    /** 本地说一句（lines.json 的 ai* 台词），排在正在说的后面；btns：说完在气泡里放的按钮 */
    void sayLocal(String who, String key, Map<String, ?> vars, List<AskItem> btns) {
        Pet pet = petFor(who);
        String text = pet != null ? app.line(pet, key, vars) : "";
        if (text.isEmpty()) return;
        Line L = new Line(pet.pack);
        L.who = pet.pack;
        L.btns = btns;
        remember(Msg.of("s", text).w(pet.pack)); // 只给聊天室看
        addText(L, text);
        L.done = true;
        queue.add(L);
        wake();
    }
    void stopSpeech() {
        if (P != null) { unconfirmed(P); if (P.b != null) app.ctlClose(P.b); }
        queue.clear();
        P = null;
        askItems = null;
        room.actions(null);
        setThinking(false, null);
    }
    private static long readMs(CharSequence t) { return 1600 + t.length() * 100L; } // 语音播报停用；开着时要等车机念完
    private final Runnable pumpR = this::pump;
    private boolean ticking;
    private long lastTick;
    void wake() { if (!ticking && !dead) { ticking = true; lastTick = 0; app.h.post(pumpR); } }
    /** 说话的节拍：有话要说时每 50ms 走一步，说完就停（不常驻定时器） */
    private void pump() {
        ticking = false;
        if (dead) return;
        long n = System.currentTimeMillis();
        float dt = lastTick != 0 ? Math.min(0.2f, (n - lastTick) / 1000f) : 0;
        lastTick = n;
        try { step(n, dt); } catch (RuntimeException e) { Log.w(TAG, "说话出错", e); stopSpeech(); }
        if (P != null || !queue.isEmpty()) { ticking = true; app.h.postDelayed(pumpR, 50); }
    }
    private boolean alive(Bubble b) { return b != null && app.bubbles.alive(b); }
    private void step(long n, float dt) {
        if (P == null) {
            if (queue.isEmpty()) return;
            P = new Player(queue.remove(0));
        }
        Line L = P.L;
        Seg B = P.si < L.segs.size() ? L.segs.get(P.si) : null;
        if (B == null && L.done && !L.confirm.isEmpty() && !P.asked) { // 只写了要确认的操作、没说话
            B = new Seg();
            B.who = !L.who.isEmpty() ? L.who : firstWho(L);
            B.closed = true; B.ask = true;
            Pet pet0 = petFor(B.who);
            String q = pet0 != null ? app.line(pet0, "aiAsk", App.vars("op", opsText(L.confirm))) : "";
            Part pt = new Part("text");
            pt.s.append(!q.isEmpty() ? q : opsText(L.confirm) + "，要帮你弄吗？");
            B.parts.add(pt);
            B.len = pt.s.length();
            L.segs.add(B);
        }
        if (B == null) { if (L.done) endLine(); return; }
        if (P.pet == null) {
            P.pet = petFor(B.who);
            if (P.pet == null) { stopSpeech(); return; } // 宠物都不在了
        }
        if (P.hold == 0) {
            if (P.b != null && !alive(P.b)) { finishSeg(B); P.hold = n; return; } // 气泡被收掉了（双击、点歌按钮）：这段不说了，后面的接着说
            P.budget = Math.min(P.budget + dt * CPS, 4);
            boolean typed = false, more = !(B.closed || L.done || P.si < L.segs.size() - 1); // 这段后面还可能接着来字
            while (P.pi < B.parts.size()) {
                Part part = B.parts.get(P.pi);
                if (!"text".equals(part.t)) { runPart(part); P.pi++; continue; }
                if (P.ci >= part.s.length()) {
                    if (more && P.pi == B.parts.size() - 1) break; // 正在流式接收的这段可能还会变长：停在这里等
                    P.pi++; P.ci = 0; continue;
                }
                if (P.budget < 1) break;
                P.text.append(part.s.charAt(P.ci++)); P.budget -= 1; typed = true;
            }
            if (typed) show(P.text.toString());
            if (!more && P.pi >= B.parts.size()) {
                P.hold = n + (P.text.length() > 0 ? readMs(P.text) : 300);
                if (P.b != null) app.ctlSet(P.b, null, null, (int) (P.hold - n + 1500));
            }
        } else if (n >= P.hold) {
            if (P.si + 1 < L.segs.size()) { // 下一段
                if (P.b != null) app.ctlClose(P.b);
                P.si++; P.pi = P.ci = 0; P.text.setLength(0); P.hold = 0; P.budget = 1; P.b = null; P.pet = null;
            } else if (L.done) {
                if (!L.confirm.isEmpty() && !P.asked && alive(P.b)) { P.asked = true; askConfirm(L); P.hold = n + 20000; } // 20 秒不点算没确认
                else if (L.btns != null && !P.asked && alive(P.b)) { P.asked = true; showAsk(L.btns); P.hold = n + 10000; } // "去改设置"：10 秒不点收起
                else if (L.ask != null && !P.asked && alive(P.b)) {
                    P.asked = true;
                    ArrayList<AskItem> items = new ArrayList<>();
                    final String to = L.to;
                    for (final String s : L.ask) items.add(new AskItem(s, false, true, () -> send(s, to)));
                    showAsk(items);
                    P.hold = n + 20000;
                } else endLine();
            }
        }
    }
    /** 这段没说完就被收掉：动作不做了，操作、打开页面照样做 */
    private void finishSeg(Seg B) {
        for (; P.pi < B.parts.size(); P.pi++) { Part p = B.parts.get(P.pi); if ("op".equals(p.t) || "page".equals(p.t)) runPart(p); }
    }
    private void runPart(Part part) {
        if ("act".equals(part.t)) doAct(P.pet, part.w);
        else if ("op".equals(part.t)) exec(part.op);
        else if ("page".equals(part.t)) goPage(part.id);
    }
    private void show(String text) {
        if (P.b == null) {
            if (thinkB != null && thinkB.pet == P.pet && alive(thinkB)) { // 正在"想"的那只接着说
                P.b = thinkB;
                thinkB = null;
                P.b.dots = false;
                app.ctlSet(P.b, text, null, 30000);
            } else {
                setThinking(false, null);
                P.b = app.sayCtl(P.pet, text, null, 30000);
                if (P.b == null) return;
                P.b.gen = P.L.gen; // 模型生成的，气泡角上挂"AI"小标（本地台词不挂）
                P.b.invalidate();
            }
            if (P.L.love && !P.L.spoke.contains(P.pet.pack)) app.love(P.pet, 1); // 聊天也加好感（每次回复每只加一次）
            P.L.spoke.add(P.pet.pack);
            if (P.first) { P.first = false; if (P.L.after != null) { try { P.L.after.run(P.pet); } catch (RuntimeException ignored) { } } }
        } else app.ctlSet(P.b, text, null, 30000);
    }
    private void endLine() {
        Line L = P != null ? P.L : null;
        if (P != null) { unconfirmed(P); if (P.b != null) app.ctlClose(P.b); }
        P = null;
        askItems = null;
        room.actions(null);
        activity();
        if (L == null) return;
        if (!L.refused.isEmpty()) sayLocal(!L.who.isEmpty() ? L.who : firstWho(L), "aiDriving", App.vars("op", opsText(L.refused)), null); // 开车时不能做的
    }
    /** 气泡里的按钮：选项、"好 / 算了"（聊天室里也放一份，竖屏时气泡被聊天室挡住） */
    private void showAsk(List<AskItem> items) {
        if (P == null || !alive(P.b)) return;
        askItems = items;
        room.actions(items);
        ArrayList<Bubble.Btn> row = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) row.add(Bubble.Btn.text("ai:" + i, items.get(i).text, items.get(i).ghost));
        app.ctlSet(P.b, null, row, 21000);
    }
    /** 点了气泡或聊天室里的第 i 个按钮 */
    void pressAt(int i) {
        List<AskItem> items = askItems;
        AskItem it = items != null && i >= 0 && i < items.size() ? items.get(i) : null;
        Player me = P;
        askItems = null;
        room.actions(null);
        if (it != null && !it.send) remember(Msg.of("s", "你点了「" + it.text + "」")); // 选项会作为车主的话发出去，不用另记
        if (it != null && it.fn != null) it.fn.run();
        if (me != null && P == me) P.hold = 1; // 点了按钮：这段话下一步就收起
    }
    void press(Bubble b, String act) {
        if (act == null || !act.startsWith("ai:")) return;
        try { pressAt(Integer.parseInt(act.substring(3))); } catch (NumberFormatException ignored) { }
    }

    // ================= 动作：【动作】→ 引擎的行为（Evil 的包没有 Wink / HeartHeartHeart，用脸红代替或不做） =================
    private void doAct(Pet pet, List<String> ids) {
        if (pet == null || ids == null) return;
        for (String id : ids) {
            boolean evil = pet.pack.equals("Eviling");
            switch (id) {
                case "sit": if (app.onFloor(pet)) app.behave(pet, "SitDown"); break;
                case "lie": if (app.onFloor(pet)) app.behave(pet, "LieDown"); break;
                case "shy": app.behave(pet, "StandBlush"); break;
                case "heart": app.behave(pet, evil ? "StandBlush" : "HeartHeartHeart"); break;
                case "wink": if (!evil) app.behave(pet, "Wink"); break;
                case "stand": app.behave(pet, "StandUp"); break;
                case "jump": if (app.onFloor(pet) && app.calm(pet)) app.toss(pet, 0.7f + app.rnd.nextFloat() * 0.25f); break;
                case "come": { ArrayList<Pet> l = new ArrayList<>(); l.add(pet); app.chaseTo(app.u.W * (0.4f + app.rnd.nextFloat() * 0.2f), l); break; }
                case "sing": if (app.play != null) app.play.notes(pet, 5); break;
            }
        }
    }

    // ================= 打开页面 =================
    boolean pageOk(String id) { return id == null || id.isEmpty() || app.PAGE_ORDER.contains(id); }
    List<String> pageNames() {
        ArrayList<String> out = new ArrayList<>();
        if (app.PAGE_ORDER.size() <= 1) return out;
        for (PetChat.Page p : PetChat.PAGES) if (app.PAGE_ORDER.contains(p.id)) out.add(p.name);
        return out;
    }
    private void goPage(String id) {
        if (!pageOk(id) || "game".equals(app.mode)) return;
        if (id != null && !id.isEmpty()) app.openPage(id); else app.closePage(false);
    }

    // ================= 车机操作（AiControl 校验、分级、执行）：直接做的打到那里时做；要确认的说完弹按钮；开车时不能做的说完告诉车主 =================
    private void addOp(Line L, String raw) {
        AiControl.Op op = ctl.parse(raw);
        if (op == null) { Log.w(TAG, "不认识的车机操作，没做：" + raw); return; }
        int lv = ctl.level(op, carNow());
        if (lv == AiControl.DIRECT && L.event) lv = AiControl.CONFIRM; // 她们自己开口（车况提醒、闲聊）时提的操作，车主点了才做
        if (lv == AiControl.DRIVING) { ctl.note(op, "没做（开车时不能）"); L.refused.add(op); }
        else if (lv != AiControl.DIRECT) L.confirm.add(op);
        else { Part pt = new Part("op"); pt.op = op; openSeg(L).parts.add(pt); }
    }
    String opsText(List<AiControl.Op> ops) {
        ArrayList<String> l = new ArrayList<>();
        for (AiControl.Op o : ops) l.add(ctl.label(o));
        String s = android.text.TextUtils.join("、", l);
        return s.length() > 16 ? s.substring(0, 15) + "…" : s;
    }
    private void failSay(String who, AiControl.Op op, String code, String reason) {
        if ("SESSION_CLOSED".equals(code)) return;
        if ("LIMIT_REACHED".equals(code)) sayLocal(who, "aiLimit", App.vars("op", ctl.label(op)), null);
        else sayLocal(who, "aiOpFail", App.vars("op", ctl.label(op), "why", reason), null);
    }
    private void exec(final AiControl.Op op) {
        final String who = P != null && P.pet != null ? P.pet.pack : "";
        ctl.run(op, (r, code, reason, raw) -> { if (code != null && !dead) failSay(who, op, code, reason); });
    }
    private void askConfirm(Line L) {
        final List<AiControl.Op> ops = L.confirm;
        final Player me = P;
        final String who = P.pet.pack;
        me.confirming = ops;
        final Runnable close = () -> { me.confirming = null; if (P == me) P.hold = 1; }; // 下一步收起这段话
        ArrayList<AskItem> items = new ArrayList<>();
        items.add(new AskItem("好，" + opsText(ops), false, false, () -> {
            close.run();
            for (AiControl.Op op : ops) {
                if (ctl.level(op, carNow()) == AiControl.DRIVING) { ctl.note(op, "没做（开车时不能）"); sayLocal(who, "aiDrivingNow", App.vars("op", ctl.label(op)), null); }
                else exec(op);
            }
        }));
        items.add(new AskItem("算了", true, false, () -> { close.run(); for (AiControl.Op op : ops) ctl.note(op, "没做（车主说算了）"); sayLocal(who, "aiCancel", null, null); }));
        showAsk(items);
    }
    private void unconfirmed(Player p) {
        if (p.confirming == null) return;
        for (AiControl.Op op : p.confirming) ctl.note(op, "没做（车主没确认）");
        p.confirming = null;
    }

    // ================= "在想"：等模型开口时，要回答的那只头上冒三个点 =================
    private Bubble thinkB;
    private void setThinking(boolean on, String who) {
        if (on) {
            if (thinkB != null && alive(thinkB)) return;
            Pet pet = petFor(who);
            thinkB = pet != null ? app.sayCtl(pet, "", null, 60000) : null;
            if (thinkB != null) { thinkB.dots = true; thinkB.gen = true; thinkB.measureNow(); }
            room.typing(pet != null ? pet.pack : "");
        } else {
            if (thinkB != null) { app.ctlClose(thinkB); thinkB = null; }
            room.typing("");
        }
    }

    // ================= 对话记录 =================
    /** 一条记录：r = u 车主（带 [对两只说] 这类前缀；v：事件、搜索结果在聊天室里显示的说明）、a 宠物（k：工具调用，x：这个格式回来的原样，w：没写名字时谁先说）、
     *  t 工具结果（i、n）、s 只给聊天室看、不交给模型（w：Neuron / Eviling 本地台词，me 车主发了没发出去的话（f），空 = 灰字）；都带 t（记下的时间） */
    static final class Msg {
        String r, c, v, w, i, n;
        long t;
        boolean f;
        JSONArray k;
        JSONObject x;
        /** 聊天室解析过的样子（按 to） */
        List<AiRoom.Item> parsed;
        String parsedTo;
        static Msg of(String r, String c) { Msg m = new Msg(); m.r = r; m.c = c; return m; }
        Msg w(String w) { this.w = w; return this; }
        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("r", r).put("c", c);
                if (v != null) o.put("v", v);
                if (w != null) o.put("w", w);
                if (f) o.put("f", 1);
                if (i != null) o.put("i", i);
                if (n != null) o.put("n", n);
                if (k != null) o.put("k", k);
                if (x != null) o.put("x", x);
                if (t != 0) o.put("t", t);
            } catch (Exception ignored) { }
            return o;
        }
    }
    static final String HKEY = "pet-ai-chat";
    static final int HMAX = 400, RAWMAX = 8000; // 一轮至少两条（调了工具更多），长档 120 轮要存得下
    ArrayList<Msg> history;
    private static boolean okCalls(Object k) {
        if (!(k instanceof JSONArray)) return false;
        JSONArray a = (JSONArray) k;
        if (a.length() == 0 || a.length() > 8) return false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            if (c == null || !(c.opt("id") instanceof String) || !(c.opt("name") instanceof String) || !(c.opt("args") instanceof String) || ((String) c.opt("args")).length() > 4000) return false;
        }
        return true;
    }
    private static boolean jsTruthy(Object v) {
        if (v == null || v == JSONObject.NULL || Boolean.FALSE.equals(v)) return false;
        if (v instanceof String) return !((String) v).isEmpty();
        if (v instanceof Number) { double d = ((Number) v).doubleValue(); return d != 0 && !Double.isNaN(d); }
        return true;
    }
    static ArrayList<Msg> loadHistory() {
        ArrayList<Msg> out = new ArrayList<>();
        Object raw = null;
        try { raw = Json.parse(Store.get(HKEY)); } catch (RuntimeException ignored) { }
        if (!(raw instanceof JSONArray)) return out;
        JSONArray a = (JSONArray) raw;
        for (int j = 0; j < a.length(); j++) {
            JSONObject o = a.optJSONObject(j);
            if (o == null || !(o.opt("c") instanceof String) || ((String) o.opt("c")).length() > 2000) continue;
            Msg m = Msg.of(o.opt("r") instanceof String ? (String) o.opt("r") : "", (String) o.opt("c"));
            Object t = o.opt("t"), v = o.opt("v"), w = o.opt("w");
            if (t instanceof Number && !Double.isNaN(((Number) t).doubleValue()) && !Double.isInfinite(((Number) t).doubleValue())) m.t = ((Number) t).longValue();
            if (v instanceof String && ((String) v).length() <= 60) m.v = (String) v;
            if ("Neuron".equals(w) || "Eviling".equals(w) || ("s".equals(m.r) && "me".equals(w))) m.w = (String) w;
            m.f = jsTruthy(o.opt("f"));
            if (m.r.equals("u") || m.r.equals("s")) { out.add(m); continue; }
            if (m.r.equals("a")) {
                if (o.has("k") && !okCalls(o.opt("k"))) continue;
                if (o.has("x")) { JSONObject x = o.optJSONObject("x"); if (x == null || !(x.opt("f") instanceof String) || !jsTruthy(x.opt("d"))) continue; m.x = x; }
                if (o.has("k")) m.k = o.optJSONArray("k");
                out.add(m);
                continue;
            }
            if (m.r.equals("t") && o.opt("i") instanceof String && o.opt("n") instanceof String) { m.i = (String) o.opt("i"); m.n = (String) o.opt("n"); out.add(m); }
        }
        while (out.size() > HMAX) out.remove(0);
        return out;
    }
    private final Runnable saveR = this::saveNow;
    private boolean savePending;
    /** 存到本地（攒一下再写：一次回复会记好几条） */
    void saveHistory() { app.h.removeCallbacks(saveR); savePending = true; app.h.postDelayed(saveR, 800); }
    void saveNow() {
        app.h.removeCallbacks(saveR);
        savePending = false;
        JSONArray a = new JSONArray();
        int from = Math.max(0, history.size() - HMAX);
        for (int j = from; j < history.size(); j++) a.put(history.get(j).json());
        String s = Json.stringify(a);
        if (s != null) Store.set(HKEY, s);
    }
    void remember(Msg m) {
        if (m.c == null) m.c = "";
        if (m.c.length() > 2000) m.c = m.c.substring(0, 2000);
        if (m.t == 0) m.t = System.currentTimeMillis();
        if (m.x != null) { String s = Json.stringify(m.x); if (s == null || s.length() > RAWMAX) m.x = null; }
        history.add(m);
        while (history.size() > HMAX) history.remove(0);
        saveHistory();
        room.update();
    }
    /** 最近的对话：按记忆长度取最近几轮（再按字数兜底），从车主的话开始。tm：用工具调用（带上调用和结果；否则只留说的话） */
    private List<JSONObject> recent(boolean tm, List<Msg> list) {
        int mi = Math.max(0, Arrays.asList(MEM_ID).indexOf(set.memory));
        ArrayList<Msg> out = new ArrayList<>();
        int n = 0, turns = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            Msg m = list.get(i);
            if (m.r.equals("s")) continue; // 只给聊天室看的
            n += m.c.length();
            if (n > MEM_CHARS[mi] && !out.isEmpty()) break;
            if (m.r.equals("u") && ++turns > MEM_TURNS[mi]) break;
            out.add(0, m);
        }
        while (!out.isEmpty() && !out.get(0).r.equals("u")) out.remove(0);
        ArrayList<JSONObject> res = new ArrayList<>();
        for (int j = 0; j < out.size(); j++) {
            Msg m = out.get(j);
            if (m.r.equals("u")) res.add(Jo.of("role", "user", "content", m.c));
            else if (m.r.equals("a") && m.k == null) res.add(Jo.of("role", "assistant", "content", m.c));
            else if (m.r.equals("a")) {
                ArrayList<Msg> rs = new ArrayList<>();
                for (int q = j + 1; q < out.size() && out.get(q).r.equals("t"); q++) rs.add(out.get(q));
                boolean all = true;
                ArrayList<Msg> picked = new ArrayList<>();
                for (int ci = 0; ci < m.k.length(); ci++) {
                    String id = m.k.optJSONObject(ci).optString("id");
                    Msg hit = null;
                    for (Msg t : rs) if (id.equals(t.i)) { hit = t; break; }
                    if (hit == null) { all = false; break; }
                    picked.add(hit);
                }
                if (tm && all) { // 每个调用都有结果才原样带上（各家都要求调用和结果一一对上）
                    JSONObject a = Jo.of("role", "assistant", "content", m.c, "calls", m.k);
                    if (m.x != null) Jo.put(a, "raw", m.x);
                    res.add(a);
                    for (int ci = 0; ci < m.k.length(); ci++) { JSONObject c = m.k.optJSONObject(ci); res.add(Jo.of("role", "tool", "id", c.optString("id"), "name", c.optString("name"), "content", picked.get(ci).c)); }
                } else if (!m.c.isEmpty()) res.add(Jo.of("role", "assistant", "content", m.c));
            }
        }
        return res;
    }
    /** 同一方连着的几条合成一条（有的服务商要求一问一答交替；带工具调用的、工具结果不合） */
    private static List<JSONObject> alternate(List<JSONObject> list) {
        ArrayList<JSONObject> out = new ArrayList<>();
        for (JSONObject m : list) {
            JSONObject p = out.isEmpty() ? null : out.get(out.size() - 1);
            String role = m.optString("role");
            if (p != null && p.optString("role").equals(role) && (role.equals("user") || role.equals("assistant")) && !p.has("calls") && !m.has("calls"))
                Jo.put(p, "content", p.optString("content") + "\n" + m.optString("content"));
            else out.add(Jo.copy(m));
        }
        return out;
    }
    /** JSONObject 的小工具（键按加进去的顺序） */
    static final class Jo {
        static JSONObject of(Object... kv) { JSONObject o = new JSONObject(); for (int i = 0; i + 1 < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]); return o; }
        static void put(JSONObject o, String k, Object v) { try { o.put(k, v); } catch (Exception ignored) { } }
        static JSONObject copy(JSONObject m) { JSONObject o = new JSONObject(); for (Iterator<String> it = m.keys(); it.hasNext(); ) { String k = it.next(); put(o, k, m.opt(k)); } return o; }
    }
    private static String pad(int x) { return (x < 10 ? "0" : "") + x; }
    private static final Map<String, String> SCENE = new HashMap<>();
    static { fill(SCENE, "day", "白天", "dusk", "傍晚", "night", "夜里"); }
    private final Map<String, int[]> since = new HashMap<>(); // 刚才车主点 / 喂 / 抛了哪只几次
    private void resetSince() { since.put("Neuron", new int[3]); since.put("Eviling", new int[3]); }
    { resetSince(); }
    String envText() {
        Calendar d = Calendar.getInstance();
        ArrayList<String> parts = new ArrayList<>();
        String sc = SCENE.get(app.currentScene);
        parts.add("现在 " + (d.get(Calendar.MONTH) + 1) + "月" + d.get(Calendar.DAY_OF_MONTH) + "日 周" + "日一二三四五六".charAt(d.get(Calendar.DAY_OF_WEEK) - 1) + " "
            + pad(d.get(Calendar.HOUR_OF_DAY)) + ":" + pad(d.get(Calendar.MINUTE)) + "，画面是" + (sc != null ? sc : "白天"));
        String day = app.todayText();
        if (!day.isEmpty()) parts.add(day);
        ArrayList<String> t = new ArrayList<>();
        for (String k : new String[] { "Neuron", "Eviling" }) {
            int[] s = since.get(k);
            ArrayList<String> x = new ArrayList<>();
            if (s[0] > 0) x.add("点了 " + s[0] + " 下");
            if (s[1] > 0) x.add("喂了 " + s[1] + " 块饼干");
            if (s[2] > 0) x.add("抛高高 " + s[2] + " 次");
            if (!x.isEmpty()) t.add(NAME.get(k) + "被" + android.text.TextUtils.join("、", x));
        }
        if (!t.isEmpty()) parts.add("刚才车主：" + android.text.TextUtils.join("；", t));
        parts.add("好感度：Neuro Lv." + App.levelOf(app.settings.love("Neuron")) + "、Evil Lv." + App.levelOf(app.settings.love("Eviling")) + "（1～10，越高越亲近车主）");
        String pg = app.page.name;
        if (!pg.isEmpty()) {
            String nm = pg;
            for (PetChat.Page p : PetChat.PAGES) if (p.id.equals(pg)) nm = p.name;
            parts.add("现在打开着" + nm + "页，你们站在页面上面");
        }
        if ("game".equals(app.mode)) parts.add("正在玩接饼干游戏");
        String c = carSummary();
        if (!c.isEmpty()) parts.add("车况：" + c);
        String sg = song();
        if (!sg.isEmpty()) parts.add("车里" + sg);
        String done = ctl.recent();
        if (!done.isEmpty()) parts.add("刚才的车机操作：" + done);
        return android.text.TextUtils.join("；", parts);
    }
    private static final Map<String, String> TAGS = new HashMap<>();
    static { fill(TAGS, "both", "[对两只说] ", "Neuron", "[只对 Neuro 说] ", "Eviling", "[只对 Evil 说] "); }
    private static final Pattern EVIL_RE = Pattern.compile("evil|eliv|坏牛肉", Pattern.CASE_INSENSITIVE), NEURO_RE = Pattern.compile("neuro|牛肉", Pattern.CASE_INSENSITIVE),
        BOTH_RE = Pattern.compile("evil[" + WSC + "-]*neuro|坏牛肉", Pattern.CASE_INSENSITIVE);
    /** 话里叫了名字：只让那只回答；两只都叫了或都没叫：按输入栏选的 */
    String targetOf(String text, String dflt) {
        boolean e = EVIL_RE.matcher(text).find(), n = NEURO_RE.matcher(BOTH_RE.matcher(text).replaceAll("")).find();
        if (e && !n) return "Eviling";
        if (n && !e) return "Neuron";
        return dflt != null && !dflt.isEmpty() ? dflt : set.to;
    }
    private Ask stopStream;
    void abortChat() { if (stopStream != null) { Ask a = stopStream; stopStream = null; a.stop(); } setThinking(false, null); }
    private static final Map<String, String> ERR = new HashMap<>();
    static { fill(ERR, "net", "aiNet", "timeout", "aiTimeout", "key", "aiKey", "balance", "aiBalance", "rate", "aiRate", "model", "aiModel", "server", "aiServer", "bad", "aiBad"); }
    // 要改设置才能好的（Key、余额、模型、连不上、请求出错、思考模型不说话）：说完在气泡里放"去改设置"，点了直接打开 AI 聊天设置页
    private static final Set<String> FIX = new HashSet<>(Arrays.asList("aiKey", "aiBalance", "aiModel", "aiNet", "aiNetCar", "aiBad", "aiThought"));
    private List<AskItem> fixBtn(String key) {
        if (!FIX.contains(key)) return null;
        ArrayList<AskItem> l = new ArrayList<>();
        l.add(new AskItem("去改设置", false, false, app::openAIPanel));
        return l;
    }

    // ================= 工具调用：宿主能力目录里有的工具；这个模型 / 中转站不支持时记住，以后改用文字指令 =================
    static final String NTKEY = "pet-ai-notools";
    static final int MAX_ROUNDS = 4, MAX_CALLS = 8, OUT_MAX = 6000;
    ArrayList<String> noTools;
    static ArrayList<String> loadNoTools() {
        ArrayList<String> out = new ArrayList<>();
        try {
            Object r = Json.parse(Store.get(NTKEY));
            if (r instanceof JSONArray) for (int i = 0; i < ((JSONArray) r).length(); i++) { Object x = ((JSONArray) r).opt(i); if (x instanceof String && ((String) x).length() < 600) out.add((String) x); }
        } catch (RuntimeException ignored) { }
        while (out.size() > 20) out.remove(0);
        return out;
    }
    private static String tkey(PetChat.Cfg cfg) { return cfg.format + "|" + cfg.base + "|" + cfg.model; }
    /** 这次的工具：list、能不能操作、能不能查询；能力目录还没拿到、这个模型不支持时 null（用文字指令） */
    static final class Tools { JSONArray list; boolean ops, query; }
    private Tools toolSet(PetChat.Cfg cfg) {
        JSONArray hostList = ctl.tools();
        if (hostList == null || noTools.contains(tkey(cfg))) return null;
        Tools t = new Tools();
        t.list = new JSONArray();
        for (int i = 0; i < hostList.length(); i++) {
            t.list.put(hostList.opt(i));
            String n = hostList.optJSONObject(i).optJSONObject("function").optString("name");
            if (ctl.query(n)) t.query = true; else t.ops = true;
        }
        JSONArray local = PetChat.localTools(pageNames(), searchConfig() != null);
        for (int i = 0; i < local.length(); i++) t.list.put(local.opt(i));
        return t;
    }
    private static JSONObject argsOf(String s) {
        try { Object o = Json.parse(s == null || s.isEmpty() ? "{}" : s); return o instanceof JSONObject ? (JSONObject) o : null; } catch (RuntimeException e) { return null; }
    }
    private static JSONObject bad(String code, String msg) { return Jo.of("ok", false, "error", AiControl.err(code, msg)); }
    private String systemPrompt(Tools ts) {
        String ops = ts == null ? ctl.prompt() : "";
        boolean canOp = ts != null ? ts.ops : !ops.isEmpty();
        PetChat.PersonaOpts o = new PetChat.PersonaOpts();
        o.car = carOk();
        o.search = searchConfig() != null;
        o.pages = pageNames();
        o.tools = ts != null ? new PetChat.ToolsOpt(ts.ops, ts.query) : null;
        o.ops = ops;
        o.groups = canOp ? ctl.groups() : new ArrayList<String>();
        return PetChat.persona(o);
    }

    /** ask 的选项：to 对谁说、search 能不能搜、fallback 失败（或 wait 毫秒还没开口）时改做的事、quiet 失败不说原因、after 开口时、love 开口的那只加好感、label 聊天室里显示的说明 */
    static final class Opts {
        String to = "both", label;
        boolean search, quiet, love;
        Runnable fallback;
        int wait;
        Car.PetAfter after;
    }
    private static final Pattern EVENT = Pattern.compile("^\\[事件\\]"), SRESULT = Pattern.compile("^\\[搜索结果\\]");
    /**
     * 发给模型，回复流式地说出来。用工具调用时：模型调了工具就按顺序执行（操作先过安全分级），结果交回去；要查到的东西才能接着说（查询、搜索、参数写错）或者还没开口时再问一轮。
     * 车主说一句最多问 4 轮、调 8 个工具。被打断时没执行完的调用记成没做 / 结果不确定。调用前要先 abortChat() / stopSpeech()
     */
    private void ask(PetChat.Cfg cfg, String text, Opts opts) {
        JSONObject m = media();
        if (m != null) ctl.sawApp(Car.str(m, "packageName")); // 能打开的应用：见过的播放器
        new Ask(cfg, text, opts).start();
    }
    final class Ask {
        final PetChat.Cfg cfg;
        final Opts opts;
        final String to, said;
        Tools ts;
        final List<Msg> before;
        final boolean event;
        final JSONObject umsg;
        JSONArray convo;
        final Line line;
        private final Runnable late = this::onLate;
        int rounds, ncalls, searched;
        boolean cancelled, got, unknown;
        Stop cur;
        JSONArray batch;
        int bi;

        Ask(PetChat.Cfg cfg, String text, Opts opts) {
            this.cfg = cfg;
            this.opts = opts;
            to = opts.to;
            ts = toolSet(cfg);
            before = new ArrayList<>(history);
            event = EVENT.matcher(text).find();
            said = event ? text : TAGS.get(to) + text;
            umsg = Jo.of("role", "user", "content", "（" + envText() + "）\n" + said);
            convo = build();
            resetSince();
            Msg u = Msg.of("u", said);
            if (opts.label != null) u.v = head(opts.label, 60);
            remember(u);
            line = new Line(to);
            line.event = event;
            line.love = !event || opts.love; // 聊天、两只对话：开口的那只加好感
            line.gen = true;
            line.after = opts.after;
        }
        JSONArray build() {
            JSONArray a = new JSONArray();
            a.put(Jo.of("role", "system", "content", systemPrompt(ts)));
            List<JSONObject> r = recent(ts != null, before);
            r.add(umsg);
            for (JSONObject x : alternate(r)) a.put(x);
            return a;
        }
        void start() {
            queue.add(line);
            stopStream = this;
            setThinking(true, firstWho(line));
            round();
            if (opts.fallback != null && opts.wait > 0) app.h.postDelayed(late, opts.wait);
        }
        private void onLate() { if (!got && rounds == 1 && stopStream == this) { stopStream = null; stop(); failed(null); } }
        void stop() { cancelled = true; if (cur != null) { cur.stop(); cur = null; } settle(); }
        void round() {
            rounds++;
            got = false;
            closeSeg(line); // 这一轮的话从新气泡开始
            line.raw.setLength(0);
            final PetChat.Parser p = PetChat.parser(tok -> {
                addToken(line, tok);
                if (!got && "text".equals(tok.t) && !line.mute) { got = true; app.h.removeCallbacks(late); }
                wake();
            });
            cur = PetChat.stream(cfg, convo, new PetChat.Handler() {
                @Override public void delta(String s) { p.push(s); line.raw.append(s); room.live(line.raw.toString(), to, firstWho(line)); }
                @Override public void done(String full, boolean thought, JSONObject tc) { cur = null; p.end(); if (cancelled) return; if (tc != null && ts != null) called(full, tc); else finish(full, thought); }
                @Override public void fail(Err e) { cur = null; p.end(); if (!cancelled) failed(e); }
            }, ts != null ? ts.list : null, ex);
        }
        void end() {
            app.h.removeCallbacks(late);
            line.done = true;
            if (stopStream == this) stopStream = null;
            if (line.segs.isEmpty() && line.confirm.isEmpty()) setThinking(false, null);
            room.live("", null, null);
            wake();
        }
        /** 没回上来：这句不留给模型（下次别接着它说）；车主打的字聊天室里照样留着，标"没发出去" */
        void popUser() {
            if (rounds != 1 || history.isEmpty() || !history.get(history.size() - 1).r.equals("u")) return;
            Msg u = history.remove(history.size() - 1);
            saveHistory();
            if (event || SRESULT.matcher(u.c).find()) room.update();
            else { Msg s = Msg.of("s", u.c).w("me"); s.t = u.t; s.f = true; remember(s); }
        }
        void finish(String full, boolean thought) {
            end();
            String sayTo = to.equals("both") ? "" : to;
            if (!AiControl.trim(full).isEmpty()) {
                Msg a = Msg.of("a", full).w(firstWho(line));
                remember(a);
                if (line.search != null && opts.search && searchConfig() != null) doSearch(cfg, line.search, to); // 文字指令：说"我查查"：去搜，搜完再问一次
                else if (line.segs.isEmpty() && line.confirm.isEmpty() && line.refused.isEmpty() && rounds == 1 && !opts.quiet) sayLocal(sayTo, "aiEmpty", null, null); // 说的全是另一只的话（被挡掉了）
            } else if (rounds == 1) { // 一个字都没说（思考模型把字数用在想上了）
                popUser();
                if (opts.fallback != null) opts.fallback.run();
                else if (!opts.quiet) { String k = thought ? "aiThought" : "aiEmpty"; sayLocal(sayTo, k, null, fixBtn(k)); }
            }
        }
        void failed(Err e) {
            if (e != null && "notools".equals(e.code) && ts != null && rounds == 1) { // 这个模型 / 中转站不支持工具调用：记住，改用文字指令再问一次
                Log.w(TAG, "不支持工具调用，改用文字指令：" + e.status + " " + e.msg);
                noTools.add(tkey(cfg));
                while (noTools.size() > 20) noTools.remove(0);
                Store.set(NTKEY, Json.stringify(new JSONArray(noTools)));
                ts = null; convo = build(); rounds = 0;
                round();
                return;
            }
            end();
            popUser();
            setThinking(false, null);
            if (e != null) Log.w(TAG, "聊天请求失败：" + e.code + " " + e.status + " " + e.msg);
            if (opts.fallback != null) { opts.fallback.run(); return; }
            if (opts.quiet) return;
            String key = e != null && ERR.containsKey(e.code) ? ERR.get(e.code) : "aiBad";
            sayLocal(to.equals("both") ? "" : to, key, null, fixBtn(key));
        }

        /* 模型调了工具：记下这条回复，按顺序执行，结果交回去 */
        private boolean spoke, more;
        private final ArrayList<Object[]> fails = new ArrayList<>();
        void called(String full, JSONObject tc) {
            JSONArray list = tc.optJSONArray("list");
            JSONObject raw = tc.optJSONObject("raw");
            if (list == null) { finish(full, false); return; }
            Msg a = Msg.of("a", full).w(firstWho(line));
            a.k = list; a.x = raw;
            remember(a);
            JSONObject am = Jo.of("role", "assistant", "content", full, "calls", list);
            if (raw != null) Jo.put(am, "raw", raw);
            convo.put(am);
            spoke = !AiControl.trim(full).isEmpty();
            more = false;
            fails.clear();
            batch = list; bi = 0;
            if (line.segs.isEmpty()) setThinking(true, firstWho(line));
            next(list);
        }
        private void next(final JSONArray list) {
            if (cancelled || batch != list) return;
            if (bi >= list.length()) { batch = null; after(); return; }
            final JSONObject call = list.optJSONObject(bi);
            run1(call, (res, mr, fail) -> {
                if (cancelled || batch != list) return;
                if (mr) more = true;
                if (fail != null) fails.add(fail);
                record(call, res);
                bi++;
                next(list);
            });
        }
        private void after() {
            if (rounds < MAX_ROUNDS && ncalls < MAX_CALLS && (more || !spoke)) { line.refused = new ArrayList<>(); round(); return; } // 下一轮按结果说（开车不能做的、没做成的也由模型说）
            end();
            String who = !line.who.isEmpty() ? line.who : firstWho(line);
            for (Object[] x : fails) failSay(who, (AiControl.Op) x[0], (String) x[1], (String) x[2]);
        }
        /** 执行一个调用的结果：res 交给模型、mr 要不要再问一轮、fail 没做成的操作 { op, code, reason } */
        private void run1(JSONObject call, R1 cb) {
            JSONObject a = argsOf(call.optString("args"));
            String n = call.optString("name");
            if (++ncalls > MAX_CALLS) { cb.done(bad("NOT_EXECUTED", "这次调用的工具太多了，没做"), false, null); return; }
            if (a == null) { cb.done(bad("INVALID_ARGUMENT", "参数要是一个 JSON 对象"), true, null); return; }
            if (n.equals("pet_open_page")) {
                PetChat.Page pg = PetChat.findPage(a.opt("page"));
                if (pg == null || !pageOk(pg.id)) { cb.done(bad("INVALID_ARGUMENT", "没有这个页面"), true, null); return; }
                Part pt = new Part("page"); pt.id = pg.id;
                openSeg(line).parts.add(pt); // 话说到这里时打开
                cb.done(Jo.of("ok", true, "result", Jo.of("page", pg.name)), false, null);
                return;
            }
            if (n.equals("pet_web_search")) {
                PetSearch.Cfg sc = searchConfig();
                final String q = head(AiControl.trim(a.opt("query") == null || a.opt("query") == JSONObject.NULL ? "" : String.valueOf(a.opt("query"))), 60);
                if (sc == null || !opts.search) { cb.done(bad("UNSUPPORTED", "现在不能搜"), true, null); return; }
                if (q.isEmpty()) { cb.done(bad("INVALID_ARGUMENT", "没有关键词"), true, null); return; }
                if (++searched > 2) { cb.done(bad("LIMIT_REACHED", "这次已经搜过两次了，按已有的结果回答"), true, null); return; }
                PetSearch.search(sc, q, ex, new Done<List<PetSearch.Result>>() {
                    @Override public void ok(List<PetSearch.Result> list) { cb.done(Jo.of("ok", true, "result", PetSearch.format(q, list)), true, null); }
                    @Override public void fail(Err e) { Log.w(TAG, "搜索失败：" + e.code + " " + e.msg); cb.done(bad(e.code != null ? e.code : "bad", PetSearch.errText(e, false)), true, null); }
                });
                return;
            }
            if (ctl.query(n)) {
                final String name = n;
                ctl.callTool(n, ctl.clean(n, a), (r, code, reason, raw) -> {
                    if (code == null) { cb.done(Jo.of("ok", true, "result", r), true, null); return; }
                    JSONObject err = AiControl.err(code.isEmpty() ? "DEVICE_ERROR" : code, raw == null ? "" : raw);
                    if (("INVALID_ARGUMENT".equals(code) || "UNKNOWN_FIELD".equals(code)) && name.equals("uiplus_vehicle_get") && !ctl.vehicleKeys().isEmpty())
                        Jo.put(err, "hint", "keys 只能用这些字段名：" + android.text.TextUtils.join(",", ctl.vehicleKeys())); // 模型自己编了字段名：给它全部的，改了再查
                    cb.done(Jo.of("ok", false, "error", err), true, null);
                });
                return;
            }
            final AiControl.Op op = ctl.toolOp(n, a);
            if (op.err != null) { cb.done(Jo.of("ok", false, "error", op.err), true, null); return; }
            if (unknown) { cb.done(bad("NOT_EXECUTED", "前一个操作不知道做没做成，先不做别的"), false, null); return; }
            int lv = ctl.level(op, carNow());
            if (lv == AiControl.DIRECT && line.event) lv = AiControl.CONFIRM; // 她们自己开口时提的操作，车主点了才做
            if (lv == AiControl.DRIVING) { ctl.note(op, "没做（开车时不能）"); line.refused.add(op); cb.done(bad("REFUSED_DRIVING", "车在开，卡片不让做这个"), false, null); return; }
            if (lv != AiControl.DIRECT) { line.confirm.add(op); cb.done(bad("NEEDS_CONFIRM", "卡片已经在气泡里请车主点按钮确认，车主点了才会做；你问一句要不要就好，别再调用"), false, null); return; }
            ctl.run(op, (r, code, reason, raw) -> {
                if (code == null) { cb.done(Jo.of("ok", true, "result", r), false, null); return; }
                if (code.equals("TIMEOUT") || code.equals("SESSION_CLOSED") || code.equals("BACKEND_UNAVAILABLE")) unknown = true; // 可能已经做了：这次不再做别的
                JSONObject err = AiControl.err(code, reason);
                if (code.equals("INVALID_ARGUMENT")) {
                    Jo.put(err, "message", "参数不对" + (raw != null && !raw.isEmpty() ? "（" + raw + "）" : "") + "，照工具说明改好了再调用一次");
                    cb.done(Jo.of("ok", false, "error", err), true, null);
                    return;
                }
                cb.done(Jo.of("ok", false, "error", err), false, new Object[] { op, code, reason });
            });
        }
        void record(JSONObject call, JSONObject res) {
            String c = Json.stringify(res);
            boolean okv = res.optBoolean("ok");
            if (c.length() > OUT_MAX) c = Json.stringify(Jo.of("ok", okv, "truncated", true, "text", c.substring(0, OUT_MAX)));
            convo.put(Jo.of("role", "tool", "id", call.optString("id"), "name", call.optString("name"), "content", c));
            Msg t = Msg.of("t", c.length() > 1500 ? Json.stringify(Jo.of("ok", okv, "truncated", true, "text", c.substring(0, 1500))) : c);
            t.i = call.optString("id"); t.n = call.optString("name");
            remember(t);
        }
        /** 被打断：这批里还没交结果的都补上（正在执行的那个结果不确定，后面的没做），记录里调用和结果才对得上 */
        void settle() {
            if (batch == null) return;
            JSONArray list = batch;
            batch = null;
            for (int i = bi; i < list.length(); i++) record(list.optJSONObject(i), i == bi ? bad("RESULT_UNKNOWN", "被打断了，不知道做没做成") : bad("NOT_EXECUTED", "被打断了，没做"));
        }
    }
    interface R1 { void done(JSONObject res, boolean more, Object[] fail); }

    /** 联网搜索（文字指令时）：搜完把结果作为一条消息再问模型（这一轮不再搜）；车主又说了新的话就作废 */
    private int searchSeq;
    private void doSearch(final PetChat.Cfg cfg, final String q, final String to) {
        final int id = ++searchSeq;
        setThinking(true, to.equals("both") ? "" : to);
        PetSearch.search(searchConfig(), q, ex, new Done<List<PetSearch.Result>>() {
            @Override public void ok(List<PetSearch.Result> list) {
                if (id != searchSeq) return;
                Opts o = new Opts();
                o.to = to; o.label = "搜了“" + q + "”";
                ask(cfg, PetSearch.format(q, list), o);
            }
            @Override public void fail(Err e) {
                if (id != searchSeq) return;
                setThinking(false, null);
                Log.w(TAG, "搜索失败：" + e.code + " " + e.msg);
                sayLocal(to.equals("both") ? "" : to, "aiSearchFail", App.vars("why", PetSearch.errText(e, false)), null);
            }
        });
    }
    /** 车主说的一句话（输入栏、点选项）；to：输入栏选的对象（话里叫了名字时以名字为准） */
    void send(String text, String to) {
        text = AiControl.trim(text == null ? "" : text);
        if (text.length() > 300) text = text.substring(0, 300);
        if (text.isEmpty() || !enabled()) return;
        PetChat.Cfg cfg = chatConfig();
        String who = targetOf(text, to);
        abortChat();
        stopSpeech();
        if (cfg == null) { sayLocal(who.equals("both") ? "" : who, "aiNoKey", null, null); return; }
        activity();
        searchSeq++;
        Opts o = new Opts();
        o.search = true; o.to = who;
        ask(cfg, text, o);
    }
    /**
     * 主动开口（闲聊、两只对话、车况事件）：返回 true 表示交给模型了。急事（安全提醒）打断正在说的；平时在打字、开着设置 / 页面、正在说话时不插嘴。
     * fallback：模型失败（或急事 4 秒没开口）时改做的事
     */
    private boolean speakUp(String text, Runnable fallback, boolean urgent, Car.PetAfter after, boolean love, String label) {
        if (!ready() || app.hidden) return false;
        if (!urgent && (typing() || busy() || app.quiet() || app.busy())) return false;
        if (urgent) { abortChat(); stopSpeech(); }
        Opts o = new Opts();
        o.to = "both"; o.fallback = fallback; o.wait = urgent ? 4000 : 0; o.quiet = true; o.after = after; o.love = love; o.label = label;
        ask(chatConfig(), "[事件] " + text, o);
        return true;
    }
    boolean busy() { return stopStream != null || P != null || !queue.isEmpty() || (thinkB != null && alive(thinkB)); }
    void stop() { abortChat(); stopSpeech(); }

    // ================= 主动开口的额度：每天最多 60 次（闲聊、车况一起算），普通的之间至少隔 2 分钟 =================
    static final String PKEY = "pet-ai-pro";
    static final int DAILY = 60;
    static final long GAP = 120000;
    private long lastNormal, lastSong;
    private String proD = "";
    private int proN;
    private void loadPro() {
        Object r = null;
        try { r = Json.parse(Store.get(PKEY)); } catch (RuntimeException ignored) { }
        JSONObject o = r instanceof JSONObject ? (JSONObject) r : new JSONObject();
        proD = o.opt("d") instanceof String ? (String) o.opt("d") : "";
        double n = o.opt("n") instanceof Number ? ((Number) o.opt("n")).doubleValue() : -1;
        proN = n >= 0 && n <= 1000 ? (int) n : 0;
    }
    private static String today() { Calendar d = Calendar.getInstance(); return d.get(Calendar.YEAR) + "-" + (d.get(Calendar.MONTH) + 1) + "-" + d.get(Calendar.DAY_OF_MONTH); }
    private boolean quota() { if (!proD.equals(today())) { proD = today(); proN = 0; } return proN < DAILY; }
    private void spend() { proN++; Store.set(PKEY, Json.stringify(Jo.of("d", proD, "n", (long) proN))); }

    // ================= 车况事件（Car.talk 交过来）：安全类、给模型的说明、换歌（偶尔才说）；没列的照旧说本地台词 =================
    private interface EvText { String text(Map<String, Object> v); }
    private static final class Ev { final boolean urgent, song; final EvText t; Ev(boolean u, EvText t, boolean s) { urgent = u; this.t = t; song = s; } }
    private static String vs(Map<String, Object> v, String k) { Object x = v.get(k); return x == null ? "" : AiControl.str(x); }
    private final Map<String, Ev> EV = new HashMap<>();
    {
        EV.put("tyreWarn", new Ev(true, v -> vs(v, "tyre") + "轮胎压" + vs(v, "state") + "。提醒车主注意，找地方停下检查。", false));
        EV.put("doorOpen", new Ev(true, v -> "车正在开，有车门被打开了！赶紧提醒车主。", false));
        EV.put("openMoving", new Ev(true, v -> "车开起来了，但" + vs(v, "what") + "还开着。马上提醒车主停下关好。", false));
        EV.put("belt", new Ev(true, v -> "车开起来了，主驾没系安全带。提醒车主系上。", false));
        EV.put("lightsNight", new Ev(true, v -> "天黑了，车速已经过 20，但大灯没开。提醒车主开大灯。", false));
        EV.put("batteryLow", new Ev(true, v -> "电量掉到 " + vs(v, "n") + "% 了。提醒车主找地方充电。", false));
        EV.put("fuelLow", new Ev(true, v -> "油量掉到 " + vs(v, "n") + "% 了。提醒车主找地方加油。", false));
        EV.put("rangeLow", new Ev(true, v -> "总续航只剩 " + vs(v, "km") + " 公里了。提醒车主赶紧充电或加油。", false));
        EV.put("lowVoltage", new Ev(true, v -> "车的低压供电报了异常（小电瓶可能有问题）。提醒车主留意，尽快检查。", false));
        EV.put("keyBattery", new Ev(true, v -> "车钥匙电池电量低了。提醒车主换个电池。", false));
        EV.put("carOn", new Ev(false, v -> "车主刚上车，车子启动了。跟车主打个招呼。", false));
        EV.put("chargeStart", new Ev(false, v -> "车开始充电了" + (jsTruthy(v.get("n")) ? "，现在电量 " + vs(v, "n") + "%" : "") + "。随口说一句。", false));
        EV.put("chargeDone", new Ev(false, v -> "车充满电了。告诉车主。", false));
        EV.put("chargeStop", new Ev(false, v -> "充电还没充满就断了。告诉车主看看是怎么回事。", false));
        EV.put("hot", new Ev(false, v -> "车外 " + vs(v, "t") + "°C，很热。随口关心一句。", false));
        EV.put("cold", new Ev(false, v -> "车外只有 " + vs(v, "t") + "°C，很冷。随口关心一句。", false));
        EV.put("stuffy", new Ev(false, v -> "车里 " + vs(v, "t") + "°C，又闷又热。提醒车主开空调或开窗透透气。", false));
        EV.put("pmBad", new Ev(false, v -> "车外空气很差（PM2.5 重度污染）。提醒车主关窗、开内循环。", false));
        EV.put("pmIndoor", new Ev(false, v -> "车里空气很差（PM2.5 重度污染）。提醒车主开空调净化或者换换气。", false));
        EV.put("milestone", new Ev(false, v -> "这辆车的总里程刚满 " + vs(v, "n") + " 公里。说一句。", false));
        EV.put("fast", new Ev(false, v -> "车速刚过 120 km/h。提醒车主别开太快。", false));
        EV.put("slope", new Ev(false, v -> "车正在爬一个很陡的坡。随口说一句。", false));
        EV.put("rest", new Ev(false, v -> "车主这次已经连续用车 " + vs(v, "n") + " 个小时了。提醒他找地方歇一会儿。", false));
        EV.put("musicNew", new Ev(false, v -> songEvent(), true));
        EV.put("musicNewBy", new Ev(false, v -> songEvent(), true));
    }
    private String songEvent() {
        JSONObject m = media();
        String t = m == null ? null : Car.str(m, "title");
        if (t == null || t.isEmpty()) return "";
        String a = Car.str(m, "artist");
        return "车里换了一首歌：《" + t + "》" + (a != null && !a.isEmpty() ? "，" + a + "唱的" : "") + "。随口聊一句这首歌（不熟就别编歌词和故事）。";
    }
    private static final Pattern SENT = Pattern.compile("[。！？]");
    /** Car / App 交过来的事件：交给模型就返回 true（local 是本地台词，模型失败时用）；不交返回 false，调用方照旧说本地台词 */
    boolean carLine(String key, Map<String, Object> vars, Car.Opt opt, final App.Lp local) {
        Ev ev = EV.get(key);
        if (ev == null || !set.proactive || !ready() || !quota()) return false;
        final long t = System.currentTimeMillis();
        if (!ev.urgent && t - lastNormal < GAP) return false;
        if (ev.song && (t - lastSong < 600000 || app.rnd.nextFloat() > 0.3f)) return false; // 换歌偶尔才聊
        String text = ev.t.text(vars != null ? vars : new HashMap<String, Object>());
        if (text.isEmpty()) return false;
        Runnable fb = () -> { if (System.currentTimeMillis() - t < 8000 && local != null) local.run(); }; // 太晚了就不补了
        String label = head(SENT.split(text, 2)[0], 40);
        if (!speakUp(text, fb, ev.urgent, opt != null ? opt.after : null, false, label)) return false;
        spend();
        if (!ev.urgent) lastNormal = t;
        if (ev.song) lastSong = t;
        return true;
    }

    // ================= 没事时找车主聊天 =================
    private static long idleOf(String chatty) { return "low".equals(chatty) ? 900000 : "mid".equals(chatty) ? 480000 : "high".equals(chatty) ? 240000 : 0; }
    private final Runnable idleR = this::idleChat;
    private boolean idleOn;
    private int idleTries;
    private String lastTopic = "";
    private long idleGap() { return idleOf(set.chatty); }
    /** 重新计时（说完话、车主碰宠物 / 说话、改了设置都从头算），间隔前后浮动两成多 */
    private void scheduleIdle(long ms) {
        app.h.removeCallbacks(idleR);
        idleOn = false;
        long g = idleGap();
        if (g > 0 && ready() && !app.hidden && !dead) { idleOn = true; app.h.postDelayed(idleR, ms > 0 ? ms : (long) (g * (0.75 + app.rnd.nextFloat() * 0.5))); }
    }
    private void activity() { idleTries = 0; scheduleIdle(0); }
    private void idleChat() {
        idleOn = false;
        if (!ready() || !quota() || "off".equals(cv("power.level"))) { scheduleIdle(0); return; } // 熄火了不说
        if (System.currentTimeMillis() - lastNormal < GAP) { scheduleIdle(GAP - (System.currentTimeMillis() - lastNormal) + 1000); return; } // 刚主动说过（车况事件）：隔够 2 分钟
        ArrayList<String> topics = new ArrayList<>(Arrays.asList("现在这个时间段", "刚才车主对你们做的事", "你们俩之间的小斗嘴", "Evil 最新的毁灭世界计划", "Neuro 又想要饼干了", "画面里的景色", "关心一下车主累不累", "Neuro 又在假装直播", "今天是什么日子"));
        if (song().startsWith("在放")) topics.add("车里正在放的歌");
        if (carOk()) topics.add("车况里值得一提的");
        topics.remove(lastTopic);
        String topic = topics.get(app.rnd.nextInt(topics.size()));
        if (!speakUp("有一会儿没人说话了，你们俩随口说点什么（可以聊：" + topic + "）。一两行就好，别问\"在吗\"，别重复前面说过的话，不一定要问车主问题。", null, false, null, false, "闲聊")) {
            if (idleTries++ < 3) { scheduleIdle(20000); return; } // 正忙：等会儿再试
            idleTries = 0; scheduleIdle(0); return;
        }
        lastTopic = topic;
        spend();
        lastNormal = System.currentTimeMillis();
    }

    // ================= 两只对话（闲聊、随机事件里原来演本地剧本的时候）：让模型现编一段；"没事时找你聊天"关了、额度用完、离上次主动开口不到 2 分钟时照旧演本地剧本 =================
    private static final String[] DIALOG = { "斗嘴", "Evil 的毁灭世界计划", "Neuro 想要饼干", "画面里的景色", "车里在放的歌", "今天是什么日子", "Neuro 又以为自己在直播", "谁更受车主喜欢", "车主刚才对你们做的事", "现在这个时间段", "鱼叉", "要不要一起唱首歌" };
    private String lastDialog = "";
    private long lastDialogAt;
    /** Play 的 dialogue() 调：交给模型就返回 true（local：演本地剧本，模型失败时用） */
    boolean dialogue(final App.Lp local) {
        long now = System.currentTimeMillis();
        if (!ready() || idleGap() == 0 || !quota() || now - lastNormal < GAP || now - lastDialogAt < idleGap()) return false; // 频率跟着“没事时找你聊天”走
        ArrayList<String> list = new ArrayList<>();
        boolean playing = song().startsWith("在放");
        for (String x : DIALOG) if (!x.equals(lastDialog) && (!x.equals("车里在放的歌") || playing)) list.add(x);
        String topic = list.get(app.rnd.nextInt(list.size()));
        final long t = now;
        Runnable fb = () -> { if (System.currentTimeMillis() - t < 10000 && local != null) local.run(); }; // 太晚了就不补了
        if (!speakUp("你们俩来一段小对话：3～6 行，两只轮流说，像车主不在旁边时你们俩自己聊（这次可以聊：" + topic + "）。别重复前面说过的话，不用问车主问题。", fb, false, null, true, "两只聊起来了")) return false;
        lastDialog = topic;
        spend();
        lastNormal = lastDialogAt = t;
        return true;
    }

    /** 互动计数（放进下一句话前面的括号里）、碰了宠物闲聊重新计时 */
    void poke(String pack, String what) {
        int[] s = since.get(pack);
        int i = "tap".equals(what) ? 0 : "feed".equals(what) ? 1 : "toss".equals(what) ? 2 : -1;
        if (s != null && i >= 0) s[i]++;
        if (ready()) activity();
    }

    // ================= 聊天键 + 输入栏：输入栏在底部，左边的对象键切换跟谁说 =================
    private boolean inputOpen;
    /** 车主在打字（主动开口不插嘴）：开着输入栏；开着聊天室时要正在输入框里才算（不然开着聊天室它们就不自己聊了） */
    boolean typing() { return inputOpen && (!room.isOpen() || (bar != null && bar.focused())); }
    void syncTo() { if (bar != null) bar.setTo(set.to); }
    void openBar() { if (inputOpen) closeInput(false); else openInput(); }
    void openInput() {
        if (!enabled()) return;
        if (chatConfig() == null) { stopSpeech(); sayLocal(set.to.equals("both") ? "" : set.to, "aiNoKey", null, null); app.openAIPanel(); return; }
        if (bar == null) bar = new AiBar(ctx, this);
        if (bar.getParent() == null) host.add(bar);
        inputOpen = true;
        bar.setVisibility(android.view.View.VISIBLE);
        app.ui.typing = true; // 输入栏开着：齿轮、聊天键、全屏键、导航藏起来
        app.ui.sync();
        syncTo();
        layout();
        if (!room.isOpen()) bar.animIn();
        bar.focusInput();
    }
    /** 收起输入栏（开着聊天室时输入栏在聊天室里，只收键盘；all：连聊天室一起关） */
    void closeInput(boolean all) {
        if (!inputOpen) return;
        if (room.isOpen()) { if (!all) { if (bar != null) bar.blur(); return; } room.close(); }
        inputOpen = false;
        if (bar != null) { bar.blur(); bar.setVisibility(android.view.View.GONE); }
        app.ui.typing = false;
        app.ui.sync();
    }
    /** 输入栏发送：开着聊天室时只收键盘，看它们回 */
    void submit(String t) {
        t = AiControl.trim(t == null ? "" : t);
        closeInput(false);
        if (!t.isEmpty()) send(t, set.to);
    }
    void nextTo() { set.to = TARGETS.get((TARGETS.indexOf(set.to) + 1) % TARGETS.size()); saveSettings(); syncTo(); }
    void outside() { closeInput(false); } // 点了输入栏外面的画面：收起输入栏
    boolean barOpen() { return inputOpen; }
    void openRoom() { room.open(); }
    void roomClosed() { layout(); if (inputOpen && bar != null) bar.animIn(); } // 输入栏回到原处：照样从下面升起
    /** 返回键：先关聊天室，再收输入栏 */
    boolean back() {
        if (room.isOpen()) { room.close(); return true; }
        if (inputOpen) { closeInput(false); return true; }
        return false;
    }
    /** 卡片大小变了（拖中缝、横竖切换、弹出键盘）：输入栏、聊天室跟着可见区域摆 */
    void layout() {
        room.layout();
        if (bar == null || !inputOpen) return;
        float rem = app.u.px(app.u.rem), W = app.u.px(app.u.W), V = app.u.px(app.u.visH), h = .8f * rem;
        if (room.isOpen()) {
            android.graphics.RectF rr = room.rect();
            Layer.place(bar, rr.left + .14f * rem, rr.bottom - .16f * rem - h, rr.width() - .28f * rem, h);
            bar.setRoom(true);
        } else {
            Layer.place(bar, .22f * rem, V - .2f * rem - h, W - .44f * rem, h);
            bar.setRoom(false);
        }
    }

    // ================= 显示不显示、设置变了 =================
    private boolean lastOn;
    /** 聊天键、设置里 AI 那行显示不显示（宠物、气泡关了都藏）；Ui.sync 每次都问，开关变了在这里接上 */
    boolean available() {
        boolean on = enabled();
        if (on != lastOn) { lastOn = on; app.h.post(this::sync); }
        return on;
    }
    String label() { return (chatConfig() != null ? PetChat.find(set.provider).name : "未设置"); }
    /** 宠物、气泡开关变了；设置、记录在另一个页面（全屏页 / 小窗口）改过时读回来 */
    void sync() {
        if (dead) return;
        boolean on = enabled();
        lastOn = on;
        if (!on) { closeInput(true); abortChat(); stopSpeech(); app.h.removeCallbacks(idleR); idleOn = false; }
        else if (!idleOn) scheduleIdle(0);
    }
    void settingsChanged() {
        if (!busy() && !inputOpen) {
            saveNowIfPending();
            set = loadSettings();
            history = loadHistory();
            noTools = loadNoTools();
            loadPro();
            room.update();
        }
        sync();
    }
    private void saveNowIfPending() { if (savePending) saveNow(); }
    void hostChanged() { ctl.refresh(); }
    void setHidden(boolean h) {
        if (h) { closeInput(false); abortChat(); stopSpeech(); app.h.removeCallbacks(idleR); idleOn = false; saveNowIfPending(); }
        else scheduleIdle(0);
    }
    void destroy() {
        abortChat();
        stopSpeech();
        saveNowIfPending();
        dead = true;
        room.close();
        app.h.removeCallbacks(idleR);
        app.h.removeCallbacks(pumpR);
        if (panel != null) panel.drop();
        panel = null;
        if (bar != null) { bar.blur(); host.removeView(bar); bar = null; }
    }

    // ================= 设置 → AI 聊天（面板里的二级页） =================
    AiPanel panel;
    /** 测试连接、测试搜索的结果（面板重建后接着显示） */
    String testText = "发一句最短的话试试能不能连上", testCls = "", searchText = "搜一下“今天的新闻”试试", searchCls = "";
    void buildPanel(Kit k, LinearLayout body) {
        if (panel != null) panel.drop();
        panel = new AiPanel(this, k, body);
    }
    /** 清空对话记录 */
    void clearHistory() {
        history = new ArrayList<>();
        saveNow();
        room.update();
    }
    void chatty(String v) { set.chatty = v; saveSettings(); activity(); }
    String fmtSecs(long ms) { return String.format(Locale.US, "%.1f", ms / 1000.0); }
}
