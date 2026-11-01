/*
 * 桌宠引擎（web-mascot 的 Java 移植），GPL-3.0-or-later，见 Js.java 开头
 */
package com.x3card.pet.mascot;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动画配置（AnimationConfig.ts 的移植）：引擎自带的默认配置（可关）→ defaults → 系统要求减少动效时的那层 → 按宠物名的那层。
 * 对应网页版 MascotEngine.setAnimationConfig({ version: 1, defaults: {...}, mascots: {...} })。
 */
public final class AnimConfig {
    public boolean ignoreBuiltin;
    public AnimOptions defaults;
    public AnimOptions reducedMotion;
    public boolean respectReducedMotion = true;
    public final Map<String, AnimOptions> mascots = new LinkedHashMap<>();

    public AnimConfig() {}

    public AnimConfig(AnimOptions defaults) { this.defaults = defaults; }

    // ---------------- 生效的配置 ----------------
    private static final AnimOptions BUILTIN = AnimOptions.builtin();
    private static final AnimOptions EMPTY = new AnimOptions();

    /** 校验过的副本（应用之后再改字段不影响） */
    static final class Resolved {
        private final boolean builtin;
        private final AnimOptions defaults, reduced;
        private final boolean respectReduced;
        private final Map<String, AnimOptions> perMascot = new HashMap<>();
        private boolean reducedActive;
        private final Map<String, AnimOptions> cache = new HashMap<>();

        Resolved(AnimConfig file) {
            builtin = file == null || !file.ignoreBuiltin;
            defaults = file != null && file.defaults != null ? file.defaults.normalized() : EMPTY;
            reduced = file != null && file.reducedMotion != null ? file.reducedMotion.normalized() : EMPTY;
            respectReduced = file == null || file.respectReducedMotion;
            if (file != null) {
                for (Map.Entry<String, AnimOptions> e : file.mascots.entrySet()) {
                    String k = e.getKey() == null ? "" : Js.trim(e.getKey());
                    if (k.isEmpty() || e.getValue() == null) continue;
                    perMascot.put(k, e.getValue().normalized());
                }
            }
        }

        void setReducedMotion(boolean active) {
            active = active && respectReduced;
            if (reducedActive == active) return;
            reducedActive = active;
            cache.clear();
        }

        AnimOptions of(String mascot) {
            AnimOptions o = cache.get(mascot);
            if (o != null) return o;
            AnimOptions m = AnimOptions.merge(builtin ? BUILTIN : EMPTY, defaults);
            m = AnimOptions.merge(m, reducedActive ? reduced : EMPTY);
            AnimOptions own = perMascot.get(mascot);
            m = AnimOptions.merge(m, own != null ? own : EMPTY);
            cache.put(mascot, m);
            return m;
        }

        boolean allows(String mascot, String behavior) {
            AnimOptions o = of(mascot);
            if (o.denyBehaviors != null && o.denyBehaviors.contains(behavior)) return false;
            return o.allowBehaviors == null || o.allowBehaviors.isEmpty() || o.allowBehaviors.contains(behavior);
        }

        boolean denies(String mascot, String behavior) {
            AnimOptions o = of(mascot);
            return o.denyBehaviors != null && o.denyBehaviors.contains(behavior);
        }

        /** 权重覆盖，没有返回 null */
        Double weightOverride(String mascot, String behavior) {
            return of(mascot).behaviorWeights.get(behavior);
        }

        double durationScale(String mascot) {
            Double v = of(mascot).durationScale;
            return v != null ? v : 1;
        }

        double mascotScale(String mascot) {
            Double v = of(mascot).scale;
            return v != null ? v : 1;
        }

        int forcedSwitchTicks(String mascot) {
            Integer v = of(mascot).forcedSwitchTicks;
            return v != null ? v : 0;
        }

        boolean pacerApplies(String mascot, String behavior) {
            if (behavior == null || behavior.isEmpty()) return true;
            AnimOptions o = of(mascot);
            return o.pacerExemptBehaviors == null || !o.pacerExemptBehaviors.contains(behavior);
        }

        boolean exitsToPool(String mascot, String behavior) {
            if (behavior == null || behavior.isEmpty()) return false;
            AnimOptions o = of(mascot);
            return o.exitToPoolBehaviors != null && o.exitToPoolBehaviors.contains(behavior);
        }

        int interactionCooldown(String mascot) {
            Integer v = of(mascot).interactionCooldownTicks;
            return v != null ? v : 0;
        }

        int maxBehaviorTicks(String mascot) {
            Integer v = of(mascot).maxBehaviorTicks;
            return v != null ? v : 0;
        }

        double maxTravelPx(String mascot) {
            Double v = of(mascot).maxTravelPx;
            return v != null ? v : 0;
        }

        /** 需要搭档的行为的权重；null 表示用 pack 自己的 Frequency */
        Double partnerWeight(String mascot, boolean withPartner, boolean reachable) {
            AnimOptions o = of(mascot);
            if (reachable && o.partnerReachable != null) return o.partnerReachable;
            return withPartner ? o.partnerWithPartner : o.partnerAlone;
        }

        double scanDistancePx(String mascot) {
            Double v = of(mascot).scanDistancePx;
            return v != null ? v : 256;
        }

        /** 某个动作配置的停留时长（tick），没配置返回 -1；有范围时随机取一次 */
        int durationTicks(String mascot, String action, World.Random rng) {
            if (action == null || action.isEmpty()) return -1;
            int[] r = of(mascot).actionDurations.get(action);
            if (r == null) return -1;
            if (r.length == 1) return r[0];
            int low = r[0], high = r[1];
            if (high <= low) return low;
            return (int) (low + Js.round(rng.next() * (high - low)));
        }
    }
}
