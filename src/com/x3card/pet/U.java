package com.x3card.pet;

/**
 * 卡片的尺寸（同网页版：坐标用 CSS 像素 = dp；1rem = 参考高度 / 10.52）。
 * 参考高度：弹出键盘时窗口变矮，正在输入就保持原来的高度（同 viewport.js），场景、宠物的地板都不跟着动
 */
final class U {
    float dens = 1, W = 1, H = 1, rem = 1;
    /** 实际看得见的高度（键盘弹出时比 H 小） */
    float visH = 1;
    boolean portrait;

    float px(float dp) { return dp * dens; }
}
