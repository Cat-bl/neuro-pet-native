package com.x3card.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;

import com.x3card.pet.mascot.AnimConfig;
import com.x3card.pet.mascot.AnimOptions;
import com.x3card.pet.mascot.Pack;
import com.x3card.pet.mascot.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 宠物层（同网页版引擎插进卡片的那几个 img）：两只宠物在同一个 World 里（互动、抱在一起要互相看得见），引擎每 40ms 走一步逻辑。
 * 每只宠物一个小 View：位置走 translation（不重画），换姿势、转身才重画。
 * 省电（同 main.js 包的 loop / render）：有宠物在动、被拖着、被按着时逐帧（两步之间插值画得顺）；都不动超过 0.6 秒就改成每 40ms 一次的定时器接着走逻辑，
 * 位置、姿势、朝向都没变时什么都不画。精灵图按需解码、最近用过的留着（两只常用的姿势加起来几兆）
 */
final class PetLayer implements Frames.Client {
    private static final int TICK = 40, IDLE = 600;
    private final Context ctx;
    private final App app;
    private final Layer host;
    final World world;
    private final Random rnd = new Random();
    private final Map<String, Pack> packs = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, int[]> sizes = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int[] NONE = new int[0];
    private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    final ArrayList<Pet> pets = new ArrayList<>();
    private boolean hidden, slow;
    private long stillSince;
    private final Runnable slowTick = this::slowTick;
    private final Runnable resized = () -> world().resized();

    PetLayer(Context c, App app, Layer host) {
        ctx = c;
        this.app = app;
        this.host = host;
        world = new World(() -> (double) SystemClock.uptimeMillis(), rnd::nextDouble, app.u.W, app.u.H);
    }
    private World world() { return world; }

    // ================= 宠物包、精灵图 =================
    /** 后台线程先把两个宠物包解析好（开场动画期间做，出场时直接用） */
    void preload(String... names) {
        Thread t = new Thread(() -> { for (String n : names) { try { pack(n); } catch (RuntimeException e) { android.util.Log.w(Uiplus.TAG, n + " 解析失败", e); } } }, "pack");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
        loader = t;
    }
    private Thread loader;
    private synchronized Pack pack(String name) {
        Pack p = packs.get(name);
        if (p != null) return p;
        Map<String, String> files = new HashMap<>();
        for (String f : new String[] { "actions.xml", "behaviors.xml", "info.json", "bubble_context.txt" }) {
            try { files.put(f, Io.text(ctx, name + "_" + f)); } catch (Exception ignored) { }
        }
        p = Pack.load(name, files, new Pack.Images() {
            @Override public int[] size(String image) { return sizeOf(name, image); }
            @Override public Boolean opaque(String image, int x, int y) {
                Bitmap b = bitmap(name, image);
                if (b == null || x < 0 || y < 0 || x >= b.getWidth() || y >= b.getHeight()) return null;
                return (b.getPixel(x, y) >>> 24) > 10;
            }
        });
        packs.put(name, p);
        return p;
    }
    private static String file(String pack, String image) {
        String n = image.startsWith("/") ? image.substring(1) : image;
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        return pack + "_" + n;
    }
    private int[] sizeOf(String pack, String image) {
        String f = file(pack, image);
        int[] s = sizes.get(f);
        if (s != null) return s == NONE ? null : s;
        try (java.io.InputStream in = ctx.getAssets().open(f)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            s = new int[] { o.outWidth, o.outHeight };
        } catch (Exception e) { s = NONE; }
        sizes.put(f, s);
        return s == NONE ? null : s;
    }
    Bitmap bitmap(String pack, String image) {
        if (image == null) return null;
        String f = file(pack, image);
        Bitmap b = bitmaps.get(f);
        if (b != null) return b;
        try (java.io.InputStream in = ctx.getAssets().open(f)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            b = BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) { b = null; }
        if (b != null) bitmaps.put(f, b);
        return b;
    }

    // ================= 出生、拿掉 =================
    interface Spawned { void spawned(Pet p); }
    /** 出生：包还在后台解析就等它解析完（不阻塞主线程），解析失败回 null */
    void spawn(String packName, Spawned cb) {
        Pack ready = packs.get(packName);
        if (ready != null) { cb.spawned(spawn(packName, ready)); return; }
        new Thread(() -> {
            Pack p;
            try { p = pack(packName); } catch (RuntimeException e) { android.util.Log.w(Uiplus.TAG, packName + " 加载失败", e); p = null; }
            final Pack pp = p;
            app.h.post(() -> cb.spawned(pp == null ? null : spawn(packName, pp)));
        }, "pack").start();
    }
    private Pet spawn(String packName, Pack p) {
        Pet pet = new Pet(this, packName, p);
        pet.view = host.add(new PetView(ctx, pet));
        pets.add(pet);
        pet.engine.init();
        pet.view.sized(); // 一出生就定好框：0×0 的 View 内容照样画，但重画请求会被丢掉，姿势卡在上一张
        wake();
        return pet;
    }
    void remove(Pet p) {
        pets.remove(p);
        p.engine.destroy();
        host.removeView(p.view);
    }
    void destroy() {
        for (Pet p : new ArrayList<>(pets)) remove(p);
        app.h.removeCallbacks(slowTick);
        app.frames.remove(this);
        bitmaps.evictAll();
    }

