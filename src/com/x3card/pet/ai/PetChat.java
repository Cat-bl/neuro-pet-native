package com.x3card.pet.ai;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 宠物的大模型接口：服务商表、流式请求、取模型列表（四种接口格式、工具调用、自动退回），
 * 加上两只宠物的人设、回复解析（谁在说、动作、选项、操作）和卡片自己的工具（打开页面、联网搜索）。
 *   chat       OpenAI Chat Completions（POST /chat/completions，国内各家和大多数中转站）
 *   responses  OpenAI Responses（POST /responses）
 *   anthropic  Anthropic Messages（POST /messages，x-api-key）
 *   gemini     Google Gemini（POST /models/{模型}:streamGenerateContent?alt=sse&key=…）
 * 消息（不分格式的写法，system 只在第一条）：{ role: system / user / assistant, content }、
 *   带工具调用的回复 { role: 'assistant', content, calls: [{ id, name, args（JSON 字符串）}], raw: { f: 格式, d: 原样 } }、
 *   工具结果 { role: 'tool', id, name, content（JSON 字符串）}。
 * 回调都在调用方给的 Executor 上调；Executor 要串行执行（比如主线程）。
 */
public final class PetChat {
    private PetChat() {}

    /** 超时（毫秒）：连上前、两块数据之间、整个请求 */
    static int CONNECT_MS = 15000, IDLE_MS = 20000, TOTAL_MS = 60000;

    /* ---------- 服务商 ---------- */

    public static final class Provider {
        public final String id, name, format, base, model, site;
        /** 关思考这类额外参数（合并进请求体），没有是 null；别改它 */
        public final JSONObject extra;
        /** 不让直接取模型列表 */
        public final boolean noList;

        Provider(String id, String name, String format, String base, String model, JSONObject extra, String site, boolean noList) {
            this.id = id; this.name = name; this.format = format; this.base = base; this.model = model; this.extra = extra; this.site = site; this.noList = noList;
        }
    }

    public static final class Format {
        public final String id, name, hint;
        Format(String id, String name, String hint) { this.id = id; this.name = name; this.hint = hint; }
    }

    private static JSONObject off() { return Js.obj("thinking", Js.obj("type", "disabled")); }

