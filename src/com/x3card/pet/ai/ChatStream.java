package com.x3card.pet.ai;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 一次流式对话（PetChat.stream）。联网在后台线程，读到的数据、超时都排到调用方的 Executor 上处理，
 * 所以解析、回调都在那一个线程上依次进行（Executor 要串行执行，比如主线程）。
 */
final class ChatStream implements Stop {
    /** '格式|地址|模型' → 这个模型要发最简请求 / 工具不能带 strict */
    static final Set<String> bare = newSet(), loose = newSet();

    private static Set<String> newSet() { return java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>()); }

    private static final Pattern DATA_LINE = Pattern.compile("(?:^|[\\n\\r\\u2028\\u2029])" + Js.WS + "*data:");
    private static final Pattern DATA_START = Pattern.compile("(?:^|[\\n\\r\\u2028\\u2029])data:");
    private static final Pattern KEY = Pattern.compile("api[ _-]?key" + Js.DOT + "*(invalid|not valid)|authentication", Pattern.CASE_INSENSITIVE);
    private static final Pattern BALANCE = Pattern.compile("balance|余额|欠费|quota|insufficient|credit", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODEL = Pattern.compile("model" + Js.DOT + "*(not|exist|found)|模型" + Js.DOT + "*(不存在|没有)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OVERLOADED = Pattern.compile("overloaded", Pattern.CASE_INSENSITIVE);

    private final PetChat.Cfg cfg;
    private final JSONArray messages, tools;
    private final PetChat.Handler h;
    private final Executor ex;
    private final String fmt, key;

    private volatile boolean over;
    private volatile HttpURLConnection conn;
    private ScheduledFuture<?> idleTimer, totalTimer;
    private int idleTok, gen;

    private String full = "", buf = "", raw = "";
    private boolean sse, thought;
    private Http.Dec dec;

    // 工具调用：各家的写法拼成 { list: [{ id, name, args }], raw: { f, d } }（raw 下次原样带回）
    private final Sparse chatCalls = new Sparse(), anBlocks = new Sparse();
    private final JSONObject chatMsg = new JSONObject();
    private final JSONArray rsItems = new JSONArray();
    private JSONArray rsOutput;
    private final List<JSONObject> gmParts = new ArrayList<JSONObject>();
    private int gid;

    ChatStream(PetChat.Cfg cfg, JSONArray messages, PetChat.Handler h, JSONArray tools, Executor ex) {
        this.cfg = cfg;
        this.messages = messages;
        this.h = h;
        this.tools = tools != null && tools.length() > 0 ? tools : null;
        this.ex = ex;
        this.fmt = PetChat.fmtOf(cfg);
        this.key = fmt + "|" + Js.str(cfg.base) + "|" + Js.str(cfg.model);
    }

    void start() {
        totalTimer = Http.later(PetChat.TOTAL_MS, new Runnable() {
            @Override public void run() {
                ex.execute(new Runnable() { @Override public void run() { if (!over) finish(timeout()); } });
            }
        });
        send(bare.contains(key) ? 2 : tools != null && loose.contains(key) ? 1 : 0);
    }

    @Override public void stop() {
        if (over) return;
        over = true;
        clear();
        Http.drop(conn);
    }

    private static Err timeout() { return new Err("timeout", 0, ""); }

    private void clear() {
        Http.cancel(idleTimer);
        Http.cancel(totalTimer);
        idleTok++;
    }

    private void finish(Err err) {
        if (over) return;
        over = true;
        clear();
        Http.drop(conn);
        if (err != null) h.fail(err); else h.done(full, thought, collected()); // thought：模型想过（有思考内容）
    }

    private void arm(int ms) {
        Http.cancel(idleTimer);
        final int t = ++idleTok;
        idleTimer = Http.later(ms, new Runnable() {
            @Override public void run() {
                ex.execute(new Runnable() { @Override public void run() { if (!over && t == idleTok) finish(timeout()); } });
            }
        });
    }

    /** 后台线程读到的东西排到 Executor 上；处理时抛错（和 JS 一样）算连不上 */
    private void post(final int g, final Runnable r) {
        ex.execute(new Runnable() {
            @Override public void run() {
                if (over || g != gen) return;
                try { r.run(); }
                catch (RuntimeException e) {
                    if (over) throw e;
                    finish(new Err("net", 0, Js.head(Http.why(e), 160)));
                }
            }
        });
    }

    // 温度不传，用各家默认（有的模型只许固定值）。stage：0 全带，1 工具去掉 strict，2 再去掉关思考这些参数（最简）
    private void send(final int stage) {
        final Req rq = request(stage < 2, stage >= 1);
        arm(PetChat.CONNECT_MS);
        final int g = ++gen;
        Http.IO.execute(new Runnable() { @Override public void run() { io(g, stage, rq); } });
    }

    /** 后台线程：发请求、一块块读回来 */
    private void io(final int g, final int stage, Req rq) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            if (over) return;
            c = Http.send(rq.url, "POST", rq.headers, rq.body, PetChat.CONNECT_MS + 5000, PetChat.TOTAL_MS + 5000);
            conn = c;
            if (over) { Http.drop(c); return; }
            final int status = c.getResponseCode();
            if (status < 200 || status > 299) {
                final String t = Http.readText(Http.body(c, status));
                post(g, new Runnable() { @Override public void run() { httpFail(stage, status, t); } });
                return;
            }
            post(g, new Runnable() { @Override public void run() { opened(stage); } });
            in = c.getInputStream();
            byte[] b = new byte[8192];
            for (;;) {
                int n = in.read(b);
                if (over) break;
                if (n < 0) {
                    post(g, new Runnable() { @Override public void run() { ended(); } });
                    break;
                }
                if (n == 0) continue;
                final byte[] chunk = java.util.Arrays.copyOf(b, n);
                post(g, new Runnable() { @Override public void run() { chunk(chunk); } });
            }
        } catch (final IOException e) {
            if (!over) post(g, new Runnable() { @Override public void run() { netFail(e); } });
        } catch (final RuntimeException e) {
            if (!over) post(g, new Runnable() { @Override public void run() { netFail(e); } });
        } finally {
            if (in != null) try { in.close(); } catch (IOException e) { /* 关不掉就算了 */ }
        }
    }

    private void netFail(Throwable e) {
        finish(new Err(e instanceof SocketTimeoutException ? "timeout" : "net", 0, Js.head(Http.why(e), 160)));
    }

    private void httpFail(int stage, int status, String t) {
        Err err = httpError(status, t);
        // 不认某个参数：换个简单点的再试（明说没有这个模型、没钱的就不白发了）；带工具的都试过了还不行：这个模型 / 中转站不支持工具调用
        if ((status == 400 || status == 422) && !"model".equals(err.code) && !"balance".equals(err.code) && !"key".equals(err.code)) {
            int nx = tools != null && stage == 0 ? 1 : stage < 2 ? 2 : -1;
            if (nx > 0) { send(nx); return; }
            if (tools != null) { finish(new Err("notools", status, err.msg)); return; }
        }
        finish(err);
    }

    private void opened(int stage) {
        if (stage == 1) loose.add(key);
        if (stage == 2) { bare.add(key); loose.add(key); }
        dec = new Http.Dec();
        buf = "";
        raw = "";
        arm(PetChat.IDLE_MS);
    }

    private void chunk(byte[] b) {
        arm(PetChat.IDLE_MS);
        buf += dec.push(b, false);
        int cut = buf.lastIndexOf('\n');
        if (cut >= 0) {
            String head = buf.substring(0, cut);
            buf = buf.substring(cut + 1);
            if (!sse && raw.length() < 200000 && !DATA_LINE.matcher(head).find()) raw += head + "\n"; // 还没见到 SSE 行：可能是整块 JSON
            else {
                if (!sse) { sse = true; head = raw + head; raw = ""; }
                parse(head);
            }
        }
    }

    private void ended() {
        buf += dec.push(new byte[0], true);
        if (sse) parse(buf); else whole(raw + buf);
        finish(null);
    }

    /* ---------- 解析：SSE 一行一个 data: {...}（event: / 注释行跳过）；有的代理不管 stream，整块回一个 JSON（可能排了版、跨好几行） ---------- */

    private void parse(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length && !over; i++) {
            String ln = Js.trim(lines[i]);
            if (!ln.startsWith("data:")) continue;
            String d = Js.trim(ln.substring(5));
            if (d.equals("[DONE]")) { finish(null); return; }
            eat(d, false);
        }
    }

    private void whole(String text) {
        text = Js.trim(text);
        if (DATA_START.matcher(text).find()) parse(text);
        else if (text.startsWith("{") || text.startsWith("[")) eat(text, true);
    }

    private void say(Object s) {
        if (over || !(s instanceof String) || ((String) s).isEmpty()) return;
        full += (String) s;
        h.delta((String) s);
    }

    private void think() {
        if (over) return;
        thought = true;
        h.thinking();
    }

    private static boolean isObj(Object v) { return v instanceof JSONObject || v instanceof JSONArray; }

    private static boolean has(Object type, String part) { return Js.str(type).contains(part); }

    private void eat(String json, boolean all) {
        Object j;
        try { j = Json.parse(json); } catch (Json.Bad e) { return; }
        if (j instanceof JSONArray) { // Gemini 不带 alt=sse 时是数组
            JSONArray a = (JSONArray) j;
            for (int i = 0; i < a.length(); i++) eat(Json.stringify(a.opt(i)), all);
            return;
        }
        Object er = Js.get(j, "error");
        if (Js.truthy(er)) { finish(httpError(Js.same(Js.get(er, "code"), 429L) ? 429 : 400, json)); return; }
        Object type = Js.get(j, "type");
        // Anthropic：content_block_start / delta（text_delta / input_json_delta / thinking_delta）、message_stop；整块时 content[]
        Object cb = Js.get(j, "content_block");
        if ("content_block_start".equals(type) && Js.truthy(cb)) {
            anBlocks.set(Js.get(j, "index"), merge(new JSONObject(), cb));
            if ("text".equals(Js.get(cb, "type"))) say(Js.get(cb, "text"));
            return;
        }
        Object dl = Js.get(j, "delta");
        if ("content_block_delta".equals(type) && Js.truthy(dl)) {
            JSONObject bl = anBlocks.get(Js.get(j, "index"));
            Object dt = Js.get(dl, "type");
            if ("text_delta".equals(dt)) {
                say(Js.get(dl, "text"));
                if (bl != null) Js.put(bl, "text", Js.str(Js.or(bl.opt("text"), "")) + Js.str(Js.get(dl, "text")));
            } else if ("input_json_delta".equals(dt)) {
                if (bl != null) Js.put(bl, "partial_json", Js.str(Js.or(bl.opt("partial_json"), "")) + Js.str(Js.or(Js.get(dl, "partial_json"), "")));
            } else if (has(dt, "thinking")) think();
            return;
        }
        if ("response.completed".equals(type)) {
            Object r = Js.get(j, "response");
            if (Js.truthy(r) && Js.get(r, "output") instanceof JSONArray) rsOutput = (JSONArray) Js.get(r, "output");
            finish(null);
            return;
        }
        if ("message_stop".equals(type)) { finish(null); return; }
        // OpenAI Responses：response.output_text.delta、函数调用（output_item.done 里是完整的）、推理摘要、失败
        if ("response.output_text.delta".equals(type)) { say(dl); return; }
        if ("response.output_item.done".equals(type) && Js.truthy(Js.get(j, "item"))) { rsItems.put(Js.get(j, "item")); return; }
        if ("error".equals(type)) { finish(httpError(400, json)); return; } // Responses 流里的错误事件（message 在顶层）
        if ("response.failed".equals(type)) {
            Object r = Js.get(j, "response");
            Object e = Js.truthy(r) ? Js.get(r, "error") : r;
            finish(httpError(400, Json.stringify(Js.truthy(e) ? e : j)));
            return;
        }
        if (type instanceof String && has(type, "reasoning")) { think(); return; }
        Object content = Js.get(j, "content");
        if (all && content instanceof JSONArray) {
            JSONArray a = (JSONArray) content;
            for (int i = 0; i < a.length(); i++) {
                Object b = a.opt(i);
                anBlocks.push(merge(new JSONObject(), b));
                Object bt = Js.get(b, "type");
                if ("text".equals(bt)) say(Js.get(b, "text"));
                else if (has(bt, "thinking")) think();
            }
            return;
        }
        Object output = Js.get(j, "output"), outText = Js.get(j, "output_text");
        if (all && (Js.truthy(outText) || output instanceof JSONArray)) {
            if (output instanceof JSONArray) rsOutput = (JSONArray) output;
            if (outText instanceof String) { say(outText); return; }
            if (!(output instanceof JSONArray)) throw new Js.Err("j.output.forEach is not a function");
            JSONArray a = (JSONArray) output;
            for (int i = 0; i < a.length(); i++) {
                Object o = a.opt(i);
                if ("reasoning".equals(Js.get(o, "type"))) think();
                Object cs = Js.or(Js.get(o, "content"), new JSONArray());
                if (!(cs instanceof JSONArray)) throw new Js.Err("forEach is not a function");
                JSONArray ca = (JSONArray) cs;
                for (int k = 0; k < ca.length(); k++) {
                    Object c = ca.opt(k);
                    if ("output_text".equals(Js.get(c, "type"))) say(Js.get(c, "text"));
                }
            }
            return;
        }
        // Gemini：candidates[0].content.parts[]（thought: true 的是思考）
        Object cands = Js.get(j, "candidates");
        if (Js.truthy(cands)) {
            Object c0 = Js.get(cands, "0"), parts = c0;
            if (Js.truthy(parts)) parts = Js.get(c0, "content");
            if (Js.truthy(parts)) parts = Js.get(parts, "parts");
            if (parts instanceof JSONArray) {
                JSONArray a = (JSONArray) parts;
                for (int i = 0; i < a.length(); i++) {
                    Object p = a.opt(i);
                    if (Js.truthy(Js.get(p, "thought"))) { think(); continue; }
                    if (Js.truthy(Js.get(p, "functionCall")) || Js.get(p, "text") instanceof String || Js.truthy(Js.get(p, "thoughtSignature"))) gemPart(p);
                    say(Js.get(p, "text"));
                }
            }
            return;
        }
        // OpenAI Chat：choices[0].delta / message
        Object ch = Js.get(j, "choices");
        Object c = Js.truthy(ch) ? Js.get(ch, "0") : ch;
        if (!Js.truthy(c)) return;
        Object d = all ? Js.get(c, "message") : Js.get(c, "delta");
        if (!Js.truthy(d)) return;
        if (Js.truthy(Js.get(d, "reasoning_content")) || Js.truthy(Js.get(d, "reasoning"))) think();
        say(Js.get(d, "content"));
        Object tcs = Js.get(d, "tool_calls");
        if (tcs instanceof JSONArray) {
            JSONArray a = (JSONArray) tcs;
            for (int n = 0; n < a.length(); n++) {
                Object tc = a.opt(n), idx = Js.get(tc, "index");
                Object ix = idx instanceof Number ? idx : (Object) (long) n;
                JSONObject cur = chatCalls.get(ix);
                chatCalls.set(ix, merge(cur != null ? cur : new JSONObject(), tc));
            }
        }
        if (d instanceof JSONObject) { // 模型回的别的字段（下次原样带回）
            JSONObject o = (JSONObject) d;
            for (String mk : Js.keys(o)) if (!mk.equals("content") && !mk.equals("tool_calls") && !mk.equals("role")) Js.put(chatMsg, mk, o.opt(mk));
        }
    }

    /** 文字片段并到前一段（思考签名跟着），函数调用原样留着 */
    private void gemPart(Object p) {
        if (!(p instanceof JSONObject)) return;
        JSONObject q = (JSONObject) p, last = gmParts.isEmpty() ? null : gmParts.get(gmParts.size() - 1);
        if (q.opt("text") instanceof String && !Js.truthy(q.opt("functionCall")) && last != null && last.opt("text") instanceof String && !Js.truthy(last.opt("functionCall"))) {
            Js.put(last, "text", (String) last.opt("text") + q.opt("text"));
            for (String k : Js.keys(q)) if (!k.equals("text")) Js.put(last, k, q.opt(k));
        } else gmParts.add(Js.copy(q));
    }

    /** 流式的片段合到一起：字符串接上（参数），对象往里合，别的覆盖 */
    private static JSONObject merge(JSONObject a, Object b) {
        mergeInto(a, b);
        return a;
    }

    private static void mergeInto(Object a, Object b) {
        if (!(b instanceof JSONObject)) return;
        JSONObject o = (JSONObject) b;
        for (String k : Js.keys(o)) {
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            if (k.equals("index")) continue;
            Object ak = a instanceof JSONObject ? ((JSONObject) a).opt(k) : Js.get(a, k);
            if (v instanceof String && ak instanceof String && (k.equals("arguments") || k.equals("partial_json"))) Js.set(a, k, (String) ak + v);
            else if (v instanceof JSONObject && Js.truthy(ak) && isObj(ak)) mergeInto(ak, v);
            else if (v instanceof JSONObject) { JSONObject n = new JSONObject(); Js.set(a, k, n); mergeInto(n, v); }
            else Js.set(a, k, v);
        }
    }

    private String newId() { return "pet_" + Long.toString(System.currentTimeMillis(), 36) + (++gid); }

    private JSONObject collected() {
        JSONArray list = new JSONArray();
        JSONObject raw = null;
        if (fmt.equals("chat") && chatCalls.any()) {
            JSONArray tc = new JSONArray();
            for (JSONObject c : chatCalls.values()) {
                if (c == null) continue;
                if (!Js.truthy(c.opt("id"))) Js.put(c, "id", newId());
                if (!Js.truthy(c.opt("type"))) Js.put(c, "type", "function");
                if (!(c.opt("function") instanceof JSONObject)) Js.put(c, "function", new JSONObject());
                JSONObject fn = (JSONObject) c.opt("function");
                Object args = fn.opt("arguments");
                if (!(args instanceof String)) Js.put(fn, "arguments", Js.truthy(args) ? Json.stringify(args) : "{}");
                tc.put(c);
                list.put(Js.obj("id", c.opt("id"), "name", fn.opt("name"), "args", Js.or(fn.opt("arguments"), "{}")));
            }
            JSONObject d = new JSONObject();
            for (String k : Js.keys(chatMsg)) if (!k.equals("content") && !k.equals("role") && !k.equals("tool_calls") && !k.equals("reasoning_content") && !k.equals("reasoning")) Js.put(d, k, chatMsg.opt(k));
            Js.put(d, "tool_calls", tc);
            raw = Js.obj("f", "chat", "d", d);
        } else if (fmt.equals("responses")) {
            JSONArray items = rsOutput != null ? rsOutput : rsItems;
            for (int i = 0; i < items.length(); i++) {
                Object it = items.opt(i);
                if (!Js.truthy(it) || !"function_call".equals(Js.get(it, "type"))) continue;
                Object a = Js.get(it, "arguments");
                list.put(Js.obj("id", Js.or(Js.get(it, "call_id"), Js.get(it, "id")), "name", Js.get(it, "name"),
                    "args", a instanceof String ? a : Json.stringify(Js.or(a, new JSONObject()))));
            }
            raw = Js.obj("f", "responses", "d", items);
        } else if (fmt.equals("anthropic")) {
            JSONArray blocks = new JSONArray();
            for (JSONObject b : anBlocks.values()) {
                if (b == null) continue;
                Object bt = b.opt("type");
                if ("text".equals(bt)) { if (Js.truthy(b.opt("text"))) blocks.put(Js.obj("type", "text", "text", b.opt("text"))); continue; }
                if (!"tool_use".equals(bt)) continue;
                Object input = b.opt("input"), pj = b.opt("partial_json");
                if (pj instanceof String && !((String) pj).isEmpty()) input = parseArgs(pj);
                Object in = Js.or(input, new JSONObject());
                blocks.put(Js.obj("type", "tool_use", "id", b.opt("id"), "name", b.opt("name"), "input", in));
                list.put(Js.obj("id", b.opt("id"), "name", b.opt("name"), "args", Json.stringify(in)));
            }
            raw = Js.obj("f", "anthropic", "d", blocks);
        } else if (fmt.equals("gemini")) {
            for (JSONObject p : gmParts) {
                Object fc = p.opt("functionCall");
                if (!Js.truthy(fc)) continue;
                if (!Js.truthy(p.opt("_id"))) { Object id = Js.get(fc, "id"); Js.put(p, "_id", Js.truthy(id) ? id : newId()); }
                list.put(Js.obj("id", p.opt("_id"), "name", Js.get(fc, "name"), "args", Json.stringify(Js.or(Js.get(fc, "args"), new JSONObject()))));
            }
            JSONArray d = new JSONArray();
            for (JSONObject p : gmParts) {
                JSONObject o = new JSONObject();
                for (String k : Js.keys(p)) if (!k.equals("_id")) Js.put(o, k, p.opt(k));
                d.put(o);
            }
            raw = Js.obj("f", "gemini", "d", d);
        }
        return list.length() > 0 ? Js.obj("list", list, "raw", raw) : null;
    }

    /* ---------- 错误、请求 ---------- */

    /** HTTP 状态和返回的错误信息 → 能说清楚的几类（各家的错误结构：error.message / error.msg / message） */
    static Err httpError(int status, String text) {
        Object msg;
        try {
            Object j = Json.parse(text);
            if (j instanceof JSONArray) j = ((JSONArray) j).opt(0);
            Object e = Js.truthy(j) ? Js.or(Js.get(j, "error"), j) : j;
            Object m = Js.truthy(e) ? Js.or(Js.get(e, "message"), Js.get(e, "msg"), Js.get(e, "code"), Js.get(e, "type")) : e;
            msg = Js.or(m, "");
        } catch (Json.Bad x) {
            msg = Js.or(text, "");
        }
        String s = Js.head(Js.collapse(Js.str(msg)), 160);
        String code = "bad";
        if (status == 401 || status == 403 || KEY.matcher(s).find()) code = "key";
        else if (status == 402 || BALANCE.matcher(s).find()) code = "balance";
        else if (status == 429) code = "rate";
        else if (status == 404 || MODEL.matcher(s).find()) code = "model";
        else if (status >= 500 || OVERLOADED.matcher(s).find()) code = "server";
        return new Err(code, status, s);
    }

    static JSONObject parseArgs(Object s) {
        try {
            Object o = Json.parse(Js.truthy(s) ? Js.str(s) : "{}");
            return o instanceof JSONObject ? (JSONObject) o : new JSONObject();
        } catch (Json.Bad e) { return new JSONObject(); }
    }

    static final class Req {
        final String url, body;
        final Map<String, String> headers;
        Req(String url, Map<String, String> headers, String body) { this.url = url; this.headers = headers; this.body = body; }
    }

    private static JSONArray rest(JSONArray a, int from) {
        JSONArray r = new JSONArray();
        for (int i = from; i < a.length(); i++) r.put(a.opt(i));
        return r;
    }

    private static JSONArray callsOf(Object m) {
        Object c = Js.get(m, "calls");
        if (!(c instanceof JSONArray)) throw new Js.Err("m.calls.map is not a function");
        return (JSONArray) c;
    }

    private static Object rawOf(Object m, String f) {
        Object r = Js.get(m, "raw");
        return Js.truthy(r) && f.equals(Js.get(r, "f")) ? Js.get(r, "d") : null;
    }

    /* 消息按格式转换。带工具调用的回复：同一种格式回来的用原样（raw），否则按名字和参数拼；工具结果跟各家的写法走 */
    static JSONArray toChat(JSONArray list) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            Object m = list.opt(i), role = Js.get(m, "role");
            if ("tool".equals(role)) { out.put(Js.obj("role", "tool", "tool_call_id", Js.get(m, "id"), "content", Js.get(m, "content"))); continue; }
            if (!Js.truthy(Js.get(m, "calls"))) { out.put(Js.obj("role", role, "content", Js.get(m, "content"))); continue; }
            JSONObject o = new JSONObject();
            Object r = rawOf(m, "chat"); // 原样：tool_calls 和模型回的别的字段（Gemini 兼容接口的思考签名在 tool_calls 里）
            if (Js.truthy(r)) { for (String k : Js.props(r)) Js.put(o, k, Js.get(r, k)); }
            else {
                JSONArray calls = callsOf(m), tc = new JSONArray();
                for (int k = 0; k < calls.length(); k++) {
                    Object c = calls.opt(k);
                    tc.put(Js.obj("id", Js.get(c, "id"), "type", "function", "function", Js.obj("name", Js.get(c, "name"), "arguments", Js.get(c, "args"))));
                }
                Js.put(o, "tool_calls", tc);
            }
            Js.put(o, "role", "assistant");
            Object content = Js.get(m, "content");
            Js.put(o, "content", Js.truthy(content) ? content : JSONObject.NULL);
            out.put(o);
        }
        return out;
    }

    static JSONArray toResponses(JSONArray list) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            Object m = list.opt(i), role = Js.get(m, "role");
            if ("tool".equals(role)) out.put(Js.obj("type", "function_call_output", "call_id", Js.get(m, "id"), "output", Js.get(m, "content")));
            else if (!Js.truthy(Js.get(m, "calls"))) out.put(Js.obj("role", role, "content", Js.get(m, "content")));
            else {
                Object r = Js.get(m, "raw");
                if (Js.truthy(r) && "responses".equals(Js.get(r, "f"))) { // 上次回的 output 原样（推理项、function_call）
                    Object d = Js.get(r, "d");
                    if (d instanceof JSONArray) { JSONArray a = (JSONArray) d; for (int k = 0; k < a.length(); k++) out.put(a.opt(k)); }
                    else out.put(d == null ? JSONObject.NULL : d);
                } else {
                    Object content = Js.get(m, "content");
                    if (Js.truthy(content)) out.put(Js.obj("role", "assistant", "content", content));
                    JSONArray calls = callsOf(m);
                    for (int k = 0; k < calls.length(); k++) {
                        Object c = calls.opt(k);
                        out.put(Js.obj("type", "function_call", "call_id", Js.get(c, "id"), "name", Js.get(c, "name"), "arguments", Js.get(c, "args")));
                    }
                }
            }
        }
        return out;
    }

    private interface Conv { Object[] conv(Object m); }

    /** Anthropic / Gemini：工具结果算用户这一方，和后面的用户消息合成一条（必须一问一答交替） */
    private static JSONArray grouped(JSONArray list, Conv conv, String itemsKey) {
        List<Object[]> out = new ArrayList<Object[]>();
        for (int i = 0; i < list.length(); i++) {
            Object[] x = conv.conv(list.opt(i));
            if (!Js.truthy(Js.get(x[1], "length"))) continue;
            Object[] p = out.isEmpty() ? null : out.get(out.size() - 1);
            if (p != null && Js.same(p[0], x[0])) p[1] = Js.concat(p[1], x[1]); else out.add(x);
        }
        JSONArray r = new JSONArray();
        for (Object[] x : out) r.put(Js.obj("role", x[0], itemsKey, x[1]));
        return r;
    }

    private static JSONArray textItem(Object content, String type) {
        return Js.truthy(content) ? Js.arr(type == null ? Js.obj("text", content) : Js.obj("type", type, "text", content)) : new JSONArray();
    }

    static JSONArray toAnthropic(JSONArray list) {
        return grouped(list, new Conv() {
            @Override public Object[] conv(Object m) {
                Object role = Js.get(m, "role");
                if ("tool".equals(role)) return new Object[] { "user", Js.arr(Js.obj("type", "tool_result", "tool_use_id", Js.get(m, "id"), "content", Js.get(m, "content"))) };
                if (!Js.truthy(Js.get(m, "calls"))) return new Object[] { role, textItem(Js.get(m, "content"), "text") };
                Object r = Js.get(m, "raw");
                if (Js.truthy(r) && "anthropic".equals(Js.get(r, "f"))) return new Object[] { "assistant", Js.get(r, "d") };
                JSONArray calls = callsOf(m), items = new JSONArray();
                for (int k = 0; k < calls.length(); k++) {
                    Object c = calls.opt(k);
                    items.put(Js.obj("type", "tool_use", "id", Js.get(c, "id"), "name", Js.get(c, "name"), "input", parseArgs(Js.get(c, "args"))));
                }
                return new Object[] { "assistant", Js.concat(textItem(Js.get(m, "content"), "text"), items) };
            }
        }, "content");
    }

    static JSONArray toGemini(JSONArray list) {
        return grouped(list, new Conv() {
            @Override public Object[] conv(Object m) {
                Object role0 = Js.get(m, "role");
                if ("tool".equals(role0)) {
                    JSONObject fr = Js.obj("name", Js.get(m, "name"), "response", parseArgs(Js.get(m, "content")));
                    Object id = Js.get(m, "id");
                    if (!Js.str(id).startsWith("pet_")) Js.put(fr, "id", id); // 模型给了调用 id 才带回去（没给的是这边编的）
                    return new Object[] { "user", Js.arr(Js.obj("functionResponse", fr)) };
                }
                String role = "assistant".equals(role0) ? "model" : "user";
                if (!Js.truthy(Js.get(m, "calls"))) return new Object[] { role, textItem(Js.get(m, "content"), null) };
                Object r = Js.get(m, "raw");
                if (Js.truthy(r) && "gemini".equals(Js.get(r, "f"))) return new Object[] { "model", Js.get(r, "d") };
                JSONArray calls = callsOf(m), items = new JSONArray();
                for (int k = 0; k < calls.length(); k++) {
                    Object c = calls.opt(k);
                    items.put(Js.obj("functionCall", Js.obj("name", Js.get(c, "name"), "args", parseArgs(Js.get(c, "args")))));
                }
                return new Object[] { "model", Js.concat(textItem(Js.get(m, "content"), null), items) };
            }
        }, "parts");
    }

    /** 工具定义按格式换写法（Chat Completions 格式进来）；loose：去掉 strict（有的服务商不认） */
    static Object toolsFor(String f, JSONArray tools, boolean loose) {
        List<Object> fns = new ArrayList<Object>();
        for (int i = 0; i < tools.length(); i++) fns.add(Js.get(tools.opt(i), "function"));
        JSONArray r = new JSONArray();
        if (f.equals("anthropic")) {
            for (Object fn : fns) r.put(Js.obj("name", Js.get(fn, "name"), "description", Js.or(Js.get(fn, "description"), ""), "input_schema", Js.get(fn, "parameters")));
            return r;
        }
        if (f.equals("gemini")) {
            JSONArray decl = new JSONArray();
            for (Object fn : fns) decl.put(Js.obj("name", Js.get(fn, "name"), "description", Js.or(Js.get(fn, "description"), ""), "parametersJsonSchema", Js.get(fn, "parameters")));
            return Js.arr(Js.obj("functionDeclarations", decl));
        }
        if (f.equals("responses")) {
            for (Object fn : fns) {
                JSONObject o = Js.obj("type", "function", "name", Js.get(fn, "name"), "description", Js.or(Js.get(fn, "description"), ""), "parameters", Js.get(fn, "parameters"));
                Object st = Js.get(fn, "strict");
                if (!loose && st != null) Js.put(o, "strict", st);
                r.put(o);
            }
            return r;
        }
        if (!loose) return tools;
        for (Object fn : fns) {
            JSONObject o = new JSONObject();
            for (String k : Js.props(fn)) if (!k.equals("strict")) Js.put(o, k, Js.get(fn, k));
            r.put(Js.obj("type", "function", "function", o));
        }
        return r;
    }

    /** 按格式拼请求：full 带字数上限和预设的额外参数（关思考），否则只带必需的；tools 有的话带上（loose 时去掉 strict） */
    private Req request(boolean full, boolean looseTools) {
        String f = fmt, base = PetChat.trimBase(cfg.base), url;
        long max = cfg.maxTokens != 0 ? cfg.maxTokens : 2048;
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        Object sys = messages.length() > 0 && "system".equals(Js.get(messages.opt(0), "role")) ? Js.get(messages.opt(0), "content") : "";
        JSONArray rest = Js.truthy(sys) ? rest(messages, 1) : messages;
        JSONObject body;
        if (f.equals("anthropic")) {
            url = base + "/messages";
            headers.put("x-api-key", Js.str(cfg.key));
            headers.put("anthropic-version", "2023-06-01");
            headers.put("anthropic-dangerous-direct-browser-access", "true");
            body = Js.obj("model", cfg.model, "max_tokens", max, "stream", true, "messages", toAnthropic(rest)); // max_tokens 是必填
            if (Js.truthy(sys)) Js.put(body, "system", sys);
        } else if (f.equals("gemini")) {
            url = base + "/models/" + Js.enc(Js.str(cfg.model)) + ":streamGenerateContent?alt=sse&key=" + Js.enc(Js.str(cfg.key));
            body = Js.obj("contents", toGemini(rest));
            if (Js.truthy(sys)) Js.put(body, "systemInstruction", Js.obj("parts", Js.arr(Js.obj("text", sys))));
            if (full) Js.put(body, "generationConfig", Js.obj("maxOutputTokens", max));
        } else if (f.equals("responses")) {
            url = base + "/responses";
            headers.put("Authorization", "Bearer " + Js.str(cfg.key));
            body = Js.obj("model", cfg.model, "input", toResponses(rest), "stream", true);
            if (Js.truthy(sys)) Js.put(body, "instructions", sys);
            if (full) Js.put(body, "max_output_tokens", max);
        } else {
            url = base + "/chat/completions";
            headers.put("Authorization", "Bearer " + Js.str(cfg.key));
            body = Js.obj("model", cfg.model, "messages", toChat(messages), "stream", true);
            if (full) Js.put(body, "max_tokens", max);
        }
        if (tools != null) {
            Js.put(body, "tools", toolsFor(f, tools, looseTools));
            if (f.equals("chat") || f.equals("responses")) Js.put(body, "tool_choice", "auto");
        }
        // 预设的额外参数：对象合并一层（gemini 的 generationConfig 和上面的字数上限合在一起）
        if (full && cfg.extra != null) for (String k : Js.keys(cfg.extra)) {
            Object v = cfg.extra.opt(k), bk = body.opt(k);
            if (Js.truthy(v) && isObj(v) && Js.truthy(bk) && isObj(bk)) { for (String k2 : Js.props(v)) Js.set(bk, k2, Js.get(v, k2)); }
            else Js.put(body, k, v);
        }
        return new Req(url, headers, Json.stringify(body));
    }

    /** JS 的稀疏数组：下标存在有序表里，别的键（JS 里是数组的普通属性）另放，不参与遍历 */
    private static final class Sparse {
        private final TreeMap<Long, JSONObject> slots = new TreeMap<Long, JSONObject>();
        private final Map<String, JSONObject> props = new HashMap<String, JSONObject>();

        JSONObject get(Object k) {
            String s = Js.str(k);
            return Js.isIndex(s) ? slots.get(Long.parseLong(s)) : props.get(s);
        }

        void set(Object k, JSONObject v) {
            String s = Js.str(k);
            if (Js.isIndex(s)) slots.put(Long.parseLong(s), v); else props.put(s, v);
        }

        void push(JSONObject v) { slots.put(slots.isEmpty() ? 0L : slots.lastKey() + 1, v); }

        boolean any() { return !slots.isEmpty(); }

        Iterable<JSONObject> values() { return slots.values(); }
    }
}
