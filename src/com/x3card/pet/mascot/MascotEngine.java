/*
 * 桌宠引擎（web-mascot 的 Java 移植）
 * web-mascot：https://github.com/CommentOut64/web-mascot （commit 206b2a6，即 pet/web-mascot.js），
 * 移植自 libshijima / Shijima-Qt / NeurolingsCE-Qt。许可：GPL-3.0-or-later，见 Js.java 开头。
 */
package com.x3card.pet.mascot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一只宠物的仿真（MascotEngine.ts 的移植，只有逻辑，不画）。坐标在引擎里是虚拟单位（1 = 精灵图 1 像素），
 * 对外的位置都是页面像素（虚拟单位 × 缩放），和网页版一样。
 *
 * <p>用法：
 * <pre>
 *   World world = new World(clock, random, 卡片宽, 卡片高);
 *   world.setAnimationConfig(new AnimConfig(new AnimOptions().scale(s).zoneBottom(地板).zoneRight(右墙)));
 *   MascotEngine e = new MascotEngine(world, pack); e.init();   // 出生、从上面掉下来
 *   每帧：e.advance(clock.now()) 走完到点的逻辑节拍（40ms 一步），再按 drawX/drawY/image()/flipped() 画；
 *   或者自己控制：e.step() 走一步，e.render(进度) 在 prevX/prevY 和 x/y 之间插值。
 * </pre>
 * 输入（页面像素，时间戳和 World 的时钟同一基准）：
 * 按下 {@link #pointerDown}（只发给按到的那只，返回 false 表示按在透明处、引擎不接）；
 * 移动 / 抬起 / 被抢走 {@link World#pointerMove} / {@link World#pointerUp} / {@link World#pointerCancel}（发给所有宠物，同网页版 window 上的事件）；
 * 让宠物跑过去、坐下看着某处：{@link World#pointerMove} 传 buttons 0 挪光标，再 setBehavior("ChaseMouse", …)。
 * 卡片自己判断"手指按在宠物上"用 {@link #hitSprite}（图片的方框，同网页版只认 img 元素）。
 * 卡片藏起来 / 回来 {@link World#setHidden}；尺寸变了 {@link World#setViewport}，200ms 后 {@link World#resized}。
 * 行为切换全走 {@link #setBehavior(String, String, int)}，子类可以覆盖（卡片把 ChaseMouse 之后的 SitAndFaceMouse 换成 StandUp）。
 * 只在一个线程里用；Pack 解析完不再改，可以在后台线程解析、几只宠物共用。
 */
public class MascotEngine {
    // ---------------- 常量（同 TS） ----------------
    static final int STALL_LIMIT_TICKS = 150;
    static final double SPRITE_WIDTH = 128;
    static final String FLEXIBLE_BORDER = "*";
    static final double MAX_CURSOR_STEP = 80;
    static final double DRAG_STILL_EPSILON = 1.25;
    static final double HOLD_TRIGGER_MS = 260, HOLD_MOVEMENT_TOLERANCE_PX = 12, CLICK_MOVEMENT_TOLERANCE_PX = 6, CLICK_DURATION_MS = 400;
    static final int MAX_BEHAVIOR_INITS_PER_TICK = 20, MAX_ACTION_EXPANSIONS_PER_TICK = 200;
    static final double MAX_FRAME_DELTA_MS = 200;
    /** setBehavior 不指定 behaviors.xml 里第几条时传它 */
    public static final int ANY_ID = -1;

    private static final Pattern ENGINE_STATES = Pattern.compile("Jump|Fall|Thrown|Dragged", Pattern.CASE_INSENSITIVE);
    private static final String NONE = "\u0000none";

    /** 动作栈里的一层（ActionFrame） */
    static final class Frame {
        final Pack.Action node;
        int childIndex;
        LinkedHashMap<String, String> vars;
        boolean dynamicKnown, dynamic;
        final HashMap<String, Object> resolved;
        HashMap<String, Object> live = new HashMap<>();
        int liveTick = -1;
        /** 停留上限（tick），NaN 表示没有 */
        double durationLimit = Double.NaN;
        int elapsed, poseIndex, poseTick, stallTicks;
        double progressX = Double.NaN, progressY = Double.NaN;
        Broadcast.Server server;
        Broadcast.Client client;
        Pack.Anim animation;
        final int bornTick;

        Frame(Pack.Action node, LinkedHashMap<String, String> vars, HashMap<String, Object> resolved, int bornTick) {
            this.node = node;
            this.vars = vars;
            this.resolved = resolved;
            this.bornTick = bornTick;
        }

        boolean hasLimit() { return durationLimit == durationLimit; }

        boolean hasDynamic() {
            if (!dynamicKnown) {
                dynamic = false;
                for (String v : vars.values()) if (v.startsWith("#{")) { dynamic = true; break; }
                dynamicKnown = true;
            }
            return dynamic;
        }
    }

    final World world;
    final Pack pack;
    final String mascotName;
    public final int id;
    public final String label;

    private final Map<String, Boolean> behaviorUsability = new HashMap<>();
    private final Map<String, Boolean> partnerCache = new HashMap<>();
    private final Map<String, boolean[]> travelCache = new HashMap<>();
    private final Map<String, String> borderStartCache = new HashMap<>();
    private final ArrayList<Frame> actionStack = new ArrayList<>();
    private Pack.Behavior currentBehavior;
    private boolean interactionVisualActive;
    private int tickCount;
    /** 上一个逻辑节拍时的位置（虚拟单位），插值用 */
    public double prevX, prevY;
    private boolean isDragging;
    private int dragStillTicks;
    private double lastTime, accumulator;
    private boolean hidden, initialized, destroyed;
    private String lastSurface;
    private double cursorX, cursorY, cursorTickDX, cursorTickDY, lastTickCursorX, lastTickCursorY;
    private double footX, footDX;
    private boolean hasGrab;
    private double grabX, grabY, dragPressAnchorX, dragPressAnchorY, dragPressCursorX, dragPressCursorY;
    private boolean pressActive;
    private double pressStartAt, pressClientX, pressClientY, pressMaxMovement;
    private Pack.Hotspot hotspotHold;
    private boolean hotspotHoldTriggered;
    private int cursorButtons;
    private double shiftX, shiftY;
    private int shiftTick = -1;
    private int behaviorTicks, stillTicks, anchorStillTicks;
    private boolean wantMovement;
    private int interactionCooldownUntil;
    private int behaviorInitsThisTick, actionExpansionsThisTick;
    private Broadcast.Interaction interaction = new Broadcast.Interaction();
    private int servingClaimSince;

    // 画出来的样子（网页版 elRoot / elImg 上的状态）
    private double drawnPageX, drawnPageY;
    private String shownImage;
    private double shownAx, shownAy;
    private boolean shownLookRight;

    /** 位置（虚拟单位）和速度 */
    public double x, y, vx, vy;
    public boolean lookRight = true;

    public MascotEngine(World world, Pack pack) {
        this.world = world;
        this.pack = pack;
        this.mascotName = pack.name;
        this.id = world.nextId++;
        this.label = id + "-" + mascotName;
    }

    /** 出生（同网页版 init()：按配置放到出生点、加入 World、从 Fall 开始） */
    public void init() {
        if (initialized || destroyed) return;
        initialized = true;
        applySpawnPosition();
        prevX = x;
        prevY = y;
        world.live.add(this);
        setBehavior("Fall", "spawn", ANY_ID);
        lastTime = world.clock.now();
    }

    // ================= 对外：状态 =================

    /** 缩放（配置里的 scale） */
    public double scale() { return world.config.mascotScale(mascotName); }

    /** 位置（页面像素） */
    public double pageX() { return x * scale(); }

    public double pageY() { return y * scale(); }

    public boolean isDragging() { return isDragging; }

    public boolean pressActive() { return pressActive; }

    public boolean isDestroyed() { return destroyed; }

    /** 当前行为名（没有时为 ""） */
    public String behavior() { return currentBehavior != null ? currentBehavior.name : ""; }

    /** 当前行为在 behaviors.xml 里是第几条（-1 表示不是从列表里来的） */
    public int behaviorId() { return currentBehavior != null ? currentBehavior.id : -1; }

    public int tickCount() { return tickCount; }

    /** 正在显示的姿势（动作栈顶的当前帧），没有时为 null */
    public Pack.Pose pose() { return currentPose(); }

    /** 同网页版 snapshot() */
    public static final class Snapshot {
        public String pet, mascot, behavior, leaf, path, nodeType, border, image;
        public double x, y;
        public boolean lookRight, dragging;
        public int stackDepth, tick;
    }

    public Snapshot snapshot() {
        Pack.Action node = currentLeaf();
        double scale = scale();
        Snapshot s = new Snapshot();
        s.pet = label;
        s.mascot = mascotName;
        s.x = x * scale;
        s.y = y * scale;
        s.behavior = behavior();
        s.leaf = leafLabel();
        s.path = stackPath();
        s.nodeType = node != null ? node.type : "";
        s.border = node != null && node.borderType != null ? node.borderType : "";
        s.image = currentImage();
        s.lookRight = lookRight;
        s.stackDepth = actionStack.size();
        s.dragging = isDragging;
        s.tick = tickCount;
        return s;
    }

    /** 动作栈：每层的名字（或类名、类型），用 " > " 连起来 */
    public String stackPath() {
        StringBuilder b = new StringBuilder();
        for (Frame f : actionStack) {
            if (b.length() > 0) b.append(" > ");
            Pack.Action n = f.node;
            b.append(n.name != null ? n.name : n.className != null ? n.className : n.type);
        }
        return b.toString();
    }

    /** 动作栈顶："名字|类型" */
    public String leafLabel() {
        Pack.Action node = currentLeaf();
        if (node == null) return "-";
        return (node.name != null ? node.name : node.className != null ? node.className : "?") + "|" + node.type;
    }

    private String currentImage() {
        if (shownImage == null) return "";
        int i = shownImage.lastIndexOf('/');
        return i >= 0 ? shownImage.substring(i + 1) : shownImage;
    }

    // ================= 对外：画 =================

    /** 锚点画在哪（页面像素，已插值） */
    public double drawX() { return drawnPageX; }

    public double drawY() { return drawnPageY; }

    /** 正在显示的图片名（pack 里写的，如 "/shime1.png"）；null 表示这时不显示（两只抱在一起时由另一只画） */
    public String image() { return shownImage; }

    /** 图片锚点（图片像素） */
    public double imageAnchorX() { return shownAx; }

    public double imageAnchorY() { return shownAy; }

    /** true：图片要左右翻转（朝右） */
    public boolean flipped() { return shownLookRight; }

    /**
     * 图片在页面上的方框 {左, 上, 右, 下}（页面像素）；不显示时返回 false。
     * 朝左时图片左边 = 锚点 - 锚点X × 缩放；朝右时左右翻转。
     */
    public boolean spriteRect(double[] out) {
        if (shownImage == null) return false;
        int[] size = pack.images.size(shownImage);
        double w = size != null && size[0] > 0 ? size[0] : SPRITE_WIDTH;
        double h = size != null && size[1] > 0 ? size[1] : SPRITE_WIDTH;
        double s = scale();
        double left = shownLookRight ? drawnPageX - (w - shownAx) * s : drawnPageX - shownAx * s;
        double top = drawnPageY - shownAy * s;
        out[0] = left;
        out[1] = top;
        out[2] = left + w * s;
        out[3] = top + h * s;
        return true;
    }

    /** 点（页面像素）在不在宠物图片的方框里（卡片判断"按在宠物上"用；引擎接不接这次按下另看 pointerDown 的返回值） */
    public boolean hitSprite(double px, double py) {
        double[] r = new double[4];
        return spriteRect(r) && px >= r[0] && px < r[2] && py >= r[1] && py < r[3];
    }

    /**
     * 按经过的时间走逻辑节拍（同网页版 loop()：一帧最多补 200ms），再记下这一帧画在哪。返回走了几步。
     * 暂停（卡片藏起来）或销毁后不动。
     */
    public int advance(double now) {
        if (destroyed || hidden || !initialized) return 0;
        accumulator += Math.min(now - lastTime, MAX_FRAME_DELTA_MS);
        lastTime = now;
        int n = 0;
        try {
            while (accumulator >= world.tickMs) {
                tick();
                accumulator -= world.tickMs;
                n++;
            }
            render(!isDragging);
        } catch (RuntimeException e) {
            accumulator = 0;
        }
        return n;
    }

    /** 走一个逻辑节拍（不插值、不记画的位置）。出错吞掉（宠物是装饰，一次出错不能让它停住） */
    public void step() {
        if (destroyed || !initialized) return;
        try {
            tick();
        } catch (RuntimeException e) {
            accumulator = 0;
        }
    }

    /** 记下这一帧画在哪：progress 是 0~1，在 prevX/prevY 和 x/y 之间插值（被拿着时网页版用 1） */
    public void render(double progress) {
        renderAt(Math.max(0, Math.min(1, progress)));
    }

    /** advance() 里累计的、还没走的时间占一个节拍的比例（插值进度） */
    public double progress() { return Math.max(0, Math.min(1, accumulator / world.tickMs)); }

    private void render(boolean interpolate) {
        renderAt(interpolate ? Math.max(0, Math.min(1, accumulator / world.tickMs)) : 1);
    }

    private void renderAt(double progress) {
        Pack.Pose pose = currentPose();
        if (pose == null) return;
        double ix = prevX + (x - prevX) * progress;
        double iy = prevY + (y - prevY) * progress;
        double scale = scale();
        drawnPageX = ix * scale;
        drawnPageY = iy * scale;
        if (pose.image.isEmpty()) {
            if (interactionVisualActive) shownImage = null;
            return;
        }
        shownImage = pose.image;
        shownAx = pose.anchorX;
        shownAy = pose.anchorY;
        shownLookRight = lookRight;
    }

    /** 销毁：放开互动广播，从 World 里拿掉 */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        releaseStack();
        actionStack.clear();
        interaction.finalizeInteraction();
        world.live.remove(this);
    }

    // ================= 输入 =================

    /**
     * 手指按下（页面像素，t 是时间戳）。只发给按到的那只（网页版 elRoot 的 mousedown）。
     * 按在不透明像素上才接：按在热区（如头）上先当候选（长按变成热区行为、短按算点击、拖远了变成拿起），否则直接拿起。
     * @return 引擎接了这次按下
     */
    public boolean pointerDown(double px, double py, int buttons, double t) {
        if (destroyed) return false;
        world.lastPointerX = px;
        world.lastPointerY = py;
        hostShift(true);
        if (!isOpaqueAt(px, py)) return false;
        pressActive = true;
        pressStartAt = t;
        pressClientX = px;
        pressClientY = py;
        pressMaxMovement = 0;
        hotspotHoldTriggered = false;
        cursorX = px;
        cursorY = py;
        cursorButtons = buttons;
        Pack.Hotspot hotspot = hotspotUnderCursor(px, py);
        if (hotspot != null) {
            hotspotHold = hotspot;
            return true;
        }
        hotspotHold = null;
        beginDrag(px, py);
        return true;
    }

    void onWindowMove(double px, double py, int buttons, double t) {
        cursorX = px;
        cursorY = py;
        cursorButtons = buttons;
        if (pressActive) {
            if ((buttons & 1) == 0) finishPress(px, py, t);
            else {
                updatePressMovement();
                if (isDragging) applyDragAnchor();
            }
        }
        if (!isDragging) hotspotUnderCursor(px, py);
    }

    void onWindowUp(int buttons, double px, double py, double t) {
        cursorButtons = buttons;
        if (pressActive) finishPress(px, py, t);
    }

    void onHidden(boolean hide) {
        if (hide) {
            if (isDragging) {
                endDrag();
                setBehavior("Thrown", "drag-release", ANY_ID);
            }
            cancelPress();
            hidden = true;
        } else if (hidden) {
            hidden = false;
            lastTime = world.clock.now();
            accumulator = 0;
        }
    }

    void onResized() {
        String borderBefore = currentBorderAt(null);
        partnerCache.clear();
        travelCache.clear();
        borderStartCache.clear();
        behaviorUsability.clear();
        clampToWorkArea();
        Scopes.Area area = area();
        for (Frame frame : actionStack) {
            Object tx = frame.resolved.get("TargetX");
            if (tx instanceof Double) frame.resolved.put("TargetX", Math.max(area.left, Math.min(area.right, (Double) tx)));
            Object ty = frame.resolved.get("TargetY");
            if (ty instanceof Double) frame.resolved.put("TargetY", Math.max(area.top, Math.min(area.bottom, (Double) ty)));
        }
        String borderAfter = currentBorderAt(null);
        if (borderBefore != null && borderAfter == null && !ENGINE_STATES.matcher(behavior()).find()) setBehavior("Fall", "resize-border-lost", ANY_ID);
    }

    // ================= 指针细节（同 TS） =================

    private double shownWidth() {
        if (shownImage == null) return SPRITE_WIDTH;
        int[] s = pack.images.size(shownImage);
        return s != null && s[0] > 0 ? s[0] : SPRITE_WIDTH;
    }

    private double shownHeight() {
        if (shownImage == null) return SPRITE_WIDTH;
        int[] s = pack.images.size(shownImage);
        return s != null && s[1] > 0 ? s[1] : SPRITE_WIDTH;
    }

    /** 页面上的点 → 精灵图里的坐标（null：没有姿势） */
    private double[] spritePoint(double px, double py) {
        Pack.Pose pose = currentPose();
        if (pose == null) return null;
        double scale = scale();
        double cx = px / scale, cy = py / scale;
        double width = shownWidth();
        hostShift(false);
        double left = (lookRight ? x - (width - pose.anchorX) : x - pose.anchorX) + shiftX;
        double top = y - pose.anchorY + shiftY;
        return new double[]{cx - left, cy - top};
    }

    /** 画的位置和逻辑位置之差（虚拟单位），每个节拍最多量一次 */
    private void hostShift(boolean force) {
        if (!force && shiftTick == tickCount) return;
        double scale = scale();
        shiftX = drawnPageX / scale - x;
        shiftY = drawnPageY / scale - y;
        shiftTick = tickCount;
    }

    private Pack.Hotspot hotspotUnderCursor(double px, double py) {
        if (actionStack.isEmpty()) return null;
        Frame frame = top();
        Pack.Anim animation = frame != null ? activeAnimation(frame) : null;
        if (animation == null || animation.hotspots == null) return null;
        double[] p = spritePoint(px, py);
        if (p == null) return null;
        Pack.Hotspot hotspot = Pack.hotspotAt(animation.hotspots, p[0], p[1]);
        if (hotspot == null) return null;
        if (world.config.denies(mascotName, hotspot.behavior)) return null;
        return hotspot;
    }

    private boolean isOpaqueAt(double px, double py) {
        double[] p = spritePoint(px, py);
        if (p == null) return false;
        double width = shownWidth(), height = shownHeight();
        if (p[0] < 0 || p[1] < 0 || p[0] > width || p[1] > height) return false;
        if (shownImage == null) return true;
        int ix = (int) Math.min(width - 1, Math.max(0, Js.round(lookRight ? width - p[0] : p[0])));
        int iy = (int) Math.min(height - 1, Math.max(0, Js.round(p[1])));
        Boolean opaque = pack.images.opaque(shownImage, ix, iy);
        return opaque == null || opaque;
    }

    private double simCursorX() {
        double c = cursorX / scale();
        return hasGrab ? c + grabX : c;
    }

    private double simCursorY() {
        double c = cursorY / scale();
        return hasGrab ? c + grabY : c;
    }

    private void endDrag() {
        isDragging = false;
        hasGrab = false;
    }

    private void beginDrag(double px, double py) {
        isDragging = true;
        double scale = scale();
        double pcx = px / scale, pcy = py / scale;
        dragPressAnchorX = x;
        dragPressAnchorY = y;
        dragPressCursorX = pcx;
        dragPressCursorY = pcy;
        hasGrab = false;
        cursorX = px;
        cursorY = py;
        footX = x;
        footDX = 0;
        dragStillTicks = 0;
        vx = vy = 0;
        interaction.finalizeInteraction();
        setBehavior("Dragged", "drag-start", ANY_ID);
    }

    private void applyDragAnchor() {
        Frame frame = top();
        if (isResistAction(frame != null ? frame.node : null)) return;
        Double ox = varNumber(frame, "OffsetX");
        double offsetX = ox != null ? ox : world.dragOffsetX;
        Double oy = varNumber(frame, "OffsetY");
        double offsetY = oy != null ? oy : world.dragOffsetY;
        if (!hasGrab) {
            hasGrab = true;
            grabX = dragPressAnchorX - dragPressCursorX - offsetX;
            grabY = dragPressAnchorY - dragPressCursorY - offsetY;
            footX = dragPressAnchorX;
        }
        x = simCursorX() + offsetX;
        y = simCursorY() + offsetY;
    }

    private void updatePressMovement() {
        if (!pressActive) return;
        double distance = Math.abs(cursorX - pressClientX) + Math.abs(cursorY - pressClientY);
        pressMaxMovement = Math.max(pressMaxMovement, distance);
        if (isDragging || hotspotHold == null) return;
        if (distance <= HOLD_MOVEMENT_TOLERANCE_PX) return;
        hotspotHold = null;
        beginDrag(cursorX, cursorY);
    }

    private void maintainHotspotHold() {
        if (!pressActive || hotspotHold == null || isDragging) return;
        if (world.clock.now() - pressStartAt < HOLD_TRIGGER_MS) return;
        if (pressMaxMovement > HOLD_MOVEMENT_TOLERANCE_PX) return;
        String behavior = hotspotHold.behavior;
        hotspotHoldTriggered = true;
        if (currentBehavior != null && behavior.equals(currentBehavior.name)) return;
        spritePoint(cursorX, cursorY);
        setBehavior(behavior, "hotspot-hold", ANY_ID);
    }

    private void finishPress(double px, double py, double t) {
        if (!pressActive) return;
        double elapsed = t - pressStartAt;
        boolean triggeredHold = hotspotHoldTriggered;
        boolean hadCandidate = hotspotHold != null;
        double movement = pressMaxMovement;
        pressActive = false;
        hotspotHold = null;
        hotspotHoldTriggered = false;
        if (isDragging) {
            endDrag();
            setBehavior("Thrown", "drag-release", ANY_ID);
            return;
        }
        if (triggeredHold || !hadCandidate) return;
        if (elapsed > CLICK_DURATION_MS || movement > CLICK_MOVEMENT_TOLERANCE_PX) return;
        Pack.Hotspot hotspot = hotspotUnderCursor(px, py);
        if (hotspot == null) return;
        spritePoint(cursorX, cursorY);
        setBehavior(hotspot.behavior, "hotspot", ANY_ID);
    }

    private void cancelPress() {
        pressActive = false;
        hotspotHold = null;
        hotspotHoldTriggered = false;
    }

    // ================= 活动范围 =================

    private AnimOptions areaOpts;
    private double areaW = Double.NaN, areaH = Double.NaN;
    private Scopes.Area areaCache;

    private double virtualWidth() { return world.viewW / scale(); }

    private double virtualHeight() { return world.viewH / scale(); }

    private double metric(Object value, boolean xAxis, double fallback) {
        if (value == null) return fallback;
        double extent = xAxis ? virtualWidth() : virtualHeight();
        double scale = scale();
        if (value instanceof Double) {
            double v = (Double) value;
            return v == v && !Double.isInfinite(v) ? v / scale : fallback;
        }
        String text = Js.trim(String.valueOf(value)).toLowerCase();
        if (text.endsWith("%")) {
            double percent = Js.parseNumber(text.substring(0, text.length() - 1));
            return percent == percent && !Double.isInfinite(percent) ? (extent * percent) / 100 : fallback;
        }
        if (text.equals("left") || text.equals("top")) return 0;
        if (text.equals("right") || text.equals("bottom")) return extent;
        if (text.equals("center")) return extent / 2;
        double numeric = Js.parseNumber(text);
        return numeric == numeric && !Double.isInfinite(numeric) ? numeric / scale : fallback;
    }

    /** 活动范围（虚拟单位），配置或卡片尺寸变了才重算 */
    Scopes.Area area() {
        AnimOptions o = world.config.of(mascotName);
        if (o == areaOpts && world.viewW == areaW && world.viewH == areaH) return areaCache;
        double left = metric(o.zoneLeft, true, 0);
        double right = metric(o.zoneRight, true, virtualWidth());
        double top = metric(o.zoneTop, false, 0);
        double bottom = metric(o.zoneBottom, false, virtualHeight());
        areaCache = new Scopes.Area(top, Math.max(left + 1, right), Math.max(top + 1, bottom), left);
        areaOpts = o;
        areaW = world.viewW;
        areaH = world.viewH;
        return areaCache;
    }

    private void applySpawnPosition() {
        AnimOptions o = world.config.of(mascotName);
        Scopes.Area area = area();
        double base = metric(o.spawnX, true, (area.left + area.right) / 2);
        int index = world.live.size();
        double stagger = (o.spawnStaggerPx != null ? o.spawnStaggerPx : 0) / scale();
        double offset = index == 0 || stagger == 0 ? 0 : (index % 2 == 1 ? 1 : -1) * Math.ceil(index / 2.0) * stagger;
        x = Math.min(area.right, Math.max(area.left, base + offset));
        y = "floor".equals(o.spawnVertical)
            ? area.bottom
            : area.top + (o.spawnTopOffset != null ? o.spawnTopOffset : 100) / scale();
    }

    private boolean borderIsOn(int side) {
        return Scopes.borderIsOn(side, x, y, area());
    }

    // ================= 表达式 =================

    /** 表达式里能读到的东西：{ finalMascot: mascot, mascot, Math, ...vars } */
    private final class EvalScope implements Expr.Scope {
        Map<String, Object> top;
        Map<String, String> base;
        boolean hasTarget;
        Object target;
        private MascotObj mascot;

        EvalScope(Map<String, Object> top, Map<String, String> base) {
            this.top = top;
            this.base = base;
        }

        /** vars 里的值；null 表示没有 */
        Object var(String key) {
            if (hasTarget && key.equals("target")) return target;
            if (top != null) {
                Object v = top.get(key);
                if (v != null) return v;
            }
            return base != null ? base.get(key) : null;
        }

        @Override public Object lookup(String name) {
            Object v = var(name);
            if (v != null) return v;
            switch (name) {
                case "mascot": case "finalMascot":
                    if (mascot == null) mascot = new MascotObj(this);
                    return mascot;
                case "Math":
                    return world.math;
                default:
                    return null;
            }
        }
    }

    /** mascot：anchor、lookRight、environment、totalCount，外加 vars */
    private final class MascotObj implements Js.Obj {
        private final EvalScope scope;
        private final double ax = x, ay = y;
        private final boolean look = lookRight;
        private Scopes.Point anchor;
        private Scopes.Env env;

        MascotObj(EvalScope scope) { this.scope = scope; }

        @Override public Object get(String key) {
            Object v = scope.var(key);
            if (v != null) return v;
            switch (key) {
                case "anchor":
                    if (anchor == null) anchor = new Scopes.Point(ax, ay);
                    return anchor;
                case "lookRight":
                    return look;
                case "environment":
                    if (env == null) env = new Scopes.Env(area(), new Scopes.Cursor(simCursorX(), simCursorY(), cursorTickDX, cursorTickDY));
                    return env;
                case "totalCount":
                    return (double) Math.max(1, world.live.size());
                default:
                    return null;
            }
        }
    }

    private static final Map<String, Expr> CLEANED = new java.util.concurrent.ConcurrentHashMap<>();

    /** 去掉 ${ / #{ 和结尾的 }，编译（带缓存） */
    private static Expr compiled(String expression) {
        Expr e = CLEANED.get(expression);
        if (e != null) return e;
        String s = expression;
        int a = s.startsWith("${") || s.startsWith("#{") ? 2 : 0;
        int b = s.length();
        if (b > a && s.charAt(b - 1) == '}') b--;
        e = Expr.compile(Js.trim(s.substring(a, b)));
        CLEANED.put(expression, e);
        return e;
    }

    private Object run(String expression, EvalScope scope) {
        return compiled(expression).eval(scope);
    }

    private static Object literal(String raw) {
        if (raw.equals("true")) return Boolean.TRUE;
        if (raw.equals("false")) return Boolean.FALSE;
        if (!Js.trim(raw).isEmpty()) {
            double n = Js.parseNumber(raw);
            if (n == n && !Double.isInfinite(n)) return n;
        }
        return raw;
    }

    /** ActionReference 的属性：${...} 现在算好冻住，#{...} 留给每个节拍，其余转成数字 / 真假（两遍，属性之间可以互相引用） */
    private HashMap<String, Object> resolveVars(Map<String, String> attributes, Map<String, Object> inherited) {
        HashMap<String, Object> resolved = new HashMap<>(inherited);
        for (int pass = 0; pass < 2; pass++) {
            for (Map.Entry<String, String> e : attributes.entrySet()) {
                String key = e.getKey(), raw = e.getValue();
                if (raw.startsWith("#{")) continue;
                if (raw.startsWith("${")) {
                    try {
                        resolved.put(key, run(raw, new EvalScope(resolved, attributes)));
                    } catch (RuntimeException err) {
                        if (!resolved.containsKey(key)) resolved.put(key, raw);
                    }
                } else resolved.put(key, literal(raw));
            }
        }
        return resolved;
    }

    /**
     * frameVars() 里有副作用的部分（${...} 第一次用到时冻住、拖着时写 FootX、每个节拍算一次 #{...}），
     * 返回盖在 frame.vars 上面的那一层。
     */
    private Map<String, Object> frameTop(Frame frame) {
        LinkedHashMap<String, String> raw = frame.vars;
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey(), value = e.getValue();
            if (frame.resolved.containsKey(key) || !value.startsWith("${")) continue;
            Object v;
            try {
                v = run(value, new EvalScope(frame.resolved, raw));
            } catch (RuntimeException err) {
                v = value;
            }
            frame.resolved.put(key, v);
        }
        HashMap<String, Object> frozen = frame.resolved;
        if (isDragging) {
            frozen.put("FootX", footX);
            frozen.put("footX", footX);
            frozen.put("FootDX", footDX);
            frozen.put("footDX", footDX);
        }
        if (!frame.hasDynamic()) return frozen;
        if (frame.liveTick == tickCount) return frame.live;
        HashMap<String, Object> live = new HashMap<>(frozen);
        for (int pass = 0; pass < 2; pass++) {
            for (Map.Entry<String, String> e : raw.entrySet()) {
                String key = e.getKey(), value = e.getValue();
                if (frozen.containsKey(key)) continue;
                if (value.startsWith("#{")) {
                    Object v;
                    try {
                        v = run(value, new EvalScope(live, raw));
                    } catch (RuntimeException err) {
                        v = value;
                    }
                    live.put(key, v);
                } else live.put(key, literal(value));
            }
        }
        frame.live = live;
        frame.liveTick = tickCount;
        return live;
    }

    private Object varValue(Frame frame, String key) {
        if (frame == null) return Js.UNDEF;
        Object v = frameTop(frame).get(key);
        if (v != null) return v;
        String r = frame.vars.get(key);
        return r != null ? r : Js.UNDEF;
    }

    private String varString(Frame frame, String key) {
        Object v = varValue(frame, key);
        if (Js.isNullish(v)) return "";
        return Js.toStr(v);
    }

    private Double varNumber(Frame frame, String key) {
        Object v = varValue(frame, key);
        return Js.finite(v) ? (Double) v : null;
    }

    private EvalScope context(Frame frame) {
        Broadcast.Server target = frame != null && frame.client != null ? frame.client.target() : null;
        EvalScope s = frame != null ? new EvalScope(frameTop(frame), frame.vars) : new EvalScope(null, null);
        s.hasTarget = true;
        s.target = target != null ? new TargetObj(target.ax, target.ay) : Js.UNDEF;
        return s;
    }

    /** { anchor: { x, y } } */
    private static final class TargetObj implements Js.Obj {
        final Scopes.Point anchor;

        TargetObj(double x, double y) { anchor = new Scopes.Point(x, y); }

        @Override public Object get(String key) { return key.equals("anchor") ? anchor : null; }
    }

    private Object evaluate(String expression, Frame frame) {
        if (expression == null || expression.isEmpty()) return Js.UNDEF;
        try {
            return run(expression, context(frame));
        } catch (RuntimeException e) {
            return Js.UNDEF;
        }
    }

    private boolean evaluateCondition(String condition, Frame frame) {
        if (condition == null || condition.isEmpty()) return true;
        return Js.truthy(evaluate(condition, frame));
    }

    private Double evaluateNumber(String expression, Frame frame) {
        Object v = evaluate(expression, frame);
        return Js.finite(v) ? (Double) v : null;
    }

    // ================= 动作栈 =================

    private static LinkedHashMap<String, String> mergeVars(Map<String, String> a, Map<String, String> b) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>(a);
        m.putAll(b);
        return m;
    }

    private Frame newFrame(Pack.Action node, Map<String, String> vars, HashMap<String, Object> resolved) {
        Frame frame = new Frame(node, mergeVars(node.attributes, vars), resolved, tickCount);
        initBroadcast(frame);
        return frame;
    }

    /** 带 Affordance 的动作：ScanMove 去找别人的广播，别的动作广播自己 */
    private void initBroadcast(Frame frame) {
        String affordance = frame.vars.get("Affordance");
        if (affordance == null || Js.trim(affordance).isEmpty()) return;
        if (isScanMove(frame.node)) {
            String probe = Js.trim(affordance);
            String behavior = varString(frame, "Behavior");
            String targetBehavior = varString(frame, "TargetBehavior");
            frame.client = world.broadcasts.tryConnect(probe, x, y, behavior, targetBehavior,
                world.config.scanDistancePx(mascotName));
            varString(frame, "Behavior");
            return;
        }
        frame.server = world.broadcasts.startBroadcast(Js.trim(affordance), x, y);
        LinkedHashMap<String, String> vars = new LinkedHashMap<>(frame.vars);
        vars.remove("Affordance");
        frame.vars = vars;
        frame.dynamicKnown = false;
        varString(frame, "Behavior");
    }

    private static void releaseFrame(Frame frame) {
        if (frame == null) return;
        if (frame.server != null) frame.server.finalizeServer();
        frame.server = null;
        if (frame.client != null) frame.client.finalizeClient();
        frame.client = null;
    }

    private void releaseStack() {
        for (Frame f : actionStack) releaseFrame(f);
    }

    private Frame top() { return actionStack.isEmpty() ? null : actionStack.get(actionStack.size() - 1); }

    private Pack.Action currentLeaf() {
        Frame f = top();
        return f != null ? f.node : null;
    }

    private Pack.Behavior findBehavior(String name, int behaviorId) {
        for (Pack.Behavior b : pack.behaviors) if (b.name.equals(name) && (behaviorId < 0 || b.id == behaviorId)) return b;
        return null;
    }

    /** 同 setBehavior(name, reason, ANY_ID) */
    public final void setBehavior(String name, String reason) {
        setBehavior(name, reason, ANY_ID);
    }

    /**
     * 换行为（引擎内部的每次切换都走这里，子类可以覆盖后再调 super）。
     * @param behaviorId behaviors.xml 里第几条（同名的有几条时用），不指定传 {@link #ANY_ID}
     */
    public void setBehavior(String name, String reason, int behaviorId) {
        if (behaviorInitsThisTick >= MAX_BEHAVIOR_INITS_PER_TICK) {
            if ("StandUp".equals(name)) return;
            name = "StandUp";
            reason = "init-limit";
        }
        behaviorInitsThisTick++;
        Pack.Behavior behavior = findBehavior(name, behaviorId);
        if (behavior == null && pack.actions.containsKey(name)) {
            behavior = new Pack.Behavior(name, -1, 100, false, null, Collections.<Pack.BehaviorRef>emptyList(), true);
        }
        Pack.Behavior selected = behavior;
        if (selected == null) selected = findBehavior("StandUp", ANY_ID);
        if (selected == null && !pack.behaviors.isEmpty()) selected = pack.behaviors.get(0);
        if (selected == null) return;
        Pack.Action action = pack.actions.get(selected.name);
        behaviorTicks = 0;
        stillTicks = 0;
        if (action == null) {
            interactionVisualActive = false;
            currentBehavior = selected;
            onBehaviorEnd(reason);
            return;
        }
        if (interaction.available() && !interaction.started && interaction.behavior().equals(selected.name)) interaction.started = true;
        if (interaction.ongoing() && !selected.name.equals(interaction.behavior()) && !reason.startsWith("interaction-")) {
            interaction.finalizeInteraction();
        }
        releaseStack();
        currentBehavior = selected;
        interactionVisualActive = actionTreeMatches(action, INTERACT);
        if (interactionVisualActive) shownImage = null;
        Frame root = newFrame(action, Collections.<String, String>emptyMap(), new HashMap<String, Object>());
        actionStack.clear();
        actionStack.add(root);
    }

    // ================= 逻辑节拍 =================

    private void tick() {
        if (actionStack.isEmpty()) return;
        if (pressActive) {
            if ((cursorButtons & 1) == 0) finishPress(cursorX, cursorY, world.clock.now());
            else {
                updatePressMovement();
                maintainHotspotHold();
            }
        }
        double beforeX = x, beforeY = y;
        prevX = x;
        prevY = y;
        tickCount++;
        behaviorInitsThisTick = 0;
        actionExpansionsThisTick = 0;
        double scale = scale();
        double limit = MAX_CURSOR_STEP / scale;
        cursorTickDX = Math.max(-limit, Math.min(limit, (cursorX - lastTickCursorX) / scale));
        cursorTickDY = Math.max(-limit, Math.min(limit, (cursorY - lastTickCursorY) / scale));
        lastTickCursorX = cursorX;
        lastTickCursorY = cursorY;
        if (isDragging) {
            Frame frame = top();
            if (!isResistAction(frame != null ? frame.node : null)) {
                applyDragAnchor();
                double cx = simCursorX();
                lookRight = false;
                footDX = (footDX + (cx - footX) * 0.1) * 0.8;
                footX += footDX;
            }
        }
        maintainBroadcasts();
        if (!isDragging) updatePhysics();
        advanceActionTree();
        behaviorTicks++;
        boolean moved = Math.abs(x - beforeX) + Math.abs(y - beforeY) > 0.05;
        stillTicks = moved ? 0 : stillTicks + 1;
        anchorStillTicks = moved ? 0 : anchorStillTicks + 1;
        if (moved) wantMovement = false;
        if (!isDragging) enforceBehaviorSwitch();
        lastSurface = surfaceAt(x, y, area());
    }

    private void maintainBroadcasts() {
        world.broadcasts.prune();
        Broadcast.Server claimed = null;
        for (int i = 0; i < actionStack.size(); i++) {
            Broadcast.Server server = actionStack.get(i).server;
            if (server == null) continue;
            if (server.active()) server.updateAnchor(x, y);
            if (server.isClaimed() && claimed == null) claimed = server;
            if (!server.didMeetUp()) continue;
            interaction = server.interaction();
            String next = interaction.behavior();
            if (!next.isEmpty()) setBehavior(next, "interaction-server", ANY_ID);
            return;
        }
        if (claimed == null) {
            servingClaimSince = 0;
            return;
        }
        if (servingClaimSince == 0) servingClaimSince = tickCount;
        if (tickCount - servingClaimSince >= world.broadcastServeTimeoutTicks) {
            claimed.finalizeServer();
            servingClaimSince = 0;
        }
    }

    private boolean holdingClaim(Frame frame) {
        Broadcast.Server server = frame != null ? frame.server : null;
        if (server == null || !server.isClaimed()) return false;
        if (servingClaimSince == 0) return true;
        return tickCount - servingClaimSince < world.broadcastServeTimeoutTicks;
    }

    private void enforceBehaviorSwitch() {
        if (isDragging) return;
        if (isFallLike(currentLeaf())) return;
        if (currentBehavior != null && "Dragged".equals(currentBehavior.name)) return;
        if (holdingClaim(top())) return;
        AnimConfig.Resolved cfg = world.config;
        int pacer = cfg.forcedSwitchTicks(mascotName);
        if (pacer > 0 && stillTicks >= pacer && !isTravelling()
            && cfg.pacerApplies(mascotName, currentBehavior != null ? currentBehavior.name : null)) {
            if (anchorStillTicks >= pacer) wantMovement = true;
            onBehaviorEnd("forced-switch");
            return;
        }
        int cap = cfg.maxBehaviorTicks(mascotName);
        if (cap > 0 && behaviorTicks >= cap) onBehaviorEnd("behavior-cap");
    }

    private boolean isTravelling() {
        Pack.Action node = currentLeaf();
        Frame frame = top();
        if (node == null || frame == null) return false;
        if ("Move".equals(node.type)) return true;
        if (isScanMove(node)) return true;
        if (varNumber(frame, "TargetX") != null || varNumber(frame, "TargetY") != null) return true;
        Pack.Pose pose = currentPose();
        return pose != null && (pose.vx != 0 || pose.vy != 0);
    }

    private Pack.Anim activeAnimation(Frame frame) {
        List<Pack.Anim> anims = frame.node.animations;
        for (Pack.Anim a : anims) if (evaluateCondition(a.condition, frame)) return a;
        return anims.isEmpty() ? null : anims.get(0);
    }

    private void advanceActionTree() {
        Frame frame = top();
        if (frame == null) return;
        if (++actionExpansionsThisTick > MAX_ACTION_EXPANSIONS_PER_TICK) {
            popActionStack();
            return;
        }
        Pack.Action node = frame.node;
        AnimConfig.Resolved cfg = world.config;

        if (node.isReference()) {
            if (node.condition != null && !evaluateCondition(node.condition, frame)) { popActionStack(); return; }
            if (node.referenceName.isEmpty()) { popActionStack(); return; }
            Pack.Action referenced = pack.actions.get(node.referenceName);
            if (referenced == null) { popActionStack(); return; }
            Map<String, String> attributes = node.attributes;
            LinkedHashMap<String, String> vars = mergeVars(frame.vars, attributes);
            HashMap<String, Object> resolved = new HashMap<>(frame.resolved);
            resolved.putAll(resolveVars(attributes, frame.resolved));
            clampTravelTarget(resolved);
            Frame next = newFrame(referenced, vars, resolved);
            int override = cfg.durationTicks(mascotName, node.referenceName, world.rng);
            if (override >= 0) next.durationLimit = override;
            else if ("Stay".equals(referenced.type)) {
                Double d = varNumber(next, "Duration");
                next.durationLimit = scaledTicks(d != null ? d : world.stayDurationTicks);
            } else next.durationLimit = Double.NaN;
            actionStack.set(actionStack.size() - 1, next);
            Double ivx = varNumber(next, "InitialVX");
            Double ivy = varNumber(next, "InitialVY");
            if (ivx != null) vx = ivx;
            if (ivy != null) vy = ivy;
            Object look = varValue(next, "LookRight");
            if (look instanceof Boolean) lookRight = (Boolean) look;
            advanceActionTree();
            return;
        }

        if (node.condition != null && !evaluateCondition(node.condition, frame)) { popActionStack(); return; }
        boolean sequence = "Sequence".equals(node.type), select = "Select".equals(node.type);
        if (sequence || select) {
            frame.elapsed++;
            if (frame.hasLimit() && frame.elapsed >= frame.durationLimit) {
                popActionStack();
                return;
            }
        }
        if (select) {
            if (frame.childIndex > 0) { popActionStack(); return; }
            Pack.Action child = null;
            if (node.children != null) {
                for (Pack.Action c : node.children) if (evaluateCondition(c.condition, frame)) { child = c; break; }
            }
            frame.childIndex = 1;
            if (child != null) actionStack.add(newFrame(child, new LinkedHashMap<>(frame.vars), new HashMap<>(frame.resolved)));
            else popActionStack();
            advanceActionTree();
            return;
        }
        if (sequence) {
            List<Pack.Action> children = node.children != null ? node.children : Collections.<Pack.Action>emptyList();
            while (frame.childIndex < children.size()) {
                Pack.Action child = children.get(frame.childIndex++);
                if (child.condition != null && !evaluateCondition(child.condition, frame)) continue;
                actionStack.add(newFrame(child, new LinkedHashMap<>(frame.vars), new HashMap<>(frame.resolved)));
                advanceActionTree();
                return;
            }
            if (node.loop) {
                frame.childIndex = 0;
                advanceActionTree();
                return;
            }
            popActionStack();
            return;
        }

        if (endsWith(node.className, ".Look") || "Look".equals(node.name)) {
            Object value = varValue(frame, "LookRight");
            lookRight = value instanceof Boolean ? (Boolean) value : !lookRight;
            popActionStack();
            return;
        }
        if (endsWith(node.className, ".Offset") || "Offset".equals(node.name)) {
            Double ox = varNumber(frame, "X");
            double offsetX = Js.trunc(ox != null ? ox : 0);
            Double oy = varNumber(frame, "Y");
            double offsetY = Js.trunc(oy != null ? oy : 0);
            x += offsetX;
            y += offsetY;
            clampToWorkArea();
            popActionStack();
            return;
        }

        if (isScanMove(node) && !(frame.client != null && frame.client.connected())) {
            if (frame.client != null) varString(frame, "Behavior");
            popActionStack();
            return;
        }

        if (isInteract(node) && (!interaction.available() || !interaction.started || !interaction.ongoing())) {
            popActionStack();
            return;
        }
        if (isInteract(node)) interactionCooldownUntil = tickCount + cfg.interactionCooldown(mascotName);

        if (isDraggedAction(node)) {
            if (!isDragging) {
                setBehavior("Thrown", "drag-release", ANY_ID);
                return;
            }
            if (Math.abs(cursorTickDX) >= DRAG_STILL_EPSILON) dragStillTicks = 0;
            else dragStillTicks++;
            if (dragStillTicks >= world.dragResistTicks) {
                popActionStack();
                return;
            }
        }

        if (isResistAction(node) && Math.abs(simCursorX() - x) >= world.dragResistAbortPx) {
            dragStillTicks = 0;
            setBehavior("Dragged", "resist-abort", ANY_ID);
            return;
        }

        if (frame.bornTick == tickCount) return;
        Pack.Anim animation = activeAnimation(frame);
        if (animation != null && frame.animation != animation) {
            frame.animation = animation;
            frame.poseIndex = 0;
            frame.poseTick = 0;
        }
        List<Pack.Pose> poses = animation != null ? animation.poses : node.poses;
        if (poses.isEmpty()) { popActionStack(); return; }
        if (!frame.hasLimit()) {
            int override = cfg.durationTicks(mascotName, node.name, world.rng);
            if (override >= 0) frame.durationLimit = override;
            else if ("Stay".equals(node.type)) {
                Double d = varNumber(frame, "Duration");
                frame.durationLimit = scaledTicks(d != null ? d : world.stayDurationTicks);
            }
        }
        frame.elapsed++;
        frame.poseTick++;
        if (frame.hasLimit() && frame.elapsed >= frame.durationLimit && !holdingClaim(frame)) {
            popActionStack();
            return;
        }
        Pack.Pose pose = frame.poseIndex < poses.size() ? poses.get(frame.poseIndex) : poses.get(0);
        if (frame.poseTick >= Math.max(1, pose.duration)) {
            frame.poseTick = 0;
            frame.poseIndex++;
            if (frame.poseIndex >= poses.size()) {
                frame.poseIndex = 0;
                if (isResistAction(node)) {
                    endDrag();
                    setBehavior("Thrown", "resist-escape", ANY_ID);
                    return;
                }
                boolean endsByPhysics = isFallLike(node) || (isJumpLike(node) && hasTarget(frame));
                boolean drivenByInteraction = isScanMove(node) && frame.client != null && frame.client.connected();
                if (!node.loop && !isDraggedAction(node) && !drivenByInteraction && !holdingClaim(frame)
                    && !hasTarget(frame) && !frame.hasLimit() && !endsByPhysics) {
                    String handover = isInteract(node) ? varString(frame, "Behavior") : "";
                    if (!handover.isEmpty()) {
                        setBehavior(handover, "interact-behavior", ANY_ID);
                        return;
                    }
                    popActionStack();
                }
            }
        }
    }

    private void popActionStack() {
        Frame frame = actionStack.isEmpty() ? null : actionStack.remove(actionStack.size() - 1);
        releaseFrame(frame);
        if (actionStack.isEmpty()) {
            interaction.finalizeInteraction();
            onBehaviorEnd("behavior-end");
        }
    }

    private Pack.Pose currentPose() {
        Frame frame = top();
        if (frame == null) return null;
        Pack.Anim animation = activeAnimation(frame);
        List<Pack.Pose> poses = animation != null ? animation.poses : frame.node.poses;
        if (poses.isEmpty()) return null;
        return frame.poseIndex < poses.size() ? poses.get(frame.poseIndex) : poses.get(0);
    }

    private boolean borderTypeSatisfied(String type) {
        if (type == null || type.isEmpty()) return true;
        if (type.equals("Floor")) return borderIsOn(Scopes.BOTTOM);
        if (type.equals("Wall")) return borderIsOn(Scopes.LEFT) || borderIsOn(Scopes.RIGHT);
        if (type.equals("Ceiling")) return borderIsOn(Scopes.TOP);
        return false;
    }

    private static boolean endsWith(String s, String suffix) { return s != null && s.endsWith(suffix); }

    private static boolean isFallLike(Pack.Action node) {
        return node != null && (endsWith(node.className, ".Fall") || "Falling".equals(node.name));
    }

    private static boolean isJumpLike(Pack.Action node) {
        return node != null && (endsWith(node.className, ".Jump") || "Jumping".equals(node.name));
    }

    private boolean hasTarget(Frame frame) {
        return varNumber(frame, "TargetX") != null || varNumber(frame, "TargetY") != null;
    }

    private static boolean isDraggedAction(Pack.Action node) { return node != null && endsWith(node.className, ".Dragged"); }

    private static boolean isResistAction(Pack.Action node) { return node != null && endsWith(node.className, ".Regist"); }

    private static boolean isOffsetAction(Pack.Action node) {
        return node != null && (endsWith(node.className, ".Offset") || "Offset".equals(node.name));
    }

    private static boolean isScanMove(Pack.Action node) { return node != null && endsWith(node.className, ".ScanMove"); }

    private static boolean isInteract(Pack.Action node) { return node != null && endsWith(node.className, ".Interact"); }

    private interface Pred {
        boolean test(Pack.Action node);
    }

    private static final Pred INTERACT = MascotEngine::isInteract;

    private boolean actionTreeMatches(Pack.Action node, Pred predicate) {
        return actionTreeMatches(node, predicate, Collections.newSetFromMap(new IdentityHashMap<Pack.Action, Boolean>()));
    }

    private boolean actionTreeMatches(Pack.Action node, Pred predicate, java.util.Set<Pack.Action> visited) {
        if (!visited.add(node)) return false;
        if (predicate.test(node)) return true;
        if (node.isReference() && !node.referenceName.isEmpty()) {
            Pack.Action referenced = pack.actions.get(node.referenceName);
            if (referenced != null && actionTreeMatches(referenced, predicate, visited)) return true;
        }
        if (node.children != null) for (Pack.Action c : node.children) if (actionTreeMatches(c, predicate, visited)) return true;
        return false;
    }

    /** 行为从哪种边开始（Floor / Wall / Ceiling）；"*" 表示先挪开再说（Offset），null 表示不需要 */
    private String firstBorderOf(String behaviorName) {
        String cached = borderStartCache.get(behaviorName);
        if (cached != null) return cached == NONE ? null : cached;
        Pack.Action action = pack.actions.get(behaviorName);
        String result = action != null ? firstBorderInTree(action, Collections.newSetFromMap(new IdentityHashMap<Pack.Action, Boolean>())) : null;
        borderStartCache.put(behaviorName, result != null ? result : NONE);
        return result;
    }

    private String firstBorderInTree(Pack.Action node, java.util.Set<Pack.Action> visited) {
        if (!visited.add(node)) return null;
        String own = node.attributes.get("BorderType");
        if (own != null && !Js.trim(own).isEmpty()) return Js.trim(own);
        if (isOffsetAction(node)) return FLEXIBLE_BORDER;
        if (node.isReference() && !node.referenceName.isEmpty()) {
            Pack.Action referenced = pack.actions.get(node.referenceName);
            if (referenced != null) {
                String found = firstBorderInTree(referenced, visited);
                if (found != null) return found;
            }
        }
        if (node.children != null) {
            for (Pack.Action c : node.children) {
                String found = firstBorderInTree(c, visited);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String currentBorderAt(String lost) {
        String surface = surfaceAt(x, y, area());
        String border = "floor".equals(surface) ? "Floor" : "ceiling".equals(surface) ? "Ceiling" : surface != null ? "Wall" : null;
        if (border != null && !border.equals(lost)) return border;
        Scopes.Area area = area();
        if (!"Floor".equals(lost) && Scopes.borderIsOn(Scopes.BOTTOM, x, y, area)) return "Floor";
        if (!"Wall".equals(lost) && (Scopes.borderIsOn(Scopes.LEFT, x, y, area) || Scopes.borderIsOn(Scopes.RIGHT, x, y, area))) return "Wall";
        if (!"Ceiling".equals(lost) && Scopes.borderIsOn(Scopes.TOP, x, y, area)) return "Ceiling";
        return null;
    }

    private String surfaceAt(double px, double py, Scopes.Area area) {
        boolean floor = Scopes.borderIsOn(Scopes.BOTTOM, px, py, area);
        boolean left = Scopes.borderIsOn(Scopes.LEFT, px, py, area);
        boolean right = Scopes.borderIsOn(Scopes.RIGHT, px, py, area);
        boolean ceiling = Scopes.borderIsOn(Scopes.TOP, px, py, area);
        Pack.Action leaf = currentLeaf();
        String required = leaf != null ? leaf.borderType : null;
        if ("Floor".equals(required) && floor) return "floor";
        if ("Wall".equals(required) && (left || right)) return left ? "left-wall" : "right-wall";
        if ("Ceiling".equals(required) && ceiling) return "ceiling";
        if ("floor".equals(lastSurface) && floor) return "floor";
        if ("left-wall".equals(lastSurface) && left) return "left-wall";
        if ("right-wall".equals(lastSurface) && right) return "right-wall";
        if ("ceiling".equals(lastSurface) && ceiling) return "ceiling";
        if (floor) return "floor";
        if (left) return "left-wall";
        if (right) return "right-wall";
        if (ceiling) return "ceiling";
        return null;
    }

    // ================= 选下一个行为 =================

    private boolean canTravel(String behaviorName) {
        boolean[] s = travelStructure(behaviorName);
        if (s[0]) return true;
        if (!s[1]) return false;
        return partnerReachable();
    }

    private boolean[] travelStructure(String behaviorName) {
        boolean[] cached = travelCache.get(behaviorName);
        if (cached != null) return cached;
        Pack.Action action = pack.actions.get(behaviorName);
        boolean[] s = {
            action != null && actionTreeMatches(action, n -> "Move".equals(n.type)),
            action != null && actionTreeMatches(action, MascotEngine::isScanMove),
        };
        travelCache.put(behaviorName, s);
        return s;
    }

    private boolean needsPartnerToAct(String behaviorName) {
        Boolean cached = partnerCache.get(behaviorName);
        if (cached != null) return cached;
        Pack.Action action = pack.actions.get(behaviorName);
        boolean needs = action != null && actionTreeMatches(action, n -> {
            String aff = n.attributes.get("Affordance");
            return isScanMove(n) || isInteract(n) || (aff != null && !aff.isEmpty());
        });
        partnerCache.put(behaviorName, needs);
        return needs;
    }

    private double effectiveWeight(String behaviorName, double packFrequency) {
        AnimConfig.Resolved cfg = world.config;
        Double override = cfg.weightOverride(mascotName, behaviorName);
        if (override != null) return override;
        if (needsPartnerToAct(behaviorName)) {
            if (tickCount < interactionCooldownUntil) return 0;
            Double shared = cfg.partnerWeight(mascotName, world.live.size() > 1, partnerReachable());
            if (shared != null) return shared;
        }
        return packFrequency;
    }

    private boolean partnerReachable() {
        double scan = world.config.scanDistancePx(mascotName) / scale();
        for (MascotEngine other : world.live) {
            if (other == this) continue;
            if (Math.abs(other.y - y) > 1) continue;
            if (Math.abs(other.x - x) > scan) continue;
            return true;
        }
        return false;
    }

    private boolean behaviorUsable(Pack.Behavior behavior) {
        Boolean cached = behaviorUsability.get(behavior.name);
        if (cached != null) return cached;
        Pack.Action action = pack.actions.get(behavior.name);
        boolean usable = action != null && !actionTreeMatches(action, n -> endsWith(n.className, ".Breed"));
        behaviorUsability.put(behavior.name, usable);
        return usable;
    }

    private void guardStall(Frame frame) {
        if (frame == null) return;
        if (frame.progressX == x && frame.progressY == y) {
            frame.stallTicks++;
            if (frame.stallTicks >= STALL_LIMIT_TICKS) popActionStack();
            return;
        }
        frame.progressX = x;
        frame.progressY = y;
        frame.stallTicks = 0;
    }

    private double scaledTicks(double ticks) {
        return Math.max(1, Js.round(ticks * world.config.durationScale(mascotName)));
    }

    private void clampTravelTarget(Map<String, Object> resolved) {
        double limit = world.config.maxTravelPx(mascotName) / scale();
        if (limit <= 0) return;
        Scopes.Area area = area();
        Object tx = resolved.get("TargetX");
        if (!Js.finite(tx)) return;
        double targetX = (Double) tx;
        if (targetX <= area.left + 0.5 || targetX >= area.right - 0.5) return;
        resolved.put("TargetX", Math.max(x - limit, Math.min(x + limit, targetX)));
    }

    private void updatePhysics() {
        Pack.Action leaf = currentLeaf();
        if (leaf == null) return;
        if (leaf.borderType != null && !borderTypeSatisfied(leaf.borderType)) {
            String here = currentBorderAt(leaf.borderType);
            if (here != null) {
                onBehaviorEnd("border-change:" + leaf.borderType + "->" + here);
                return;
            }
            setBehavior("Fall", "border-lost:" + leaf.borderType, ANY_ID);
            return;
        }
        Frame frame = top();
        Pack.Pose pose = currentPose();
        Double targetX = varNumber(frame, "TargetX");
        Double targetY = varNumber(frame, "TargetY");

        if (isScanMove(leaf)) {
            Broadcast.Client handle = frame != null ? frame.client : null;
            if (handle == null || !handle.connected()) return;
            Broadcast.Server target = handle.target();
            if (target == null) return;
            double tx = target.ax;
            double beforeX = x;
            if (pose != null && pose.vx != 0) {
                lookRight = pose.vx > 0 ? tx < x : tx > x;
                x += (lookRight ? -1 : 1) * pose.vx;
            }
            clampToWorkArea();
            boolean passed = (beforeX <= tx && x >= tx) || (beforeX >= tx && x <= tx);
            if (passed) {
                x = tx;
                handle.notifyArrival();
                interaction = handle.interaction();
                String next = interaction.behavior();
                setBehavior(!next.isEmpty() ? next : "StandUp", "interaction-client", ANY_ID);
                return;
            }
            guardStall(frame);
            return;
        }

        if (isFallLike(leaf)) {
            Scopes.Area area = area();
            boolean onFloor = Scopes.borderIsOn(Scopes.BOTTOM, x, y, area);
            boolean onCeiling = Scopes.borderIsOn(Scopes.TOP, x, y, area);
            boolean onWall = Scopes.borderIsOn(Scopes.LEFT, x, y, area) || Scopes.borderIsOn(Scopes.RIGHT, x, y, area);
            boolean landingOnCeiling = onCeiling && !"Fall".equals(currentBehavior != null ? currentBehavior.name : null);
            if (onFloor || onWall || landingOnCeiling) {
                vx = vy = 0;
                if (onFloor) y = area().bottom;
                popActionStack();
                return;
            }
            Double rx = evaluateNumber(leaf.attributes.get("RegistanceX"), frame);
            double resistanceX = rx != null ? rx : world.resistanceX;
            Double ry = evaluateNumber(leaf.attributes.get("RegistanceY"), frame);
            double resistanceY = ry != null ? ry : world.resistanceY;
            Double g = evaluateNumber(leaf.attributes.get("Gravity"), frame);
            double gravity = g != null ? g : world.gravity;
            vx -= vx * resistanceX;
            vy += gravity - vy * resistanceY;
            x += vx;
            y += vy;
            clampToWorkArea();
            return;
        }
        if (isJumpLike(leaf) && targetX != null && targetY != null) {
            double dx = targetX - x;
            double dy = targetY - y - Math.abs(dx);
            double distance = Js.hypot(dx, dy);
            Double sp = evaluateNumber(leaf.attributes.get("VelocityParam"), frame);
            double speed = sp != null ? sp : 20;
            lookRight = dx > 0;
            if (distance <= speed) {
                x = targetX;
                y = targetY;
                popActionStack();
                return;
            }
            x += speed * dx / distance;
            y += speed * dy / distance;
            guardStall(frame);
            return;
        }

        boolean translates = pose != null && (pose.vx != 0 || pose.vy != 0);
        boolean moves = pose != null && ("Move".equals(leaf.type) || "Animate".equals(leaf.type) || translates);
        if (moves) {
            double beforeX = x, beforeY = y;
            if (targetX != null) {
                if (pose.vx > 0) lookRight = targetX < x;
                else if (pose.vx < 0) lookRight = targetX > x;
            }
            if (targetY != null) {
                if (borderIsOn(Scopes.LEFT)) lookRight = false;
                if (borderIsOn(Scopes.RIGHT)) lookRight = true;
            }
            x += (lookRight ? -1 : 1) * pose.vx;
            y += pose.vy;
            if (targetX != null && ((beforeX <= targetX && x >= targetX) || (beforeX >= targetX && x <= targetX))) {
                x = targetX;
                popActionStack();
                return;
            }
            if (targetY != null && ((beforeY <= targetY && y >= targetY) || (beforeY >= targetY && y <= targetY))) {
                y = targetY;
                popActionStack();
                return;
            }
        }
        clampToWorkArea();
        Pack.Action clinging = currentLeaf();
        if (clinging != null && "Wall".equals(clinging.borderType)) {
            if (borderIsOn(Scopes.LEFT)) lookRight = false;
            if (borderIsOn(Scopes.RIGHT)) lookRight = true;
        }
        if (targetX != null || targetY != null || translates || "Move".equals(leaf.type)) guardStall(frame);
    }

    private void clampToWorkArea() {
        Scopes.Area area = area();
        x = Math.max(area.left, Math.min(area.right, x));
        y = Math.max(area.top, Math.min(area.bottom, y));
    }

    private static final class Candidate {
        final String name;
        final int id;
        final double frequency;

        Candidate(String name, int id, double frequency) {
            this.name = name;
            this.id = id;
            this.frequency = frequency;
        }
    }

    private void onBehaviorEnd(String reason) {
        AnimConfig.Resolved cfg = world.config;
        List<Candidate> candidates = new ArrayList<>();
        List<Pack.BehaviorRef> next = currentBehavior != null ? currentBehavior.nextBehaviors : Collections.<Pack.BehaviorRef>emptyList();
        boolean forcePool = cfg.exitsToPool(mascotName, currentBehavior != null ? currentBehavior.name : null);
        if (!forcePool) {
            for (Pack.BehaviorRef reference : next) {
                if (!evaluateCondition(reference.condition, null)) continue;
                if (!cfg.allows(mascotName, reference.name)) continue;
                Pack.Behavior behavior = findBehavior(reference.name, ANY_ID);
                if (behavior != null && !behaviorUsable(behavior)) continue;
                double frequency = effectiveWeight(reference.name, reference.frequency);
                if (frequency <= 0) continue;
                candidates.add(new Candidate(reference.name, reference.id, frequency));
            }
        }
        if (candidates.isEmpty() || currentBehavior == null || currentBehavior.addNext) {
            for (Pack.Behavior behavior : pack.behaviors) {
                Double override = cfg.weightOverride(mascotName, behavior.name);
                if (override != null ? override <= 0 : behavior.frequency <= 0) continue;
                if (!cfg.allows(mascotName, behavior.name)) continue;
                if (!evaluateCondition(behavior.condition, null) || !behaviorUsable(behavior)) continue;
                double frequency = effectiveWeight(behavior.name, behavior.frequency);
                if (frequency <= 0) continue;
                candidates.add(new Candidate(behavior.name, behavior.id, frequency));
            }
        }
        if (candidates.isEmpty()) {
            Pack.Behavior idle = findBehavior("StandUp", ANY_ID);
            if (idle == null && !pack.behaviors.isEmpty()) idle = pack.behaviors.get(0);
            if (idle != null) setBehavior(idle.name, "no-candidates", ANY_ID);
            return;
        }
        List<Candidate> pool = candidates;
        if (wantMovement) {
            wantMovement = false;
            List<Candidate> travel = new ArrayList<>();
            for (Candidate c : candidates) if (canTravel(c.name)) travel.add(c);
            if (!travel.isEmpty()) pool = travel;
        }
        String standingOn = currentBorderAt(null);
        if (standingOn != null) {
            List<Candidate> runnable = new ArrayList<>();
            for (Candidate c : pool) {
                String start = firstBorderOf(c.name);
                if (start == null || start.equals(FLEXIBLE_BORDER) || start.equals(standingOn)) runnable.add(c);
            }
            if (!runnable.isEmpty()) pool = runnable;
        }
        double total = 0;
        for (Candidate c : pool) total = total + Math.max(0, c.frequency);
        if (total <= 0) {
            setBehavior(pool.get(0).name, reason, pool.get(0).id);
            return;
        }
        double random = world.rng.next() * total;
        for (Candidate c : pool) {
            random -= c.frequency;
            if (random <= 0) {
                setBehavior(c.name, reason, c.id);
                return;
            }
        }
        setBehavior(candidates.get(0).name, reason, candidates.get(0).id);
    }
}
