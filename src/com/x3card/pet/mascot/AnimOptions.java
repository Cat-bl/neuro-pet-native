/*
 * 桌宠引擎（web-mascot 的 Java 移植），GPL-3.0-or-later，见 Js.java 开头
 */
package com.x3card.pet.mascot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动画配置的一层（AnimationConfig.ts 的 MascotAnimationOptions）。字段为 null 表示这一层不改。
 * 位置类的值（zone、spawn.x）可以是 Double（页面像素）或 String（"40%"、"left"、"right"、"top"、"bottom"、"center"）。
 */
public final class AnimOptions {
    public List<String> allowBehaviors, denyBehaviors, pacerExemptBehaviors, exitToPoolBehaviors;
    public Map<String, Double> behaviorWeights;
    /** 每个动作的停留时长（tick）：{n} 或 {min, max} */
    public Map<String, int[]> actionDurations;
    public Double durationScale, scale;
    public Integer forcedSwitchTicks, interactionCooldownTicks, maxBehaviorTicks;
    public Double maxTravelPx, scanDistancePx;
    public Double partnerAlone, partnerWithPartner, partnerReachable;
    /** 活动范围（页面像素或关键字），宠物的地板、墙、天花板 */
    public Object zoneLeft, zoneRight, zoneTop, zoneBottom;
    /** 出生位置："top"（默认，从上面掉下来）或 "floor" */
    public String spawnVertical;
    public Object spawnX;
    public Double spawnStaggerPx, spawnTopOffset;

    public AnimOptions scale(double v) { scale = v; return this; }
    public AnimOptions zoneBottom(Object v) { zoneBottom = v; return this; }
    public AnimOptions zoneRight(Object v) { zoneRight = v; return this; }
    public AnimOptions zoneLeft(Object v) { zoneLeft = v; return this; }
    public AnimOptions zoneTop(Object v) { zoneTop = v; return this; }

    // ---------------- 校验（normalizeOptions） ----------------
    private static final double MIN_SCALE = 0.05, MAX_SCALE = 20, MIN_SPRITE_SCALE = 0.2, MAX_SPRITE_SCALE = 4;
    private static final int MAX_COOLDOWN_TICKS = 3000;
    private static final double MAX_TICKS = 1_000_000, MAX_PIXELS = 100_000;

    private static boolean ok(Double v) { return v != null && !v.isNaN() && !v.isInfinite(); }

    private static Integer ticks(Number v, double min) {
        if (v == null) return null;
        Double d = v.doubleValue();
        if (!ok(d)) return null;
        return (int) Js.round(Math.min(MAX_TICKS, Math.max(min, d)));
    }

    private static Double pixels(Double v, double min, double max) {
        return ok(v) ? Math.min(max, Math.max(min, v)) : null;
    }

    private static Object metric(Object v) {
        if (v instanceof Number) {
            Double d = ((Number) v).doubleValue();
            return ok(d) ? d : null;
        }
        if (v instanceof String && !Js.trim((String) v).isEmpty()) return Js.trim((String) v);
        return null;
    }