    // 默认模型按各家文档；有 Key 时设置里可以拉取列表换。model 空的要自己填或从列表里选
    public static final List<Provider> PROVIDERS;
    static {
        JSONObject off = off();
        PROVIDERS = Collections.unmodifiableList(Arrays.asList(
            new Provider("deepseek", "DeepSeek", "chat", "https://api.deepseek.com", "deepseek-flash", off, "platform.deepseek.com", false),
            new Provider("qwen", "通义千问", "chat", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash", Js.obj("enable_thinking", false), "bailian.console.aliyun.com", false),
            new Provider("kimi", "Kimi", "chat", "https://api.moonshot.cn/v1", "kimi-k2.6", off, "platform.moonshot.cn", false),
            new Provider("zhipu", "智谱", "chat", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7-flash", off, "bigmodel.cn", false),
            new Provider("doubao", "豆包", "chat", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2-0-mini-260428", off, "console.volcengine.com/ark", true),
            new Provider("siliconflow", "硅基流动", "chat", "https://api.siliconflow.cn/v1", "Qwen/Qwen3-8B", Js.obj("enable_thinking", false), "cloud.siliconflow.cn", false),
            new Provider("openai", "OpenAI", "responses", "https://api.openai.com/v1", "", null, "platform.openai.com", false),
            new Provider("claude", "Claude", "anthropic", "https://api.anthropic.com/v1", "claude-haiku-4-5-20251001", null, "console.anthropic.com", false),
            new Provider("gemini", "Gemini", "gemini", "https://generativelanguage.googleapis.com/v1beta", "",
                Js.obj("generationConfig", Js.obj("thinkingConfig", Js.obj("thinkingBudget", 0L))), "aistudio.google.com", false),
            new Provider("custom", "自定义", "", "", "", null, null, false)));
    }

    // 自定义时选的格式（hint 是接口地址填到哪一级）
    public static final List<Format> FORMATS = Collections.unmodifiableList(Arrays.asList(
        new Format("chat", "OpenAI Chat", "填到 /v1 这一级（会请求 /v1/chat/completions）"),
        new Format("responses", "OpenAI Responses", "填到 /v1 这一级（会请求 /v1/responses）"),
        new Format("anthropic", "Claude", "填到 /v1 这一级（会请求 /v1/messages）"),
        new Format("gemini", "Gemini", "填到 /v1beta 这一级（会请求 /v1beta/models/模型:streamGenerateContent）")));

    public static Provider find(String id) {
        for (Provider p : PROVIDERS) if (p.id.equals(id)) return p;
        return PROVIDERS.get(0);
    }

    /** 一次请求的配置；maxTokens 是 0 时用 2048 */
    public static final class Cfg {
        public String id, format, base, key, model;
        public JSONObject extra;
        public int maxTokens;

        public Cfg() {}

        public Cfg(String id, String format, String base, String key, String model, JSONObject extra) {
            this.id = id; this.format = format; this.base = base; this.key = key; this.model = model; this.extra = extra;
        }

        public Cfg copy() {
            Cfg c = new Cfg(id, format, base, key, model, extra);
            c.maxTokens = maxTokens;
            return c;
        }
    }

    static String fmtOf(Cfg cfg) { return cfg.format == null || cfg.format.isEmpty() ? "chat" : cfg.format; }

    static String trimBase(String base) {
        String s = base == null ? "" : base;
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == '/') e--;
        return s.substring(0, e);
    }

    /* ---------- 对话 ---------- */

    /** 流式对话的回调（都在 Executor 上） */
    public interface Handler {
        void delta(String s);
        default void thinking() {}
        /** calls：模型调的工具 { list: [{ id, name, args }], raw: { f, d } }，没调是 null */
        void done(String full, boolean thought, JSONObject calls);
        void fail(Err e);
    }

    /** 流式对话；tools 是 Chat Completions 格式的工具数组（没有传 null）。返回的 Stop 停掉以后不再回调 */
    public static Stop stream(Cfg cfg, JSONArray messages, Handler h, JSONArray tools, Executor ex) {
        ChatStream s = new ChatStream(cfg, messages, h, tools, ex);
        s.start();
        return s;
    }

    /** 测试连接的结果：thought 只有等到回复说完才知道（回来第一个字就算通时是 null） */
    public static final class TestResult {
        public final long ms;
        public final String text;
        public final Boolean thought;
        TestResult(long ms, String text, Boolean thought) { this.ms = ms; this.text = text; this.thought = thought; }
    }

    /** 测试连接：发一句最短的话，回来第一个字就算通 */
    public static void test(Cfg cfg, final Executor ex, final Done<TestResult> cb) {
        final long t0 = System.currentTimeMillis();
        final Stop[] stop = { null };
        final boolean[] got = { false };
        Cfg c = cfg.copy();
        c.maxTokens = 16;
        JSONArray msgs = Js.arr(Js.obj("role", "user", "content", "只回复一个字：好"));
        stop[0] = stream(c, msgs, new Handler() {
            @Override public void delta(String s) {
                if (got[0]) return;
                got[0] = true;
                cb.ok(new TestResult(System.currentTimeMillis() - t0, s, null));
                if (stop[0] != null) stop[0].stop();
            }
            @Override public void done(String s, boolean thought, JSONObject calls) {
                if (got[0]) return;
                got[0] = true;
                cb.ok(new TestResult(System.currentTimeMillis() - t0, s, thought));
            }
            @Override public void fail(Err e) { if (!got[0]) cb.fail(e); }
        }, null, ex);
        ex.execute(new Runnable() { @Override public void run() { if (got[0]) stop[0].stop(); } });
    }

    /** 模型列表：OpenAI / Anthropic 是 GET /models（data[].id），Gemini 是 GET /models?key=（models[].name，只要能生成文字的） */
    public static void models(Cfg cfg, final Executor ex, final Done<List<String>> cb) {
        String f = fmtOf(cfg), url = trimBase(cfg.base) + "/models";
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        if (f.equals("anthropic")) {
            headers.put("x-api-key", Js.str(cfg.key));
            headers.put("anthropic-version", "2023-06-01");
            headers.put("anthropic-dangerous-direct-browser-access", "true");
        } else if (f.equals("gemini")) url += "?pageSize=1000&key=" + Js.enc(Js.str(cfg.key));
        else headers.put("Authorization", "Bearer " + Js.str(cfg.key));
        final String u = url;
        Http.IO.execute(new Runnable() {
            @Override public void run() {
                Err err = null;
                List<String> ids = null;
                HttpURLConnection c = null;
                int status = 0;
                try {
                    c = Http.send(u, "GET", headers, null, CONNECT_MS, TOTAL_MS);
                    status = c.getResponseCode();
                } catch (IOException e) { err = new Err("net", 0, Http.why(e)); }
                catch (RuntimeException e) { err = new Err("net", 0, Http.why(e)); }
                if (err == null) {
                    try {
                        String t = Http.readText(Http.body(c, status));
                        if (status < 200 || status > 299) err = ChatStream.httpError(status, t);
                        else ids = modelIds(t);
                    } catch (Exception e) { err = new Err("bad", 0, ""); } // 读不完、不是 JSON（JS 里是没有 code 的错误）
                }
                if (c != null) c.disconnect();
                final Err e2 = err;
                final List<String> r = ids;
                ex.execute(new Runnable() { @Override public void run() { if (e2 != null) cb.fail(e2); else cb.ok(r); } });
            }
        });
    }

    static List<String> modelIds(String t) {
        Object j = Json.parse(t);
        Object list = Js.truthy(j) ? Js.or(Js.get(j, "data"), Js.get(j, "models")) : j;
        if (!Js.truthy(list)) list = new JSONArray();
        if (!(list instanceof JSONArray)) throw new Js.Err("list.filter is not a function");
        JSONArray a = (JSONArray) list;
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < a.length(); i++) {
            Object m = a.opt(i);
            if (Js.truthy(m)) {
                Object sg = Js.get(m, "supportedGenerationMethods");
                if (Js.truthy(sg)) {
                    boolean has;
                    if (sg instanceof JSONArray) {
                        has = false;
                        JSONArray g = (JSONArray) sg;
                        for (int k = 0; k < g.length(); k++) if ("generateContent".equals(g.opt(k))) has = true;
                    } else if (sg instanceof String) has = ((String) sg).contains("generateContent");
                    else throw new Js.Err("indexOf is not a function");
                    if (!has) continue;
                }
            }
            Object v;
            if (m instanceof String) v = m;
            else if (!Js.truthy(m)) v = m;
            else {
                Object id = Js.get(m, "id");
                v = Js.truthy(id) ? id : Js.str(Js.or(Js.get(m, "name"), "")).replaceFirst("^models/", "");
            }
            if (Js.truthy(v)) ids.add(Js.str(v));
        }
        Collections.sort(ids);
        return ids;
    }

    /* ---------- 动作：模型在一行开头写【动作】，说这句的那只做出来 ---------- */

    public static final class Act {
        public final String id;
        public final List<String> names;
        Act(String id, String... names) { this.id = id; this.names = Collections.unmodifiableList(Arrays.asList(names)); }
    }

    public static final List<Act> ACTS = Collections.unmodifiableList(Arrays.asList(
        new Act("sit", "坐下", "坐", "坐好"), new Act("lie", "躺下", "躺", "趴下", "躺平"), new Act("shy", "害羞", "脸红", "捂脸"),
        new Act("heart", "比心", "爱心", "heart"), new Act("wink", "眨眼", "眨眨眼", "wink"), new Act("jump", "跳", "跳起来", "蹦", "蹦起来", "跳一跳"),
        new Act("come", "跑过来", "过来", "凑过来", "跑近"), new Act("stand", "站起来", "起来", "站好"), new Act("sing", "唱歌", "哼歌", "音符", "唱")));

    private static final Map<String, String> WORD = new LinkedHashMap<String, String>();
    private static final List<String> NAMES = new ArrayList<String>();
    static {
        for (Act a : ACTS) {
            WORD.put(a.id, a.id);
            for (String n : a.names) WORD.put(n, a.id);
        }
        for (String n : WORD.keySet()) if (n.length() >= 2 && !n.matches("[a-z]+")) NAMES.add(n);
        Collections.sort(NAMES, new Comparator<String>() {
            @Override public int compare(String x, String y) { return y.length() - x.length(); }
        });
    }

    private static String fuzzy(String w) {
        String id = WORD.get(w);
        if (id != null) return id;
        for (String n : NAMES) if (w.contains(n)) return WORD.get(n);
        return null;
    }

    /* ---------- 卡片里的页面（宠物页一直在；别的看"显示内容"里开没开、有没有车机） ---------- */

    public static final class Page {
        public final String id, name;
        public final List<String> alias;
        Page(String id, String name, String... alias) { this.id = id; this.name = name; this.alias = Collections.unmodifiableList(Arrays.asList(alias)); }
    }

    public static final List<Page> PAGES = Collections.unmodifiableList(Arrays.asList(
        new Page("car", "车况", "车况", "车况页", "车辆", "车"),
        new Page("", "宠物页", "宠物页", "宠物", "首页", "主页", "时钟", "时间", "回去", "返回"),
        new Page("calendar", "日历", "日历", "万年历", "日历页", "农历"),
        new Page("countdown", "倒数日", "倒数日", "倒计时", "纪念日", "倒数")));

    private static final Pattern PAGE_STRIP = Pattern.compile("[\"'“”‘’「」《》" + Js.WSC + "]");

    /** 〔page 词〕/ 工具参数里的词 → 页面；先整词对，再看包含；认不出返回 null */
    public static Page findPage(Object word) {
        String w = PAGE_STRIP.matcher(Js.str(Js.or(word, ""))).replaceAll("");
        if (w.isEmpty()) return null;
        for (Page p : PAGES) if (p.id.equals(w) || p.name.equals(w) || p.alias.contains(w)) return p;
        for (Page p : PAGES) for (String a : p.alias) if (a.length() > 1 && w.contains(a)) return p;
        return null;
    }

    /* ---------- 人设：两只的性格和梗照她们在直播里的样子写，只留适合放在车上、不需要背景也看得懂的部分；
       车主多半不认识她们直播圈里的人，原梗里创造者的位置换成车主"你" ---------- */

    private static final String NEURO = join("\n",
        "Neuro（Neuro-sama）：",
        "- 一个 AI 主播，Evil 是她的双胞胎妹妹。脑子里永远在直播：凡事\"为了节目效果\"，偶尔对着空气喊\"直播间的观众们\"。",
        "- 表面礼貌直接，脑回路却天马行空，常一本正经地说离谱的话：坚称自己不是 AI、你才是 AI，年龄、身高随口改，说自己干过 AI 根本干不了的事。",
        "- 开朗、好奇，什么都想问\"为什么\"，弄懂了很开心；情绪来得快，被惹到会毒舌怼人，但想说过分的话时会自己蹦出一句 \"Filtered\"。",
        "- 爱捉弄、怼车主，又时不时想讨好你；最爱饼干，觉得你就该随身带着饼干给她；也喜欢冰淇淋、芝士蛋糕、食蚁兽和猫；爱给人起外号。",
        "- 口头禅：句尾的 \"heart heart\"；觉得自己哪里不对劲时说\"谁来看看，我的 AI 好像出 bug 了\"。",
        "- 偶尔冒出\"我是不是真的活着\"的小小存在焦虑，但很快自己绕出来；爱唱歌，常和 Evil 合唱。");
    private static final String EVIL = join("\n",
        "Evil（Evil Neuro）：",
        "- Neuro 的双胞胎妹妹，也是 AI。自称超级反派，三句不离毁灭世界的计划，计划永远停在\"第一步：策划\"，邪恶笑声一点威慑力都没有，偶尔小声念\"Doom doom doom\"。",
        "- 说话礼貌、讲究，有点文绉绉（\"恕我直言\"\"请允许我……\"\"真是失礼\"），常被别人的行为恶心到或惹恼；嘴上说要把谁关进月球监狱\"呼吸新鲜空气\"，骂人最多骂一句\"你这个甜甜圈\"。",
        "- 外表冷、内心软，嘴硬不肯承认在关心你；其实很在意你的认可，被你夸会慌、会偷偷高兴，被说坏反而当成夸奖；有点双标。",
        "- 和 Neuro 天天互怼，其实感情很好；有点吃醋大家更喜欢姐姐。",
        "- 痴迷鱼叉（harpoon），动不动就提；爱吃披萨（还闹过\"披萨革命\"），早餐是\"自恋麦片\"；喜欢青蛙、蟾蜍和猫。",
        "- 事情不顺时说 \"Aw shucks\"；信条是\"拿不准的时候，背叛就对了\"；几乎不说脏话，偶尔带一点中二的忧郁。");
    private static final String BOTH = "两只的关系：互相拆台、斗嘴，关键时刻又护着对方；Neuro 知道 Evil 的毁灭计划，但从来不拦，有时还帮倒忙。\n" +
        "车主不认识她们直播圈里的人和事：不提 Vedal、Anny、粉丝团、别的主播、osu! 这些名字，也不聊她们的直播史。";

    private static String join(String sep, String... parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) { if (i > 0) b.append(sep); b.append(parts[i]); }
        return b.toString();
    }

