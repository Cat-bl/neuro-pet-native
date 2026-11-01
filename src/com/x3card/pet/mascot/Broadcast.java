/*
 * 桌宠引擎（web-mascot 的 Java 移植），GPL-3.0-or-later，见 Js.java 开头
 */
package com.x3card.pet.mascot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两只宠物互动（Broadcast.ts 的移植，libshijima 的 broadcast/）：
 * 一只的动作带 Affordance 就广播自己；另一只的 ScanMove 找附近的广播走过去，到了两边一起换成约好的行为。
 */
final class Broadcast {
    private Broadcast() {}

    /** libshijima broadcast::server_state */
    static final class Server {
        double ax, ay;
        String clientBehavior = "", serverBehavior = "";
        private boolean finalized, available = true, metUp;
        private boolean[] shared;

        Server(double x, double y) {
            ax = x;
            ay = y;
        }

        boolean active() { return !finalized; }

        boolean isAvailable() { return active() && available; }

        /** 已经有搭档认领、正在走过来 */
        boolean isClaimed() { return active() && !available && !metUp; }

        boolean didMeetUp() { return metUp; }

        void updateAnchor(double x, double y) {
            if (active()) {
                ax = x;
                ay = y;
            }
        }

        void notifyArrival() {
            metUp = true;
            finalized = true;
        }

        void finalizeServer() { finalized = true; }

        void setAvailable(boolean v) { available = v; }

        Interaction interaction() {
            if (shared == null) shared = new boolean[]{true};
            return new Interaction(shared, serverBehavior);
        }

        boolean[] ongoingPoint() {
            if (shared == null) shared = new boolean[]{true};
            return shared;
        }
    }

    /** libshijima broadcast::interaction：两边共看的一个"还在进行"标记 */
    static final class Interaction {
        boolean started;
        private boolean[] shared;
        private String name;

        Interaction() { this(null, ""); }

        Interaction(boolean[] shared, String behavior) {
            this.shared = shared;
            this.name = behavior;
        }

        boolean available() { return shared != null; }

        boolean ongoing() { return shared != null && shared[0]; }

        String behavior() { return name; }

        void finalizeInteraction() {
            if (shared != null) shared[0] = false;
            shared = null;
            name = "";
            started = false;
        }
    }

    /** libshijima broadcast::client：一个 ScanMove 动作连上的广播 */
    static final class Client {
        private Server server;

        Client(Server server) { this.server = server; }

        boolean connected() { return server != null && server.active(); }

        Server target() { return server; }

        void notifyArrival() {
            if (server != null) server.notifyArrival();
        }

        Interaction interaction() {
            return new Interaction(server != null ? server.ongoingPoint() : null, server != null ? server.clientBehavior : "");
        }

        void finalizeClient() {
            if (server != null) server.setAvailable(true);
            server = null;
        }
    }

    /** libshijima broadcast::manager：同一个 World 里的宠物共用 */
    static final class Manager {
        private final Map<String, List<Server>> servers = new LinkedHashMap<>();

        void prune() {
            Iterator<Map.Entry<String, List<Server>>> it = servers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, List<Server>> e = it.next();
                List<Server> live = liveOf(e.getValue());
                if (live.isEmpty()) it.remove();
                else if (live.size() != e.getValue().size()) e.setValue(live);
            }
        }

        private static List<Server> liveOf(List<Server> list) {
            List<Server> live = new ArrayList<>(list.size());
            for (Server s : list) if (s.active()) live.add(s);
            return live;
        }

        Server startBroadcast(String affordance, double x, double y) {
            Server s = new Server(x, y);
            List<Server> list = servers.get(affordance);
            if (list != null) list.add(s);
            else {
                list = new ArrayList<>();
                list.add(s);
                servers.put(affordance, list);
            }
            return s;
        }

        Client tryConnect(String affordance, double x, double y, String clientBehavior, String serverBehavior, double maxDistance) {
            List<Server> list = servers.get(affordance);
            if (list == null || list.isEmpty()) return null;
            List<Server> live = liveOf(list);
            servers.put(affordance, live);
            Server nearest = null;
            double nearestDistance = maxDistance;
            for (Server c : live) {
                if (!c.isAvailable()) continue;
                if (Math.abs(y - c.ay) > 1) continue;
                double d = Math.abs(x - c.ax);
                if (d > maxDistance) continue;
                if (nearest == null || d < nearestDistance) {
                    nearest = c;
                    nearestDistance = d;
                }
            }
            if (nearest == null) return null;
            nearest.clientBehavior = clientBehavior;
            nearest.serverBehavior = serverBehavior;
            nearest.setAvailable(false);
            return new Client(nearest);
        }
    }
}
