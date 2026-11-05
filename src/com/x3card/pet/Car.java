package com.x3card.pet;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 车机联动（同网页版 car.js）：订阅车况和媒体，宠物对车况接话、时钟下方显示车况胶囊和实时歌词、放歌时宠物跟着唱，车况页显示全部车况。
 * 宿主给外部应用的接口返回新版格式（{data: 业务树}、数字编码、同节点伴随属性；首次全量、之后只给变化的叶子），按文档逐叶子合并、解码成统一的值（DEC）。
 * 常驻一个车况订阅（接话、胶囊、行程要用的字段）+ 一个媒体订阅；只在车况页上显示的字段等车况页打开时才另订。看不见时退订，回来重订；gen 挡掉旧回调。
 * 只对"变化"接话：第一个快照只当基线（持续的问题延时说一次），每类台词有冷却，车况台词之间至少隔 8 秒，重要的排队。
 * 行程：开车时把本次时长、里程按增量累计存本地；熄火或隔了很久再上车算上一趟，下次开机两只一唱一和说一下
 */
final class Car {
    // 常驻订阅的字段（接话、胶囊、行程要用），按重要程度排（资源超限时从后往前砍）
    static final String[] CORE = {
        "power.level", "driving.gear", "driving.speed",
        "tyre.frontLeft.state", "tyre.frontRight.state", "tyre.rearLeft.state", "tyre.rearRight.state",
        "tyre.frontLeft.pressure", "tyre.frontRight.pressure", "tyre.rearLeft.pressure", "tyre.rearRight.pressure",
        "energy.batteryPercent", "energy.fuelPercent", "energy.electricRange", "energy.fuelRange",
        "energy.chargingState", "energy.charging.remainingTime", "energy.connectorState",
        "seat.driver.beltFastened",
        "warning.lowVoltageBatteryFault", "warning.keyBatteryLow",
        "body.doors.frontLeft.state", "body.doors.frontRight.state", "body.doors.rearLeft.state", "body.doors.rearRight.state",
        "body.hood.state", "body.tailgate.state", "body.windows.anyOpen", "body.sunroof.position",
        "body.windows.frontLeft.position", "body.windows.frontRight.position", "body.windows.rearLeft.position", "body.windows.rearRight.position",
        "driving.sessionDuration", "driving.sessionDistance", "driving.evSessionDistance", "driving.hevSessionDistance", "driving.totalDistance",
        "ac.outdoorTemperature", "ac.indoorTemperature", "ac.power", "ac.driverTemperature", "ac.frontDefrost", "ac.rearDefrost",
        "seat.driver.heating", "seat.passenger.heating", "seat.driver.ventilation", "seat.passenger.ventilation",
        "energy.operationMode", "energy.mode", "energy.regenerationLevel", "energy.simulator", "energy.socSave", "energy.socTarget",
        "energy.wirelessChargingState",
        "light.lowBeam", "light.highBeam", "light.frontFog", "light.rearFog", "light.turnSignal.left", "light.turnSignal.right", "light.hazard", "light.brake",
        "light.ambient.color", "light.ambient.musicMode", "light.dynamicColorful",
        "environment.pm25.outdoorLevel", "environment.pm25.indoorLevel", "environment.slope",
        "audio.muted", "audio.volume",
        "energy.wirelessCharging", "ac.circulation", "body.windows.ventilation", "body.sunshade.position", "display.dayNightMode",
        "ac.rear.power", "ac.controlMode", "steeringWheel.heating"
    };
    // 只在车况页上显示的字段：车况页打开时才订阅
    static final String[] DETAIL = {
        "driving.journeyDistance", "driving.evTotalDistance", "driving.hevTotalDistance", "driving.averageSpeed",
        "energy.charging.power", "energy.charging.capacity", "energy.charging.type",
        "energy.consumption.averageElectric", "energy.consumption.recentElectric", "energy.consumption.instantElectric",
        "energy.consumption.averageFuel", "energy.consumption.recentFuel", "energy.consumption.instantFuel",
        "ac.compressor", "ac.fanLevel",
        "body.windows.halfOpen",
        "light.daytimeRunning", "light.side", "light.ambient.area", "light.ambient.brightness",
        "environment.pm25.indoor", "environment.pm25.outdoor",
        "tyre.displayUnit", "tyre.frontLeft.temperature", "tyre.frontRight.temperature", "tyre.rearLeft.temperature", "tyre.rearRight.temperature",
        "engine.rpm", "engine.coolantTemperature",
        "audio.navigationVolume", "maintenance.distance", "maintenance.days",
        "body.pedals.accelerator", "body.pedals.brake",
        "ac.windMode", "ac.rear.temperature", "ac.rear.fanLevel", "ac.rear.controlMode", "ac.rear.windMode", "motor.front.rpm", "motor.rear.rpm",
        "radar.distance.leftFront", "radar.distance.rightFront", "radar.distance.leftRear", "radar.distance.rightRear", "radar.distance.left", "radar.distance.right",
        "radar.distance.frontLeftMid", "radar.distance.frontRightMid", "radar.distance.middleRear",
        "warning.bsd.left", "warning.bsd.right", "warning.dow.left", "warning.dow.right", "warning.rcta.left", "warning.rcta.right",
        "body.doors.lockState", "body.doors.remoteUnlocking" // 车门锁、远程解锁：0 / 1 的含义按车型定，只在车况页上照原样显示，不接话
    };
    static final String VIN = "identity.vin";