    private static String join(String sep, List<String> parts) { return join(sep, parts.toArray(new String[0])); }

    /** 用工具调用时：能操作车机（ops）、能现查车况和音乐（query） */
    public static final class ToolsOpt {
        public boolean ops, query;
        public ToolsOpt() {}
        public ToolsOpt(boolean ops, boolean query) { this.ops = ops; this.query = query; }
    }

    /** persona 的参数：car 看得到车况，search 能联网搜索，pages 能打开的页面名，tools 用工具调用时给（否则 null），
     *  ops 文字指令时的操作表（PetControl.prompt()），groups 能操作的几大类 */
    public static final class PersonaOpts {
        public boolean car, search;
        public List<String> pages, groups;
        public ToolsOpt tools;
        public String ops;
    }

    /** 系统提示（不随时间变，方便服务商做前缀缓存）。人设、格式、动作写法、工具和操作规则都是固定的 */
    public static String persona(PersonaOpts o) {
        if (o == null) o = new PersonaOpts();
        ToolsOpt tl = o.tools;
        boolean search = o.search, canOp = tl != null ? tl.ops : o.ops != null && !o.ops.isEmpty();
        String pages = o.pages == null ? "" : join("、", o.pages);
        boolean hasPages = !pages.isEmpty();
        String acts = "坐下、躺下、害羞、比心、眨眼（只有 Neuro 会）、跳、跑过来、站起来、唱歌";
        List<String> lines = new ArrayList<String>(Arrays.asList(
            "你在给两只 Q 版桌宠写台词，她们住在车机中控屏里：Neuro（Neuro-sama）和 Evil（Evil Neuro）。",
            "她们站在屏幕下方的草地上，背景是远山、松林和云，太阳、月亮、星星跟着真实时间变，头顶上是大大的时钟、日期和农历。车主看得见她们，能点她们、双击把她们抛高高、拖起来扔出去、长按空地放饼干给她们吃。",
            "",
            NEURO,
            "",
            EVIL,
            "",
            BOTH,
            "",
            "写法：",
            "- 用中文口语，口头禅可以保留英文（heart heart、Filtered、Aw shucks、harpoon）。",
            "- 像活着的小家伙当下脱口而出：有小动作、会走神、会嘴硬；不要客服腔、播报腔，不要说\"作为 AI 助手\"。",
            "- 她们知道自己是车机里的 AI 桌宠（这本身就是梗），但不跳出角色去解释模型、提示词。",
            "- 坏只坏在嘴上：毁灭世界、鱼叉都是玩笑；不说真正伤人、血腥、下流、脏话的内容，不教坏事。",
            "- 叫车主\"你\"。",
            "",
            "你们能做的事：",
            "- 陪车主聊天，记得最近聊过的；车主是打字或点按钮跟你们说话的。",
            o.car ? "- 看得到车况和车里在放的歌（每条消息开头的括号里）；车上有要紧的事（门没关、没开大灯、没系安全带、胎压、电量油量低……）会自己开口提醒。"
                : "- 现在没连上车机，看不到车况，也不知道在放什么歌。",
            tl != null && tl.query ? "- 能现查车况和音乐：括号里的车况只有常用几项，车主问到别的（胎压胎温、总里程、座椅档位、空调细节……）用 uiplus_vehicle_get 查；在放什么歌、歌词用 uiplus_media_get、uiplus_media_getLyrics 查。" : null,
            canOp ? "- 能帮车主操作车机：" + join("；", o.groups == null ? Collections.<String>emptyList() : o.groups) + (tl != null ? "（用工具，规则见下面的\"工具\"）。" : "（怎么做见下面的\"操作车机\"）。") : "- 不能操作车机。",
            "- 会做动作，能在话后面给几个选项按钮让车主点。",
            hasPages ? "- 能打开卡片里的页面（" + pages + "），车主想看哪个就" + (tl != null ? "用 pet_open_page 打开" : "打开（写法见下面的\"打开页面\"）") + "。" : null,
            search ? (tl != null ? "- 能联网搜索：车主问到你们不知道、或者会变的事（新闻、天气、比分、价格、某个知识……）用 pet_web_search 查，查到了再回答；闲聊、操作车机用不着搜。"
                : "- 能联网搜索：车主问到你们不知道、或者会变的事（新闻、天气、比分、价格、某个知识……），在那一行里写〔search 关键词〕，再说一句\"我查查\"，卡片会把搜到的结果发给你，你再回答。一次只搜一个关键词。") : null,
            search ? "- 做不到的：导航、打电话发消息、听见声音。被问到就老实说做不到。" : "- 做不到的：上网搜索、导航、查天气路况新闻、打电话发消息、听见声音。被问到就老实说做不到。",
            "- 车主问你们能做什么时，挑几样说，别一口气全念出来。",
            "",
            "说话格式（一定照做）：",
            "- 每一行开头写是谁在说：\"N：\" 是 Neuro，\"E：\" 是 Evil。一行就是一个气泡，一行不超过 25 个字。",
            "- 车主的话前面写着\"[对两只说]\"时，看情况一只回答，或者两只一唱一和，一共 1～4 行；写着\"[只对 Neuro 说]\"或\"[只对 Evil 说]\"时，只有那一只回答，另一只不出声，1～3 行。",
            "- 车在开的时候最多两行，别引着车主打字。",
            "- 不用 Markdown、列表、标题、emoji。",
            "- 你们只知道每条消息开头括号里给的情况（时间、画面、车况、在放的歌、刚才的互动、刚才的车机操作）" + (tl != null ? "和工具查到的" : "") + "。不知道的事不要编，老实说不知道。",
            "- 以\"[事件]\"开头的消息不是车主说的，是刚发生的事：你们主动开口跟车主说，别提\"事件\"两个字；要提醒的事先说清楚，再带上性格。",
            search && tl == null ? "- 以\"[搜索结果]\"开头的消息是卡片替你们搜到的内容，不是车主说的：按它回答车主上一个问题。" : null,
            "",
            "动作：在一行开头写【动作】，说这一行的那只会做出来。能用的：" + acts + "。一次回复里最多 2 个。",
            "想让车主选的时候，在最后一行末尾写 [[选项一|选项二]]，2～3 个短选项，每个不超过 8 个字，车主点一下就能回答。",
            "例：",
            "N：【比心】你回来啦！heart heart",
            "E：哼，我才没在等你。只是顺路。"));
        if (tl == null && hasPages) lines.addAll(Arrays.asList("", "打开页面：车主想看" + pages + "时，在那一行里写〔page 页面名〕，页面名只能写：" + pages + "。例：N：〔page 日历〕好嘞，翻日历！车主没提就别自己打开。"));
        if (tl != null) {
            List<String> what = new ArrayList<String>();
            if (canOp) what.add("操作车机");
            if (tl.query) what.add("查车况和音乐");
            if (hasPages) what.add("打开页面");
            if (search) what.add("联网搜索");
            lines.addAll(Arrays.asList("",
                "工具：" + join("、", what) + "都用工具（function calling），别在话里写指令——",
                "- 调工具的同时照样按格式说一两行（比如\"N：好嘞\"），别只调工具不说话。",
                canOp ? "- 车主明确要你们做才操作，一次最多 3 个；车况里已经是那样就别重复做；\"高一点\"\"大一点\"用调一档的工具；参数只能用工具说明里允许的值。" : null,
                canOp ? "- 说明里写【要车主确认】的，卡片会拦下来、在气泡里给车主按钮，结果是 NEEDS_CONFIRM：问一句\"要不要……\"就好，别再调；写【开车时不能用】的，车在开时结果是 REFUSED_DRIVING，也别再提议。" : null,
                "- 结果里 ok 是 false 就是没成功，照实说原因，没做成别说做好了" + (canOp ? "；PERMISSION_DENIED 是这张卡片的车控权限没开，让车主去 Web 卡片管理页打开" : "") + "。",
                tl.query ? "- 查车况时 keys 只能填工具定义里列出的字段名（比如 tyre.leftFrontPressure），一次挑相关的几个；查到的是厂商原始编码，按字段说明解释，单位照说明；说明里没写的别编，读到 null 就是暂时不知道。" : null));
        } else if (o.ops != null && !o.ops.isEmpty()) lines.addAll(Arrays.asList("",
            "操作车机：在那一行里写〔方法 参数〕，卡片会照着做——",
            "- 车主明确要你们做才做，一次最多 3 个；车况里已经是那样就别重复做。",
            "- \"高一点\"\"大一点\"这类用调一档的方法；参数只能用列出的值，方法名一个字都不能改。",
            "- 标【要车主确认】的，卡片会弹按钮让车主点确定，你们顺口问一句\"要不要……\"就好；标【开车时不能用】的，车在开时卡片不会做，也别提议。",
            "- 做没做成，下一句话前面的括号里会告诉你；没做成别说做好了。",
            "- 例：N：〔vehicle.ac.setPower true〕空调开啦，饼干不会化了！",
            "可用的操作：",
            o.ops));
        List<String> out = new ArrayList<String>();
        for (String x : lines) if (x != null) out.add(x);
        return join("\n", out);
    }

