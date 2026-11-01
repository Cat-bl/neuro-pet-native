package com.x3card.pet.ai;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 联网搜索：自己调搜索接口（和用哪家大模型、哪种接口格式无关）。模型写 〔search 关键词〕（或调 pet_web_search），搜完把结果再交给模型回答。
 * 四家返回统一成 Result（title / url / text / site / date）：
 *   bocha   博查  POST https://api.bochaai.com/v1/web-search  → data.webPages.value[]（name / url / summary / snippet / siteName / datePublished）
 *   metaso  秘塔  POST https://metaso.cn/api/v1/search  → webpages[]（title / link / summary / snippet / date）；Key 不对时也回 HTTP 200，错误在 code / message
 *   tavily  Tavily POST https://api.tavily.com/search  → results[]（title / url / content / published_date）
 *   jina    Jina  GET https://s.jina.ai/?q=  Accept: application/json，X-Respond-With: no-content  → data[]（title / url / description）
 * 都是 Authorization: Bearer。回调在调用方给的 Executor 上调。
 */
public final class PetSearch {
    private PetSearch() {}

    static final int N = 5;
    /** 整个请求的超时（毫秒） */
    static int TIMEOUT = 12000;

    public static final class Provider {
        public final String id, name, site;
        Provider(String id, String name, String site) { this.id = id; this.name = name; this.site = site; }
    }

    public static final List<Provider> PROVIDERS = Collections.unmodifiableList(Arrays.asList(
        new Provider("bocha", "博查", "open.bochaai.com"),
        new Provider("metaso", "秘塔", "metaso.cn"),
        new Provider("tavily", "Tavily", "tavily.com"),
        new Provider("jina", "Jina", "jina.ai")));

    public static Provider find(String id) {
        for (Provider p : PROVIDERS) if (p.id.equals(id)) return p;
        return PROVIDERS.get(0);
    }

    public static final class Cfg {
        public String id, key;
        public Cfg() {}
        public Cfg(String id, String key) { this.id = id; this.key = key; }
    }

    /** 一条结果；text / site / date 没有时是 null */
    public static final class Result {
        public final String title, url, text, site, date;
        Result(String title, String url, String text, String site, String date) { this.title = title; this.url = url; this.text = text; this.site = site; this.date = date; }

        public JSONObject toJSON() { return Js.obj("title", title, "url", url, "text", text, "site", site, "date", date); }
    }

    /** 各家的请求 */
    private static final class Req {
        String url, body;
        boolean get;
        final Map<String, String> headers = new LinkedHashMap<String, String>();
    }

    private static Req req(String id, String q) {
        Req r = new Req();
        if (id.equals("metaso")) {
            r.url = "https://metaso.cn/api/v1/search";
            r.body = Json.stringify(Js.obj("q", q, "scope", "webpage", "includeSummary", true, "size", (long) N));
        } else if (id.equals("tavily")) {
            r.url = "https://api.tavily.com/search";
            r.body = Json.stringify(Js.obj("query", q, "max_results", (long) N, "search_depth", "basic", "topic", "general"));
        } else if (id.equals("jina")) {
            r.url = "https://s.jina.ai/?q=" + Js.enc(Js.str(q)) + "&num=" + N;
            r.get = true;
            r.headers.put("Accept", "application/json");
            r.headers.put("X-Respond-With", "no-content");
        } else {
            r.url = "https://api.bochaai.com/v1/web-search";
            r.body = Json.stringify(Js.obj("query", q, "summary", true, "count", (long) N, "freshness", "noLimit"));
        }
        return r;
    }

    /** (w && w.value) || [] 这类：真值但不是数组时 JS 的 .map 会报错 */
    private static JSONArray listOf(Object v) {
        if (!Js.truthy(v)) return new JSONArray();
        if (!(v instanceof JSONArray)) throw new Js.Err("map is not a function");
        return (JSONArray) v;
    }

