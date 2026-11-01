package com.x3card.pet;

import android.content.Context;
import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 场景形状（assets 的 scene.json，网页版 index.html 里 _tools/gen/landscape.mjs 生成的 SVG）：四层远山、三朵云、静态星空、草叶。
 * 坐标都是各自 viewBox 里的，画的时候按网页版的 preserveAspectRatio 换算。读一次，全屏页、小窗口共用
 */
final class SceneData {
    /** 远山的一种形状：受光面 rf / 背光面 rs / 小屋灯 lt / 山脚雾 mist */
    static final class Shape { String cls; Path path; RectF rect; float rx; }
    static final class Ridge { float vbW, vbH; Shape[] items; }
    static final class Cloud { float vbW, vbH; Path body, shade; }

    final Ridge[] ridges = new Ridge[4];
    final Cloud[] clouds = new Cloud[3];
    float sfW, sfH;
    /** 静态星空：每颗 [x, y, 半径, 透明度]，按透明度分了 6 档（同一档一次画完） */
    float[][] starPts = new float[6][];
    float[] starR = new float[6], starA = new float[6];
    float grassW, grassH;
    Path grass;

    private static SceneData one;
    static synchronized SceneData get(Context c) {
        if (one == null) one = new SceneData(c);
        return one;
    }

    private SceneData(Context c) {
        JSONObject j;
        try { j = new JSONObject(Io.text(c, "scene.json")); } catch (Exception e) { throw new IllegalStateException("scene.json 读不出来", e); }
        JSONArray rs = j.optJSONArray("ridges");
        for (int i = 0; i < 4; i++) {
            JSONObject r = rs.optJSONObject(i);
            Ridge R = new Ridge();
            JSONArray vb = r.optJSONArray("vb");
            R.vbW = (float) vb.optDouble(2); R.vbH = (float) vb.optDouble(3);
            JSONArray items = r.optJSONArray("items");
            R.items = new Shape[items.length()];
            for (int k = 0; k < items.length(); k++) {
                JSONObject it = items.optJSONObject(k);
                Shape s = new Shape();
                s.cls = it.optString("c");
                if ("p".equals(it.optString("t"))) {
                    JSONArray p = it.optJSONArray("p");
                    Path path = new Path();
                    for (int q = 0; q + 1 < p.length(); q += 2) {
                        float x = (float) p.optDouble(q), y = (float) p.optDouble(q + 1);
                        if (q == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                    path.close();
                    s.path = path;
                } else {
                    float x = (float) it.optDouble("x"), y = (float) it.optDouble("y");
                    s.rect = new RectF(x, y, x + (float) it.optDouble("w"), y + (float) it.optDouble("h"));
                    s.rx = (float) it.optDouble("rx", 0);
                }
                R.items[k] = s;
            }
            ridges[i] = R;
        }
        JSONArray cs = j.optJSONArray("clouds");
        for (int i = 0; i < 3; i++) {
            JSONObject o = cs.optJSONObject(i);
            Cloud C = new Cloud();
            JSONArray vb = o.optJSONArray("vb");
            C.vbW = (float) vb.optDouble(2); C.vbH = (float) vb.optDouble(3);
            C.body = shapes(o.optJSONArray("body"));
            C.shade = shapes(o.optJSONArray("shade"));
            clouds[i] = C;
        }
        JSONObject sf = j.optJSONObject("starfield");
        JSONArray vb = sf.optJSONArray("vb");
        sfW = (float) vb.optDouble(2); sfH = (float) vb.optDouble(3);
        JSONArray dots = sf.optJSONArray("dots");
        // 按透明度分档：同一档的星星大小也取平均（都是 0.7～1.7 的小点，看不出差别），一次 drawPoints 画完
        int[] n = new int[6];
        float[] sumR = new float[6], sumA = new float[6];
        int[] tier = new int[dots.length()];
        for (int i = 0; i < dots.length(); i++) {
            JSONArray d = dots.optJSONArray(i);
            float a = (float) d.optDouble(3), r = (float) d.optDouble(2);
            int t = Math.min(5, Math.max(0, (int) ((a - 0.3f) / 0.7f * 6)));
            tier[i] = t; n[t]++; sumR[t] += r; sumA[t] += a;
        }
        int[] at = new int[6];
        for (int t = 0; t < 6; t++) { starPts[t] = new float[n[t] * 2]; starR[t] = n[t] > 0 ? sumR[t] / n[t] : 1; starA[t] = n[t] > 0 ? sumA[t] / n[t] : 1; }
        for (int i = 0; i < dots.length(); i++) {
            JSONArray d = dots.optJSONArray(i);
            int t = tier[i];
            starPts[t][at[t]++] = (float) d.optDouble(0);
            starPts[t][at[t]++] = (float) d.optDouble(1);
        }
        JSONObject g = j.optJSONObject("grass");
        JSONArray gvb = g.optJSONArray("vb");
        grassW = (float) gvb.optDouble(2); grassH = (float) gvb.optDouble(3);
        grass = SvgPath.parse(g.optString("d"));
    }

    private static Path shapes(JSONArray a) {
        Path p = new Path();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if ("c".equals(o.optString("t"))) p.addCircle((float) o.optDouble("x"), (float) o.optDouble("y"), (float) o.optDouble("r"), Path.Direction.CW);
            else {
                float x = (float) o.optDouble("x"), y = (float) o.optDouble("y"), rx = (float) o.optDouble("rx", 0);
                p.addRoundRect(new RectF(x, y, x + (float) o.optDouble("w"), y + (float) o.optDouble("h")), rx, rx, Path.Direction.CW);
            }
        }
        return p;
    }
}
