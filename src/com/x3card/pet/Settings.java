package com.x3card.pet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** 设置（同网页版 main.js 的 settings：存在 pet-settings，读的时候按白名单校验，坏了回到默认值） */
final class Settings {
    /** 倒数日 / 生日 */
    static final class Day {
        String name = "";
        boolean lunar, repeat;
        int y, m, d;
        Day copy() { Day o = new Day(); o.name = name; o.lunar = lunar; o.repeat = repeat; o.y = y; o.m = m; o.d = d; return o; }
        JSONObject json(boolean full) {
            JSONObject o = new JSONObject();
            try {
                if (full) o.put("name", name).put("repeat", repeat).put("y", y);
                o.put("lunar", lunar).put("m", m).put("d", d);
            } catch (Exception ignored) { }
            return o;
        }
        static Day of(JSONObject o) {
            if (o == null) return null;
            int m = o.optInt("m"), d = o.optInt("d");
            if (m < 1 || m > 12 || d < 1 || d > 31) return null;
            Day x = new Day();
            x.name = o.optString("name", "");
            x.lunar = o.optBoolean("lunar");
            x.repeat = o.optBoolean("repeat");
            x.y = o.optInt("y", java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
            x.m = m; x.d = d;
            return x;
        }
    }

    /**
     * 显示内容（设置 → 显示内容）：[id, 名字, 分组, 沿用的旧设置项, 要车机接口]。
     * 关掉的相关部分要停下来（shown(id)）；胶囊的 id 是类名去掉 "pill-"
     */
    static final String[][] SHOW = {
        { "time", "时间", "时钟", "", "" }, { "date", "日期", "时钟", "", "" }, { "lunar", "农历", "时钟", "", "" },
        { "lyric", "歌词", "时钟", "lyric", "1" }, { "lyricNext", "下一句歌词", "时钟", "", "1" }, { "cover", "歌曲封面", "时钟", "cover", "1" },
        { "cd", "倒数日", "胶囊", "", "" }, { "bd", "生日", "胶囊", "", "" }, { "tm", "陪伴时长", "胶囊", "timer", "" },
        { "warn", "车况告警", "胶囊", "", "1" }, { "ev", "电量", "胶囊", "", "1" }, { "fuel", "油量", "胶囊", "", "1" }, { "temp", "室外温度", "胶囊", "", "1" },
        { "music", "歌名", "胶囊", "", "1" }, { "mcBtn", "音乐按钮", "胶囊", "", "1" },
        { "sun", "太阳和月亮", "场景", "", "" }, { "cloud", "云", "场景", "", "" }, { "stars", "星空和流星", "场景", "", "" }, { "fest", "节日特效", "场景", "fest", "" },
        { "pg-car", "车况页", "页面", "", "1" }, { "pg-calendar", "日历页", "页面", "", "" }, { "pg-countdown", "倒数日页", "页面", "", "" },
        { "dots", "分页圆点", "页面", "", "" }, { "nav", "底部导航", "页面", "", "" }, { "chat", "聊天键", "页面", "", "" }, { "fs", "全屏键", "页面", "", "1" }
    };

    String size = "m", scene = "auto", clock = "digital";
    String fest = "on", lyric = "on", cover = "on", intro = "on", pets = "on", bubble = "on", events = "on", rest = "on", timer = "on", fsBar = "off";
    final ArrayList<String> hide = new ArrayList<>();
    Day countdown, birthday;
    double best, loveN, loveE;

    Settings() { load(); }

