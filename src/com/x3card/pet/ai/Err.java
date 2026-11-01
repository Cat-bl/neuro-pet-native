package com.x3card.pet.ai;

import org.json.JSONObject;

/**
 * 请求出错：code 是哪一类，status 是 HTTP 状态（没有是 0），msg 是服务商给的说明（截短过，可能是空的）。
 * 大模型：net / timeout / key / balance / rate / model / server / bad / notools；搜索：net / timeout / key / balance / rate / bad。
 */
public final class Err {
    public final String code;
    public final int status;
    public final String msg;

    public Err(String code, int status, String msg) {
        this.code = code;
        this.status = status;
        this.msg = msg == null ? "" : msg;
    }

    public JSONObject toJSON() { return Js.obj("code", code, "status", (long) status, "msg", msg); }

    @Override public String toString() { return code + " " + status + " " + msg; }
}