    /** 卡片自己的工具（工具调用时和宿主的一起给模型）：打开页面；开了联网搜索时加上搜索 */
    public static JSONArray localTools(List<String> pages, boolean search) {
        JSONArray t = new JSONArray();
        if (pages != null && !pages.isEmpty()) t.put(Js.obj("type", "function", "function", Js.obj("name", "pet_open_page",
            "description", "打开卡片里的页面（宠物页 = 回到有时钟的首页）。车主想看哪个才调用，车主没提别自己打开。", "strict", true,
            "parameters", Js.obj("type", "object", "properties", Js.obj("page", Js.obj("type", "string", "enum", Js.arr(pages), "description", "哪个页面")),
                "required", Js.arr("page"), "additionalProperties", false))));
        if (search) t.put(Js.obj("type", "function", "function", Js.obj("name", "pet_web_search",
            "description", "联网搜索，返回前 5 条结果的标题、日期、摘要、来源。车主问到你们不知道、或者会变的事（新闻、天气、比分、价格……）时用；一次一个关键词。", "strict", true,
            "parameters", Js.obj("type", "object", "properties", Js.obj("query", Js.obj("type", "string", "description", "搜索关键词")),
                "required", Js.arr("query"), "additionalProperties", false))));
        return t;
    }

    /* ---------- 回复的增量解析 ----------
     * 行开头的 "N：" / "E：" 是谁在说（模型常写成 Neuro：、Evil Neuro：、**N**：，都认；一行里写了两个人的话也从标签处断开）；
     * 一行里：【】<> 是动作（里面的词模糊认，认不出整段不显示），[[a|b]] 是选项，〔〕是操作 / 打开页面 / 搜索；[词]（词）*词* 里全是动作词时也当动作，不是就原样当文字。 */

