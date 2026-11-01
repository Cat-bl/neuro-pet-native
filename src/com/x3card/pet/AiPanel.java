package com.x3card.pet;

import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.x3card.pet.ai.Done;
import com.x3card.pet.ai.Err;
import com.x3card.pet.ai.PetChat;
import com.x3card.pet.ai.PetSearch;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置 → AI 聊天（同网页版 #panelAI）：服务商、接口格式 / 地址（自定义）、API Key、模型（列表）、测试连接、权限说明（车上）、
 * 联网搜索、没事时找你聊天、车况变化时用 AI 说、记忆长度、清空对话记录。面板每次打开重建；改了选项只更新这一页里的东西（输入框不重建，不丢焦点）
 */
final class AiPanel {
    private static final int LINE = 0xffeeeeee, OK = 0xff2e9d57, BAD = 0xffd9534f;
    private final Ai ai;
    private final Kit k;
    private final App app;
    private Kit.Flow provs, formats, searchBy, models;
    private View rowFormat, rowBase, rowSearchBy, rowSearchKey, rowSearchTest;
    private EditText fKey, fModel, fBase, fSearchKey;
    private TextView baseHint, keyHint, modelHint, testMsg, searchHint, searchMsg, listBtn, clearBtn;
    private ScrollView modelBox;
    private TextView modelText;
    private Kit.Seg segSearch;
    private boolean muting, dropped;
    private int testing, searchTesting;
    private final Runnable disarm = this::disarm;
    private boolean clearArm;

    AiPanel(Ai ai, Kit k, LinearLayout body) {
        this.ai = ai;
        this.k = k;
        app = ai.app;
        build(body);
        render();
    }
    /** 这一页被换掉了：晚到的结果不再写 */
    void drop() { dropped = true; app.h.removeCallbacks(disarm); }

