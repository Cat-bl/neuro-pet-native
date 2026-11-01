package com.x3card.pet;

import com.x3card.pet.ai.Json;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 操作车机（同网页版 ai-control.js）：宿主的原生操作（80 个，组合、发广播不开放）。两种做法：
 * · 工具调用：给外部应用的接口里没有工具清单，原生版自己带着（assets 的 tools.json，同宿主给网页卡片的那份），按宿主能力目录筛过、加上安全提示后交给模型；
 *   模型调用工具，这里按安全分级拦一道、按工具定义严格校验参数（同宿主：多余字段、类型不对整个拒绝），再按方法名交给宿主执行。另有 3 个查询：车况、媒体快照、整首歌词。
 * · 文字指令（模型 / 中转站不支持工具调用时）：模型在回复里写 〔方法 参数…〕，按下表严格校验后执行（参数按 actions.json 的参数名排成对象）。
 * 宿主不管车在不在开，所以安全分级在这里强制：0 直接做；1 先在气泡里让车主点"确定"；2 开车时（车速 > 0 或挂着 D / R）不做（也要确认）。
 * 宿主新加的、下表里没有的操作按 2 算。没有「执行操作」授权时所有操作返回 PERMISSION_DENIED（查询不受影响）
 */
final class AiControl {
    static final int DIRECT = 0, CONFIRM = 1, DRIVING = 3;

    /** 参数说明：b 开关、i 整数、e 枚举、x 文字、p 包名 */
    static final class Spec {
        final char t;
        final double min, max;
        final int[] v;
        final String u;
        final Map<Integer, String> n;
        final String[] e;
        Spec(char t, double min, double max, int[] v, String u, Map<Integer, String> n, String[] e) { this.t = t; this.min = min; this.max = max; this.v = v; this.u = u; this.n = n; this.e = e; }
    }
    static final class Def {
        final String m, name, hint;
        final Spec[] p;
        final int lv;
        Def(String m, Spec[] p, String name, int lv, String hint) { this.m = m; this.p = p; this.name = name; this.lv = lv; this.hint = hint == null ? "" : hint; }
    }
    /** 一次操作：方法、按参数顺序排的值（给分级、显示用）；工具调用的还带工具名和参数对象 */
    static final class Op {
        String m, name, tool;
        List<Object> a = new ArrayList<>();
        JSONObject obj;
        /** 不能交给宿主时的错误 { code, message } */
        JSONObject err;
    }
    /** 执行结果：成功 result；失败 code、给车主看的原因 reason、宿主的原话 rawMsg */
    interface Done { void done(JSONObject result, String code, String reason, String rawMsg); }

