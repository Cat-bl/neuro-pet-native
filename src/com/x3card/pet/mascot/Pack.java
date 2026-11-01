/*
 * 桌宠引擎（web-mascot 的 Java 移植），GPL-3.0-or-later，见 Js.java 开头
 */
package com.x3card.pet.mascot;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.parsers.SAXParserFactory;

/**
 * 解析好的一个宠物包（XMLParser.ts、types.ts、Hotspot.ts 的移植），几只同一个包的宠物可以共用。
 * 属性按文件里的顺序保存（求值顺序、随机数的先后跟着它走，所以不用 DOM：DOM 的属性表按名字排序）。
 */
public final class Pack {
    /** 应用提供的精灵图信息 */
    public interface Images {
        /** 图片（pack 里写的名字，如 "/shime1.png"）的 {宽, 高}；不知道时返回 null */
        int[] size(String image);

        /** (x, y) 处是不是不透明；不知道时返回 null（整个方框都算，同网页版读不到像素时） */
        default Boolean opaque(String image, int x, int y) { return null; }
    }

    public static final class Pose {
        public final String image;
        public final double anchorX, anchorY;
        public final double vx, vy;
        public final int duration;

        Pose(String image, double anchorX, double anchorY, double vx, double vy, int duration) {
            this.image = image;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.vx = vx;
            this.vy = vy;
            this.duration = duration;
        }
    }

    public static final class Hotspot {
        public final boolean ellipse;
        public final double ox, oy, sx, sy;
        public final String behavior;

        Hotspot(boolean ellipse, double ox, double oy, double sx, double sy, String behavior) {
            this.ellipse = ellipse;
            this.ox = ox;
            this.oy = oy;
            this.sx = sx;
            this.sy = sy;
            this.behavior = behavior;
        }

        /** libshijima hotspot::point_inside()：椭圆按 Size 当半轴算 */
        boolean contains(double px, double py) {
            if (sx <= 0 || sy <= 0) return false;
            if (ellipse) {
                double nx = (px - (ox + sx / 2)) / sx;
                double ny = (py - (oy + sy / 2)) / sy;
                return nx * nx + ny * ny < 1;
            }
            return px >= ox && px <= ox + sx && py >= oy && py <= oy + sy;
        }
    }

    static Hotspot hotspotAt(List<Hotspot> list, double px, double py) {
        if (list == null) return null;
        Hotspot found = null;
        for (Hotspot h : list) if (h.contains(px, py)) found = h;
        return found;
    }

    static final class Anim {
        final String condition;
        final List<Pose> poses;
        final List<Hotspot> hotspots;

        Anim(String condition, List<Pose> poses, List<Hotspot> hotspots) {
            this.condition = condition;
            this.poses = poses;
            this.hotspots = hotspots;
        }
    }

    /** ActionNode：Stay / Move / Animate / Embedded / Sequence / Select / ActionReference */
    static final class Action {
        String name;
        String type;
        String className;
        String borderType;
        String condition;
        boolean loop;
        List<Pose> poses = Collections.emptyList();
        List<Anim> animations = Collections.emptyList();
        List<Action> children;
        String referenceName;
        /** 全部 XML 属性，按文件里的顺序 */
        Map<String, String> attributes = Collections.emptyMap();

        boolean isReference() { return "ActionReference".equals(type); }
    }

    static final class BehaviorRef {
        final String name;
        final double frequency;
        final String condition;
        int id = -1;

        BehaviorRef(String name, double frequency, String condition) {
            this.name = name;
            this.frequency = frequency;
            this.condition = condition;
        }
    }

    static final class Behavior {
        final String name;
        final int id;
        final double frequency;
        final boolean hidden;
        final String condition;
        final List<BehaviorRef> nextBehaviors;
        final boolean addNext;

        Behavior(String name, int id, double frequency, boolean hidden, String condition, List<BehaviorRef> next, boolean addNext) {
            this.name = name;
            this.id = id;
            this.frequency = frequency;
            this.hidden = hidden;
            this.condition = condition;
            this.nextBehaviors = next;
            this.addNext = addNext;
        }
    }

    public final String name;
    final Map<String, Action> actions;
    final List<Behavior> behaviors;
    final Images images;

    private Pack(String name, Map<String, Action> actions, List<Behavior> behaviors, Images images) {
        this.name = name;
        this.actions = actions;
        this.behaviors = behaviors;
        this.images = images;
    }

    /**
     * 从内存里的文件解析一个宠物包。
     * @param name 包名（"Neuron"、"Eviling"），也是动画配置里按宠物区分的名字
     * @param files 包里的文本文件，至少要有 "actions.xml"、"behaviors.xml"
     * @param images 精灵图的尺寸（和不透明像素）
     */
    public static Pack load(String name, Map<String, String> files, Images images) {
        String actions = files.get("actions.xml"), behaviors = files.get("behaviors.xml");
        if (actions == null || behaviors == null) throw new IllegalArgumentException("加载配置失败: " + name + ".");
        El a = parse(XMLNS.matcher(actions).replaceAll(""), name + "/actions.xml");
        El b = parse(XMLNS.matcher(behaviors).replaceAll(""), name + "/behaviors.xml");
        return new Pack(name, parseActions(a), parseBehaviors(b), images);
    }