    void load() {
        JSONObject s;
        try { String t = Store.get("pet-settings"); s = t == null ? new JSONObject() : new JSONObject(t); } catch (Exception e) { s = new JSONObject(); }
        size = pick(s, "size", "m", "s", "m", "l");
        scene = pick(s, "scene", "auto", "auto", "day", "dusk", "night");
        clock = pick(s, "clock", "digital", "digital", "dial");
        fest = onOff(s, "fest", "on"); lyric = onOff(s, "lyric", "on"); cover = onOff(s, "cover", "on"); intro = onOff(s, "intro", "on");
        pets = onOff(s, "pets", "on"); bubble = onOff(s, "bubble", "on"); events = onOff(s, "events", "on"); rest = onOff(s, "rest", "on");
        timer = onOff(s, "timer", "on"); fsBar = onOff(s, "fsBar", "off");
        hide.clear();
        JSONArray h = s.optJSONArray("hide");
        for (int i = 0; h != null && i < h.length(); i++) {
            String id = h.optString(i, null);
            if (id != null && plainShow(id) && !hide.contains(id)) hide.add(id);
        }
        countdown = Day.of(s.optJSONObject("countdown"));
        birthday = Day.of(s.optJSONObject("birthday"));
        best = num(s.opt("best"));
        JSONObject love = s.optJSONObject("love");
        loveN = love == null ? 0 : num(love.opt("Neuron"));
        loveE = love == null ? 0 : num(love.opt("Eviling"));
    }
    private static double num(Object o) {
        if (o instanceof Number) { double v = ((Number) o).doubleValue(); return Double.isNaN(v) || Double.isInfinite(v) ? 0 : v; }
        if (o instanceof String) try { return Double.parseDouble((String) o); } catch (Exception ignored) { }
        return 0;
    }
    private static String pick(JSONObject s, String k, String def, String... ok) {
        String v = s.optString(k, def);
        for (String o : ok) if (o.equals(v)) return v;
        return def;
    }
    private static String onOff(JSONObject s, String k, String def) { String v = s.optString(k, def); return "on".equals(v) || "off".equals(v) ? v : def; }
    /** 不沿用旧设置项的那几项（存在 hide 里） */
    private static boolean plainShow(String id) { for (String[] r : SHOW) if (r[0].equals(id)) return r[3].isEmpty(); return false; }

    JSONObject json() {
        JSONObject o = new JSONObject();
        try {
            o.put("size", size).put("scene", scene).put("clock", clock).put("fest", fest).put("lyric", lyric).put("cover", cover).put("intro", intro)
                .put("pets", pets).put("bubble", bubble).put("events", events).put("rest", rest).put("timer", timer).put("fsBar", fsBar);
            o.put("hide", new JSONArray(hide));
            o.put("countdown", countdown == null ? JSONObject.NULL : countdown.json(true));
            o.put("birthday", birthday == null ? JSONObject.NULL : birthday.json(false));
            o.put("best", best);
            o.put("love", new JSONObject().put("Neuron", loveN).put("Eviling", loveE));
        } catch (Exception ignored) { }
        return o;
    }

    private Runnable saveLater;
    private android.os.Handler h;
    /** 存（0.3 秒内连着改只存一次） */
    void save() {
        if (h == null) { h = new android.os.Handler(android.os.Looper.getMainLooper()); saveLater = () -> Store.set("pet-settings", json().toString()); }
        h.removeCallbacks(saveLater);
        h.postDelayed(saveLater, 300);
    }
    void saveNow() { if (h != null) h.removeCallbacks(saveLater); Store.set("pet-settings", json().toString()); }

    double love(String pack) { return "Eviling".equals(pack) ? loveE : loveN; }
    void addLove(String pack, double n) { if ("Eviling".equals(pack)) loveE += n; else loveN += n; }

    /** 显示内容里这一项开着没有 */
    boolean shown(String id) {
        for (String[] r : SHOW) {
            if (!r[0].equals(id)) continue;
            if (!r[3].isEmpty()) return !"off".equals(get(r[3]));
            return !hide.contains(id);
        }
        return true;
    }
    /** 改显示内容里的一项：返回改没改 */
    boolean setShown(String id, boolean on) {
        String[] row = null;
        for (String[] r : SHOW) if (r[0].equals(id)) row = r;
        if (row == null || shown(id) == on) return false;
        if (!row[3].isEmpty()) set(row[3], on ? "on" : "off");
        else if (on) hide.remove(id);
        else hide.add(id);
        return true;
    }

    String get(String k) {
        switch (k) {
            case "size": return size; case "scene": return scene; case "clock": return clock; case "fest": return fest; case "lyric": return lyric;
            case "cover": return cover; case "intro": return intro; case "pets": return pets; case "bubble": return bubble; case "events": return events;
            case "rest": return rest; case "timer": return timer; case "fsBar": return fsBar;
        }
        return null;
    }
    void set(String k, String v) {
        switch (k) {
            case "size": size = v; break; case "scene": scene = v; break; case "clock": clock = v; break; case "fest": fest = v; break; case "lyric": lyric = v; break;
            case "cover": cover = v; break; case "intro": intro = v; break; case "pets": pets = v; break; case "bubble": bubble = v; break; case "events": events = v; break;
            case "rest": rest = v; break; case "timer": timer = v; break; case "fsBar": fsBar = v; break;
        }
    }
}
