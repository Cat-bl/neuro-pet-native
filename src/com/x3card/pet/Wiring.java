package com.x3card.pet;

import android.content.Context;
import android.os.SystemClock;

/** 把各部分装到卡片上、接好互相的回调（顺序同网页版 index.html 的层次和脚本顺序） */
final class Wiring {
    private Wiring() {}

    static void build(MainActivity a, App app, Card card, Host host) {
        Context c = a;
        long t0 = SystemClock.uptimeMillis();
        app.sky = new Sky(c, app.u, app.frames, card.skyBack, card.skyFront);
        app.sky.introAt = t0;
        app.clock = new Clock(c, app.u, app.frames, card.clockHost);
        app.clock.introAt = t0;
        app.fx = new Fx(c, app.u, app.frames, card.festHost, card.topHost, card.rippleHost);
        app.pages = new Pages(c, app, card.pageHost);
        app.petLayer = new PetLayer(c, app, card.petsHost);
        app.bubbles = new Bubbles(app, card.bubblesHost);
        app.play = new Play(c, app, card.propsHost, card.eventsHost, card.topHost, card.gameHost);
        app.car = new Car(app, host);
        app.music = new Music(app, app.car);
        app.ui = new Ui(c, app, card.uiHost);
        app.ai = new Ai(c, app, card.aiHost);
        app.panel = card.panelHost.add(new Panel(c, app));
        app.sheet = card.sheetHost.add(new Sheet(c, app));
        app.guide = new Guide(c, app, card.guideHost);
        app.info = card.infoHost.add(new InfoBar(c, app));

        app.ui.onGear = () -> { if (app.panelOpen) app.closePanel(); else app.openPanel(); };
        app.ui.onChat = () -> { if (app.ai != null) app.ai.openBar(); };
        app.ui.onFs = () -> {
            if (a.fullscreen()) return;
            Pet p = app.randomPet();
            if (p != null) app.say(p, app.line(p, "fsFail"));
        };
        app.clock.pills.listener = act -> { if (app.music != null) app.music.pillTap(act); };
        app.bubbles.onPress = (b, act) -> {
            if (act.startsWith("mc:")) { if (app.music != null) app.music.bubblePress(act); }
            else if (app.ai != null) app.ai.press(b, act);
        };
        app.activity = a;
    }
}