    /** 某个动作（含子动作、引用的动作）里会用到的全部图片名 */
    public List<String> imageNames() {
        LinkedHashMap<String, Boolean> set = new LinkedHashMap<>();
        for (Action a : actions.values()) collectImages(a, set);
        return new ArrayList<>(set.keySet());
    }

    private static void collectImages(Action node, Map<String, Boolean> out) {
        for (Pose p : node.poses) if (!p.image.isEmpty()) out.put(p.image, Boolean.TRUE);
        for (Anim an : node.animations) for (Pose p : an.poses) if (!p.image.isEmpty()) out.put(p.image, Boolean.TRUE);
        if (node.children != null) for (Action c : node.children) collectImages(c, out);
    }

    // ---------------- 一个只保留元素、属性顺序的小 DOM ----------------
    private static final Pattern XMLNS = Pattern.compile("xmlns=\".*?\"");

    static final class El {
        final String qName, localName;
        final Map<String, String> attrs = new LinkedHashMap<>();
        final List<El> children = new ArrayList<>();
        El parent;

        El(String qName) {
            this.qName = qName;
            int c = qName.indexOf(':');
            this.localName = c >= 0 ? qName.substring(c + 1) : qName;
        }

        String attr(String name) { return attrs.get(name); }

        /** getAttribute(name) || undefined：空字符串也当没有 */
        String attrOrNull(String name) {
            String v = attrs.get(name);
            return v == null || v.isEmpty() ? null : v;
        }
    }