    /** 各家的结果 → [title, url, text, site, date]（原样的值） */
    private static List<Object[]> list(String id, Object j) {
        List<Object[]> r = new ArrayList<Object[]>();
        JSONArray a;
        if (id.equals("metaso")) {
            a = listOf(Js.get(j, "webpages"));
            for (int i = 0; i < a.length(); i++) { Object x = a.opt(i); r.add(new Object[] { Js.get(x, "title"), Js.get(x, "link"), Js.or(Js.get(x, "summary"), Js.get(x, "snippet")), null, Js.get(x, "date") }); }
        } else if (id.equals("tavily")) {
            a = listOf(Js.get(j, "results"));
            for (int i = 0; i < a.length(); i++) { Object x = a.opt(i); r.add(new Object[] { Js.get(x, "title"), Js.get(x, "url"), Js.get(x, "content"), null, Js.get(x, "published_date") }); }
        } else if (id.equals("jina")) {
            Object d = Js.get(j, "data");
            a = d instanceof JSONArray ? (JSONArray) d : new JSONArray();
            for (int i = 0; i < a.length(); i++) { Object x = a.opt(i); r.add(new Object[] { Js.get(x, "title"), Js.get(x, "url"), Js.or(Js.get(x, "description"), Js.get(x, "content")), null, null }); }
        } else {
            Object data = Js.get(j, "data");
            Object w = Js.or(Js.truthy(data) ? Js.get(data, "webPages") : data, Js.get(j, "webPages"));
            a = listOf(Js.truthy(w) ? Js.get(w, "value") : w);
            for (int i = 0; i < a.length(); i++) {
                Object x = a.opt(i);
                r.add(new Object[] { Js.get(x, "name"), Js.get(x, "url"), Js.or(Js.get(x, "summary"), Js.get(x, "snippet")), Js.get(x, "siteName"), Js.get(x, "datePublished") });
            }
        }
        return r;
    }

    private static String opt(Object v) { return Js.truthy(v) ? Js.str(v) : null; }

    private static final Pattern KEY = Pattern.compile("key|unauthori|鉴权|认证|token", Pattern.CASE_INSENSITIVE);
    private static final Pattern BALANCE = Pattern.compile("balance|余额|额度|credit|quota|insufficient", Pattern.CASE_INSENSITIVE);

    // 各家的错误：HTTP 状态，或者 200 里带着 code / success:false / detail / message
    static Err fail(int status, Object j, String text) {
        Object e;
        if (!Js.truthy(j)) e = j;
        else {
            Object det = Js.get(j, "detail");
            Object a = Js.truthy(det) ? Js.or(Js.get(det, "error"), det) : det;
            e = Js.or(a, Js.get(j, "error"), j);
        }
        Object m = Js.truthy(e) ? Js.or(Js.get(e, "message"), Js.get(e, "msg"), Js.get(e, "readableMessage"), e instanceof String ? e : "") : e;
        String msg = Js.head(Js.collapse(Js.str(Js.or(m, text, ""))), 120);
        String code = "bad";
        if (status == 401 || status == 403 || KEY.matcher(msg).find()) code = "key";
        else if (status == 402 || BALANCE.matcher(msg).find()) code = "balance";
        else if (status == 429) code = "rate";
        return new Err(code, status, msg);
    }

    /** 收到的响应 → 最多 5 条结果；出错抛 Fail */
    static List<Result> read(String id, int status, String t) {
        Object j = null;
        try { j = Json.parse(t); } catch (Json.Bad e) { /* 不是 JSON */ }
        List<Result> res = new ArrayList<Result>();
        if (Js.truthy(j)) for (Object[] x : list(id, j)) if (Js.truthy(x[0]) && Js.truthy(x[1])) res.add(new Result(Js.str(x[0]), Js.str(x[1]), opt(x[2]), opt(x[3]), opt(x[4])));
        boolean refused = false;
        if (Js.truthy(j)) {
            Object code = Js.get(j, "code");
            refused = Js.same(Js.get(j, "success"), Boolean.FALSE)
                || (code != null && !Js.same(code, 0L) && !Js.same(code, 200L) && !Js.same(code, "200") && !Js.same(code, "0"));
        }
        if (status < 200 || status > 299 || !Js.truthy(j) || (refused && res.isEmpty())) throw new Fail(fail(status, j, t));
        return res.size() > N ? new ArrayList<Result>(res.subList(0, N)) : res;
    }

    static final class Fail extends RuntimeException {
        final Err err;
        Fail(Err err) { super(err.toString()); this.err = err; }
    }

