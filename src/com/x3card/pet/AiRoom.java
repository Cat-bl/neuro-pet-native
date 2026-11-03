package com.x3card.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ListView;

import com.x3card.pet.ai.Json;
import com.x3card.pet.ai.PetChat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 聊天室（同网页版 ai-room.js）：对话记录像群聊一样看——车主在右，Neuro、Evil 带头像在左，车况事件、闲聊开场、查车况 / 操作车机 / 联网搜索是中间的灰字。
 * - 边说边更新：模型的回复流式到达时就显示出来，等模型开口时显示"正在输入"。
 * - 横屏占右半边（宠物的活动范围收到左半边、时间挪到左半边正中），竖屏小窗口占满整张卡片；输入栏放在最底下（Ai 摆），发完不收起。
 * - 内容是 Ai 的对话记录：车主的话（去掉 [对两只说] 前缀；事件、搜索结果显示成灰字）、宠物的回复（按 "N：/E：" 拆成一条条；只对一只说时另一只的话不显示）、
 *   调用工具（灰字，带结果）、只给聊天室看的（本地台词、没发出去的话、车主点的按钮）。相隔 5 分钟以上的消息之间显示时间。
 * - 气泡里的按钮（选项、"好 / 算了"、"去改设置"）聊天室里也有一份，和气泡里的走同一个处理（Ai.pressAt）。
 * - 只在打开时有视图：记录有变就整段重算（攒到下一帧），列表按需复用行；在最底下时跟着滚，往上翻着时出"有新消息"。关掉就拿掉整块
 */
final class AiRoom implements Frames.Client {
    /** 一条消息：t 时间、n 灰字、m 车主、p 宠物、d 正在输入、a 按钮、e 空 */
    static final class Item {
        char k;
        long t;
        String s, to = "both", who;
        boolean fail, cont, typing;
        final ArrayList<Piece> parts = new ArrayList<>();
        List<String> ask;
        List<Ai.AskItem> acts;
        Item(char k) { this.k = k; }
        Item copy() { Item x = new Item(k); x.t = t; x.s = s; x.to = to; x.who = who; x.fail = fail; x.parts.addAll(parts); x.ask = ask; x.acts = acts; return x; }
        boolean hasWords() { for (Piece p : parts) if (p.a != null || (p.s != null && !AiControl.trim(p.s.toString()).isEmpty())) return true; return false; }
    }
    /** 宠物一条里的一段：a 动作（显示成小标签），s 文字 */
    static final class Piece { String a; StringBuilder s; }

    private final Ai ai;
    private final App app;
    private final U u;
    private RoomView view;
    private boolean opened, dirty, pin = true, queued;
    private String liveText = "", liveTo = "both", liveFirst = "", typingWho = "";
    private List<Ai.AskItem> acts;
    private ArrayList<Item> hist = new ArrayList<>();
    private int shownLive;
    private static final Map<String, String> ACT = new HashMap<>();
    static { for (PetChat.Act a : PetChat.ACTS) ACT.put(a.id, a.names.get(0)); }

    AiRoom(Ai ai) { this.ai = ai; app = ai.app; u = app.u; }

    boolean isOpen() { return opened; }
    /** 对话记录变了（多了一条、清空了） */
    void update() { dirty = true; schedule(); }
    /** 正在说的这段（模型回复到目前为止的原文）；空 = 说完了 */
    void live(String text, String to, String first) {
        liveText = text == null ? "" : text;
        liveTo = to == null ? "both" : to;
        liveFirst = first == null ? "" : first;
        if (!liveText.isEmpty()) typingWho = "";
        schedule();
    }
    /** 正在等模型开口的那只（空 = 没在等） */
    void typing(String who) { typingWho = who == null ? "" : who; schedule(); }
    /** 气泡里的按钮（null = 收起了） */
    void actions(List<Ai.AskItem> list) { acts = list != null && !list.isEmpty() ? list : null; dirty = true; schedule(); }
    private void schedule() { if (opened && !queued) { queued = true; app.frames.add(this, 60); } }
    @Override public boolean frame(long now) { queued = false; render(); return false; }

    // ================= 开 / 关 =================
    void open() {
        if (opened) return;
        opened = true;
        view = new RoomView(ai.ctx);
        ai.host.addView(view, 0, new Layer.Lp()); // 输入栏画在聊天室上面
        dirty = true; pin = true; shownLive = 0;
        layout();
        view.animIn();
        render();
        view.toEnd();
        app.ui.roomOpen = true;
        app.ui.sync();
        app.clock.setRoom(true);
        zone();
        ai.layout();
    }
    void close() {
        if (!opened) return;
        opened = false;
        queued = false;
        app.frames.remove(this);
        if (view != null) { view.release(); ai.host.removeView(view); view = null; } // 关着不留一大片视图
        hist = new ArrayList<>();
        for (Ai.Msg m : ai.history) m.parsed = null;
        app.ui.roomOpen = false;
        app.ui.sync();
        app.clock.setRoom(false);
        zone();
        ai.roomClosed();
    }
    private final Runnable zoneR = this::zone;
    /** 横屏：宠物的右墙挪到聊天室左边（不钻到聊天室底下）；竖屏聊天室盖满，不用管 */
    private void zone() {
        float wall = opened && !u.portrait ? u.W / 2 : 0;
        app.zoneRight(wall > 0 ? wall : null);
        if (wall <= 0) return;
        ArrayList<Pet> out = new ArrayList<>();
        for (Pet p : app.livePets()) { PointF q = app.pos(p); if (q != null && q.x > wall - 30) out.add(p); } // 已经在右半边的：跑回左边来
        if (!out.isEmpty()) app.chaseTo(wall * (0.35f + app.rnd.nextFloat() * 0.3f), out);
    }
    /** 聊天室的框（像素）：横屏右半边，竖屏占满；高到可见区域的底边（键盘弹出时输入栏露在键盘上面） */
    RectF rect() {
        float W = u.px(u.W), V = u.px(u.visH);
        return u.portrait ? new RectF(0, 0, W, V) : new RectF(Math.round(W / 2), 0, W, V);
    }
    void layout() {
        if (!opened || view == null) return;
        RectF r = rect();
        Layer.place(view, r.left, r.top, r.width(), r.height());
        view.setPortrait(u.portrait);
        app.clock.setRoom(true);
        app.h.removeCallbacks(zoneR);
        app.h.postDelayed(zoneR, 50); // 拖中缝、横竖屏切换：右墙跟着聊天室走
    }