    // ================= 新版宿主的数字编码 → 统一的值 =================
    private static final Map<String, Map<Integer, Object>> DEC = new HashMap<>();
    private static Map<Integer, Object> m(Object... kv) { HashMap<Integer, Object> o = new HashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) o.put((Integer) kv[i], kv[i + 1]); return o; }
    static {
        Map<Integer, Object> B01 = m(0, false, 1, true), B12 = m(1, true, 2, false), OC = m(0, "closed", 1, "open"), AUTO = m(0, "auto", 1, "manual");
        Map<Integer, Object> WIND = m(1, "face", 2, "faceFeet", 3, "feet", 4, "feetScreen", 5, "screen", 6, "faceFeetScreen", 7, "faceScreen");
        Map<Integer, Object> PM = m(1, "excellent", 2, "good", 3, "light", 4, "moderate", 5, "heavy", 6, "severe");
        DEC.put("driving.gear", m(0, "N", 1, "R", 2, "D", 3, "P"));
        DEC.put("power.level", m(0, "off", 1, "acc", 2, "on", 3, "ready", 4, "fakeReady"));
        DEC.put("energy.wirelessCharging", B12); DEC.put("energy.socSave", m(1, false, 2, true)); DEC.put("energy.simulator", B01);
        DEC.put("energy.regenerationLevel", m(1, "standard", 2, "high"));
        DEC.put("energy.operationMode", m(1, "eco", 2, "sport", 3, "comfort"));
        DEC.put("energy.mode", m(1, "ev", 3, "hev"));
        DEC.put("energy.chargingState", m(1, "ready", 2, "charging", 3, "finished", 4, "terminated"));
        DEC.put("energy.connectorState", m(1, "none", 2, "ac", 3, "dc", 4, "acDc", 5, "vtol"));
        DEC.put("energy.charging.type", m(1, "default", 2, "ac", 3, "vtog", 4, "gbDc", 5, "gbNonDc"));
        DEC.put("energy.wirelessChargingState", m(0, "charging", 1, "full", 2, "fault", 3, "standby"));
        for (String k : new String[] { "ac.power", "ac.compressor", "ac.frontDefrost", "ac.rearDefrost", "seat.driver.beltFastened", "body.windows.anyOpen", "body.windows.ventilation",
            "body.windows.halfOpen", "light.side", "light.lowBeam", "light.highBeam", "light.turnSignal.left", "light.turnSignal.right", "light.frontFog", "light.rearFog",
            "warning.lowVoltageBatteryFault", "audio.muted", "ac.rear.power" }) DEC.put(k, B01);
        DEC.put("ac.circulation", m(0, "outside", 1, "inside"));
        for (String k : new String[] { "body.tailgate.state", "body.hood.state", "body.doors.frontLeft.state", "body.doors.frontRight.state", "body.doors.rearLeft.state", "body.doors.rearRight.state" }) DEC.put(k, OC);
        for (String k : new String[] { "light.daytimeRunning", "light.dynamicColorful", "light.hazard", "light.ambient.musicMode", "warning.keyBatteryLow" }) DEC.put(k, B12);
        DEC.put("steeringWheel.heating", m(1, false, 2, true)); // 方向盘加热：1 关、2 开
        DEC.put("light.brake", m(1, "on", 2, "flash", 3, "off"));
        DEC.put("light.ambient.area", m(1, "front", 2, "rear", 3, "all"));
        DEC.put("light.ambient.color", m(1, "iceBlue", 2, "coldWhite", 3, "warmWhite", 4, "redOrange", 5, "red", 6, "redPurple", 7, "darkBlue"));
        DEC.put("environment.pm25.indoorLevel", PM); DEC.put("environment.pm25.outdoorLevel", PM);
        DEC.put("tyre.displayUnit", m(1, "bar", 2, "psi", 3, "kPa"));
        DEC.put("display.dayNightMode", m(16, "day", 32, "night"));
        DEC.put("ac.controlMode", AUTO); DEC.put("ac.rear.controlMode", AUTO); DEC.put("ac.windMode", WIND); DEC.put("ac.rear.windMode", WIND);
    }
    private static final Set<String> SEAT_LV = new HashSet<>(Arrays.asList("seat.driver.heating", "seat.passenger.heating", "seat.driver.ventilation", "seat.passenger.ventilation"));
    private static final Set<String> TEMPS = new HashSet<>(Arrays.asList("ac.driverTemperature", "ac.indoorTemperature", "ac.outdoorTemperature", "ac.rear.temperature"));
    private static final Map<String, Map<Integer, Object>> UNIT_NAMES = new HashMap<>();
    static {
        UNIT_NAMES.put("energy.consumption.averageElectric", m(1, "kW/100km", 2, "kW/100mi", 3, "hp/100km", 4, "hp/100mi"));
        UNIT_NAMES.put("energy.consumption.instantFuel", m(1, "L/100km", 2, "km/L", 3, "mpg", 4, "L/h"));
    }
    private static final Map<Integer, Object> PRESSURE_UNIT = m(1, "bar", 2, "psi", 3, "kPa");
    private static final Set<String> PILL = new HashSet<>(Arrays.asList("energy.batteryPercent", "energy.fuelPercent", "energy.electricRange", "energy.fuelRange",
        "energy.chargingState", "energy.charging.remainingTime", "ac.outdoorTemperature",
        "tyre.frontLeft.state", "tyre.frontRight.state", "tyre.rearLeft.state", "tyre.rearRight.state",
        "tyre.frontLeft.pressure", "tyre.frontRight.pressure", "tyre.rearLeft.pressure", "tyre.rearRight.pressure",
        "warning.lowVoltageBatteryFault", "warning.keyBatteryLow"));
    private static final Set<String> TRIP = new HashSet<>(Arrays.asList("driving.sessionDuration", "driving.sessionDistance", "driving.evSessionDistance", "driving.hevSessionDistance"));
    static final String[] TYRE_KEYS = { "tyre.frontLeft.state", "tyre.frontRight.state", "tyre.rearLeft.state", "tyre.rearRight.state" };
    static final String[] TYRE_NAMES = { "左前", "右前", "左后", "右后" };
    private static final Map<String, String> TYRES = new HashMap<>(), TYRE_P = new HashMap<>();
    static { for (int i = 0; i < 4; i++) { TYRES.put(TYRE_KEYS[i], TYRE_NAMES[i]); TYRE_P.put(TYRE_KEYS[i].replace(".state", ".pressure"), TYRE_NAMES[i]); } }
    private static final double RANGE_BAD = 2046;
    private static final Map<String, String> TYRE_STATE = new HashMap<>();
    static { TYRE_STATE.put("high", "偏高"); TYRE_STATE.put("low", "偏低"); TYRE_STATE.put("warning", "异常"); TYRE_STATE.put("critical", "严重异常"); TYRE_STATE.put("burst", "告警，可能漏气"); }
    static final String[] DOORS = { "body.doors.frontLeft.state", "body.doors.frontRight.state", "body.doors.rearLeft.state", "body.doors.rearRight.state" };
    static final String[] WINDOWS = { "body.windows.frontLeft.position", "body.windows.frontRight.position", "body.windows.rearLeft.position", "body.windows.rearRight.position" };
    private static final Map<String, String> AMBIENT = new HashMap<>();
    static { String[][] a = { { "iceBlue", "冰蓝" }, { "coldWhite", "冷白" }, { "warmWhite", "暖白" }, { "redOrange", "红橙" }, { "red", "红色" }, { "redPurple", "红紫" }, { "darkBlue", "深蓝" } }; for (String[] x : a) AMBIENT.put(x[0], x[1]); }
    private static final Set<String> ON = new HashSet<>(Arrays.asList("on", "ready", "fakeReady"));
    private static final Set<String> BAD_AIR = new HashSet<>(Arrays.asList("heavy", "severe"));
    private static final long GAP = 8000;

    final App app;
    private final Host host;
    private final Handler h;
    private final HandlerThread io = new HandlerThread("cover");
    private final Handler ioH;

    final Map<String, Object> car = new HashMap<>();
    final Map<String, String> units = new HashMap<>();
    private JSONObject tree = new JSONObject();
    JSONObject media;
    Map<String, String> caps;
    private List<String> fields;
    final Set<String> actions = new HashSet<>();
    JSONObject info;
    String vin, androidVer;
    private boolean androidAsked;
    private long infoAsked;
    private int gen, retries, seq;
    private boolean stale, detailWanted, coreSeen;
    private String coreId, mediaId, detailId, session;
    private List<String> detailList;
    private final long bornAt;
    private final Map<String, Long> cool = new HashMap<>();
    private long lastLineAt, powerAt;
    private Pending pending;
    private final Map<String, String> said = new HashMap<>();
    private final long[] blinkAt = new long[2];
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    private boolean hidden;

    Car(App app, Host host) {
        this.app = app;
        this.host = host;
        h = app.h;
        io.start();
        ioH = new Handler(io.getLooper());
        bornAt = app.now();
        loadTrip();
    }

    // ================= 接话：冷却 + 最小间隔 + 一个排队位 =================
    static final class Opt {
        long cool = 300000;
        int pri = 1, ms = 3200;
        App.Done after2;
        PetAfter after;
        Opt cool(long c) { cool = c; return this; }
        Opt pri(int p) { pri = p; return this; }
        Opt ms(int v) { ms = v; return this; }
        Opt after(PetAfter a) { after = a; return this; }
    }
    interface PetAfter { void run(Pet p); }
    private static final class Pending { String key; Map<String, Object> vars; Opt opt; }
    static Opt opt() { return new Opt(); }

    void talk(String key, Map<String, Object> vars, Opt opt) {
        if (!"on".equals(app.settings.bubble) || app.livePets().isEmpty()) return;
        if (opt == null) opt = new Opt();
        long t = app.now();
        Long c = cool.get(key);
        if (c != null && t - c < opt.cool) return;
        boolean ai = app.aiBusy();
        if (ai && opt.pri > 1 && app.ai != null) { app.ai.stop(); ai = false; } // 重要的事打断大模型的回复
        if (t - lastLineAt < GAP || app.busy() || ai) {
            if (opt.pri > 1 && (pending == null || pending.opt.pri <= opt.pri)) { Pending p = new Pending(); p.key = key; p.vars = vars; p.opt = opt; pending = p; waitPending(); }
            return;
        }
        final Opt o = opt;
        App.Lp local = () -> {
            Pet p = app.randomPet();
            String text = p == null ? "" : app.line(p, key, vars);
            if (text.isEmpty()) return false;
            app.say(p, text, o.ms);
            app.react(p, text);
            if (o.after != null) o.after.run(p);
            return true;
        };
        // 开了"车况变化时用 AI 说"：要紧的事交给大模型说（模型失败时它再调 local）；交不出去照旧说本地台词
        if (!(app.ai != null && app.ai.carLine(key, vars, o, local)) && !local.run()) return;
        cool.put(key, t);
        lastLineAt = t;
    }
    private final Runnable pendR = () -> { Pending q = pending; pending = null; if (q != null) talk(q.key, q.vars, q.opt); };
    private void waitPending() { h.removeCallbacks(pendR); h.postDelayed(pendR, Math.max(300, GAP - (app.now() - lastLineAt)) + 200); }
    private Runnable duetR;
    /** 两只一唱一和：一只先说 a 组，另一只隔 3 秒多接 b 组 */
    private void duet(String a, final String b, final Map<String, Object> vars) {
        talk(a, vars, opt().pri(2).cool(0).ms(4200).after(p -> {
            Pet q = app.other(p);
            if (q == null) return;
            h.removeCallbacks(duetR);
            duetR = () -> {
                if (hidden) return;
                String s = app.line(q, b, vars);
                if (s.isEmpty()) return;
                lastLineAt = app.now();
                app.say(q, s, 3600);
                app.react(q, s);
            };
            h.postDelayed(duetR, 3300);
        }));
    }
    long lastLineAt() { return lastLineAt; }
    void spoke() { lastLineAt = app.now(); }

    // ================= 业务树 → 统一的值 =================
    private static Integer intOf(Object v) { return v instanceof Number && ((Number) v).doubleValue() % 1 == 0 ? ((Number) v).intValue() : null; }
    /** 从合并后的业务树里取字段 k 的值（连同同节点的单位编码、胎压状态来源），解码；没有有效数据返回 null */
    private Object fromTree(String k) {
        String[] parts = k.split("\\.");
        JSONObject node = tree;
        for (int i = 0; i < parts.length - 1 && node != null; i++) node = node.optJSONObject(parts[i]);
        if (node == null) return null;
        String leaf = parts[parts.length - 1];
        Object v = node.opt(leaf);
        if (v == null || v == JSONObject.NULL) return null;
        Object u = node.opt(leaf + "Unit");
        Integer ui = intOf(u);
        Map<Integer, Object> d = DEC.get(k);
        if (d != null) { Integer iv = intOf(v); return iv == null ? null : d.get(iv); }
        if (k.equals("energy.charging.remainingTime")) { JSONArray a = v instanceof JSONArray ? (JSONArray) v : null; return a != null && a.length() == 2 ? (double) (a.optDouble(0) * 3600 + a.optDouble(1) * 60) : null; }
        if (!(v instanceof Number)) return v instanceof String ? v : null;
        double x = ((Number) v).doubleValue();
        if ((k.equals("energy.electricRange") || k.equals("energy.fuelRange")) && x >= RANGE_BAD) return null;
        if (SEAT_LV.contains(k)) return x >= 1 && x <= 3 ? x - 1 : null;
        if (TEMPS.contains(k)) return ui != null && ui == 0 ? (x - 32) * 5 / 9 : x;
        if (UNIT_NAMES.containsKey(k)) { Object n = ui == null ? null : UNIT_NAMES.get(k).get(ui); units.put(k, n == null ? "" : (String) n); return x; }
        if (k.equals("driving.journeyDistance")) return ui != null && ui == 2 ? x * 1.609344 : x;
        if (k.equals("driving.totalDistance")) return "m".equals(u) ? x / 1000 : "0.1km".equals(u) ? x / 10 : x;
        if (TYRE_P.containsKey(k)) {
            if (x == 4092) return "burst";
            if (x == 4093) return "fault";
            Object n = ui == null ? null : PRESSURE_UNIT.get(ui);
            units.put(k, n == null ? "kPa" : (String) n);
            return ui != null && ui == 1 ? x * 10 : ui != null && ui == 2 ? x * 0.6894757 : x;
        }
        if (TYRES.containsKey(k)) {
            String src = node.optString("stateSource");
            int i = (int) x;
            if ("instrument".equals(src)) return i == 1 ? "normal" : i == 2 ? "warning" : i == 3 ? "critical" : null;
            if ("tyre".equals(src)) return i == 0 ? "normal" : i == 1 ? "high" : i == 2 ? "low" : null;
            return x == 0 ? "normal" : null;
        }
        return x;
    }
    private static void merge(JSONObject t, JSONObject p) {
        for (Iterator<String> it = p.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = p.opt(k);
            try {
                if (v instanceof JSONObject) { JSONObject sub = t.optJSONObject(k); if (sub == null) { sub = new JSONObject(); t.put(k, sub); } merge(sub, (JSONObject) v); }
                else t.put(k, v);
            } catch (Exception ignored) { }
        }
    }
    /** 这次回调碰到了哪些字段：叶子本身、它的单位编码或胎压状态来源变了都算 */
    private static void touched(JSONObject p, String prefix, Set<String> out, Set<String> set) {
        for (Iterator<String> it = p.keys(); it.hasNext(); ) {
            String key = it.next();
            Object v = p.opt(key);
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (v instanceof JSONObject) touched((JSONObject) v, path, out, set);
            else if (set.contains(path)) out.add(path);
            else if (key.equals("stateSource") && set.contains(prefix + ".state")) out.add(prefix + ".state");
            else if (key.length() > 4 && key.endsWith("Unit") && set.contains(prefix + "." + key.substring(0, key.length() - 4))) out.add(prefix + "." + key.substring(0, key.length() - 4));
        }
    }

    // ================= 车况变化 =================
    static double num(Object v) { return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN; }
    double n(String k) { return num(car.get(k)); }
    boolean is(String k, Object v) { Object x = car.get(k); return x != null && x.equals(v); }
    String openThings() {
        ArrayList<String> w = new ArrayList<>();
        if (anyDoorOpen()) w.add("车门");
        if (windowsOpen()) w.add("车窗");
        if (n("body.sunroof.position") > 0) w.add("天窗");
        if (is("body.tailgate.state", "open")) w.add("尾门");
        if (is("body.hood.state", "open")) w.add("前机舱盖");
        return android.text.TextUtils.join("、", w);
    }
    boolean windowsOpen() { if (is("body.windows.anyOpen", true)) return true; for (String k : WINDOWS) if (n(k) > 0) return true; return false; }
    boolean moving() { return n("driving.speed") > 5; }
    boolean charging() { return is("energy.chargingState", "charging"); }
    private static boolean crossDown(Object a, Object b, double n) { return a instanceof Number && num(a) >= n && num(b) < n; }
    private static boolean crossUp(Object a, Object b, double n) { return a instanceof Number && num(a) < n && num(b) >= n; }
    private static boolean turnedOn(Object a, Object b) { return Boolean.TRUE.equals(b) && Boolean.FALSE.equals(a); }
    private static boolean turnedOff(Object a, Object b) { return Boolean.FALSE.equals(b) && Boolean.TRUE.equals(a); }
    /** 关掉 / 合上一类的事：晚 1.5 秒再看，还在上电、离电源变化超过 5 秒才说（熄火时一堆东西跟着关，不该一样一样念） */
    private void talkOff(String key, Map<String, Object> vars, Opt opt) {
        final int g = gen;
        h.postDelayed(() -> { if (g == gen && ON.contains(String.valueOf(car.get("power.level"))) && app.now() - powerAt > 5000) talk(key, vars, opt); }, 1500);
    }
    boolean anyDoorOpen() { for (String d : DOORS) if (is(d, "open")) return true; return false; }
    private String openWhileMoving() {
        ArrayList<String> w = new ArrayList<>();
        if (anyDoorOpen()) w.add("车门");
        if (is("body.hood.state", "open")) w.add("前机舱盖");
        if (is("body.tailgate.state", "open")) w.add("尾门");
        return android.text.TextUtils.join("、", w);
    }
    boolean isNight() {
        Object m = car.get("display.dayNightMode");
        if (m != null) return "night".equals(m);
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hr >= 19 || hr < 6;
    }
    private void lightsCheck() { if (isNight() && n("driving.speed") > 20 && is("light.lowBeam", false)) talk("lightsNight", null, opt().pri(2).cool(1800000)); }
    static Double totalRange(Object er, Object fr) { return er == null && fr == null ? null : (er instanceof Number ? num(er) : 0) + (fr instanceof Number ? num(fr) : 0); }
    String tyreState(String k) {
        Object s = car.get(k), p = car.get(k.replace(".state", ".pressure"));
        if ("burst".equals(p)) return TYRE_STATE.get("burst");
        if ("fault".equals(p)) return "异常";
        if (s instanceof String && !"normal".equals(s)) { String t = TYRE_STATE.get(s); return t != null ? t : "异常"; }
        return "";
    }
    private static Map<String, Object> V(Object... kv) { return App.vars(kv); }

    private void changed(String k, Object a, Object b) {
        if (b == null) return;
        if (TYRES.containsKey(k) || TYRE_P.containsKey(k)) {
            String sk = k.replace(".pressure", ".state"), name = TYRES.get(sk), st = tyreState(sk);
            if (TYRE_P.containsKey(k) && a instanceof Number && b instanceof Number) return; // 胎压数值正常波动
            String was = said.get(sk);
            if (!st.isEmpty() && !st.equals(was)) { said.put(sk, st); talk("tyreWarn", V("tyre", name, "state", st), opt().pri(2).cool(0).ms(4500)); }
            else if (st.isEmpty() && was != null && !was.isEmpty()) { said.put(sk, ""); talk("tyreOk", V("tyre", name), opt().cool(0)); }
            return;
        }
        if (Arrays.asList(DOORS).contains(k)) {
            if ("open".equals(b) && "closed".equals(a)) { if (moving()) talk("doorOpen", null, opt().pri(2).cool(60000)); else talk("doorOpenPark", null, opt().cool(300000)); }
            else if ("closed".equals(b) && "open".equals(a) && !anyDoorOpen()) talkOff("doorsClosed", null, opt().cool(300000));
            return;
        }
        switch (k) {
            case "power.level":
                powerAt = app.now();
                if (ON.contains(String.valueOf(b)) && ("off".equals(a) || "acc".equals(a))) { talk("carOn", null, opt().pri(2).cool(600000)); tripLater(6000); }
                else if ("off".equals(b) && ON.contains(String.valueOf(a))) {
                    tripTrack();
                    tripEnd();
                    String w = openThings();
                    if (!w.isEmpty()) talk("leftOpen", V("what", w), opt().pri(2).cool(0).ms(4500)); else talk("carOff", null, opt().pri(2).cool(600000));
                }
                break;
            case "driving.gear":
                if ("D".equals(b) && ("P".equals(a) || "N".equals(a))) talk("gearDrive", null, opt().cool(600000));
                else if ("R".equals(b) && a != null && !"R".equals(a)) talk("gearReverse", null, opt().cool(180000));
                else if ("P".equals(b) && ("D".equals(a) || "R".equals(a))) talk("gearPark", null, opt().cool(300000));
                else if ("N".equals(b) && ("D".equals(a) || "R".equals(a))) talk("gearNeutral", null, opt().cool(600000));
                break;
            case "driving.speed":
                if (crossUp(a, b, 10)) { String ow = openWhileMoving(); if (!ow.isEmpty()) talk("openMoving", V("what", ow), opt().pri(2).cool(60000)); }
                if (crossUp(a, b, 20)) lightsCheck();
                if (crossUp(a, b, 120)) talk("fast", null, opt().cool(1200000));
                if (num(b) > 10 && is("seat.driver.beltFastened", false)) talk("belt", null, opt().pri(2).cool(180000));
                break;
            case "driving.totalDistance":
                if (a instanceof Number && num(b) > num(a) && num(b) - num(a) < 50 && Math.floor(num(b) / 1000) > Math.floor(num(a) / 1000)) talk("milestone", V("n", (long) Math.floor(num(b) / 1000) * 1000), opt().cool(0).ms(4000));
                break;
            case "seat.driver.beltFastened":
                if (Boolean.FALSE.equals(b) && n("driving.speed") > 10) talk("belt", null, opt().pri(2).cool(180000));
                else if (turnedOn(a, b)) talk("beltOn", null, opt().cool(600000));
                break;
            case "energy.chargingState":
                if ("charging".equals(b) && a != null && !"charging".equals(a)) talk("chargeStart", V("n", Math.round(n0("energy.batteryPercent"))), opt().pri(2).cool(300000));
                else if ("finished".equals(b) && "charging".equals(a)) talk("chargeDone", null, opt().pri(2).cool(600000));
                else if ("terminated".equals(b) && "charging".equals(a)) talk("chargeStop", null, opt().pri(2).cool(300000));
                break;
            case "energy.connectorState":
                if ("none".equals(a) && ("ac".equals(b) || "dc".equals(b) || "acDc".equals(b)) && !charging()) talk("plugIn", V("type", "dc".equals(b) ? "直流快充" : "ac".equals(b) ? "交流慢充" : "充电枪"), opt().cool(300000));
                else if ("vtol".equals(b) && a != null && !"vtol".equals(a)) talk("vtol", null, opt().cool(1800000));
                else if ("vtol".equals(a) && !"vtol".equals(b)) talk("vtolEnd", null, opt().cool(1800000));
                else if ("none".equals(b) && ("ac".equals(a) || "dc".equals(a) || "acDc".equals(a))) talk("unplug", null, opt().cool(300000));
                break;
            case "energy.wirelessCharging":
                if (turnedOn(a, b)) talk("phonePadOn", null, opt().cool(1800000)); else if (turnedOff(a, b)) talkOff("phonePadOff", null, opt().cool(1800000));
                break;
            case "energy.wirelessChargingState":
                if ("charging".equals(b) && a != null && !"charging".equals(a)) talk("phoneCharge", null, opt().cool(600000));
                else if ("full".equals(b) && "charging".equals(a)) talk("phoneFull", null, opt().cool(600000));
                else if ("fault".equals(b) && a != null && !"fault".equals(a)) talk("phoneFault", null, opt().cool(1800000));
                break;
            case "energy.batteryPercent":
                if (!charging() && (crossDown(a, b, 20) || crossDown(a, b, 10))) talk("batteryLow", V("n", Math.round(num(b))), opt().pri(2).cool(0));
                break;
            case "energy.electricRange": case "energy.fuelRange":
                if (a instanceof Number && !charging()) {
                    Object er = car.get("energy.electricRange"), fr = car.get("energy.fuelRange");
                    Double now = totalRange(er, fr), before = totalRange(k.equals("energy.electricRange") ? a : er, k.equals("energy.fuelRange") ? a : fr);
                    if (now != null && crossDown(before, now, 50)) talk("rangeLow", V("km", Math.round(now)), opt().pri(2).cool(1800000));
                }
                break;
            case "energy.fuelPercent":
                if (crossDown(a, b, 15) || crossDown(a, b, 8)) talk("fuelLow", V("n", Math.round(num(b))), opt().pri(2).cool(0));
                break;
            case "energy.operationMode":
                if (a != null && !b.equals(a)) talk("sport".equals(b) ? "modeSport" : "eco".equals(b) ? "modeEco" : "modeComfort", null, opt().cool(600000));
                break;
            case "energy.mode": if (a != null && !b.equals(a)) talk("ev".equals(b) ? "modeEv" : "modeHev", null, opt().cool(600000)); break;
            case "energy.regenerationLevel": if (a != null && !b.equals(a)) talk("high".equals(b) ? "regenHigh" : "regenStd", null, opt().cool(600000)); break;
            case "energy.simulator": if (turnedOn(a, b)) talk("soundOn", null, opt().cool(600000)); else if (turnedOff(a, b)) talkOff("soundOff", null, opt().cool(600000)); break;
            case "energy.socSave": if (turnedOn(a, b)) talk("socSave", null, opt().cool(600000)); else if (turnedOff(a, b)) talkOff("socSaveOff", null, opt().cool(600000)); break;
            case "energy.socTarget": // 拖滑块会连着变好几次：停下 2.5 秒后说最后那个数
                if (a == null) break;
                h.removeCallbacks(socR);
                h.postDelayed(socR, 2500);
                break;
            case "ac.outdoorTemperature":
                if (crossUp(a, b, 33)) talk("hot", V("t", Math.round(num(b))), opt().cool(1800000));
                else if (crossDown(a, b, 5.5)) talk("cold", V("t", Math.round(num(b))), opt().cool(1800000));
                break;
            case "ac.indoorTemperature": if (crossUp(a, b, 35)) talk("stuffy", V("t", Math.round(num(b))), opt().cool(1800000)); break;
            case "ac.driverTemperature":
                if (is("ac.power", false)) break;
                if (crossDown(a, b, 18.5)) talk("acCold", V("t", Math.round(num(b))), opt().cool(900000));
                else if (crossUp(a, b, 28)) talk("acWarm", V("t", Math.round(num(b))), opt().cool(900000));
                break;
            case "ac.power": if (turnedOn(a, b)) talk("acOn", null, opt().cool(900000)); else if (turnedOff(a, b)) talkOff("acOff", null, opt().cool(900000)); break;
            case "ac.rear.power": if (turnedOn(a, b)) talk("acRearOn", null, opt().cool(900000)); else if (turnedOff(a, b)) talkOff("acRearOff", null, opt().cool(900000)); break;
            case "ac.controlMode": if ("manual".equals(a) && "auto".equals(b) && is("ac.power", true)) talk("acAuto", null, opt().cool(1800000)); break;
            case "ac.frontDefrost": case "ac.rearDefrost":
                if (turnedOn(a, b)) talk("defrost", null, opt().cool(600000)); else if (turnedOff(a, b)) talkOff("defrostOff", null, opt().cool(600000));
                break;
            case "ac.circulation": if (a != null && !b.equals(a)) talk("inside".equals(b) ? "circInside" : "circOutside", null, opt().cool(1800000)); break;
            case "seat.driver.heating": case "seat.passenger.heating":
                if (num(b) > 0 && num(a) == 0) talk("seatHeat", null, opt().cool(900000)); else if (num(b) == 0 && num(a) > 0) talkOff("seatHeatOff", null, opt().cool(900000));
                break;
            case "steeringWheel.heating": if (turnedOn(a, b)) talk("wheelHeat", null, opt().cool(900000)); else if (turnedOff(a, b)) talkOff("wheelHeatOff", null, opt().cool(900000)); break;
            case "seat.driver.ventilation": case "seat.passenger.ventilation":
                if (num(b) > 0 && num(a) == 0) talk("seatVent", null, opt().cool(900000)); else if (num(b) == 0 && num(a) > 0) talkOff("seatVentOff", null, opt().cool(900000));
                break;
            case "body.hood.state":
                if ("open".equals(b) && "closed".equals(a) && moving()) talk("openMoving", V("what", "前机舱盖"), opt().pri(2).cool(60000));
                else if ("open".equals(b) && "closed".equals(a)) talk("hoodOpen", null, opt().cool(300000));
                else if ("closed".equals(b) && "open".equals(a)) talk("hoodClose", null, opt().cool(300000));
                break;
            case "body.tailgate.state":
                if ("open".equals(b) && "closed".equals(a) && moving()) talk("openMoving", V("what", "尾门"), opt().pri(2).cool(60000));
                else if ("open".equals(b) && "closed".equals(a)) talk("trunkOpen", null, opt().cool(300000));
                else if ("closed".equals(b) && "open".equals(a)) talk("trunkClose", null, opt().cool(300000));
                break;
            case "body.windows.anyOpen": if (turnedOn(a, b)) talk("windowOpen", null, opt().cool(600000)); else if (turnedOff(a, b)) talkOff("windowClose", null, opt().cool(600000)); break;
            case "body.windows.ventilation": if (turnedOn(a, b)) talk("windowVent", null, opt().cool(1800000)); break;
            case "body.sunroof.position":
                if (num(a) == 0 && num(b) > 0) talk("sunroofOpen", null, opt().cool(600000)); else if (num(a) > 0 && num(b) == 0) talkOff("sunroofClose", null, opt().cool(600000));
                break;
            case "body.sunshade.position":
                if (num(a) == 0 && num(b) > 0) talk("shadeOpen", null, opt().cool(600000)); else if (num(a) > 0 && num(b) == 0) talkOff("shadeClose", null, opt().cool(600000));
                break;
            case "light.brake": if ("flash".equals(b) && !"flash".equals(a) && a != null) talk("hardBrake", null, opt().pri(2).cool(120000)); break;
            case "light.hazard": if (turnedOn(a, b)) talk("hazard", null, opt().cool(300000)); else if (turnedOff(a, b)) talkOff("hazardOff", null, opt().cool(300000)); break;
            case "light.lowBeam":
                if (turnedOn(a, b)) talk("lightsOn", null, opt().cool(1800000));
                else if (turnedOff(a, b)) { if (isNight() && n("driving.speed") > 20) lightsCheck(); else talkOff("lightsOff", null, opt().cool(1800000)); }
                break;
            case "light.highBeam": if (turnedOn(a, b)) talk("highBeam", null, opt().cool(600000)); break;
            case "light.frontFog": case "light.rearFog":
                if (turnedOn(a, b)) talk("fogLight", null, opt().cool(1800000)); else if (turnedOff(a, b)) talkOff("fogOff", null, opt().cool(1800000));
                break;
            case "light.turnSignal.left": case "light.turnSignal.right":
                if (Boolean.TRUE.equals(b)) {
                    int side = k.endsWith("left") ? 0 : 1;
                    long t = app.now();
                    if (t - blinkAt[side] > 4000 && !is("light.hazard", true) && app.rnd.nextFloat() < 0.3f) talk(side == 0 ? "turnLeft" : "turnRight", null, opt().cool(600000));
                    blinkAt[side] = t;
                }
                break;
            case "light.ambient.color": if (a != null && !b.equals(a)) { String c = AMBIENT.get(b); talk("ambient", V("c", c != null ? c : "新颜色"), opt().cool(600000)); } break;
            case "light.ambient.musicMode": if (turnedOn(a, b)) talk("ambientMusic", null, opt().cool(1800000)); else if (turnedOff(a, b)) talkOff("ambientMusicOff", null, opt().cool(1800000)); break;
            case "light.dynamicColorful": if (turnedOn(a, b)) talk("colorful", null, opt().cool(1800000)); else if (turnedOff(a, b)) talkOff("colorfulOff", null, opt().cool(1800000)); break;
            case "display.dayNightMode":
                if (a != null && !b.equals(a)) talk("night".equals(b) ? "nightMode" : "dayMode", null, opt().cool(3600000));
                if ("night".equals(b)) lightsCheck();
                break;
            case "environment.pm25.outdoorLevel":
                if (BAD_AIR.contains(b) && a != null && !BAD_AIR.contains(a)) talk("pmBad", null, opt().cool(1800000));
                else if (BAD_AIR.contains(a) && !BAD_AIR.contains(b)) talk("pmOk", null, opt().cool(1800000));
                break;
            case "environment.pm25.indoorLevel":
                if (BAD_AIR.contains(b) && a != null && !BAD_AIR.contains(a)) talk("pmIndoor", null, opt().cool(1800000));
                else if (BAD_AIR.contains(a) && !BAD_AIR.contains(b)) talk("pmIndoorOk", null, opt().cool(1800000));
                break;
            case "environment.slope": if (moving() && Math.abs(num(b)) >= 15 && a instanceof Number && Math.abs(num(a)) < 15) talk("slope", null, opt().cool(600000)); break;
            case "warning.lowVoltageBatteryFault": if (turnedOn(a, b)) talk("lowVoltage", null, opt().pri(2).cool(0)); else if (turnedOff(a, b)) talk("lowVoltageOk", null, opt().cool(600000)); break;
            case "warning.keyBatteryLow": if (turnedOn(a, b)) talk("keyBattery", null, opt().pri(2).cool(0)); else if (turnedOff(a, b)) talk("keyBatteryOk", null, opt().cool(600000)); break;
            case "audio.volume": if (crossUp(a, b, 70) && playing()) talk("loud", null, opt().cool(1200000)); break;
            case "audio.muted":
                if (Boolean.TRUE.equals(b)) stopHum(); else hum();
                if (a != null && media != null && media.optBoolean("playing")) talk(Boolean.TRUE.equals(b) ? "muted" : "unmuted", null, opt().cool(600000));
                break;
        }
    }
    private double n0(String k) { double v = n(k); return Double.isNaN(v) ? 0 : v; }
    private final Runnable socR = () -> { double n = n("energy.socTarget"); if (!Double.isNaN(n)) talk("socTarget", V("n", Math.round(n)), opt().cool(300000)); };
    private final Runnable baseR = this::baselineNow;
    private void baseline() { h.removeCallbacks(baseR); h.postDelayed(baseR, 9000); }
    /** 第一个快照之后：持续存在的问题延时说一次（打招呼之后），室外太热太冷、车里太闷也说一次；同一个问题只说一次 */
    private void baselineNow() {
        for (String k : TYRE_KEYS) {
            String s = tyreState(k);
            if (!s.isEmpty() && !s.equals(said.get(k))) { said.put(k, s); talk("tyreWarn", V("tyre", TYRES.get(k), "state", s), opt().pri(2).cool(0).ms(4500)); }
        }
        if (is("warning.lowVoltageBatteryFault", true) && !said.containsKey("lv")) { said.put("lv", "1"); talk("lowVoltage", null, opt().pri(2).cool(0)); }
        if (is("warning.keyBatteryLow", true) && !said.containsKey("key")) { said.put("key", "1"); talk("keyBattery", null, opt().pri(2).cool(0)); }
        Double tr = totalRange(car.get("energy.electricRange"), car.get("energy.fuelRange"));
        if (tr != null && tr < 50 && !charging() && !said.containsKey("range")) { said.put("range", "1"); talk("rangeLow", V("km", Math.round(tr)), opt().pri(2).cool(1800000)); }
        double t = n("ac.outdoorTemperature"), ti = n("ac.indoorTemperature");
        if (!Double.isNaN(ti) && ti >= 35 && !said.containsKey("stuffy")) { said.put("stuffy", "1"); talk("stuffy", V("t", Math.round(ti)), opt().cool(1800000)); }
        else if (!Double.isNaN(t) && !said.containsKey("temp")) {
            said.put("temp", "1");
            if (t >= 33) talk("hot", V("t", Math.round(t)), opt().cool(1800000));
            else if (t <= 5) talk("cold", V("t", Math.round(t)), opt().cool(1800000));
        }
    }

    // ================= 行程：本次时长、里程按增量累计存本地；下次开机说上一趟 =================
    private static final double MAX_KMS = 250.0 / 3600;
    private JSONObject tripCur, tripLast;
    private long tripSavedAt;
    private static boolean tripSane(JSONObject t) {
        if (t == null) return false;
        double ds = t.optDouble("ds", -1), dk = t.optDouble("dk", -1);
        return ds >= 0 && dk >= 0 && !(ds > 0 && dk > ds * MAX_KMS + 1);
    }
    private void loadTrip() {
        try {
            String s = Store.get("pet-trip");
            JSONObject o = s == null ? null : new JSONObject(s);
            if (o != null) {
                JSONObject c = o.optJSONObject("cur"), l = o.optJSONObject("last");
                if (tripSane(c) && c.optDouble("at", 0) > 0) tripCur = c;
                if (tripSane(l)) tripLast = l;
            }
        } catch (Exception ignored) { }
    }
    private void tripSave(boolean force) {
        if (!force && app.now() - tripSavedAt < 20000) return;
        tripSavedAt = app.now();
        try { Store.set("pet-trip", new JSONObject().put("cur", tripCur == null ? JSONObject.NULL : tripCur).put("last", tripLast == null ? JSONObject.NULL : tripLast).toString()); } catch (Exception ignored) { }
    }
    private static Double numOrNull(Object v) { return v instanceof Number && !Double.isNaN(num(v)) && !Double.isInfinite(num(v)) ? num(v) : null; }
    /** 累加计数器 r 的增量到 d；计数器变小 = 清零过。增量超过"离上次变化的秒数 × rate + slack"的是错值 */
    private static void acc(JSONObject t, String d, String r, Double v, long now, double rate, double slack) {
        if (v == null) return;
        try {
            Double prev = t.has(r) && !t.isNull(r) ? t.optDouble(r) : null;
            if (prev != null && !v.equals(prev)) {
                double inc = v > prev ? v - prev : v, at = t.has(r + "At") ? t.optDouble(r + "At") : t.optDouble("at", now);
                if (inc <= Math.max(0, now - at) / 1000 * rate + slack) t.put(d, t.optDouble(d, 0) + inc);
            }
            if (prev == null || !v.equals(prev)) t.put(r + "At", now);
            t.put(r, v);
        } catch (Exception ignored) { }
    }
    private boolean tripDirty;
    private void tripTrack() {
        tripDirty = false;
        Double s = numOrNull(car.get("driving.sessionDuration")), km = numOrNull(car.get("driving.sessionDistance"));
        if (s == null && km == null) return;
        Double ev = numOrNull(car.get("driving.evSessionDistance")), hev = numOrNull(car.get("driving.hevSessionDistance"));
        long now = app.now();
        try {
            if (tripCur == null) {
                tripCur = new JSONObject().put("ds", 0).put("dk", 0).put("de", 0).put("dh", 0);
                if (s != null) tripCur.put("s", s);
                if (km != null) tripCur.put("km", km);
                if (ev != null) tripCur.put("ev", ev);
                if (hev != null) tripCur.put("hev", hev);
            } else {
                acc(tripCur, "ds", "s", s, now, 1, 90);
                acc(tripCur, "dk", "km", km, now, MAX_KMS, 0.3);
                acc(tripCur, "de", "ev", ev, now, MAX_KMS, 0.3);
                acc(tripCur, "dh", "hev", hev, now, MAX_KMS, 0.3);
            }
            tripCur.put("at", now);
        } catch (Exception ignored) { }
        tripSave(false);
    }
    private static boolean worth(JSONObject t) { return t != null && (t.optDouble("dk", 0) >= 1 || t.optDouble("ds", 0) >= 600); }
    private void tripEnd() {
        JSONObject c = tripCur;
        tripCur = null;
        if (worth(c)) {
            try { tripLast = new JSONObject().put("ds", c.optDouble("ds")).put("dk", c.optDouble("dk")).put("de", c.optDouble("de")).put("dh", c.optDouble("dh")).put("at", c.optDouble("at")).put("told", false); }
            catch (Exception ignored) { }
        }
        tripSave(true);
    }
    /** 刚连上：上次记录是很久以前的、这段时间计数也没走，就算上一趟结束了 */
    private void tripResume() {
        if (tripCur == null) return;
        long gap = app.now() - (long) tripCur.optDouble("at", 0);
        Double s = numOrNull(car.get("driving.sessionDuration"));
        boolean drove = s != null && tripCur.has("s") && s >= tripCur.optDouble("s") && s - tripCur.optDouble("s") >= gap / 2000.0;
        if (gap > 600000 && !drove) tripEnd();
    }
    private final Runnable tripR = this::tripTell;
    private void tripLater(long ms) { h.removeCallbacks(tripR); h.postDelayed(tripR, ms); }
    private void tripTell() {
        JSONObject l = tripLast;
        Object pw = car.get("power.level");
        if (l == null || l.optBoolean("told") || !worth(l) || (!ON.contains(String.valueOf(pw)) && pw != null)) return;
        try { l.put("told", true); } catch (Exception ignored) { }
        tripSave(true);
        String[] tv = tripVars(l);
        duet(tv[0] + "A", tv[1], tripV(l));
    }
    /** 上一趟的台词组：长途（≥100 km）/ 短途（<5 km）/ 普通；插混几乎全程用电时第二句夸省油 */
    private String[] tripVars(JSONObject l) {
        double km = l.optDouble("dk"), de = l.optDouble("de"), dh = l.optDouble("dh");
        String kind = km >= 100 ? "tripLong" : km < 5 ? "tripShort" : "trip";
        double evShare = de + dh > 0 && car.get("energy.fuelPercent") != null ? de / (de + dh) : -1;
        return new String[] { kind, evShare >= 0.9 && km >= 5 ? "tripEvB" : kind + "B" };
    }
    private Map<String, Object> tripV(JSONObject l) {
        double km = l.optDouble("dk");
        return V("t", dur(Math.max(60, l.optDouble("ds"))), "km", km >= 100 ? String.valueOf(Math.round(km)) : String.valueOf(Math.round(km * 10) / 10.0));
    }
    static String dur(double s) { long m = Math.round(s / 60); return m >= 60 ? m / 60 + " 小时" + (m % 60 != 0 ? " " + m % 60 + " 分" : "") : Math.max(1, m) + " 分钟"; }

    // ================= 开场报车况 =================
    private String energyText() {
        double b = n("energy.batteryPercent"), f = n("energy.fuelPercent"), er = n("energy.electricRange"), fr = n("energy.fuelRange");
        if (!Double.isNaN(b) && charging()) { double rt = n("energy.charging.remainingTime"); return "正在充电，电量 " + Math.round(b) + "%" + (rt > 0 ? "，还要 " + dur(rt) : ""); }
        double r = (!Double.isNaN(b) && !Double.isNaN(er) ? er : 0) + (!Double.isNaN(f) && !Double.isNaN(fr) ? fr : 0);
        String s = !Double.isNaN(b) && !Double.isNaN(f) ? "电量 " + Math.round(b) + "%、油量 " + Math.round(f) + "%" : !Double.isNaN(b) ? "电量 " + Math.round(b) + "%" : "油量 " + Math.round(f) + "%";
        return s + (r > 0 ? "，能跑 " + Math.round(r) + " 公里" : "");
    }
    private String issuesText() {
        ArrayList<String> w = new ArrayList<>();
        for (int i = 0; i < 4; i++) { String st = tyreState(TYRE_KEYS[i]); if (!st.isEmpty()) w.add(TYRE_NAMES[i] + "胎压" + st); }
        if (is("warning.lowVoltageBatteryFault", true)) w.add("低压供电有异常");
        if (is("warning.keyBatteryLow", true)) w.add("钥匙电池快没电了");
        if (n("energy.batteryPercent") < 20 && !charging()) w.add("电量有点低");
        if (n("energy.fuelPercent") < 15) w.add("油不多了");
        if (windowsOpen()) w.add("车窗开着");
        if (n("body.sunroof.position") > 0) w.add("天窗开着");
        if (is("body.tailgate.state", "open")) w.add("尾门开着");
        if (is("body.hood.state", "open")) w.add("前机舱盖开着");
        return android.text.TextUtils.join("、", w.subList(0, Math.min(3, w.size())));
    }
    private Runnable seqR;
    private App.Done seqDone;
    /** 两只轮流说一串（间隔 3.6 秒，不受车况台词最小间隔限制）；某句没台词就跳过 */
    private void sequence(final List<Object[]> steps, final App.Done done) {
        Pet n = app.randomPet("Neuron"), e = app.randomPet("Eviling");
        final Pet[] pets = app.rnd.nextBoolean() ? new Pet[] { n != null ? n : e, e != null ? e : n } : new Pet[] { e != null ? e : n, n != null ? n : e };
        final int[] i = { 0 };
        h.removeCallbacks(seqR);
        seqR = new Runnable() {
            @Override public void run() {
                if (i[0] >= steps.size() || hidden) { seqDone = null; if (done != null) done.done(true); return; }
                Pet p = pets[i[0] % 2];
                Object[] st = steps.get(i[0]++);
                @SuppressWarnings("unchecked") String text = p == null ? "" : app.line(p, (String) st[0], (Map<String, Object>) st[1]);
                if (text.isEmpty()) { run(); return; }
                app.say(p, text, 3800);
                app.react(p, text);
                lastLineAt = app.now();
                h.postDelayed(this, 3600);
            }
        };
        seqR.run();
    }
    boolean ready() { return car.get("energy.batteryPercent") != null || car.get("energy.fuelPercent") != null; }
    /** 开场：一只说电量 / 油量 / 续航，另一只说有没有问题；有上一趟行程接着说。车况 6 秒没来 done(false) */
    void report(final App.Done done) {
        final long t0 = app.now();
        h.removeCallbacks(seqR);
        seqDone = done;
        seqR = new Runnable() {
            @Override public void run() {
                if (ready()) {
                    String w = issuesText();
                    double t = n("ac.outdoorTemperature");
                    ArrayList<Object[]> steps = new ArrayList<>();
                    steps.add(new Object[] { "reportA", V("s", energyText()) });
                    steps.add(!w.isEmpty() ? new Object[] { "reportWarn", V("w", w) } : !Double.isNaN(t) ? new Object[] { "reportOkT", V("t", Math.round(t)) } : new Object[] { "reportOk", null });
                    JSONObject l = tripLast;
                    Object pw = car.get("power.level");
                    if (l != null && !l.optBoolean("told") && worth(l) && (ON.contains(String.valueOf(pw)) || pw == null)) {
                        try { l.put("told", true); } catch (Exception ignored) { }
                        tripSave(true);
                        String[] tv = tripVars(l);
                        steps.add(new Object[] { tv[0] + "A", tripV(l) });
                        steps.add(new Object[] { tv[1], tripV(l) });
                    }
                    h.removeCallbacks(baseR);
                    said.put("temp", "1"); said.put("lv", "1"); said.put("key", "1"); said.put("stuffy", "1");
                    for (String k : TYRE_KEYS) said.put(k, tyreState(k));
                    sequence(steps, done);
                    return;
                }
                if (app.now() - t0 > 6000) { seqDone = null; done.done(false); return; }
                h.postDelayed(this, 300);
            }
        };
        seqR.run();
    }

    // ================= 订阅推送 =================
    void onEvent(JSONObject msg) {
        String id = msg.optString("subscriptionId");
        JSONObject err = msg.optJSONObject("error");
        if (err != null) {
            String code = err.optString("code");
            if (id.equals(coreId) || id.equals(mediaId)) {
                if ("BACKEND_UNAVAILABLE".equals(code)) { stale = true; app.refreshExtra(); emit(); }
                else if ("RESOURCE_LIMIT".equals(code) && id.equals(coreId)) fail(code, gen);
            }
            return;
        }
        JSONObject d = msg.optJSONObject("data");
        if (d == null) return;
        if (id.equals(coreId)) onVehicle(d, true);
        else if (id.equals(detailId)) onVehicle(d, false);
        else if (id.equals(mediaId)) onMedia(d, false);
    }
    private boolean coreFirst, detailFirst;
    private final Set<String> coreSet = new HashSet<>(), detailSet = new HashSet<>();
    private void onVehicle(JSONObject data, boolean core) {
        boolean initial = core ? coreFirst : detailFirst;
        if (core) coreFirst = false; else detailFirst = false;
        merge(tree, data);
        Set<String> hit = new HashSet<>();
        touched(data, "", hit, core ? coreSet : detailSet);
        initial = initial || stale;
        boolean pills = initial || stale, dn = core && !coreSeen;
        if (core) coreSeen = true;
        retries = 0;
        stale = false;
        for (String k : hit) {
            Object a = car.get(k), b = fromTree(k);
            if (b == null) car.remove(k); else car.put(k, b);
            if (a == null ? b == null : a.equals(b)) continue;
            if (k.equals("display.dayNightMode")) dn = true;
            if (PILL.contains(k)) pills = true;
            if (TRIP.contains(k)) tripDirty = true;
            if (!initial) changed(k, a, b);
        }
        if (initial && core) { tripResume(); baseline(); tripLater(14000); }
        if (tripDirty) tripTrack();
        if (pills) app.refreshExtra();
        if (dn) app.refreshScene();
        emit();
    }

    // ================= 媒体：换歌接话；实时歌词 + 宠物轮唱 / 副歌合唱 / 间奏 + 歌词关键词彩蛋 =================
    static String shortText(String s, int n) { s = s == null ? "" : s.replaceAll("\\s+", " ").trim(); return s.length() > n ? s.substring(0, n - 1) + "…" : s; }
    static String str(JSONObject d, String k) { return d == null || d.isNull(k) ? null : d.optString(k, null); }
    boolean playing() { return media != null && media.optBoolean("playing") && !is("audio.muted", true); }
    private static final class LyrLine { final String t; final long at; LyrLine(String t, long at) { this.t = t; this.at = at; } }
    private static final class Lyr {
        String track, line = "", stale = "", hold = "";
        List<LyrLine> lines;
        Map<String, Integer> count = new HashMap<>(), seen = new HashMap<>();
        int idx = -1, tries, turn, holdIdx = -2;
        long asked;
        Long start;
    }
    private Lyr lyr = new Lyr();
    private Runnable interR, pauseR, lyrRetry, humR;
    private long eggAt;
    private final Map<String, Long> eggCool = new HashMap<>();
    private String posTrack;
    private long posPos, posAt = -1;
    private void anchorPos(long pos) { posTrack = lyr.track; posPos = pos; posAt = app.now(); }
    /** 估计的播放位置（毫秒），不知道时 -1 */
    long mediaPos() {
        if (media == null || posAt < 0 || posTrack == null || !posTrack.equals(lyr.track) || media.optLong("durationMs") <= 0) return -1;
        long p = posPos + (playing() ? app.now() - posAt : 0);
        return Math.max(0, Math.min(p, media.optLong("durationMs")));
    }
    private static final long PAUSE_GRACE = 3500;
    boolean musicOn() { return playing() || pauseR != null; }
    private boolean lyricOn() { return !"off".equals(app.settings.lyric); }
    static String clean(String t) { return t == null ? "" : t.replaceAll("\\s+", " ").trim(); }
    private void newTrack(String id, String staleLine, boolean live) {
        h.removeCallbacks(interR);
        if (lyrRetry != null) h.removeCallbacks(lyrRetry);
        Lyr n = new Lyr();
        n.track = id; n.turn = lyr.turn; n.stale = staleLine == null ? "" : staleLine;
        n.start = live ? app.now() : null;
        lyr = n;
    }
    /** 这句能不能显示：刚换歌时句子在歌里的时间要和换歌后过了多久对得上（差 10 秒内），或者连着两句按顺序往下走 */
    private boolean lyricOk(String t) {
        if (lyr.start == null) return lyr.lines == null || lyr.count.containsKey(t);
        if (lyr.lines == null) {
            if (app.now() - lyr.start < 6000) { lyr.hold = t; return false; }
            lyr.start = null;
            return true;
        }
        List<LyrLine> L = lyr.lines;
        long due = app.now() - lyr.start + 10000;
        int last = -1;
        for (int i = 0; i < L.size(); i++) {
            if (!L.get(i).t.equals(t)) continue;
            if (L.get(i).at <= due || i == lyr.holdIdx + 1) { lyr.start = null; lyr.idx = i - 1; lyr.hold = ""; return true; }
            if (last < 0) last = i;
        }
        if (last >= 0) { lyr.hold = t; lyr.holdIdx = last; }
        return false;
    }
    /** 整首歌词：每首取一次，核对 trackId；刚换歌时宿主常常还没准备好：隔 1.5 秒再取，最多 3 次 */
    private void loadLyrics() {
        final String id = str(media, "trackId");
        if (media == null || id == null || lyr.lines != null || app.now() - lyr.asked < 10000 || !host.ready()) return;
        lyr.asked = app.now();
        final int g = gen;
        final Lyr mine = lyr;
        host.request("media.getLyrics", null, (r, code, msg) -> {
            if (g != gen || lyr != mine) return;
            JSONArray arr = r == null ? null : r.optJSONArray("lines");
            if (r == null || !id.equals(str(r, "trackId")) || arr == null || arr.length() == 0) {
                if (++mine.tries <= 3) {
                    if (lyrRetry != null) h.removeCallbacks(lyrRetry);
                    lyrRetry = () -> { if (lyr == mine) { mine.asked = 0; loadLyrics(); } };
                    h.postDelayed(lyrRetry, 1500);
                }
                return;
            }
            List<LyrLine> L = new ArrayList<>();
            Map<String, Integer> count = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                String t = clean(str(o, "text"));
                if (!t.isEmpty()) { L.add(new LyrLine(t, o.optLong("timeMs"))); count.put(t, count.containsKey(t) ? count.get(t) + 1 : 1); }
            }
            if (L.isEmpty()) return;
            mine.lines = L;
            mine.count = count;
            if (!mine.line.isEmpty()) {
                mine.idx = -1;
                locate(mine.line);
                if (mine.idx < 0) { mine.line = ""; lyricShown(false); } else setNext();
            } else if (!mine.hold.isEmpty() && playing()) {
                String hd = mine.hold;
                mine.hold = "";
                if (lyricOk(hd)) { newLine(hd, true); lyricShown(lyricOn()); }
            }
        });
    }
    private void locate(String t) {
        List<LyrLine> L = lyr.lines;
        if (L == null) return;
        int from = lyr.idx;
        for (int i = from + 1; i < L.size(); i++) if (L.get(i).t.equals(t)) { lyr.idx = i; return; }
        for (int i = 0; i <= from && i < L.size(); i++) if (L.get(i).t.equals(t)) { lyr.idx = i; return; }
        lyr.idx = -1;
    }
    private long gapMs() { List<LyrLine> L = lyr.lines; int i = lyr.idx; return L != null && i >= 0 && i + 1 < L.size() ? L.get(i + 1).at - L.get(i).at : 0; }
    private int lineMs() { long g = gapMs(); return g == 0 ? 4000 : g > 8000 ? 4500 : (int) Math.max(1200, g); }
    private String nextText() { List<LyrLine> L = lyr.lines; int i = lyr.idx; return L != null && i >= 0 && i + 1 < L.size() ? L.get(i + 1).t : ""; }
    private boolean chorus(String t) { if (lyr.lines != null) { Integer c = lyr.count.get(t); return c != null && c >= 2; } Integer s = lyr.seen.get(t); return s != null && s >= 2; }

    private void lyricShown(boolean on) {
        if (app.clock.lyric.shown() == on) return;
        app.clock.lyric.show(on);
        app.setHasLyric(on);
        app.clock.wake();
    }
    /** 换一句（ms = 0：不知道唱到哪了，整句直接亮） */
    private void showLine(String t, int ms, boolean instant) {
        if (musicOn()) lyricShown(true);
        app.clock.lyric.line(t, ms, instant);
        setNext();
    }
    private void setNext() {
        if (!app.shown("lyricNext")) { app.clock.lyric.setNext(""); return; }
        app.clock.lyric.setNext(nextText());
    }
    private void newLine(String t, boolean instant) {
        lyr.line = t;
        lyr.seen.put(t, lyr.seen.containsKey(t) ? lyr.seen.get(t) + 1 : 1);
        locate(t);
        if (!instant && lyr.idx >= 0) anchorPos(lyr.lines.get(lyr.idx).at);
        h.removeCallbacks(interR);
        if (!lyricOn()) return;
        showLine(t, instant ? 0 : lineMs(), instant);
        if (instant || hidden) return;
        singLine(t);
        if (gapMs() > 8000) { interR = this::interlude; h.postDelayed(interR, 4500); }
    }
    /** 宠物跟唱：主歌两只轮流（唱的那只冒音符），副歌两只一起唱（Neuro 偶尔冒爱心） */
    private void singLine(String t) {
        if ("game".equals(app.mode)) return;
        Pet n = app.randomPet("Neuron"), e = app.randomPet("Eviling");
        if (chorus(t)) {
            if (app.play != null) { if (n != null) app.play.notes(n, 3); if (e != null) app.play.notes(e, 3); }
            Long hc = eggCool.get("heart");
            if (n != null && app.now() - (hc == null ? 0 : hc) > 20000 && app.behave(n, "HeartHeartHeart")) eggCool.put("heart", app.now());
        } else {
            Pet p = (lyr.turn++ % 2) != 0 ? (e != null ? e : n) : (n != null ? n : e);
            if (p != null && app.play != null) app.play.notes(p, 2);
        }
        egg(t);
    }
    private void interlude() {
        interR = null;
        if (!playing() || hidden) return;
        if (app.play != null) for (Pet p : app.livePets()) app.play.notes(p, 4);
        talk("musicInterlude", null, opt().cool(90000));
    }
    private static final Pattern EGG_STAR = Pattern.compile("流星|星星|星空|银河"), EGG_MOON = Pattern.compile("月亮|月光"), EGG_SUN = Pattern.compile("太阳|阳光"),
        EGG_RAIN = Pattern.compile("雨"), EGG_LOVE = Pattern.compile("爱|喜欢|心动|心跳");
    /** 歌词关键词彩蛋：同一个彩蛋 25 秒冷却，彩蛋之间至少隔 6 秒 */
    private void egg(String t) {
        long tn = app.now();
        if (tn - eggAt < 6000) return;
        Object[][] eggs = { { "star", EGG_STAR }, { "moon", EGG_MOON }, { "sun", EGG_SUN }, { "rain", EGG_RAIN }, { "love", EGG_LOVE } };
        for (Object[] g : eggs) {
            String id = (String) g[0];
            Long c = eggCool.get(id);
            if (!((Pattern) g[1]).matcher(t).find() || tn - (c == null ? 0 : c) < 25000) continue;
            if (fireEgg(id)) { eggCool.put(id, tn); eggAt = tn; return; }
        }
    }
    private boolean fireEgg(String id) {
        switch (id) {
            case "star": if (!"night".equals(app.currentScene)) return false; app.shootingStar(Float.NaN, Float.NaN); return true;
            case "moon": {
                if (!"night".equals(app.currentScene) || !app.shown("sun")) return false;
                android.graphics.RectF r = app.sky.moonBox();
                app.fx.burst(r.centerX() / app.u.dens, r.centerY() / app.u.dens, 0xfffffbe6, 12, 0.8f);
                return true;
            }
            case "sun": return app.play != null && app.play.fxFlare();
            case "rain": return app.play != null && app.play.fxRain();
            default: {
                Pet p = app.randomPet("Neuron");
                if (p == null) p = app.randomPet();
                android.graphics.PointF q = app.pos(p);
                if (q == null) return false;
                app.fx.burst(q.x, q.y - App.SPRITE * app.petScale() * 0.6f, 0xffff8fab, 12, 0.8f);
                return true;
            }
        }
    }
    /** 设置里开关歌词 */
    void lyricSetting() {
        if (!lyricOn()) { h.removeCallbacks(interR); lyricShown(false); app.clock.lyric.clear(); }
        else if (playing() && !lyr.line.isEmpty()) { showLine(lyr.line, 0, true); lyricShown(true); }
    }
    void lyricNextSetting() { setNext(); }

    // 播放器包名 → 名字
    private static final Object[][] APPS = { { "(?i)qqmusic", "QQ音乐" }, { "(?i)cloudmusic|netease", "网易云音乐" }, { "(?i)kugou", "酷狗音乐" }, { "(?i)kuwo", "酷我音乐" }, { "(?i)ximalaya", "喜马拉雅" },
        { "(?i)qingting", "蜻蜓FM" }, { "(?i)luna", "汽水音乐" }, { "(?i)migu", "咪咕音乐" }, { "(?i)spotify", "Spotify" }, { "(?i)apple", "Apple Music" }, { "(?i)bilibili", "哔哩哔哩" },
        { "(?i)bluetooth|btmusic|\\.bt\\b", "蓝牙音乐" }, { "(?i)radio|\\.fm\\b", "收音机" }, { "(?i)byd|dilink|media", "车机音乐" } };
    private static final Pattern[] APP_P = new Pattern[APPS.length];
    static { for (int i = 0; i < APPS.length; i++) APP_P[i] = Pattern.compile((String) APPS[i][0]); }
    static String appName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        for (int i = 0; i < APPS.length; i++) if (APP_P[i].matcher(pkg).find()) return (String) APPS[i][1];
        return "";
    }
    private boolean mediaFirst;
    void onMedia(JSONObject m, boolean initialIn) {
        boolean initial = initialIn || mediaFirst;
        mediaFirst = false;
        JSONObject prev = media;
        if (!initial && m != null && prev != null && m.optLong("updatedAt") > 0 && prev.optLong("updatedAt") > 0 && m.optLong("updatedAt") < prev.optLong("updatedAt")) return; // 晚到的旧快照
        media = m != null && "ok".equals(m.optString("status")) ? m : null;
        boolean was = prev != null && prev.optBoolean("playing"), on = playing(), gap = pauseR != null;
        String track = media == null ? null : str(media, "trackId") != null ? str(media, "trackId") : str(media, "title");
        String prevTrack = prev == null ? null : str(prev, "trackId") != null ? str(prev, "trackId") : str(prev, "title");
        boolean newT = track == null ? prevTrack != null : !track.equals(prevTrack);
        if (newT) newTrack(track, prev == null ? null : clean(str(prev, "lyric")), !initial && prev != null);
        String raw = media != null ? clean(str(media, "lyric")) : "";
        if (!lyr.stale.isEmpty() && !raw.equals(lyr.stale)) lyr.stale = "";
        if (media != null) {
            boolean posCh = prev == null || newT || media.optLong("positionMs", -1) != prev.optLong("positionMs", -1) || media.optBoolean("playing") != prev.optBoolean("playing");
            if (posCh) {
                if (prev != null && !newT && prev.optBoolean("playing") && !media.optBoolean("playing") && posAt >= 0) anchorPos(mediaPos());
                else if (media.has("positionMs") && !media.isNull("positionMs") && (newT || prev == null || media.optLong("positionMs") != prev.optLong("positionMs") || posAt < 0)) anchorPos(media.optLong("positionMs"));
                else if (posAt >= 0) posAt = app.now();
            }
        }
        String appN = media != null ? appName(str(media, "packageName")) : "", prevApp = prev != null ? appName(str(prev, "packageName")) : "";
        if (pressed != null && media != null && media.optBoolean("playing") == pressed) clearPressed();
        if (app.now() >= hushUntil) { // 刚在卡片上按过键：换歌、暂停、继续由音乐那边接话
            if (!initial && on && !appN.isEmpty() && !prevApp.isEmpty() && !appN.equals(prevApp)) talk("musicApp", V("app", appN), opt().cool(600000));
            else if (!initial && on && str(media, "title") != null && newT) {
                boolean by = str(media, "artist") != null && app.rnd.nextFloat() < 0.4f;
                talk(by ? "musicNewBy" : "musicNew", V("title", shortText(str(media, "title"), 12), "artist", shortText(str(media, "artist"), 8)), opt().cool(180000).after(p -> { if (app.play != null) app.play.notes(p, 5); }));
            } else if (!initial && on && !was && !gap) talk("musicPlay", null, opt().cool(600000));
        }
        if (on) { if (pauseR != null) h.removeCallbacks(pauseR); pauseR = null; }
        else if (!initial && was && pauseR == null) { pauseR = this::pauseConfirmed; h.postDelayed(pauseR, PAUSE_GRACE); }
        if (on) {
            if (lyricOn()) loadLyrics();
            String t = raw.equals(lyr.stale) ? "" : raw;
            if (!t.isEmpty() && !t.equals(lyr.line) && !lyricOk(t)) t = "";
            if (!t.isEmpty() && !t.equals(lyr.line)) newLine(t, initial || !was);
            else if (!t.isEmpty() && !was && lyricOn()) showLine(t, 0, true);
        } else if (pauseR == null) h.removeCallbacks(interR);
        lyricShown(musicOn() && lyricOn() && !lyr.line.isEmpty());
        if (on && lyr.line.isEmpty()) hum(); else stopHum();
        syncCover();
        String pt = prev == null ? null : str(prev, "title"), mt = media == null ? null : str(media, "title"), pa = prev == null ? null : str(prev, "artist"), ma = media == null ? null : str(media, "artist");
        boolean pp = prev != null && prev.optBoolean("playing"), mp = media != null && media.optBoolean("playing");
        if (!eq(pt, mt) || !eq(pa, ma) || was != on || pp != mp) app.refreshExtra();
        if (app.music != null) app.music.changed(newT && !initial);
        emit();
    }
    private static boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }
    private void pauseConfirmed() {
        pauseR = null;
        if (playing()) return;
        if (media != null && !media.optBoolean("playing") && app.now() >= hushUntil && app.rnd.nextFloat() < 0.5f) talk("musicPause", null, opt().cool(600000));
        h.removeCallbacks(interR);
        stopHum();
        lyricShown(false);
        syncCover();
        app.refreshExtra();
    }
    private void hum() {
        if (humR != null || !playing() || hidden || !lyr.line.isEmpty()) return;
        humR = () -> {
            humR = null;
            if (!playing() || !lyr.line.isEmpty()) return;
            if (app.now() - lastLineAt >= GAP && !app.busy() && !"game".equals(app.mode)) {
                Pet p = app.randomPet();
                if (p != null) {
                    lastLineAt = app.now();
                    app.say(p, app.line(p, "musicHum"), 3400);
                    if (app.play != null) app.play.notes(p, 4);
                }
            }
            hum();
        };
        h.postDelayed(humR, (long) app.rand(40000, 70000));
    }
    private void stopHum() { if (humR != null) h.removeCallbacks(humR); humR = null; }

    // ================= 封面 =================
    // 宿主的封面地址靠不住（几首歌同一个地址、内容换了地址不变）：按"地址 + 曲目"认封面，换歌时重新取；和上一首地址一样时 2 秒后再取一次
    private String coverKey = "", coverUrl = "";
    private int coverSeq, coverSize = -1;
    private Runnable coverRetry;
    private boolean wantCover() { return !"off".equals(app.settings.cover) && musicOn(); }
    private void coverShown(boolean on) {
        Cover c = app.clock.cover;
        if (c.on() == on) return;
        c.show(on);
        app.clock.setHasCover(on);
        app.clock.wake();
    }
    void syncCover() {
        Cover cv = app.clock.cover;
        if ("off".equals(app.settings.cover)) {
            coverShown(false);
            if (coverRetry != null) h.removeCallbacks(coverRetry);
            if (!coverKey.isEmpty()) { coverKey = coverUrl = ""; coverSeq++; h.postDelayed(() -> { if ("off".equals(app.settings.cover)) cv.clear(); }, 800); }
            return;
        }
        boolean want = wantCover() && media != null;
        if (want && str(media, "coverUrl") == null && pauseR != null) return; // 换歌的空档：宿主先推空封面，先别收
        String url = want && str(media, "coverUrl") != null ? str(media, "coverUrl") : "";
        if (url.isEmpty()) { coverShown(false); return; }
        String key = url + "#" + (lyr.track == null ? "" : lyr.track);
        if (key.equals(coverKey)) { if (key.equals(cv.key())) coverShown(true); return; }
        boolean same = url.equals(coverUrl);
        coverKey = key;
        coverUrl = url;
        if (coverRetry != null) h.removeCallbacks(coverRetry);
        fetchCover(url, key, false);
        if (same) { coverRetry = () -> { if (coverKey.equals(key)) fetchCover(url, key, true); }; h.postDelayed(coverRetry, 2000); }
    }
    private void fetchCover(final String url, final String key, final boolean onlyIfChanged) {
        final int s = ++coverSeq;
        ioH.post(() -> {
            byte[] bytes = null;
            try (ParcelFileDescriptor fd = host.openCover(url)) {
                if (fd != null) bytes = Io.readAll(new FileInputStream(fd.getFileDescriptor()));
            } catch (Exception ignored) { }
            final byte[] b = bytes;
            Bitmap bm = null;
            if (b != null && !(onlyIfChanged && b.length == coverSize)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(b, 0, b.length, o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 360 && o.outHeight / (sample * 2) >= 360) sample *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
            }
            final Bitmap img = bm;
            h.post(() -> {
                if (s != coverSeq || !coverKey.equals(key)) { if (img != null) img.recycle(); return; }
                if (b == null) { if (!onlyIfChanged) { coverKey = ""; coverShown(false); } return; }
                if (onlyIfChanged && b.length == coverSize) return;
                coverSize = b.length;
                if (img == null) { if (!onlyIfChanged) { coverKey = ""; coverShown(false); } return; }
                app.clock.cover.image(img, key);
                if (wantCover()) coverShown(true);
                if (app.music != null) app.music.changed(false);
                app.refreshExtra();
            });
        });
    }

    // ================= 播放控制（音乐那边用） =================
    private Boolean pressed;
    private Runnable pressedR;
    private long hushUntil;
    private void clearPressed() { pressed = null; if (pressedR != null) h.removeCallbacks(pressedR); }
    boolean effPlaying() { return pressed != null ? pressed : playing(); }
    boolean can(String cmd) { return actions.contains("media." + cmd); }
    /** 现在放的歌（没有返回 null）：歌名、歌手、播放器名、在不在放、知不知道在放、封面 */
    static final class Song { String title, artist, app; boolean playing, known; Bitmap cover; }
    Song song() {
        String t = str(media, "title");
        if (media == null || t == null || t.isEmpty() || stale) return null;
        Song s = new Song();
        s.title = t;
        s.artist = str(media, "artist") == null ? "" : str(media, "artist");
        s.app = appName(str(media, "packageName"));
        s.playing = effPlaying();
        s.known = pressed != null || media.has("playing") && !media.isNull("playing");
        s.cover = !"off".equals(app.settings.cover) && coverKey.equals(app.clock.cover.key()) ? app.clock.cover.current() : null;
        return s;
    }
    interface Sent { void done(String errCode); }
    /** 发一个播放控制（play / pause / previous / next）；按了播放 / 暂停先按按下去的显示（最多 4 秒），失败变回去 */
    void send(String cmd, Sent done) {
        if (!can(cmd)) { if (done != null) done.done("UNSUPPORTED"); return; }
        if (cmd.equals("play") || cmd.equals("pause")) {
            clearPressed();
            pressed = cmd.equals("play");
            pressedR = () -> { pressed = null; app.refreshExtra(); if (app.music != null) app.music.changed(false); };
            h.postDelayed(pressedR, 4000);
            app.refreshExtra();
        }
        host.request("media." + cmd, new JSONObject(), (r, code, msg) -> {
            if (code != null && (cmd.equals("play") || cmd.equals("pause"))) { clearPressed(); app.refreshExtra(); }
            if (done != null) done.done(code);
        });
    }
    void hush(long ms) { hushUntil = app.now() + ms; }

    // ================= 胶囊 =================
    private static String lv(double p) { return p >= 50 ? " lv-hi" : p >= 20 ? " lv-mid" : " lv-low"; }
    List<Pills.Pill> pills() {
        ArrayList<Pills.Pill> out = new ArrayList<>();
        if (stale) return out;
        double b = n("energy.batteryPercent"), er = n("energy.electricRange"), fu = n("energy.fuelPercent"), fr = n("energy.fuelRange");
        if (app.shown("warn")) {
            ArrayList<Integer> bad = new ArrayList<>();
            for (int i = 0; i < 4; i++) if (!tyreState(TYRE_KEYS[i]).isEmpty()) bad.add(i);
            if (!bad.isEmpty()) out.add(new Pills.Pill("pill-warn", (bad.size() > 1 ? bad.size() + " 个轮胎" : TYRE_NAMES[bad.get(0)]) + "胎压" + tyreState(TYRE_KEYS[bad.get(0)])));
            if (is("warning.lowVoltageBatteryFault", true)) out.add(new Pills.Pill("pill-warn", "低压供电异常"));
            if (is("warning.keyBatteryLow", true)) out.add(new Pills.Pill("pill-warn", "钥匙电池电量低"));
        }
        if (!Double.isNaN(b) && app.shown("ev")) {
            double rt = n("energy.charging.remainingTime");
            if (charging()) out.add(new Pills.Pill("pill-ev charging" + lv(b), "充电中 " + Math.round(b) + "%" + (rt > 0 ? " · 还要 " + dur(rt) : "")));
            else out.add(new Pills.Pill("pill-ev" + lv(b), "电量 " + Math.round(b) + "%" + (!Double.isNaN(er) ? " · " + Math.round(er) + " km" : "")));
        }
        if (!Double.isNaN(fu) && app.shown("fuel")) out.add(new Pills.Pill("pill-fuel" + lv(fu), "油量 " + Math.round(fu) + "%" + (!Double.isNaN(fr) ? " · " + Math.round(fr) + " km" : "")));
        double ot = n("ac.outdoorTemperature");
        if (!Double.isNaN(ot) && app.shown("temp")) out.add(new Pills.Pill("pill-temp", "室外 " + Math.round(ot) + "°"));
        if (app.shown("music") && app.music != null) { Pills.Pill mp = app.music.pill(); if (mp != null) out.add(mp); }
        return out;
    }

    // ================= 车况页的数据源 =================
    void emit() { for (Runnable r : new ArrayList<>(listeners)) { try { r.run(); } catch (RuntimeException e) { android.util.Log.w(Uiplus.TAG, "车况页出错", e); } } }
    /** 车况页打开：订阅只在页面上显示的字段，查宿主信息、VIN；返回关掉的方法 */
    Runnable open(Runnable fn) {
        listeners.add(fn);
        detailOn();
        loadInfo();
        return () -> { listeners.remove(fn); if (listeners.isEmpty()) detailOff(false); };
    }
    Object get(String k) { return car.get(k); }
    String unit(String k) { return units.get(k); }
    boolean stale() { return stale; }
    /** 车机的日夜模式 'day' / 'night'；宿主没有这个字段返回 ''；还没收到车况返回 null（连不上时 15 秒后也当没有） */
    String dayNight() {
        Object m = car.get("display.dayNightMode");
        if (m != null) return (String) m;
        return coreSeen || app.now() - bornAt > 15000 ? "" : null;
    }
    boolean blinking(String side) { return app.now() - blinkAt["left".equals(side) ? 0 : 1] < 1600; }
    /** 宿主有 system.getProperty 才查安卓版本：null 没查到 / 读不到 */
    boolean hasProp() { return androidVer != null || actions.contains("system.getProperty"); }
    private void loadInfo() {
        if (caps == null || (info != null && vin != null) || app.now() - infoAsked < 30000 || !host.ready()) return;
        infoAsked = app.now();
        if (info == null) host.request("info", null, (r, code, msg) -> { if (r != null) { info = r; emit(); } });
        if (!androidAsked) {
            androidAsked = true;
            try { host.request("system.getProperty", new JSONObject().put("name", "ro.build.version.release"), (r, code, msg) -> { androidVer = r != null ? r.optString("value", null) : null; if (code != null) androidAsked = false; emit(); }); } catch (Exception ignored) { }
        }
        if (vin == null) {
            try {
                host.request("vehicle.get", new JSONObject().put("fields", new JSONArray().put(VIN)), (r, code, msg) -> {
                    JSONObject d = r == null ? null : r.optJSONObject("data");
                    JSONObject id = d == null ? null : d.optJSONObject("identity");
                    String v = id == null ? null : id.optString("vin", null);
                    vin = v != null ? v : "";
                    emit();
                });
            } catch (Exception ignored) { }
        }
    }
    private void detailOn() {
        detailWanted = true;
        if (caps == null || detailId != null || !live()) return;
        ArrayList<String> list = new ArrayList<>();
        for (String k : DETAIL) { String m = caps.get(k); if (m != null && !"none".equals(m)) list.add(k); }
        if (list.isEmpty()) return;
        final String id = detailId = "detail" + (++seq);
        detailList = list;
        detailSet.clear();
        detailSet.addAll(list);
        detailFirst = true;
        try {
            host.request("vehicle.subscribe", new JSONObject().put("subscriptionId", id).put("fields", new JSONArray(list)), (r, code, msg) -> {
                if (code != null && id.equals(detailId)) detailId = null; // 失败了：下次打开车况页再试
            });
        } catch (Exception ignored) { }
    }
    private void detailOff(boolean keepWanted) {
        detailWanted = keepWanted;
        String id = detailId;
        detailId = null;
        if (id == null) return;
        unsub("vehicle", id);
        if (detailList != null) for (String k : detailList) { car.remove(k); units.remove(k); }
    }

    // ================= 订阅生命周期 =================
    private boolean live() { return host.ready() && host.canRead() && host.backendReady() && session != null && session.equals(host.session()); }
    private void unsub(String ns, String id) {
        if (id == null || session == null || !session.equals(host.session())) return;
        try { host.request(ns + ".unsubscribe", new JSONObject().put("subscriptionId", id), null); } catch (Exception ignored) { }
    }
    private final Runnable retryR = this::start;
    void stop() {
        gen++;
        unsub("vehicle", coreId); unsub("media", mediaId);
        coreId = mediaId = null;
        detailOff(detailWanted);
        h.removeCallbacks(retryR); h.removeCallbacks(baseR); h.removeCallbacks(pendR); h.removeCallbacks(interR);
        if (pauseR != null) h.removeCallbacks(pauseR);
        if (coverRetry != null) h.removeCallbacks(coverRetry);
        if (lyrRetry != null) h.removeCallbacks(lyrRetry);
        h.removeCallbacks(tripR); h.removeCallbacks(duetR); h.removeCallbacks(seqR); h.removeCallbacks(socR);
        clearPressed();
        if (seqDone != null) { App.Done sd = seqDone; seqDone = null; sd.done(true); }
        pauseR = null;
        pending = null;
        stopHum();
        if (tripCur != null) tripSave(true);
    }
    private static boolean transientErr(String code) { return !Arrays.asList("PERMISSION_DENIED", "INVALID_ARGUMENT", "UNKNOWN_FIELD", "UNSUPPORTED", "RESOURCE_LIMIT").contains(code); }
    private void fail(String code, int g) {
        if (g != gen) return;
        if ("RESOURCE_LIMIT".equals(code) && fields != null && fields.size() > 6) fields = new ArrayList<>(fields.subList(0, (fields.size() + 1) / 2));
        if ("SESSION_CLOSED".equals(code)) return;
        if (!transientErr(code) && retries >= 6) return;
        retries++;
        stop();
        final int g2 = gen;
        h.postDelayed(() -> { if (g2 == gen) start(); }, Math.min(10000, 1000L << Math.min(retries, 4)));
    }
    /** 宿主连上、授权变了、后端好了：（重新）订阅 */
    void hostChanged() {
        String s = host.ready() && host.canRead() ? host.session() : null;
        if (s == null) { if (session != null) { session = null; stop(); } return; }
        if (!s.equals(session)) { stop(); session = s; caps = null; actions.clear(); }
        if (host.backendReady() && !hidden && coreId == null) start();
    }
    void start() {
        if (!live() || hidden) return;
        final int g = gen;
        if (caps == null) {
            host.request("capabilities", null, (r, code, msg) -> {
                if (g != gen) return;
                if (code != null) { fail(code, g); return; }
                Map<String, String> c = new HashMap<>();
                JSONArray f = r.optJSONArray("fields");
                for (int i = 0; f != null && i < f.length(); i++) { JSONObject o = f.optJSONObject(i); if (o != null && o.has("name")) c.put(o.optString("name"), o.optString("subscriptionMode", "native")); }
                caps = c;
                actions.clear();
                JSONArray a = r.optJSONArray("actions");
                for (int i = 0; a != null && i < a.length(); i++) { Object o = a.opt(i); String n = o instanceof JSONObject ? ((JSONObject) o).optString("method") : String.valueOf(o); if (!n.isEmpty()) actions.add(n); }
                if (!c.containsKey(VIN)) vin = "";
                fields = new ArrayList<>();
                for (String k : CORE) { String mm = c.get(k); if (mm != null && !"none".equals(mm)) fields.add(k); }
                if (app.music != null) app.music.changed(false);
                app.refreshExtra();
                if (app.ai != null) app.ai.hostChanged();
                start();
            });
            return;
        }
        if (coreId == null && fields != null && !fields.isEmpty()) {
            final String id = coreId = "core" + (++seq);
            coreSet.clear();
            coreSet.addAll(fields);
            coreFirst = true;
            try {
                host.request("vehicle.subscribe", new JSONObject().put("subscriptionId", id).put("fields", new JSONArray(fields)), (r, code, msg) -> {
                    if (!id.equals(coreId)) return;
                    if (code != null) { coreId = null; fail(code, g); }
                });
            } catch (Exception ignored) { }
        }
        if (mediaId == null) {
            final String id = mediaId = "media" + (++seq);
            mediaFirst = true;
            try {
                host.request("media.subscribe", new JSONObject().put("subscriptionId", id), (r, code, msg) -> {
                    if (!id.equals(mediaId)) return;
                    if (code != null) { mediaId = null; fail(code, g); return; }
                    host.request("media.get", null, (m, c2, m2) -> { if (m != null && id.equals(mediaId)) onMedia(m, false); });
                });
            } catch (Exception ignored) { }
        }
        if (detailWanted) { detailOn(); loadInfo(); }
    }
    void setHidden(boolean hd) {
        hidden = hd;
        if (hd) stop(); else hostChanged();
    }
    void quit() { stop(); io.quitSafely(); }
}