    // ================= 搭页面 =================
    private void build(LinearLayout body) {
        TextView note = k.tip("填好就能打字跟两只聊天，它们按自己的性格回你；不填就照旧说自带的台词。API Key 只存在这个应用里，聊天内容会发给所选的服务商。", .15f, 1.2f);
        LinearLayout.LayoutParams np = Kit.lp(-1, -2);
        np.topMargin = k.px(.1f);
        body.addView(note, np);

        body.addView(k.header("模型服务"));
        provs = flow();
        body.addView(col("服务商", null, provs));
        formats = flow();
        body.addView(rowFormat = col("接口格式", Kit.list("中转站一般用 OpenAI Chat，看服务商文档"), formats));
        ArrayList<TextView> hs = new ArrayList<>();
        fBase = field("https://…/v1", false);
        body.addView(rowBase = col("接口地址", Kit.list(""), fieldRow(fBase, null), hs));
        baseHint = hs.get(0);
        hs.clear();
        fKey = field("sk-…", true);
        TextView eye = k.mini("显示", false, null);
        eye(eye, fKey);
        body.addView(col("API Key", Kit.list(""), fieldRow(fKey, eye), hs));
        keyHint = hs.get(0);
        hs.clear();
        fModel = field("模型名", false);
        listBtn = k.mini("列表", false, this::list);
        LinearLayout modelCol = col("模型", Kit.list("", "想让它们控制车辆、音乐，要选支持工具调用的大模型（这类模型能按格式发指令，太小的模型容易写错、不执行）"), fieldRow(fModel, listBtn), hs);
        modelHint = hs.get(0);
        hs.clear();
        modelBox = new MaxScroll(k.c, k.px(2.4f));
        modelBox.setNestedScrollingEnabled(true);
        modelBox.setVerticalScrollBarEnabled(true);
        LinearLayout inner = new LinearLayout(k.c);
        inner.setOrientation(LinearLayout.VERTICAL);
        modelText = k.text("", .19f, Kit.MUTE, Fonts.sys);
        models = flow();
        inner.addView(modelText, Kit.lp(-1, -2));
        inner.addView(models, Kit.lp(-1, -2));
        modelBox.addView(inner, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams mp = Kit.lp(-1, -2);
        mp.topMargin = k.px(.08f);
        modelCol.addView(modelBox, mp);
        modelBox.setVisibility(View.GONE);
        body.addView(modelCol);
        TextView testBtn = k.mini("测试", false, this::test);
        body.addView(line("测试连接", hs, testBtn));
        testMsg = hs.get(0);
        hs.clear();
        if (ai.onCar()) body.addView(col("车机权限", Kit.list("聊天、搜索直接联网；想让它们操作车机，要在宿主的授权页里允许「执行操作」"), null));

        body.addView(k.header("联网搜索"));
        segSearch = k.seg(new String[] { "开", "关" }, new String[] { "true", "false" }, String.valueOf(ai.set.searchOn), v -> { ai.set.searchOn = "true".equals(v); ai.saveSettings(); render(); });
        body.addView(line("联网搜索", "它们不知道的（新闻、天气、比分……）会先搜一下再回答；要单独的搜索服务 Key", segSearch));
        searchBy = flow();
        body.addView(rowSearchBy = col("搜索服务", null, searchBy));
        fSearchKey = field("", true);
        TextView seye = k.mini("显示", false, null);
        eye(seye, fSearchKey);
        body.addView(rowSearchKey = col("搜索 Key", Kit.list(""), fieldRow(fSearchKey, seye), hs));
        searchHint = hs.get(0);
        hs.clear();
        body.addView(rowSearchTest = line("测试搜索", hs, k.mini("测试", false, this::searchTest)));
        searchMsg = hs.get(0);
        hs.clear();

        body.addView(k.header("说话"));
        final Kit.Seg[] sg = new Kit.Seg[3];
        sg[0] = k.seg(Ai.CHATTY_NAME, Ai.CHATTY_ID, ai.set.chatty, v -> { sg[0].set(v); ai.chatty(v); });
        body.addView(line("没事时找你聊天", "隔一阵让模型编一两句：少约 15 分钟、中约 8 分钟、多约 4 分钟；和下面那项一起每天最多 60 次", sg[0]));
        sg[1] = k.seg(new String[] { "开", "关" }, new String[] { "true", "false" }, String.valueOf(ai.set.proactive), v -> { sg[1].set(v); ai.set.proactive = "true".equals(v); ai.saveSettings(); });
        if (app.car != null) body.addView(line("车况变化时用 AI 说", "上车、门没关、电量油量低、充电……交给模型说；模型说不上来时照旧说自带的台词", sg[1]));
        sg[2] = k.seg(Ai.MEM_NAME, Ai.MEM_ID, ai.set.memory, v -> { sg[2].set(v); ai.set.memory = v; ai.saveSettings(); });
        body.addView(line("记忆长度", "每次带给模型最近约 20 / 50 / 120 轮对话（它们自己聊的也算）；越长越记得住，也越费 token", sg[2]));
        clearBtn = k.mini("清空", false, this::clear);
        body.addView(line("清空对话记录", "它们会忘掉之前聊过的内容", clearBtn));

        watch(fKey, s -> { ai.set.keys.put(ai.set.provider, Ai.head(AiControl.trim(s), 400)); ai.saveSettings(); ai.sync(); });
        watch(fModel, s -> { ai.set.models.put(ai.set.provider, Ai.head(AiControl.trim(s), 200)); ai.saveSettings(); });
        watch(fBase, s -> { ai.set.base = Ai.head(AiControl.trim(s), 300); ai.saveSettings(); ai.sync(); });
        watch(fSearchKey, s -> { ai.set.searchKeys.put(ai.set.searchBy, Ai.head(AiControl.trim(s), 300)); ai.saveSettings(); });
        setMsg(testMsg, ai.testText, ai.testCls);
        setMsg(searchMsg, ai.searchText, ai.searchCls);
    }

    private Kit.Flow flow() { return new Kit.Flow(k.c, k.px(.08f), k.px(.08f)); }
    /** 一块（同 .ai-col）：说明 + 小字，下面放控件 */
    private LinearLayout col(String label, List<CharSequence> smalls, View below) { return col(label, smalls, below, null); }
    private LinearLayout col(String label, List<CharSequence> smalls, View below, List<TextView> out) {
        LinearLayout c = new LinearLayout(k.c);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(0, k.px(.08f), 0, k.px(.1f));
        c.setBackground(k.bottomLine());
        c.addView(k.label(label, smalls, out), Kit.lp(-1, -2));
        if (below != null) {
            LinearLayout.LayoutParams p = Kit.lp(-1, -2);
            p.topMargin = k.px(.08f);
            c.addView(below, p);
        }
        c.setLayoutParams(Kit.lp(-1, -2));
        return c;
    }
    /** 一行（同 .ai-col.ai-line）：左边说明 + 小字，右边控件 */
    private LinearLayout line(String label, String small, View right) { return line(label, Kit.list(small), right, null); }
    private LinearLayout line(String label, List<TextView> out, View right) { return line(label, Kit.list(""), right, out); }
    private LinearLayout line(String label, List<CharSequence> smalls, View right, List<TextView> out) {
        LinearLayout r = new LinearLayout(k.c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, k.px(.08f), 0, k.px(.1f));
        r.setBackground(k.bottomLine());
        LinearLayout.LayoutParams lp = Kit.lp(0, -2);
        lp.weight = 1;
        lp.rightMargin = k.px(.16f);
        r.addView(k.label(label, smalls, out), lp);
        r.addView(right, Kit.lp(-2, -2));
        r.setLayoutParams(Kit.lp(-1, -2));
        return r;
    }
    private EditText field(String hint, boolean password) {
        EditText e = k.field(hint, password);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        final GradientDrawable bg = (GradientDrawable) e.getBackground();
        e.setOnFocusChangeListener((v, f) -> { bg.setStroke(1, f ? Kit.BLUE : Kit.BORDER); app.card.typing = f; }); // 输入时卡片保持原来的高度
        return e;
    }
    private LinearLayout fieldRow(EditText f, TextView btn) {
        LinearLayout r = new LinearLayout(k.c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = Kit.lp(0, k.px(.52f));
        lp.weight = 1;
        r.addView(f, lp);
        if (btn != null) {
            LinearLayout.LayoutParams bp = Kit.lp(-2, k.px(.48f));
            bp.leftMargin = k.px(.1f);
            r.addView(btn, bp);
        }
        return r;
    }
    private void eye(final TextView b, final EditText f) {
        Kit.press(b, () -> {
            boolean show = (f.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD;
            int sel = f.getSelectionEnd();
            f.setInputType(InputType.TYPE_CLASS_TEXT | (show ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            if (sel >= 0) f.setSelection(Math.min(sel, f.length()));
            b.setText(show ? "隐藏" : "显示");
        });
    }
    private interface Txt { void set(String s); }
    private void watch(EditText e, final Txt fn) {
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { if (!muting) fn.set(s.toString()); }
        });
    }
    private void setVal(EditText e, String v) {
        if (v.equals(e.getText().toString())) return;
        muting = true;
        e.setText(v);
        muting = false;
    }
    private static void setMsg(TextView t, String text, String cls) {
        t.setText(text);
        t.setTextColor("ok".equals(cls) ? OK : "bad".equals(cls) ? BAD : Kit.MUTE);
    }
    private void chips(Kit.Flow f, String[] ids, String[] names, String on, Kit.Pick pick) {
        f.removeAllViews();
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            f.addView(k.chip(names[i], id.equals(on), false, .46f, () -> pick.pick(id)));
        }
    }

    // ================= 按设置刷新这一页 =================
    void render() {
        Ai.Cfg set = ai.set;
        PetChat.Provider p = PetChat.find(set.provider);
        String[] pi = new String[PetChat.PROVIDERS.size()], pn = new String[pi.length];
        for (int i = 0; i < pi.length; i++) { pi[i] = PetChat.PROVIDERS.get(i).id; pn[i] = PetChat.PROVIDERS.get(i).name; }
        chips(provs, pi, pn, p.id, id -> {
            if (ai.set.provider.equals(id)) return;
            ai.set.provider = id; ai.saveSettings();
            setTest("发一句最短的话试试能不能连上", "");
            render(); ai.sync();
        });
        boolean custom = p.id.equals("custom");
        rowBase.setVisibility(custom ? View.VISIBLE : View.GONE);
        rowFormat.setVisibility(custom ? View.VISIBLE : View.GONE);
        setVal(fBase, set.base);
        String[] fi = new String[PetChat.FORMATS.size()], fn = new String[fi.length];
        PetChat.Format fm = PetChat.FORMATS.get(0);
        for (int i = 0; i < fi.length; i++) { PetChat.Format f = PetChat.FORMATS.get(i); fi[i] = f.id; fn[i] = f.name; if (f.id.equals(set.format)) fm = f; }
        chips(formats, fi, fn, set.format, id -> { ai.set.format = id; ai.saveSettings(); render(); });
        baseHint.setText(fm.hint);
        fBase.setHint("gemini".equals(set.format) ? "https://…/v1beta" : "https://…/v1");
        setVal(fKey, Ai.str(set.keys, p.id));
        setVal(fModel, Ai.str(set.models, p.id));
        fModel.setHint(!p.model.isEmpty() ? p.model : "模型名");
        keyHint.setText(p.site != null ? "在 " + p.site + " 申请" : "");
        keyHint.setVisibility(p.site != null ? View.VISIBLE : View.GONE);
        modelHint.setText(!p.model.isEmpty() ? "不填用 " + p.model : custom ? "" : "点“列表”选一个");
        modelHint.setVisibility(modelHint.getText().length() > 0 ? View.VISIBLE : View.GONE);
        listBtn.setVisibility(p.noList ? View.GONE : View.VISIBLE);
        modelBox.setVisibility(View.GONE);
        segSearch.set(String.valueOf(set.searchOn));
        int sv = set.searchOn ? View.VISIBLE : View.GONE;
        rowSearchBy.setVisibility(sv); rowSearchKey.setVisibility(sv); rowSearchTest.setVisibility(sv);
        PetSearch.Provider sp = PetSearch.find(set.searchBy);
        String[] si = new String[PetSearch.PROVIDERS.size()], sn = new String[si.length];
        for (int i = 0; i < si.length; i++) { si[i] = PetSearch.PROVIDERS.get(i).id; sn[i] = PetSearch.PROVIDERS.get(i).name; }
        chips(searchBy, si, sn, set.searchBy, id -> { ai.set.searchBy = id; ai.saveSettings(); setSearch("搜一下“今天的新闻”试试", ""); render(); });
        setVal(fSearchKey, Ai.str(set.searchKeys, set.searchBy));
        searchHint.setText("在 " + sp.site + " 申请" + (sp.id.equals("bocha") || sp.id.equals("metaso") ? "（国内服务，车上更稳）" : ""));
    }
    private void setTest(String t, String cls) { ai.testText = t; ai.testCls = cls; if (!dropped) setMsg(testMsg, t, cls); }
    private void setSearch(String t, String cls) { ai.searchText = t; ai.searchCls = cls; if (!dropped) setMsg(searchMsg, t, cls); }
    static String errText(Err e) {
        String m = e != null && !e.msg.isEmpty() ? "（" + e.msg + "）" : "";
        String code = e == null || e.code == null ? "" : e.code;
        switch (code) {
            case "net": return "连不上：可能没联网，或者接口地址不对" + m;
            case "timeout": return "等太久没回应，网络可能不好";
            case "key": return "API Key 不对或没有权限" + m;
            case "balance": return "账户余额不足" + m;
            case "rate": return "请求太频繁，等一会儿再试" + m;
            case "model": return "没有这个模型，换一个试试" + m;
            case "server": return "服务商那边出错了" + m;
            default: return "出错了" + (e != null && e.status != 0 ? " " + e.status : "") + m;
        }
    }

    // ================= 按钮 =================
    private void list() {
        PetChat.Cfg cfg = ai.chatConfig();
        if (cfg == null && !Ai.NEED_MODEL.equals(ai.missing())) { setTest(ai.missing(), "bad"); return; }
        if (cfg == null) { // 列表不需要模型
            PetChat.Provider p0 = PetChat.find(ai.set.provider);
            boolean custom = p0.id.equals("custom");
            cfg = new PetChat.Cfg(p0.id, custom ? ai.set.format : p0.format, custom ? AiControl.trim(ai.set.base) : p0.base, AiControl.trim(Ai.str(ai.set.keys, p0.id)), null, null);
        }
        final String cur = cfg.model;
        modelBox.setVisibility(View.VISIBLE);
        modelText.setVisibility(View.VISIBLE);
        modelText.setText("正在取列表…");
        models.removeAllViews();
        PetChat.models(cfg, ai.ex, new Done<List<String>>() {
            @Override public void ok(List<String> ids) {
                if (dropped) return;
                models.removeAllViews();
                if (ids.isEmpty()) { modelText.setText("列表是空的"); return; }
                modelText.setVisibility(View.GONE);
                for (final String id : ids) models.addView(k.chip(id, id.equals(cur), false, .46f, () -> {
                    ai.set.models.put(ai.set.provider, id); ai.saveSettings();
                    setVal(fModel, id);
                    modelBox.setVisibility(View.GONE);
                }));
            }
            @Override public void fail(Err e) { if (!dropped) modelText.setText("取不到：" + errText(e)); }
        });
    }
    private void test() {
        PetChat.Cfg cfg = ai.chatConfig();
        if (cfg == null) { setTest(ai.missing(), "bad"); return; }
        final int id = ++testing;
        setTest("正在连接 " + cfg.model + "…", "");
        PetChat.test(cfg, ai.ex, new Done<PetChat.TestResult>() {
            @Override public void ok(PetChat.TestResult r) {
                if (id != testing) return;
                setTest(r.text != null && !r.text.isEmpty() ? "连上了：" + ai.fmtSecs(r.ms) + " 秒回了“" + Ai.head(r.text, 12) + "”"
                    : "连上了，但模型没说话" + (Boolean.TRUE.equals(r.thought) ? "（是思考模型，回复会慢，可以换一个）" : ""), "ok");
            }
            @Override public void fail(Err e) { if (id == testing) setTest(errText(e), "bad"); }
        });
    }
    private void searchTest() {
        PetSearch.Cfg sc = ai.searchConfig();
        if (sc == null) { setSearch("先填搜索 Key", "bad"); return; }
        final int id = ++searchTesting;
        final long t0 = System.currentTimeMillis();
        setSearch("正在搜…", "");
        PetSearch.search(sc, "今天的新闻", ai.ex, new Done<List<PetSearch.Result>>() {
            @Override public void ok(List<PetSearch.Result> list) {
                if (id != searchTesting) return;
                setSearch(!list.isEmpty() ? "能用：" + ai.fmtSecs(System.currentTimeMillis() - t0) + " 秒搜到 " + list.size() + " 条，第一条“" + Ai.head(String.valueOf(list.get(0).title), 16) + "”" : "连上了，但没搜到东西",
                    !list.isEmpty() ? "ok" : "bad");
            }
            @Override public void fail(Err e) { if (id == searchTesting) setSearch(PetSearch.errText(e, false), "bad"); }
        });
    }
    private void clear() {
        if (!clearArm) {
            clearArm = true;
            clearBtn.setText("再点一次清空");
            warn(true);
            app.h.postDelayed(disarm, 3000);
            return;
        }
        disarm();
        ai.clearHistory();
        clearBtn.setText("已清空");
        app.h.postDelayed(() -> { if (!clearArm && !dropped) clearBtn.setText("清空"); }, 1500);
    }
    private void disarm() {
        app.h.removeCallbacks(disarm);
        clearArm = false;
        if (dropped) return;
        clearBtn.setText("清空");
        warn(false);
    }
    private void warn(boolean on) {
        clearBtn.setTextColor(on ? BAD : Kit.BLUE);
        clearBtn.setBackground(Kit.round(0xffffffff, k.px(.12f), on ? BAD : Kit.BORDER));
    }

    /** 最高只有 max 像素、超出能滑的一块（同 .ai-models 的 max-height） */
    static final class MaxScroll extends ScrollView {
        private final int max;
        MaxScroll(android.content.Context c, int max) { super(c); this.max = max; }
        @Override protected void onMeasure(int ws, int hs) {
            super.onMeasure(ws, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }
}