    private static final Spec B = new Spec('b', 0, 0, null, "", null, null);
    private static Spec I(int min, int max, String u, Map<Integer, String> n) { return new Spec('i', min, max, null, u, n, null); }
    private static Spec E(String... list) { return new Spec('e', 0, 0, null, "", null, list); }
    private static Spec D(String up, String down) { return new Spec('i', 0, 0, new int[] { -1, 1 }, "", nm(1, up, -1, down), null); }
    private static Map<Integer, String> nm(Object... kv) { Map<Integer, String> m = new HashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) m.put((Integer) kv[i], (String) kv[i + 1]); return m; }
    private static final Spec SIDE = E("driver", "passenger"), LV3 = I(0, 2, "", nm(0, "关", 1, "低档", 2, "高档")), PKG = new Spec('p', 0, 0, null, "", null, null), TEXT = new Spec('x', 0, 0, null, "", null, null);
    private static final Map<Integer, String> WIND = nm(1, "吹脸", 2, "脸+脚", 3, "吹脚", 4, "脚+前风挡", 5, "吹前风挡", 6, "脸+脚+前风挡", 7, "脸+前风挡");
    private static final String[] COLORS = { "iceBlue", "coldWhite", "warmWhite", "redOrange", "red", "redPurple", "darkBlue" };
    private static Spec[] ps(Spec... s) { return s; }
    private static final Spec[] NONE = {};
    // [方法, 参数, 中文名, 级别, 说明]（说明写给模型看）
    static final Def[] LIST = {
        new Def("media.play", NONE, "播放音乐", 0, null), new Def("media.pause", NONE, "暂停音乐", 0, null), new Def("media.previous", NONE, "上一首", 0, null), new Def("media.next", NONE, "下一首", 0, null),
        new Def("media.togglePlayPause", NONE, "播放 / 暂停", 0, null), new Def("media.restoreLastSource", NONE, "恢复上次的音乐", 0, "车机刚启动、还没放过歌时用"),
        new Def("media.floatingLyrics.setMode", ps(I(0, 2, "", nm(0, "关", 1, "中控屏", 2, "仪表屏"))), "悬浮歌词", 0, "0 关、1 中控屏、2 仪表屏"),
        new Def("vehicle.ac.setPower", ps(B), "空调开关", 0, null), new Def("vehicle.ac.togglePower", NONE, "空调开 / 关切换", 0, null),
        new Def("vehicle.ac.setCompressor", ps(B), "A/C 压缩机", 0, null), new Def("vehicle.ac.toggleCompressor", NONE, "A/C 压缩机切换", 0, null),
        new Def("vehicle.ac.setDriverTemperature", ps(I(17, 33, "°C", null)), "空调温度", 0, "摄氏度整数"), new Def("vehicle.ac.adjustDriverTemperature", ps(D("调高", "调低")), "空调温度", 0, "调一档：1 升、-1 降"),
        new Def("vehicle.ac.setFanLevel", ps(I(0, 7, " 档", null)), "风量", 0, null), new Def("vehicle.ac.adjustFanLevel", ps(D("调大", "调小")), "风量", 0, "调一档：1 大、-1 小"),
        new Def("vehicle.ac.setCirculation", ps(E("outside", "inside")), "内外循环", 0, null), new Def("vehicle.ac.toggleCirculation", NONE, "内外循环切换", 0, null),
        new Def("vehicle.ac.setFrontDefrost", ps(B), "前挡除雾", 0, null), new Def("vehicle.ac.toggleFrontDefrost", NONE, "前挡除雾切换", 0, null),
        new Def("vehicle.ac.setRearDefrost", ps(B), "后挡除雾", 0, null), new Def("vehicle.ac.toggleRearDefrost", NONE, "后挡除雾切换", 0, null),
        new Def("vehicle.ac.setWindMode", ps(I(1, 7, "", WIND)), "空调送风方向", 0, "1 吹脸、2 脸+脚、3 吹脚、4 脚+前风挡、5 吹前风挡、6 脸+脚+前风挡、7 脸+前风挡；车型可能只支持一部分"),
        new Def("vehicle.ac.setAuto", ps(B), "空调自动模式", 0, "true 自动、false 手动（手动不是关空调）"),
        new Def("vehicle.ac.setRearPower", ps(B), "后排空调开关", 0, null),
        new Def("vehicle.ac.setRearTemperature", ps(I(17, 33, "°C", null)), "后排空调温度", 0, "摄氏度整数"),
        new Def("vehicle.ac.setRearFanLevel", ps(I(0, 7, " 档", null)), "后排风量", 0, "有的车只有 4 档；0 档不是关后排空调"),
        new Def("vehicle.ac.setRearWindMode", ps(I(1, 5, "", WIND)), "后排送风方向", 0, "1 吹脸、2 脸+脚、3 吹脚、4 脚+前风挡、5 吹前风挡"),
        new Def("vehicle.ac.setRearAuto", ps(B), "后排空调自动模式", 0, "true 自动、false 手动（手动不是关后排空调）"),
        new Def("vehicle.seat.setHeating", ps(SIDE, LV3), "座椅加热", 0, "0 关、1 低、2 高"), new Def("vehicle.seat.cycleHeating", ps(SIDE), "座椅加热换一档", 0, null),
        new Def("vehicle.seat.setVentilation", ps(SIDE, LV3), "座椅通风", 0, "0 关、1 低、2 高"), new Def("vehicle.seat.cycleVentilation", ps(SIDE), "座椅通风换一档", 0, null),
        new Def("vehicle.body.setWindowsMode", ps(E("open", "close", "stop", "half", "vent")), "车窗", 1, "四个窗一起：全开、关、停、半开、通风"),
        new Def("vehicle.body.toggleWindowsMode", ps(E("open", "half", "vent")), "车窗切换", 1, "已经是这个状态就关上"),
        new Def("vehicle.body.setWindowPosition", ps(I(1, 4, "", nm(1, "左前窗", 2, "右前窗", 3, "左后窗", 4, "右后窗")), I(0, 100, "%", null)), "单个车窗", 1, "第一个是哪扇：1 左前、2 右前、3 左后、4 右后；第二个开到百分之几：0 全关、100 全开"),
        new Def("vehicle.body.operateSunroof", ps(E("close", "open", "comfort", "vent", "stop")), "天窗", 1, null), new Def("vehicle.body.toggleSunroof", NONE, "天窗开 / 关切换", 1, null),
        new Def("vehicle.body.operateSunshade", ps(E("close", "open", "stop")), "遮阳帘", 1, null), new Def("vehicle.body.toggleSunshade", NONE, "遮阳帘开 / 关切换", 1, null),
        new Def("vehicle.body.operateTailgate", ps(E("open", "close")), "尾门", 2, "只能在 P 挡"),
        new Def("vehicle.body.unlockDoors", NONE, "解锁车门", 2, null),
        new Def("vehicle.light.setLowBeam", ps(B), "近光灯", 1, null), new Def("vehicle.light.setDaytimeRunning", ps(B), "日行灯", 1, null), new Def("vehicle.light.toggleDaytimeRunning", NONE, "日行灯切换", 1, null),
        new Def("vehicle.light.operateReadingLight", ps(E("on", "off")), "阅读灯", 0, null),
        new Def("vehicle.light.setDynamicColorful", ps(B), "动态多彩灯", 0, null),
        new Def("vehicle.light.setAmbientBrightness", ps(I(1, 5, " 档", null)), "氛围灯亮度", 0, null),
        new Def("vehicle.light.setAmbientColor", ps(E(COLORS)), "氛围灯颜色", 0, "冰蓝、冷白、暖白、橙红、红、紫红、深蓝"),
        new Def("vehicle.light.setAmbientMusicMode", ps(E("on", "off")), "氛围灯随音乐律动", 0, null),
        new Def("vehicle.energy.setWirelessCharging", ps(B), "手机无线充电", 0, null), new Def("vehicle.energy.toggleWirelessCharging", NONE, "手机无线充电切换", 0, null),
        new Def("vehicle.energy.setSocSave", ps(B), "保电", 1, "true 强制保电、false 智能保电"), new Def("vehicle.energy.toggleSocSave", NONE, "保电切换", 1, null),
        new Def("vehicle.energy.setSocTarget", ps(I(15, 70, "%", null)), "保电目标电量", 1, "百分比，车型不同范围不同"),
        new Def("vehicle.energy.setRegenerationLevel", ps(E("standard", "high")), "能量回收", 1, null), new Def("vehicle.energy.toggleRegenerationLevel", NONE, "能量回收切换", 1, null),
        new Def("vehicle.energy.setOperationMode", ps(E("eco", "sport", "comfort")), "驾驶模式", 1, "经济、运动、舒适"), new Def("vehicle.energy.cycleOperationMode", NONE, "驾驶模式换一个", 1, null),
        new Def("vehicle.energy.setMode", ps(E("ev", "hev")), "能源模式", 1, "纯电、混动"),
        new Def("vehicle.energy.setSimulator", ps(E("off", "on")), "模拟声浪", 1, null), new Def("vehicle.energy.toggleSimulator", NONE, "模拟声浪切换", 1, null),
        new Def("display.setRotation", ps(E("landscape", "portrait", "followSystem")), "屏幕方向", 1, null),
        new Def("display.setAmbientMusicControl", ps(E("on", "off", "followSystem")), "氛围灯律动控制", 0, null),
        new Def("display.setAmbientBreathMode", ps(E("off", "single", "colorful")), "氛围灯呼吸", 0, "关、单色、多彩"),
        new Def("display.setAmbientBreathBrightness", ps(I(1, 5, " 档", null)), "氛围灯呼吸亮度", 0, null),
        new Def("instrument.setBacklight", ps(B), "仪表休息模式", 2, "true 进入休息模式（仪表背光关）"),
        new Def("instrument.setMapZoom", ps(E("50m", "100m")), "仪表地图比例", 1, null),
        new Def("system.back", NONE, "返回", 1, null), new Def("system.home", NONE, "回主页", 1, null), new Def("system.recents", NONE, "最近任务", 1, null),
        new Def("system.sleep", NONE, "息屏", 2, null), new Def("system.screenshot", NONE, "截屏", 1, null),
        new Def("apps.launch", ps(PKG), "打开应用", 1, "包名只能用下面\"能打开的应用\"里的"),
        new Def("ui.toast", ps(TEXT), "弹一条提示", 0, "80 字以内"), new Def("ui.announce", ps(TEXT), "车机播报一句话", 0, "80 字以内"),
        new Def("windows.openPip", NONE, "打开双画中画", 1, null), new Def("windows.togglePip", NONE, "收起 / 拉回双画中画", 1, "会把你所在的卡片收起"),
        new Def("windows.toggleFloating", NONE, "开关悬浮窗", 1, "会把你所在的卡片收起")
    };
    private static final Map<String, Def> BY = new HashMap<>();
    static { for (Def d : LIST) BY.put(d.m, d); }

    private final App app;
    AiControl(App app) { this.app = app; apps = loadApps(); }

    /* ---------- 宿主有哪些：能力目录里登记了这个操作 ---------- */
    private boolean hostHas(String m) {
        Car c = app.car;
        return c != null && c.caps != null && (c.actions.contains(m) || c.actions.contains("uiplus." + m));
    }
    boolean can(String m) { return BY.containsKey(m) && hostHas(m); }
    boolean available() {
        if (hostTools() != null) return true;
        for (Def d : LIST) if (can(d.m)) return true;
        return false;
    }

    /* ---------- 能打开的应用：媒体快照里见过的播放器（存本地，最多 8 个） ---------- */
    private static final String AKEY = "pet-apps";
    private static final Object[][] NAMES = { { "qqmusic", "QQ音乐" }, { "cloudmusic|netease", "网易云音乐" }, { "kugou", "酷狗音乐" }, { "kuwo", "酷我音乐" }, { "ximalaya", "喜马拉雅" },
        { "qingting", "蜻蜓FM" }, { "luna", "汽水音乐" }, { "migu", "咪咕音乐" }, { "spotify", "Spotify" }, { "bilibili", "哔哩哔哩" } };
    private static final Pattern[] NAME_RE = new Pattern[NAMES.length];
    static { for (int i = 0; i < NAMES.length; i++) NAME_RE[i] = Pattern.compile((String) NAMES[i][0], Pattern.CASE_INSENSITIVE); }
    private static final Pattern PKG_RE = Pattern.compile("^[a-zA-Z]\\w*(\\.[a-zA-Z]\\w*)+$");
    private List<String> apps;
    private List<String> loadApps() {
        ArrayList<String> out = new ArrayList<>();
        try {
            Object r = Json.parse(Store.get(AKEY));
            if (r instanceof JSONArray) for (int i = 0; i < ((JSONArray) r).length() && out.size() < 8; i++) {
                Object p = ((JSONArray) r).opt(i);
                if (p instanceof String && PKG_RE.matcher((String) p).find() && !appName((String) p).isEmpty()) out.add((String) p);
            }
        } catch (RuntimeException ignored) { }
        return out;
    }
    static String appName(String p) { for (int i = 0; i < NAMES.length; i++) if (NAME_RE[i].matcher(p).find()) return (String) NAMES[i][1]; return ""; }
    void sawApp(String p) {
        if (p == null || appName(p).isEmpty() || apps.contains(p) || !PKG_RE.matcher(p).find()) return;
        apps.add(0, p);
        if (apps.size() > 8) apps = new ArrayList<>(apps.subList(0, 8));
        Store.set(AKEY, Json.stringify(new JSONArray(apps)));
    }

    /* ---------- 解析与校验 ---------- */
    static final String WSC = "\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";
    private static final Pattern QUOTES = Pattern.compile("^[\"'“”‘’]+|[\"'“”‘’]+$"), QUOTES2 = Pattern.compile("^[\"'“”]+|[\"'“”]+$");
    private static final Pattern NUM = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern CALL = Pattern.compile("^([a-zA-Z.]+)[" + WSC + "]*(?:\\(([\\s\\S]*)\\))?[" + WSC + "]*([\\s\\S]*)$");
    private static final Pattern ARG_SPLIT = Pattern.compile("[" + WSC + ",，]+");
    static Object value(String s) {
        s = QUOTES.matcher(s).replaceAll("");
        if (s.equals("true")) return Boolean.TRUE;
        if (s.equals("false")) return Boolean.FALSE;
        if (NUM.matcher(s).find()) {
            double d = Double.parseDouble(s);
            return d == Math.rint(d) && Math.abs(d) < 9e15 ? (Object) (long) d : (Object) d;
        }
        return s;
    }
    private static boolean integral(Object v) { if (!(v instanceof Number)) return false; double d = ((Number) v).doubleValue(); return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d); }
    private boolean ok(Spec spec, Object v) {
        switch (spec.t) {
            case 'b': return v instanceof Boolean;
            case 'i': {
                if (!integral(v)) return false;
                double d = ((Number) v).doubleValue();
                if (spec.v != null) { for (int x : spec.v) if (x == d) return true; return false; }
                return d >= spec.min && d <= spec.max;
            }
            case 'e': return v instanceof String && Arrays.asList(spec.e).contains(v);
            case 'x': return v instanceof String && !trim((String) v).isEmpty() && ((String) v).length() <= 80;
            case 'p': return v instanceof String && apps.contains(v);
        }
        return false;
    }
    private static final Pattern TRIM = Pattern.compile("^[" + WSC + "]+|[" + WSC + "]+$");
    static String trim(String s) { return TRIM.matcher(s).replaceAll(""); }
    /** 〔方法 参数…〕里面的内容 → Op；方法没登记、参数个数 / 类型 / 范围不对返回 null（不猜、不截断） */
    Op parse(String raw) {
        raw = trim(raw == null ? "" : raw).replaceFirst("^uiplus\\.", "");
        Matcher mm = CALL.matcher(raw);
        if (!mm.find()) return null;
        Def d = BY.get(mm.group(1));
        if (d == null || !can(d.m)) return null;
        String rest = trim(mm.group(2) != null ? mm.group(2) : mm.group(3));
        ArrayList<Object> args = new ArrayList<>();
        if (d.p.length == 1 && d.p[0].t == 'x') { if (!rest.isEmpty()) args.add(QUOTES2.matcher(rest).replaceAll("")); }
        else if (!rest.isEmpty()) for (String s : ARG_SPLIT.split(rest)) if (!s.isEmpty()) args.add(value(s));
        if (args.size() != d.p.length) return null;
        for (int i = 0; i < args.size(); i++) if (!ok(d.p[i], args.get(i))) return null;
        Op op = new Op();
        op.m = d.m; op.a = args; op.name = d.name;
        return op;
    }

    /* ---------- 安全分级 ---------- */
    private static boolean driving(Map<String, Object> car) {
        Object s = car.get("driving.speed"), g = car.get("driving.gear");
        return s instanceof Number && ((Number) s).doubleValue() > 0 || "D".equals(g) || "R".equals(g);
    }
    private static boolean night(Map<String, Object> car) {
        Object m = car.get("display.dayNightMode");
        if (m instanceof String && !((String) m).isEmpty()) return m.equals("night");
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 19 || h < 6;
    }
    /** DIRECT 直接做、CONFIRM 要确认、DRIVING 开车时不能做 */
    int level(Op op, Map<String, Object> car) {
        Def d = BY.get(op.m);
        int lv = d != null ? d.lv : 2;
        Object a0 = op.a.isEmpty() ? null : op.a.get(0);
        if (op.m.matches("^vehicle\\.body\\.(setWindowsMode|operateSunroof|operateSunshade)$") && "stop".equals(a0)) lv = 0; // 停下来随时可以
        if (op.m.equals("vehicle.light.setLowBeam") && Boolean.TRUE.equals(a0)) lv = 0; // 开灯随时可以
        if (op.m.equals("vehicle.light.setLowBeam") && Boolean.FALSE.equals(a0) && night(car)) lv = 2; // 夜里关大灯
        if (op.m.equals("instrument.setBacklight") && Boolean.FALSE.equals(a0)) lv = 1; // 退出休息模式
        if (lv == 2 && driving(car)) return DRIVING;
        return lv != 0 ? CONFIRM : DIRECT;
    }
    /** 给车主看的一句话："空调温度 24"、"车窗 全开" */
    private static final Map<String, String> WORDS = new HashMap<>();
    static {
        String[] w = { "open", "打开", "close", "关上", "stop", "停下", "half", "半开", "vent", "通风", "comfort", "舒适位", "on", "开", "off", "关", "inside", "内循环", "outside", "外循环",
            "driver", "主驾", "passenger", "副驾", "standard", "标准", "high", "强", "eco", "经济", "sport", "运动", "ev", "纯电", "hev", "混动", "landscape", "横屏", "portrait", "竖屏", "followSystem", "跟随系统",
            "single", "单色", "colorful", "多彩", "iceBlue", "冰蓝", "coldWhite", "冷白", "warmWhite", "暖白", "redOrange", "橙红", "red", "红", "redPurple", "紫红", "darkBlue", "深蓝" };
        for (int i = 0; i + 1 < w.length; i += 2) WORDS.put(w[i], w[i + 1]);
    }
    /** JS 的 String(数)：整数不带小数点 */
    static String str(Object v) {
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return String.valueOf(v);
    }
    String label(Op op) {
        Def d = BY.get(op.m);
        StringBuilder s = new StringBuilder();
        if (d == null) {
            s.append(op.name == null ? "" : op.name);
            if (!op.a.isEmpty()) { for (int i = 0; i < op.a.size(); i++) s.append(i == 0 ? " " : " ").append(str(op.a.get(i))); }
            return s.toString();
        }
        for (int i = 0; i < op.a.size(); i++) {
            Object v = op.a.get(i);
            Spec spec = i < d.p.length ? d.p[i] : null;
            String x;
            if (spec != null && spec.n != null && integral(v) && spec.n.containsKey((int) ((Number) v).doubleValue())) x = spec.n.get((int) ((Number) v).doubleValue());
            else if (Boolean.TRUE.equals(v)) x = "开";
            else if (Boolean.FALSE.equals(v)) x = "关";
            else if (spec != null && spec.t == 'p') x = appName(String.valueOf(v));
            else if (spec != null && spec.t == 'x') { String t = String.valueOf(v); x = "“" + (t.length() > 10 ? t.substring(0, 9) + "…" : t) + "”"; }
            else { String w = v instanceof String ? WORDS.get(v) : null; x = w != null ? w : str(v) + (spec != null ? spec.u : ""); }
            if (i > 0) s.append(' ');
            s.append(x);
        }
        return d.name + (s.length() > 0 ? " " + s : "");
    }

    /* ---------- 执行与结果 ---------- */
    private static final Map<String, String> REASON = new HashMap<>();
    static {
        String[] r = { "INVALID_ARGUMENT", "参数不对", "UNSUPPORTED", "这辆车不支持", "BACKEND_UNAVAILABLE", "车机服务还没准备好", "DEVICE_ERROR", "车机拒绝了",
            "TIMEOUT", "车机没回应（可能已经做了）", "BUSY", "车机正忙", "RESOURCE_LIMIT", "车机正忙", "PERMISSION_DENIED", "还没授权执行操作，去宿主的授权页允许「执行操作」", "LIMIT_REACHED", "已经到头了",
            "SESSION_CLOSED", "和宿主断开了（可能已经做了）" };
        for (int i = 0; i + 1 < r.length; i += 2) REASON.put(r[i], r[i + 1]);
    }
    private static final class Log { final long at; final String text; Log(long at, String text) { this.at = at; this.text = text; } }
    private final ArrayList<Log> log = new ArrayList<>();
    void note(Op op, String result) {
        log.add(new Log(System.currentTimeMillis(), label(op) + "：" + result));
        if (log.size() > 6) log.remove(0);
    }
    /** 最近 5 分钟的操作结果（放进下一句话前面的括号里） */
    String recent() {
        long now = System.currentTimeMillis();
        ArrayList<String> l = new ArrayList<>();
        for (Log x : log) if (now - x.at < 300000) l.add(x.text);
        while (l.size() > 3) l.remove(0);
        return android.text.TextUtils.join("；", l);
    }
    void run(final Op op, final Done done) {
        Done after = (r, code, reason, raw) -> {
            if (code == null) { note(op, "做好了"); done.done(r, null, null, null); return; }
            String c = code.isEmpty() ? "DEVICE_ERROR" : code, why = REASON.containsKey(c) ? REASON.get(c) : raw != null && !raw.isEmpty() ? raw : "没成功";
            note(op, "没做成（" + why + "）");
            done.done(null, c, why, raw);
        };
        if (op.tool != null) { callTool(op.tool, op.obj, after); return; }
        if (!can(op.m)) { done.done(null, "UNSUPPORTED", REASON.get("UNSUPPORTED"), null); return; }
        JSONObject params = new JSONObject();
        List<String> names = paramNames(op.m);
        try { for (int i = 0; i < op.a.size() && i < names.size(); i++) params.put(names.get(i), op.a.get(i)); } catch (Exception ignored) { }
        request(op.m, params, after);
    }
    private void request(String m, JSONObject params, final Done done) {
        Host h = app.host;
        if (h == null) { done.done(null, "SESSION_CLOSED", null, null); return; }
        h.request(m, params, (r, code, msg) -> done.done(r == null ? new JSONObject() : r, code, null, msg));
    }

    /** 能操作的几大类（给模型一个总览，车主问"你能干嘛"时好说） */
    private static final Object[][] GROUPS = {
        { "^media\\.", "放歌、暂停、切歌、悬浮歌词" }, { "^vehicle\\.ac\\.", "空调（开关、温度、风量、除雾、内外循环）" }, { "^vehicle\\.seat\\.", "座椅加热和通风" },
        { "^vehicle\\.body\\.(set|toggle)Windows|Sunroof|Sunshade", "车窗、天窗、遮阳帘" }, { "Tailgate|unlockDoors", "尾门、解锁车门" },
        { "^vehicle\\.light\\.|^display\\.setAmbient", "车灯和氛围灯" }, { "^vehicle\\.energy\\.", "无线充电、保电、能量回收、驾驶和能源模式、声浪" },
        { "^display\\.setRotation|^instrument\\.", "屏幕方向、仪表" }, { "^system\\.|^windows\\.", "返回、回主页、息屏、截屏、画中画" }, { "^apps\\.", "打开应用" }, { "^ui\\.", "弹提示、车机播报" }
    };
    List<String> groups() {
        ArrayList<String> ms = new ArrayList<>();
        Map<String, JSONObject> host = hostTools();
        if (host != null) { for (String n : host.keySet()) if (!QUERIES.contains(n)) ms.add(methodOf(n)); }
        else for (Def d : LIST) if (can(d.m)) ms.add(d.m);
        ArrayList<String> out = new ArrayList<>();
        for (Object[] g : GROUPS) {
            Pattern p = Pattern.compile((String) g[0]);
            for (String m : ms) if (p.matcher(m).find()) { out.add((String) g[1]); break; }
        }
        return out;
    }

    /** 系统提示里的操作表（宿主有的；不随时间变） */
    String prompt() {
        ArrayList<String> lines = new ArrayList<>();
        for (Def d : LIST) {
            if (!can(d.m)) continue;
            StringBuilder args = new StringBuilder();
            for (Spec s : d.p) {
                if (args.length() > 0) args.append(' ');
                if (s.t == 'b') args.append("true|false");
                else if (s.t == 'i') {
                    if (s.v != null) { for (int i = 0; i < s.v.length; i++) args.append(i > 0 ? "|" : "").append(s.v[i]); }
                    else args.append(str(s.min)).append('~').append(str(s.max));
                } else if (s.t == 'e') args.append(android.text.TextUtils.join("|", s.e));
                else if (s.t == 'p') args.append("包名");
                else args.append("文字");
            }
            lines.add("〔" + d.m + (args.length() > 0 ? " " + args : "") + "〕" + d.name + (!d.hint.isEmpty() ? "（" + d.hint + "）" : "") + (d.lv == 2 ? "【开车时不能用】" : d.lv == 1 ? "【要车主确认】" : ""));
        }
        if (lines.isEmpty()) return "";
        String list = can("apps.launch") && !apps.isEmpty() ? appList() : "";
        return android.text.TextUtils.join("\n", lines) + (can("apps.launch") ? "\n能打开的应用：" + (list.isEmpty() ? "暂时没有" : list) : "");
    }
    private String appList() {
        ArrayList<String> l = new ArrayList<>();
        for (String p : apps) l.add(appName(p) + " " + p);
        return android.text.TextUtils.join("、", l);
    }

    /* ---------- 工具调用：tools.json（同宿主给网页卡片的工具清单）按能力目录筛过 ---------- */
    static final List<String> QUERIES = Arrays.asList("uiplus_vehicle_get", "uiplus_media_get", "uiplus_media_getLyrics"); // 给模型的查询（宿主信息、能力目录用不着）
    private static final Map<String, String> QUERY_M = new HashMap<>();
    static { QUERY_M.put("uiplus_vehicle_get", "vehicle.get"); QUERY_M.put("uiplus_media_get", "media.get"); QUERY_M.put("uiplus_media_getLyrics", "media.getLyrics"); }
    // 系统数据（含 GPS 位置）、系统属性（可能有设备标识）是查询不是操作，也不交给外面的模型；发 Android 广播以宿主身份发出、能触发别的应用，也不交给模型
    private static final Pattern EXCLUDED = Pattern.compile("^uiplus_(getInfo|getCapabilities|actions_runSequence|system_get|system_getProperty|system_sendBroadcast)$");
    // 按参数决定的分级，写进工具说明，模型好知道什么时候会被拦
    private static final Map<String, String> EXC = new HashMap<>();
    static {
        EXC.put("vehicle.body.setWindowsMode", "stop（停下）不用确认"); EXC.put("vehicle.body.operateSunroof", "stop（停下）不用确认"); EXC.put("vehicle.body.operateSunshade", "stop（停下）不用确认");
        EXC.put("vehicle.light.setLowBeam", "开灯不用确认；夜里开车时不能关"); EXC.put("instrument.setBacklight", "退出休息模式开车时也可以");
    }
    private static JSONArray allTools;
    private static Map<String, String> nameToMethod;
    private static Map<String, List<String>> methodParams;
    /** tools.json / actions.json（只读，两个页面共用一份） */
    private static synchronized void loadCatalog(android.content.Context c) {
        if (allTools != null) return;
        JSONArray t = new JSONArray();
        Map<String, String> nm = new HashMap<>(QUERY_M);
        Map<String, List<String>> mp = new HashMap<>();
        try {
            Object a = Json.parse(Io.text(c, "actions.json"));
            if (a instanceof JSONArray) for (int i = 0; i < ((JSONArray) a).length(); i++) {
                JSONObject x = ((JSONArray) a).optJSONObject(i);
                if (x == null) continue;
                String m = x.optString("m");
                nm.put("uiplus_" + m.replace('.', '_'), m);
                ArrayList<String> names = new ArrayList<>();
                JSONArray p = x.optJSONArray("p");
                for (int j = 0; p != null && j < p.length(); j++) { JSONArray q = p.optJSONArray(j); if (q != null) names.add(q.optString(0)); }
                mp.put(m, names);
            }
            Object tl = Json.parse(Io.text(c, "tools.json"));
            if (tl instanceof JSONArray) t = (JSONArray) tl;
        } catch (Exception e) { android.util.Log.w(Uiplus.TAG, "读不出工具清单", e); }
        nameToMethod = nm;
        methodParams = mp;
        allTools = t;
    }
    static String methodOf(String name) {
        String m = nameToMethod != null ? nameToMethod.get(name) : null;
        return m != null ? m : name.replaceFirst("^uiplus_", "").replace('_', '.');
    }
    private List<String> paramNames(String m) {
        loadCatalog(app.ctx);
        List<String> l = methodParams.get(m);
        return l != null ? l : new ArrayList<String>();
    }
    private Map<String, JSONObject> host; // 宿主有的工具：名字 → 定义（筛过）
    private Object hostKey;
    /** 宿主有的工具（能力目录还没拿到时 null）：能力目录变了重新筛 */
    Map<String, JSONObject> hostTools() {
        Car c = app.car;
        if (c == null || c.caps == null) return null;
        Object key = c.caps;
        if (host != null && hostKey == key && hostSize == c.actions.size()) return host;
        loadCatalog(app.ctx);
        LinkedHashMap<String, JSONObject> h = new LinkedHashMap<>();
        for (int i = 0; i < allTools.length(); i++) {
            JSONObject t = allTools.optJSONObject(i), fn = t == null ? null : t.optJSONObject("function");
            String n = fn == null ? null : fn.optString("name", null);
            if (n == null || !(fn.opt("parameters") instanceof JSONObject)) continue;
            if (QUERIES.contains(n)) h.put(n, n.equals("uiplus_vehicle_get") ? vehicleGet(fn, c.caps) : fn);
            else if (n.startsWith("uiplus_") && !EXCLUDED.matcher(n).find() && hostHas(methodOf(n))) h.put(n, fn);
        }
        host = h.isEmpty() ? null : h;
        hostKey = key;
        hostSize = c.actions.size();
        return host;
    }
    private int hostSize;
    void refresh() {
        host = null; hostKey = null;
        if (allTools == null && app.car != null && app.car.caps != null) { // 工具清单在后台先读好（第一次用时不卡界面）
            final android.content.Context c = app.ctx.getApplicationContext();
            new Thread(() -> loadCatalog(c), "pet-tools").start();
        }
    }
    /** 车况查询的字段只留宿主有的 */
    private static JSONObject vehicleGet(JSONObject fn, Map<String, String> caps) {
        try {
            JSONObject g = (JSONObject) Json.parse(Json.stringify(fn));
            JSONObject items = g.getJSONObject("parameters").getJSONObject("properties").getJSONObject("keys").getJSONObject("items");
            JSONArray en = items.optJSONArray("enum"), any = items.optJSONArray("anyOf"), e2 = new JSONArray(), a2 = new JSONArray();
            for (int i = 0; en != null && i < en.length(); i++) if (caps.containsKey(en.optString(i))) e2.put(en.optString(i));
            for (int i = 0; any != null && i < any.length(); i++) {
                JSONObject x = any.optJSONObject(i);
                JSONArray xe = x == null ? null : x.optJSONArray("enum");
                if (xe != null && xe.length() > 0 && caps.containsKey(xe.optString(0))) a2.put(x);
            }
            if (e2.length() == 0) return fn;
            items.put("enum", e2);
            if (any != null) items.put("anyOf", a2);
            return g;
        } catch (Exception e) { return fn; }
    }
    /** 交给模型的工具（Chat Completions 格式；能力目录还没拿到时 null）。说明前面加上安全提示；打开应用加上见过的播放器 */
    JSONArray tools() {
        Map<String, JSONObject> h = hostTools();
        if (h == null) return null;
        JSONArray out = new JSONArray();
        for (Map.Entry<String, JSONObject> e : h.entrySet()) {
            String n = e.getKey(), m = methodOf(n);
            JSONObject fn = e.getValue();
            Def d = BY.get(m);
            int lv = QUERIES.contains(n) ? 0 : d != null ? d.lv : 2;
            String tip = "", ex = EXC.get(m);
            if (lv == 1) tip = "【要车主确认" + (ex != null ? "；" + ex : "") + "】";
            else if (lv == 2) tip = "【开车时不能用，也要车主确认" + (ex != null ? "；" + ex : "") + "】";
            else if (ex != null) tip = "【" + ex + "】";
            JSONObject g = new JSONObject();
            try {
                for (java.util.Iterator<String> it = fn.keys(); it.hasNext(); ) { String k = it.next(); g.put(k, fn.opt(k)); }
                String desc = fn.optString("description", "");
                String dd = tip + (d != null && desc.isEmpty() ? d.name : desc);
                if (m.equals("apps.launch")) dd += " 只能打开这些：" + (apps.isEmpty() ? "暂时没有（还没见过播放器）" : appList());
                g.put("description", dd);
                out.put(new JSONObject().put("type", "function").put("function", g));
            } catch (Exception ignored) { }
        }
        return out;
    }
    boolean query(String name) { Map<String, JSONObject> h = hostTools(); return QUERIES.contains(name) && h != null && h.containsKey(name); }
    private JSONObject props(String name) {
        Map<String, JSONObject> h = hostTools();
        JSONObject fn = h == null ? null : h.get(name), p = fn == null ? null : fn.optJSONObject("parameters");
        return p == null ? null : p.optJSONObject("properties");
    }
    /** 去掉工具定义里没有的字段（有的模型 / 中转站会自己加，比如给无参工具加个 reason；宿主见到多余字段整个拒绝）。值不动，照样校验 */
    JSONObject clean(String name, JSONObject obj) {
        JSONObject p = props(name);
        if (p == null) return obj;
        JSONObject o = new JSONObject();
        try { for (java.util.Iterator<String> it = obj.keys(); it.hasNext(); ) { String k = it.next(); if (p.has(k)) o.put(k, obj.opt(k)); } } catch (Exception ignored) { }
        return o;
    }
    /** 车况查询能填的字段名（工具定义里的枚举）；查询报参数不对时连同错误交给模型，让它改了再查 */
    List<String> vehicleKeys() {
        ArrayList<String> out = new ArrayList<>();
        JSONObject p = props("uiplus_vehicle_get"), k = p == null ? null : p.optJSONObject("keys"), items = k == null ? null : k.optJSONObject("items");
        JSONArray en = items == null ? null : items.optJSONArray("enum");
        for (int i = 0; en != null && i < en.length(); i++) out.add(en.optString(i));
        return out;
    }
    /** 模型的一次操作调用 → Op（a 是按参数顺序排的值，给分级、显示用）；不能交给宿主的 op.err 是 { code, message } */
    Op toolOp(String name, JSONObject obj) {
        Map<String, JSONObject> h = hostTools();
        JSONObject fn = h == null ? null : h.get(name);
        String m = methodOf(name);
        Op op = new Op();
        op.m = m;
        if (fn == null || QUERIES.contains(name)) { op.err = err("UNSUPPORTED", "没有这个工具"); return op; }
        if (obj == null) { op.err = err("INVALID_ARGUMENT", "参数要是一个对象"); return op; }
        obj = clean(name, obj);
        JSONObject pp = fn.optJSONObject("parameters"), props = pp == null ? null : pp.optJSONObject("properties");
        ArrayList<String> keys = new ArrayList<>();
        if (props != null) for (java.util.Iterator<String> it = props.keys(); it.hasNext(); ) keys.add(it.next());
        for (String k : keys) op.a.add(obj.has(k) ? obj.opt(k) : null);
        Def d = BY.get(m);
        if (d != null && d.p.length == op.a.size()) for (int i = 0; i < op.a.size(); i++) if (!ok(d.p[i], op.a.get(i))) {
            op.err = err("INVALID_ARGUMENT", d.p[i].t == 'p' ? "只能打开见过的播放器" : "参数 " + keys.get(i) + " 不对：" + want(props.optJSONObject(keys.get(i))) + "改好了再调用一次");
            return op;
        }
        String desc = fn.optString("description", "");
        if (desc.isEmpty()) desc = m;
        desc = desc.split("[：:；;，,。（(]")[0];
        op.name = d != null ? d.name : desc.length() > 12 ? desc.substring(0, 12) : desc;
        op.tool = name;
        op.obj = obj;
        return op;
    }
    static JSONObject err(String code, String msg) {
        JSONObject o = new JSONObject();
        try { o.put("code", code).put("message", msg); } catch (Exception ignored) { }
        return o;
    }
    /** 参数该是什么样（写给模型看，参数写错时让它改） */
    private static String want(JSONObject sp) {
        if (sp == null) return "";
        JSONArray en = sp.optJSONArray("enum");
        if (en != null) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < en.length(); i++) { if (i > 0) b.append(" / "); b.append(Json.stringify(en.opt(i))); }
            return "只能是 " + b + "（照写，数字不带引号）。";
        }
        String t = sp.optString("type");
        if (t.equals("integer")) return "要是整数（不带引号）" + (sp.has("minimum") ? "，" + str(sp.opt("minimum")) + "～" + str(sp.opt("maximum")) : "") + "。";
        if (t.equals("number")) return "要是数字（不带引号）。";
        if (t.equals("boolean")) return "要是 true 或 false（不带引号）。";
        return "";
    }
    /** 交给宿主执行：先按工具定义严格校验参数（同宿主的 callFunction），再按方法名发请求；查询返回原始结果，操作返回回执 */
    void callTool(String name, JSONObject args, Done done) {
        Map<String, JSONObject> h = hostTools();
        JSONObject fn = h == null ? null : h.get(name);
        if (fn == null) { done.done(null, "UNSUPPORTED", null, "没有这个工具"); return; }
        String bad = check(args, fn.optJSONObject("parameters"), "");
        if (bad != null) { done.done(null, "INVALID_ARGUMENT", null, bad); return; }
        request(methodOf(name), args, done);
    }

    /* ---------- 参数校验（JSON Schema 里工具定义用到的那些：type、enum、范围、长度、pattern、数组、对象、anyOf） ---------- */
    private static String check(Object v, JSONObject s, String path) {
        if (s == null) return null;
        String at = path.isEmpty() ? "参数" : "参数 " + path;
        JSONArray any = s.optJSONArray("anyOf");
        if (any != null) {
            boolean one = false;
            for (int i = 0; i < any.length() && !one; i++) one = check(v, any.optJSONObject(i), path) == null;
            if (!one) return at + " 不合要求：" + brief(v);
        }
        Object type = s.opt("type");
        if (type instanceof String && !typeOk(v, (String) type)) return at + " 类型不对，要是 " + type;
        if (type instanceof JSONArray) {
            boolean one = false;
            for (int i = 0; i < ((JSONArray) type).length() && !one; i++) one = typeOk(v, ((JSONArray) type).optString(i));
            if (!one) return at + " 类型不对";
        }
        JSONArray en = s.optJSONArray("enum");
        if (en != null) {
            boolean one = false;
            for (int i = 0; i < en.length() && !one; i++) one = same(v, en.opt(i));
            if (!one) return at + " 只能是列出的值：" + brief(v);
        }
        if (s.has("const") && !same(v, s.opt("const"))) return at + " 不合要求：" + brief(v);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (s.has("minimum") && d < s.optDouble("minimum")) return at + " 太小：" + brief(v);
            if (s.has("maximum") && d > s.optDouble("maximum")) return at + " 太大：" + brief(v);
        }
        if (v instanceof String) {
            String t = (String) v;
            if (s.has("minLength") && t.length() < s.optInt("minLength")) return at + " 太短";
            if (s.has("maxLength") && t.length() > s.optInt("maxLength")) return at + " 太长";
            if (s.has("pattern")) { try { if (!Pattern.compile(s.optString("pattern")).matcher(t).find()) return at + " 格式不对：" + brief(v); } catch (RuntimeException ignored) { } }
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            if (s.has("minItems") && a.length() < s.optInt("minItems")) return at + " 至少要 " + s.optInt("minItems") + " 项";
            if (s.has("maxItems") && a.length() > s.optInt("maxItems")) return at + " 最多 " + s.optInt("maxItems") + " 项";
            if (s.optBoolean("uniqueItems")) for (int i = 0; i < a.length(); i++) for (int j = i + 1; j < a.length(); j++) if (same(a.opt(i), a.opt(j))) return at + " 有重复的项：" + brief(a.opt(i));
            JSONObject it = s.optJSONObject("items");
            if (it != null) for (int i = 0; i < a.length(); i++) { String e = check(a.opt(i), it, path + "[" + i + "]"); if (e != null) return e; }
        }
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v, props = s.optJSONObject("properties");
            JSONArray req = s.optJSONArray("required");
            for (int i = 0; req != null && i < req.length(); i++) if (!o.has(req.optString(i))) return "缺参数 " + (path.isEmpty() ? "" : path + ".") + req.optString(i);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next(), p = path.isEmpty() ? k : path + "." + k;
                JSONObject ps = props == null ? null : props.optJSONObject(k);
                if (ps == null) { if (Boolean.FALSE.equals(s.opt("additionalProperties"))) return "多余参数 " + p; continue; }
                String e = check(o.opt(k), ps, p);
                if (e != null) return e;
            }
        }
        return null;
    }
    private static boolean typeOk(Object v, String t) {
        switch (t) {
            case "string": return v instanceof String;
            case "integer": return integral(v);
            case "number": return v instanceof Number;
            case "boolean": return v instanceof Boolean;
            case "object": return v instanceof JSONObject;
            case "array": return v instanceof JSONArray;
            case "null": return v == null || v == JSONObject.NULL;
        }
        return true;
    }
    private static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        return a != null && a.equals(b);
    }
    private static String brief(Object v) { String s = Json.stringify(v); if (s == null) s = "undefined"; return s.length() > 40 ? s.substring(0, 40) + "…" : s; }
}
