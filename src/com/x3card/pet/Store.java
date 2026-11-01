package com.x3card.pet;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地存储（同网页版的 localStorage：同样的键，值是同样的 JSON 文字）。全屏页、小窗口共用一份 */
final class Store {
    private static SharedPreferences sp;
    private Store() {}

    static void init(Context c) { if (sp == null) sp = c.getApplicationContext().getSharedPreferences("pet", Context.MODE_PRIVATE); }
    static String get(String k) { return sp == null ? null : sp.getString(k, null); }
    static void set(String k, String v) { if (sp != null) sp.edit().putString(k, v).apply(); }
    static void remove(String k) { if (sp != null) sp.edit().remove(k).apply(); }
}
