package com.x3card.pet;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * 桌宠原生版（照网页版 pet 卡片整个搬过来）：一张卡片（Card）上叠着场景、时钟、宠物、气泡、特效、页面、按钮和面板，主逻辑在 App。
 * 宿主（车上是 Uiplus：宿主给外部应用的 Binder 接口；模拟器、手机上是调试宿主 DebugHost）给车况、媒体，App 的车机部分（Car）接话、出胶囊、车况页。
 * 全屏同琉光原生版：自己在主屏上开一个全屏页（FullActivity），全屏页开着时小窗口里停；设置、记录两边共用（SharedPreferences），回到前台重新读。
 * 看不见时（藏起来、全屏页盖住）所有动画、定时器、订阅都停
 */
public class MainActivity extends Activity implements Uiplus.Listener {
    private static WeakReference<MainActivity> pip = new WeakReference<>(null);

    private Host host;
    private App app;
    private Card card;
    private Frames frames;
    private boolean started, slept;
    private long fsAt;

    /** 全屏页（FullActivity）是真 */
    boolean isFull() { return false; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!isFull()) pip = new WeakReference<>(this);
        Store.init(this);
        Fonts.init(this);
        U u = new U();
        frames = new Frames();
        card = new Card(this, u);
        app = new App(this, u, frames, card);
        card.app = app;
        host = DebugHost.wanted(this) ? new DebugHost(this, this) : new Uiplus(this, this);
        app.host = host;
        Wiring.build(this, app, card, host);
        app.fsMode = isFull() ? "small" : onMainScreen() ? "" : "full";
        card.onSized = app::layoutAll;
        setContentView(card);
        applyBar();
        app.boot();
        app.debug(getIntent().getExtras());
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); app.debug(i.getExtras()); }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        reload();
        if (!slept) wake(true);
        host.start();
        if (isFull()) { MainActivity p = pip.get(); if (p != null && p != this) p.sleep(true); }
    }
    @Override protected void onStop() {
        started = false;
        wake(false);
        host.background(); // 不马上断开：宿主的授权确认页会把这里盖住，授权申请挂在这个会话上
        if (isFull()) { MainActivity p = pip.get(); if (p != null && p != this) p.sleep(false); }
        super.onStop();
    }
    @Override protected void onDestroy() {
        app.destroy();
        host.quit();
        super.onDestroy();
    }
    @Override public void onBackPressed() { if (!app.back()) super.onBackPressed(); }

    private void wake(boolean on) {
        frames.pause(!on);
        app.setHidden(!on);
    }
    /** 全屏页开着：小窗口里停；关了接着跑，顺便读一下全屏页里改过的设置 */
    void sleep(boolean s) {
        if (s == slept) return;
        slept = s;
        if (!started) return;
        if (s) wake(false); else { reload(); wake(true); }
    }
    /** 回到前台：全屏页和小窗口共用一份设置，另一边改过的跟上 */
    private void reload() { app.reloadSettings(); }
    private boolean onMainScreen() {
        Display d = getWindowManager().getDefaultDisplay();
        return d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY;
    }
    /** 全屏页里收起状态栏（设置"全屏时收起状态栏"） */
    void applyBar() {
        if (!isFull()) return;
        if ("on".equals(app.settings.fsBar)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    /** 全屏键：小窗口里打开全屏页，全屏页里关掉自己回到小窗口；返回 false 表示切不过去 */
    boolean fullscreen() {
        long t = SystemClock.uptimeMillis();
        if (t - fsAt < 2500) return true; // 点一次 2.5 秒内不再切
        fsAt = t;
        if (isFull()) { finishAndRemoveTask(); return true; }
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            startActivity(new Intent(this, FullActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), o.toBundle());
            return true;
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "全屏打开失败", e);
            fsAt = 0;
            return false;
        }
    }

    // ================= 宿主 =================
    @Override public void onState(String state, String detail) { app.hostChanged(); }
    @Override public void onPermissions(boolean read, boolean exec) { app.hostChanged(); }
    @Override public void onEvent(JSONObject m) { if (app.car != null) app.car.onEvent(m); }
    void authorize() {
        try {
            host.authorize(new JSONObject().put("readData", true).put("executeActions", true), (r, code, msg) -> { if (code != null) app.toast("授权没成：" + msg); });
        } catch (Exception ignored) { }
    }
}