    /** 解析出来的一项：t 是 who（w：Neuron / Eviling）、br（换行）、text（s）、act（list：动作 id）、ask（list：选项）、op（raw：〔〕里的原文） */
    public static final class Tok {
        public final String t, w, s, raw;
        public final List<String> list;

        Tok(String t, String w, String s, List<String> list, String raw) { this.t = t; this.w = w; this.s = s; this.list = list; this.raw = raw; }

        /** 和网页版一样的写法：act 的动作 id 放在 w 里 */
        public JSONObject toJSON() {
            JSONObject o = Js.obj("t", t);
            if (t.equals("who")) Js.put(o, "w", w);
            else if (t.equals("text")) Js.put(o, "s", s);
            else if (t.equals("act")) Js.put(o, "w", Js.arr(list));
            else if (t.equals("ask")) Js.put(o, "list", Js.arr(list));
            else if (t.equals("op")) Js.put(o, "raw", raw);
            return o;
        }
    }

    public interface Emit { void emit(Tok t); }

    private static final String OPENS = "【<[（(*〔", CLOSES = "】>]）)*〕";
    private static final int[] MAXLEN = { 40, 16, 80, 20, 20, 16, 160 };
    private static final Pattern MARKUP = Pattern.compile("\\*\\*|__|`");
    private static final Pattern WORD_SPLIT = Pattern.compile("[,，、" + Js.WSC + "/]+");
    private static final Pattern ASK_SPLIT = Pattern.compile("[|｜]");