    private static List<String> names(List<String> v) {
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String s : v) if (s != null && !Js.trim(s).isEmpty()) out.add(Js.trim(s));
        return out.isEmpty() && !v.isEmpty() ? null : out;
    }

    private static Double weight(Double v) {
        return ok(v) && v >= 0 ? Math.min(MAX_TICKS, v) : null;
    }

    /** 按 TS 的规则校验、夹到范围里（不合法的字段丢掉） */
    AnimOptions normalized() {
        AnimOptions o = new AnimOptions();
        o.allowBehaviors = names(allowBehaviors);
        o.denyBehaviors = names(denyBehaviors);
        if (behaviorWeights != null) {
            Map<String, Double> m = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : behaviorWeights.entrySet()) {
                String k = e.getKey() == null ? "" : Js.trim(e.getKey());
                Double w = weight(e.getValue());
                if (k.isEmpty() || w == null) continue;
                m.put(k, w);
            }
            if (!m.isEmpty()) o.behaviorWeights = m;
        }
        if (actionDurations != null) {
            Map<String, int[]> m = new LinkedHashMap<>();
            for (Map.Entry<String, int[]> e : actionDurations.entrySet()) {
                String k = e.getKey() == null ? "" : Js.trim(e.getKey());
                int[] r = e.getValue();
                if (k.isEmpty() || r == null || r.length == 0) continue;
                Integer low = ticks(r[0], 1), high = r.length > 1 ? ticks(r[1], 1) : null;
                if (low == null) continue;
                if (high == null) high = low;
                if (high < low) { Integer t = low; low = high; high = t; }
                m.put(k, low.equals(high) ? new int[]{low} : new int[]{low, high});
            }
            if (!m.isEmpty()) o.actionDurations = m;
        }
        if (ok(durationScale)) o.durationScale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, durationScale));
        if (ok(scale)) o.scale = Math.min(MAX_SPRITE_SCALE, Math.max(MIN_SPRITE_SCALE, scale));
        o.forcedSwitchTicks = ticks(forcedSwitchTicks, 0);
        o.pacerExemptBehaviors = names(pacerExemptBehaviors);
        o.exitToPoolBehaviors = names(exitToPoolBehaviors);
        Integer cd = ticks(interactionCooldownTicks, 0);
        if (cd != null) o.interactionCooldownTicks = Math.min(MAX_COOLDOWN_TICKS, cd);
        o.maxBehaviorTicks = ticks(maxBehaviorTicks, 0);
        o.maxTravelPx = pixels(maxTravelPx, 0, MAX_PIXELS);
        o.partnerAlone = weight(partnerAlone);
        o.partnerWithPartner = weight(partnerWithPartner);
        o.partnerReachable = weight(partnerReachable);
        o.scanDistancePx = pixels(scanDistancePx, 16, 4096);
        o.zoneLeft = metric(zoneLeft);
        o.zoneRight = metric(zoneRight);
        o.zoneTop = metric(zoneTop);
        o.zoneBottom = metric(zoneBottom);
        if (spawnVertical != null) {
            String v = Js.trim(spawnVertical).toLowerCase();
            if (v.equals("top") || v.equals("floor")) o.spawnVertical = v;
        }
        o.spawnX = metric(spawnX);
        o.spawnStaggerPx = pixels(spawnStaggerPx, 0, MAX_PIXELS);
        o.spawnTopOffset = pixels(spawnTopOffset, 0, MAX_PIXELS);
        return o;
    }

    private static <T> T or(T a, T b) { return a != null ? a : b; }

    private static <V> Map<String, V> mergeMap(Map<String, V> a, Map<String, V> b) {
        Map<String, V> m = new LinkedHashMap<>();
        if (a != null) m.putAll(a);
        if (b != null) m.putAll(b);
        return m;
    }

    /** mergeOptions：override 有的字段盖住 base；表类字段按键合并 */
    static AnimOptions merge(AnimOptions base, AnimOptions o) {
        AnimOptions m = new AnimOptions();
        m.allowBehaviors = or(o.allowBehaviors, base.allowBehaviors);
        m.denyBehaviors = or(o.denyBehaviors, base.denyBehaviors);
        m.behaviorWeights = mergeMap(base.behaviorWeights, o.behaviorWeights);
        m.actionDurations = mergeMap(base.actionDurations, o.actionDurations);
        m.durationScale = or(o.durationScale, base.durationScale);
        m.scale = or(o.scale, base.scale);
        m.forcedSwitchTicks = or(o.forcedSwitchTicks, base.forcedSwitchTicks);
        m.pacerExemptBehaviors = or(o.pacerExemptBehaviors, base.pacerExemptBehaviors);
        m.exitToPoolBehaviors = or(o.exitToPoolBehaviors, base.exitToPoolBehaviors);
        m.interactionCooldownTicks = or(o.interactionCooldownTicks, base.interactionCooldownTicks);
        m.maxBehaviorTicks = or(o.maxBehaviorTicks, base.maxBehaviorTicks);
        m.maxTravelPx = or(o.maxTravelPx, base.maxTravelPx);
        m.partnerAlone = or(o.partnerAlone, base.partnerAlone);
        m.partnerWithPartner = or(o.partnerWithPartner, base.partnerWithPartner);
        m.partnerReachable = or(o.partnerReachable, base.partnerReachable);
        m.scanDistancePx = or(o.scanDistancePx, base.scanDistancePx);
        m.zoneLeft = or(o.zoneLeft, base.zoneLeft);
        m.zoneRight = or(o.zoneRight, base.zoneRight);
        m.zoneTop = or(o.zoneTop, base.zoneTop);
        m.zoneBottom = or(o.zoneBottom, base.zoneBottom);
        m.spawnVertical = or(o.spawnVertical, base.spawnVertical);
        m.spawnX = or(o.spawnX, base.spawnX);
        m.spawnStaggerPx = or(o.spawnStaggerPx, base.spawnStaggerPx);
        m.spawnTopOffset = or(o.spawnTopOffset, base.spawnTopOffset);
        return m;
    }

    /** 引擎自带的默认配置（animationPreset.ts） */
    static AnimOptions builtin() {
        AnimOptions o = new AnimOptions();
        o.spawnTopOffset = 100.0;
        o.spawnStaggerPx = 180.0;
        o.forcedSwitchTicks = 200;
        o.pacerExemptBehaviors = Arrays.asList("SitWhileDanglingLegs");
        o.exitToPoolBehaviors = Arrays.asList("SitWhileDanglingLegs");
        o.maxBehaviorTicks = 1100;
        o.behaviorWeights = new LinkedHashMap<>();
        o.behaviorWeights.put("SitWhileDanglingLegs", 150.0);
        o.behaviorWeights.put("SitAndFaceMouse", 8.0);
        return o.normalized();
    }
}
