package com.x3card.pet;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** 读文件的小工具 */
final class Io {
    private Io() {}

    static byte[] readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(4096, in.available()));
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            return o.toByteArray();
        } finally { in.close(); }
    }

    static String text(android.content.Context c, String asset) throws java.io.IOException {
        return new String(readAll(c.getAssets().open(asset)), "UTF-8");
    }
}