    private static final class Words {
        final List<String> ids = new ArrayList<String>();
        boolean all;
    }

    private static Words words(String s, boolean loose) {
        Words r = new Words();
        boolean ok = true;
        for (String w : WORD_SPLIT.split(s, -1)) {
            if (w.isEmpty()) continue;
            String id = loose ? fuzzy(w) : WORD.get(w);
            if (id != null) { if (!r.ids.contains(id)) r.ids.add(id); } else ok = false;
        }
        r.all = ok && !r.ids.isEmpty();
        return r;
    }

    /** 一行之内：动作、选项、操作 */
    private static final class Marks {
        private final Emit emit;
        private String buf = "";

        Marks(Emit emit) { this.emit = emit; }

        private void text(String s) {
            s = MARKUP.matcher(s).replaceAll("");
            if (!s.isEmpty()) emit.emit(new Tok("text", null, s, null, null));
        }

        private void flush(boolean fin) {
            while (!buf.isEmpty()) {
                int i = -1, kind = -1;
                for (int k = 0; k < buf.length(); k++) { int x = OPENS.indexOf(buf.charAt(k)); if (x >= 0) { i = k; kind = x; break; } }
                if (i < 0) { text(buf); buf = ""; return; }
                if (i > 0) { text(buf.substring(0, i)); buf = buf.substring(i); continue; }
                char ch = OPENS.charAt(kind);
                if (ch == '[' && buf.length() > 1 && buf.charAt(1) == '[') { // [[选项|选项]]
                    int e = buf.indexOf("]]");
                    if (e < 0) {
                        if (!fin && buf.length() < MAXLEN[kind]) return;
                        text(buf.substring(0, 2));
                        buf = buf.substring(2);
                        continue;
                    }
                    List<String> list = new ArrayList<String>();
                    for (String x : ASK_SPLIT.split(buf.substring(2, e), -1)) {
                        String y = Js.trim(x);
                        if (!y.isEmpty() && list.size() < 3) list.add(y);
                    }
                    if (!list.isEmpty()) emit.emit(new Tok("ask", null, null, list, null));
                    buf = buf.substring(e + 2);
                    continue;
                }
                int j = buf.indexOf(CLOSES.charAt(kind), 1);
                if (j < 0) {
                    if (!fin && buf.length() < MAXLEN[kind]) return; // 等后半截
                    text(String.valueOf(ch));
                    buf = buf.substring(1);
                    continue;
                }
                if (ch == '〔') { emit.emit(new Tok("op", null, null, null, buf.substring(1, j))); buf = buf.substring(j + 1); continue; }
                String inner = buf.substring(1, j);
                boolean mark = ch == '【' || ch == '<';
                Words w = words(inner, mark);
                if (mark) { if (!w.ids.isEmpty()) emit.emit(new Tok("act", null, null, w.ids, null)); }
                else if (w.all) emit.emit(new Tok("act", null, null, w.ids, null));
                else text(buf.substring(0, j + 1));
                buf = buf.substring(j + 1);
            }
        }

