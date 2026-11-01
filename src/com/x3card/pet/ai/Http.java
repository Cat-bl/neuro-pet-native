package com.x3card.pet.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 联网用的后台线程、定时器和几个小工具 */
final class Http {
    private Http() {}

    private static ThreadFactory named(final String name) {
        final AtomicInteger n = new AtomicInteger();
        return new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    /** 连接、读数据（会一直等着网络） */
    static final ExecutorService IO = Executors.newCachedThreadPool(named("pet-ai-io"));
    /** 超时用的定时器：到点只往调用方的 Executor 上排一个任务 */
    private static final ScheduledThreadPoolExecutor TIMER;
    static {
        TIMER = new ScheduledThreadPoolExecutor(1, named("pet-ai-timer"));
        TIMER.setRemoveOnCancelPolicy(true);
    }

    static ScheduledFuture<?> later(int ms, Runnable r) { return TIMER.schedule(r, Math.max(0, ms), TimeUnit.MILLISECONDS); }

    static void cancel(ScheduledFuture<?> f) { if (f != null) f.cancel(false); }

    /** 建连接、发请求头和请求体（body 为 null 时是 GET）；没指定 Accept 时同浏览器发 *&#47;* */
    static HttpURLConnection send(String url, String method, Map<String, String> headers, String body, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setUseCaches(false);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        boolean accept = false;
        for (String k : headers.keySet()) if ("accept".equalsIgnoreCase(k)) accept = true;
        if (!accept) c.setRequestProperty("Accept", "*/*");
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            try { os.write(b); } finally { os.close(); }
        }
        return c;
    }

    /** 响应体（出错的状态码读错误流；可能没有） */
    static InputStream body(HttpURLConnection c, int status) throws IOException {
        return status >= 200 && status <= 299 ? c.getInputStream() : c.getErrorStream();
    }

    static String readText(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            Dec d = new Dec();
            return d.push(o.toByteArray(), true);
        } finally { in.close(); }
    }

    /** 断开连接（放到后台线程：主线程上关 TLS 连接可能要写网络） */
    static void drop(final HttpURLConnection c) {
        if (c == null) return;
        IO.execute(new Runnable() { @Override public void run() { try { c.disconnect(); } catch (RuntimeException e) { /* 已经断了 */ } } });
    }

    /** JS 里 String(e && e.message || e) */
    static String why(Throwable e) {
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.toString();
    }

    /** 流式 UTF-8 解码，同 TextDecoder（坏字节换成 U+FFFD，开头的 BOM 去掉） */
    static final class Dec {
        private final CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        private byte[] left = new byte[0];
        private boolean started;

        String push(byte[] b, boolean end) {
            byte[] all = left.length == 0 ? b : concat(left, b);
            ByteBuffer in = ByteBuffer.wrap(all);
            CharBuffer out = CharBuffer.allocate(all.length + 8);
            d.decode(in, out, end);
            if (end) d.flush(out);
            left = Arrays.copyOfRange(all, in.position(), all.length);
            out.flip();
            String s = out.toString();
            if (!started && !s.isEmpty()) {
                started = true;
                if (s.charAt(0) == 0xFEFF) s = s.substring(1);
            }
            return s;
        }

        private static byte[] concat(byte[] a, byte[] b) {
            byte[] r = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, r, a.length, b.length);
            return r;
        }
    }
}