    /** 搜一个关键词，最多 5 条 */
    public static void search(Cfg cfg, String q, final Executor ex, final Done<List<Result>> cb) {
        final String id = find(cfg.id).id;
        final Req r = req(id, q);
        r.headers.put("Authorization", "Bearer " + Js.str(cfg.key));
        if (!r.get) r.headers.put("Content-Type", "application/json");
        final boolean[] settled = { false };
        final HttpURLConnection[] conn = { null };
        final ScheduledFuture<?> timer = Http.later(TIMEOUT, new Runnable() {
            @Override public void run() {
                ex.execute(new Runnable() {
                    @Override public void run() {
                        if (settled[0]) return;
                        settled[0] = true;
                        synchronized (conn) { Http.drop(conn[0]); conn[0] = null; }
                        cb.fail(new Err("timeout", 0, ""));
                    }
                });
            }
        });
        Http.IO.execute(new Runnable() {
            @Override public void run() {
                Err err = null;
                List<Result> list = null;
                HttpURLConnection c = null;
                int status = 0;
                try {
                    c = Http.send(r.url, r.get ? "GET" : "POST", r.headers, r.get ? null : r.body, TIMEOUT + 5000, TIMEOUT + 5000);
                    synchronized (conn) { conn[0] = c; }
                    status = c.getResponseCode();
                } catch (IOException e) { err = new Err("net", 0, Js.head(Http.why(e), 120)); }
                catch (RuntimeException e) { err = new Err("net", 0, Js.head(Http.why(e), 120)); }
                if (err == null) {
                    try { list = read(id, status, Http.readText(Http.body(c, status))); }
                    catch (Fail e) { err = e.err; }
                    catch (Exception e) { err = new Err("bad", 0, Js.head(Http.why(e), 120)); }
                }
                if (c != null) c.disconnect();
                final Err e2 = err;
                final List<Result> l2 = list;
                ex.execute(new Runnable() {
                    @Override public void run() {
                        if (settled[0]) return;
                        settled[0] = true;
                        Http.cancel(timer);
                        if (e2 != null) cb.fail(e2); else cb.ok(l2);
                    }
                });
            }
        });
    }

    private static final Pattern HOST = Pattern.compile("^https?://([^/]+)" + Js.DOT + "*\\z");

    /** 给模型看的搜索结果（每条摘要截到 220 字，整体不超过 1600 字） */
    public static String format(String q, List<Result> list) {
        if (list.isEmpty()) return "[搜索结果] 关键词\"" + q + "\"没搜到东西。跟车主说没查到，别编。";
        StringBuilder out = new StringBuilder("[搜索结果] 关键词\"" + q + "\"：\n");
        for (int i = 0; i < list.size(); i++) {
            Result x = list.get(i);
            String t = Js.trim(Js.collapse(x.text == null ? "" : x.text));
            if (t.length() > 220) t = t.substring(0, 219) + "…";
            String src = x.site;
            if (src == null) {
                Matcher m = HOST.matcher(x.url);
                src = m.find() ? m.group(1) : x.url;
            }
            out.append(i + 1).append(". ").append(x.title).append(x.date != null ? "（" + Js.head(x.date, 10) + "）" : "")
                .append(" — ").append(t).append("（来源：").append(src).append("）\n");
        }
        return Js.head(out.toString(), 1600) + "根据这些结果回答车主刚才的问题，用自己的话简短说，可以顺口说是从哪儿查到的；结果里没有答案就说没查到，别编。";
    }

    /** 错误说明；onCar：在车上（连不上时提一句卡片的联网权限） */
    public static String errText(Err e, boolean onCar) {
        String m = e != null && !e.msg.isEmpty() ? "（" + e.msg + "）" : "";
        String code = e == null ? "" : e.code == null ? "" : e.code;
        switch (code) {
            case "net": return "连不上搜索服务" + (onCar ? "（先看看这张卡片的「联网」权限开了没有）" : "") + m;
            case "timeout": return "搜索太久没回应";
            case "key": return "搜索 Key 不对" + m;
            case "balance": return "搜索额度用完了" + m;
            case "rate": return "搜索太频繁，等一会儿" + m;
            default: return "搜索出错了" + (e != null && e.status != 0 && e.status != 200 ? " " + e.status : "") + m; // 秘塔出错也回 200，不显示
        }
    }
}