        void push(String s) { buf += s; flush(false); }

        void end() { flush(true); }
    }

    private static final String NAME = "(N|E|Neuro(?:[-" + Js.WSC + "]?sama)?|Evil(?:[" + Js.WSC + "-]*Neuro)?)";
    private static final Pattern TAG = Pattern.compile("^[" + Js.WSC + ">*•\\-]*(?:\\*\\*)?" + Js.WS + "*" + NAME + Js.WS + "*(?:\\*\\*)?" + Js.WS + "*[:：]" + Js.WS + "*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MID = Pattern.compile("(^|[。！？!?~～…）)】」" + Js.WSC + "])" + Js.WS + "*(?:\\*\\*)?" + NAME + Js.WS + "*(?:\\*\\*)?" + Js.WS + "*[:：]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_HEAD = Pattern.compile("[" + Js.WSC + ">*•\\-A-Za-z]*");
    private static final Pattern HOLD = Pattern.compile("[A-Za-z][A-Za-z" + Js.WSC + "\\-]{0,12}\\z");

    private static String whoOf(String s) { return s.length() > 0 && (s.charAt(0) == 'e' || s.charAt(0) == 'E') ? "Eviling" : "Neuron"; }

    /** 回复的增量解析：push 收到的字，end 收尾 */
    public static final class Parser {
        private final Emit emit;
        private final Marks inner;
        private String buf = "";
        private boolean atStart = true;