    // ================= 记录 → 一条条消息 =================
    private static final Pattern TAG = Pattern.compile("^\\[(对两只说|只对 Neuro 说|只对 Evil 说)\\][" + Ai.WSC + "]*");
    private static final Pattern EVENT = Pattern.compile("^\\[事件\\][" + Ai.WSC + "]*"), SRESULT = Pattern.compile("^\\[搜索结果\\]"), KEYWORD = Pattern.compile("关键词\"([^\"]*)\"");
    private static final Map<String, String> QUERY = new HashMap<>();
    static { QUERY.put("uiplus_vehicle_get", "查了车况"); QUERY.put("uiplus_media_get", "看了在放的歌"); QUERY.put("uiplus_media_getLyrics", "查了歌词"); }
    private static String toOf(String c) {
        Matcher g = TAG.matcher(c);
        if (!g.find()) return "both";
        String w = g.group(1);
        return w.equals("只对 Neuro 说") ? "Neuron" : w.equals("只对 Evil 说") ? "Eviling" : "both";
    }
    /** 事件的说明（以前的记录没存 v：从原文推） */
    private static String eventLabel(String c) {
        String s = EVENT.matcher(c).replaceFirst("");
        if (s.startsWith("有一会儿没人说话")) return "闲聊";
        if (s.startsWith("你们俩来一段小对话")) return "两只聊起来了";
        return Ai.head(s.split("[。！？\\n]", 2)[0], 40);
    }
    private static String searchLabel(String c) { Matcher m = KEYWORD.matcher(c); return m.find() ? "搜了“" + m.group(1) + "”" : "联网搜索"; }
    private static JSONObject resultOf(List<Ai.Msg> h, int i, String id) {
        for (int j = i + 1; j < h.size() && j < i + 12; j++) {
            Ai.Msg m = h.get(j);
            if ("t".equals(m.r) && id.equals(m.i)) { try { Object o = Json.parse(m.c); return o instanceof JSONObject ? (JSONObject) o : null; } catch (RuntimeException e) { return null; } }
        }
        return null;
    }
    private static JSONObject argsOf(String s) {
        try { Object o = Json.parse(s == null || s.isEmpty() ? "{}" : s); return o instanceof JSONObject ? (JSONObject) o : new JSONObject(); } catch (RuntimeException e) { return new JSONObject(); }
    }
    private static Item note(String s) { Item n = new Item('n'); n.s = s; return n; }
    /** 一个工具调用的灰字：做了什么 + 结果 */
    private String callLabel(JSONObject c, JSONObject res) {
        JSONObject a = argsOf(c.optString("args"));
        String name = c.optString("name"), s;
        if (name.equals("pet_web_search")) s = "搜了“" + Ai.head(a.opt("query") == null ? "" : String.valueOf(a.opt("query")), 30) + "”";
        else if (name.equals("pet_open_page")) { PetChat.Page p = PetChat.findPage(a.opt("page")); s = "打开了" + (p != null ? p.name : "") + "页"; }
        else if (QUERY.containsKey(name)) s = QUERY.get(name);
        else { // 车机操作：按 AiControl 的说明表说成"空调 开"（宿主的能力目录不在时也能说）
            AiControl.Op op = ai.ctl.toolOp(name, a);
            if (op.err != null) {
                op = new AiControl.Op();
                op.m = AiControl.methodOf(name);
                op.name = "";
                for (java.util.Iterator<String> it = a.keys(); it.hasNext(); ) op.a.add(a.opt(it.next()));
            }
            String l = ai.ctl.label(op);
            s = "操作：" + (l.isEmpty() || Ai.isWs(l.charAt(0)) ? op.m : l);
        }
        JSONObject err = res != null && !res.optBoolean("ok") ? res.optJSONObject("error") : null;
        String e = err != null ? err.optString("code") : "";
        return s + (e.equals("NEEDS_CONFIRM") ? "（等你确认）" : e.equals("REFUSED_DRIVING") ? "（开车时不能做）" : e.equals("NOT_EXECUTED") ? "（没做）" : !e.isEmpty() ? "（没做成）" : "");
    }
    /** 回复里的文字指令〔…〕：搜索、打开页面、车机操作 → 灰字 */
    private String opLabel(String raw) {
        Matcher sq = Ai.SEARCH.matcher(raw);
        if (sq.find()) return !sq.group(1).isEmpty() ? "搜了“" + Ai.head(Ai.QUOTES.matcher(sq.group(1)).replaceAll(""), 30) + "”" : "";
        Matcher pg = Ai.PAGE.matcher(raw);
        if (pg.find()) { PetChat.Page p = PetChat.findPage(pg.group(1)); return p != null ? "打开了" + p.name + "页" : ""; }
        AiControl.Op op = ai.ctl.parse(raw);
        return op != null ? "操作：" + ai.ctl.label(op) : "";
    }
    /** 宠物的一段回复 → 一条条消息（和气泡一样：每行开头 N：/E：换人、换行换一条；只对一只说时另一只的话不要） */
    private void petMsgs(String text, final String to, String first, final List<Item> out) {
        final String[] who = { !to.equals("both") ? to : first != null && !first.isEmpty() ? first : "Neuron" };
        final Item[] cur = { null }, last = { null };
        final boolean[] mute = { false };
        PetChat.Parser p = PetChat.parser(tok -> {
            switch (tok.t) {
                case "who": who[0] = tok.w; mute[0] = !to.equals("both") && !who[0].equals(to); cur[0] = null; return;
                case "br": cur[0] = null; return;
                case "ask": if (last[0] != null) last[0].ask = tok.list; return;
                case "op": { String n = opLabel(tok.raw); if (!n.isEmpty()) { out.add(note(n)); cur[0] = null; } return; }
            }
            if (mute[0]) return;
            if (cur[0] == null) { cur[0] = new Item('p'); cur[0].who = who[0]; out.add(cur[0]); last[0] = cur[0]; }
            if ("act".equals(tok.t)) {
                ArrayList<String> names = new ArrayList<>();
                for (String id : tok.list) names.add(ACT.containsKey(id) ? ACT.get(id) : id);
                Piece pc = new Piece(); pc.a = TextUtils.join("、", names); cur[0].parts.add(pc);
            } else if ("text".equals(tok.t)) {
                Piece q = cur[0].parts.isEmpty() ? null : cur[0].parts.get(cur[0].parts.size() - 1);
                if (q != null && q.s != null) q.s.append(tok.s);
                else { Piece pc = new Piece(); pc.s = new StringBuilder(tok.s); cur[0].parts.add(pc); }
            }
        });
        p.push(text);
        p.end();
    }
    private ArrayList<Item> items() {
        List<Ai.Msg> h = ai.history;
        ArrayList<Item> out = new ArrayList<>();
        long lastT = 0;
        String to = "both";
        for (int i = 0; i < h.size(); i++) {
            Ai.Msg m = h.get(i);
            if ("t".equals(m.r)) continue;
            if (m.t != 0) { if (m.t - lastT > 300000) { Item t = new Item('t'); t.t = m.t; out.add(t); } lastT = m.t; }
            if ("u".equals(m.r)) {
                if (m.c.startsWith("[事件]")) { to = "both"; out.add(note(m.v != null ? m.v : eventLabel(m.c))); }
                else if (SRESULT.matcher(m.c).find()) out.add(note(m.v != null ? m.v : searchLabel(m.c)));
                else { to = toOf(m.c); Item me = new Item('m'); me.s = TAG.matcher(m.c).replaceFirst(""); me.to = to; out.add(me); }
            } else if ("a".equals(m.r)) {
                if (m.parsed == null || !to.equals(m.parsedTo)) { // 解析过的留着，记录没变就不再解析
                    ArrayList<Item> l = new ArrayList<>();
                    petMsgs(m.c, to, m.w, l);
                    m.parsed = l; m.parsedTo = to;
                }
                out.addAll(m.parsed);
                if (m.k != null) for (int c = 0; c < m.k.length(); c++) { JSONObject call = m.k.optJSONObject(c); if (call != null) out.add(note(callLabel(call, resultOf(h, i, call.optString("id"))))); }
            } else if ("s".equals(m.r)) {
                if ("me".equals(m.w)) { Item me = new Item('m'); me.s = TAG.matcher(m.c).replaceFirst(""); me.to = toOf(m.c); me.fail = m.f; out.add(me); }
                else if (Ai.NAME.containsKey(m.w)) { Item pt = new Item('p'); pt.who = m.w; Piece pc = new Piece(); pc.s = new StringBuilder(m.c); pt.parts.add(pc); out.add(pt); }
                else out.add(note(m.c));
            }
        }
        ArrayList<Item> res = new ArrayList<>();
        for (Item x : out) if (x.k != 'p' || x.hasWords()) res.add(x);
        return res;
    }
    /** 正在说的那段（流式）、正在输入、气泡里的按钮 */
    private ArrayList<Item> liveItems() {
        ArrayList<Item> out = new ArrayList<>(), res = new ArrayList<>();
        if (!liveText.isEmpty()) petMsgs(liveText, liveTo, liveFirst, out);
        for (Item x : out) if (x.k != 'p' || !x.parts.isEmpty()) res.add(x);
        Item prev = null;
        for (int i = 0; i < res.size(); i++) {
            Item it = res.get(i);
            if (acts != null) it.ask = null; // 选项已经是下面能点的按钮了
            if (it.k == 'p') { it.cont = prev != null && prev.k == 'p' && prev.who.equals(it.who); it.typing = i == res.size() - 1; }
            prev = it;
        }
        if (!typingWho.isEmpty() && res.isEmpty()) { Item d = new Item('d'); d.who = typingWho; res.add(d); }
        if (acts != null) { Item a = new Item('a'); a.acts = acts; res.add(a); }
        return res;
    }
    private void render() {
        if (!opened || view == null) return;
        if (dirty) {
            dirty = false;
            ArrayList<Item> list = items(), shown = new ArrayList<>();
            int askAt = -1;
            if (acts != null) for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).ask != null) { askAt = i; break; } // 最后那组选项已经是下面能点的按钮了
            Item prev = null;
            for (int i = 0; i < list.size(); i++) {
                Item it = list.get(i);
                boolean cont = it.k == 'p' && prev != null && prev.k == 'p' && prev.who.equals(it.who);
                if (i == askAt || cont != it.cont) { it = it.copy(); it.cont = cont; if (i == askAt) it.ask = null; }
                shown.add(it);
                prev = it;
            }
            if (shown.isEmpty()) shown.add(new Item('e'));
            hist = shown;
        }
        ArrayList<Item> all = new ArrayList<>(hist);
        all.addAll(liveItems());
        boolean grew = all.size() > view.adapter.items.size() || liveText.length() > shownLive;
        shownLive = liveText.length();
        view.adapter.items = all;
        view.adapter.notifyDataSetChanged();
        view.sub = !typingWho.isEmpty() || !liveText.isEmpty() ? Ai.NAME.get(!typingWho.isEmpty() ? typingWho : !liveFirst.isEmpty() ? liveFirst : "Neuron") + " 正在输入…" : "Neuro、Evil 和你";
        view.head.invalidate();
        if (!pin && grew) view.showNew(true); // 往上翻着：不打扰，出个"有新消息"
    }
    private static String pad(int x) { return (x < 10 ? "0" : "") + x; }
    /** 时间：今天只写几点几分，昨天写"昨天"，更早写几月几日 */
    static String when(long t) {
        Calendar d = Calendar.getInstance(), now = Calendar.getInstance();
        d.setTimeInMillis(t);
        String hm = pad(d.get(Calendar.HOUR_OF_DAY)) + ":" + pad(d.get(Calendar.MINUTE));
        Calendar t0 = (Calendar) now.clone();
        t0.set(Calendar.HOUR_OF_DAY, 0); t0.set(Calendar.MINUTE, 0); t0.set(Calendar.SECOND, 0); t0.set(Calendar.MILLISECOND, 0);
        long z = t0.getTimeInMillis();
        if (t >= z) return hm;
        if (t >= z - 86400000L) return "昨天 " + hm;
        return (d.get(Calendar.YEAR) == now.get(Calendar.YEAR) ? "" : d.get(Calendar.YEAR) + "年") + (d.get(Calendar.MONTH) + 1) + "月" + d.get(Calendar.DAY_OF_MONTH) + "日 " + hm;
    }

    // ================= 画出来 =================
    static final String CLOSE = "M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12l5.6 5.6-1.4 1.4-5.6-5.6L6.4 19 5 17.6 10.6 12 5 6.4z";
    private static final int INK = 0xff222222;
    /** 聊天室整块：标题栏、消息列表、"有新消息"；背景横屏是左边圆角的半透明卡片（带阴影），竖屏不透明铺满 */
    final class RoomView extends ViewGroup {
        final Head head;
        final ListView list;
        final Adapter adapter = new Adapter();
        final NewBtn newBtn;
        String sub = "Neuro、Evil 和你";
        private boolean portrait;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        // 列表共用的画笔、头像
        final TextPaint pBody = new TextPaint(Paint.ANTI_ALIAS_FLAG), pSmall = new TextPaint(Paint.ANTI_ALIAS_FLAG), pBtn = new TextPaint(Paint.ANTI_ALIAS_FLAG), pEmpty = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), av = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Bitmap bmpN, bmpE;
        private BitmapShader shN, shE;
        private final Matrix am = new Matrix();
        float rem;

        RoomView(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            setClickable(true); // 按在聊天室上不算点到画面
            rem = u.px(u.rem);
            bmpN = load(c, "Neuron_shime1.png");
            bmpE = load(c, "Eviling_shime1.png");
            if (bmpN != null) shN = new BitmapShader(bmpN, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (bmpE != null) shE = new BitmapShader(bmpE, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            for (TextPaint p : new TextPaint[] { pBody, pSmall, pBtn, pEmpty }) p.setTypeface(Fonts.sys);
            head = new Head(c);
            list = new ListView(c);
            list.setDivider(null);
            list.setDividerHeight(0);
            list.setSelector(new ColorDrawable(0));
            list.setCacheColorHint(0);
            list.setFocusable(false);
            list.setFocusableInTouchMode(false);
            list.setItemsCanFocus(false);
            list.setOverScrollMode(OVER_SCROLL_NEVER);
            list.setClipToPadding(false);
            list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
            list.setAdapter(adapter);
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(AbsListView v, int s) { }
                @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                    View last = v.getChildAt(v.getChildCount() - 1);
                    boolean bottom = total == 0 || (first + visible >= total && last != null && last.getBottom() - (v.getHeight() - v.getPaddingBottom()) < 40 * u.dens);
                    if (bottom == pin) return;
                    pin = bottom;
                    v.setTranscriptMode(pin ? AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL : AbsListView.TRANSCRIPT_MODE_DISABLED);
                    if (pin) showNew(false);
                }
            });
            newBtn = new NewBtn(c);
            newBtn.setVisibility(GONE);
            addView(list);
            addView(head);
            addView(newBtn);
            sizes();
        }
        private Bitmap load(Context c, String f) {
            try (java.io.InputStream in = c.getAssets().open(f)) { return BitmapFactory.decodeStream(in); } catch (Exception e) { return null; }
        }
        void release() { list.setAdapter(null); bmpN = bmpE = null; shN = shE = null; }
        private void sizes() {
            pBody.setTextSize(.25f * rem);
            pSmall.setTextSize(.2f * rem);
            pBtn.setTextSize(.23f * rem);
            pEmpty.setTextSize(.24f * rem);
            int side = Math.round(.18f * rem);
            list.setPadding(side, Math.round(.04f * rem), side, Math.round(.2f * rem));
        }
        void setPortrait(boolean p) { if (portrait != p) { portrait = p; invalidate(); } }
        /** 从右边滑进来（同 roomIn） */
        void animIn() {
            setAlpha(0);
            setTranslationX(.8f * rem);
            animate().alpha(1).translationX(0).setDuration(350).setInterpolator(t -> ROOM_IN.at(t)).start();
        }
        void toEnd() { int n = adapter.getCount(); if (n > 0) list.setSelection(n - 1); }
        void showNew(boolean on) { newBtn.setVisibility(on ? VISIBLE : GONE); }

        @Override protected void onMeasure(int ws, int hs) {
            float nr = u.px(u.rem);
            if (Math.abs(nr - rem) > .5f) { rem = nr; sizes(); adapter.notifyDataSetChanged(); }
            int W = MeasureSpec.getSize(ws), H = MeasureSpec.getSize(hs);
            int headH = Math.round(.92f * rem), listB = Math.round(H - .16f * rem - .8f * rem - .1f * rem);
            head.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(headH, MeasureSpec.EXACTLY));
            list.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(0, listB - headH), MeasureSpec.EXACTLY));
            newBtn.measure(MeasureSpec.makeMeasureSpec(Math.round(1.8f * rem), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.round(.5f * rem), MeasureSpec.EXACTLY));
            setMeasuredDimension(W, H);
        }
        @Override protected void onLayout(boolean ch, int l, int t, int rr, int b) {
            int W = rr - l, H = b - t, headH = head.getMeasuredHeight();
            head.layout(0, 0, W, headH);
            list.layout(0, headH, W, headH + list.getMeasuredHeight());
            int nw = newBtn.getMeasuredWidth(), nh = newBtn.getMeasuredHeight(), ny = Math.round(H - 1.2f * rem) - nh;
            newBtn.layout((W - nw) / 2, ny, (W + nw) / 2, ny + nh);
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (portrait) { c.drawColor(0xfff6f7fa); return; } // 占满时不透：后面的时间不透出来
            float rad = .24f * rem;
            bg.setColor(Ease.rgba(246, 247, 250, .98f));
            float blur = .3f * rem, sr = Math.max(0.1f, (blur / 2 - 0.5f) / 0.57735f);
            bg.setShadowLayer(sr, -.04f * rem, 0, Ease.rgba(0, 0, 0, .22f));
            r.set(0, 0, w, h);
            path.reset();
            path.addRoundRect(r, new float[] { rad, rad, 0, 0, 0, 0, rad, rad }, Path.Direction.CW);
            c.drawPath(path, bg);
            bg.clearShadowLayer();
        }
        @Override public boolean onTouchEvent(MotionEvent e) { return true; }
        /** 消息列表裁在自己的范围里：滚上去的消息不画到标题栏上（别的子视图不裁，"有新消息"的阴影要画出边界） */
        @Override protected boolean drawChild(Canvas c, View child, long t) {
            if (child != list) return super.drawChild(c, child, t);
            c.save();
            c.clipRect(list.getLeft(), list.getTop(), list.getRight(), list.getBottom());
            boolean more = super.drawChild(c, child, t);
            c.restore();
            return more;
        }

        /** 头像：圆里放站立图的脸（同 background-size 250%、位置 50% 16%），外面一圈白边 */
        void avatar(Canvas c, float x, float y, float D, boolean evil) {
            float cx = x + D / 2, cy = y + D / 2;
            fill.setColor(0xffffffff);
            c.drawCircle(cx, cy, D / 2 + .025f * rem, fill);
            fill.setColor(evil ? 0xff3a2442 : 0xffffe3ee);
            c.drawCircle(cx, cy, D / 2, fill);
            Bitmap b = evil ? bmpE : bmpN;
            BitmapShader sh = evil ? shE : shN;
            if (b == null || sh == null) return;
            float S = 2.5f * D, k = S / b.getWidth();
            am.setScale(k, k);
            am.postTranslate(x + (D - S) * 0.5f, y + (D - b.getHeight() * k) * 0.16f);
            sh.setLocalMatrix(am);
            av.setShader(sh);
            c.drawCircle(cx, cy, D / 2, av);
            av.setShader(null);
        }
    }
    private static final Ease ROOM_IN = new Ease(.2f, .9f, .3f, 1);

    /** 标题栏：两个头像、"群聊"、谁在输入、关闭键 */
    final class Head extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Matrix m = new Matrix();
        private boolean down;
        Head(Context c) { super(c); }
        private float xRect() { float rem = view.rem; return getWidth() - .16f * rem - .54f * rem; }
        @Override protected void onDraw(Canvas c) {
            RoomView v = view;
            if (v == null) return;
            float rem = v.rem, h = getHeight(), D = .52f * rem, x0 = .2f * rem, y0 = (h - D) / 2;
            p.setColor(0xffe3e6ec);
            c.drawRect(0, h - 1, getWidth(), h, p);
            v.avatar(c, x0, y0, D, false);
            v.avatar(c, x0 + .28f * rem, y0, D, true);
            float tx = x0 + .8f * rem + .14f * rem, xr = xRect(), tw = Math.max(0, xr - tx - .1f * rem);
            tp.setTypeface(Fonts.sysBold);
            tp.setTextSize(.28f * rem);
            tp.setColor(INK);
            float l1 = .28f * rem * 1.25f, l2 = .2f * rem * 1.4f, top = (h - l1 - l2) / 2;
            c.drawText("群聊", tx, top + Fonts.baseIn(tp, l1), tp);
            tp.setTypeface(Fonts.sys);
            tp.setTextSize(.2f * rem);
            tp.setColor(Kit.MUTE);
            CharSequence sub = TextUtils.ellipsize(v.sub, tp, tw, TextUtils.TruncateAt.END);
            c.drawText(sub, 0, sub.length(), tx, top + l1 + Fonts.baseIn(tp, l2), tp);
            float s = .54f * rem, cx = xr + s / 2, cy = h / 2, k = down ? .92f : 1;
            c.save();
            c.scale(k, k, cx, cy);
            p.setColor(Ease.rgba(0, 0, 0, .06f));
            c.drawCircle(cx, cy, s / 2, p);
            float pad = .13f * rem, sc = (s - 2 * pad) / 24;
            m.setScale(sc, sc);
            m.postTranslate(xr + pad, cy - s / 2 + pad);
            SvgPath.of(CLOSE).transform(m, path);
            p.setColor(0xff444444);
            c.drawPath(path, p);
            c.restore();
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float xr = xRect(), s = .54f * view.rem, pad = .1f * view.rem;
            boolean on = e.getX() >= xr - pad && e.getX() <= xr + s + pad;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: down = on; invalidate(); return true;
                case MotionEvent.ACTION_UP: if (down && on) post(AiRoom.this::close); down = false; invalidate(); return true;
                case MotionEvent.ACTION_CANCEL: down = false; invalidate(); return true;
            }
            return true;
        }
    }

    /** "有新消息 ↓"：点了跳到最底下、接着跟着滚 */
    final class NewBtn extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        NewBtn(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float rem = view.rem, w = getWidth(), h = getHeight();
            p.setColor(Kit.BLUE);
            Fonts.shadow(p, .04f * rem, .14f * rem, Ease.rgba(47, 124, 246, .4f));
            c.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
            p.clearShadowLayer();
            p.setColor(0xffffffff);
            p.setTypeface(Fonts.sys);
            p.setTextSize(.21f * rem);
            String s = "有新消息 ↓";
            c.drawText(s, (w - p.measureText(s)) / 2, h / 2 - (p.descent() + p.ascent()) / 2, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) post(() -> {
                pin = true;
                RoomView v = view;
                if (v == null) return;
                v.showNew(false);
                v.list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
                v.toEnd();
            });
            return true;
        }
    }

    final class Adapter extends BaseAdapter {
        ArrayList<Item> items = new ArrayList<>();
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            Row r = convert instanceof Row ? (Row) convert : new Row(parent.getContext());
            r.bind(items.get(i));
            return r;
        }
    }

    /** 列表里的一行：按 Item 的种类自己排、自己画 */
    final class Row extends View {
        private Item it;
        private int bw = -1;
        private float h;
        private StaticLayout lay, fail;
        private String timeText;
        private float bubW, bubH, bubX, bubY, nameH;
        private final ArrayList<RectF> chips = new ArrayList<>();
        private final Path path = new Path();
        private final RectF r = new RectF();
        private int pressed = -1;
        Row(Context c) { super(c); }
        void bind(Item x) { if (x == it) return; it = x; bw = -1; pressed = -1; requestLayout(); invalidate(); }

        @Override protected void onMeasure(int ws, int hs) {
            int W = MeasureSpec.getSize(ws);
            if (W != bw || view == null) build(W);
            setMeasuredDimension(W, (int) Math.ceil(h));
        }
        private float lh(TextPaint p) { return Fonts.lineH(p); }
        private StaticLayout make(CharSequence s, TextPaint p, int w, float lineK, Layout.Alignment al) {
            float mult = lineK > 0 ? lineK * p.getTextSize() / Math.max(1, lh(p)) : 1;
            return StaticLayout.Builder.obtain(s, 0, s.length(), p, Math.max(1, w)).setAlignment(al).setLineSpacing(0, mult).setIncludePad(false).build();
        }
        private int fit(CharSequence s, TextPaint p, float max) { return (int) Math.ceil(Math.min(max, Layout.getDesiredWidth(s, p))); }
        private void build(int W) {
            bw = W;
            chips.clear();
            lay = fail = null;
            RoomView v = view;
            if (v == null || it == null) { h = 0; return; }
            float rem = v.rem;
            switch (it.k) {
                case 't': timeText = when(it.t); h = .22f * rem + lh(v.pSmall); return;
                case 'e': h = 1.2f * rem + lh(v.pEmpty); return;
                case 'n': {
                    float max = .92f * W - .32f * rem;
                    int w = fit(it.s, v.pSmall, max);
                    lay = make(it.s, v.pSmall, w, 1.5f, Layout.Alignment.ALIGN_CENTER);
                    bubW = w + .32f * rem; bubH = lay.getHeight() + .08f * rem;
                    bubX = (W - bubW) / 2; bubY = .16f * rem;
                    h = bubY + bubH;
                    return;
                }
                case 'm': {
                    SpannableStringBuilder b = new SpannableStringBuilder();
                    if (!"both".equals(it.to)) { b.append("@").append(Ai.NAME.get(it.to)).append(' '); b.setSpan(new ForegroundColorSpan(0xc7ffffff), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
                    b.append(it.s);
                    float max = .8f * W - .36f * rem;
                    int w = fit(b, v.pBody, max);
                    lay = make(b, v.pBody, w, 1.5f, Layout.Alignment.ALIGN_NORMAL);
                    bubW = w + .36f * rem; bubH = lay.getHeight() + .2f * rem;
                    bubX = W - bubW; bubY = .16f * rem;
                    h = bubY + bubH;
                    if (it.fail) { fail = make("没发出去", v.pSmall, fit("没发出去", v.pSmall, W), 0, Layout.Alignment.ALIGN_NORMAL); h += .04f * rem + fail.getHeight(); }
                    return;
                }
                case 'p': case 'd': {
                    float D = .52f * rem, cx = D + .12f * rem, top = it.cont ? .06f * rem : .16f * rem, maxC = Math.min(.8f * W, W - cx);
                    nameH = it.cont ? 0 : .2f * rem * 1.3f + .04f * rem;
                    bubX = cx; bubY = top + nameH;
                    if (it.k == 'd') { bubW = .94f * rem; bubH = .44f * rem; }
                    else {
                        boolean evil = "Eviling".equals(it.who);
                        SpannableStringBuilder b = new SpannableStringBuilder();
                        for (Piece pc : it.parts) {
                            if (pc.a != null) { int st = b.length(); b.append((char) 0xFFFC); b.setSpan(new Chip(pc.a, evil), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
                            else b.append(ltrim(pc.s));
                        }
                        if (it.typing) { int st = b.length(); b.append((char) 0xFFFC); b.setSpan(new Caret(), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
                        int w = fit(b, v.pBody, maxC - .36f * rem);
                        lay = make(b, v.pBody, w, 1.5f, Layout.Alignment.ALIGN_NORMAL);
                        bubW = w + .36f * rem; bubH = lay.getHeight() + .2f * rem;
                    }
                    float bottom = bubY + bubH;
                    if (it.ask != null && !it.ask.isEmpty()) { // 选项（只是看的，能点的在下面）
                        float x = 0, y = bottom + .06f * rem, ch = .06f * rem + lh(v.pSmall), lineMax = maxC;
                        for (String s : it.ask) {
                            float cw = v.pSmall.measureText(s) + .32f * rem + 2;
                            if (x > 0 && x + cw > lineMax) { x = 0; y += ch + .06f * rem; }
                            chips.add(new RectF(cx + x, y, cx + x + cw, y + ch));
                            x += cw + .08f * rem;
                        }
                        bottom = y + ch + .06f * rem;
                    }
                    h = Math.max(bottom, it.cont ? 0 : top + D);
                    return;
                }
                case 'a': {
                    float x = 0, y = .16f * rem, bh = .56f * rem, mx = .06f * rem;
                    ArrayList<Integer> lineStart = new ArrayList<>();
                    ArrayList<Float> lineW = new ArrayList<>();
                    lineStart.add(0);
                    for (int i = 0; i < it.acts.size(); i++) {
                        float cw = v.pBtn.measureText(it.acts.get(i).text) + .52f * rem;
                        if (x > 0 && x + cw + 2 * mx > W) { lineW.add(x); lineStart.add(i); x = 0; y += bh + .08f * rem; }
                        chips.add(new RectF(x + mx, y, x + mx + cw, y + bh));
                        x += cw + 2 * mx;
                    }
                    lineW.add(x);
                    for (int li = 0; li < lineStart.size(); li++) { // 每一行居中
                        int a = lineStart.get(li), b = li + 1 < lineStart.size() ? lineStart.get(li + 1) : chips.size();
                        float off = (W - lineW.get(li)) / 2;
                        for (int i = a; i < b; i++) chips.get(i).offset(off, 0);
                    }
                    h = y + bh + .08f * rem;
                    return;
                }
            }
            h = 0;
        }
        private CharSequence ltrim(StringBuilder s) { int i = 0; while (i < s.length() && Ai.isWs(s.charAt(i))) i++; return s.subSequence(i, s.length()); }

        @Override protected void onDraw(Canvas c) {
            RoomView v = view;
            if (v == null || it == null) return;
            float rem = v.rem, W = getWidth();
            Paint f = v.fill;
            switch (it.k) {
                case 't': {
                    v.pSmall.setColor(0xffa0a5ae);
                    String s = timeText == null ? "" : timeText;
                    c.drawText(s, (W - v.pSmall.measureText(s)) / 2, .22f * rem + Fonts.base(v.pSmall), v.pSmall);
                    return;
                }
                case 'e': {
                    v.pEmpty.setColor(0xffa0a5ae);
                    String s = "还没有和它们聊过。在下面说一句试试";
                    float tw = v.pEmpty.measureText(s);
                    c.drawText(s, Math.max(0, (W - tw) / 2), 1.2f * rem + Fonts.base(v.pEmpty), v.pEmpty);
                    return;
                }
                case 'n': {
                    f.setColor(Ease.rgba(0, 0, 0, .05f));
                    c.drawRoundRect(bubX, bubY, bubX + bubW, bubY + bubH, .2f * rem, .2f * rem, f);
                    v.pSmall.setColor(0xff858b96);
                    c.save();
                    c.translate(bubX + .16f * rem, bubY + .04f * rem);
                    lay.draw(c);
                    c.restore();
                    return;
                }
                case 'm': {
                    bubble(c, f, Kit.BLUE, .2f * rem, .06f * rem);
                    v.pBody.setColor(0xffffffff);
                    c.save();
                    c.translate(bubX + .18f * rem, bubY + .1f * rem);
                    lay.draw(c);
                    c.restore();
                    if (fail != null) {
                        v.pSmall.setColor(0xffd9534f);
                        c.save();
                        c.translate(W - fail.getWidth(), bubY + bubH + .04f * rem);
                        fail.draw(c);
                        c.restore();
                    }
                    return;
                }
                case 'p': case 'd': {
                    boolean evil = "Eviling".equals(it.who);
                    float D = .52f * rem, top = it.cont ? .06f * rem : .16f * rem;
                    if (!it.cont) {
                        v.avatar(c, 0, top, D, evil);
                        v.pSmall.setColor(evil ? 0xff9c2e45 : 0xffd0477e);
                        String n = Ai.NAME.get(it.who);
                        c.drawText(n == null ? "" : n, bubX + .04f * rem, top + Fonts.baseIn(v.pSmall, .2f * rem * 1.3f), v.pSmall);
                    }
                    int ink = evil ? 0xffffe1e8 : INK;
                    bubble(c, f, evil ? 0xff33223b : 0xffffeaf2, .06f * rem, .2f * rem);
                    if (it.k == 'd') { // 三个点：每个 1.2 秒一亮，依次错开 0.2 秒
                        long now = SystemClock.uptimeMillis();
                        float r0 = .05f * rem, cy = bubY + .1f * rem + .07f * rem + r0, x = bubX + .18f * rem + .02f * rem + .04f * rem + r0;
                        for (int i = 0; i < 3; i++) {
                            float ph = ((now - i * 200) % 1200) / 1200f;
                            float a = ph < .3f ? .25f + .65f * Ease.IN_OUT.at(ph / .3f) : ph < .6f ? .9f - .65f * Ease.IN_OUT.at((ph - .3f) / .3f) : .25f;
                            f.setColor(Ease.alpha(ink, a));
                            c.drawCircle(x + i * .18f * rem, cy, r0, f);
                        }
                        postInvalidateDelayed(33);
                    } else {
                        v.pBody.setColor(ink);
                        c.save();
                        c.translate(bubX + .18f * rem, bubY + .1f * rem);
                        lay.draw(c);
                        c.restore();
                    }
                    if (it.ask != null) for (int i = 0; i < chips.size() && i < it.ask.size(); i++) {
                        RectF cr = chips.get(i);
                        f.setStyle(Paint.Style.STROKE);
                        f.setStrokeWidth(1);
                        f.setColor(Kit.BORDER);
                        r.set(cr.left + .5f, cr.top + .5f, cr.right - .5f, cr.bottom - .5f);
                        c.drawRoundRect(r, .2f * rem, .2f * rem, f);
                        f.setStyle(Paint.Style.FILL);
                        v.pSmall.setColor(0xff6a707a);
                        c.drawText(it.ask.get(i), cr.left + .16f * rem + 1, cr.top + .03f * rem + Fonts.base(v.pSmall), v.pSmall);
                    }
                    return;
                }
                case 'a': {
                    for (int i = 0; i < chips.size() && i < it.acts.size(); i++) {
                        Ai.AskItem a = it.acts.get(i);
                        RectF cr = chips.get(i);
                        float s = i == pressed ? .95f : 1;
                        c.save();
                        c.scale(s, s, cr.centerX(), cr.centerY());
                        f.setColor(a.ghost ? 0xffe3e6ec : Kit.BLUE);
                        c.drawRoundRect(cr, cr.height() / 2, cr.height() / 2, f);
                        v.pBtn.setColor(a.ghost ? 0xff555555 : 0xffffffff);
                        c.drawText(a.text, cr.centerX() - v.pBtn.measureText(a.text) / 2, cr.centerY() - (v.pBtn.descent() + v.pBtn.ascent()) / 2, v.pBtn);
                        c.restore();
                    }
                }
            }
        }
        /** 气泡底色：左上（tl）和右上（tr）的圆角不一样，下面两个角 .2rem */
        private void bubble(Canvas c, Paint f, int color, float tl, float tr) {
            float br = .2f * view.rem;
            r.set(bubX, bubY, bubX + bubW, bubY + bubH);
            path.reset();
            path.addRoundRect(r, new float[] { tl, tl, tr, tr, br, br, br, br }, Path.Direction.CW);
            f.setColor(color);
            c.drawPath(path, f);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (it == null || it.k != 'a') return false;
            int hit = -1;
            for (int i = 0; i < chips.size(); i++) if (chips.get(i).contains(e.getX(), e.getY())) hit = i;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: if (hit < 0) return false; pressed = hit; invalidate(); return true;
                case MotionEvent.ACTION_UP:
                    if (hit >= 0 && hit == pressed) { final int i = hit; post(() -> ai.pressAt(i)); }
                    pressed = -1; invalidate(); return true;
                case MotionEvent.ACTION_CANCEL: pressed = -1; invalidate(); return true;
            }
            return true;
        }
    }

    /** 动作小标签（同 .rm-act）：圆角浅底、小一号的字，不折行 */
    final class Chip extends ReplacementSpan {
        private final String label;
        private final boolean evil;
        Chip(String label, boolean evil) { this.label = label; this.evil = evil; }
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            RoomView v = view;
            if (v == null) return 0;
            return (int) Math.ceil(v.pSmall.measureText(label) + .28f * v.rem);
        }
        @Override public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            RoomView v = view;
            if (v == null) return;
            float rem = v.rem, w = v.pSmall.measureText(label) + .2f * rem, x0 = x + .04f * rem;
            Paint f = v.fill;
            int keep = f.getColor();
            f.setColor(evil ? Ease.rgba(255, 225, 232, .14f) : Ease.rgba(208, 71, 126, .12f));
            c.drawRoundRect(x0, y + v.pSmall.ascent(), x0 + w, y + v.pSmall.descent(), .14f * rem, .14f * rem, f);
            f.setColor(keep);
            int ink = v.pSmall.getColor();
            v.pSmall.setColor(evil ? 0xffffbccb : 0xffb04473);
            c.drawText(label, x0 + .1f * rem, y, v.pSmall);
            v.pSmall.setColor(ink);
        }
    }
    /** 还在往下说：句尾一道细竖线 */
    final class Caret extends ReplacementSpan {
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            RoomView v = view;
            return v == null ? 0 : (int) Math.ceil(.07f * v.rem);
        }
        @Override public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            RoomView v = view;
            if (v == null) return;
            float rem = v.rem;
            Paint f = v.fill;
            int keep = f.getColor();
            f.setColor(Ease.alpha(paint.getColor(), .45f));
            c.drawRect(x + .04f * rem, y + .04f * rem - .26f * rem, x + .07f * rem, y + .04f * rem, f);
            f.setColor(keep);
        }
    }
}
