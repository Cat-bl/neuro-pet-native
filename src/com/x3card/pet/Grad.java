package com.x3card.pet;

import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/** 渐变（同 CSS 的 linear-gradient / radial-gradient）和"模糊的圆"（同 box-shadow 的柔光：高斯模糊后的圆盘按半径的透明度曲线） */
final class Grad {
    private Grad() {}

    static Shader vertical(float y0, float y1, int[] colors, float[] stops) {
        return new LinearGradient(0, y0, 0, y1, colors, stops, Shader.TileMode.CLAMP);
    }
    static Shader radial(float cx, float cy, float r, int[] colors, float[] stops) {
        return new RadialGradient(cx, cy, Math.max(0.01f, r), colors, stops, Shader.TileMode.CLAMP);
    }

    /** 半径 R 的圆盘做 σ 的高斯模糊后，离圆心 d 处的透明度（0～1） */
    static float blurAt(float d, float R, float sigma) {
        if (sigma < 0.01f) return d <= R ? 1 : 0;
        return 0.5f * erfc((d - R) / (sigma * 1.41421356f));
    }
    private static float erfc(float x) { // Abramowitz-Stegun 7.1.26
        float z = Math.abs(x), t = 1 / (1 + 0.3275911f * z);
        float y = t * (0.254829592f + t * (-0.284496736f + t * (1.421413741f + t * (-1.453152027f + t * 1.061405429f)))) * (float) Math.exp(-z * z);
        return x >= 0 ? y : 2 - y;
    }
    /**
     * box-shadow: 0 0 blur spread color 画在半径 r 的圆外：圆盘半径 r + spread，高斯 σ = blur / 2。
     * 返回以 (cx, cy) 为圆心、半径到透明为止的径向渐变（外径用 outR 取回，给调用方画圆用）
     */
    static Shader glow(float cx, float cy, float r, float blur, float spread, int color, float[] outR) {
        float R = r + spread, s = blur / 2, end = R + 3 * s;
        int n = 12;
        int[] cs = new int[n];
        float[] st = new float[n];
        for (int i = 0; i < n; i++) {
            float k = i / (float) (n - 1), d = k * end;
            st[i] = k;
            cs[i] = Ease.alpha(color, blurAt(d, R, s));
        }
        if (outR != null) outR[0] = end;
        return radial(cx, cy, end, cs, st);
    }
}
