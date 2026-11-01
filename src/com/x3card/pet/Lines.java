package com.x3card.pet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * 台词库（assets 的 lines.json，同网页版 lines.js）和"不重复地抽一句"（同 main.js 的 fresh）：
 * 每组像洗牌一样轮着抽，这一轮没说完不重复，新一轮第一句也不和上一句相同；说过的记成短哈希存在 pet-said。
 * 台词要和画面、钟点、季节对得上（FIT）：对不上的这次跳过，留在袋里。
 */
final class Lines {
    /** 当前画面（day / dusk / night），由场景交进来 */
    interface Ctx { String scene(); }

    final JSONObject lines, mc;
    final JSONArray dialogues;
    private final Ctx ctx;
    private final Random rnd = new Random();
    private final Handler h = new Handler(Looper.getMainLooper());
    private JSONObject said;
    private final Runnable saveSaid = () -> Store.set("pet-said", said.toString());

    Lines(Context c, Ctx ctx) {
        this.ctx = ctx;
        JSONObject j;
        try { j = new JSONObject(Io.text(c, "lines.json")); } catch (Exception e) { j = new JSONObject(); }
        lines = j.optJSONObject("lines") != null ? j.optJSONObject("lines") : new JSONObject();
        mc = j.optJSONObject("mc") != null ? j.optJSONObject("mc") : new JSONObject();
        dialogues = j.optJSONArray("dialogues") != null ? j.optJSONArray("dialogues") : new JSONArray();
        try { String s = Store.get("pet-said"); said = s == null ? new JSONObject() : new JSONObject(s); } catch (Exception e) { said = new JSONObject(); }
    }

    /** 这只宠物这组台词（没有就用 Neuro 的） */
    JSONArray group(String pack, String key) {
        JSONObject p = lines.optJSONObject(pack);
        JSONArray a = p == null ? null : p.optJSONArray(key);
        if (a == null) { JSONObject n = lines.optJSONObject("Neuron"); a = n == null ? null : n.optJSONArray(key); }
        return a;
    }
    boolean has(String pack, String key) { JSONObject p = lines.optJSONObject(pack); return p != null && p.optJSONArray(key) != null; }

    static String hashOf(String t) {
        int h = 5381;
        for (int i = 0; i < t.length(); i++) h = (h << 5) + h + t.charAt(i);
        return Long.toString(h & 0xffffffffL, 36);
    }
    static String textOf(Object x) {
        if (x instanceof String) return (String) x;
        if (x instanceof JSONArray) { // 双人剧本：整段一起看
            JSONArray a = (JSONArray) x;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length(); i++) { JSONArray l = a.optJSONArray(i); if (l != null) { if (b.length() > 0) b.append(' '); b.append(l.optString(1)); } }
            return b.toString();
        }
        return String.valueOf(x);
    }

    // ================= 和画面、钟点、季节对得上 =================
    private static final Pattern[] FIT = {
        Pattern.compile("太阳|阳光|日光|晒"), Pattern.compile("蓝天|大白天|天好亮"), Pattern.compile("晚霞|夕阳|落日|黄昏"),
        Pattern.compile("月亮|月光|星星|星空|银河|萤火虫|流星"), Pattern.compile("天黑"),
        Pattern.compile("早上|早晨|早安|一大早|上午|早饭|早餐"), Pattern.compile("中午|午饭|午觉|午休"), Pattern.compile("下午"), Pattern.compile("傍晚"),
        Pattern.compile("晚上|今晚|夜里|夜晚|夜空|晚安|晚饭"), Pattern.compile("深夜|夜深|半夜|凌晨|这么晚|熬夜|夜宵"),
        Pattern.compile("春天|樱花|春风|花瓣"), Pattern.compile("夏天|蝉"), Pattern.compile("秋天|落叶|秋风"), Pattern.compile("冬天|雪人|下雪|雪花|打雪仗|雪地")
    };
    private static boolean ok(int rule, String scene, int h, String season) {
        switch (rule) {
            case 0: return !"night".equals(scene);
            case 1: return "day".equals(scene);
            case 2: return "dusk".equals(scene);
            case 3: return "night".equals(scene);
            case 4: return !"day".equals(scene);
            case 5: return h >= 5 && h < 11;
            case 6: return h >= 11 && h < 14;
            case 7: return h >= 12 && h < 18;
            case 8: return h >= 16 && h < 20;
            case 9: return h >= 17 || h < 5;
            case 10: return h >= 22 || h < 5;
            case 11: return "spring".equals(season);
            case 12: return "summer".equals(season);
            case 13: return "autumn".equals(season);
            default: return "winter".equals(season);
        }
    }
    private final Map<String, int[]> fitMemo = new HashMap<>();
    private boolean fits(Object x, String scene, int h, String season) {
        String t = textOf(x);
        int[] rules = fitMemo.get(t);
        if (rules == null) {
            int n = 0;
            int[] tmp = new int[FIT.length];
            for (int i = 0; i < FIT.length; i++) if (FIT[i].matcher(t).find()) tmp[n++] = i;
            rules = java.util.Arrays.copyOf(tmp, n);
            fitMemo.put(t, rules);
        }
        for (int r : rules) if (!ok(r, scene, h, season)) return false;
        return true;
    }
    static String seasonOf(int m) { return m >= 3 && m <= 5 ? "spring" : m >= 6 && m <= 8 ? "summer" : m >= 9 && m <= 11 ? "autumn" : "winter"; }

    /** 从这袋里不重复地抽一个（字符串台词或双人剧本）；都对不上返回 null */
    Object fresh(String bag, JSONArray arr) {
        if (arr == null || arr.length() == 0) return null;
        Calendar d = Calendar.getInstance();
        String scene = ctx.scene(), season = seasonOf(d.get(Calendar.MONTH) + 1);
        int hr = d.get(Calendar.HOUR_OF_DAY);
        ArrayList<Object> ok = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) { Object x = arr.opt(i); if (x != null && fits(x, scene, hr, season)) ok.add(x); }
        if (ok.isEmpty()) return null;
        JSONArray usedA = said.optJSONArray(bag);
        ArrayList<String> used = new ArrayList<>();
        for (int i = 0; usedA != null && i < usedA.length(); i++) used.add(usedA.optString(i));
        ArrayList<Object> left = new ArrayList<>();
        for (Object x : ok) if (!used.contains(hashOf(key(x)))) left.add(x);
        if (left.isEmpty()) { // 合适的这一轮说完：重新开始，但别跟刚说的那句一样
            String last = used.isEmpty() ? null : used.get(used.size() - 1);
            used.clear();
            if (ok.size() > 1) { for (Object x : ok) if (!hashOf(key(x)).equals(last)) left.add(x); }
            else left.addAll(ok);
        }
        Object v = left.get(rnd.nextInt(left.size()));
        used.add(hashOf(key(v)));
        try { said.put(bag, new JSONArray(used)); } catch (Exception ignored) { }
        h.removeCallbacks(saveSaid);
        h.postDelayed(saveSaid, 1500);
        return v;
    }
    private static String key(Object x) { return x instanceof String ? (String) x : String.valueOf(x); }

    /** 这只宠物这组台词抽一句，{名字} 换成 vars 里的值 */
    String line(String pack, String key, Map<String, ?> vars) {
        String p = has(pack, key) ? pack : "Neuron";
        Object v = fresh(p + "." + key, group(p, key));
        String s = v instanceof String ? (String) v : "";
        if (vars != null) for (Map.Entry<String, ?> e : vars.entrySet()) s = s.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        return s;
    }
    void flush() { h.removeCallbacks(saveSaid); Store.set("pet-said", said.toString()); }
}