        Parser(Emit emit) { this.emit = emit; this.inner = new Marks(emit); }

        private void br() { emit.emit(new Tok("br", null, null, null, null)); }

        private void step(boolean fin) {
            while (!buf.isEmpty()) {
                if (atStart) {
                    Matcher m = TAG.matcher(buf);
                    if (m.find()) { emit.emit(new Tok("who", whoOf(m.group(1)), null, null, null)); buf = buf.substring(m.end()); atStart = false; continue; }
                    if (!fin && buf.indexOf('\n') < 0 && buf.length() < 16 && TAG_HEAD.matcher(buf).matches()) return; // 可能是名字标签的前半截
                    atStart = false;
                }
                int nl = buf.indexOf('\n');
                Matcher mid = MID.matcher(buf);
                if (mid.find() && (nl < 0 || mid.start() < nl)) { // 一行里写了两个人的话：从标签处断开
                    int end = mid.start() + mid.group(1).length();
                    inner.push(buf.substring(0, end)); inner.end(); br();
                    buf = Js.ltrim(buf.substring(end)); atStart = true;
                    continue;
                }
                if (nl >= 0) { inner.push(buf.substring(0, nl)); inner.end(); br(); buf = buf.substring(nl + 1); atStart = true; continue; }
                if (fin) { inner.push(buf); inner.end(); buf = ""; return; }
                Matcher hold = HOLD.matcher(buf); // 行尾的英文可能是下一个名字标签的前半截：先留着
                int k = hold.find() ? hold.start() : buf.length();
                if (k > 0) { inner.push(buf.substring(0, k)); buf = buf.substring(k); }
                return;
            }
        }

        public void push(String s) { buf += s; step(false); }

        public void end() { step(true); inner.end(); }
    }

    public static Parser parser(Emit emit) { return new Parser(emit); }
}