    /** 设置宠物的缩放、地板、右墙、卡片大小（引擎每一步都按它重新算活动区，站在地上的宠物跟着地板走） */
    private float cfgScale = -1, cfgFloor = -1, cfgW = -1, cfgH = -1;
    private Float cfgRight;
    void config(float scale, float floor, Float right, float W, float H) {
        boolean sizeCh = W != cfgW || H != cfgH;
        if (scale == cfgScale && floor == cfgFloor && W == cfgW && H == cfgH && (right == null ? cfgRight == null : right.equals(cfgRight))) return;
        cfgScale = scale; cfgFloor = floor; cfgRight = right; cfgW = W; cfgH = H;
        AnimOptions o = new AnimOptions().scale(scale).zoneBottom((double) floor);
        if (right != null) o.zoneRight((double) right);
        world.setAnimationConfig(new AnimConfig(o));
        if (sizeCh) {
            world.setViewport(W, H);
            app.h.removeCallbacks(resized);
            app.h.postDelayed(resized, 200);
        }
        for (Pet p : pets) p.view.sized();
        wake();
    }

    /** 只挪"光标"（让宠物跑过去：ChaseMouse 看它） */
    void cursor(float x, float y) { world.pointerMove(x, y, 0, SystemClock.uptimeMillis()); wake(); }

    /** 这一点（dp）按在哪只宠物的图片上（后画的在上面） */
    Pet petAt(float x, float y) {
        for (int i = pets.size() - 1; i >= 0; i--) { Pet p = pets.get(i); if (!p.removed && p.engine.hitSprite(x, y)) return p; }
        return null;
    }

    // ================= 走逻辑、画 =================
    /** 有东西要动了（被按、被拖、换了行为、出生）：切回逐帧 */
    void wake() {
        if (hidden) return;
        slow = false;
        stillSince = 0;
        app.h.removeCallbacks(slowTick);
        app.frames.add(this, 60);
    }
    private boolean moving() {
        for (Pet p : pets) {
            if (p.engine.isDragging() || p.engine.pressActive()) return true;
            if (p.engine.x != p.engine.prevX || p.engine.y != p.engine.prevY) return true;
        }
        return false;
    }
    @Override public boolean frame(long now) {
        if (hidden || pets.isEmpty()) return false;
        for (Pet p : pets) { p.engine.advance(now); p.view.sync(); }
        if (moving()) { stillSince = 0; return true; }
        if (stillSince == 0) stillSince = now;
        if (now - stillSince < IDLE) return true;
        // 都不动了：改成每 40ms 一次的定时器接着走（逻辑节拍不变，换姿势照常画）
        slow = true;
        app.h.postDelayed(slowTick, TICK);
        return false;
    }
    private void slowTick() {
        if (hidden || pets.isEmpty() || !slow) return;
        long now = SystemClock.uptimeMillis();
        for (Pet p : pets) { p.engine.advance(now); p.view.sync(); }
        if (moving()) { wake(); return; }
        app.h.postDelayed(slowTick, TICK);
    }
    void setHidden(boolean h) {
        if (hidden == h) return;
        hidden = h;
        world.setHidden(h);
        if (h) { app.h.removeCallbacks(slowTick); app.frames.remove(this); }
        else wake();
    }
    void layout() { for (Pet p : pets) p.view.sized(); }

    // ================= 一只宠物的 View =================
    final class PetView extends View {
        private final Pet pet;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        private String image;
        private boolean flip;
        private float ax, ay, box;
        PetView(Context c, Pet pet) { super(c); this.pet = pet; }
        /** 框：精灵图（128）× 缩放，四周多留一点（图片大于 128 的） */
        void sized() {
            float d = app.u.dens;
            box = (float) (160 * Math.max(0.1, pet.engine.scale())) * d;
            Layer.place(this, 0, 0, box * 2, box * 2);
            invalidate();
            sync();
        }
        /** 跟上引擎：位置走 translation，图片 / 朝向 / 锚点变了才重画 */
        void sync() {
            String img = pet.engine.image();
            float d = app.u.dens;
            float x = (float) pet.engine.drawX() * d, y = (float) pet.engine.drawY() * d;
            setTranslationX(x - box);
            setTranslationY(y - box);
            boolean f = pet.engine.flipped();
            float nax = (float) pet.engine.imageAnchorX(), nay = (float) pet.engine.imageAnchorY();
            if (img == null ? image != null : !img.equals(image) || f != flip || nax != ax || nay != ay) {
                image = img; flip = f; ax = nax; ay = nay;
                invalidate();
            }
        }
        @Override protected void onDraw(Canvas c) {
            if (image == null) return;
            Bitmap b = bitmap(pet.pack, image);
            if (b == null) return;
            float s = (float) pet.engine.scale() * app.u.dens;
            c.save();
            c.translate(box, box); // 锚点在 View 正中
            if (flip) c.scale(-1, 1);
            c.scale(s, s);
            c.drawBitmap(b, -ax, -ay, p);
            c.restore();
        }
    }
}