    private static El parse(String xml, String label) {
        if (!xml.isEmpty() && xml.charAt(0) == '﻿') xml = xml.substring(1);
        final El doc = new El("#document");
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(false);
            f.setValidating(false);
            f.newSAXParser().parse(new InputSource(new StringReader(xml)), new DefaultHandler() {
                El cur = doc;

                @Override public void startElement(String uri, String localName, String qName, Attributes atts) {
                    El e = new El(qName != null && !qName.isEmpty() ? qName : localName);
                    for (int i = 0; i < atts.getLength(); i++) {
                        String n = atts.getQName(i);
                        if (n == null || n.isEmpty()) n = atts.getLocalName(i);
                        e.attrs.put(n, atts.getValue(i));
                    }
                    e.parent = cur;
                    cur.children.add(e);
                    cur = e;
                }

                @Override public void endElement(String uri, String localName, String qName) {
                    cur = cur.parent;
                }
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("配置 XML 无法解析: " + label + "。", e);
        }
        return doc;
    }

    /** 后代里本地名为 name 的元素（文档顺序，不含自己） */
    private static List<El> getTag(El node, String name) {
        List<El> out = new ArrayList<>();
        collect(node, name, out, false);
        if (out.isEmpty()) collect(node, name, out, true);
        return out;
    }

    private static void collect(El node, String name, List<El> out, boolean byQName) {
        for (El c : node.children) {
            if ((byQName ? c.qName : c.localName).equals(name)) out.add(c);
            collect(c, name, out, byQName);
        }
    }

    private static Map<String, Action> parseActions(El doc) {
        Map<String, Action> actions = new LinkedHashMap<>();
        for (El list : getTag(doc, "ActionList")) {
            for (El node : list.children) {
                String ln = node.localName;
                if (ln.equals("Action") || ln.endsWith(":Action")) {
                    Action action = parseActionNode(node);
                    if (action.name != null) actions.put(action.name, action);
                }
            }
        }
        return actions;
    }

    private static Action parseActionNode(El node) {
        String tag = node.localName;
        Action a = new Action();
        if (tag.equals("ActionReference") || tag.endsWith(":ActionReference")) {
            a.type = "ActionReference";
            a.referenceName = node.attr("Name") != null ? node.attr("Name") : "";
            a.condition = node.attrOrNull("Condition");
            a.attributes = new LinkedHashMap<>(node.attrs);
            return a;
        }
        String type = node.attrOrNull("Type");
        a.type = type != null ? type : "Stay";
        a.name = node.attrOrNull("Name");
        a.className = node.attrOrNull("Class");
        a.borderType = node.attrOrNull("BorderType");
        a.condition = node.attrOrNull("Condition");
        a.loop = "true".equals(node.attr("Loops")) || "true".equals(node.attr("Loop"));
        a.attributes = new LinkedHashMap<>(node.attrs);
        if (a.type.equals("Sequence") || a.type.equals("Select")) {
            a.children = new ArrayList<>();
            for (El child : node.children) {
                String ct = child.localName;
                if (ct.equals("Action") || ct.equals("ActionReference") || ct.endsWith("Action") || ct.endsWith("ActionReference")) {
                    a.children.add(parseActionNode(child));
                }
            }
        } else {
            List<Anim> anims = new ArrayList<>();
            for (El child : node.children) {
                if (!child.localName.equals("Animation")) continue;
                anims.add(new Anim(child.attrOrNull("Condition"), parsePoses(child), parseHotspots(child)));
            }
            a.animations = anims;
            a.poses = anims.isEmpty() ? Collections.<Pose>emptyList() : anims.get(0).poses;
        }
        return a;
    }

    private static double part(String[] parts, int i) {
        return i < parts.length ? Js.parseNumber(parts[i]) : Double.NaN;
    }

    private static boolean ok(double v) { return v == v && !Double.isInfinite(v); }

    private static List<Pose> parsePoses(El anim) {
        List<Pose> poses = new ArrayList<>();
        for (El p : getTag(anim, "Pose")) {
            String[] anchor = orElse(p.attrOrNull("ImageAnchor"), "0,0").split(",", -1);
            String[] vel = orElse(p.attrOrNull("Velocity"), "0,0").split(",", -1);
            double ax = part(anchor, 0), ay = part(anchor, 1), vx = part(vel, 0), vy = part(vel, 1);
            double duration = Js.parseNumber(orElse(p.attrOrNull("Duration"), "1"));
            if (!ok(ax) || !ok(ay) || !ok(vx) || !ok(vy) || !ok(duration)) continue;
            String image = p.attr("Image");
            poses.add(new Pose(image == null ? "" : image, ax, ay, vx, vy, (int) Math.max(1, Js.round(duration))));
        }
        return poses;
    }

    private static List<Hotspot> parseHotspots(El anim) {
        List<Hotspot> out = new ArrayList<>();
        for (El h : getTag(anim, "Hotspot")) {
            String shape = Js.trim(orElse(h.attr("Shape"), ""));
            if (!shape.equals("Ellipse") && !shape.equals("Rectangle")) continue;
            String behavior = Js.trim(orElse(h.attr("Behavior"), ""));
            if (behavior.isEmpty()) continue;
            String[] origin = orElse(h.attr("Origin"), "").split(",", -1);
            String[] size = orElse(h.attr("Size"), "").split(",", -1);
            double ox = Js.parseFloat(origin[0]), oy = origin.length > 1 ? Js.parseFloat(origin[1]) : Double.NaN;
            double sx = Js.parseFloat(size[0]), sy = size.length > 1 ? Js.parseFloat(size[1]) : Double.NaN;
            if (!ok(ox) || !ok(oy) || !ok(sx) || !ok(sy) || sx <= 0 || sy <= 0) continue;
            out.add(new Hotspot(shape.equals("Ellipse"), ox, oy, sx, sy, behavior));
        }
        return out.isEmpty() ? null : out;
    }

    private static String orElse(String v, String fallback) { return v != null ? v : fallback; }

    private static List<Behavior> parseBehaviors(El doc) {
        List<Behavior> behaviors = new ArrayList<>();
        List<El> nodes = getTag(doc, "Behavior");
        for (int i = 0; i < nodes.size(); i++) {
            El node = nodes.get(i);
            String name = node.attr("Name");
            if (name == null || name.isEmpty()) continue;
            double fv = Js.parseNumber(orElse(node.attrOrNull("Frequency"), "0"));
            double frequency = ok(fv) ? Math.max(0, fv) : 0;
            boolean hidden = "true".equals(node.attr("Hidden"));
            String condition = null;
            El parent = node.parent;
            if (parent != null && (parent.localName.equals("Condition") || parent.qName.equals("Condition"))) {
                condition = parent.attrOrNull("Condition");
            }
            List<BehaviorRef> next = new ArrayList<>();
            boolean addNext = true;
            List<El> lists = getTag(node, "NextBehaviorList");
            if (!lists.isEmpty()) {
                El nl = lists.get(0);
                addNext = !"false".equals(nl.attr("Add"));
                for (El r : getTag(nl, "BehaviorReference")) {
                    double rf = Js.parseNumber(orElse(r.attrOrNull("Frequency"), "0"));
                    next.add(new BehaviorRef(orElse(r.attr("Name"), ""), ok(rf) ? Math.max(0, rf) : 0, r.attrOrNull("Condition")));
                }
            }
            behaviors.add(new Behavior(name, i, frequency, hidden, condition, next, addNext));
        }
        for (Behavior b : behaviors) {
            for (BehaviorRef ref : b.nextBehaviors) {
                for (Behavior c : behaviors) {
                    if (c.name.equals(ref.name) && c.frequency == ref.frequency) { ref.id = c.id; break; }
                }
            }
        }
        return behaviors;
    }
}
